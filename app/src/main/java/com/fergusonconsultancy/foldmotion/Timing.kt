package com.fergusonconsultancy.foldmotion

import android.content.Context
import kotlin.math.roundToInt

/**
 * Learns how quickly this phone, in this owner's hands, hands over from the
 * inner screen to the cover, and fits the effect inside that window.
 *
 * Each close records the time from the effect starting to One UI switching
 * panels. Once enough closes are recorded, the effect length is set from the
 * fastest fifth of them, minus a margin, so the dusk reaches black before
 * the switch on almost every close.
 */
object Timing {
    private const val PREFS = "fold_motion"
    private const val KEY = "close_switch_ms"

    const val MAX_SAMPLES = 20
    const val MIN_SAMPLES = 5
    private const val PERCENTILE = 0.2
    private const val MARGIN_MS = 50L
    const val MIN_EFFECT_MS = 450f

    /** Ignore readings that cannot be a normal close (a pause mid-fold, a missed event). */
    private val PLAUSIBLE = 100L..3000L

    fun record(context: Context, ms: Long) {
        if (ms !in PLAUSIBLE) return
        val updated = (samples(context) + ms).takeLast(MAX_SAMPLES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, updated.joinToString(",")).apply()
    }

    fun samples(context: Context): List<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }

    /**
     * Effect length to use, given the length the scale setting asks for.
     * Never longer than requested; never shorter than MIN_EFFECT_MS unless
     * the requested length itself is shorter.
     */
    fun fittedMs(context: Context, requestedMs: Float): Float {
        val s = samples(context)
        if (s.size < MIN_SAMPLES) return requestedMs
        val sorted = s.sorted()
        val window = sorted[((sorted.size - 1) * PERCENTILE).roundToInt()] - MARGIN_MS
        return minOf(requestedMs, maxOf(MIN_EFFECT_MS, window.toFloat()))
    }

    fun describe(context: Context, requestedMs: Float): String {
        val n = samples(context).size
        return if (n < MIN_SAMPLES) {
            "Learning your closing speed: $n of $MIN_SAMPLES closes recorded (using ${requestedMs.roundToInt()}ms)"
        } else {
            "Effect fitted to ${fittedMs(context, requestedMs).roundToInt()}ms, learned from your last $n closes"
        }
    }
}
