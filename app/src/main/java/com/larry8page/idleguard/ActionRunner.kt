package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Idle-time actions (mute/DND removed by design — killing the app is the point):
 *
 *  leaveApp()       — 先「双击返回」(连发两次 GLOBAL_ACTION_BACK, 约120ms);若目标
 *                     已退出即结束(不再 HOME, 避开视频 App 的悬浮小窗),仍在前台才
 *                     补一记 BACK、再兜底 HOME。
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
    private const val DOUBLE_BACK_MS = 120L // 双击返回两击之间的间隔(ms)
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

    /**
     * Leave the foreground app by DOUBLE-TAPPING BACK first, only falling back to
     * HOME as a last resort. Many apps close a page / exit precisely on two quick
     * BACK presses, so we first send [AccessibilityService.GLOBAL_ACTION_BACK]
     * twice (~[DOUBLE_BACK_MS] apart). We then re-check whether [target] is still
     * the foreground app:
     *   - exited  → finish immediately, do NOT press HOME (avoids the floating
     *               PiP window these video apps turn HOME into).
     *   - present → original fallback: one more BACK, and if still present, HOME.
     * Callback gets a short description.
     */
    fun leaveApp(ctx: Context, target: String?, onDone: (String) -> Unit) {
        val svc = IdleAccessibilityService.instance
        if (svc == null) {
            onDone("无障碍未连接,仅静音")
            return
        }
        val app = ctx.applicationContext
        val h = Handler(Looper.getMainLooper())
        fun back(): Boolean = runCatching {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }.getOrDefault(false)
        fun stillThere(): Boolean = target != null && CurrentApp.pkg(app) == target

        var backs = 0
        // 第一击。
        if (back()) backs++
        // 第二击(约 120ms 后,模拟双击返回)。
        h.postDelayed({
            if (back()) backs++
            // 双击后再等一瞬观察目标是否已退出。
            h.postDelayed({
                if (!stillThere()) {
                    // 双击返回即已退出:直接结束,不再按 HOME。
                    onDone("双击返回退出")
                    return@postDelayed
                }
                // 仍在前台 → 走原有兜底:再补一记 BACK,仍不退才 HOME。
                if (back()) backs++
                h.postDelayed({
                    if (stillThere()) {
                        goHome(app)
                        onDone("双击返回+BACK×${backs}未退出,已HOME兜底")
                    } else {
                        onDone("双击返回未退出,BACK×${backs}退出(兜底)")
                    }
                }, 600)
            }, DOUBLE_BACK_MS)
        }, DOUBLE_BACK_MS)
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

    private const val KEY_VOL_SAVED = "vol_saved_json"

    /**
     * Safety net for apps that survive the kill (e.g. 央视频 floating PiP window):
     * zero the media/system volume now; original levels are restored on user return.
     */
    fun muteNow(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sp = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (sp.contains(KEY_VOL_SAVED)) return // already muted by us
        val music = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val system = runCatching { am.getStreamVolume(AudioManager.STREAM_SYSTEM) }.getOrDefault(0)
        sp.edit().putString(KEY_VOL_SAVED, "$music,$system").apply()
        val okM = runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0); true
        }.getOrDefault(false)
        runCatching { am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0) }
        Logger.log(app, "静音(音量) → ${if (okM) "已调到最小（原媒体$music/系统$system）" else "失败（原媒体$music/系统$system）"}")
    }

    /** Restore volumes saved by muteNow, when the user comes back. */
    fun restoreVolume(ctx: Context) {
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val saved = sp.getString(KEY_VOL_SAVED, null) ?: return
        sp.edit().remove(KEY_VOL_SAVED).apply()
        val parts = saved.split(",")
        val music = parts.getOrNull(0)?.toIntOrNull() ?: return
        val system = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxM = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(music)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, music.coerceIn(0, maxM), 0) }
        val maxS = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_SYSTEM) }.getOrDefault(system)
        runCatching { am.setStreamVolume(AudioManager.STREAM_SYSTEM, system.coerceIn(0, maxS), 0) }
        Logger.log(app, "恢复音量 → 媒体$music/系统$system")
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
        val pkg = CurrentApp.pkg(app)
        muteNow(app)
        leaveApp(app, pkg) { desc ->
            Logger.log(app, "触发执行 → $desc  (前台=${pkg ?: "未知"})")
            Handler(Looper.getMainLooper()).postDelayed({
                killForeground(app, pkg) {
                    Handler(Looper.getMainLooper()).postDelayed({ lockScreen(app) }, 800L)
                }
            }, 400)
        }
    }
}
