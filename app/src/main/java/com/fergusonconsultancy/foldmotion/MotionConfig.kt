package com.fergusonconsultancy.foldmotion

/**
 * Lumen Line with a smoked glass finish, both directions. Chosen in Fold Motion
 * Studio on 13 Oct 2026: closingEffect "lumenglass", openingEffect "lumenglass",
 * effectMs 725, previewed at 1x.
 *
 * A blade of light sweeps right to left with a soft bloom, leaving dark smoked
 * glass behind it with the violet afterglow caught in the surface. It only
 * ever plays on the screen being folded away and the arriving screen is always
 * clean. Durations follow Developer options > Animator duration scale.
 */
object MotionConfig {
    /** Closing sweep length before Animator duration scale. */
    const val EFFECT_MS = 725L

    /**
     * One UI switches to the inner screen 63 to 297ms after the hinge reports
     * 90 degrees when opening (measured on this phone, it varies). The cover
     * sweep is squeezed to fit the shortest measured window.
     */
    const val OPENING_FIT_MS = 60L

    /**
     * Opening stops short of black: the blade comes to rest just inside the
     * hinge edge, so the violet afterglow holds on the cover until One UI
     * switches, rather than the cover sitting dark for up to ~240ms.
     */
    const val OPENING_END_P = 0.9f

    /** How long the resting afterglow takes to ease its bloom down while it holds. */
    const val AFTERGLOW_SETTLE_MS = 400L

    /** Stay dark this long after the sweep if no screen switch follows, then fade out. */
    const val HOLD_TIMEOUT_MS = 600L
    const val FADE_MS = 200L

    /**
     * Normal window opacity. Android 12+ blocks touches through another app's
     * window above 80%, so the window only goes fully opaque while the sweep
     * is on screen mid-fold, and always drops back afterwards.
     */
    const val IDLE_WINDOW_ALPHA = 0.8f

    /** Android's standard hinge sensor reports 90 degree steps; ignore jitter below this. */
    const val MOVE_THRESHOLD_DEG = 1.5f
}
