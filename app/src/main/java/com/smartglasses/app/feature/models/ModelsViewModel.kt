package com.smartglasses.app.feature.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.data.translation.AiModelState
import com.smartglasses.app.data.translation.HyMtTranslator
import com.smartglasses.app.domain.EngineStatus
import com.smartglasses.app.domain.ModelImportState
import com.smartglasses.app.domain.OfflineSpeechEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelsUiState(
    val engine: EngineStatus,
    val import: ModelImportState,
    val aiModel: AiModelState,
    val aiEnabled: Boolean,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val engine: OfflineSpeechEngine,
    private val aiTranslator: HyMtTranslator,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<ModelsUiState> = combine(
        engine.status, engine.importState, aiTranslator.state, settings.settings.map { it.aiTranslation }, ::ModelsUiState,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ModelsUiState(engine.status.value, engine.importState.value, aiTranslator.state.value, aiEnabled = true),
    )

    fun import(folder: Uri) = engine.importModels(folder)

    fun cancelImport() = engine.cancelImport()

    fun importAiModel(file: Uri) = aiTranslator.import(file)

    fun cancelAiImport() = aiTranslator.cancelImport()

    fun removeAiModel() = aiTranslator.remove()

    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAiTranslation(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
