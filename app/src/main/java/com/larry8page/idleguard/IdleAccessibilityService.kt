package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for real user-interaction events to reset the idle timer.
 * Also exposes performGlobalAction(HOME) so the guard can "leave" the
 * foreground app without root.
 *
 * Idle = no genuine touchscreen interaction. We count ONLY taps (click /
 * long-click) as user activity — those are unambiguously produced by a real
 * finger. Auto-playing carousels / auto-pagers inside the foreground app emit
 * TYPE_VIEW_SCROLLED (never clicks), so they no longer spuriously reset the
 * timer and prevent the idle action from firing.
 */
class IdleAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: IdleAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        when (ev.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                IdleGuardService.instance?.onUserActivity()
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
