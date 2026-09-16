package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for real user-interaction events to reset the idle timer.
 * Also exposes performGlobalAction(HOME) so the guard can "leave" the
 * foreground app without root.
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
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                IdleGuardService.instance?.onUserActivity()
            }
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
