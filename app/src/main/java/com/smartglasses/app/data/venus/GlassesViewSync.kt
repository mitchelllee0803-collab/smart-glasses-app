package com.smartglasses.app.data.venus

import com.jueyuantech.venussdk.VNConstant

/** What the phone should do after the wearer used the glasses' touchpad. */
enum class GlassesAction {
    NONE,
    /** Open (or return to) the live translation page and listen. */
    SHOW_TRANSLATION,
    /** End the live translation and go back to the Translate home. */
    LEAVE_TRANSLATION,
}

/** Decides how the phone follows the glasses. Pure, so the rules are unit-tested. */
object GlassesViewSync {
    /** An onViewChanged within this time of the app's own setView of the same view is its echo. */
    const val ECHO_WINDOW_MS = 3_000L

    fun isEcho(view: Int, ownRequest: RequestedView?, nowElapsedMs: Long): Boolean =
        ownRequest != null && ownRequest.view == view && nowElapsedMs - ownRequest.atElapsedMs in 0..ECHO_WINDOW_MS

    /**
     * @param sessionPageOpen the live translation page exists on the phone (possibly under another page)
     * @param screenOn the glasses' screen is on; with the screen off a view change is the automatic
     *   fall-back to HOME after a timeout, not the wearer leaving
     * @param echo the event only confirms a view switch this app requested itself
     */
    fun decide(event: VenusEvent, sessionPageOpen: Boolean, screenOn: Boolean, echo: Boolean): GlassesAction = when (event) {
        // The guide (§5.7) leaves TRANSCRIBE vs TRANSLATE to the app: this app only translates.
        VenusEvent.SttRequest -> GlassesAction.SHOW_TRANSLATION
        is VenusEvent.ViewChanged -> when {
            echo -> GlassesAction.NONE
            event.view == VNConstant.View.TRANSLATE -> GlassesAction.SHOW_TRANSLATION
            event.view == VNConstant.View.UNKNOWN -> GlassesAction.NONE
            !sessionPageOpen || !screenOn -> GlassesAction.NONE
            else -> GlassesAction.LEAVE_TRANSLATION
        }
        else -> GlassesAction.NONE
    }
}
