package com.larry8page.idleguard

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Identifies the app that currently holds the foreground, using UsageStats
 * (needs the "usage access" app-op). Used so the guard can clean up exactly the
 * app the user walked away from.
 */
object ForegroundApp {

    /** @return foreground package, or null when permission is missing / unknown. */
    fun peek(ctx: Context): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 12 * 60 * 60 * 1000L, now)
            var pkg: String? = null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = e.packageName
                }
            }
            pkg
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Whether the "usage access" special permission is granted: without it the
     * system hands back an empty usage map, so any data at all means granted.
     */
    fun isGranted(ctx: Context): Boolean {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats: Map<String, UsageStats>? =
                usm.queryAndAggregateUsageStats(now - 24 * 60 * 60 * 1000L, now)
            stats != null && stats.isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    /** Packages we should never try to "kill" (system UI / launchers / ourselves). */
    fun isNonTarget(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg == "android") return true
        val l = pkg.lowercase()
        return l.contains("launcher") || l.contains("systemui") ||
            l.contains("settings") || l.contains("inputmethod") || l.contains("mihome")
    }

    /** The package currently registered as the default HOME/launcher, or null. */
    private fun homePackage(ctx: Context): String? = runCatching {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    /**
     * True when the foreground is the DESKTOP/launcher itself — i.e. there is no
     * real user app on screen worth cleaning. Kept narrower than [isNonTarget]
     * (which also covers Settings / SystemUI): only the resolved home, plus the
     * common launcher package-name substrings, count as the desktop here.
     */
    fun isDesktop(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return false
        val home = homePackage(ctx)
        if (home != null && pkg == home) return true
        val l = pkg.lowercase()
        return l.contains("launcher") || l.contains("mihome") ||
            l.contains("husky") || l == "com.android.launcher3"
    }
}

/**
 * Best-available foreground package at this instant.
 * Order: accessibility active window (real time) → UsageStats (may lag).
 * If the winner is a system/launcher package, tries the other source first.
 */
object CurrentApp {
    fun pkg(ctx: Context): String? {
        val live = IdleAccessibilityService.instance?.currentForegroundPkg()
        val stats = ForegroundApp.peek(ctx)
        val first = live ?: stats
        val second = if (first === live) stats else live
        return if (first != null && !ForegroundApp.isNonTarget(ctx, first)) first
        else second ?: first
    }
}
