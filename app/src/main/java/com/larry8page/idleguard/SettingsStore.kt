package com.larry8page.idleguard

import android.content.Context

/** Thin SharedPreferences-backed settings store. */
object SettingsStore {
    private const val PREFS = "idle_guard_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_IDLE_MIN = "idle_minutes"
    private const val KEY_COUNTDOWN_MIN = "countdown_minutes"
    private const val KEY_WHITELIST_CCTV = "whitelist_cctv"
    const val DEFAULT_IDLE_MIN = 1
    const val DEFAULT_COUNTDOWN_MIN = 0
    const val MAX_IDLE_MIN = 24 * 60

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()
    }

    fun idleMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_IDLE_MIN, DEFAULT_IDLE_MIN).coerceIn(1, MAX_IDLE_MIN)

    fun setIdleMinutes(ctx: Context, m: Int) {
        prefs(ctx).edit().putInt(KEY_IDLE_MIN, m.coerceIn(1, MAX_IDLE_MIN)).apply()
    }

    fun idleMillis(ctx: Context): Long = idleMinutes(ctx) * 60_000L

    /** Second-stage grace: minutes to wait after going HOME before kill+lock. 0 = off. */
    fun countdownMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_COUNTDOWN_MIN, DEFAULT_COUNTDOWN_MIN).coerceIn(0, MAX_IDLE_MIN)

    fun setCountdownMinutes(ctx: Context, m: Int) {
        prefs(ctx).edit().putInt(KEY_COUNTDOWN_MIN, m.coerceIn(0, MAX_IDLE_MIN)).apply()
    }

    fun countdownMillis(ctx: Context): Long = countdownMinutes(ctx) * 60_000L

    /** When 央视频 is the foreground app at trigger time, guide it to CCTV1 instead of kill+lock. */
    fun whitelistCctv(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WHITELIST_CCTV, true)

    fun setWhitelistCctv(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WHITELIST_CCTV, v).apply()
    }
}
