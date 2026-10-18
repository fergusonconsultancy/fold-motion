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
    // v2 from 18 Oct 2026: closes now start earlier (fold posture trigger), so the samples
    // recorded under the old trigger understated the time available and were discarded
    private const val KEY = "close_switch_ms_v2"

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

    /**
     * When One UI is expected to switch to the cover, measured from the effect starting:
     * a cautious figure (the fastest fifth of recent closes, less a margin). Null until
     * enough closes are recorded.
     */
    fun predictedSwitchMs(context: Context): Float? {
        val s = samples(context)
        if (s.size < MIN_SAMPLES) return null
        val sorted = s.sorted()
        return (sorted[((sorted.size - 1) * PERCENTILE).roundToInt()] - MARGIN_MS).toFloat()
    }

    fun describe(context: Context, requestedMs: Float): String {
        val predicted = predictedSwitchMs(context)
        if (predicted != null) {
            val plan = Plan.make(predicted, requestedMs / MotionConfig.EFFECT_MS)
            return "Closing planned back from your switch at ${predicted.roundToInt()}ms: " +
                "build-up ${plan.buildMs.roundToInt()}ms, finale ${plan.finaleMs.roundToInt()}ms " +
                "(learned from your last ${samples(context).size} closes)"
        }
        val n = samples(context).size
        return if (n < MIN_SAMPLES) {
            "Learning your closing speed: $n of $MIN_SAMPLES closes recorded (using ${requestedMs.roundToInt()}ms)"
        } else {
            "Effect fitted to ${fittedMs(context, requestedMs).roundToInt()}ms, learned from your last $n closes"
        }
    }
}

/**
 * The closing effect, planned backwards from the predicted switch. The finale (the seal
 * and the last light) has a fixed length and is timed to finish just before the switch;
 * the build-up gets whatever time is left, up to its full designed length.
 */
data class Plan(val buildMs: Float, val finaleMs: Float) {
    companion object {
        fun make(predictedSwitchMs: Float?, scale: Float): Plan {
            val finale = MotionConfig.FINALE_MS * scale
            val fullBuild = (MotionConfig.EFFECT_MS - MotionConfig.FINALE_MS) * scale
            val available = (predictedSwitchMs ?: (MotionConfig.EFFECT_MS * scale)) - finale - MotionConfig.SWITCH_SAFETY_MS
            return Plan(available.coerceIn(MotionConfig.MIN_BUILD_MS * scale, fullBuild), finale)
        }
    }
}
