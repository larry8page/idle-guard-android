package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Idle-time actions (mute/DND removed by design — killing the app is the point):
 *
 *  goHome()         — HOME via AccessibilityService.
 *  killForeground() — kill the identified app, best available path:
 *                       1. device owner            → setApplicationHidden(true)
 *                                                     (system force-stops it; auto-restored on return)
 *                       2. accessibility connected → open Settings "App info" for the target and
 *                                                     auto-tap 强行停止 + confirm (works on stock /
 *                                                     MIUI / EMUI / ColorOS wording variants)
 *                       3. otherwise / on failure  → ActivityManager.killBackgroundProcesses
 *                                                     (light: cannot stop foreground-service apps)
 *  lockScreen()     — DevicePolicyManager.lockNow()
 *
 * killForeground is asynchronous (path 2 takes seconds); [onDone] receives the
 * outcome so callers can chain the lock AFTER the kill finished.
 */
object ActionRunner {
    private const val KILL_DELAY_MS = 800L
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

    fun killForeground(ctx: Context, pkg: String?, onDone: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isOwner = runCatching { dpm.isDeviceOwnerApp(app.packageName) }.getOrDefault(false)

        fun done(desc: String) {
            Logger.log(app, "清理前台 → $desc")
            onDone?.invoke(desc)
        }

        when {
            pkg == null -> done("未识别到前台包名，跳过")
            ForegroundApp.isNonTarget(app, pkg) -> done("目标为系统/桌面，跳过（$pkg）")
            isOwner -> {
                val admin = ComponentName(app, IdleGuardDeviceAdminReceiver::class.java)
                val ok = runCatching { dpm.setApplicationHidden(admin, pkg, true) }.getOrDefault(false)
                if (ok) {
                    app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_HIDDEN_PKG, pkg).apply()
                    done("已隐藏强停 $pkg（用户返回后自动恢复）")
                } else {
                    done(lightKill(app, pkg) + "（隐藏失败，退回轻量清理）")
                }
            }
            else -> {
                val svc = IdleAccessibilityService.instance
                if (svc != null) {
                    svc.autoForceStop(pkg) { desc ->
                        if (desc.startsWith("已通过设置页")) {
                            runCatching {
                                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                            }
                            done(desc)
                        } else {
                            done(desc + "，退回 " + lightKill(app, pkg))
                        }
                    }
                } else {
                    done(lightKill(app, pkg) + "（无障碍未连接，无法深杀）")
                }
            }
        }
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
        return if (ok) "已轻量清理 $pkg" else "轻量清理失败 $pkg"
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
     * Full immediate sequence (test button / service-not-running fallback):
     * peek → HOME → (+0.8s) kill (async) → lock after kill completes.
     */
    fun runSequenceNow(ctx: Context) {
        val app = ctx.applicationContext
        val pkg = ForegroundApp.peek(app)
        val home = goHome(app)
        Logger.log(app, "触发执行 → 回桌面=$home  (前台=${pkg ?: "未知"})")
        Handler(Looper.getMainLooper()).postDelayed({
            killForeground(app, pkg) {
                Handler(Looper.getMainLooper()).postDelayed({ lockScreen(app) }, 800L)
            }
        }, KILL_DELAY_MS)
    }
}
