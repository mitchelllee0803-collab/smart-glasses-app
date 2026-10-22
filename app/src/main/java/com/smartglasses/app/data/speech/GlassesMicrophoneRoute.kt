package com.smartglasses.app.data.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The glasses' microphone could not be used; the phone microphone still works. */
class GlassesMicUnavailableException(message: String) : MicrophoneException(message)

/**
 * Routes the glasses' microphone to the app's recordings. VenusSDK has no audio API: the glasses
 * act as a Bluetooth hands-free (HFP) headset, and Android opens its SCO audio link on request,
 * the same route a phone call uses. Needs the glasses paired in Android's Bluetooth settings.
 */
@Singleton
class GlassesMicrophoneRoute @Inject constructor(@ApplicationContext private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL

    /**
     * Opens the SCO link and returns the input device to record from (null if Android routes it
     * without naming one). Call [close] afterwards, also on failure.
     */
    suspend fun open(glassesAddress: String?): AudioDeviceInfo? {
        previousMode = audioManager.mode
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) openCommunicationDevice(glassesAddress) else openSco()
    }

    fun close() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                closeScoLegacy()
            }
            audioManager.mode = previousMode
        }.onFailure { Log.w(TAG, "could not release the glasses microphone", it) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun openCommunicationDevice(glassesAddress: String?): AudioDeviceInfo? {
        val candidates = audioManager.availableCommunicationDevices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val device = candidates.firstOrNull { it.address.equals(glassesAddress, ignoreCase = true) } ?: candidates.firstOrNull()
            ?: throw GlassesMicUnavailableException(NOT_AVAILABLE)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!audioManager.setCommunicationDevice(device)) throw GlassesMicUnavailableException(NOT_STARTED)
        // The SCO link takes a moment to come up after the request.
        val active = withTimeoutOrNull(SCO_TIMEOUT_MS) {
            while (audioManager.communicationDevice?.id != device.id) delay(POLL_MS)
            true
        }
        if (active == null) throw GlassesMicUnavailableException(NOT_STARTED)
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.address.equals(device.address, ignoreCase = true) }
    }

    /** Android 7–11: the SCO link is started explicitly and reported by broadcast. */
    @Suppress("DEPRECATION") // The replacement (setCommunicationDevice) only exists from Android 12.
    private suspend fun openSco(): AudioDeviceInfo? {
        if (!audioManager.isBluetoothScoAvailableOffCall) throw GlassesMicUnavailableException(NOT_AVAILABLE)
        val connected = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> connected.complete(true)
                    AudioManager.SCO_AUDIO_STATE_ERROR -> connected.complete(false)
                }
            }
        }
        // Sticky broadcast from the system: the current state is delivered right away.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            if (withTimeoutOrNull(SCO_TIMEOUT_MS) { connected.await() } != true) {
                throw GlassesMicUnavailableException(NOT_STARTED)
            }
            audioManager.isBluetoothScoOn = true
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    @Suppress("DEPRECATION")
    private fun closeScoLegacy() {
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
    }

    private companion object {
        const val TAG = "GlassesMicRoute"
        const val SCO_TIMEOUT_MS = 6_000L
        const val POLL_MS = 100L
        const val NOT_AVAILABLE =
            "The glasses' microphone is not available. Pair the glasses in Android's Bluetooth settings so they connect for calls/audio."
        const val NOT_STARTED = "The glasses' microphone could not be started. Check that the glasses are on and connected for audio."
    }
}
