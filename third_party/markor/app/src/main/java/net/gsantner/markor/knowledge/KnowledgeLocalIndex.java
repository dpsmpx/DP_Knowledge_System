/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A completely disposable, local-only SQLite FTS4 projection of a Markdown vault.
 *
 * The database lives in noBackupFilesDir: neither Android cloud backup nor
 * Git can accidentally treat this derived index as an authoritative vault.
 * Sync commits atomically, and cancellation rolls back the complete batch.
 */
public final class KnowledgeLocalIndex {
    public static final int MAX_INDEX_BYTES_PER_NOTE = 2_000_000;
    private static final String DB_NAME = "knowledge_index_v1.sqlite";
    private static final int SCHEMA_VERSION = 1;

    public static final class Hit {
        public final String relativePath;
        public final String title;

        private Hit(final String relativePath, final String title) {
            this.relativePath = relativePath;
            this.title = title;
        }
    }

    public static final class SyncResult {
        public final int indexed;
        public final int reused;
        public final int oversized;
        public final int removed;
        public final boolean keptMissingEntries;

        private SyncResult(final int indexed, final int reused, final int oversized,
                           final int removed, final boolean keptMissingEntries) {
            this.indexed = indexed;
            this.reused = reused;
            this.oversized = oversized;
            this.removed = removed;
            this.keptMissingEntries = keptMissingEntries;
        }
    }

    private final File databaseFile;

    public KnowledgeLocalIndex(final Context context) {
        databaseFile = new File(context.getApplicationContext().getNoBackupFilesDir(), DB_NAME);
    }

    public synchronized SyncResult synchronize(final File root,
                                                final KnowledgeVaultScanner.Snapshot snapshot,
                                                final KnowledgeVaultScanner.Cancellation cancellation)
            throws IOException {
        final String canonicalRoot = root.getCanonicalPath();
        final String generation = UUID.randomUUID().toString();
        final SQLiteDatabase db = open();
        int indexed = 0;
        int reused = 0;
        int oversized = 0;
        int removed = 0;
        db.beginTransaction();
        try {
            final String previousRoot = getMeta(db, "root");
            if (previousRoot != null && !previousRoot.equals(canonicalRoot)) {
                // The old derived projection belongs to another user-selected vault.
                db.execSQL("DELETE FROM links");
                db.execSQL("DELETE FROM documents_fts");
                db.execSQL("DELETE FROM notes");
            }
            setMeta(db, "root", canonicalRoot);

            for (final KnowledgeVaultScanner.Note note : snapshot.notes) {
                checkCancelled(cancellation);
                final long existingId;
                final long existingModified;
                final long existingSize;
                try (Cursor c = db.rawQuery(
                        "SELECT id, modified, size FROM notes WHERE path=?",
                        new String[]{note.relativePath})) {
                    if (c.moveToFirst()) {
                        existingId = c.getLong(0);
                        existingModified = c.getLong(1);
                        existingSize = c.getLong(2);
                    } else {
                        existingId = -1;
                        existingModified = -1;
                        existingSize = -1;
                    }
                }
                if (existingId >= 0 && existingModified == note.modifiedAtMillis
                        && existingSize == note.sizeBytes) {
                    db.execSQL("UPDATE notes SET generation=? WHERE id=?",
                            new Object[]{generation, existingId});
                    reused++;
                    continue;
                }

                // A note may grow between discovery and indexing. In that case,
                // retain its filename and postpone content indexing to a rescan.
                final String bounded = note.sizeBytes > MAX_INDEX_BYTES_PER_NOTE
                        ? null : readBounded(note.file, cancellation);
                final boolean tooLarge = bounded == null;
                final String text = tooLarge ? "" : bounded;
                final KnowledgeMarkdownMetadata.Parsed parsed =
                        KnowledgeMarkdownMetadata.parse(note.relativePath, text);
                final String title = tooLarge
                        ? KnowledgeMarkdownMetadata.filenameTitle(note.relativePath) : parsed.title;
                final ContentValues values = new ContentValues();
                values.put("path", note.relativePath);
                values.put("modified", note.modifiedAtMillis);
                values.put("size", note.sizeBytes);
                values.put("title", title);
                values.put("oversized", tooLarge ? 1 : 0);
                values.put("generation", generation);
                final long rowId;
                if (existingId < 0) {
                    rowId = db.insertOrThrow("notes", null, values);
                } else {
                    rowId = existingId;
                    db.update("notes", values, "id=?", new String[]{Long.toString(rowId)});
                    db.execSQL("DELETE FROM documents_fts WHERE docid=?", new Object[]{rowId});
                    db.delete("links", "source_path=?", new String[]{note.relativePath});
                }
                db.execSQL("INSERT INTO documents_fts(docid, path, title, body) VALUES(?,?,?,?)",
                        new Object[]{rowId, note.relativePath, title, text});
                for (final String link : parsed.possibleLinks) {
                    db.execSQL("INSERT OR IGNORE INTO links(source_path, target_key) VALUES(?,?)",
                            new Object[]{note.relativePath, link});
                }
                indexed++;
                if (tooLarge) {
                    oversized++;
                }
            }

            // A partial scan is never evidence that previously indexed notes
            // were deleted. Only reconcile removals after a complete traversal.
            final boolean incomplete = snapshot.truncated || snapshot.unreadableDirectories > 0;
            if (!incomplete) {
                final List<Long> ids = new ArrayList<>();
                final List<String> paths = new ArrayList<>();
                try (Cursor c = db.rawQuery("SELECT id, path FROM notes WHERE generation<>?",
                        new String[]{generation})) {
                    while (c.moveToNext()) {
                        ids.add(c.getLong(0));
                        paths.add(c.getString(1));
                    }
                }
                for (int i = 0; i < ids.size(); i++) {
                    checkCancelled(cancellation);
                    db.execSQL("DELETE FROM documents_fts WHERE docid=?",
                            new Object[]{ids.get(i)});
                    db.delete("links", "source_path=?", new String[]{paths.get(i)});
                    db.delete("notes", "id=?", new String[]{Long.toString(ids.get(i))});
                    removed++;
                }
            }
            checkCancelled(cancellation);
            db.setTransactionSuccessful();
            return new SyncResult(indexed, reused, oversized, removed, incomplete);
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    public synchronized List<Hit> search(final String input, final int limit) {
        final List<Hit> hits = new ArrayList<>();
        final SQLiteDatabase db = open();
        try {
            final String safe = KnowledgeMarkdownMetadata.ftsQuery(input);
            final String sql;
            final String[] arguments;
            if (safe.isEmpty()) {
                sql = "SELECT path, title FROM notes ORDER BY path COLLATE NOCASE LIMIT ?";
                arguments = new String[]{Integer.toString(limit)};
            } else {
                sql = "SELECT notes.path, notes.title FROM documents_fts JOIN notes "
                        + "ON documents_fts.docid=notes.id "
                        + "WHERE documents_fts MATCH ? ORDER BY notes.path COLLATE NOCASE LIMIT ?";
                arguments = new String[]{safe, Integer.toString(limit)};
            }
            try (Cursor c = db.rawQuery(sql, arguments)) {
                while (c.moveToNext()) {
                    hits.add(new Hit(c.getString(0), c.getString(1)));
                }
            }
            return hits;
        } finally {
            db.close();
        }
    }

    /** Only possible backlinks: paths and wiki aliases can be ambiguous. */
    public synchronized List<String> possibleBacklinks(final String relativePath, final int limit) {
        final String full = KnowledgeMarkdownMetadata.linkKey(relativePath);
        final String shortName = KnowledgeMarkdownMetadata.linkKey(
                KnowledgeMarkdownMetadata.filenameTitle(relativePath));
        final List<String> sources = new ArrayList<>();
        final SQLiteDatabase db = open();
        try (Cursor c = db.rawQuery("SELECT DISTINCT source_path FROM links "
                        + "WHERE target_key=? OR target_key=? ORDER BY source_path LIMIT ?",
                new String[]{full, shortName, Integer.toString(limit)})) {
            while (c.moveToNext()) {
                sources.add(c.getString(0));
            }
        } finally {
            db.close();
        }
        return sources;
    }

    private static String readBounded(final File file,
                                      final KnowledgeVaultScanner.Cancellation cancellation)
            throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                checkCancelled(cancellation);
                if (bytes.size() + count > MAX_INDEX_BYTES_PER_NOTE) {
                    return null;
                }
                bytes.write(buffer, 0, count);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private SQLiteDatabase open() {
        final SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        try (Cursor c = db.rawQuery("PRAGMA user_version", null)) {
            c.moveToFirst();
            final int version = c.getInt(0);
            if (version > SCHEMA_VERSION) {
                db.close();
                throw new IllegalStateException("Newer knowledge index schema is not supported");
            }
            if (version < SCHEMA_VERSION) {
                db.execSQL("DROP TABLE IF EXISTS links");
                db.execSQL("DROP TABLE IF EXISTS documents_fts");
                db.execSQL("DROP TABLE IF EXISTS notes");
                db.execSQL("DROP TABLE IF EXISTS meta");
            }
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "path TEXT NOT NULL UNIQUE, modified INTEGER NOT NULL, size INTEGER NOT NULL,"
                + "title TEXT NOT NULL, oversized INTEGER NOT NULL, generation TEXT NOT NULL)");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts "
                + "USING fts4(path, title, body, tokenize=unicode61)");
        db.execSQL("CREATE TABLE IF NOT EXISTS links (source_path TEXT NOT NULL,"
                + "target_key TEXT NOT NULL, PRIMARY KEY(source_path, target_key))");
        db.execSQL("CREATE INDEX IF NOT EXISTS links_by_target ON links(target_key)");
        db.execSQL("PRAGMA user_version=" + SCHEMA_VERSION);
        return db;
    }

    private static String getMeta(final SQLiteDatabase db, final String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static void setMeta(final SQLiteDatabase db, final String key, final String value) {
        db.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES(?,?)",
                new Object[]{key, value});
    }

    private static void checkCancelled(final KnowledgeVaultScanner.Cancellation cancellation)
            throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()
                || (cancellation != null && cancellation.isCancelled())) {
            throw new InterruptedIOException("Index update cancelled");
        }
    }
}
