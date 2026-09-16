package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Runs the three idle-time actions and the restore path.
 *  1. Go HOME (via AccessibilityService if bound)
 *  2. Mute by enabling Do-Not-Disturb (via ACCESS_NOTIFICATION_POLICY)
 *  3. Turn screen off via DevicePolicyManager.lockNow()
 *
 * Restore path flips DND back to whatever it was before we touched it,
 * so users' own priorities/alarms settings are preserved.
 */
object ActionRunner {
    private const val STATE_PREFS = "idle_guard_state"
    private const val KEY_PREV_DND_FILTER = "prev_dnd_filter"

    fun trigger(ctx: Context) {
        // 1) HOME
        IdleAccessibilityService.instance?.let { svc ->
            runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        }

        // 2) Mute via DND
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sp = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (nm.isNotificationPolicyAccessGranted) {
            val prev = nm.currentInterruptionFilter
            sp.edit().putInt(KEY_PREV_DND_FILTER, prev).apply()
            if (prev != NotificationManager.INTERRUPTION_FILTER_NONE) {
                runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }
            }
        }

        // 3) Lock screen (turn display off)
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, IdleGuardDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
        }
    }

    fun restoreQuietMode(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        val sp = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val prev = sp.getInt(KEY_PREV_DND_FILTER, -1)
        if (prev != -1 && prev != NotificationManager.INTERRUPTION_FILTER_NONE) {
            runCatching { nm.setInterruptionFilter(prev) }
        }
        sp.edit().remove(KEY_PREV_DND_FILTER).apply()
    }
}
