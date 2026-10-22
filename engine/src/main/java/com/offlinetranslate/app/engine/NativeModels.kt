package com.offlinetranslate.app.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.offlinetranslate.engine.ChineseConverter
import com.offlinetranslate.engine.EngineException
import com.offlinetranslate.engine.ErrorCode
import com.offlinetranslate.engine.SentenceTranslator
import com.offlinetranslate.engine.Speech
import com.offlinetranslate.engine.SpeechBackend
import java.io.Closeable
import java.io.File

/** Decoding parameters mirrored from Argos Translate 1.11.0 (same as the Windows worker). */
private const val BEAM_SIZE = 4
private const val LENGTH_PENALTY = 0.2f
private const val MAX_DECODING_LENGTH = 512

/**
 * One Argos package: SentencePiece tokenizer + CTranslate2 translator, read from the imported
 * models in app storage (".../models/argos/packages/<dir>").
 */
class NativeArgosPackage(dir: File, threads: Int) : SentenceTranslator, Closeable {
    private val sp = Native.spCreate(File(dir, "sentencepiece.model").absolutePath)
    private val translator = try {
        Native.translatorCreate(File(dir, "model").absolutePath, threads)
    } catch (e: Throwable) {
        Native.spFree(sp)
        throw e
    }

    override fun translateSentences(sentences: List<String>): List<String> {
        val batch = sentences.map { Native.spEncode(sp, it) }.toTypedArray()
        val out = Native.translatorTranslate(translator, batch, BEAM_SIZE, LENGTH_PENALTY, MAX_DECODING_LENGTH)
        // Argos SentencePieceTokenizer.decode(): decode pieces, then '▁' and '_' -> space.
        return out.map { Native.spDecode(sp, it).replace("▁", " ").replace("_", " ") }
    }

    override fun close() {
        Native.translatorFree(translator)
        Native.spFree(sp)
    }
}

/** OpenCC with the imported configs and dictionaries. */
class NativeChineseConverter(private val dir: File) : ChineseConverter, Closeable {
    private val converters = mutableMapOf<String, Long>()

    @Synchronized
    override fun convert(text: String, config: String): String {
        val handle = converters.getOrPut(config) {
            val file = File(dir, config)
            if (!file.isFile) throw EngineException(ErrorCode.RESOURCE_MISSING, "OpenCC config missing: $config")
            Native.openccCreate(file.absolutePath)
        }
        return Native.openccConvert(handle, text)
    }

    @Synchronized
    override fun close() {
        converters.values.forEach(Native::openccFree)
        converters.clear()
    }
}

/**
 * SenseVoice-Small via sherpa-onnx (ONNX Runtime), read from the imported models
 * (".../models/speech/sense-voice-small"). Fixed to one language ([Speech.senseVoiceLanguage]):
 * in automatic mode short live phrases are often recognised as the wrong language.
 */
class SenseVoiceRecognizer(dir: File, threads: Int, val language: String) : SpeechBackend, Closeable {
    private val recognizer = OfflineRecognizer(
        null, // files, not APK assets
        OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply { sampleRate = Speech.SAMPLE_RATE }
            modelConfig = OfflineModelConfig().apply {
                senseVoice = OfflineSenseVoiceModelConfig().apply {
                    model = File(dir, "model.int8.onnx").absolutePath
                    language = this@SenseVoiceRecognizer.language
                    useInverseTextNormalization = true // punctuation and digits
                }
                tokens = File(dir, "tokens.txt").absolutePath
                numThreads = threads
                provider = "cpu"
                debug = false
            }
        },
    )

    @Synchronized
    override fun recognize(audio: FloatArray): Pair<String, String> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, Speech.SAMPLE_RATE)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            return r.text to r.lang
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun close() = recognizer.release()
}
