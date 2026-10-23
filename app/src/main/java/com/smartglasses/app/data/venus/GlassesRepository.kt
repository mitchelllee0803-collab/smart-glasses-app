package com.smartglasses.app.data.venus

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.jueyuantech.venussdk.VNConstant
import com.smartglasses.app.data.bluetooth.BluetoothAvailabilityMonitor
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.di.ApplicationScope
import com.smartglasses.app.domain.model.BluetoothAvailability
import com.smartglasses.app.domain.model.ConnectFailure
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.GlassesDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connection to the glasses through VNCommon (guide §4.2) and its real state: a connect request
 * only counts once the SDK reports CONNECT_SUCCESS. A dropped link is reconnected with back-off
 * unless the user disconnected. All state changes run on one confined dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GlassesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val venus: VenusClient,
    private val availability: BluetoothAvailabilityMonitor,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var connectJob: Job? = null
    private var watchdogJob: Job? = null

    init {
        scope.launch(confined) { venus.events.collect(::onEvent) }
        scope.launch(confined) {
            availability.state.collect { if (it != BluetoothAvailability.READY) onBluetoothUnavailable() }
        }
        val aclReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val address = device?.address ?: return
                scope.launch(confined) { if (currentDevice()?.address == address) onLinkLost("ACL disconnected") }
            }
        }
        ContextCompat.registerReceiver(
            context, aclReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun connect(device: GlassesDevice) {
        scope.launch(confined) {
            val s = _state.value
            if ((s is ConnectionState.Connecting || s is ConnectionState.Connected) && currentDevice()?.address == device.address) return@launch
            connectJob?.cancel()
            stopWatchdog()
            _state.value = ConnectionState.Connecting(device)
            if (s is ConnectionState.Connected && !s.device.simulated) venus.disconnect()
            connectJob = launch {
                if (device.simulated) {
                    delay(SIMULATED_CONNECT_MS)
                    _state.value = ConnectionState.Connected(device, battery = SIMULATED_BATTERY)
                    return@launch
                }
                val failure = attempt(device)
                _state.value = if (failure == null) connected(device) else ConnectionState.Failed(device, failure)
            }
        }
    }

    /** User-initiated: no automatic reconnection afterwards. */
    fun disconnect() {
        scope.launch(confined) {
            connectJob?.cancel()
            stopWatchdog()
            val previous = _state.value
            if (previous !is ConnectionState.Disconnected) {
                // State first: the ACL broadcast caused by disconnecting must not look like a lost link.
                _state.value = ConnectionState.Disconnected
                if (!previous.isSimulated()) venus.disconnect()
            }
        }
    }

    /** Clears a failure shown on screen. */
    fun dismissFailure() {
        scope.launch(confined) { if (_state.value is ConnectionState.Failed) _state.value = ConnectionState.Disconnected }
    }

    /** One connection attempt; null on success. Runs in [confined]. */
    @SuppressLint("MissingPermission") // Only reachable from connect(), which the UI offers when Bluetooth is READY.
    private suspend fun attempt(device: GlassesDevice): ConnectFailure? {
        val adapter = availability.bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) return ConnectFailure.BLUETOOTH_OFF
        val bluetoothDevice = runCatching { adapter.getRemoteDevice(device.address) }.getOrNull()
            ?: return ConnectFailure.REJECTED
        if (adapter.isDiscovering) adapter.cancelDiscovery() // discovery slows down connections
        var sdkThrew = false
        val outcome = coroutineScope {
            // Subscribe before connecting so a fast CONNECT_SUCCESS is not missed.
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                venus.events.filterIsInstance<VenusEvent.ServiceState>().first()
            }
            try {
                venus.connect(bluetoothDevice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unexpected SDK exception must not crash the app: report a failed attempt.
                Log.e(TAG, "VNCommon.connect threw", e)
                reply.cancel()
                sdkThrew = true
                return@coroutineScope null
            }
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { reply.await() }.also { reply.cancel() }
        }
        if (sdkThrew) return ConnectFailure.SDK_ERROR
        return when (outcome?.state) {
            VNConstant.ServiceState.CONNECT_SUCCESS -> {
                settings.setLastGlasses(device.address)
                null
            }
            null -> {
                Log.w(TAG, "connect timed out after ${CONNECT_TIMEOUT_MS}ms")
                venus.disconnect() // abandon the pending attempt
                ConnectFailure.TIMEOUT
            }
            else -> ConnectFailure.REJECTED
        }
    }

    private fun connected(device: GlassesDevice): ConnectionState.Connected {
        startWatchdog()
        scope.launch(confined) {
            val status = venus.systemStatus() ?: return@launch
            _state.update { s ->
                if (s is ConnectionState.Connected && s.device.address == device.address) {
                    s.copy(battery = status.battery ?: s.battery, charging = status.chargeState == VNConstant.ChargeState.CHARGING)
                } else s
            }
        }
        return ConnectionState.Connected(device)
    }

    private fun onEvent(event: VenusEvent) {
        when (event) {
            is VenusEvent.ServiceState ->
                if (event.state == VNConstant.ServiceState.CONNECT_ERROR && _state.value is ConnectionState.Connected) {
                    onLinkLost("CONNECT_ERROR while connected")
                }
            is VenusEvent.Battery -> _state.update { if (it is ConnectionState.Connected) it.copy(battery = event.percent) else it }
            is VenusEvent.Charging -> _state.update { if (it is ConnectionState.Connected) it.copy(charging = event.charging) else it }
            VenusEvent.DeviceReset -> if (_state.value is ConnectionState.Connected) onLinkLost("device reset/reboot")
            else -> Unit
        }
    }

    /** Runs in [confined]. Reconnects with back-off; gives up after [RECONNECT_DELAYS_MS]. */
    private fun onLinkLost(reason: String) {
        val device = (_state.value as? ConnectionState.Connected)?.device ?: return
        Log.w(TAG, "link lost: $reason")
        stopWatchdog()
        connectJob?.cancel()
        connectJob = scope.launch(confined) {
            for ((index, wait) in RECONNECT_DELAYS_MS.withIndex()) {
                _state.value = ConnectionState.Reconnecting(device, index + 1, RECONNECT_DELAYS_MS.size)
                delay(wait)
                if (availability.state.value != BluetoothAvailability.READY) break
                if (attempt(device) == null) {
                    _state.value = connected(device)
                    return@launch
                }
            }
            _state.value = ConnectionState.Failed(device, ConnectFailure.LOST)
        }
    }

    private fun onBluetoothUnavailable() {
        val device = currentDevice() ?: return
        if (device.simulated) return // the test device does not use Bluetooth
        connectJob?.cancel()
        stopWatchdog()
        _state.value = ConnectionState.Failed(device, ConnectFailure.BLUETOOTH_OFF)
    }

    /** VNCommon.isConnected() as a backstop in case the SDK reports no event when the link drops. */
    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = scope.launch(confined) {
            var misses = 0
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                misses = if (venus.isConnected()) 0 else misses + 1
                if (misses >= WATCHDOG_MISSES) {
                    onLinkLost("isConnected() false")
                    return@launch
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun ConnectionState.isSimulated(): Boolean = when (this) {
        is ConnectionState.Connecting -> device.simulated
        is ConnectionState.Connected -> device.simulated
        is ConnectionState.Reconnecting -> device.simulated
        is ConnectionState.Failed -> device.simulated
        ConnectionState.Disconnected -> false
    }

    private fun currentDevice(): GlassesDevice? = when (val s = _state.value) {
        is ConnectionState.Connecting -> s.device
        is ConnectionState.Connected -> s.device
        is ConnectionState.Reconnecting -> s.device
        else -> null
    }

    private companion object {
        const val TAG = "GlassesRepository"
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val SIMULATED_CONNECT_MS = 800L
        const val SIMULATED_BATTERY = 82
        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val WATCHDOG_MISSES = 2
        val RECONNECT_DELAYS_MS = listOf(1_000L, 3_000L, 6_000L, 12_000L, 20_000L)
    }
}
