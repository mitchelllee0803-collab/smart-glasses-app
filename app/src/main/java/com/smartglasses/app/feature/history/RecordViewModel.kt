package com.smartglasses.app.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.history.HistoryRepository
import com.smartglasses.app.domain.model.RecordDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RecordUiState {
    data object Loading : RecordUiState
    /** Deleted meanwhile. */
    data object Missing : RecordUiState
    data class Loaded(val record: RecordDetail) : RecordUiState
}

@HiltViewModel
class RecordViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val history: HistoryRepository,
) : ViewModel() {
    private val id: Long = checkNotNull(savedState[ARG_RECORD_ID])

    val state: StateFlow<RecordUiState> = history.record(id)
        .map { it?.let(RecordUiState::Loaded) ?: RecordUiState.Missing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecordUiState.Loading)

    fun rename(title: String) {
        viewModelScope.launch { history.rename(id, title) }
    }

    companion object {
        const val ARG_RECORD_ID = "recordId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
