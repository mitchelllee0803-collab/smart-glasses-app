package com.smartglasses.app.feature.translate

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.data.translation.SessionOptions
import com.smartglasses.app.data.translation.SessionState
import com.smartglasses.app.data.translation.TranslationSessionManager
import com.smartglasses.app.data.venus.GlassesRepository
import com.smartglasses.app.data.venus.GlassesSync
import com.smartglasses.app.domain.EngineStatus
import com.smartglasses.app.domain.OfflineSpeechEngine
import com.smartglasses.app.domain.SubtitleConfig
import com.smartglasses.app.domain.SubtitleSink
import com.smartglasses.app.domain.SubtitleSinkStatus
import com.smartglasses.app.domain.model.AppSettings
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.MicrophoneSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SessionUiState(
    val settings: AppSettings = AppSettings(),
    val session: SessionState = SessionState(),
    val engine: EngineStatus = EngineStatus.Starting,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val glasses: SubtitleSinkStatus = SubtitleSinkStatus.Idle,
)

/** One live session page: created by "Start Translation", saved as it goes, ended when left. */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val session: TranslationSessionManager,
    private val sink: SubtitleSink,
    private val glasses: GlassesRepository,
    private val glassesSync: GlassesSync,
    engine: OfflineSpeechEngine,
) : ViewModel() {

    private var settings = AppSettings()

    val state: StateFlow<SessionUiState> = combine(
        settingsRepository.settings, session.state, engine.status, glasses.state, sink.status, ::SessionUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SessionUiState())

    /** The glasses re-entered Translate while this page is open: listen again. */
    val glassesListenRequest: StateFlow<Boolean> = glassesSync.listenRequested

    init {
        session.newSession()
        glassesSync.onSessionPageOpened()
        // Open the glasses' translation view while this page exists and keep its display
        // preferences in sync with the settings.
        viewModelScope.launch {
            settings = settingsRepository.current()
            sink.start(config(settings))
            settingsRepository.settings.collect {
                settings = it
                sink.updateConfig(config(it))
                // A microphone chosen in Translation Settings applies to a running session at once.
                session.restartIfListening(sessionOptions())
            }
        }
    }

    private fun config(s: AppSettings) = SubtitleConfig(s.source, s.target, s.display, s.microphone)

    private fun sessionOptions(): SessionOptions {
        val connected = (glasses.state.value as? ConnectionState.Connected)?.device?.address
        return SessionOptions(
            source = settings.source,
            target = settings.target,
            liveTranslation = settings.display.liveTranslation,
            microphone = settings.microphone,
            glassesAddress = connected ?: settings.lastGlassesAddress,
        )
    }

    /** The caller has checked the microphone permission. */
    fun startListening() {
        // Settings may still be loading on the very first frame; read them before starting.
        viewModelScope.launch {
            settings = settingsRepository.current()
            session.startListening(sessionOptions())
        }
    }

    fun stopListening() = session.stopListening()

    /** "Use phone microphone" after the glasses' microphone could not be opened. */
    fun usePhoneMicrophone() {
        session.dismissError()
        viewModelScope.launch {
            settingsRepository.setMicrophone(MicrophoneSource.PHONE)
            settings = settings.copy(microphone = MicrophoneSource.PHONE)
            session.startListening(sessionOptions())
        }
    }

    fun reopenOnGlasses() = sink.reopen()

    fun consumeGlassesListenRequest() = glassesSync.consumeListenRequest()

    fun dismissError() = session.dismissError()

    override fun onCleared() {
        // Leaving the page ends the session: phrases already spoken are still finished and saved.
        session.stopListening()
        sink.stop()
        glassesSync.onSessionPageClosed()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
