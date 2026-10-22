package com.smartglasses.app.data.speech

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Live voice input, ported unchanged from the Translation project's
 * src/renderer/src/audio/pcm.ts (SpeechGate, PhraseSegmenter, LIVE_SEGMENTER, rms, toPcm16).
 */

const val SAMPLE_RATE = 16_000

fun rms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) sum += s * s
    return sqrt(sum / samples.size).toFloat()
}

/** Float [-1, 1] -> little-endian signed 16-bit PCM bytes (the engine's input format). */
fun toPcm16(samples: FloatArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
        out[2 * i] = (v and 0xff).toByte()
        out[2 * i + 1] = (v shr 8 and 0xff).toByte()
    }
    return out
}

/**
 * Speech / non-speech decision per audio block. Adapts to the room's noise floor so it works
 * with quiet microphones and in noisy rooms.
 */
class SpeechGate(
    /** Absolute minimum, just above digital silence; the real decision is 4x the noise floor. */
    private val minSpeechRms: Float = 0.0003f,
    /** Blocks at least this loud always count as speech, even if the floor was learned from speech. */
    private val loudRms: Float = 0.06f,
) {
    private var floor = Float.POSITIVE_INFINITY

    fun isSpeech(blockRms: Float): Boolean {
        floor = min(floor * 1.002f + 1e-6f, max(blockRms, 1e-5f))
        return blockRms >= min(max(minSpeechRms, floor * 4), loudRms)
    }
}

data class SegmenterOptions(
    /** A pause this long ends a phrase. */
    val pauseMs: Int = 700,
    /** A phrase is cut here even without a pause. */
    val maxPhraseMs: Int = 15_000,
    /** Audio kept from just before speech starts, so first syllables are not clipped. */
    val prerollMs: Int = 300,
    /** How often the phrase so far is offered for a live (partial) update. */
    val partialEveryMs: Int = 600,
)

enum class SegmenterEvent { PARTIAL, FINAL }

/**
 * Splits the continuous microphone stream into phrases at pauses. [push] returns PARTIAL when the
 * phrase so far should be re-recognised (live text) and FINAL when it is complete; [takePhrase]
 * then hands it over.
 */
class PhraseSegmenter(private val opts: SegmenterOptions = SegmenterOptions()) {
    private val gate = SpeechGate()
    private val preroll = ArrayDeque<FloatArray>()
    private var prerollMs = 0
    private val blocks = ArrayList<FloatArray>()
    private var phraseMs = 0
    private var speechMs = 0
    private var silentMs = 0
    private var sincePartialMs = 0
    private var noSpeechMs = 0

    fun push(block: FloatArray, blockRms: Float, blockMs: Int): SegmenterEvent? {
        val speech = gate.isSpeech(blockRms)
        noSpeechMs = if (speech) 0 else noSpeechMs + blockMs
        if (blocks.isEmpty()) {
            if (!speech) {
                preroll.addLast(block)
                prerollMs += blockMs
                while (preroll.size > 1 && prerollMs - blockMs >= opts.prerollMs) {
                    preroll.removeFirst()
                    prerollMs -= blockMs
                }
                return null
            }
            blocks.addAll(preroll)
            blocks.add(block)
            phraseMs = prerollMs + blockMs
            preroll.clear()
            prerollMs = 0
            speechMs = blockMs
            silentMs = 0
            sincePartialMs = 0
            return null
        }
        blocks.add(block)
        phraseMs += blockMs
        sincePartialMs += blockMs
        if (speech) {
            speechMs += blockMs
            silentMs = 0
        } else {
            silentMs += blockMs
        }
        if (silentMs >= opts.pauseMs || phraseMs >= opts.maxPhraseMs) return SegmenterEvent.FINAL
        if (sincePartialMs >= opts.partialEveryMs) {
            sincePartialMs = 0
            return SegmenterEvent.PARTIAL
        }
        return null
    }

    /** A phrase has started and not yet been taken. */
    val inPhrase: Boolean get() = blocks.isNotEmpty()

    /** Milliseconds of speech in the current phrase (very short ones are clicks, not words). */
    val phraseSpeechMs: Int get() = speechMs

    /** Time since the last block that counted as speech. */
    val msWithoutSpeech: Int get() = noSpeechMs

    /** The phrase so far (for a live update); the segmenter keeps it. */
    fun phrase(): FloatArray = concat(blocks)

    /** Hands over the complete phrase and starts waiting for the next one. */
    fun takePhrase(): FloatArray {
        val audio = concat(blocks)
        blocks.clear()
        phraseMs = 0
        speechMs = 0
        silentMs = 0
        sincePartialMs = 0
        return audio
    }

    private fun concat(chunks: List<FloatArray>): FloatArray {
        val out = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        return out
    }
}
