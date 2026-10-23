package com.smartglasses.app.data.venus

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jueyuantech.venussdk.VNCommon
import com.jueyuantech.venussdk.VNConstant
import com.jueyuantech.venussdk.bean.VNSystemConfig
import com.jueyuantech.venussdk.bean.VNSystemStatus
import com.jueyuantech.venussdk.cb.VNCommonCallBack
import com.jueyuantech.venussdk.cb.VNSystemConfigCallBack
import com.jueyuantech.venussdk.cb.VNSystemStatusCallBack
import com.jueyuantech.venussdk.listener.VNDeviceConnectionListener
import com.jueyuantech.venussdk.listener.VNDeviceServiceListener
import com.jueyuantech.venussdk.listener.VNDeviceStatusListener
import com.jueyuantech.venussdk.listener.VNDeviceUnsolicitedListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Something the glasses reported through a VenusSDK listener. */
sealed interface VenusEvent {
    /** [VNConstant.ServiceState]: CONNECT_SUCCESS or CONNECT_ERROR. */
    data class ServiceState(val state: Int) : VenusEvent
    data class Battery(val percent: Int) : VenusEvent
    data class Charging(val charging: Boolean) : VenusEvent
    /** [VNConstant.View] the glasses now show. */
    data class ViewChanged(val view: Int) : VenusEvent
    data class Screen(val on: Boolean) : VenusEvent
    /** The wearer asked for speech-to-text on the glasses (guide §5.7). */
    data object SttRequest : VenusEvent
    /** Unbind, factory reset or reboot: the link is about to end. */
    data object DeviceReset : VenusEvent
}

enum class VenusResult { SUCCESS, TIMEOUT, FAILED }

/** A view switch requested by the app, with its [SystemClock.elapsedRealtime] time. */
data class RequestedView(val view: Int, val atElapsedMs: Long)

/**
 * Thin coroutine wrapper around the static VenusSDK API; listener callbacks become [events].
 *
 * Every SDK call runs on the main thread. The guide does not say so, but VNCommon.connect()
 * creates android.os.Handler() objects bound to the calling thread's Looper, and throws
 * ("Can't create handler inside thread ... Looper.prepare()") on a plain background thread.
 * The SDK calls themselves only queue work; its socket I/O runs on its own threads.
 */
@Singleton
class VenusClient @Inject constructor() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _events = MutableSharedFlow<VenusEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<VenusEvent> = _events.asSharedFlow()

    init {
        // VNSDK.init has run in SmartGlassesApp.onCreate, before anything can inject this class.
        VNCommon.setDeviceServiceListener(VNDeviceServiceListener { state -> emit(VenusEvent.ServiceState(state)) })
        VNCommon.setDeviceStatusListener(object : VNDeviceStatusListener {
            override fun onSysStateChanged(state: Int) = emit(VenusEvent.Screen(state == VNConstant.SysState.SCREEN_ON))
            override fun onChargeStateChanged(state: Int) = emit(VenusEvent.Charging(state == VNConstant.ChargeState.CHARGING))
            override fun onBatteryChanged(percent: Int) = emit(VenusEvent.Battery(percent))
            override fun onViewChanged(view: Int) = emit(VenusEvent.ViewChanged(view))
            override fun onTouchEvent(event: Int) = Unit
            override fun onResourceErr(code: Int) {
                Log.w(TAG, "glasses resource error $code")
            }
            override fun onTTMReceived(data: ByteArray?) = Unit
        })
        VNCommon.setDeviceConnectionListener(object : VNDeviceConnectionListener {
            override fun onUnbind() = emit(VenusEvent.DeviceReset)
            override fun onFactoryReset() = emit(VenusEvent.DeviceReset)
            override fun onReboot() = emit(VenusEvent.DeviceReset)
        })
        VNCommon.setDeviceUnsolicitedListener(VNDeviceUnsolicitedListener { emit(VenusEvent.SttRequest) })
    }

    private fun emit(event: VenusEvent) {
        if (event !is VenusEvent.Battery) Log.d(TAG, "event $event")
        if (!_events.tryEmit(event)) Log.w(TAG, "event dropped: $event")
    }

    /** Starts the business connection; the result arrives as [VenusEvent.ServiceState]. */
    suspend fun connect(device: BluetoothDevice) = onSdkThread {
        VNCommon.initDevice(device)
        VNCommon.connect(device)
    }

    suspend fun disconnect() = onSdkThread { VNCommon.disconnect() }

    suspend fun isConnected(): Boolean = onSdkThread { VNCommon.isConnected() }

    suspend fun sendKeepAlive() = onSdkThread { VNCommon.sendKeepAlive() }

    /** The view this app last asked the glasses to show; its onViewChanged echo is not a touchpad action. */
    @Volatile var lastRequestedView: RequestedView? = null
        private set

    /** Switches the glasses to [view] ([VNConstant.View]). */
    suspend fun setView(view: Int): VenusResult {
        lastRequestedView = RequestedView(view, SystemClock.elapsedRealtime())
        return command("setView($view)") { VNCommon.setView(view, it) }
    }

    /**
     * Runs an SDK command that reports through [VNCommonCallBack]. [timeoutMs] only guards against
     * a callback that never arrives; the SDK normally reports its own onTimeOut.
     */
    suspend fun command(name: String, timeoutMs: Long = COMMAND_TIMEOUT_MS, call: (VNCommonCallBack) -> Unit): VenusResult {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                fun finish(r: VenusResult) {
                    if (done.compareAndSet(false, true)) cont.resume(r)
                }
                val callback = object : VNCommonCallBack {
                    override fun onSuccess() = finish(VenusResult.SUCCESS)
                    override fun onTimeOut() = finish(VenusResult.TIMEOUT)
                    override fun onFailed() = finish(VenusResult.FAILED)
                }
                mainHandler.post {
                    try {
                        call(callback)
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "$name threw", e)
                        finish(VenusResult.FAILED)
                    }
                }
            }
        } ?: VenusResult.TIMEOUT
        if (result != VenusResult.SUCCESS) Log.w(TAG, "$name -> $result")
        return result
    }

    /** Battery and charge state, or null when the glasses did not answer. */
    suspend fun systemStatus(): VNSystemStatus? = query("getSystemStatus") { finish ->
        VNCommon.getSystemStatus(object : VNSystemStatusCallBack {
            override fun onSuccess(status: VNSystemStatus?) = finish(status)
            override fun onTimeOut() = finish(null)
            override fun onFailed() = finish(null)
        })
    }

    /** Brightness (0–255) and auto-brightness state (guide §4.6), or null when the glasses did not answer. */
    suspend fun brightness(): VNSystemConfig? = query("getBrightness") { finish ->
        VNCommon.getBrightness(object : VNSystemConfigCallBack {
            override fun onSuccess(config: VNSystemConfig?) = finish(config)
            override fun onTimeOut() = finish(null)
            override fun onFailed() = finish(null)
        })
    }

    suspend fun setBrightness(level: Int): VenusResult = command("setBrightness($level)") { VNCommon.setBrightness(level, it) }

    suspend fun setAutoBrightness(enabled: Boolean): VenusResult {
        val state = if (enabled) VNConstant.EnableState.ENABLED else VNConstant.EnableState.DISABLED
        return command("setAutoBrightnessEnabled($state)") { VNCommon.setAutoBrightnessEnabled(state, it) }
    }

    /** Runs an SDK read whose callback hands [call]'s `finish` the answer (null: none). */
    private suspend fun <T> query(name: String, call: (finish: (T?) -> Unit) -> Unit): T? {
        val result = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                fun finish(value: T?) {
                    if (done.compareAndSet(false, true)) cont.resume(value)
                }
                mainHandler.post {
                    try {
                        call(::finish)
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "$name threw", e)
                        finish(null)
                    }
                }
            }
        }
        if (result == null) Log.w(TAG, "$name -> no answer")
        return result
    }

    private suspend fun <T> onSdkThread(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private companion object {
        const val TAG = "VenusClient"
        const val EVENT_BUFFER = 64
        const val COMMAND_TIMEOUT_MS = 10_000L
    }
}
