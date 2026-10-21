package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Watches for real user-interaction events to reset the idle timer (taps only:
 * click / long-click — auto-carousels emit SCROLLED and never fool us).
 *
 * Also performs privileged UI automation that normal apps cannot do directly:
 *  - GLOBAL_ACTION_HOME
 *  - autoForceStop(): open the target app's system "App info" page and click
 *    the "Force stop" button + its confirmation dialog, emulating exactly what
 *    the user would tap in Settings. This is our no-root / no-DeviceOwner way
 *    to stop foreground-service apps like video players.
 */
class IdleAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: IdleAccessibilityService? = null
            private set

        private val MAIN_BUTTON_TEXTS = listOf(
            "强行停止", "强制停止", "停止运行", "Force stop", "Force Stop", "Force-stop"
        )
        private val CONFIRM_BUTTON_TEXTS = listOf(
            "强行停止", "强制停止", "停止", "确定", "允许", "OK", "Force stop", "Force Stop"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        when (ev.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                IdleGuardService.instance?.onUserActivity()
        }
    }

    override fun onInterrupt() { /* no-op */ }

    // ---- 执行前"10 秒可取消"全屏提示(用无障碍自带悬浮层,免悬浮窗权限) ----

    private val ui = Handler(Looper.getMainLooper())
    private var warnRoot: View? = null

    /**
     * 全屏倒计时提示:点按任意处 → [onCancel];无人操作数到 0 → [onProceed]。
     * 用 TYPE_ACCESSIBILITY_OVERLAY 窗口(无障碍服务可越界绘制,无需 SYSTEM_ALERT_WINDOW)。
     * 若因故无法弹出,直接视为超时并回调 [onProceed],绝不卡住关闭流程。
     */
    fun showKillWarning(seconds: Int, onCancel: () -> Unit, onProceed: () -> Unit) {
        ui.post {
            dismissKillWarning()
            var settled = false
            val cancel = { if (!settled) { settled = true; dismissKillWarning(); onCancel() } }
            val proceed = { if (!settled) { settled = true; dismissKillWarning(); onProceed() } }
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val density = resources.displayMetrics.density
                val pad = (20 * density).toInt()

                val root = FrameLayout(this)
                root.setBackgroundColor(0xCC0E1116.toInt())
                root.isClickable = true

                val card = LinearLayout(this)
                card.orientation = LinearLayout.VERTICAL
                card.gravity = Gravity.CENTER
                card.setPadding(pad, pad, pad, pad)

                val count = TextView(this).apply {
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    textSize = 72f
                    text = seconds.toString()
                }
                val tip = TextView(this).apply {
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    textSize = 16f
                    text = "闲置守护:${seconds} 秒后关闭前台并锁屏\n点按任意位置取消"
                }
                card.addView(count)
                val tipLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad }
                card.addView(tip, tipLp)
                root.addView(
                    card,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )

                var remaining = seconds
                val ticker = object : Runnable {
                    override fun run() {
                        if (settled) return
                        remaining -= 1
                        if (remaining <= 0) {
                            proceed()
                        } else {
                            count.text = remaining.toString()
                            ui.postDelayed(this, 1000)
                        }
                    }
                }
                root.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        ui.removeCallbacks(ticker)
                        cancel()
                        true
                    } else false
                }

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                warnRoot = root
                wm.addView(root, lp)
                ui.postDelayed(ticker, 1000)
            } catch (t: Throwable) {
                if (!settled) { settled = true; dismissKillWarning(); onProceed() }
            }
        }
    }

    /** 移除当前提示悬浮层(若有)。可在任意线程调用,内部切主线程。 */
    fun dismissKillWarning() {
        val root = warnRoot ?: return
        warnRoot = null
        ui.post {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(root) }
        }
    }

    /**
     * Real-time foreground package by window AREA, not focus: TV/box video
     * windows are often non-focusable, so rootInActiveWindow alone can report a
     * random previously-focused app (e.g. Alipay) instead of the playing video.
     * Strategy: among non-system application windows, prefer a focused one that
     * covers >=60% of the screen, otherwise the largest one. MUST be called
     * BEFORE pressing HOME.
     */
    fun currentForegroundPkg(): String? {
        data class WinInfo(val pkg: String, val area: Long, val focused: Boolean)
        return try {
            val dm = resources.displayMetrics
            val screenArea = dm.widthPixels.toLong() * dm.heightPixels.toLong()
            val ws = windows.mapNotNull { w ->
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
                val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
                    ?: return@mapNotNull null
                val r = Rect()
                runCatching { w.getBoundsInScreen(r) }
                WinInfo(pkg, (r.width().toLong() * r.height().toLong()).coerceAtLeast(0L), w.isFocused)
            }.filter { !isSystemish(it.pkg) }
            val big = ws.filter { it.area * 10L >= screenArea * 6L }.sortedByDescending { it.area }
            (big.firstOrNull { it.focused } ?: big.firstOrNull() ?: ws.maxByOrNull { it.area })?.pkg
                ?: runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        } catch (t: Throwable) {
            runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        }
    }

    /**
     * Find the Settings app-info page among ALL windows — a video app's PiP
     * floating window steals focus, which makes rootInActiveWindow useless
     * during autoForceStop. Returns the settings window's root, or null.
     */
    private fun settingsPageRoot(): AccessibilityNodeInfo? {
        return try {
            windows.firstOrNull { w ->
                val p = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: return@firstOrNull false
                (p.lowercase().contains("settings") || p.lowercase().contains("systemui")) &&
                    runCatching { w.root?.findAccessibilityNodeInfosByText("强行停止")?.isNotEmpty() == true ||
                        w.root?.findAccessibilityNodeInfosByText("强制停止")?.isNotEmpty() == true ||
                        w.root?.findAccessibilityNodeInfosByText("Force stop")?.isNotEmpty() == true }
                        .getOrDefault(false)
            }?.root
        } catch (t: Throwable) {
            null
        }
    }

    private fun isSystemish(pkg: String): Boolean {
        val l = pkg.lowercase()
        return l.contains("systemui") || l.contains("launcher") || l.contains("mihome") ||
            l.contains("inputmethod") || pkg == "android" || pkg == packageName
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        warnRoot?.let { r ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(r) }
        }
        warnRoot = null
        instance = null
        super.onDestroy()
    }

    // ---- automation helpers (used by YangshipinGuide) ----

    fun pressBack(): Boolean = runCatching {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }.getOrDefault(false)

    /**
     * Roots to search for nodes. On this Freeme/TCL ROM AccessibilityService.windows
     * returns an EMPTY list, but rootInActiveWindow correctly yields the foreground
     * app's tree — so always include it (plus any windows that DO report).
     */
    private fun candidateRoots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow }.getOrNull()?.let { list.add(it) }
        runCatching {
            for (w in windows) runCatching { w.root }.getOrNull()?.let { list.add(it) }
        }
        return list
    }

    /** Search ALL windows for a visible clickable node whose text/desc matches any of [texts]. */
    fun clickNodeByText(texts: List<String>, bottomOnly: Boolean = false): Boolean {
        val screenH = resources.displayMetrics.heightPixels
        return try {
            for (root in candidateRoots()) {
                for (t in texts) {
                    val nodes = runCatching { root.findAccessibilityNodeInfosByText(t) }
                        .getOrNull() ?: continue
                    for (n in nodes) {
                        val c = clickableParent(n) ?: n
                        val b = Rect()
                        runCatching { c.getBoundsInScreen(b) }
                        if (bottomOnly && b.top < screenH * 0.72) continue
                        if (!runCatching { c.isVisibleToUser }.getOrDefault(false)) continue
                        if (runCatching {
                                c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }.getOrDefault(false)) return true
                    }
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    /** Diagnostic: report every window the service sees and whether [query] matches. */
    fun debugWindows(query: String): String {
        val sb = StringBuilder()
        runCatching {
            val ws = windows
            sb.append("windows=${ws.size};activeRootPkg=")
            sb.append(runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: "null")
            ws.forEachIndexed { i, w ->
                val root = runCatching { w.root }.getOrNull()
                val pkg = runCatching { root?.packageName?.toString() }.getOrNull() ?: "null"
                val kids = runCatching { root?.childCount ?: -1 }.getOrDefault(-1)
                val hits = runCatching { root?.findAccessibilityNodeInfosByText(query)?.size ?: -1 }.getOrDefault(-1)
                sb.append(" | #$i type=${w.type} pkg=$pkg kids=$kids '$query'=$hits")
            }
        }.onFailure { sb.append(" EX=${it.message}") }
        return sb.toString()
    }

    fun tapAt(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 120))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }

    /** Resolution-independent tap: fx/fy are fractions of screen width/height (0f..1f). */
    fun tapRel(fx: Float, fy: Float) {
        val dm = resources.displayMetrics
        tapAt(dm.widthPixels * fx, dm.heightPixels * fy)
    }

    /** Resolution-independent swipe using screen-relative fractions. */
    fun swipeRel(fx1: Float, fy1: Float, fx2: Float, fy2: Float, durationMs: Long) {
        val dm = resources.displayMetrics
        swipe(dm.widthPixels * fx1, dm.heightPixels * fy1, dm.widthPixels * fx2, dm.heightPixels * fy2, durationMs)
    }

    /** True if any visible node across all windows has text/desc containing any of [texts]. */
    fun hasNodeText(texts: List<String>): Boolean {
        return try {
            for (root in candidateRoots()) {
                for (t in texts) {
                    val nodes = runCatching { root.findAccessibilityNodeInfosByText(t) }.getOrNull() ?: continue
                    for (n in nodes) {
                        if (runCatching { n.isVisibleToUser }.getOrDefault(false)) return true
                    }
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Opens Settings → App info for [pkg] and tries to tap "Force stop" and its
     * confirm button. Calls [onDone] with a human-readable outcome (always,
     * exactly once, within ~20s).
     */
    fun autoForceStop(pkg: String, onDone: (String) -> Unit) {
        val opened = runCatching {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }.isSuccess
        if (!opened) {
            onDone("无法打开应用信息页")
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + 18_000L
        var stage = 0 // 0 = find main button, 1 = find confirm dialog button
        var finished = false
        val finish: (String) -> Unit = { d ->
            if (!finished) { finished = true; onDone(d) }
        }

        val task = object : Runnable {
            override fun run() {
                if (SystemClock.elapsedRealtime() > deadline) {
                    val where = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: "?"
                    finish("未找到强行停止按钮(超时,当前窗口=$where)")
                    return
                }
                val root = settingsPageRoot() ?: runCatching { rootInActiveWindow }.getOrNull()
                if (root != null) {
                    if (stage == 0) {
                        val node = findClickableByText(root, MAIN_BUTTON_TEXTS)
                        if (node != null && clickNode(node)) {
                            stage = 1
                            handler.postDelayed(this, 800)
                            return
                        }
                        // button may be below the fold — try one scroll per pass
                        runCatching {
                            if (root.isScrollable) root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                    } else {
                        val node = findClickableByText(root, CONFIRM_BUTTON_TEXTS)
                        if (node != null && clickNode(node)) {
                            finish("已通过设置页强行停止 $pkg")
                            return
                        }
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(task, 900)
    }

    private fun findClickableByText(node: AccessibilityNodeInfo?, texts: List<String>): AccessibilityNodeInfo? {
        node ?: return null
        try {
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if ((t.isNotEmpty() || d.isNotEmpty()) &&
                texts.any { (t.contains(it, true) || d.contains(it, true)) } &&
                node.isVisibleToUser &&
                (node.isClickable || clickableParent(node) != null)
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                findClickableByText(node.getChild(i), texts)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        var depth = 0
        while (p != null && depth < 4) {
            if (p.isClickable) return p
            p = p.parent
            depth++
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val target = if (node.isClickable) node else clickableParent(node) ?: node
        return runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
    }
}
