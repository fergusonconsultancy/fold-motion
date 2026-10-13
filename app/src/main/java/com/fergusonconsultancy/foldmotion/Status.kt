package com.fergusonconsultancy.foldmotion

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timestamps shown on the home screen, so that if the effect stops working
 * it is clear whether the service was killed or something else went wrong.
 */
object Status {
    const val STARTED = "service_started_at"
    const val PLAYED = "last_played_at"

    private const val PREFS = "fold_motion"

    fun mark(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(key, System.currentTimeMillis()).apply()
    }

    fun describe(context: Context, key: String): String {
        val t = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        if (t == 0L) return "never"
        return SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(Date(t))
    }
}
