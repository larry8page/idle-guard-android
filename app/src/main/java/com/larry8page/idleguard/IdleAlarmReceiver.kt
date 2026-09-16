package com.larry8page.idleguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Invoked by AlarmManager when the idle window elapses. */
class IdleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        ctx ?: return
        if (!SettingsStore.isEnabled(ctx)) return
        val svc = IdleGuardService.instance
        if (svc != null) {
            svc.onIdleFired()
        } else {
            // Service not up (shouldn't happen if enabled). Fire the actions directly.
            ActionRunner.trigger(ctx)
        }
    }
}
