package com.smartglasses.app.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.bluetooth.BluetoothAvailabilityMonitor
import com.smartglasses.app.data.bluetooth.ClassicDeviceScanner
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.data.venus.GlassesRepository
import com.smartglasses.app.domain.model.BluetoothAvailability
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.GlassesDevice
import com.smartglasses.app.domain.model.ScanState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class HomeUiState(
    val availability: BluetoothAvailability,
    val scan: ScanState,
    val connection: ConnectionState,
    /** The floating touchpad is switched on (👆). */
    val touchpadPanel: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val glasses: GlassesRepository,
    private val scanner: ClassicDeviceScanner,
    private val availability: BluetoothAvailabilityMonitor,
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        availability.state, scanner.state, glasses.state, settings.settings.map { it.touchpadPanel }, ::HomeUiState,
    ).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        HomeUiState(availability.state.value, scanner.state.value, glasses.state.value),
    )

    fun setTouchpadPanel(shown: Boolean) {
        viewModelScope.launch { settings.setTouchpadPanel(shown) }
    }

    fun refreshAvailability() = availability.refresh()

    fun scan() {
        glasses.dismissFailure()
        scanner.start()
    }

    fun stopScan() = scanner.stop()

    fun connect(device: GlassesDevice) {
        scanner.stop()
        glasses.connect(device)
    }

    fun disconnect() {
        glasses.disconnect()
        scanner.reset()
    }

    fun dismissFailure() = glasses.dismissFailure()

    override fun onCleared() {
        scanner.stop()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
