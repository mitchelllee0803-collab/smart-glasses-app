package com.smartglasses.app.data.venus

import com.jueyuantech.venussdk.VNConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesViewSyncTest {
    private val translate = VenusEvent.ViewChanged(VNConstant.View.TRANSLATE)
    private val home = VenusEvent.ViewChanged(VNConstant.View.HOME)

    private fun decide(event: VenusEvent, pageOpen: Boolean, screenOn: Boolean = true, echo: Boolean = false) =
        GlassesViewSync.decide(event, pageOpen, screenOn, echo)

    @Test
    fun `glasses opening Translate opens the live page from any phone page`() {
        assertEquals(GlassesAction.SHOW_TRANSLATION, decide(translate, pageOpen = false))
        assertEquals(GlassesAction.SHOW_TRANSLATION, decide(translate, pageOpen = true)) // e.g. from Settings on top
    }

    @Test
    fun `speech request from the glasses opens translation`() {
        assertEquals(GlassesAction.SHOW_TRANSLATION, decide(VenusEvent.SttRequest, pageOpen = false))
    }

    @Test
    fun `glasses leaving Translate ends the live session`() {
        assertEquals(GlassesAction.LEAVE_TRANSLATION, decide(home, pageOpen = true))
        assertEquals(GlassesAction.LEAVE_TRANSLATION, decide(VenusEvent.ViewChanged(VNConstant.View.MAP), pageOpen = true))
    }

    @Test
    fun `leaving does nothing when no live page is open`() {
        assertEquals(GlassesAction.NONE, decide(home, pageOpen = false))
    }

    @Test
    fun `fall-back to HOME after a screen timeout keeps the session`() {
        assertEquals(GlassesAction.NONE, decide(home, pageOpen = true, screenOn = false))
    }

    @Test
    fun `the echo of the app's own view switch is ignored`() {
        assertEquals(GlassesAction.NONE, decide(translate, pageOpen = true, echo = true))
        assertEquals(GlassesAction.NONE, decide(home, pageOpen = true, echo = true))
    }

    @Test
    fun `unknown views and other events are ignored`() {
        assertEquals(GlassesAction.NONE, decide(VenusEvent.ViewChanged(VNConstant.View.UNKNOWN), pageOpen = true))
        assertEquals(GlassesAction.NONE, decide(VenusEvent.Battery(50), pageOpen = true))
    }

    @Test
    fun `echo needs the same view within the window`() {
        val own = RequestedView(VNConstant.View.TRANSLATE, atElapsedMs = 10_000)
        assertTrue(GlassesViewSync.isEcho(VNConstant.View.TRANSLATE, own, nowElapsedMs = 11_000))
        assertFalse(GlassesViewSync.isEcho(VNConstant.View.HOME, own, nowElapsedMs = 11_000))
        assertFalse(GlassesViewSync.isEcho(VNConstant.View.TRANSLATE, own, nowElapsedMs = 10_000 + GlassesViewSync.ECHO_WINDOW_MS + 1))
        assertFalse(GlassesViewSync.isEcho(VNConstant.View.TRANSLATE, null, nowElapsedMs = 11_000))
    }
}
