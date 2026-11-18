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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
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
    private static final int SCHEMA_VERSION = 3;
    private static final int MAX_SEMANTIC_CANDIDATES = 45_000;

    public interface Progress {
        void onProgress(int processed, int total);
    }

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

    public static final class SemanticHit {
        public final String relativePath;
        public final String title;
        public final int excerptStart;
        public final int excerptLength;
        public final double score;
        public final long modifiedAtMillis;
        public final long sizeBytes;

        private SemanticHit(final String path, final String displayTitle, final int offset,
                            final int length, final double similarity,
                            final long modified, final long size) {
            relativePath = path;
            title = displayTitle;
            excerptStart = offset;
            excerptLength = length;
            score = similarity;
            modifiedAtMillis = modified;
            sizeBytes = size;
        }
    }

    /** Read-only, count-only diagnostics; no source paths or private note text. */
    public static final class IndexHealth {
        public final String quickCheck;
        public final int sources;
        public final int textRows;
        public final int missingTextRows;
        public final int orphanTextRows;
        public final int links;
        public final int embeddedNotes;
        public final int embeddedChunks;
        public final int lshRows;
        public final int incompleteEmbeddings;
        public final int truncatedEmbeddings;
        public final int orphanEmbeddingNotes;
        public final int orphanChunks;
        public final int orphanLsh;
        public final long lastScanMillis;
        public final int lastScanCount;
        public final boolean lastScanIncomplete;
        public final int unreadableDirectories;

        private IndexHealth(final SQLiteDatabase db) {
            String check = "unknown";
            try (Cursor c = db.rawQuery("PRAGMA quick_check(1)", null)) {
                if (c.moveToFirst()) {
                    check = c.getString(0);
                }
            }
            quickCheck = check == null ? "unknown" : check;
            sources = count(db, "SELECT COUNT(*) FROM notes");
            textRows = count(db, "SELECT COUNT(*) FROM documents_fts");
            missingTextRows = count(db, "SELECT COUNT(*) FROM notes n "
                    + "LEFT JOIN documents_fts f ON f.docid=n.id WHERE f.docid IS NULL");
            orphanTextRows = count(db, "SELECT COUNT(*) FROM documents_fts f "
                    + "LEFT JOIN notes n ON n.id=f.docid WHERE n.id IS NULL");
            links = count(db, "SELECT COUNT(*) FROM links");
            embeddedNotes = count(db, "SELECT COUNT(*) FROM embedding_notes");
            embeddedChunks = count(db, "SELECT COUNT(*) FROM embedding_chunks");
            lshRows = count(db, "SELECT COUNT(*) FROM embedding_lsh");
            incompleteEmbeddings = count(db, "SELECT COUNT(*) FROM embedding_notes "
                    + "WHERE completed<chunks");
            truncatedEmbeddings = count(db, "SELECT COUNT(*) FROM embedding_notes "
                    + "WHERE truncated<>0");
            orphanEmbeddingNotes = count(db, "SELECT COUNT(*) FROM embedding_notes e "
                    + "LEFT JOIN notes n ON n.path=e.path WHERE n.path IS NULL "
                    + "OR n.modified<>e.modified OR n.size<>e.size");
            orphanChunks = count(db, "SELECT COUNT(*) FROM embedding_chunks v "
                    + "LEFT JOIN embedding_notes e ON e.path=v.path WHERE e.path IS NULL "
                    + "OR v.chunk>=e.completed");
            orphanLsh = count(db, "SELECT COUNT(*) FROM embedding_lsh l "
                    + "LEFT JOIN embedding_chunks v ON v.path=l.path AND v.chunk=l.chunk "
                    + "WHERE v.path IS NULL");
            lastScanMillis = parseLong(getMeta(db, "last_scan_ms"));
            lastScanCount = (int) parseLong(getMeta(db, "last_scan_count"));
            lastScanIncomplete = "1".equals(getMeta(db, "last_scan_incomplete"));
            unreadableDirectories = (int) parseLong(getMeta(db, "last_scan_unreadable"));
        }

        public boolean isHealthy() {
            return "ok".equals(quickCheck) && sources == textRows
                    && missingTextRows == 0 && orphanTextRows == 0
                    && orphanEmbeddingNotes == 0 && orphanChunks == 0 && orphanLsh == 0
                    && lshRows == (long) embeddedChunks * KnowledgeEmbeddingLsh.TABLES;
        }
    }

    private final File databaseFile;

    public KnowledgeLocalIndex(final Context context) {
        databaseFile = new File(context.getApplicationContext().getNoBackupFilesDir(), DB_NAME);
    }

    /** Isolated synthetic QA index. Never point this constructor at a real vault index. */
    KnowledgeLocalIndex(final File syntheticDatabaseFile) {
        databaseFile = syntheticDatabaseFile;
    }

    /** Does not create or open the user's derived index. */
    public boolean hasDerivedIndex() {
        return databaseFile.isFile();
    }

    public synchronized SyncResult synchronize(final File root,
                                                final KnowledgeVaultScanner.Snapshot snapshot,
                                                final KnowledgeVaultScanner.Cancellation cancellation)
            throws IOException {
        return synchronize(root, snapshot, cancellation, null);
    }

    public synchronized SyncResult synchronize(final File root,
                                                final KnowledgeVaultScanner.Snapshot snapshot,
                                                final KnowledgeVaultScanner.Cancellation cancellation,
                                                final Progress progress)
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
                db.execSQL("DELETE FROM embedding_lsh");
                db.execSQL("DELETE FROM embedding_chunks");
                db.execSQL("DELETE FROM embedding_notes");
                db.execSQL("DELETE FROM links");
                db.execSQL("DELETE FROM documents_fts");
                db.execSQL("DELETE FROM notes");
            }
            setMeta(db, "root", canonicalRoot);

            int processed = 0;
            for (final KnowledgeVaultScanner.Note note : snapshot.notes) {
                checkCancelled(cancellation);
                processed++;
                if (progress != null && (processed % 512 == 0
                        || processed == snapshot.notes.size())) {
                    progress.onProgress(processed, snapshot.notes.size());
                }
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
                    db.delete("embedding_lsh", "path=?", new String[]{note.relativePath});
                    db.delete("embedding_chunks", "path=?", new String[]{note.relativePath});
                    db.delete("embedding_notes", "path=?", new String[]{note.relativePath});
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
                    db.delete("embedding_lsh", "path=?", new String[]{paths.get(i)});
                    db.delete("embedding_chunks", "path=?", new String[]{paths.get(i)});
                    db.delete("embedding_notes", "path=?", new String[]{paths.get(i)});
                    db.delete("notes", "id=?", new String[]{Long.toString(ids.get(i))});
                    removed++;
                }
            }
            checkCancelled(cancellation);
            // These markers share the index transaction: a killed process can
            // never claim a partially indexed scan completed successfully.
            setMeta(db, "last_scan_ms", Long.toString(System.currentTimeMillis()));
            setMeta(db, "last_scan_count", Integer.toString(snapshot.notes.size()));
            setMeta(db, "last_scan_incomplete", incomplete ? "1" : "0");
            setMeta(db, "last_scan_truncated", snapshot.truncated ? "1" : "0");
            setMeta(db, "last_scan_unreadable", Integer.toString(snapshot.unreadableDirectories));
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

    /** Retrieve possible source notes for a model, still entirely local/read-only. */
    public synchronized List<Hit> evidenceSearch(final String question, final int limit) {
        final List<Hit> result = new ArrayList<>();
        if (question == null || limit < 1 || limit > 40) {
            return result;
        }
        final String safe = KnowledgeMarkdownMetadata.ftsEvidenceQuery(question);
        if (safe.isEmpty()) {
            return result;
        }
        final SQLiteDatabase db = open();
        try (Cursor c = db.rawQuery("SELECT notes.path, notes.title FROM documents_fts "
                        + "JOIN notes ON notes.id=documents_fts.docid "
                        + "WHERE documents_fts MATCH ? AND notes.oversized=0 "
                        + "ORDER BY notes.path COLLATE NOCASE LIMIT ?",
                new String[]{safe, Integer.toString(limit)})) {
            while (c.moveToNext()) {
                result.add(new Hit(c.getString(0), c.getString(1)));
            }
        } finally {
            db.close();
        }
        return result;
    }

    /** True only when every part of this unchanged document has been embedded. */
    public synchronized boolean hasEmbeddings(final String path, final long modified,
                                               final long size, final String modelKey) {
        final SQLiteDatabase db = open();
        try (Cursor c = db.rawQuery("SELECT 1 FROM embedding_notes "
                        + "WHERE path=? AND modified=? AND size=? AND model_key=? "
                        + "AND chunker_version=? AND completed=chunks",
                new String[]{path, Long.toString(modified), Long.toString(size), modelKey,
                        Integer.toString(KnowledgeEmbeddingChunks.CHUNKER_VERSION)})) {
            return c.moveToFirst();
        } finally {
            db.close();
        }
    }

    /** Already-current paths for this exact endpoint and model, in a single SQLite pass. */
    public synchronized Set<String> embeddedPaths(final String modelKey) {
        final Set<String> paths = new HashSet<>();
        final SQLiteDatabase db = open();
        try (Cursor c = db.rawQuery("SELECT e.path FROM embedding_notes e "
                        + "JOIN notes n ON n.path=e.path AND n.modified=e.modified "
                        + "AND n.size=e.size WHERE e.model_key=? AND e.completed=e.chunks "
                        + "AND e.chunker_version=?",
                new String[]{modelKey,
                        Integer.toString(KnowledgeEmbeddingChunks.CHUNKER_VERSION)})) {
            while (c.moveToNext()) {
                paths.add(c.getString(0));
            }
        } finally {
            db.close();
        }
        return paths;
    }

    /**
     * Start or resume an explicit user-approved note upload. Old sampled vectors
     * remain usable until the user initiates a rebuild with the new chunker.
     */
    public synchronized int beginEmbedding(final String path, final long modified,
                                            final long size, final String modelKey,
                                            final int expectedChunks, final boolean truncated) {
        if (path == null || path.isEmpty() || modelKey == null || modelKey.isEmpty()
                || expectedChunks < 1
                || expectedChunks > KnowledgeEmbeddingChunks.MAX_CHUNKS_PER_NOTE) {
            throw new IllegalArgumentException("Invalid embedding note");
        }
        final SQLiteDatabase db = open();
        int start = 0;
        db.beginTransaction();
        try {
            verifyCurrentNote(db, path, modified, size);
            boolean reuse = false;
            try (Cursor c = db.rawQuery("SELECT model_key, modified, size, chunks, completed,"
                            + "chunker_version, truncated FROM embedding_notes WHERE path=?",
                    new String[]{path})) {
                if (c.moveToFirst() && modelKey.equals(c.getString(0))
                        && modified == c.getLong(1) && size == c.getLong(2)
                        && expectedChunks == c.getInt(3)
                        && KnowledgeEmbeddingChunks.CHUNKER_VERSION == c.getInt(5)
                        && (truncated ? 1 : 0) == c.getInt(6)
                        && c.getInt(4) >= 0 && c.getInt(4) <= expectedChunks) {
                    reuse = true;
                    start = c.getInt(4);
                }
            }
            if (!reuse) {
                db.delete("embedding_lsh", "path=?", new String[]{path});
                db.delete("embedding_chunks", "path=?", new String[]{path});
                db.delete("embedding_notes", "path=?", new String[]{path});
                final ContentValues note = new ContentValues();
                note.put("path", path);
                note.put("model_key", modelKey);
                note.put("modified", modified);
                note.put("size", size);
                note.put("dimension", 0);
                note.put("chunks", expectedChunks);
                note.put("completed", 0);
                note.put("truncated", truncated ? 1 : 0);
                note.put("chunker_version", KnowledgeEmbeddingChunks.CHUNKER_VERSION);
                db.insertOrThrow("embedding_notes", null, note);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
        return start;
    }

    /** Commit one API batch, including its LSH posting lists, in a single transaction. */
    public synchronized void appendEmbeddingBatch(final String path, final long modified,
                                                    final long size, final String modelKey,
                                                    final List<KnowledgeEmbeddingChunks.Chunk> chunks,
                                                    final int from, final float[][] normalized) {
        if (normalized == null || normalized.length < 1
                || normalized.length > KnowledgeEmbeddingClient.MAX_BATCH
                || from < 0 || chunks == null || from + normalized.length > chunks.size()) {
            throw new IllegalArgumentException("Invalid embedding checkpoint");
        }
        final SQLiteDatabase db = open();
        db.beginTransaction();
        try {
            verifyCurrentNote(db, path, modified, size);
            int storedDimension = -1;
            try (Cursor c = db.rawQuery("SELECT completed, chunks, dimension FROM embedding_notes "
                            + "WHERE path=? AND model_key=? AND modified=? AND size=? "
                            + "AND chunker_version=?",
                    new String[]{path, modelKey, Long.toString(modified), Long.toString(size),
                            Integer.toString(KnowledgeEmbeddingChunks.CHUNKER_VERSION)})) {
                if (!c.moveToFirst() || c.getInt(0) != from || c.getInt(1) != chunks.size()) {
                    throw new IllegalStateException("Embedding checkpoint is stale");
                }
                storedDimension = c.getInt(2);
            }
            final int dimension = normalized[0].length;
            if (storedDimension != 0 && storedDimension != dimension) {
                throw new IllegalArgumentException("Embedding model dimension changed");
            }
            for (int j = 0; j < normalized.length; j++) {
                final int index = from + j;
                final KnowledgeEmbeddingChunks.Chunk chunk = chunks.get(index);
                if (normalized[j].length != dimension || chunk.start < 0
                        || chunk.text.isEmpty() || chunk.text.length() > 850
                        || chunk.start + chunk.text.length() > size) {
                    throw new IllegalArgumentException("Invalid embedding source position");
                }
                final ContentValues values = new ContentValues();
                values.put("path", path);
                values.put("chunk", index);
                values.put("offset", chunk.start);
                values.put("length", chunk.text.length());
                values.put("vector", KnowledgeEmbeddingVectors.encode(normalized[j]));
                db.insertOrThrow("embedding_chunks", null, values);
                for (final int bucket : KnowledgeEmbeddingLsh.buckets(normalized[j])) {
                    db.execSQL("INSERT INTO embedding_lsh(bucket, path, chunk) VALUES(?,?,?)",
                            new Object[]{bucket, path, index});
                }
            }
            db.execSQL("UPDATE embedding_notes SET completed=?, dimension=? WHERE path=?",
                    new Object[]{from + normalized.length, dimension, path});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    private static void verifyCurrentNote(final SQLiteDatabase db, final String path,
                                           final long modified, final long size) {
        try (Cursor c = db.rawQuery("SELECT modified, size, oversized FROM notes WHERE path=?",
                new String[]{path})) {
            if (!c.moveToFirst() || c.getLong(0) != modified
                    || c.getLong(1) != size || c.getInt(2) != 0) {
                throw new IllegalStateException("Source changed before embedding save");
            }
        }
    }

    public synchronized int embeddingNoteCount(final String modelKey) {
        final SQLiteDatabase db = open();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM embedding_notes e "
                        + "JOIN notes n ON n.path=e.path AND n.modified=e.modified "
                        + "AND n.size=e.size WHERE e.model_key=? AND e.completed=e.chunks",
                new String[]{modelKey})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            db.close();
        }
    }

    /**
     * Indexed approximate candidate lookup (44 LSH buckets), followed by exact
     * cosine reranking and per-file deduplication. Not a proof of nearest neighbor.
     */
    public synchronized List<SemanticHit> semanticSearch(final String modelKey,
                                                          final float[] normalizedQuestion,
                                                          final int limit) {
        if (limit < 1 || limit > 40) {
            throw new IllegalArgumentException("Invalid result limit");
        }
        final int[] buckets = KnowledgeEmbeddingLsh.probes(normalizedQuestion);
        final StringBuilder placeholders = new StringBuilder();
        final String[] args = new String[buckets.length + 3];
        for (int i = 0; i < buckets.length; i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
            args[i] = Integer.toString(buckets[i]);
        }
        args[buckets.length] = modelKey;
        args[buckets.length + 1] = Integer.toString(normalizedQuestion.length);
        args[buckets.length + 2] = Integer.toString(MAX_SEMANTIC_CANDIDATES);
        final String sql = "SELECT e.path, n.title, v.offset, v.length, v.vector, e.modified, e.size "
                + "FROM embedding_lsh l JOIN embedding_chunks v "
                + "ON v.path=l.path AND v.chunk=l.chunk "
                + "JOIN embedding_notes e ON e.path=v.path "
                + "JOIN notes n ON n.path=e.path AND n.modified=e.modified AND n.size=e.size "
                + "WHERE l.bucket IN (" + placeholders + ") AND e.model_key=? "
                + "AND e.dimension=? AND e.completed=e.chunks "
                + "GROUP BY l.path, l.chunk LIMIT ?";
        final Map<String, SemanticHit> best = new HashMap<>();
        final SQLiteDatabase db = open();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) {
                    return Collections.emptyList();
                }
                final double score = KnowledgeEmbeddingVectors.similarity(normalizedQuestion,
                        c.getBlob(4));
                if (!Double.isFinite(score)) {
                    continue;
                }
                final String path = c.getString(0);
                final SemanticHit previous = best.get(path);
                if (previous == null || score > previous.score) {
                    best.put(path, new SemanticHit(path, c.getString(1), c.getInt(2),
                            c.getInt(3), score, c.getLong(5), c.getLong(6)));
                }
            }
        } finally {
            db.close();
        }
        final List<SemanticHit> ranked = new ArrayList<>(best.values());
        ranked.sort(Comparator.comparingDouble((SemanticHit hit) -> hit.score).reversed()
                .thenComparing(hit -> hit.relativePath));
        return ranked.subList(0, Math.min(ranked.size(), limit));
    }

    public synchronized void clearEmbeddings() {
        final SQLiteDatabase db = open();
        db.beginTransaction();
        try {
            db.delete("embedding_lsh", null, null);
            db.delete("embedding_chunks", null, null);
            db.delete("embedding_notes", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    /** Snapshot-only integrity report. Never rewrites, repairs or exports private notes. */
    public synchronized IndexHealth healthReport() {
        // Do not call open(): that method initializes and migrates the schema.
        // Diagnostics must never change the index under investigation.
        final SQLiteDatabase db = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(),
                null, SQLiteDatabase.OPEN_READONLY);
        try {
            return new IndexHealth(db);
        } finally {
            db.close();
        }
    }

    /** Read-only test for the same-vault baseline before issuing change alerts. */
    public synchronized boolean hasCommittedScanFor(final File root) throws IOException {
        if (!databaseFile.isFile()) {
            return false;
        }
        final SQLiteDatabase db = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(),
                null, SQLiteDatabase.OPEN_READONLY);
        try {
            return root.getCanonicalPath().equals(getMeta(db, "root"))
                    && parseLong(getMeta(db, "last_scan_ms")) > 0
                    && !"1".equals(getMeta(db, "last_scan_incomplete"));
        } finally {
            db.close();
        }
    }

    /**
     * Explicitly user-authorized repair of an entirely disposable projection.
     * This file is beneath noBackupFilesDir, never a Markdown source or journal.
     */
    public synchronized void resetDerivedIndex() throws IOException {
        if (databaseFile.isFile() && !SQLiteDatabase.deleteDatabase(databaseFile)) {
            throw new IOException("Could not remove damaged derived index");
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
        final int version;
        try (Cursor c = db.rawQuery("PRAGMA user_version", null)) {
            c.moveToFirst();
            version = c.getInt(0);
            if (version > SCHEMA_VERSION) {
                db.close();
                throw new IllegalStateException("Newer knowledge index schema is not supported");
            }
            if (version < 1) {
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
        db.execSQL("CREATE TABLE IF NOT EXISTS embedding_notes (path TEXT PRIMARY KEY,"
                + "model_key TEXT NOT NULL, modified INTEGER NOT NULL, size INTEGER NOT NULL,"
                + "dimension INTEGER NOT NULL, chunks INTEGER NOT NULL,"
                + "completed INTEGER NOT NULL, truncated INTEGER NOT NULL,"
                + "chunker_version INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS embedding_chunks (path TEXT NOT NULL,"
                + "chunk INTEGER NOT NULL, offset INTEGER NOT NULL, length INTEGER NOT NULL,"
                + "vector BLOB NOT NULL,"
                + "PRIMARY KEY(path, chunk))");
        db.execSQL("CREATE INDEX IF NOT EXISTS embedding_model ON embedding_notes(model_key)");
        db.execSQL("CREATE TABLE IF NOT EXISTS embedding_lsh (bucket INTEGER NOT NULL,"
                + "path TEXT NOT NULL, chunk INTEGER NOT NULL,"
                + "PRIMARY KEY(bucket, path, chunk))");
        db.execSQL("CREATE INDEX IF NOT EXISTS embedding_lsh_path ON embedding_lsh(path)");
        if (version == 2) {
            // Preserve already generated (potentially costly) v2 embeddings and
            // progressively replace old 3-slice notes only after fresh consent.
            db.beginTransaction();
            try {
                db.execSQL("ALTER TABLE embedding_notes "
                        + "ADD COLUMN completed INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE embedding_notes "
                        + "ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE embedding_notes "
                        + "ADD COLUMN chunker_version INTEGER NOT NULL DEFAULT 1");
                db.execSQL("UPDATE embedding_notes SET completed=chunks");
                db.execSQL("ALTER TABLE embedding_chunks "
                        + "ADD COLUMN length INTEGER NOT NULL DEFAULT 850");
                try (Cursor old = db.rawQuery(
                        "SELECT path, chunk, vector FROM embedding_chunks", null)) {
                    while (old.moveToNext()) {
                        try {
                            final int[] buckets = KnowledgeEmbeddingLsh.buckets(
                                    KnowledgeEmbeddingVectors.decode(old.getBlob(2)));
                            for (final int bucket : buckets) {
                                db.execSQL("INSERT OR IGNORE INTO embedding_lsh "
                                                + "(bucket,path,chunk) VALUES(?,?,?)",
                                        new Object[]{bucket, old.getString(0), old.getInt(1)});
                            }
                        } catch (IllegalArgumentException invalid) {
                            // A corrupt derived vector is ignored, never proof of a match.
                        }
                    }
                }
                db.execSQL("PRAGMA user_version=" + SCHEMA_VERSION);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } else if (version != SCHEMA_VERSION) {
            db.execSQL("PRAGMA user_version=" + SCHEMA_VERSION);
        }
        return db;
    }

    private static String getMeta(final SQLiteDatabase db, final String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static int count(final SQLiteDatabase db, final String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private static long parseLong(final String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            return 0;
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
