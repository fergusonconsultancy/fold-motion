package com.fergusonconsultancy.foldmotion

/**
 * 18 Oct 2026: Signature made grander (800ms, bigger ignition, brighter flood fronts, lip flare).
 * 16 Oct 2026 (late): opening Signature (champagne seam, lacquer flood, reflection, parting); Hinge Shade rejected.
 * 16 Oct 2026 (night): closingEffect "aperture", openingEffect "hingeshade", finish "visconti", effectMs 900, revealMs 650.
 * 16 Oct 2026 (night): Lacquer Panel closing and opening.
 * 16 Oct 2026 (evening): closing Lacquer Panel, opening Arch Aperture, chosen in Studio.
 * 16 Oct 2026: closing Ruled Surface (Gaudí), opening Crease Split; Nave of Light tried and dropped.
 * 15 Oct 2026: Crease Split closing and opening.
 * Earlier, 14 Oct 2026:
 *   closingEffect "composition" (De Stijl, One Plane), openingEffect "creasesplit",
 *   finish "visconti", effectMs 900, revealMs 400 (lengthened to 650 on 14 Oct after testing), previewed at 1x
 *
 * Closing plays on the main screen and reaches black before One UI switches
 * to the cover. Opening blacks out the cover the moment the hinge reports
 * 90 degrees, then reveals the main screen the moment One UI switches.
 *
 * The green is an approximation of Alfa Romeo's metallic Verde Visconti;
 * no official colour value was available.
 */
object MotionConfig {
    enum class Effect { ONE_PLANE, CREASE_SPLIT, RULED_SURFACE, NAVE_OF_LIGHT, LACQUER_PANEL, ARCH_APERTURE, HINGE_SHADE, SIGNATURE }

    /**
     * Closing effect. Arch Aperture since 16 Oct 2026 (night), chosen in Studio: the
     * arch closes in towards the crease with its lit rim, the crease flashes, then black.
     */
    val CLOSING_EFFECT = Effect.ARCH_APERTURE

    /**
     * Opening effect. Signature since 16 Oct 2026 (late): built as a premium opening.
     * Hinge Shade, Lacquer Panel, Arch Aperture, Crease Split and Nave of Light were
     * tried before it.
     */
    val OPENING_EFFECT = Effect.SIGNATURE

    /**
     * Nave of Light: reveal progress at which the darkness starts lifting. The ribs
     * finish growing at about 0.68, so the screen stays covered until then.
     * Was 0.22 (lifting while the ribs grew); raised on 16 Oct 2026.
     */
    const val NAVE_LIFT_START = 0.6f

    /** Closing effect length before Animator duration scale. */
    const val EFFECT_MS = 900L

    /**
     * Opening reveal length before Animator duration scale. Taps are ignored while it
     * plays. 800ms since 18 Oct 2026 so Signature's four acts can land (was 650ms).
     */
    const val REVEAL_MS = 800L

    /** Closing stages, as shares of eased progress (0 to 1). Rules draw from 0. */
    const val PLANE_START = 0.20f
    const val PLANE_SPAN = 0.60f
    const val DUSK_START = 0.80f

    /** Haptic moments: a soft tick as the motion starts, a crisp click on the crease flash (Crease Split) or as the plane lands (One Plane). */
    const val TICK_AT = 0.03f
    const val CLICK_AT = 0.7f

    /** Stay black this long after the effect if no screen switch follows, then fade out. */
    const val HOLD_TIMEOUT_MS = 600L
    const val FADE_MS = 200L

    /**
     * Opening: give up if One UI has not switched to the main screen this long after
     * arming. Was 700ms; raised on 16 Oct 2026 after a wake from screen-off took longer.
     */
    const val ARM_TIMEOUT_MS = 1500L

    /** Opening: longest the reveal holds black waiting for the main screen to report it is lit. */
    const val PANEL_WAIT_MAX_MS = 350L

    /** Opening onto the lock screen: how long to wait for an unlock before skipping the reveal. */
    const val UNLOCK_WAIT_MS = 2500L

    /** A closing step this soon after an opening step is the hinge bouncing, not a close. */
    const val BOUNCE_GUARD_MS = 600L

    /**
     * Opening: if One UI switches to the main screen before the hinge sensor has
     * reported the opening, cover and reveal anyway. The screen may show for a
     * frame or two before the cover lands; set false to skip the effect instead.
     */
    const val CATCH_UP_REVEAL = true

    /** Opening: the covering window is at least this many pixels square, larger than either panel. */
    const val OVERSIZE_MIN_PX = 2800

    /** Opening: the overlay is cleared this long after the reveal should have ended, even if frames stall. Covers a late first frame. */
    const val REVEAL_GRACE_MS = 400L

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
