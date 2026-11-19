package com.dpsmpx.dpks.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dpsmpx.dpks.domain.NoteHit
import com.dpsmpx.dpks.domain.SyncStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotesRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = VaultPreferences(app)
    private val index = NotesIndex(app)

    val treeUri: Uri? get() = prefs.treeUri
    val displayName: String? get() = prefs.displayName

    fun hasVault(): Boolean = prefs.treeUri != null

    fun persistTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Read-only grant may still work for the current session.
            }
        }
        prefs.treeUri = uri
        prefs.displayName = DocumentFile.fromTreeUri(app, uri)?.name ?: uri.lastPathSegment
    }

    suspend fun refresh(
        onScanProgress: (Int) -> Unit = {},
        onIndexProgress: (Int, Int) -> Unit = {},
    ): SyncStats = withContext(Dispatchers.IO) {
        val uri = prefs.treeUri ?: error("No vault selected")
        val snapshot = VaultScanner.scan(app, uri, onProgress = onScanProgress)
        index.synchronize(uri, snapshot, onProgress = onIndexProgress)
    }

    fun search(query: String): List<NoteHit> = index.search(query)

    fun totalCount(): Int = index.totalCount()

    fun unverifiedCount(): Int = index.lastUnverified()

    fun lastScanIncomplete(): Boolean = index.lastScanIncomplete()
}
