package com.smartglasses.app.feature.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.domain.EngineStatus
import com.smartglasses.app.domain.ModelImportState
import com.smartglasses.app.domain.OfflineSpeechEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ModelsUiState(val engine: EngineStatus, val import: ModelImportState)

@HiltViewModel
class ModelsViewModel @Inject constructor(private val engine: OfflineSpeechEngine) : ViewModel() {
    val state: StateFlow<ModelsUiState> = combine(engine.status, engine.importState, ::ModelsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ModelsUiState(engine.status.value, engine.importState.value))

    fun import(folder: Uri) = engine.importModels(folder)

    fun cancelImport() = engine.cancelImport()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
