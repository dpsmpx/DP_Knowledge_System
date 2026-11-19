package com.dpsmpx.dpks.domain

/** One Markdown note discovered under the selected vault (read-only metadata). */
data class DiscoveredNote(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    /** Content URI string for SAF reads; null when only a path was used. */
    val documentUri: String? = null,
)

data class ScanSnapshot(
    val notes: List<DiscoveredNote>,
    val unreadableDirectories: Int,
    val truncated: Boolean,
)

data class NoteHit(
    val relativePath: String,
    val title: String,
    val modifiedAtMillis: Long,
    val sizeBytes: Long,
    /** False when the row was kept after a partial scan and not rechecked. */
    val confirmedInLastScan: Boolean,
    val oversized: Boolean = false,
)

data class SyncStats(
    val indexed: Int,
    val reused: Int,
    val oversized: Int,
    val removed: Int,
    val keptMissingEntries: Boolean,
    val unverified: Int,
)

data class NotesUiState(
    val vaultLabel: String? = null,
    val hasVault: Boolean = false,
    val isScanning: Boolean = false,
    val scanProgress: Int = 0,
    val statusMessage: String? = null,
    val query: String = "",
    val hits: List<NoteHit> = emptyList(),
    val totalIndexed: Int = 0,
    val truncatedScan: Boolean = false,
    val unverified: Int = 0,
    val error: String? = null,
)
