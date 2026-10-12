package com.fergusonconsultancy.foldmotion

/**
 * Values tuned in Fold Motion Studio on 12 Oct 2026.
 *
 * Studio export:
 *   peakBlurPx 20, frostPct 100, seeThroughPct 90,
 *   blurDrivenBy "angle", durationMs 1275, easing "glide"
 */
object MotionConfig {
    /** Peak blur behind the overlay, in dp (studio px treated as dp). */
    const val PEAK_BLUR_DP = 20f

    /** Strength of the frosted glass layer, 0..1. */
    const val FROST = 1.00f

    /** How far the moving half of the inner screen veils over, 0..1. */
    const val SEE_THROUGH = 0.90f

    /**
     * On the phone your hand sets the speed of the fold, so this is used
     * as the settle time: how long the blur takes to clear once the hinge
     * stops moving (for example when you hold the phone half open).
     */
    const val SETTLE_MS = 1275L

    /**
     * When One UI hands over to the other screen, that screen arrives
     * frosted and clears over this time. This is the studio durationMs.
     */
    const val ARRIVAL_MS = 1275L

    /** Share of ARRIVAL_MS spent lifting out of black before the frost clears. */
    const val ARRIVAL_DARK_SHARE = 0.35f

    /**
     * Closing: the inner screen fades to black between these angles so it
     * meets the panel switch already dark. Tune END to the switch angle
     * the app records (shown on its home screen).
     */
    const val CLOSE_FADE_START_DEG = 35f
    const val CLOSE_FADE_END_DEG = 10f

    /** Hinge must sit still this long before the settle starts. */
    const val HOLD_MS = 300L

    /** Ignore hinge jitter smaller than this, in degrees. */
    const val MOVE_THRESHOLD_DEG = 1.5f

    /**
     * Android 12+ blocks touches that pass through another app's window
     * when that window is more than 80% opaque. Capping here keeps the
     * phone usable mid-fold.
     */
    const val MAX_WINDOW_ALPHA = 0.8f
}
