package com.larry8page.idleguard

import android.content.Context
import android.content.res.Configuration

/**
 * Whitelist behavior for 央视频 (com.cctv.yangshipin.app.androidp).
 *
 * Goal: on cold-start, actively dismiss the opening ad, then land on the CCTV 电视
 * page where CCTV-6 (电影) is already playing by default, and STOP there. Fullscreen
 * is left to the user turning the phone sideways (the app auto-fullscreens on
 * rotation); the guide does NOT try to tap the transient fullscreen button.
 *
 * Design: a SELF-CORRECTING state machine. Each poll tick performs only the ONE
 * next action matching the current screen, then returns.
 *
 * On-device constraints (this TCL T803H / Freeme ROM, current 央视频 build):
 *  - 央视频 now opens into a Tencent videolite home (com.tencent.videolite.android.ui
 *    .HomeActivity) whose accessibility tree is EMPTY (uiautomator dump = 0 nodes).
 *    So the classic CCTV "电视" bottom-nav can't be found by text → we tap it by
 *    COORDINATE (see TV_TAB_FX/TV_TAB_FY).
 *  - The opening splash ad usually DOES expose a "跳过"/"关闭" node → we still skip it
 *    actively via node click (not just waiting it out).
 *  - CCTV-6 is the remembered/default live channel on the 电视 page, so no channel
 *    selection is needed.
 */
object YangshipinGuide {
    const val PKG = "com.cctv.yangshipin.app.androidp"

    // 底部导航「电视」的位置(比例, 适配不同分辨率)。TCL 1080x2460 实测约 (300,2230)。
    private const val TV_TAB_FX = 0.278f
    private const val TV_TAB_FY = 0.907f

    fun run(ctx: Context, onDone: (String) -> Unit) {
        val svc = IdleAccessibilityService.instance
        if (svc == null) {
            onDone("等待(无障碍未连接)")
            return
        }
        val app = ctx.applicationContext

        // 0) 已横屏 = 用户已把手机横过来全屏 → 不再做任何操作。
        if (app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            onDone("已在CCTV6(横屏全屏)")
            return
        }

        // 1) 主动去开屏广告: 有"跳过/关闭"节点就点掉(不靠干等)。
        if (svc.clickNodeByText(listOf("跳过"))) { onDone("跳过(节点)"); return }
        if (svc.clickNodeByText(listOf("关闭"))) { onDone("关弹窗(节点)"); return }

        // 2) 进 CCTV 电视页(CCTV-6 默认就在播)。videolite 首页无节点, 用坐标点底部「电视」导航。
        //    点一次即视为到位 → 结束引导会话, 之后不再干预(全屏交给用户横屏)。
        svc.tapRel(TV_TAB_FX, TV_TAB_FY)
        onDone("已在CCTV6(点电视导航)")
    }
}
