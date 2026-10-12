package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Runs the three idle-time actions and the restore path.
 *  1. Go HOME (via AccessibilityService if bound) — backgrounds/leaves the foreground app
 *  2. Mute by enabling Do-Not-Disturb (via ACCESS_NOTIFICATION_POLICY)
 *  3. Turn screen off via DevicePolicyManager.lockNow()
 *
 * Restore path flips DND back to whatever it was before we touched it,
 * so the user's own priorities/alarms settings are preserved.
 *
 * Each trigger writes a per-action summary line to [Logger] — key for diagnosing
 * "nothing happened" on aggressive OEM ROMs.
 */
object ActionRunner {
    private const val STATE_PREFS = "idle_guard_state"
    private const val KEY_PREV_DND_FILTER = "prev_dnd_filter"

    /** @return a human-readable summary of what each action did. */
    fun trigger(ctx: Context): String {
        val sb = StringBuilder()

        // 1) Return to home (backgrounds / leaves the foreground app)
        val svc = IdleAccessibilityService.instance
        val homeOk = svc?.runCatching {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }?.getOrDefault(false) ?: false
        sb.append("回桌面=").append(when {
            homeOk -> "OK"
            svc == null -> "无障碍未连接"
            else -> "失败"
        })

        // 2) Mute via DND, remembering the prior interruption filter
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sp = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val muteDesc = if (nm.isNotificationPolicyAccessGranted) {
            val prev = nm.currentInterruptionFilter
            sp.edit().putInt(KEY_PREV_DND_FILTER, prev).apply()
            if (prev == NotificationManager.INTERRUPTION_FILTER_NONE) {
                "本已静音"
            } else {
                if (runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }.isSuccess) "OK" else "失败"
            }
        } else {
            sp.edit().remove(KEY_PREV_DND_FILTER).apply()
            "无勿扰权限"
        }
        sb.append("  静音=").append(muteDesc)

        // 3) Lock screen (turn display off)
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, IdleGuardDeviceAdminReceiver::class.java)
        val lockDesc = if (dpm.isAdminActive(admin)) {
            if (runCatching { dpm.lockNow() }.isSuccess) "OK" else "失败"
        } else {
            "无设备管理员"
        }
        sb.append("  息屏=").append(lockDesc)

        val summary = sb.toString()
        Logger.log(ctx, "触发执行 → $summary")
        return summary
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
