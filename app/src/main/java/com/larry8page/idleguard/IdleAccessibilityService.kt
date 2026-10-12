package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.view.InputDevice
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
        val ev = event ?: return
        // Reset ONLY on genuine user input.
        val src: Int = ev.getSource()
        val pointerMask: Int =
            InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS
        val fromPointer: Boolean = (src and pointerMask) != 0
        when (ev.eventType) {
            // A click/long-click is by definition a user tap → always counts.
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                IdleGuardService.instance?.onUserActivity()

            // Scrolls/hovers can be synthesized by auto-playing carousels & auto-pagers
            // in the foreground app. Only count them when they came from a real pointer.
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                if (fromPointer) IdleGuardService.instance?.onUserActivity()
            }
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
