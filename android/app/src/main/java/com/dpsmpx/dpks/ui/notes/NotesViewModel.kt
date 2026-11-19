package com.dpsmpx.dpks.ui.notes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpsmpx.dpks.data.NotesRepository
import com.dpsmpx.dpks.domain.NoteHit
import com.dpsmpx.dpks.domain.NotesUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NotesFilter { All, Unverified, Recent }

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = NotesRepository(application)

    private val _state = MutableStateFlow(NotesUiState())
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    private var filter: NotesFilter = NotesFilter.All
    private var allHits: List<NoteHit> = emptyList()
    private var refreshJob: Job? = null

    init {
        if (repo.hasVault()) {
            _state.update {
                it.copy(
                    hasVault = true,
                    vaultLabel = repo.displayName,
                )
            }
            reloadFromIndex()
        }
    }

    fun onVaultPicked(uri: Uri) {
        repo.persistTree(uri)
        _state.update {
            it.copy(
                hasVault = true,
                vaultLabel = repo.displayName,
                error = null,
            )
        }
        refresh()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        applyFilterAndQuery()
    }

    fun onFilter(filter: NotesFilter) {
        this.filter = filter
        applyFilterAndQuery()
    }

    fun refresh() {
        if (!repo.hasVault()) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isScanning = true,
                    scanProgress = 0,
                    error = null,
                    statusMessage = "Scanning…",
                )
            }
            try {
                val stats = repo.refresh(
                    onScanProgress = { n ->
                        _state.update {
                            it.copy(scanProgress = n, statusMessage = "Found $n notes…")
                        }
                    },
                    onIndexProgress = { done, total ->
                        _state.update {
                            it.copy(
                                scanProgress = done,
                                statusMessage = "Indexing $done / $total…",
                            )
                        }
                    },
                )
                allHits = repo.search(_state.value.query)
                val total = repo.totalCount()
                val unverified = repo.unverifiedCount()
                val incomplete = repo.lastScanIncomplete()
                _state.update {
                    it.copy(
                        isScanning = false,
                        totalIndexed = total,
                        unverified = unverified,
                        truncatedScan = incomplete,
                        statusMessage = buildStatus(stats.indexed, stats.reused, total, unverified, incomplete),
                        hits = applyLocalFilter(allHits),
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update {
                    it.copy(
                        isScanning = false,
                        error = t.message ?: "Scan failed",
                        statusMessage = null,
                    )
                }
            }
        }
    }

    fun cancelRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        _state.update {
            it.copy(isScanning = false, statusMessage = "Cancelled")
        }
    }

    private fun reloadFromIndex() {
        viewModelScope.launch {
            try {
                allHits = repo.search("")
                _state.update {
                    it.copy(
                        totalIndexed = repo.totalCount(),
                        unverified = repo.unverifiedCount(),
                        truncatedScan = repo.lastScanIncomplete(),
                        hits = applyLocalFilter(allHits),
                        statusMessage = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message) }
            }
        }
    }

    private fun applyFilterAndQuery() {
        viewModelScope.launch {
            allHits = repo.search(_state.value.query)
            _state.update { it.copy(hits = applyLocalFilter(allHits)) }
        }
    }

    private fun applyLocalFilter(source: List<NoteHit>): List<NoteHit> {
        return when (filter) {
            NotesFilter.All -> source
            NotesFilter.Unverified -> source.filter { !it.confirmedInLastScan }
            NotesFilter.Recent -> source.sortedByDescending { it.modifiedAtMillis }.take(100)
        }
    }

    private fun buildStatus(
        indexed: Int,
        reused: Int,
        total: Int,
        unverified: Int,
        incomplete: Boolean,
    ): String {
        val base = "$total notes · $indexed updated · $reused unchanged"
        return when {
            incomplete && unverified > 0 ->
                "$base · $unverified not rechecked (partial scan)"
            incomplete -> "$base · partial scan"
            else -> base
        }
    }
}
