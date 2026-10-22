package com.smartglasses.app.feature.translate

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.history.HistoryRepository
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.domain.model.AppSettings
import com.smartglasses.app.domain.model.Language
import com.smartglasses.app.domain.model.RecordSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TranslateUiState(
    val settings: AppSettings = AppSettings(),
    /** Null while loading. */
    val records: List<RecordSummary>? = null,
    val searching: Boolean = false,
    val query: String = "",
    val selecting: Boolean = false,
    val selected: Set<Long> = emptySet(),
)

private data class ListMode(val searching: Boolean, val query: String, val selecting: Boolean, val selected: Set<Long>)

/** The Translate home: languages, "Start Translation" and the saved records. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val history: HistoryRepository,
) : ViewModel() {

    private val mode = MutableStateFlow(ListMode(searching = false, query = "", selecting = false, selected = emptySet()))
    private val query = MutableStateFlow("")

    // Typing is debounced; the full list (empty query) shows at once.
    private val records = query.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }.flatMapLatest { history.records(it) }

    val state: StateFlow<TranslateUiState> = combine(settingsRepository.settings, records, mode) { settings, list, m ->
        val ids = list.mapTo(HashSet()) { it.id }
        TranslateUiState(
            settings = settings,
            records = list,
            searching = m.searching,
            query = m.query,
            selecting = m.selecting,
            selected = m.selected.filterTo(HashSet()) { it in ids }, // deleted records drop out
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TranslateUiState())

    fun setSource(language: Language) = saveLanguages(language, state.value.settings.target)

    fun setTarget(language: Language) = saveLanguages(state.value.settings.source, language)

    fun swapLanguages() = saveLanguages(state.value.settings.target, state.value.settings.source)

    private fun saveLanguages(source: Language, target: Language) {
        viewModelScope.launch { settingsRepository.setLanguages(source, target) }
    }

    fun openSearch() = mode.update { it.copy(searching = true) }

    fun setQuery(text: String) {
        mode.update { it.copy(query = text) }
        query.value = text
    }

    fun closeSearch() {
        mode.update { it.copy(searching = false, query = "") }
        query.value = ""
    }

    fun toggleSelecting() = mode.update { it.copy(selecting = !it.selecting, selected = emptySet()) }

    fun toggleSelected(id: Long) = mode.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    /** Selects every listed record, or clears the selection when all are selected already. */
    fun toggleSelectAll() {
        val all = state.value.records.orEmpty().mapTo(HashSet()) { it.id }
        mode.update { it.copy(selected = if (it.selected.containsAll(all)) emptySet() else all) }
    }

    fun deleteSelected() {
        val ids = state.value.selected
        viewModelScope.launch {
            history.delete(ids)
            mode.update { it.copy(selecting = false, selected = emptySet()) }
        }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { history.rename(id, title) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}
