package com.fergusonconsultancy.foldmotion

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timestamps and measurements shown on the home screen, so it is always clear
 * what the app is doing and, if it stops, why.
 */
object Status {
    const val STARTED = "service_started_at"
    const val PLAYED = "last_played_at"
    private const val HEALED = "last_healed_at"
    private const val HEAL_COUNT = "heal_count"

    private const val PREFS = "fold_motion"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mark(context: Context, key: String) {
        prefs(context).edit().putLong(key, System.currentTimeMillis()).apply()
    }

    fun describe(context: Context, key: String): String {
        val t = prefs(context).getLong(key, 0L)
        if (t == 0L) return "never"
        return SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(Date(t))
    }

    fun countHeal(context: Context) {
        val p = prefs(context)
        p.edit()
            .putInt(HEAL_COUNT, p.getInt(HEAL_COUNT, 0) + 1)
            .putLong(HEALED, System.currentTimeMillis())
            .apply()
    }

    fun describeHeals(context: Context): String {
        val n = prefs(context).getInt(HEAL_COUNT, 0)
        return if (n == 0) "Self-healing restarts: none needed"
        else "Self-healing restarts: $n, last ${describe(context, HEALED)}"
    }

    /** completedPercent is 100 if the effect finished, lower if the screen switch cut it off. */
    fun saveRun(context: Context, r: FrameStats.Result, effectMs: Int, completedPercent: Int) {
        prefs(context).edit()
            .putInt("run_frames", r.frames)
            .putInt("run_dropped", r.dropped)
            .putInt("run_fps", r.fps)
            .putInt("run_effect_ms", effectMs)
            .putInt("run_completed", completedPercent)
            .apply()
    }

    fun describeRun(context: Context): String {
        val p = prefs(context)
        val frames = p.getInt("run_frames", 0)
        if (frames == 0) return "Last close: no measurement yet"
        val dropped = p.getInt("run_dropped", 0)
        val fps = p.getInt("run_fps", 0)
        val effect = p.getInt("run_effect_ms", 0)
        val done = p.getInt("run_completed", 100)
        val ending = if (done >= 100) "finished before the switch" else "cut off at $done% by the switch"
        return "Last close: $frames frames at ${fps}fps, $dropped dropped, ${effect}ms effect, $ending"
    }
}
