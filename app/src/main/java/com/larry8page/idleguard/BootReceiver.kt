package com.larry8page.idleguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Auto-starts the guard service after boot / reboot, only when enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        ctx ?: return
        val a = intent?.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED &&
            a != "android.intent.action.QUICKBOOT_POWERON" &&
            a != "com.htc.intent.action.QUICKBOOT_POWERON") return
        if (!SettingsStore.isEnabled(ctx)) return
        IdleGuardService.start(ctx)
    }
}
