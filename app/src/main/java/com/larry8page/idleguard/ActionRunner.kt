package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Idle-time actions (mute/DND was removed by design — killing the app is the point now):
 *
 *  goHome()        — HOME via AccessibilityService (background the foreground app)
 *  killForeground()— deep-kill the identified app:
 *                      · device owner  → DevicePolicyManager.forceStopPackage (real "Force stop")
 *                      · otherwise     → ActivityManager.killBackgroundProcesses (best effort;
 *                                        apps with a foreground service survive this)
 *  lockScreen()    — DevicePolicyManager.lockNow()
 *
 * Every step logs its outcome to [Logger].
 */
object ActionRunner {
    private const val KILL_DELAY_MS = 800L
    private const val LOCK_DELAY_MS = 1600L
    private const val STATE_PREFS = "idle_guard_state"
    private const val KEY_HIDDEN_PKG = "hidden_pkg"

    /** @return human-readable outcome of the HOME global action. */
    fun goHome(ctx: Context): String {
        val svc = IdleAccessibilityService.instance
        val ok = svc?.runCatching {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }?.getOrDefault(false) ?: false
        return when {
            ok -> "OK"
            svc == null -> "无障碍未连接"
            else -> "失败"
        }
    }

    /** Kills (or attempts to kill) the given package. Logs the result. @return description. */
    fun killForeground(ctx: Context, pkg: String?): String {
        val app = ctx.applicationContext
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isOwner = runCatching { dpm.isDeviceOwnerApp(app.packageName) }.getOrDefault(false)

        val desc = when {
            pkg == null -> "未识别到前台包名，跳过"
            ForegroundApp.isNonTarget(app, pkg) -> "目标为系统/桌面，跳过（$pkg）"
            isOwner -> {
                val admin = ComponentName(app, IdleGuardDeviceAdminReceiver::class.java)
                val ok = runCatching { dpm.setApplicationHidden(admin, pkg, true) }.getOrDefault(false)
                if (ok) {
                    app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_HIDDEN_PKG, pkg).apply()
                    "已隐藏强停 $pkg（用户返回后自动恢复）"
                } else {
                    lightKill(app, pkg) + "（隐藏失败，退回轻量清理）"
                }
            }
            else -> lightKill(app, pkg) + "（未配DeviceOwner，带前台服务的可能杀不掉）"
        }
        Logger.log(app, "清理前台 → $desc")
        return desc
    }

    /** Un-hides whatever killForeground hid, when the user comes back. */
    fun restoreHidden(ctx: Context) {
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val pkg = sp.getString(KEY_HIDDEN_PKG, null) ?: return
        sp.edit().remove(KEY_HIDDEN_PKG).apply()
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(app, IdleGuardDeviceAdminReceiver::class.java)
        val ok = runCatching { dpm.setApplicationHidden(admin, pkg, false) }.getOrDefault(false)
        Logger.log(app, "恢复显示 → $pkg ${if (ok) "OK" else "失败"}")
    }

    private fun lightKill(app: Context, pkg: String): String {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ok = runCatching { am.killBackgroundProcesses(pkg) }.isSuccess
        return if (ok) "已清理 $pkg" else "清理失败 $pkg"
    }

    /** Locks the screen via device admin / device owner. Logs the result. @return description. */
    fun lockScreen(ctx: Context): String {
        val app = ctx.applicationContext
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(app, IdleGuardDeviceAdminReceiver::class.java)
        val capable = runCatching { dpm.isAdminActive(admin) || dpm.isDeviceOwnerApp(app.packageName) }
            .getOrDefault(false)
        val desc = if (capable) {
            if (runCatching { dpm.lockNow() }.isSuccess) "OK" else "失败"
        } else {
            "无设备管理员/DeviceOwner"
        }
        Logger.log(app, "锁屏 → $desc")
        return desc
    }

    /**
     * Full immediate sequence (used by the test button and when the service isn't running):
     * peek → HOME → (+0.8s) kill → (+1.6s) lock.
     */
    fun runSequenceNow(ctx: Context) {
        val app = ctx.applicationContext
        val pkg = ForegroundApp.peek(app)
        val home = goHome(app)
        Logger.log(app, "触发执行 → 回桌面=$home  (前台=${pkg ?: "未知"})")
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ killForeground(app, pkg) }, KILL_DELAY_MS)
        handler.postDelayed({ lockScreen(app) }, LOCK_DELAY_MS)
    }
}
