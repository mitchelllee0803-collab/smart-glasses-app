package com.smartglasses.app.data.venus

import com.jueyuantech.venussdk.VNConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchpadGesturesTest {
    private val distance = 100f

    @Test
    fun `short movements are not swipes`() {
        assertNull(TouchpadGestures.swipe(99f, -99f, distance))
    }

    @Test
    fun `left and up swipe forward, right and down swipe back`() {
        assertEquals(TouchGesture.FORWARD, TouchpadGestures.swipe(-120f, 10f, distance))
        assertEquals(TouchGesture.FORWARD, TouchpadGestures.swipe(5f, -120f, distance))
        assertEquals(TouchGesture.BACK, TouchpadGestures.swipe(120f, -10f, distance))
        assertEquals(TouchGesture.BACK, TouchpadGestures.swipe(-5f, 120f, distance))
    }

    @Test
    fun `the longer axis decides a diagonal swipe`() {
        assertEquals(TouchGesture.BACK, TouchpadGestures.swipe(130f, -110f, distance))
        assertEquals(TouchGesture.FORWARD, TouchpadGestures.swipe(110f, -130f, distance))
    }

    @Test
    fun `gestures map to the SDK's touch events`() {
        assertEquals(VNConstant.TouchEvent.SINGLE_TAP, TouchGesture.TAP.sdkEvent)
        assertEquals(VNConstant.TouchEvent.DOUBLE_TAP, TouchGesture.DOUBLE_TAP.sdkEvent)
        assertEquals(VNConstant.TouchEvent.LONG_PRESS, TouchGesture.LONG_PRESS.sdkEvent)
        assertEquals(VNConstant.TouchEvent.SLIP_LEFT, TouchGesture.FORWARD.sdkEvent)
        assertEquals(VNConstant.TouchEvent.SLIP_RIGHT, TouchGesture.BACK.sdkEvent)
    }
}
