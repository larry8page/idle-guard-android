package com.larry8page.idleguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires when the second-stage "kill countdown" elapses. */
class KillAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        ctx ?: return
        if (!SettingsStore.isEnabled(ctx)) return
        val svc = IdleGuardService.instance
        if (svc != null) {
            Logger.log(ctx, "关闭倒计时到达 → 交给守护服务处理")
            svc.onKillAlarm()
        } else {
            Logger.log(ctx, "关闭倒计时到达，服务未运行 → 拉起服务执行清理+锁屏")
            IdleGuardService.startWithKill(ctx)
        }
    }
}
