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
 * Hard constraints learned on-device (TCL T803H):
 *  - Splash / interstitial ads are SurfaceView-drawn: they expose NO accessibility
 *    node, and the "跳过" button position VARIES per ad (top-right in one, top-left
 *    in a playable ad). A fixed-coordinate tap therefore misfires (it opened a
 *    "算术派对" mini-game once). So we NEVER tap coordinates: we only click a real
 *    "跳过"/"关闭" node when one exists, otherwise we do nothing and let the ad's
 *    own countdown dismiss it — the 10s poll simply retries on the next tick.
 *  - The home channel strip is also self-drawn inside a horizontal ViewPager: a
 *    synthetic (dispatchGesture) horizontal swipe is swallowed by the ViewPager and
 *    cannot scroll the strip. CCTV1 is therefore reached via the "展开全部频道"
 *    grid, whose cells ARE real accessibility nodes.
 *
 * Every navigation step is gated on confirming the expected node; if a node is
 * missing we abort and retry next tick rather than guessing. Idempotent: already
 * on CCTV1 (a "CCTV-1栏目" node present) is a no-op, so watching is never disrupted.
 */
object YangshipinGuide {
    const val PKG = "com.cctv.yangshipin.app.androidp"

    private val CCTV1_MARKERS = listOf("CCTV-1栏目", "CCTV1栏目")
    private val CCTV1_CELLS = listOf("综合频道", "CCTV1", "CCTV-1", "综合")

    fun run(ctx: Context, onDone: (String) -> Unit) {
        val svc = IdleAccessibilityService.instance
        if (svc == null) {
            onDone("无障碍未连接,跳过")
            return
        }
        val app = ctx.applicationContext
        val h = Handler(Looper.getMainLooper())
        val done = mutableListOf<String>()

        // Already on CCTV1 → nothing to do.
        if (svc.hasNodeText(CCTV1_MARKERS)) {
            onDone("已在CCTV1,无需引导")
            return
        }

        fun finish(extra: String? = null) {
            val all = if (extra != null) done + extra else done
            onDone(if (all.isEmpty()) "无可执行步骤" else all.joinToString("→"))
        }

        // 1) exit fullscreen if landscape.
        if (app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (svc.pressBack()) done += "BACK退全屏"
        }

        // 2) best-effort dismiss ad overlays that DO expose a node (native splash).
        //    No coordinate fallback — a node-less playable ad is left to auto-dismiss.
        if (svc.clickNodeByText(listOf("跳过"))) done += "跳过(节点)"
        if (svc.clickNodeByText(listOf("关闭"))) done += "关弹窗(节点)"

        // Give a just-dismissed splash a moment before probing the real UI.
        h.postDelayed({
            // 3) gate: the bottom "电视" nav must be present, else an ad still covers
            //    the screen. Do NOT tap blindly — abort and retry next tick.
            if (!svc.clickNodeByText(listOf("电视"), bottomOnly = true)) {
                finish("未见底部电视导航(广告未消失?),等下轮重试")
                return@postDelayed
            }
            done += "进电视页"

            h.postDelayed({
                // 4) open the 展开全部频道 grid (real node; the strip itself can't be swiped).
                if (!svc.clickNodeByText(listOf("展开全部频道"))) {
                    finish("展开网格失败,等下轮重试")
                    return@postDelayed
                }
                done += "展开网格"

                // 5) scroll the grid to its top row (vertical scroll works — it is the
                //    grid's own scroll view, not the ViewPager).
                svc.swipeRel(0.5f, 0.45f, 0.5f, 0.82f, 400L)
                h.postDelayed({
                    svc.swipeRel(0.5f, 0.45f, 0.5f, 0.82f, 400L)
                    h.postDelayed({
                        // 6) select CCTV1 by its grid cell node.
                        if (svc.clickNodeByText(CCTV1_CELLS)) {
                            done += "CCTV1(节点)"
                            h.postDelayed({ finish() }, 1500)
                        } else {
                            finish("没找到CCTV1网格项,等下轮重试")
                        }
                    }, 900)
                }, 700)
            }, 2600)
        }, 1200)
    }
}
