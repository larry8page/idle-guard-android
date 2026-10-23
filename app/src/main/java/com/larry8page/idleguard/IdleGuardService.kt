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

    enum class State { MONITORING, WAIT_KILL, COUNTDOWN, GUIDING, ASLEEP }

    @Volatile private var state: State = State.MONITORING
    @Volatile private var screenOn: Boolean = true
    private var screenReceiver: BroadcastReceiver? = null
    private var lastResetLogMs: Long = 0L
    private var asleepSinceMs: Long = 0L
    // 央视频"冷启动单次引导"会话状态(见 pollRunnable):
    // 只有从"非央视频 → 央视频"的那一次冷启动才开一个窗口,窗口内跳广告并导航到
    // CCTV1,到达 CCTV1 或超过 CCTV_AD_WINDOW_MS 即休眠,期间不反复刷日志/干预。
    private var wasCctvForeground = false
    @Volatile private var cctvSessionActive = false
    private var cctvSessionStartMs = 0L
    private var alert: Alert? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val CHANNEL_ID = "idle_guard_service"
        const val NOTIF_ID = 1001
        const val EXTRA_KILL_NOW = "extra_kill_now"
        private const val STATE_PREFS = "idle_guard_state"
        private const val KEY_PENDING_PKG = "pending_fg_pkg"
        private const val CCTV_POLL_MS = 10_000L
        private const val CCTV_STEP_MS = 4_000L
        private const val CCTV_AD_WINDOW_MS = 30_000L
        private const val COUNTDOWN_SECONDS = 10

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
        WatchdogScheduler.schedule(this)
        alert = Alert(this)
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        screenOn = pm.isInteractive
        if (pm.isInteractive) {
            ActionRunner.restoreVolume(this)
            ActionRunner.restoreHidden(this)
        }
        Logger.log(this, "守护服务已启动")
        startCctvPoll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshNotification()
        if (intent?.getBooleanExtra(EXTRA_KILL_NOW, false) == true) {
            savePendingPkg(CurrentApp.pkg(this))
            beginExitAndKill()
        } else {
            rescheduleIfMonitoring()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        alert?.shutdown()
        alert = null
        stopCctvPoll()
        TimerScheduler.cancel(this)
        KillScheduler.cancel(this)
        super.onDestroy()
    }

    // ------- External hooks -------

    /** A real user tap was observed. */
    fun onUserActivity() {
        // 真实点击必然意味着屏幕亮着、用户就在屏幕前。部分 OEM/Freeme ROM 只发
        // ACTION_SCREEN_OFF、唤醒却不发 ACTION_SCREEN_ON,会让 screenOn 卡死在 false,
        // 导致守护一次息屏后永远恢复不过来。用"有人触屏"这个硬信号复位息屏闸门。
        screenOn = true
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
            State.COUNTDOWN -> cancelCountdown()
            State.GUIDING -> { /* guide owns the UI right now */ }
            State.ASLEEP -> onUserReturned("触屏")
        }
    }

    /** Called from IdleAlarmReceiver when the idle window elapses. */
    fun onIdleFired() {
        if (state != State.MONITORING) return
        if (!SettingsStore.isEnabled(this)) return
        if (!screenOn) return // 已息屏：不触发语音倒计时/浮层，什么都不做

        val pkg = CurrentApp.pkg(this)

        // 前台已是桌面/启动器:没有需要清理的 App。
        // 完全静默——不静音、不 leaveApp、不起关闭倒计时、不写日志、不锁屏;
        // 桌面本身没有功耗,交给系统息屏超时处理即可。
        if (ForegroundApp.isDesktop(this, pkg)) return

        savePendingPkg(pkg)

        // 央视频不再特殊豁免:触发即先播语音倒计时,这期间完全不碰前台 App;
        // 10 秒内触屏取消,数到 0 才 静音→退出→杀→锁。
        startVoiceCountdownThenAct()
    }

    /** Called from KillAlarmReceiver when the kill countdown elapses. */
    fun onKillAlarm() {
        if (state != State.WAIT_KILL) return
        Logger.log(this, "关闭倒计时结束 → 执行清理+锁屏")
        killAndLock(loadPendingPkg())
    }

    /** Settings changed while waiting for the kill countdown. */
    fun onCountdownSettingChanged() {
        if (state != State.WAIT_KILL) return
        val cd = SettingsStore.countdownMillis(this)
        if (cd <= 0L) {
            Logger.log(this, "倒计时已改为0 → 立即执行清理+锁屏")
            killAndLock(loadPendingPkg())
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

    private fun startVoiceCountdownThenAct() {
        val a = alert
        if (a == null) { beginExitAndKill(); return }
        // 触发先播提示音,这期间不静音、不退出、不碰前台 App;10 秒内触屏可取消
        state = State.COUNTDOWN
        refreshNotification()
        IdleAccessibilityService.instance?.showCancelCatcher { cancelCountdown() }
        Logger.log(this, "即将 ${COUNTDOWN_SECONDS} 秒息屏，倒计时开始（触屏可取消）")
        a.startCountdown(COUNTDOWN_SECONDS) {
            IdleAccessibilityService.instance?.dismissCancelCatcher()
            beginExitAndKill()
        }
    }

    private fun beginExitAndKill() {
        val pkg = loadPendingPkg()
        ActionRunner.muteNow(this)
        ActionRunner.leaveApp(this, pkg) { desc ->
            val cd = SettingsStore.countdownMillis(this)
            if (cd <= 0L) {
                Logger.log(this, "触发执行 → $desc  (前台=${pkg ?: "未知"})")
                killAndLock(pkg)
            } else {
                Logger.log(this, "触发执行 → $desc  (前台=${pkg ?: "未知"})  进入 ${cd / 60_000} 分钟关闭倒计时")
                state = State.WAIT_KILL
                KillScheduler.schedule(this, cd)
                refreshNotification()
            }
        }
    }

    private fun killAndLock(pkg: String?) {
        state = State.ASLEEP
        asleepSinceMs = SystemClock.elapsedRealtime()
        KillScheduler.cancel(this)
        clearPendingPkg()
        ActionRunner.killForeground(this, pkg) {
            handler.postDelayed({ ActionRunner.lockScreen(this) }, 800L)
        }
        refreshNotification()
    }

    private fun cancelCountdown() {
        if (state != State.COUNTDOWN) return // 捕获层与无障碍点击可能同时触发,只认第一次
        alert?.stop()
        IdleAccessibilityService.instance?.dismissCancelCatcher()
        clearPendingPkg()
        state = State.MONITORING
        Logger.log(this, "用户操作 → 打断息屏倒计时，取消预定操作并恢复监测")
        rescheduleIfMonitoring()
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
                    Intent.ACTION_USER_PRESENT -> onUserReturned("解锁")
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        screenReceiver = r
    }

    /** Screen turned off: stop every countdown/timer/guide — nothing to guard a dark screen. */
    private fun onScreenOff() {
        screenOn = false
        if (state == State.COUNTDOWN) {
            cancelCountdown() // 立刻停语音倒计时 + 撤全屏取消浮层
        }
        TimerScheduler.cancel(this) // 息屏期间不再触发闲置
        Logger.log(this, "息屏 → 停止一切倒计时/轮询/引导")
    }

    /** Screen turned on: resume monitoring. */
    private fun onScreenOn() {
        screenOn = true
        if (state == State.ASLEEP) {
            onUserReturned("亮屏")
        } else {
            rescheduleIfMonitoring()
        }
        Logger.log(this, "亮屏 → 恢复监测 (SCREEN_ON 广播到达)")
    }

    private fun onUserReturned(reason: String) {
        // 解锁 / 用户返回必然意味着屏幕是亮的:复位息屏闸门,不依赖 SCREEN_ON 广播。
        screenOn = true
        // Unconditional: these self-guard on persisted markers. After an OEM
        // process kill the in-memory ASLEEP state is lost, and the old guarded
        // call used to skip volume restore forever.
        ActionRunner.restoreVolume(this)
        ActionRunner.restoreHidden(this)
        if (state == State.ASLEEP) {
            state = State.MONITORING
            Logger.log(this, "用户返回($reason) → 恢复监测")
            refreshNotification()
        }
        rescheduleIfMonitoring()
    }

    /** Called by the 10-min watchdog; also repairs lost alarms after OEM kills. */
    fun watchdogTick() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isInteractive && state != State.ASLEEP && state != State.WAIT_KILL && state != State.COUNTDOWN) {
            // process may have restarted mid-mute: markers persisted but nobody restored
            ActionRunner.restoreVolume(this)
            ActionRunner.restoreHidden(this)
        }
        when (state) {
            State.ASLEEP -> if (pm.isInteractive &&
                SystemClock.elapsedRealtime() - asleepSinceMs > 12 * 60_000L) {
                Logger.log(this, "看门狗 → 亮屏超12分钟仍沉睡，强制恢复监测")
                onUserReturned("看门狗")
            }
            State.MONITORING -> rescheduleIfMonitoring()
            State.WAIT_KILL -> { /* kill alarm owns this state */ }
            State.COUNTDOWN -> { /* 语音倒计时进行中,由 beginExitAndKill/cancelCountdown 收尾 */ }
            State.GUIDING -> { /* guide in progress */ }
        }
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

    // ------- 央视频 10s foreground poll -------

    private val pollRunnable = object : Runnable {
        override fun run() {
            var nextDelay = CCTV_POLL_MS
            try {
                if (!SettingsStore.isEnabled(this@IdleGuardService) ||
                    !SettingsStore.whitelistCctv(this@IdleGuardService) ||
                    !screenOn ||
                    state != State.MONITORING
                ) return
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isInteractive) return
                if (IdleAccessibilityService.instance == null) return

                val isCctv = CurrentApp.pkg(this@IdleGuardService) == YangshipinGuide.PKG

                // 冷启动检测:上一拍不是央视频、这一拍是 → 开启一次引导会话
                if (isCctv && !wasCctvForeground) {
                    cctvSessionActive = true
                    cctvSessionStartMs = SystemClock.elapsedRealtime()
                    Logger.log(
                        this@IdleGuardService,
                        "央视频冷启动 → 开屏广告引导窗口 ${CCTV_AD_WINDOW_MS / 1000}s,到CCTV1后休眠"
                    )
                }
                wasCctvForeground = isCctv

                // 已离开央视频:关闭会话(下次再进入算新的冷启动)
                if (!isCctv) {
                    cctvSessionActive = false
                    return
                }
                // 无会话(本轮已到CCTV1/超时休眠,或只是停在央视频里没冷启动)→ 不干预、不写日志
                if (!cctvSessionActive) return
                // 窗口到点仍未到 CCTV1 → 休眠
                if (SystemClock.elapsedRealtime() - cctvSessionStartMs > CCTV_AD_WINDOW_MS) {
                    cctvSessionActive = false
                    Logger.log(this@IdleGuardService, "央视频引导窗口超时 → 休眠,不再干预")
                    return
                }

                // 会话内:每拍只做当前一步,用更快的步频让跳广告+导航能在窗口内走完
                nextDelay = CCTV_STEP_MS
                state = State.GUIDING
                refreshNotification()
                YangshipinGuide.run(this@IdleGuardService) { desc ->
                    when {
                        desc.contains("已在CCTV1") -> {
                            Logger.log(this@IdleGuardService, "央视频引导 → 已到达CCTV1,休眠")
                            cctvSessionActive = false
                        }
                        !desc.startsWith("等待") -> Logger.log(this@IdleGuardService, "央视频引导 → $desc")
                        // "等待(广告自动消失或页面未就绪)" → 不写日志,下一拍再看
                    }
                    state = State.MONITORING
                    rescheduleIfMonitoring()
                    refreshNotification()
                }
            } finally {
                handler.postDelayed(this, nextDelay)
            }
        }
    }

    private fun startCctvPoll() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, CCTV_POLL_MS)
    }

    private fun stopCctvPoll() {
        handler.removeCallbacks(pollRunnable)
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
        State.COUNTDOWN -> getString(R.string.notif_countdown)
        State.GUIDING -> getString(R.string.notif_guiding)
        State.ASLEEP -> getString(R.string.notif_asleep)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
