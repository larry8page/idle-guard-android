package com.larry8page.idleguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wakes every ~10 minutes to verify the guard is alive and unstuck. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        ctx ?: return
        if (!SettingsStore.isEnabled(ctx)) return
        val svc = IdleGuardService.instance
        if (svc == null) {
            Logger.log(ctx, "看门狗 → 服务不在，重新拉起")
            IdleGuardService.start(ctx)
        } else {
            svc.watchdogTick()
        }
    }
}
