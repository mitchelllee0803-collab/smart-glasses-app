package com.offlinetranslate.engine

import kotlin.math.abs
import kotlin.math.max

/*
 * Speech recognition with SenseVoice-Small: input validation and the text / language rules,
 * ported from worker/offline_translate_worker/speech.py (same results on phone and PC; see
 * SpeechTest, checked against cases produced by the Python worker). Inference is sherpa-onnx
 * behind SpeechBackend.
 */

object Speech {
    const val SAMPLE_RATE = 16_000
    const val MIN_SECONDS = 0.3
    const val MAX_SECONDS = 120
    const val MAX_PCM_BYTES = MAX_SECONDS * SAMPLE_RATE * 2
    const val DIGITAL_SILENCE_PEAK = 0.001f

    // SenseVoice's language tags; Cantonese is written in Chinese characters.
    private val LANG_TAGS = mapOf("<|en|>" to "en", "<|ko|>" to "ko", "<|zh|>" to "zh", "<|yue|>" to "zh", "<|ja|>" to "ja")

    // On silence, noise and breaths SenseVoice still emits one short filler (per language:
    // "그." / "I." / "Yeah." / "我。" / "没。" / "The.").
    private val FILLERS = setOf("그", "음", "어", "아", "嗯", "啊", "呃", "我", "没", "the", "a", "i", "uh", "um", "oh", "yeah")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+") // Python: [\W_]+

    // CJK symbols, Han (incl. extension A and compatibility ideographs), full-width forms; not Hangul.
    private const val CJK = "　-〿㐀-䶿一-鿿豈-﫿＀-￯"
    private val SPACE_BETWEEN_CJK = Regex("(?<=[$CJK])$UNICODE_SPACE+(?=[$CJK])")
    private val SPACES = Regex("$UNICODE_SPACE+")

    // Safety net: characters that cannot belong to the selected language (a misrecognised phrase).
    private const val KANA = "\u3040-\u30ff"
    private const val HAN = "\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff"
    private const val HANGUL = "\u1100-\u11ff\u3130-\u318f\uac00-\ud7af"
    private val FOREIGN_SCRIPT = mapOf(
        Lang.EN to Regex("[$KANA$HAN$HANGUL]"),
        Lang.KO to Regex("[$KANA$HAN]"),
        Lang.ZH_HANS to Regex("[$KANA$HANGUL]"),
        Lang.ZH_HANT to Regex("[$KANA$HANGUL]"),
    )

    /** Language the recognizer is fixed to (SenseVoice's per-request option has no effect). */
    fun senseVoiceLanguage(language: Lang?): String = when (language) {
        null -> "auto"
        Lang.EN -> "en"
        Lang.KO -> "ko"
        Lang.ZH_HANS, Lang.ZH_HANT -> "zh"
    }

    /** Little-endian 16-bit mono PCM at 16 kHz -> float [-1, 1]. */
    fun decodePcm16(raw: ByteArray, sampleRate: Any?): FloatArray {
        if (sampleRate != SAMPLE_RATE) throw EngineException(ErrorCode.INVALID_AUDIO, "sampleRate must be $SAMPLE_RATE.")
        if (raw.isEmpty()) throw EngineException(ErrorCode.INVALID_AUDIO, "The recording is empty.")
        if (raw.size % 2 != 0) throw EngineException(ErrorCode.INVALID_AUDIO, "16-bit PCM must have an even number of bytes.")
        val n = raw.size / 2
        val seconds = n.toDouble() / SAMPLE_RATE
        if (seconds < MIN_SECONDS) throw EngineException(ErrorCode.NO_SPEECH, "The recording is too short.")
        if (seconds > MAX_SECONDS + 0.5) throw EngineException(ErrorCode.AUDIO_TOO_LONG, "Recording is longer than $MAX_SECONDS seconds.")
        return FloatArray(n) { i ->
            val v = (raw[2 * i].toInt() and 0xff) or (raw[2 * i + 1].toInt() shl 8)
            v.toShort() / 32768f
        }
    }

    /** Trim, collapse whitespace and drop spaces between Chinese characters. */
    fun cleanText(text: String): String = SPACE_BETWEEN_CJK.replace(SPACES.replace(text, " ").trim(), "")

    /** True for empty output, punctuation only, or a lone filler emitted on silence / noise. */
    fun isFiller(text: String): Boolean {
        val core = NOT_WORD.replace(text.lowercase(), "")
        return core.isEmpty() || core in FILLERS
    }

    /**
     * SenseVoice output -> (text, language). A final result fails with NO_SPEECH /
     * UNSUPPORTED_LANGUAGE; a partial result (the user is still speaking) is empty instead.
     */
    fun finishText(rawText: String, langTag: String, requested: Lang?, partial: Boolean, toScript: ((String, Lang) -> String)?): Pair<String, Lang?> {
        var text = cleanText(rawText)
        val detected = LANG_TAGS[langTag]
        val foreign = requested != null && (detected == "ja" || FOREIGN_SCRIPT.getValue(requested).containsMatchIn(text))
        if (isFiller(text) || foreign) {
            if (partial) return "" to null
            throw EngineException(ErrorCode.NO_SPEECH, "No speech was recognised. Please try again, closer to the microphone.")
        }
        if (detected == "ja") {
            if (partial) return "" to null
            throw EngineException(ErrorCode.UNSUPPORTED_LANGUAGE, "Japanese speech was recognised. Voice input supports English, Korean and Chinese.")
        }
        val lang = when {
            requested != null -> requested
            // Spoken Mandarin carries no script: "Detect language" writes Simplified.
            detected == "zh" -> Lang.ZH_HANS
            detected == "en" -> Lang.EN
            detected == "ko" -> Lang.KO
            else -> null
        }
        if (lang != null && lang.chinese && toScript != null) text = toScript(text, lang)
        return text to lang
    }
}

/** Native recognizer (sherpa-onnx SenseVoice in automatic language mode). */
fun interface SpeechBackend {
    /** Returns SenseVoice's raw text and language tag (e.g. "<|ko|>"). */
    fun recognize(audio: FloatArray): Pair<String, String>
}

class SpeechService(
    private val backend: SpeechBackend,
    private val modelName: String,
    /** Converts Chinese output to the requested script (t2s for zh-Hans, s2tw for zh-Hant). */
    private val toScript: (String, Lang) -> String,
    private val clock: () -> Long = { System.nanoTime() },
) {
    /** [language] is a Lang, or null for automatic detection. Same result fields as the Windows worker. */
    fun transcribe(audio: FloatArray, language: Lang?, partial: Boolean = false, cancel: CancelCheck = NO_CANCEL): Map<String, Any?> {
        val started = clock()
        var peak = 0f
        for (v in audio) peak = max(peak, abs(v))
        val (text, lang) = if (peak < Speech.DIGITAL_SILENCE_PEAK) {
            if (!partial) throw EngineException(ErrorCode.NO_SPEECH, "The microphone recorded only silence. Check that it is not muted.")
            "" to null
        } else {
            cancel.check()
            val (raw, tag) = backend.recognize(audio)
            Speech.finishText(raw, tag, language, partial, toScript)
        }
        return mapOf(
            "text" to text,
            "language" to lang?.code,
            "requestedLanguage" to (language?.code ?: "auto"),
            "audioSeconds" to Math.rint(audio.size.toDouble() / Speech.SAMPLE_RATE * 100) / 100,
            "elapsedMs" to (clock() - started) / 1_000_000,
            "model" to modelName,
            "partial" to partial,
        )
    }
}
