package com.fergusonconsultancy.foldmotion

/**
 * De Stijl Rules in Visconti green, closing only. Chosen in Fold Motion
 * Studio on 13 Oct 2026, replacing Louvre Signature:
 *   closingEffect "destijl", finish "visconti", effectMs 900, previewed at 1x
 *
 * Opening is deliberately left clean: One UI hands over to the inner screen
 * 63 to 297ms after the hinge reports 90 degrees (measured on this phone),
 * too short to read, and the phone is usually opened edge-on anyway.
 *
 * The green is an approximation of Alfa Romeo's metallic Verde Visconti;
 * no official colour value was available.
 */
object MotionConfig {
    /** Effect length before Animator duration scale. */
    const val EFFECT_MS = 900L

    /** Stage timings, as shares of eased progress (0 to 1). Rules draw from 0. */
    const val CELLS_START = 0.28f
    const val CELLS_END = 0.82f
    const val CELL_FILL = 0.14f
    const val DUSK_START = 0.80f

    /** Haptic moments: a soft tick as the first rule starts to draw, a crisp click as the last cell lands. */
    const val TICK_AT = 0.03f
    const val CLICK_AT = CELLS_END

    /** Stay black this long after the effect if no screen switch follows, then fade out. */
    const val HOLD_TIMEOUT_MS = 600L
    const val FADE_MS = 200L

    /**
     * Normal window opacity. Android 12+ blocks touches through another app's
     * window above 80%, so the window only goes fully opaque while the effect
     * is on screen mid-fold, and always drops back afterwards.
     */
    const val IDLE_WINDOW_ALPHA = 0.8f

    /** Direction of the wave across the inner screen. Changed to left to right on 13 Oct 2026. */
    const val LEFT_TO_RIGHT = true

    /** Anything still playing after this long is stuck and gets reset. */
    const val WATCHDOG_MS = 3000L

    /** Android's standard hinge sensor reports 90 degree steps; ignore jitter below this. */
    const val MOVE_THRESHOLD_DEG = 1.5f
}
