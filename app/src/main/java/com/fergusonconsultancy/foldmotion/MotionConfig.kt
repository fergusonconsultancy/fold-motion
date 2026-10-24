package com.fergusonconsultancy.foldmotion

/**
 * 22 Oct 2026: opening Lit Glass (Frame of Light without the lacquer: dark glass, Visconti light inside it), chosen in Studio.
 * 19 Oct 2026: design charter applied (project doc fold-motion-design-charter.md): opening reflection right to left,
 *   arched bezel window, champagne motes, touch returns as the bezel seats, seal flash removed, the last light settles,
 *   light follows the time of day.
 * 18 Oct 2026 (night): opening Frame of Light, chosen in Studio: closingEffect "aperture", openingEffect "frame", finish "visconti", effectMs 900, revealMs 1000, previewed at 1x.
 * 18 Oct 2026 (late): soft-close attraction on closing, rounded release on opening, Signature break-away glow and haptic, parting on real time.
 * 18 Oct 2026: magnetic motion curves (attraction on closing, cling and release on opening), opening 1000ms.
 * 18 Oct 2026: closing planned backwards from the predicted switch (fixed finale, elastic build-up).
 * 18 Oct 2026: Arch Aperture made a grand closing (lacquer curtains, champagne rim, seal flash, last point of light).
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
    enum class Effect { ONE_PLANE, CREASE_SPLIT, RULED_SURFACE, NAVE_OF_LIGHT, LACQUER_PANEL, ARCH_APERTURE, HINGE_SHADE, SIGNATURE, FRAME_OF_LIGHT, LIT_GLASS, LAMP_GLASS, FANALE, MEMBRANA, FANALE_REVERSE }

    /**
     * The pairings Mechack chooses between in the app (23 Oct 2026). Each names its closing and
     * opening; [apply] sets them before every run.
     */
    enum class Style(val label: String, val detail: String, val closing: Effect, val opening: Effect) {
        VISCONTI("Visconti", "Lacquer arch closing, lit glass opening", Effect.ARCH_APERTURE, Effect.LIT_GLASS),
        GIULIETTA("Giulietta", "Lit glass, woken by the rear lamps", Effect.ARCH_APERTURE, Effect.LAMP_GLASS),
        FANALE("Fanale", "Alfa Romeo only: the lamps light, part and lock", Effect.FANALE, Effect.FANALE),
        MEMBRANA("Membrana", "One living membrane parts on curves and snaps taut", Effect.MEMBRANA, Effect.MEMBRANA),
    }

    /** The colour of Membrana's rim light: glow and core. */
    enum class Rim(val label: String, val glow: Int, val core: Int) {
        CHAMPAGNE("Champagne", android.graphics.Color.rgb(236, 196, 120), android.graphics.Color.rgb(255, 244, 222)),
        ROSSO("Rosso", android.graphics.Color.rgb(246, 20, 34), android.graphics.Color.rgb(255, 118, 98)),
        VISCONTI("Visconti", android.graphics.Color.rgb(36, 222, 120), android.graphics.Color.rgb(200, 255, 214)),
    }

    fun rim(context: android.content.Context): Rim = runCatching {
        Rim.valueOf(context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("rim", null)!!)
    }.getOrDefault(Rim.CHAMPAGNE)

    fun setRim(context: android.content.Context, r: Rim) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("rim", r.name).apply()
        RIM = r
    }

    /**
     * Concentrato (Bellezza Necessaria, principle 2): the faster the fold, the emptier the
     * transition. On by default; off keeps every detail at every speed.
     */
    fun concentrato(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean("concentrato", true)

    fun setConcentrato(context: android.content.Context, on: Boolean) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("concentrato", on).apply()

    var RIM = Rim.CHAMPAGNE
        private set

    private const val PREFS = "fold_motion"
    private const val STYLE_KEY = "style"

    fun style(context: android.content.Context): Style = runCatching {
        Style.valueOf(context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(STYLE_KEY, null)!!)
    }.getOrDefault(Style.VISCONTI)

    /** A preset: sets the opening and the closing together. */
    fun setStyle(context: android.content.Context, s: Style) {
        prefs(context).edit().putString(STYLE_KEY, s.name).putString(OPENING_KEY, s.opening.name).putString(CLOSING_KEY, s.closing.name).apply()
        apply(context)
    }

    // ---- Fine tuning (24 Oct 2026): opening and closing chosen and tuned separately ----------

    /** The openings offered in the app. */
    enum class Opening(val label: String, val detail: String, val effect: Effect) {
        LIT_GLASS("Lit glass", "Dark glass with Visconti light inside it parts into a lit bezel", Effect.LIT_GLASS),
        GIULIETTA("Giulietta", "Lit glass, woken by the rear lamps", Effect.LAMP_GLASS),
        FANALE("Fanale", "The lamps light in sequence, bloom, and the halves part", Effect.FANALE),
        FANALE_REVERSE("Fanale reversed", "The Fanale closing played backwards: a point of light rises from the hinge and the lamps come on as the halves part", Effect.FANALE_REVERSE),
        MEMBRANA("Membrana", "One membrane parts on curves and snaps taut", Effect.MEMBRANA),
    }

    /** The closings offered in the app. */
    enum class Closing(val label: String, val detail: String, val effect: Effect) {
        ARCH("Lacquer arch", "A lacquer arch closes in on the crease; the last light dips into the hinge", Effect.ARCH_APERTURE),
        FANALE("Fanale", "Locking the car: the lit halves meet and the lamps go out like an indicator", Effect.FANALE),
        MEMBRANA("Membrana", "The membrane is drawn in to a lens that seals into a last point of light", Effect.MEMBRANA),
    }

    private const val OPENING_KEY = "opening_effect"
    private const val CLOSING_KEY = "closing_effect"
    private fun prefs(c: android.content.Context) = c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun opening(c: android.content.Context): Opening =
        Opening.entries.firstOrNull { it.effect.name == prefs(c).getString(OPENING_KEY, null) }
            ?: Opening.entries.first { it.effect == style(c).opening }

    fun closing(c: android.content.Context): Closing =
        Closing.entries.firstOrNull { it.effect.name == prefs(c).getString(CLOSING_KEY, null) }
            ?: Closing.entries.first { it.effect == style(c).closing }

    fun setOpening(c: android.content.Context, o: Opening) { prefs(c).edit().putString(OPENING_KEY, o.effect.name).apply(); apply(c) }
    fun setClosing(c: android.content.Context, k: Closing) { prefs(c).edit().putString(CLOSING_KEY, k.effect.name).apply(); apply(c) }

    /** The preset matching the chosen opening and closing, if any. */
    fun matchingStyle(c: android.content.Context): Style? = Style.entries.firstOrNull { it.opening == opening(c).effect && it.closing == closing(c).effect }

    /**
     * Brightness of the light (a multiplier: 0.6 dim to 1.6 vivid) and speed (1 as designed,
     * above 1 quicker, below 1 slower), each for the opening and the closing.
     */
    fun openBrightness(c: android.content.Context) = prefs(c).getFloat("open_brightness", 1f)
    fun closeBrightness(c: android.content.Context) = prefs(c).getFloat("close_brightness", 1f)
    fun openSpeed(c: android.content.Context) = prefs(c).getFloat("open_speed", 1f)
    fun closeSpeed(c: android.content.Context) = prefs(c).getFloat("close_speed", 1f)
    fun setTuning(c: android.content.Context, key: String, v: Float) = prefs(c).edit().putFloat(key, v).apply()

    const val BRIGHTNESS_MIN = 0.6f
    const val BRIGHTNESS_MAX = 1.6f
    const val SPEED_MIN = 0.7f
    const val SPEED_MAX = 1.4f

    /** Reads the chosen opening and closing into the effects the overlay draws. */
    fun apply(context: android.content.Context) {
        CLOSING_EFFECT = closing(context).effect
        OPENING_EFFECT = opening(context).effect
        RIM = rim(context)
    }

    /** Closing effect; set from the chosen style. Arch Aperture since 16 Oct 2026 (night). */
    var CLOSING_EFFECT = Effect.ARCH_APERTURE
        private set

    /** Opening effect; set from the chosen style. Lit Glass since 22 Oct 2026. */
    var OPENING_EFFECT = Effect.LIT_GLASS
        private set

    /** Giulietta: the lamps in Alfa red inside the dark Visconti glass (false: Visconti light). */
    const val LAMP_GLASS_ROSSO = true

    /**
     * Fanale, as shares of the reveal's time: the left ring closes and the lamps bloom at
     * LIGHTS_T (the haptic click); touch returns at TOUCH_T, when the halves have nearly gone.
     * Closing: the halves meet at CONTACT_P of the closing's progress (the click).
     */
    const val FANALE_LIGHTS_T = 0.54f
    const val FANALE_TOUCH_T = 0.8f
    const val FANALE_CONTACT_P = 0.545f

    /**
     * Membrana, as shares of the reveal's time: the membrane snaps taut at TAUT_T (the haptic
     * click, and touch returns). Closing: the edges meet at CONTACT_P.
     */
    const val MEMBRANA_TAUT_T = 0.7f
    const val MEMBRANA_CONTACT_P = 0.545f

    /**
     * Fanale reversed, as shares of the reveal's time: the closing's contact (p = 0.545) comes at
     * about 0.48 of the reveal (the click), and touch returns at 0.75, the halves nearly gone.
     */
    const val FANALE_REVERSE_CLICK_T = 0.48f
    const val FANALE_REVERSE_TOUCH_T = 0.75f

    /**
     * Concentrato's speed scale. Opening: the time from the hinge passing 90 degrees to One UI
     * switching (fast at or under FAST, slow at or over SLOW). Closing: the build-up time the
     * plan has, which follows how quickly this owner usually closes.
     */
    const val OPEN_FAST_MS = 140L
    const val OPEN_SLOW_MS = 520L
    /** Fewest details Concentrato keeps: the trajectory always stays. */
    const val DENSITY_FLOOR = 0.25f

    /**
     * Nave of Light: reveal progress at which the darkness starts lifting. The ribs
     * finish growing at about 0.68, so the screen stays covered until then.
     * Was 0.22 (lifting while the ribs grew); raised on 16 Oct 2026.
     */
    const val NAVE_LIFT_START = 0.6f

    /** Closing effect length before Animator duration scale, when there is time for all of it. */
    const val EFFECT_MS = 900L

    /**
     * Closing is planned backwards from the predicted switch (18 Oct 2026). The finale,
     * from BUILD_END to black (the seal and the last point of light), always gets
     * FINALE_MS and ends SWITCH_SAFETY_MS before the switch is expected. The build-up
     * (0 to BUILD_END) gets what is left, never less than MIN_BUILD_MS.
     */
    const val FINALE_MS = 300L
    const val BUILD_END = 0.55f
    const val SWITCH_SAFETY_MS = 40L
    const val MIN_BUILD_MS = 60L

    /**
     * Opening reveal length before Animator duration scale. Taps are ignored while it
     * plays. 1000ms since 18 Oct 2026 (magnetic motion, slowed as far as is sensible;
     * was 800ms, 650ms before that).
     */
    const val REVEAL_MS = 1000L

    /**
     * Signature: share of the reveal's time at which the halves break their magnetic
     * hold (the parting starts at 0.5 over 0.45, and the cling is the first 20% of it).
     * The glow crests and a light haptic click plays here: 590ms into a 1000ms reveal.
     */
    const val SIGNATURE_BREAK_T = 0.5f + 0.2f * 0.45f

    /**
     * Frame of Light, as shares of the reveal's time. The halves part from 0.42 over 0.34,
     * so the hold gives at 0.488 (the glow crests); the bezel seats at 0.735 (the haptic
     * click, mirroring the closing's click at contact); it holds until 0.84, then slides away.
     */
    const val FRAME_BREAK_T = 0.42f + 0.2f * 0.34f
    const val FRAME_SEAT_T = 0.735f
    const val FRAME_HOLD_T = 0.84f

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
