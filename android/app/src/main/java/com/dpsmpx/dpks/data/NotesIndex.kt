package com.dpsmpx.dpks.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.dpsmpx.dpks.domain.DiscoveredNote
import com.dpsmpx.dpks.domain.NoteHit
import com.dpsmpx.dpks.domain.ScanSnapshot
import com.dpsmpx.dpks.domain.SyncStats
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Disposable app-private FTS index. Lives in noBackupFilesDir.
 * Incomplete scans never delete preserved rows (Stage 16 rule).
 */
class NotesIndex(context: Context) {
    private val appContext = context.applicationContext
    private val helper = Helper(appContext)

    suspend fun synchronize(
        treeUri: Uri,
        snapshot: ScanSnapshot,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null,
    ): SyncStats {
        val generation = UUID.randomUUID().toString()
        val db = helper.writableDatabase
        var indexed = 0
        var reused = 0
        var oversized = 0
        var removed = 0
        var unverified = 0

        db.beginTransaction()
        try {
            val previousRoot = getMeta(db, "root")
            val root = treeUri.toString()
            if (previousRoot != null && previousRoot != root) {
                db.execSQL("DELETE FROM links")
                db.execSQL("DELETE FROM documents_fts")
                db.execSQL("DELETE FROM notes")
            }
            setMeta(db, "root", root)

            snapshot.notes.forEachIndexed { index, note ->
                coroutineContext.ensureActive()
                if (index % 64 == 0 || index == snapshot.notes.lastIndex) {
                    onProgress?.invoke(index + 1, snapshot.notes.size)
                }

                val existing = db.rawQuery(
                    "SELECT id, modified, size FROM notes WHERE path=?",
                    arrayOf(note.relativePath),
                ).use { c ->
                    if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getLong(2)) else null
                }

                if (existing != null &&
                    existing.second == note.modifiedAtMillis &&
                    existing.third == note.sizeBytes
                ) {
                    db.execSQL(
                        "UPDATE notes SET generation=?, doc_uri=? WHERE id=?",
                        arrayOf(generation, note.documentUri, existing.first),
                    )
                    reused++
                    return@forEachIndexed
                }

                val tooLarge = note.sizeBytes > MAX_INDEX_BYTES
                val body = if (tooLarge) "" else readBody(note)
                val title = if (tooLarge) {
                    MarkdownMeta.filenameTitle(note.relativePath)
                } else {
                    MarkdownMeta.titleOf(note.relativePath, body)
                }

                val values = ContentValues().apply {
                    put("path", note.relativePath)
                    put("modified", note.modifiedAtMillis)
                    put("size", note.sizeBytes)
                    put("title", title)
                    put("oversized", if (tooLarge) 1 else 0)
                    put("generation", generation)
                    put("doc_uri", note.documentUri)
                }

                val rowId: Long
                if (existing == null) {
                    rowId = db.insertOrThrow("notes", null, values)
                } else {
                    rowId = existing.first
                    db.update("notes", values, "id=?", arrayOf(rowId.toString()))
                    db.execSQL("DELETE FROM documents_fts WHERE docid=?", arrayOf(rowId))
                    db.delete("links", "source_path=?", arrayOf(note.relativePath))
                }

                db.execSQL(
                    "INSERT INTO documents_fts(docid, path, title, body) VALUES(?,?,?,?)",
                    arrayOf(rowId, note.relativePath, title, body),
                )
                if (!tooLarge) {
                    for (link in MarkdownMeta.possibleLinks(body)) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO links(source_path, target_key) VALUES(?,?)",
                            arrayOf(note.relativePath, link),
                        )
                    }
                }
                indexed++
                if (tooLarge) oversized++
            }

            val incomplete = snapshot.truncated || snapshot.unreadableDirectories > 0
            if (!incomplete) {
                db.rawQuery(
                    "SELECT id, path FROM notes WHERE generation<>?",
                    arrayOf(generation),
                ).use { c ->
                    while (c.moveToNext()) {
                        coroutineContext.ensureActive()
                        val id = c.getLong(0)
                        val path = c.getString(1)
                        db.execSQL("DELETE FROM documents_fts WHERE docid=?", arrayOf(id))
                        db.delete("links", "source_path=?", arrayOf(path))
                        db.delete("notes", "id=?", arrayOf(id.toString()))
                        removed++
                    }
                }
            } else {
                db.rawQuery(
                    "SELECT COUNT(*) FROM notes WHERE generation<>?",
                    arrayOf(generation),
                ).use { c ->
                    unverified = if (c.moveToFirst()) c.getInt(0) else 0
                }
            }

            setMeta(db, "last_scan_ms", System.currentTimeMillis().toString())
            setMeta(db, "last_scan_count", snapshot.notes.size.toString())
            setMeta(db, "last_scan_generation", generation)
            setMeta(db, "last_scan_incomplete", if (incomplete) "1" else "0")
            setMeta(db, "last_scan_truncated", if (snapshot.truncated) "1" else "0")
            setMeta(db, "last_scan_unreadable", snapshot.unreadableDirectories.toString())
            db.setTransactionSuccessful()

            return SyncStats(indexed, reused, oversized, removed, incomplete, unverified)
        } finally {
            db.endTransaction()
        }
    }

    fun search(query: String, limit: Int = MAX_VISIBLE): List<NoteHit> {
        val db = helper.readableDatabase
        val generation = getMeta(db, "last_scan_generation").orEmpty()
        val fts = MarkdownMeta.ftsQuery(query)
        val hits = ArrayList<NoteHit>(limit)

        if (fts.isEmpty()) {
            db.rawQuery(
                """
                SELECT path, title, modified, size, generation, oversized
                FROM notes
                ORDER BY CASE WHEN generation=? THEN 0 ELSE 1 END, path COLLATE NOCASE
                LIMIT ?
                """.trimIndent(),
                arrayOf(generation, limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    hits.add(c.toHit(generation))
                }
            }
        } else {
            db.rawQuery(
                """
                SELECT notes.path, notes.title, notes.modified, notes.size,
                       notes.generation, notes.oversized
                FROM documents_fts
                JOIN notes ON documents_fts.docid = notes.id
                WHERE documents_fts MATCH ?
                ORDER BY CASE WHEN notes.generation=? THEN 0 ELSE 1 END,
                         notes.path COLLATE NOCASE
                LIMIT ?
                """.trimIndent(),
                arrayOf(fts, generation, limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    hits.add(c.toHit(generation))
                }
            }
        }
        return hits
    }

    fun totalCount(): Int {
        val db = helper.readableDatabase
        return db.rawQuery("SELECT COUNT(*) FROM notes", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun lastUnverified(): Int {
        val db = helper.readableDatabase
        val generation = getMeta(db, "last_scan_generation") ?: return totalCount()
        return db.rawQuery(
            "SELECT COUNT(*) FROM notes WHERE generation<>?",
            arrayOf(generation),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun lastScanIncomplete(): Boolean {
        val db = helper.readableDatabase
        return getMeta(db, "last_scan_incomplete") == "1"
    }

    private fun android.database.Cursor.toHit(generation: String): NoteHit {
        val gen = getString(4)
        return NoteHit(
            relativePath = getString(0),
            title = getString(1),
            modifiedAtMillis = getLong(2),
            sizeBytes = getLong(3),
            confirmedInLastScan = gen == generation,
            oversized = getInt(5) != 0,
        )
    }

    private fun readBody(note: DiscoveredNote): String {
        val uri = note.documentUri?.let(Uri::parse) ?: return ""
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    buildString {
                        var total = 0
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            total += line!!.length + 1
                            if (total > MAX_INDEX_BYTES) break
                            appendLine(line)
                        }
                    }
                }
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun getMeta(db: SQLiteDatabase, key: String): String? {
        return db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun setMeta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO meta(key, value) VALUES(?,?)",
            arrayOf(key, value),
        )
    }

    private class Helper(context: Context) : SQLiteOpenHelper(
        context,
        context.noBackupFilesDir.resolve(DB_NAME).absolutePath,
        null,
        SCHEMA,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE notes(
                  id INTEGER PRIMARY KEY,
                  path TEXT NOT NULL UNIQUE,
                  modified INTEGER NOT NULL,
                  size INTEGER NOT NULL,
                  title TEXT NOT NULL,
                  oversized INTEGER NOT NULL DEFAULT 0,
                  generation TEXT NOT NULL,
                  doc_uri TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE VIRTUAL TABLE documents_fts USING fts4(
                    path, title, body, tokenize=unicode61
                )".trimIndent().replace("\n", " "),
            )
            db.execSQL(
                "CREATE TABLE links(
                    source_path TEXT NOT NULL,
                    target_key TEXT NOT NULL,
                    PRIMARY KEY(source_path, target_key)
                )".trimIndent().replace("\n", " "),
            )
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS links")
            db.execSQL("DROP TABLE IF EXISTS documents_fts")
            db.execSQL("DROP TABLE IF EXISTS notes")
            db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "notes_index_v1.sqlite"
        private const val SCHEMA = 1
        const val MAX_INDEX_BYTES = 2_000_000
        const val MAX_VISIBLE = 250
    }
}
