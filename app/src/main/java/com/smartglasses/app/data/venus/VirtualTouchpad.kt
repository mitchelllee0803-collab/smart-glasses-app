package com.smartglasses.app.data.venus

import android.util.Log
import com.jueyuantech.venussdk.VNConstant
import com.smartglasses.app.di.ApplicationScope
import com.smartglasses.app.domain.model.ConnectionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the phone's touchpad sends: the gestures the glasses accept from the app (guide §5.2). */
enum class TouchGesture(val sdkEvent: Int) {
    TAP(VNConstant.TouchEvent.SINGLE_TAP),
    DOUBLE_TAP(VNConstant.TouchEvent.DOUBLE_TAP),
    LONG_PRESS(VNConstant.TouchEvent.LONG_PRESS),
    /** "前滑" on the glasses' temple. */
    FORWARD(VNConstant.TouchEvent.SLIP_LEFT),
    /** "后滑" on the glasses' temple. */
    BACK(VNConstant.TouchEvent.SLIP_RIGHT),
}

object TouchpadGestures {
    /**
     * The swipe for a finger that moved [dx], [dy] px, or null below [distance]. The guide lists
     * only left/right swipes for sending, so up counts as forward and down as back, like left/right.
     */
    fun swipe(dx: Float, dy: Float, distance: Float): TouchGesture? {
        if (maxOf(abs(dx), abs(dy)) < distance) return null
        val towardsStart = if (abs(dx) >= abs(dy)) dx < 0 else dy < 0
        return if (towardsStart) TouchGesture.FORWARD else TouchGesture.BACK
    }
}

/**
 * Sends the phone touchpad's gestures to the connected glasses; the test device only logs them.
 *
 * The glasses act on these gestures but do not report the view changes they cause (tested on
 * hardware 2026-10-23), so the app would not follow. After each gesture the glasses are asked
 * which view they show, and a change goes through [VenusClient.events] like one they reported.
 */
@Singleton
class VirtualTouchpad @Inject constructor(
    private val venus: VenusClient,
    private val glasses: GlassesRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var checkJob: Job? = null

    suspend fun send(gesture: TouchGesture) {
        val device = (glasses.state.value as? ConnectionState.Connected)?.device ?: return
        if (device.simulated) {
            Log.d(TAG, "test device: $gesture")
            return
        }
        // The view before the gesture, so that only a change it causes counts.
        if (venus.knownView == null) venus.currentView()?.let(venus::reportView)
        Log.d(TAG, "send $gesture, glasses view ${venus.knownView}")
        venus.sendTouchEvent(gesture.sdkEvent)
        // A newer gesture checks again; its readings also catch a change caused by this one.
        checkJob?.cancel()
        checkJob = scope.launch {
            for (wait in VIEW_CHECKS_MS) {
                delay(wait)
                val view = venus.currentView() ?: continue
                Log.d(TAG, "after $gesture: glasses view $view")
                venus.reportView(view)
            }
        }
    }

    private companion object {
        const val TAG = "VirtualTouchpad"
        /** The glasses need a moment to switch views; a slow switch is caught by the second check. */
        val VIEW_CHECKS_MS = listOf(400L, 800L)
    }
}
