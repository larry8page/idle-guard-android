package com.larry8page.idleguard

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper

/**
 * Whitelist behavior for 央视频 (com.cctv.yangshipin.app.androidp).
 *
 * Goal: when 央视频 is the foreground app (idle trigger OR the 10s foreground
 * poll), drive it to CCTV1 live — WITHOUT ever blind-tapping coordinates.
 *
 * Design: a SELF-CORRECTING STATE MACHINE. Each 10s tick performs only the ONE
 * next action that matches the current screen state, then returns. This avoids a
 * rigid multi-step chain (whose timing desyncs if any single step's page hasn't
 * rendered yet, and whose half-finished state then confuses the next tick).
 *
 * On-device constraints (TCL T803H / Freeme ROM):
 *  - AccessibilityService.windows returns an EMPTY list here, so node search uses
 *    rootInActiveWindow (see IdleAccessibilityService.candidateRoots()).
 *  - Splash/interstitial ads are SurfaceView-drawn: no node, and the "跳过" position
 *    VARIES per ad → we NEVER tap coordinates; a node-less ad is left to auto-dismiss
 *    and retried on the next tick.
 *  - The home channel strip is self-drawn inside a horizontal ViewPager: a synthetic
 *    horizontal swipe is swallowed → CCTV1 is reached via the "展开全部频道" grid,
 *    whose cells are real nodes.
 */
object YangshipinGuide {
    const val PKG = "com.cctv.yangshipin.app.androidp"

    private val CCTV1_PAGE = listOf("CCTV-1栏目", "CCTV1栏目")
    private val CCTV1_CELL = listOf("综合频道")

    fun run(ctx: Context, onDone: (String) -> Unit) {
        val svc = IdleAccessibilityService.instance
        if (svc == null) {
            onDone("无障碍未连接,跳过")
            return
        }
        val app = ctx.applicationContext
        val h = Handler(Looper.getMainLooper())

        // 0) exit fullscreen if landscape.
        if (app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (svc.pressBack()) { onDone("横屏→BACK退全屏"); return }
        }

        // 1) already on CCTV1 → nothing to do.
        if (svc.hasNodeText(CCTV1_PAGE)) { onDone("已在CCTV1,无需引导"); return }

        // 2) grid open and CCTV1 cell visible → select it.
        if (svc.clickNodeByText(CCTV1_CELL)) {
            h.postDelayed({ onDone("选CCTV1(网格)") }, 1200); return
        }

        // 3) grid open (收起 present) but CCTV1 not visible → ensure CCTV tab, scroll up.
        if (svc.hasNodeText(listOf("收起"))) {
            svc.clickNodeByText(listOf("CCTV"))
            svc.swipeRel(0.5f, 0.5f, 0.5f, 0.85f, 400L)
            h.postDelayed({ onDone("网格:确保CCTV页签+上滚找CCTV1") }, 800); return
        }

        // 4) on the TV page → open the 展开全部频道 grid.
        if (svc.clickNodeByText(listOf("展开全部频道"))) {
            h.postDelayed({ onDone("展开网格") }, 1000); return
        }

        // 5) on home (bottom nav present) → go to the TV page.
        if (svc.clickNodeByText(listOf("电视"), bottomOnly = true)) {
            h.postDelayed({ onDone("进电视页") }, 1000); return
        }

        // 6) ad overlays that DO expose a node → dismiss them.
        if (svc.clickNodeByText(listOf("跳过"))) { onDone("跳过(节点)"); return }
        if (svc.clickNodeByText(listOf("关闭"))) { onDone("关弹窗(节点)"); return }

        // otherwise: node-less ad still up, or a page not ready → wait, retry next tick.
        onDone("等待(广告自动消失或页面未就绪)")
    }
}
