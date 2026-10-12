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
            Logger.log(ctx, "定时广播到达 → 交给守护服务处理")
            svc.onIdleFired()
        } else {
            Logger.log(ctx, "定时广播到达，服务未运行 → 直接执行触发")
            ActionRunner.runSequenceNow(ctx)
        }
    }
}
