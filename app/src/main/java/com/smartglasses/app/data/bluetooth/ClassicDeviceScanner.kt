package com.smartglasses.app.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.di.ApplicationScope
import com.smartglasses.app.domain.model.BluetoothAvailability
import com.smartglasses.app.domain.model.GlassesDevice
import com.smartglasses.app.domain.model.ScanState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Classic Bluetooth discovery. VenusSDK has no scan API (guide §4.1): the app finds the glasses
 * and hands the BluetoothDevice to VNCommon. Main-thread confined (receiver callbacks and callers).
 *
 * Bluetooth calls are annotated MissingPermission: [start] only runs when
 * [BluetoothAvailabilityMonitor] reports READY, which includes the runtime permissions.
 */
@Singleton
class ClassicDeviceScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val availability: BluetoothAvailabilityMonitor,
    settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val found = LinkedHashMap<String, GlassesDevice>()
    private var receiver: BroadcastReceiver? = null
    private var safetyStop: Job? = null
    @Volatile private var lastGlassesAddress: String? = null

    init {
        scope.launch { settings.settings.collect { lastGlassesAddress = it.lastGlassesAddress } }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = availability.bluetoothAdapter
        if (adapter == null || availability.state.value != BluetoothAvailability.READY) {
            _state.value = ScanState.Failed
            return
        }
        finishDiscovery(publish = false)
        found.clear()
        // Glasses already paired in system settings are listed even if they are not discoverable now.
        adapter.bondedDevices.orEmpty().forEach { put(it, name = it.name, rssi = null) }

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onDiscoveryEvent(intent)
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r

        if (adapter.isDiscovering) adapter.cancelDiscovery()
        if (!adapter.startDiscovery()) {
            finishDiscovery(publish = false)
            _state.value = ScanState.Failed
            return
        }
        _state.value = ScanState.Scanning(sorted())
        // Discovery ends by itself after ~12 s; this only guards against a missing FINISHED broadcast.
        safetyStop = scope.launch(Dispatchers.Main) {
            delay(MAX_SCAN_MS)
            stop()
        }
    }

    /** Stops discovery and keeps the results on screen. */
    fun stop() = finishDiscovery(publish = true)

    /** Clears the results (e.g. after connecting). */
    fun reset() {
        finishDiscovery(publish = false)
        _state.value = ScanState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun finishDiscovery(publish: Boolean) {
        safetyStop?.cancel()
        safetyStop = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        val wasScanning = receiver != null
        receiver = null
        if (wasScanning) runCatching { availability.bluetoothAdapter?.takeIf { it.isDiscovering }?.cancelDiscovery() }
        if (publish && wasScanning) _state.value = ScanState.Finished(sorted())
    }

    @SuppressLint("MissingPermission")
    private fun onDiscoveryEvent(intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_FOUND -> {
                val device = intent.device() ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    .takeIf { it != Short.MIN_VALUE }?.toInt()
                put(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name, rssi)
            }
            BluetoothDevice.ACTION_NAME_CHANGED -> {
                val device = intent.device() ?: return
                put(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), found[device.address]?.rssi)
            }
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                stop()
                return
            }
        }
        if (_state.value is ScanState.Scanning) _state.value = ScanState.Scanning(sorted())
    }

    @SuppressLint("MissingPermission")
    private fun put(device: BluetoothDevice, name: String?, rssi: Int?) {
        val address = device.address ?: return
        val previous = found[address]
        // Unnamed devices are not shown: glasses always advertise a name.
        val label = name?.takeIf { it.isNotBlank() } ?: previous?.name ?: return
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        found[address] = GlassesDevice(
            address = address,
            name = label,
            rssi = rssi ?: previous?.rssi,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            likelyGlasses = address == lastGlassesAddress ||
                major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                major == BluetoothClass.Device.Major.WEARABLE ||
                GLASSES_NAME.containsMatchIn(label),
        )
    }

    private fun sorted(): List<GlassesDevice> = found.values.sortedWith(
        compareByDescending<GlassesDevice> { it.address == lastGlassesAddress }
            .thenByDescending { it.likelyGlasses }
            .thenByDescending { it.rssi ?: Int.MIN_VALUE }
            .thenBy { it.name.lowercase() },
    )

    private fun Intent.device(): BluetoothDevice? =
        IntentCompat.getParcelableExtra(this, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private companion object {
        const val MAX_SCAN_MS = 20_000L
        val GLASSES_NAME = Regex("glass|venus", RegexOption.IGNORE_CASE)
    }
}
