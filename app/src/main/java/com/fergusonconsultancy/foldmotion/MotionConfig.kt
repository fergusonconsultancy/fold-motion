package com.fergusonconsultancy.foldmotion

/**
 * Chosen in Fold Motion Studio on 12 Oct 2026:
 *   closingEffect "roll", openingEffect "slide", effectMs 600, previewed at 1x
 *
 * Both travel right to left. Effects only ever play on the screen being
 * folded away and finish dark before One UI switches panels. The arriving
 * screen is always clean. Durations follow Developer options > Animator
 * duration scale.
 */
object MotionConfig {
    enum class Effect { ROLL, SLIDE }

    /** Inner screen folding shut. */
    val CLOSING_EFFECT = Effect.ROLL

    /** Cover screen as you open the phone. */
    val OPENING_EFFECT = Effect.SLIDE

    /** Effect length before Animator duration scale. */
    const val EFFECT_MS = 600L

    /**
     * One UI switches to the inner screen roughly 170 to 300ms after the hinge
     * reports 90 degrees when opening (measured on this phone, it varies). The
     * opening effect is squeezed to finish inside that.
     */
    const val OPENING_FIT_MS = 150L

    /** Stay covered this long after the effect if no screen switch follows, then fade out. */
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
