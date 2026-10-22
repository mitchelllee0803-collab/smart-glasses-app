package com.smartglasses.app.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.smartglasses.app.core.permissions.AppPermissions
import com.smartglasses.app.domain.model.BluetoothAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether Bluetooth can be used right now: adapter present and on, permissions granted. */
@Singleton
class BluetoothAvailabilityMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _state = MutableStateFlow(compute())
    val state: StateFlow<BluetoothAvailability> = _state.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        // Process-wide singleton: registered for the lifetime of the app.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Re-check after a permission result or when the app returns to the foreground. */
    fun refresh() {
        _state.value = compute()
    }

    val bluetoothAdapter: BluetoothAdapter? get() = adapter

    private fun compute(): BluetoothAvailability = when {
        adapter == null -> BluetoothAvailability.UNSUPPORTED
        !AppPermissions.allGranted(context, AppPermissions.bluetooth) -> BluetoothAvailability.PERMISSION_REQUIRED
        !adapter.isEnabled -> BluetoothAvailability.OFF
        needsLocationServices() -> BluetoothAvailability.LOCATION_OFF
        else -> BluetoothAvailability.READY
    }

    private fun needsLocationServices(): Boolean {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) return false
        val location = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(location)
    }
}
