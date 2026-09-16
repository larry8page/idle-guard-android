package com.larry8page.idleguard

import android.content.Context

/** Thin SharedPreferences-backed settings store. */
object SettingsStore {
    private const val PREFS = "idle_guard_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_IDLE_MIN = "idle_minutes"
    const val DEFAULT_IDLE_MIN = 15
    const val MAX_IDLE_MIN = 24 * 60

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply()
    }

    fun idleMinutes(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_IDLE_MIN, DEFAULT_IDLE_MIN)
            .coerceIn(1, MAX_IDLE_MIN)

    fun setIdleMinutes(ctx: Context, m: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_IDLE_MIN, m.coerceIn(1, MAX_IDLE_MIN)).apply()
    }

    fun idleMillis(ctx: Context): Long = idleMinutes(ctx) * 60_000L
}
