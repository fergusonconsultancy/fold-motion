package com.fergusonconsultancy.foldmotion

/**
 * Louvre, closing only. Chosen in Fold Motion Studio on 13 Oct 2026:
 *   closingEffect "louvre", effectMs 900, previewed at 1x
 *
 * Opening is deliberately left clean: One UI hands over to the inner screen
 * 63 to 297ms after the hinge reports 90 degrees (measured on this phone),
 * too short to read, and the phone is usually opened edge-on anyway.
 *
 * The effect plays only on the inner screen as it closes, and the cover
 * always arrives clean. Durations follow Developer options > Animator
 * duration scale.
 */
object MotionConfig {
    /** Louvre length before Animator duration scale. */
    const val EFFECT_MS = 900L

    /** Blades across the inner screen, and the delay between each as a share of the run. */
    const val BLADES = 10
    const val BLADE_STAGGER = 0.09f

    /** Stay black this long after the blades shut if no screen switch follows, then fade out. */
    const val HOLD_TIMEOUT_MS = 600L
    const val FADE_MS = 200L

    /**
     * Normal window opacity. Android 12+ blocks touches through another app's
     * window above 80%, so the window only goes fully opaque while the blades
     * are on screen mid-fold, and always drops back afterwards.
     */
    const val IDLE_WINDOW_ALPHA = 0.8f

    /** Android's standard hinge sensor reports 90 degree steps; ignore jitter below this. */
    const val MOVE_THRESHOLD_DEG = 1.5f
}
