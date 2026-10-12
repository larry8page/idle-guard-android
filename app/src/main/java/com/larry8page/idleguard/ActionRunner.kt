package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Idle-time action sequence:
 *  1. HOME (AccessibilityService) so the foreground app moves to background
 *  2. Mute via Do-Not-Disturb (ACCESS_NOTIFICATION_POLICY)
 *  3. (+0.8s) kill the original foreground app's process
 *     (UsageStats to identify it, ActivityManager.killBackgroundProcesses — no root)
 *  4. (+1.6s) lock the screen via DevicePolicyManager.lockNow()
 *
 * Restore path flips DND back to whatever it was before we touched it.
 * Every step writes a per-action result line to [Logger].
 */
object ActionRunner {
    private const val STATE_PREFS = "idle_guard_state"
    private const val KEY_PREV_DND_FILTER = "prev_dnd_filter"
    private const val KILL_DELAY_MS = 800L
    private const val LOCK_DELAY_MS = 1600L

    /** Runs the full sequence. @return the immediate (HOME + mute) summary. */
    fun trigger(ctx: Context): String {
        val app = ctx.applicationContext
        val sb = StringBuilder()

        // Capture the foreground app BEFORE we go home.
        val fgPkg = ForegroundApp.peek(app)

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
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sp = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
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

        val head = sb.toString()
        Logger.log(app, "触发执行 → $head  (前台=${fgPkg ?: "未知"})  稍后清理进程并锁屏")

        val handler = Handler(Looper.getMainLooper())

        // 3) Kill the original foreground app's process (best effort, no root)
        handler.postDelayed({
            val desc = when {
                fgPkg == null -> "未识别到前台包名，跳过"
                ForegroundApp.isNonTarget(app, fgPkg) -> "目标为系统/桌面，跳过（$fgPkg）"
                else -> {
                    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val ok = runCatching { am.killBackgroundProcesses(fgPkg) }.isSuccess
                    (if (ok) "已清理 " else "清理调用失败 ") + fgPkg
                }
            }
            Logger.log(app, "清理前台 → $desc")
        }, KILL_DELAY_MS)

        // 4) Lock screen (turn display off)
        handler.postDelayed({
            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(app, IdleGuardDeviceAdminReceiver::class.java)
            val lockDesc = if (dpm.isAdminActive(admin)) {
                if (runCatching { dpm.lockNow() }.isSuccess) "OK" else "失败"
            } else {
                "无设备管理员"
            }
            Logger.log(app, "锁屏 → $lockDesc")
        }, LOCK_DELAY_MS)

        return head
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
