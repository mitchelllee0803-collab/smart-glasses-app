package com.smartglasses.app.domain.model

import androidx.compose.runtime.Immutable

/** A Classic Bluetooth device found nearby (or already paired) that may be a pair of glasses. */
@Immutable
data class GlassesDevice(
    val address: String,
    val name: String,
    /** Signal strength from discovery; null for paired devices that were not seen in this scan. */
    val rssi: Int?,
    val bonded: Boolean,
    /** Ranked first: the last glasses used, or an audio/wearable Bluetooth class. */
    val likelyGlasses: Boolean,
    /** The test device (BuildConfig.SHOW_TEST_DEVICE): no hardware, nothing goes over Bluetooth or to the VenusSDK. */
    val simulated: Boolean = false,
) {
    companion object {
        /** Lets the connected dashboard and the translation screens be checked without glasses. */
        val TEST_DEVICE = GlassesDevice(
            address = "TEST-DEVICE",
            name = "test_device",
            rssi = -55,
            bonded = false,
            likelyGlasses = true,
            simulated = true,
        )
    }
}

enum class BluetoothAvailability {
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    OFF,
    /** Android 10–11 return no discovery results while location services are off. */
    LOCATION_OFF,
    READY,
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val devices: List<GlassesDevice>) : ScanState
    data class Finished(val devices: List<GlassesDevice>) : ScanState
    data object Failed : ScanState
}

enum class ConnectFailure {
    /** No CONNECT_SUCCESS from the SDK within the timeout. */
    TIMEOUT,
    /** The SDK reported CONNECT_ERROR. */
    REJECTED,
    /** The connection dropped and reconnecting did not succeed. */
    LOST,
    BLUETOOTH_OFF,
    /** The VenusSDK threw while starting the connection. */
    SDK_ERROR,
}

/** What the glasses report about themselves (guide §4.3); a field is null when they leave it out. */
@Immutable
data class GlassesInfo(
    val name: String?,
    val manufacturer: String?,
    val model: String?,
    val edition: String?,
    val serialNumber: String?,
    val firmware: String?,
    /** Bluetooth stack version. */
    val bluetoothStack: String?,
    val protocol: String?,
    val bluetoothAddress: String?,
    val bleName: String?,
    val bleAddress: String?,
)

/** About Glasses actions that end the connection. */
enum class GlassesReset { UNBIND, FACTORY_RESET }

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val device: GlassesDevice) : ConnectionState
    data class Connected(val device: GlassesDevice, val battery: Int? = null, val charging: Boolean = false) : ConnectionState
    data class Reconnecting(val device: GlassesDevice, val attempt: Int, val maxAttempts: Int) : ConnectionState
    data class Failed(val device: GlassesDevice, val reason: ConnectFailure) : ConnectionState
}
