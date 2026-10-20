package com.larry8page.idleguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny rolling event log (newest first) persisted in SharedPreferences.
 * Powers the on-device 触发日志 page so the user can see exactly when the
 * timer was armed, when it fired, and whether each action succeeded.
 */
object Logger {
    private const val PREFS = "idle_guard_log"
    private const val KEY = "log_lines"
    private const val MAX_LINES = 300
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun log(ctx: Context, msg: String) {
        if (!SettingsStore.isLogEnabled(ctx)) return // 用户关闭了日志:一条都不写
        val line = fmt.format(Date()) + "  " + msg
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = sp.getString(KEY, "").orEmpty()
        val updated = if (cur.isEmpty()) line else line + "\n" + cur
        val lines = updated.split("\n")
        val trimmed = if (lines.size <= MAX_LINES) updated else lines.subList(0, MAX_LINES).joinToString("\n")
        sp.edit().putString(KEY, trimmed).apply()
    }

    fun readAll(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
