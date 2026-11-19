package com.dpsmpx.dpks.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dpsmpx.dpks.domain.DiscoveredNote
import com.dpsmpx.dpks.domain.ScanSnapshot
import kotlinx.coroutines.ensureActive
import java.util.ArrayDeque
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Read-only, bounded discovery of Markdown files under a SAF tree.
 * Hidden entries are skipped; traversal does not leave the selected tree.
 */
object VaultScanner {
    const val MAX_NOTES = 200_000

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        limit: Int = MAX_NOTES,
        onProgress: ((discovered: Int) -> Unit)? = null,
    ): ScanSnapshot {
        require(limit >= 1)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Vault tree is unavailable")
        if (!root.isDirectory) error("Vault is not a directory")

        val notes = ArrayList<DiscoveredNote>(minOf(limit, 1024))
        var unreadable = 0
        var truncated = false
        val queue: ArrayDeque<Pair<DocumentFile, String>> = ArrayDeque()
        queue.add(root to "")

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dir, prefix) = queue.removeFirst()
            val children = try {
                dir.listFiles()
            } catch (_: SecurityException) {
                unreadable++
                continue
            }
            if (children.isEmpty() && !dir.canRead()) {
                unreadable++
            }
            for (child in children) {
                coroutineContext.ensureActive()
                val name = child.name ?: continue
                if (name.startsWith(".")) continue
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                when {
                    child.isDirectory -> queue.addLast(child to relative)
                    child.isFile && isMarkdown(name) -> {
                        notes.add(
                            DiscoveredNote(
                                relativePath = relative,
                                sizeBytes = child.length().coerceAtLeast(0L),
                                modifiedAtMillis = child.lastModified().coerceAtLeast(0L),
                                documentUri = child.uri.toString(),
                            ),
                        )
                        if (notes.size % 512 == 0) {
                            onProgress?.invoke(notes.size)
                        }
                        if (notes.size >= limit) {
                            truncated = true
                            queue.clear()
                            break
                        }
                    }
                }
            }
        }

        notes.sortWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath }
                .thenBy { it.relativePath },
        )
        onProgress?.invoke(notes.size)
        return ScanSnapshot(notes, unreadable, truncated)
    }

    private fun isMarkdown(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".md") || lower.endsWith(".markdown")
    }
}
