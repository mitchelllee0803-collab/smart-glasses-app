package com.smartglasses.app.domain

import com.smartglasses.app.domain.model.DisplaySettings
import com.smartglasses.app.domain.model.Language
import com.smartglasses.app.domain.model.MicrophoneSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Where live subtitles go besides the phone screen. The translation pipeline only talks to this
 * interface, so another glasses vendor can be supported without touching speech or translation.
 */
interface SubtitleSink {
    val status: StateFlow<SubtitleSinkStatus>

    /** Keep the subtitle view open while a translation screen is shown (also after reconnecting). */
    fun start(config: SubtitleConfig)

    fun updateConfig(config: SubtitleConfig)

    /** Show the subtitle view again after the user switched away from it on the glasses. */
    fun reopen()

    /** Non-blocking. Only the newest update per line is delivered; lines go out oldest first. */
    fun submit(update: SubtitleUpdate)

    fun remove(lineId: Long)

    /** Leave the subtitle view (the glasses return to their home screen). */
    fun stop()
}

data class SubtitleConfig(
    val source: Language,
    val target: Language,
    val display: DisplaySettings,
    /** Shown as the audio-source icon on the display. */
    val microphone: MicrophoneSource,
)

data class SubtitleUpdate(val lineId: Long, val original: String, val translation: String, val isFinal: Boolean)

sealed interface SubtitleSinkStatus {
    /** No translation screen is open. */
    data object Idle : SubtitleSinkStatus
    data object NoGlasses : SubtitleSinkStatus
    data object Opening : SubtitleSinkStatus
    data object Active : SubtitleSinkStatus
    /** The glasses switched to another view (touchpad, screen timeout). */
    data object ViewLeft : SubtitleSinkStatus
    data class Failed(val timedOut: Boolean) : SubtitleSinkStatus
}
