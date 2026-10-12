package com.fergusonconsultancy.foldmotion

/**
 * Seam Close, both directions. Chosen in Fold Motion Studio on 12 Oct 2026:
 *   closingEffect "seam", openingEffect "seam", effectMs 550, previewed at 1x
 *
 * The effect only ever plays on the screen being folded away and finishes in
 * black before One UI switches panels. The arriving screen is always clean.
 * Durations follow Developer options > Animator duration scale.
 */
object MotionConfig {
    /** Closing: panels close from both outer edges to the crease. */
    const val EFFECT_MS = 550L

    /**
     * Opening: One UI switches to the inner screen roughly 170 to 300ms after
     * the hinge reports 90 degrees (measured on this phone, it varies). The
     * cover sweep is squeezed to finish inside that.
     */
    const val OPENING_FIT_MS = 150L

    /** Stay black this long after the effect if no screen switch follows, then fade out. */
    const val HOLD_TIMEOUT_MS = 600L
    const val FADE_MS = 200L

    /**
     * Normal window opacity. Android 12+ blocks touches through another app's
     * window above 80%, so the window only goes fully opaque while an effect
     * is on screen mid-fold, and always drops back afterwards.
     */
    const val IDLE_WINDOW_ALPHA = 0.8f

    /** Android's standard hinge sensor reports 90 degree steps; ignore jitter below this. */
    const val MOVE_THRESHOLD_DEG = 1.5f
}
