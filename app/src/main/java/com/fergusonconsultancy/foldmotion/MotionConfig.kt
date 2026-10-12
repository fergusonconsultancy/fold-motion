package com.fergusonconsultancy.foldmotion

/**
 * Choreographed fold transition.
 *
 * The Fold8 Ultra only reports the hinge at 0, 90 and 180 degrees to apps,
 * and Samsung's fine sensor is reserved for its own software. So rather than
 * track the hinge, each hinge step and screen switch triggers a timed,
 * designed animation. All durations follow Developer options >
 * Animator duration scale.
 */
object MotionConfig {
    /** Strength of the frosted glass, 0..1 (studio frostPct). */
    const val FROST = 1.00f

    /** Peak blur behind the glass, in dp, when the phone allows live blur. */
    const val PEAK_BLUR_DP = 20f

    /** Frost spreading across the screen you are folding away from. */
    const val FILL_MS = 380L

    /**
     * Frost clearing on the screen you arrive on. Shorter than the studio's
     * 1275ms: One UI already spends 300 to 430ms switching panels, and the
     * whole thing felt sluggish at full length.
     */
    const val REVEAL_MS = 750L

    /** If no screen switch follows (phone held half open), fade out after this. */
    const val HOLD_TIMEOUT_MS = 700L
    const val FADE_MS = 250L

    /**
     * Android 12+ blocks touches through another app's window above 80%
     * opacity, so the glass never goes beyond this.
     */
    const val MAX_WINDOW_ALPHA = 0.8f

    /** Android's standard hinge sensor reports steps; ignore anything smaller. */
    const val MOVE_THRESHOLD_DEG = 1.5f
}
