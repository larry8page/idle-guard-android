package com.larry8page.idleguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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
        instance = null
        super.onDestroy()
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
