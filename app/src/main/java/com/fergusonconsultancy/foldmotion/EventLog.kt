package com.fergusonconsultancy.foldmotion

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short rolling log of what the service saw and decided, shown on the home
 * screen. Written a few times per fold at most, so the cost is negligible.
 */
object EventLog {
    private const val PREFS = "fold_motion"
    private const val KEY = "event_log"
    private const val MAX_LINES = 16

    @Synchronized
    fun add(context: Context, line: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        val lines = (p.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } + "$stamp  $line")
            .takeLast(MAX_LINES)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .ifBlank { "Nothing logged yet" }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
