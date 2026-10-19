package com.larry8page.idleguard

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper

/**
 * Whitelist behavior for 央视频 (com.cctv.yangshipin.app.androidp).
 *
 * When 央视频 is the foreground app (idle trigger OR the 10s foreground poll),
 * instead of mute+kill+lock we walk its UI to CCTV1 live:
 *   1. exit fullscreen if landscape (BACK)
 *   2. dismiss a splash ad ("跳过" node, else top-right coordinate — the splash
 *      is a SurfaceView with no clickable node, so a coordinate tap is required)
 *   3. dismiss an interstitial popup if it exposes a "关闭"/"跳过" node
 *   4. open the bottom "电视" tab (node first, calibrated coords fallback)
 *   5. open the "展开全部频道" grid — the self-drawn channel strip CANNOT be
 *      scrolled by a synthetic gesture (the outer ViewPager swallows horizontal
 *      swipes), so the grid (real nodes) is the only reliable path to CCTV1
 *   6. scroll the grid to its top row and select CCTV1 (node first, coords fallback)
 *
 * Idempotent: if we are already on the CCTV1 page the run is a no-op, so the
 * 10s poll never disrupts normal watching. Every step is best-effort and logged.
 */
object YangshipinGuide {
    const val PKG = "com.cctv.yangshipin.app.androidp"

    /** Text that only exists once we are actually on the CCTV1 live page. */
    private val CCTV1_MARKERS = listOf("CCTV-1栏目", "CCTV1栏目")

    fun run(ctx: Context, onDone: (String) -> Unit) {
        val svc = IdleAccessibilityService.instance
        if (svc == null) {
            onDone("无障碍未连接,跳过")
            return
        }
        val app = ctx.applicationContext
        val h = Handler(Looper.getMainLooper())
        val done = mutableListOf<String>()

        // Already watching CCTV1 → nothing to do (prevents the poll from nagging).
        if (svc.hasNodeText(CCTV1_MARKERS)) {
            onDone("已在CCTV1,无需引导")
            return
        }

        val steps: List<Pair<String, ((Settle) -> Unit)>> = listOf(
            "全屏退出" to { next: Settle ->
                if (app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    if (svc.pressBack()) done += "BACK退全屏"
                    h.postDelayed(next, 1500)
                } else {
                    h.postDelayed(next, 300)
                }
            },
            "跳过开屏" to { next: Settle ->
                if (svc.clickNodeByText(listOf("跳过"))) {
                    done += "点了跳过(节点)"
                } else {
                    svc.tapRel(0.84f, 0.09f)
                    done += "点了跳过(坐标)"
                }
                h.postDelayed(next, 1200)
            },
            "关弹窗" to { next: Settle ->
                if (svc.clickNodeByText(listOf("关闭", "跳过"))) done += "关了弹窗"
                h.postDelayed(next, 800)
            },
            "进电视页" to { next: Settle ->
                if (svc.clickNodeByText(listOf("电视"), bottomOnly = true)) {
                    done += "电视tab(节点)"
                } else {
                    svc.tapRel(0.30f, 0.924f)
                    done += "电视tab(坐标)"
                }
                h.postDelayed(next, 2600)
            },
            "展开全部频道" to { next: Settle ->
                if (svc.clickNodeByText(listOf("展开全部频道"))) {
                    done += "展开网格(节点)"
                } else {
                    svc.tapRel(0.48f, 0.56f)
                    done += "展开网格(坐标)"
                }
                h.postDelayed(next, 1400)
            },
            "网格滚到顶" to { next: Settle ->
                svc.swipeRel(0.5f, 0.45f, 0.5f, 0.82f, 400L)
                h.postDelayed({
                    svc.swipeRel(0.5f, 0.45f, 0.5f, 0.82f, 400L)
                    h.postDelayed(next, 900)
                }, 700)
            },
            "选CCTV1" to { next: Settle ->
                if (svc.clickNodeByText(listOf("综合频道", "CCTV1", "CCTV-1", "综合"))) {
                    done += "CCTV1(节点)"
                } else {
                    svc.tapRel(0.16f, 0.54f)
                    done += "CCTV1(坐标)"
                }
                h.postDelayed(next, 1500)
            }
        )

        fun exec(i: Int) {
            if (i >= steps.size) {
                onDone(if (done.isEmpty()) "无可执行步骤" else done.joinToString("→"))
            } else {
                steps[i].second { exec(i + 1) }
            }
        }
        exec(0)
    }

    private typealias Settle = () -> Unit
}
