package com.fergusonconsultancy.foldmotion

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The same curves used in Fold Motion Studio, so the phone matches the preview. */
object FoldMath {
    fun clamp(v: Float, lo: Float = 0f, hi: Float = 1f): Float = min(hi, max(lo, v))

    /**
     * Magnetic attraction (18 Oct 2026). Two things drawn together: an unhurried start,
     * a pull that strengthens as the gap closes, then contact with a soft, damped settle
     * (a 1.2% overshoot that dies away) rather than a hard stop.
     */
    fun magnetIn(u: Float): Float {
        val c = clamp(u)
        val contact = 0.88f
        if (c < contact) {
            val v = c / contact
            return v * v * (0.35f + 0.65f * v)
        }
        val w = (c - contact) / (1f - contact)
        return 1f + 0.012f * kotlin.math.sin(Math.PI.toFloat() * w) * (1f - w)
    }

    /**
     * Magnetic release: two things held together. They cling first (a slight creep as the
     * hold gives), break away, then glide to rest. The opposite of magnetIn.
     */
    fun magnetOut(u: Float): Float {
        val c = clamp(u)
        val hold = 0.2f
        if (c < hold) return 0.035f * smooth(c / hold)
        return 0.035f + 0.965f * glide((c - hold) / (1f - hold))
    }

    /** Smoothstep. */
    fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    /** "Glide" easing: cubic in and out. */
    fun glide(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

    /** Blur on the cover display while it opens. Peaks by about 80 degrees. */
    fun coverMotion(progress: Float): Float = smooth(clamp(progress / 0.45f))

    /** Inner screen is near square; the cover is tall and narrow (about 0.46). */
    fun isInnerShape(w: Float, h: Float): Boolean = min(w, h) / max(w, h) > 0.75f

    /** Blur on the inner display, fading out as it reaches flat. */
    fun innerMotion(progress: Float): Float = smooth(clamp((1f - progress) / 0.55f))
}
