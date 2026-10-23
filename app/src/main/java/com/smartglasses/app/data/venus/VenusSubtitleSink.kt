package com.smartglasses.app.data.venus

import android.os.SystemClock
import android.util.Log
import com.jueyuantech.venussdk.VNCommon
import com.jueyuantech.venussdk.VNConstant
import com.jueyuantech.venussdk.bean.FontConfig
import com.jueyuantech.venussdk.bean.LanguageHint
import com.jueyuantech.venussdk.bean.VNSttInfo
import com.smartglasses.app.di.ApplicationScope
import com.smartglasses.app.domain.SubtitleConfig
import com.smartglasses.app.domain.SubtitleSink
import com.smartglasses.app.domain.SubtitleSinkStatus
import com.smartglasses.app.domain.SubtitleUpdate
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.GlassesTextMode
import com.smartglasses.app.domain.model.MicrophoneSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Subtitles on the glasses' built-in translation view (guide §6.1): setView(TRANSLATE), display
 * preferences, then VNSttInfo messages through updateSttInfo. A connection alone shows nothing
 * on the glasses; this class opens the view whenever a session is running and the glasses are
 * connected, including after a reconnect. Speech content is never logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class VenusSubtitleSink @Inject constructor(
    private val venus: VenusClient,
    private val glasses: GlassesRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : SubtitleSink {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val _status = MutableStateFlow<SubtitleSinkStatus>(SubtitleSinkStatus.Idle)
    override val status: StateFlow<SubtitleSinkStatus> = _status.asStateFlow()

    private val queue = SubtitleQueue()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var config: SubtitleConfig? = null
    private var session: Job? = null
    private var msgIdBase = 0L
    private var lastSentAt = 0L

    override fun start(config: SubtitleConfig) {
        scope.launch(confined) {
            this@VenusSubtitleSink.config = config
            if (session?.isActive == true) return@launch
            session = scope.launch(confined) { runSession() }
        }
    }

    override fun updateConfig(config: SubtitleConfig) {
        scope.launch(confined) {
            val previous = this@VenusSubtitleSink.config ?: return@launch
            this@VenusSubtitleSink.config = config
            if (previous != config && _status.value == SubtitleSinkStatus.Active) applyPreferences(config)
        }
    }

    override fun reopen() {
        scope.launch(confined) {
            if (session?.isActive == true && glasses.state.value is ConnectionState.Connected) activate()
        }
    }

    override fun submit(update: SubtitleUpdate) {
        scope.launch(confined) {
            val c = config ?: return@launch
            // In translation-only mode a line without translation would be an empty row.
            if (c.display.textMode == GlassesTextMode.TRANSLATION_ONLY && update.translation.isBlank()) return@launch
            queue.offer(update)
            wake.trySend(Unit)
        }
    }

    override fun remove(lineId: Long) {
        scope.launch(confined) {
            queue.remove(lineId)
            wake.trySend(Unit)
        }
    }

    override fun stop() {
        scope.launch(confined) {
            session?.cancel()
            session = null
            config = null
            queue.reset()
            // Only while the glasses still show the translation view: if the wearer already went
            // elsewhere with the touchpad, they stay where they are.
            val showing = _status.value == SubtitleSinkStatus.Active
            _status.value = SubtitleSinkStatus.Idle
            if (showing && glasses.state.value is ConnectionState.Connected && !simulated()) venus.setView(VNConstant.View.HOME)
        }
    }

    private suspend fun runSession() = coroutineScope {
        launch { sendLoop() }
        launch { keepAliveLoop() }
        launch {
            venus.events.collect { event ->
                when (event) {
                    is VenusEvent.ViewChanged -> onViewChanged(event.view)
                    // After a screen timeout the glasses fall back to HOME (guide §4.12/4.13);
                    // show the translation again when the screen wakes up.
                    is VenusEvent.Screen -> if (event.on && _status.value == SubtitleSinkStatus.ViewLeft) activate()
                    else -> Unit
                }
            }
        }
        glasses.state.map { it is ConnectionState.Connected }.distinctUntilChanged().collect { connected ->
            if (connected) {
                activate()
            } else {
                queue.reset()
                _status.value = SubtitleSinkStatus.NoGlasses
            }
        }
    }

    /** Opens the translation view and applies the display preferences (guide §6.1 steps 1–2). */
    private suspend fun activate() {
        val c = config ?: return
        _status.value = SubtitleSinkStatus.Opening
        queue.reset()
        // Numeric ids that fit an int: the AAR takes a String, the guide documents an integer.
        msgIdBase = (System.currentTimeMillis() / 1000 % MSG_ID_SESSIONS) * MSG_ID_SPAN
        if (simulated()) {
            _status.value = SubtitleSinkStatus.Active
            wake.trySend(Unit)
            return
        }
        val result = venus.setView(VNConstant.View.TRANSLATE)
        if (result != VenusResult.SUCCESS) {
            _status.value = SubtitleSinkStatus.Failed(timedOut = result == VenusResult.TIMEOUT)
            return
        }
        applyPreferences(c)
        lastSentAt = SystemClock.elapsedRealtime()
        _status.value = SubtitleSinkStatus.Active
        wake.trySend(Unit)
    }

    /** Best effort: a preference the firmware rejects does not stop subtitles. */
    private suspend fun applyPreferences(c: SubtitleConfig) {
        val view = VNConstant.View.TRANSLATE
        val transMode = when (c.display.textMode) {
            GlassesTextMode.TRANSLATION_ONLY -> VNConstant.SttConfig.TransMode.TRANSLATION_ONLY
            GlassesTextMode.ORIGINAL_AND_TRANSLATION -> VNConstant.SttConfig.TransMode.ORIGINAL_AND_TRANSLATION
        }
        val textMode = if (c.display.showHistory) {
            VNConstant.SttConfig.TextMode.CURRENT_AND_HISTORICAL
        } else {
            VNConstant.SttConfig.TextMode.CURRENT_ONLY
        }
        val font = FontConfig().apply {
            weight = c.display.fontSize
            wordSpace = FONT_WORD_SPACE
            rowSpace = FONT_ROW_SPACE
        }
        val hint = LanguageHint().apply {
            mode = LANGUAGE_HINT_BOTH
            source = c.source.label
            target = c.target.label
        }
        venus.command("setTransMode") { VNCommon.setTransMode(view, transMode, it) }
        venus.command("setTextMode") { VNCommon.setTextMode(view, textMode, it) }
        venus.command("setFontConfig") { VNCommon.setFontConfig(view, font, it) }
        venus.command("setLanguageHint") { VNCommon.setLanguageHint(view, hint, it) }
        // Icon only (guide §6.1): the audio itself is routed by MicrophoneCapture.
        val audioSource = when (c.microphone) {
            MicrophoneSource.GLASSES -> VNConstant.SttConfig.AudioSource.GLASSES
            MicrophoneSource.PHONE -> VNConstant.SttConfig.AudioSource.PHONE
        }
        venus.command("setAudioSourceIndicator") { VNCommon.setAudioSourceIndicator(view, audioSource, it) }
    }

    private fun onViewChanged(view: Int) {
        when {
            view == VNConstant.View.TRANSLATE && _status.value == SubtitleSinkStatus.ViewLeft -> {
                _status.value = SubtitleSinkStatus.Active
                wake.trySend(Unit)
            }
            view != VNConstant.View.TRANSLATE && _status.value == SubtitleSinkStatus.Active ->
                _status.value = SubtitleSinkStatus.ViewLeft
        }
    }

    /** One message in flight at a time, so updates reach the glasses in order. */
    private suspend fun sendLoop() {
        while (true) {
            wake.receive()
            while (_status.value == SubtitleSinkStatus.Active) {
                val message = queue.poll() ?: break
                queue.onResult(message, deliver(message))
                // Partial updates are paced; final ones go out as soon as possible.
                if (message is SubtitleQueue.Outgoing.Send && !message.update.isFinal) delay(MIN_PARTIAL_GAP_MS)
            }
        }
    }

    private suspend fun deliver(message: SubtitleQueue.Outgoing): Boolean {
        if (simulated()) {
            Log.d(TAG, "test device: line ${message.lineId} not sent (no glasses)")
            return true
        }
        val info = VNSttInfo().apply {
            msgId = (msgIdBase + message.lineId % MSG_ID_SPAN).toString()
            msgType = VNConstant.SttInfo.MsgType.STT
        }
        val action = when (message) {
            is SubtitleQueue.Outgoing.Send -> {
                info.actionType = if (message.isNew) VNConstant.SttInfo.ActionType.NEW else VNConstant.SttInfo.ActionType.UPDATE
                info.transcribe = clip(message.update.original)
                info.translate = clip(message.update.translation)
                info.isFinal = if (message.update.isFinal) 1 else 0
                if (message.isNew) "NEW" else "UPDATE"
            }
            is SubtitleQueue.Outgoing.Remove -> {
                info.actionType = VNConstant.SttInfo.ActionType.REMOVE
                "REMOVE"
            }
        }
        val result = venus.command("updateSttInfo($action)", SEND_TIMEOUT_MS) {
            VNCommon.updateSttInfo(VNConstant.View.TRANSLATE, info, it)
        }
        lastSentAt = SystemClock.elapsedRealtime()
        if (result != VenusResult.SUCCESS) Log.w(TAG, "line ${message.lineId} $action -> $result")
        return result != VenusResult.FAILED
    }

    /**
     * The glasses switch off their screen after a period without messages (guide §4.13); during a
     * session silence is normal, so the screen is kept on.
     */
    private suspend fun keepAliveLoop() {
        while (true) {
            delay(KEEP_ALIVE_CHECK_MS)
            if (_status.value == SubtitleSinkStatus.Active &&
                SystemClock.elapsedRealtime() - lastSentAt >= KEEP_ALIVE_INTERVAL_MS && !simulated()
            ) {
                venus.sendKeepAlive()
                lastSentAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /** The connected device is the debug test device: nothing goes to the VenusSDK. */
    private fun simulated(): Boolean = (glasses.state.value as? ConnectionState.Connected)?.device?.simulated == true

    private fun clip(text: String): String =
        if (text.length <= MAX_TEXT_CHARS) text else "…" + text.takeLast(MAX_TEXT_CHARS - 1)

    private companion object {
        const val TAG = "VenusSubtitleSink"
        const val SEND_TIMEOUT_MS = 8_000L
        /** Minimum gap after a partial update; measured values from the glasses may tune this. */
        const val MIN_PARTIAL_GAP_MS = 150L
        const val KEEP_ALIVE_CHECK_MS = 5_000L
        const val KEEP_ALIVE_INTERVAL_MS = 20_000L
        const val MAX_TEXT_CHARS = 300
        const val MSG_ID_SPAN = 10_000L
        const val MSG_ID_SESSIONS = 100_000L
        const val FONT_WORD_SPACE = 1
        const val FONT_ROW_SPACE = 8
        /** LanguageHint mode 1: source and target language both shown. */
        const val LANGUAGE_HINT_BOTH = 1
    }
}
