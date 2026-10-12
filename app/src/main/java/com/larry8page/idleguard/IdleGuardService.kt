package com.larry8page.idleguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Long-running foreground service that owns the idle state machine.
 *
 *   MONITORING — idle timer armed; any tap resets it.
 *   WAIT_KILL  — idle fired, app already sent HOME; a second "kill countdown"
 *                (SettingsStore.countdownMillis) runs. Any tap during this window
 *                aborts the kill and returns to MONITORING.
 *   ASLEEP     — kill + lock already executed; waits for the user (USER_PRESENT)
 *                to come back, then re-arms.
 *
 * When countdown is 0, idle firing goes straight from MONITORING to kill+lock.
 */
class IdleGuardService : Service() {

    enum class State { MONITORING, WAIT_KILL, ASLEEP }

    @Volatile private var state: State = State.MONITORING
    private var screenReceiver: BroadcastReceiver? = null
    private var lastResetLogMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val CHANNEL_ID = "idle_guard_service"
        const val NOTIF_ID = 1001
        const val EXTRA_KILL_NOW = "extra_kill_now"
        private const val STATE_PREFS = "idle_guard_state"
        private const val KEY_PENDING_PKG = "pending_fg_pkg"

        @Volatile
        var instance: IdleGuardService? = null
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, IdleGuardService::class.java))
        }

        fun startWithKill(ctx: Context) {
            val i = Intent(ctx, IdleGuardService::class.java).putExtra(EXTRA_KILL_NOW, true)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, IdleGuardService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerScreenReceiver()
        Logger.log(this, "守护服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshNotification()
        if (intent?.getBooleanExtra(EXTRA_KILL_NOW, false) == true) {
            proceedToKillAndLock()
        } else {
            rescheduleIfMonitoring()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        TimerScheduler.cancel(this)
        KillScheduler.cancel(this)
        super.onDestroy()
    }

    // ------- External hooks -------

    /** A real user tap was observed. */
    fun onUserActivity() {
        when (state) {
            State.MONITORING -> {
                rescheduleIfMonitoring()
                val now = SystemClock.elapsedRealtime()
                if (now - lastResetLogMs > 60_000L) {
                    lastResetLogMs = now
                    Logger.log(this, "用户交互 → 计时已重置 (下次 ${SettingsStore.idleMinutes(this)} 分钟后触发)")
                }
            }
            State.WAIT_KILL -> abortKill()
            State.ASLEEP -> { /* waiting for USER_PRESENT */ }
        }
    }

    /** Called from IdleAlarmReceiver when the idle window elapses. */
    fun onIdleFired() {
        if (state != State.MONITORING) return
        if (!SettingsStore.isEnabled(this)) return

        val pkg = ForegroundApp.peek(this)
        savePendingPkg(pkg)
        val home = ActionRunner.goHome(this)

        val cd = SettingsStore.countdownMillis(this)
        if (cd <= 0L) {
            Logger.log(this, "触发执行 → 回桌面=$home  (前台=${pkg ?: "未知"})")
            proceedToKillAndLock()
        } else {
            Logger.log(this, "触发执行 → 回桌面=$home  (前台=${pkg ?: "未知"})  进入 ${cd / 60_000} 分钟关闭倒计时")
            state = State.WAIT_KILL
            KillScheduler.schedule(this, cd)
            refreshNotification()
        }
    }

    /** Called from KillAlarmReceiver when the kill countdown elapses. */
    fun onKillAlarm() {
        if (state != State.WAIT_KILL) return
        Logger.log(this, "关闭倒计时结束 → 执行清理+锁屏")
        proceedToKillAndLock()
    }

    /** Settings changed while waiting for the kill countdown. */
    fun onCountdownSettingChanged() {
        if (state != State.WAIT_KILL) return
        val cd = SettingsStore.countdownMillis(this)
        if (cd <= 0L) {
            Logger.log(this, "倒计时已改为0 → 立即执行清理+锁屏")
            proceedToKillAndLock()
        } else {
            KillScheduler.schedule(this, cd)
        }
    }

    // ------- Internals -------

    private fun abortKill() {
        KillScheduler.cancel(this)
        clearPendingPkg()
        state = State.MONITORING
        Logger.log(this, "用户操作 → 打断关闭倒计时，恢复监测")
        rescheduleIfMonitoring()
        refreshNotification()
    }

    private fun proceedToKillAndLock() {
        state = State.ASLEEP
        KillScheduler.cancel(this)
        val pkg = loadPendingPkg()
        clearPendingPkg()
        ActionRunner.killForeground(this, pkg)
        handler.postDelayed({ ActionRunner.lockScreen(this) }, 800L)
        refreshNotification()
    }

    private fun loadPendingPkg(): String? =
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING_PKG, null)

    private fun savePendingPkg(pkg: String?) {
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_PKG, pkg).apply()
    }

    private fun clearPendingPkg() {
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_PKG).apply()
    }

    private fun registerScreenReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_USER_PRESENT -> onUserReturned()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        runCatching { ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        screenReceiver = r
    }

    private fun onUserReturned() {
        if (state == State.ASLEEP) {
            state = State.MONITORING
            ActionRunner.restoreHidden(this)
            Logger.log(this, "用户返回 → 恢复监测")
            refreshNotification()
        }
        rescheduleIfMonitoring()
    }

    private fun rescheduleIfMonitoring() {
        if (!SettingsStore.isEnabled(this)) {
            TimerScheduler.cancel(this)
            return
        }
        if (state == State.MONITORING) {
            TimerScheduler.schedule(this, SettingsStore.idleMillis(this))
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(stateText())
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stateText(): String = when (state) {
        State.MONITORING -> getString(R.string.notif_monitoring, SettingsStore.idleMinutes(this))
        State.WAIT_KILL -> getString(R.string.notif_waiting_kill, SettingsStore.countdownMinutes(this))
        State.ASLEEP -> getString(R.string.notif_asleep)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
