package com.offlinetranslate.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import com.offlinetranslate.engine.CancelCheck
import com.offlinetranslate.engine.ChineseConverter
import com.offlinetranslate.engine.Detect
import com.offlinetranslate.engine.EngineException
import com.offlinetranslate.engine.ErrorCode
import com.offlinetranslate.engine.ImportProgress
import com.offlinetranslate.engine.Lang
import com.offlinetranslate.engine.MODELS_MANIFEST
import com.offlinetranslate.engine.ModelAvailability
import com.offlinetranslate.engine.ModelIndex
import com.offlinetranslate.engine.ModelSource
import com.offlinetranslate.engine.ModelStore
import com.offlinetranslate.engine.OcrLine
import com.offlinetranslate.engine.OcrText
import com.offlinetranslate.engine.Preprocess
import com.offlinetranslate.engine.SentenceTranslator
import com.offlinetranslate.engine.Speech
import com.offlinetranslate.engine.SpeechService
import com.offlinetranslate.engine.TranslationService
import com.offlinetranslate.engine.round1
import com.offlinetranslate.engine.validateText
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "OfflineEngine"
const val ENGINE_VERSION = "0.4.1"
const val SPEECH_MODEL = "sense-voice-small"
private const val SPEECH_PREFIX = "speech/sense-voice-small/"
private const val MAX_LOADED_TRANSLATORS = 3 // a pivot route needs two at once
private const val SPEECH_IDLE_UNLOAD_MS = 10 * 60 * 1000L
private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
private const val MAX_PIXELS = 40_000_000
private const val MIN_DIMENSION = 8

/**
 * The Android translation / OCR / speech engine. Mirrors the Windows worker: same models,
 * same routing and post-processing (shared Kotlin core), all on the device.
 * The models are imported by the user from the Translate-models folder (see [ModelStore]).
 */
class OfflineEngine(context: Context) : ModelAvailability {
    private val app = context.applicationContext
    private val assets = app.assets
    private val threads = max(1, min(4, Runtime.getRuntime().availableProcessors() / 2))

    // Translation/detection on one thread, OCR/speech on another (like the worker's pool).
    private val textExecutor = Executors.newSingleThreadExecutor { Thread(it, "ot-text") }
    private val mediaExecutor = Executors.newSingleThreadExecutor { Thread(it, "ot-media") }
    private val importExecutor = Executors.newSingleThreadExecutor { Thread(it, "ot-import") }
    private val importing = AtomicBoolean(false)
    private val importCancelled = AtomicBoolean(false)
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()

    @Volatile var lastError: String? = null; private set
    @Volatile var ready = false; private set
    @Volatile private var argos: Map<String, ArgosEntry> = emptyMap()
    @Volatile private var speechAvailable = false
    @Volatile private var ocrAvailable: Map<String, Boolean> = emptyMap()
    @Volatile private var openccAvailable = false
    private lateinit var store: ModelStore
    private lateinit var manifest: JSONObject

    private var converter: NativeChineseConverter? = null
    private lateinit var translation: TranslationService
    private val translators = LinkedHashMap<String, NativeArgosPackage>(8, 0.75f, true) // LRU order
    private var speech: SenseVoiceRecognizer? = null
    private var speechLastUsed = 0L
    private val tess = mutableMapOf<String, TessBaseAPI>()
    // No-backup storage: Android's cloud backup must never try to upload ~760 MB of models.
    private val modelsDir = File(app.noBackupFilesDir, "models")
    private val openccDir = File(modelsDir, "opencc")

    data class ArgosEntry(val id: String, val dir: File?, val packageName: String, val version: String, val sha256: String, val problems: List<String>)

    // ----------------------------------------------------------- start-up
    fun start(onStatus: () -> Unit) {
        textExecutor.execute {
            try {
                Native.nativeInit()
                manifest = JSONObject(assets.open("models/$MODELS_MANIFEST").bufferedReader().readText())
                store = ModelStore(ModelIndex.parse(assets.open("models/models.expected.json").bufferedReader().readText()), modelsDir)
                store.cleanStaging() // left over if the app was closed during an import
                scanAndPrepare()
                ready = true
                Log.i(TAG, "engine ready: ${argos.size} packages, ocr=$ocrAvailable, speech=$speechAvailable, threads=$threads")
            } catch (t: Throwable) {
                lastError = "The offline engine could not start: ${t.message}"
                Log.e(TAG, "start failed", t)
            }
            onStatus()
        }
    }

    /** Checks which imported models are usable. Runs at start-up and after every import. */
    private fun scanAndPrepare() {
        val installed = store.installed()
        // Map package directories by their metadata codes (like the Windows registry).
        val dirs = mutableMapOf<String, String>()
        for (meta in store.index.files.filter { it.path.startsWith("argos/packages/") && it.path.endsWith("/metadata.json") }) {
            if (!store.isInstalled(meta.path, installed)) continue
            val m = try { JSONObject(store.file(meta.path).readText()) } catch (_: Exception) { continue }
            dirs["${m.optString("from_code")}_${m.optString("to_code")}"] = meta.path.removeSuffix("metadata.json")
        }
        val entries = mutableMapOf<String, ArgosEntry>()
        val list = manifest.getJSONArray("argos")
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val id = e.getString("id")
            val prefix = dirs[id]
            val problems = mutableListOf<String>()
            if (prefix == null) problems += "Translation model ${e.getString("file")} is not imported"
            else for (f in store.index.under(prefix)) if (!store.isInstalled(f.path, installed)) problems += "Missing file: ${f.path}"
            entries[id] = ArgosEntry(
                id, prefix?.let { store.file(it) }, e.getString("file").removeSuffix(".argosmodel"),
                e.getString("packageVersion"), e.getString("sha256"), problems,
            )
        }
        argos = entries

        val tessdata = manifest.getJSONArray("tessdata")
        ocrAvailable = (0 until tessdata.length()).associate { i ->
            val e = tessdata.getJSONObject(i)
            e.getString("lang") to store.isInstalled("tessdata/${e.getString("file")}", installed)
        }
        openccAvailable = store.isComplete("opencc/", installed)
        speechAvailable = store.isComplete(SPEECH_PREFIX, installed)
        converter = if (openccAvailable) NativeChineseConverter(openccDir) else null
        translation = TranslationService(this, converterOrMissing, ::translatorFor)
    }

    /** OpenCC, or a stand-in that reports the dictionaries as not imported. */
    private val converterOrMissing = object : ChineseConverter {
        override fun convert(text: String, config: String): String =
            (converter ?: throw EngineException(ErrorCode.RESOURCE_MISSING, "OpenCC conversion dictionaries are not imported.")).convert(text, config)
    }

    // --------------------------------------------------------- availability
    override fun requireArgos(packageId: String): String {
        val e = argos[packageId] ?: throw EngineException(ErrorCode.MODEL_UNAVAILABLE, "No approved model package for route $packageId")
        if (e.problems.isNotEmpty()) throw EngineException(ErrorCode.MODEL_UNAVAILABLE, "Translation model ${e.packageName} is not available: ${e.problems.joinToString("; ")}")
        return e.packageName
    }

    override fun requireOpencc() {
        if (!openccAvailable) throw EngineException(ErrorCode.RESOURCE_MISSING, "OpenCC conversion dictionaries are not imported.")
    }

    private fun requireReady() {
        if (!ready) throw EngineException("WORKER_FAILED", lastError ?: "The offline engine is still starting.")
    }

    val translationReady get() = ready && argos.size == 6 && argos.values.all { it.problems.isEmpty() } && openccAvailable
    val ocrReady get() = ready && ocrAvailable.size == 4 && ocrAvailable.values.all { it }
    val speechReady get() = ready && speechAvailable

    /** Called only on the text thread. Keeps at most three translation models in memory. */
    private fun translatorFor(packageId: String): SentenceTranslator = synchronized(translators) {
        translators[packageId]?.let { return it }
        while (translators.size >= MAX_LOADED_TRANSLATORS) {
            val eldest = translators.entries.first()
            eldest.value.close()
            translators.remove(eldest.key)
            Log.i(TAG, "unloaded translation model ${eldest.key}")
        }
        val e = argos.getValue(packageId)
        val dir = e.dir ?: throw EngineException(ErrorCode.MODEL_UNAVAILABLE, "Translation model $packageId is not imported.")
        val t = NativeArgosPackage(dir, threads)
        translators[packageId] = t
        Log.i(TAG, "loaded translation model $packageId")
        t
    }

    // ------------------------------------------------------------ requests
    fun cancel(requestId: String) {
        cancels[requestId]?.set(true)
    }

    private fun token(requestId: String): CancelCheck {
        val flag = cancels.getOrPut(requestId) { AtomicBoolean(false) }
        return CancelCheck { if (flag.get()) throw EngineException(ErrorCode.CANCELLED, "Request was cancelled.") }
    }

    private fun <T> run(executor: java.util.concurrent.ExecutorService, requestId: String?, done: (Result<T>) -> Unit, work: (CancelCheck) -> T) {
        if (requestId != null) cancels.putIfAbsent(requestId, AtomicBoolean(false))
        executor.execute {
            val result = try {
                requireReady()
                val check = requestId?.let(::token) ?: CancelCheck { }
                check.check()
                Result.success(work(check))
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                if (requestId != null) cancels.remove(requestId)
            }
            done(result)
        }
    }

    fun translate(requestId: String, text: Any?, source: Any?, target: Any?, done: (Result<Map<String, Any?>>) -> Unit) =
        run(textExecutor, requestId, done) { cancel ->
            translation.translate(text, Lang.of(source, "sourceLanguage"), Lang.of(target, "targetLanguage"), cancel).toMap()
        }

    fun detect(text: Any?, done: (Result<Map<String, Any?>>) -> Unit) = run(textExecutor, null, done) {
        val t = validateText(text)
        requireOpencc()
        Detect.detect(t, converterOrMissing)
    }

    // ------------------------------------------------------------------ OCR
    fun recognize(requestId: String, image: ByteArray, language: Any?, layout: String, maxBytes: Int, done: (Result<Map<String, Any?>>) -> Unit) =
        run(mediaExecutor, requestId, done) { cancel -> recognizeNow(image, Lang.of(language), layout, min(maxBytes, MAX_IMAGE_BYTES), cancel) }

    private fun recognizeNow(raw: ByteArray, lang: Lang, layout: String, maxBytes: Int, cancel: CancelCheck): Map<String, Any?> {
        val langs = if (lang == Lang.EN) "eng" else "${lang.tesseract}+eng"
        for (l in langs.split("+")) if (ocrAvailable[l] != true) throw EngineException(ErrorCode.RESOURCE_MISSING, "OCR language data '$l' is not imported.")
        val psms = when (layout) {
            "auto" -> listOf(3, 6)
            "page" -> listOf(3)
            "block" -> listOf(6)
            "sparse" -> listOf(11)
            else -> throw EngineException(ErrorCode.INVALID_REQUEST, "layout must be one of auto, page, block, sparse")
        }
        val decoded = decodeImage(raw, maxBytes)
        val started = System.nanoTime()
        val bmp = decoded.bitmap
        val argb = IntArray(bmp.width * bmp.height)
        bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        // Default preprocessing chosen by the Windows OCR benchmark: grayscale + autocontrast.
        val gray = Preprocess.autocontrast(Preprocess.grayscale(argb, bmp.width, bmp.height))
        val applied = mutableListOf("grayscale", "autocontrast")
        var work = grayBitmap(gray.pixels, gray.width, gray.height)
        var scale = 1.0
        val api = tessFor(langs)

        fun runAll(img: Bitmap, sc: Double): List<Pair<Int, List<OcrLine>>> = psms.map { psm ->
            cancel.check()
            api.setPageSegMode(psm)
            api.setImage(img)
            val hocr = api.getHOCRText(0) ?: ""
            api.clear()
            psm to OcrText.groupWords(OcrText.parseHocr(hocr), lang, sc)
        }

        var candidates = runAll(work, scale)
        // Adaptive upscale: enlarge small text towards ~36 px line height and keep the better pass.
        val heights = candidates.flatMap { (_, lines) -> lines.map { it.bbox.h * scale } }
        if (heights.isNotEmpty()) {
            val medianH = com.offlinetranslate.engine.median(heights.map { it.roundToInt() })
            val factor = min(min(OcrText.TARGET_LINE_HEIGHT / max(1.0, medianH), 4.0), 6000.0 / max(work.width, work.height))
            if (medianH < OcrText.SMALL_LINE_HEIGHT && factor > 1.2) {
                val big = Bitmap.createScaledBitmap(work, (work.width * factor).roundToInt(), (work.height * factor).roundToInt(), true)
                val retry = runAll(big, scale * factor)
                if (retry.maxOf { OcrText.weightedConfidence(it.second) } > candidates.maxOf { OcrText.weightedConfidence(it.second) }) {
                    candidates = retry
                    applied += "adaptive-upscale x%.2f".format(factor)
                }
                if (big !== work) big.recycle()
            }
        }
        val (psm, best) = OcrText.chooseBest(candidates)
        work.recycle()
        val layoutUsed = mapOf(3 to "page", 6 to "block", 11 to "sparse").getValue(psm)
        return mapOf(
            "text" to OcrText.assembleText(best, lang),
            "lines" to best.map { it.toMap() },
            "language" to lang.code,
            "tesseractLanguages" to langs,
            "meanConfidence" to round1(OcrText.weightedConfidence(best)),
            "imageWidth" to decoded.width,
            "imageHeight" to decoded.height,
            "elapsedMs" to (System.nanoTime() - started) / 1_000_000,
            "layout" to if (layout == "auto") layoutUsed else layout,
            "preprocessing" to applied,
            "image" to mapOf(
                "format" to decoded.format, "width" to decoded.width, "height" to decoded.height,
                "bytes" to raw.size, "exifTransposed" to decoded.exifTransposed,
            ),
        ).also { bmp.recycle() }
    }

    private fun tessFor(langs: String): TessBaseAPI = tess.getOrPut(langs) {
        val api = TessBaseAPI()
        // Tesseract expects the folder that contains "tessdata".
        if (!api.init(modelsDir.absolutePath, langs, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            throw EngineException(ErrorCode.OCR_FAILED, "Tesseract could not load '$langs'.")
        }
        api
    }

    private class DecodedImage(val bitmap: Bitmap, val format: String, val width: Int, val height: Int, val exifTransposed: Boolean)

    private fun decodeImage(raw: ByteArray, maxBytes: Int): DecodedImage {
        if (raw.isEmpty()) throw EngineException(ErrorCode.INVALID_IMAGE, "The image is empty.")
        if (raw.size > maxBytes) throw EngineException(ErrorCode.IMAGE_TOO_LARGE, "Image is %.1f MB; the maximum is %d MB.".format(raw.size / 1048576.0, maxBytes / 1048576))
        val format = sniffFormat(raw) ?: throw EngineException(ErrorCode.INVALID_IMAGE, "Unsupported image format. Use PNG, JPEG, WEBP or BMP.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw EngineException(ErrorCode.INVALID_IMAGE, "Image is corrupt or truncated.")
        if (w.toLong() * h > MAX_PIXELS) throw EngineException(ErrorCode.IMAGE_TOO_LARGE, "Image is ${w}x$h pixels; the maximum is 40,000,000 pixels.")
        if (w < MIN_DIMENSION || h < MIN_DIMENSION) throw EngineException(ErrorCode.INVALID_IMAGE, "Image is too small (${w}x$h).")
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw EngineException(ErrorCode.INVALID_IMAGE, "Image is corrupt or truncated.")
        // Orientation correction from the EXIF tag (phone photos).
        val orientation = try {
            ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        val transposed = !matrix.isIdentity
        if (transposed) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        return DecodedImage(bmp, format, bmp.width, bmp.height, transposed)
    }

    private fun sniffFormat(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4e.toByte() && b[3] == 0x47.toByte() -> "PNG"
        b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte() -> "JPEG"
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "WEBP"
        b.size >= 2 && b[0] == 0x42.toByte() && b[1] == 0x4d.toByte() -> "BMP"
        else -> null
    }

    private fun grayBitmap(pixels: IntArray, w: Int, h: Int): Bitmap {
        val argb = IntArray(pixels.size) { val v = pixels[it]; (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    // --------------------------------------------------------------- speech
    /** Called only on the media thread. */
    /** The recognizer for [language] (null = detect); another language replaces the loaded one. */
    private fun speechModel(language: Lang?): SenseVoiceRecognizer {
        if (!speechAvailable) throw EngineException(ErrorCode.MODEL_UNAVAILABLE, "The speech recognition model is not imported.")
        speechLastUsed = System.currentTimeMillis()
        val svLang = Speech.senseVoiceLanguage(language)
        speech?.let { if (it.language == svLang) return it }
        speech?.close() // only one recognizer (≈ 350 MB) in memory
        speech = null
        return SenseVoiceRecognizer(store.file(SPEECH_PREFIX.removeSuffix("/")), threads, svLang).also {
            speech = it
            Log.i(TAG, "loaded speech model ($svLang)")
        }
    }

    // Releases the speech model after 10 idle minutes (checked every minute, on the media thread).
    private val idleScheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "ot-idle").apply { isDaemon = true } }.also {
        it.scheduleWithFixedDelay({
            mediaExecutor.execute {
                if (speech != null && System.currentTimeMillis() - speechLastUsed > SPEECH_IDLE_UNLOAD_MS) {
                    speech?.close()
                    speech = null
                    Log.i(TAG, "unloaded idle speech model")
                }
            }
        }, 1, 1, java.util.concurrent.TimeUnit.MINUTES)
    }

    fun prepareSpeech(language: Any?, done: (Result<Map<String, Any?>>) -> Unit) = run(mediaExecutor, null, done) {
        val t0 = System.nanoTime()
        speechModel(if (language == null || language == "auto") null else Lang.of(language))
        mapOf("model" to SPEECH_MODEL, "loaded" to true, "elapsedMs" to (System.nanoTime() - t0) / 1_000_000)
    }

    /** One phrase of live voice input; [partial] = the phrase so far, while the user is still speaking. */
    fun transcribe(
        requestId: String, pcm: ByteArray, sampleRate: Any?, language: Any?, partial: Boolean,
        done: (Result<Map<String, Any?>>) -> Unit,
    ) = run(mediaExecutor, requestId, done) { cancel ->
        val lang = if (language == "auto") null else Lang.of(language)
        val audio = Speech.decodePcm16(pcm, sampleRate)
        if (lang == null || lang.chinese) requireOpencc()
        val service = SpeechService(speechModel(lang), SPEECH_MODEL, { text, l ->
            if (l == Lang.ZH_HANS) converterOrMissing.toSimplified(text) else converterOrMissing.normalizeTraditional(text)
        })
        try {
            service.transcribe(audio, lang, partial, cancel)
        } finally {
            speechLastUsed = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------ metadata
    fun loadedModels(): List<String> = synchronized(translators) { translators.keys.toList() } + listOfNotNull(if (speech != null) SPEECH_MODEL else null)

    fun problems(): List<String> = listOfNotNull(lastError) +
        (if (ready && argos.values.all { it.dir == null }) listOf("Models are not imported yet (Translate-models folder)") else emptyList()) +
        argos.values.flatMap { it.problems } +
        ocrAvailable.filterValues { !it }.keys.map { "OCR language data '$it' is missing" } +
        (if (ready && !openccAvailable) listOf("OpenCC dictionaries are missing") else emptyList()) +
        (if (ready && !speechAvailable) listOf("Speech model is missing") else emptyList())

    fun listModels(): Map<String, Any?> = mapOf(
        "translation" to argos.values.map {
            mapOf("id" to it.id, "package" to it.packageName, "fromCode" to it.id.substringBefore('_'), "toCode" to it.id.substringAfter('_'),
                "packageVersion" to it.version, "archiveSha256" to it.sha256, "available" to it.problems.isEmpty(), "problems" to it.problems)
        },
        "ocr" to ocrAvailable.map { (lang, ok) -> mapOf("lang" to lang, "file" to "$lang.traineddata", "available" to ok, "problems" to if (ok) emptyList<String>() else listOf("not imported")) },
        "tesseract" to mapOf("available" to true, "version" to try { tess.values.firstOrNull()?.version ?: TessBaseAPI().let { val v = it.version; it.recycle(); v } } catch (_: Throwable) { null }),
        "opencc" to mapOf("available" to openccAvailable, "configs" to listOf("s2t.json", "t2s.json", "s2tw.json")),
        "speech" to listOf(mapOf("id" to SPEECH_MODEL, "label" to "SenseVoice-Small (live)", "available" to speechAvailable, "problems" to if (speechAvailable) emptyList<String>() else listOf("not imported"))),
        "loadedModels" to loadedModels(),
    )

    // --------------------------------------------------------------- import
    val isImporting: Boolean get() = importing.get()

    fun cancelImport() = importCancelled.set(true)

    /**
     * Copies and verifies the models from [openSource] (the folder the user picked) into a staging
     * area while the app keeps working with the current models, then swaps them in.
     */
    fun importModels(openSource: () -> ModelSource, progress: (ImportProgress) -> Unit, done: (Result<Map<String, Any?>>) -> Unit) {
        if (!importing.compareAndSet(false, true)) {
            done(Result.failure(EngineException(ErrorCode.IMPORT_RUNNING, "A model import is already running.")))
            return
        }
        importCancelled.set(false)
        importExecutor.execute {
            val result = try {
                requireReady()
                val source = openSource()
                val cancel = CancelCheck { if (importCancelled.get()) throw EngineException(ErrorCode.CANCELLED, "The import was cancelled.") }
                modelsDir.mkdirs()
                val staged = store.stage(source, modelsDir.usableSpace, cancel, progress)
                exclusive {
                    unloadNow()
                    store.commit(staged)
                    scanAndPrepare()
                }
                Log.i(TAG, "imported ${staged.files.size} model files (${staged.bytes} bytes), ${staged.alreadyInstalled} already installed, ${staged.missing.size} missing")
                Result.success(staged.toMap())
            } catch (t: Throwable) {
                Log.e(TAG, "model import failed", t)
                Result.failure(t)
            } finally {
                importing.set(false)
            }
            done(result)
        }
    }

    /** Runs [work] on the text thread while the media thread is parked, so no model is in use. */
    private fun <T> exclusive(work: () -> T): T {
        val mediaParked = CountDownLatch(1)
        val release = CountDownLatch(1)
        mediaExecutor.execute { mediaParked.countDown(); release.await() }
        val task = FutureTask {
            mediaParked.await()
            try { work() } finally { release.countDown() }
        }
        textExecutor.execute(task)
        return try { task.get() } catch (e: ExecutionException) { throw e.cause ?: e }
    }

    /** Only inside [exclusive]. */
    private fun unloadNow() {
        synchronized(translators) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
        speech?.close(); speech = null
        tess.values.forEach { it.recycle() }
        tess.clear()
        converter?.close(); converter = null
    }

    /** Releases all loaded models (used by "Restart engine"). */
    fun unloadAll(done: () -> Unit) {
        textExecutor.execute {
            synchronized(translators) {
                translators.values.forEach { it.close() }
                translators.clear()
            }
            mediaExecutor.execute {
                speech?.close(); speech = null
                tess.values.forEach { it.recycle() }
                tess.clear()
                done()
            }
        }
    }
}
