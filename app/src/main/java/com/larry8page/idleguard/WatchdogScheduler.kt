package com.larry8page.idleguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** 10-minute repeating watchdog: revives the service after OEM kills and self-heals alarms. */
object WatchdogScheduler {
    private const val REQ = 0x102
    private const val INTERVAL_MS = 10 * 60_000L

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            pendingIntent(ctx)
        )
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, WatchdogReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
