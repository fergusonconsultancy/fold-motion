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
    const val HOLD_MS = 600L

    /**
     * The Fold8 Ultra hinge sensor only reports 0, 90 and 180 degrees.
     * The app glides between those steps over this time to fake a smooth fold.
     */
    const val TWEEN_MS = 450L

    /** With Samsung's fine sensor, only a light smoothing against jitter. */
    const val TWEEN_FINE_MS = 70L

    /** Samsung's continuous folding angle sensor (0.01° resolution on the Fold8 Ultra). */
    const val SAMSUNG_FOLDING_ANGLE = "com.samsung.sensor.folding_angle"

    /**
     * Fade through black around the panel switch. Off: on hardware it
     * lengthened the dark gap instead of hiding it.
     */
    const val BRIDGE_THROUGH_BLACK = false

    /** Ignore hinge jitter smaller than this, in degrees. */
    const val MOVE_THRESHOLD_DEG = 1.5f

    /**
     * Android 12+ blocks touches that pass through another app's window
     * when that window is more than 80% opaque. Capping here keeps the
     * phone usable mid-fold.
     */
    const val MAX_WINDOW_ALPHA = 0.8f
}
