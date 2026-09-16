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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Long-running foreground service that owns the idle timer.
 * Two states:
 *   MONITORING — normal operation, alarm scheduled for idleMillis from last user interaction.
 *   ASLEEP     — we already executed the trigger (HOME + mute + lock); we wait for user return.
 * When the user returns (ACTION_USER_PRESENT), we restore DND and re-enter MONITORING.
 */
class IdleGuardService : Service() {

    enum class State { MONITORING, ASLEEP }

    @Volatile private var state: State = State.MONITORING
    private var screenReceiver: BroadcastReceiver? = null

    companion object {
        const val CHANNEL_ID = "idle_guard_service"
        const val NOTIF_ID = 1001

        @Volatile
        var instance: IdleGuardService? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, IdleGuardService::class.java)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshNotification()
        rescheduleIfMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        TimerScheduler.cancel(this)
        super.onDestroy()
    }

    // ------- External hooks -------

    /** Called from IdleAccessibilityService when a real user input event is observed. */
    fun onUserActivity() {
        if (state == State.ASLEEP) return
        rescheduleIfMonitoring()
    }

    /** Called from IdleAlarmReceiver when the idle window elapses. */
    fun onIdleFired() {
        if (state == State.ASLEEP) return
        if (!SettingsStore.isEnabled(this)) return
        state = State.ASLEEP
        ActionRunner.trigger(this)
        refreshNotification()
        // Do NOT reschedule here — we wait for the user to return.
    }

    // ------- Internals -------

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
            ActionRunner.restoreQuietMode(this)
            state = State.MONITORING
            refreshNotification()
        }
        rescheduleIfMonitoring()
    }

    private fun rescheduleIfMonitoring() {
        if (!SettingsStore.isEnabled(this)) {
            TimerScheduler.cancel(this)
            return
        }
        TimerScheduler.schedule(this, SettingsStore.idleMillis(this))
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
        State.ASLEEP -> getString(R.string.notif_asleep)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
