package com.fergusonconsultancy.foldmotion

/**
 * Chosen in Fold Motion Studio on 14 Oct 2026:
 *   closingEffect "composition" (De Stijl, One Plane), openingEffect "creasesplit",
 *   finish "visconti", effectMs 900, revealMs 400, previewed at 1x
 *
 * Closing plays on the main screen and reaches black before One UI switches
 * to the cover. Opening blacks out the cover the moment the hinge reports
 * 90 degrees, then reveals the main screen the moment One UI switches.
 *
 * The green is an approximation of Alfa Romeo's metallic Verde Visconti;
 * no official colour value was available.
 */
object MotionConfig {
    /** Closing effect length before Animator duration scale. */
    const val EFFECT_MS = 900L

    /** Opening reveal length before Animator duration scale. Taps are ignored while it plays. */
    const val REVEAL_MS = 400L

    /** Closing stages, as shares of eased progress (0 to 1). Rules draw from 0. */
    const val PLANE_START = 0.20f
    const val PLANE_SPAN = 0.60f
    const val DUSK_START = 0.80f

    /** Haptic moments: a soft tick as the first rule draws, a crisp click as the plane lands. */
    const val TICK_AT = 0.03f
    const val CLICK_AT = 0.80f

    /** Stay black this long after the effect if no screen switch follows, then fade out. */
    const val HOLD_TIMEOUT_MS = 600L
    const val FADE_MS = 200L

    /** Opening: give up if One UI has not switched to the main screen this long after arming. */
    const val ARM_TIMEOUT_MS = 700L

    /** Opening: the overlay is cleared this long after the reveal should have ended, even if frames stall. */
    const val REVEAL_GRACE_MS = 150L

    /**
     * Normal window opacity. Android 12+ blocks touches through another app's
     * window above 80%, so the window only goes fully opaque while an effect
     * is on screen, and always drops back afterwards.
     */
    const val IDLE_WINDOW_ALPHA = 0.8f

    /** Direction of motion across the main screen. Left to right since 13 Oct 2026. */
    const val LEFT_TO_RIGHT = true

    /** Anything still playing after this long is stuck and gets reset. */
    const val WATCHDOG_MS = 3000L

    /** Android's standard hinge sensor reports 90 degree steps; ignore jitter below this. */
    const val MOVE_THRESHOLD_DEG = 1.5f
}
