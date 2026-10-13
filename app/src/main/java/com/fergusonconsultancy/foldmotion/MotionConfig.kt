package com.fergusonconsultancy.foldmotion

/**
 * Louvre Signature in Visconti green, closing only. Chosen in Fold Motion
 * Studio on 13 Oct 2026:
 *   closingEffect "louvre2", finish "visconti", effectMs 900, previewed at 1x
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

    /** Blades across the inner screen. */
    const val BLADES = 10

    /** Stage timings, as shares of eased progress (0 to 1). */
    const val BLADES_START = 0.04f
    const val BLADES_END = 0.74f
    const val BLADE_TURN = 0.30f
    const val PULSE_START = 0.72f
    const val PULSE_LENGTH = 0.18f
    const val DUSK_START = 0.80f

    /** Haptic moments: a soft tick as the wave starts, a crisp click on the light pulse. */
    const val TICK_AT = BLADES_START
    const val CLICK_AT = PULSE_START

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
