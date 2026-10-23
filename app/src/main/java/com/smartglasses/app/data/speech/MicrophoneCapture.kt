package com.smartglasses.app.data.speech

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.smartglasses.app.R
import com.smartglasses.app.domain.model.MicrophoneSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

open class MicrophoneException(message: String) : Exception(message)

/** Live audio from the glasses' or the phone's microphone. */
fun interface AudioInput {
    /**
     * Mono float blocks of [BLOCK_MS] at [SAMPLE_RATE] until the collector is cancelled.
     * [glassesAddress] picks the glasses among several Bluetooth headsets (GLASSES only).
     */
    fun blocks(source: MicrophoneSource, glassesAddress: String?): Flow<FloatArray>

    companion object {
        /** Same block length as the Translation app's AudioWorklet recorder. */
        const val BLOCK_MS = 100
        const val BLOCK_SAMPLES = SAMPLE_RATE * BLOCK_MS / 1000
    }
}

/**
 * Microphone through AudioRecord at 16 kHz mono (the speech model's rate). For the glasses,
 * Android resamples the Bluetooth hands-free audio (16 kHz wideband or 8 kHz narrowband,
 * depending on the link) to this rate. Audio stays in memory.
 */
@Singleton
class MicrophoneCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glassesRoute: GlassesMicrophoneRoute,
) : AudioInput {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun blocks(source: MicrophoneSource, glassesAddress: String?): Flow<FloatArray> = flow {
        val glasses = source == MicrophoneSource.GLASSES
        try {
            val inputDevice = if (glasses) glassesRoute.open(glassesAddress) else null
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw MicrophoneException(context.getString(R.string.error_mic_rate))
            val record = AudioRecord(
                // VOICE_COMMUNICATION follows the Bluetooth communication route; VOICE_RECOGNITION
                // is the phone source tuned for speech recognisers.
                if (glasses) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, AudioInput.BLOCK_SAMPLES * 2 * BUFFERED_BLOCKS),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw MicrophoneException(context.getString(R.string.error_mic_open))
            }
            try {
                inputDevice?.let { record.setPreferredDevice(it) }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw MicrophoneException(context.getString(R.string.error_mic_busy))
                }
                val pcm = ShortArray(AudioInput.BLOCK_SAMPLES)
                while (currentCoroutineContext().isActive) {
                    var filled = 0
                    while (filled < pcm.size) {
                        val n = record.read(pcm, filled, pcm.size - filled)
                        if (n < 0) throw MicrophoneException(context.getString(R.string.error_mic_failed, n))
                        filled += n
                    }
                    emit(FloatArray(pcm.size) { pcm[it] / PCM16_SCALE })
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        } finally {
            if (glasses) glassesRoute.close()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val BUFFERED_BLOCKS = 4
        const val PCM16_SCALE = 32768f
    }
}
