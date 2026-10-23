package com.smartglasses.app.data.venus

import com.smartglasses.app.domain.model.ConnectionState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The glasses take brightness as 0–255 (guide §4.6); the app shows a percentage. */
object BrightnessScale {
    const val MAX_LEVEL = 255
    /** At 0 the display is black and the glasses look broken, so the slider stops here. */
    const val MIN_PERCENT = 5
    const val DEFAULT_PERCENT = 50

    fun toLevel(percent: Int): Int = (percent.coerceIn(MIN_PERCENT, 100) * MAX_LEVEL + 50) / 100

    fun toPercent(level: Int): Int =
        ((level.coerceIn(0, MAX_LEVEL) * 100 + MAX_LEVEL / 2) / MAX_LEVEL).coerceIn(MIN_PERCENT, 100)
}

/** What the glasses report: [level] 0–255; [auto] null when they did not say. */
data class BrightnessReading(val level: Int, val auto: Boolean?)

/** The brightness calls of the glasses. */
interface BrightnessLink {
    /** Null when the glasses did not answer. */
    suspend fun read(): BrightnessReading?
    suspend fun set(level: Int): VenusResult
    suspend fun setAuto(enabled: Boolean): VenusResult
}

/** The connected glasses through VenusSDK; the test device keeps the values in memory instead. */
class VenusBrightnessLink @Inject constructor(
    private val venus: VenusClient,
    private val glasses: GlassesRepository,
) : BrightnessLink {
    private val simulated get() = (glasses.state.value as? ConnectionState.Connected)?.device?.simulated == true

    override suspend fun read(): BrightnessReading? {
        if (simulated) return BrightnessReading(testLevel, testAuto)
        val config = venus.brightness() ?: return null
        val level = config.brightness ?: return null
        return BrightnessReading(level, config.autoBrightnessEnabled?.let { it != 0 })
    }

    override suspend fun set(level: Int): VenusResult {
        if (!simulated) return venus.setBrightness(level)
        testLevel = level
        return VenusResult.SUCCESS
    }

    override suspend fun setAuto(enabled: Boolean): VenusResult {
        if (!simulated) return venus.setAutoBrightness(enabled)
        testAuto = enabled
        return VenusResult.SUCCESS
    }

    private companion object {
        // Survive closing the popup, like the real glasses' own setting.
        @Volatile var testLevel = BrightnessScale.toLevel(BrightnessScale.DEFAULT_PERCENT)
        @Volatile var testAuto = false
    }
}

data class BrightnessState(
    val loading: Boolean = true,
    val percent: Int = BrightnessScale.DEFAULT_PERCENT,
    val auto: Boolean = false,
    /** False once the glasses rejected auto brightness (no light sensor). */
    val autoSupported: Boolean = true,
)

enum class BrightnessProblem { READ_FAILED, SET_FAILED, AUTO_UNSUPPORTED }

/**
 * The brightness popup's logic. The slider moves at once; the glasses get the newest level at most
 * every [SEND_INTERVAL_MS] while dragging, and always the level where the finger stopped. Moving
 * the slider turns auto brightness off, since the glasses ignore manual levels while it is on.
 * A rejected change puts the slider back to what the glasses last accepted.
 */
class BrightnessController(private val link: BrightnessLink, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(BrightnessState())
    val state: StateFlow<BrightnessState> = _state.asStateFlow()

    private val _problems = MutableSharedFlow<BrightnessProblem>(extraBufferCapacity = 4)
    val problems: SharedFlow<BrightnessProblem> = _problems.asSharedFlow()

    /** One command at a time over the link. */
    private val mutex = Mutex()

    // What the glasses last confirmed.
    private var glassesPercent = BrightnessScale.DEFAULT_PERCENT
    private var glassesAuto = false

    /** Numbered so that dragging back to a level that just failed still sends it. */
    private data class Request(val percent: Int, val number: Long)
    private val requested = MutableStateFlow<Request?>(null)
    private var requestCount = 0L

    init {
        // A StateFlow keeps only the newest request while a send is under way.
        scope.launch {
            requested.filterNotNull().collect { request ->
                send(request)
                delay(SEND_INTERVAL_MS)
            }
        }
    }

    /** Reads the glasses' current values (each time the popup opens). */
    fun load() {
        _state.update { it.copy(loading = true) }
        scope.launch {
            val reading = mutex.withLock { link.read() }
            if (reading == null) {
                _state.update { it.copy(loading = false) }
                _problems.tryEmit(BrightnessProblem.READ_FAILED)
                return@launch
            }
            glassesPercent = BrightnessScale.toPercent(reading.level)
            glassesAuto = reading.auto ?: false
            _state.update { it.copy(loading = false, percent = glassesPercent, auto = glassesAuto) }
        }
    }

    fun setPercent(percent: Int) {
        val clamped = percent.coerceIn(BrightnessScale.MIN_PERCENT, 100)
        _state.update { it.copy(percent = clamped, auto = false) }
        requested.value = Request(clamped, ++requestCount)
    }

    fun setAuto(enabled: Boolean) {
        _state.update { it.copy(auto = enabled) }
        scope.launch {
            mutex.withLock {
                val result = link.setAuto(enabled)
                when {
                    result == VenusResult.SUCCESS -> glassesAuto = enabled
                    // Rejected outright: no light sensor. Hide the switch.
                    result == VenusResult.FAILED && enabled -> {
                        _state.update { it.copy(auto = glassesAuto, autoSupported = false) }
                        _problems.tryEmit(BrightnessProblem.AUTO_UNSUPPORTED)
                    }
                    else -> {
                        _state.update { it.copy(auto = glassesAuto) }
                        _problems.tryEmit(BrightnessProblem.SET_FAILED)
                    }
                }
            }
        }
    }

    private suspend fun send(request: Request) = mutex.withLock {
        if (glassesAuto && link.setAuto(false) == VenusResult.SUCCESS) glassesAuto = false
        val ok = !glassesAuto && link.set(BrightnessScale.toLevel(request.percent)) == VenusResult.SUCCESS
        if (ok) {
            glassesPercent = request.percent
        } else if (requested.value?.number == request.number) {
            // Nothing newer is waiting: show what the glasses really have.
            _state.update { it.copy(percent = glassesPercent, auto = glassesAuto) }
            _problems.tryEmit(BrightnessProblem.SET_FAILED)
        }
    }

    companion object {
        const val SEND_INTERVAL_MS = 150L
    }
}
