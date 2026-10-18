package com.fergusonconsultancy.foldmotion

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The same curves used in Fold Motion Studio, so the phone matches the preview. */
object FoldMath {
    fun clamp(v: Float, lo: Float = 0f, hi: Float = 1f): Float = min(hi, max(lo, v))

    /**
     * Soft-close attraction (18 Oct 2026, replaces the first magnetIn). A fusion of a
     * magnet and a soft-close damper: the pull strengthens to a peak at about 59%
     * (2.0x average speed), then a damper takes over and brings it into contact at 86%
     * still moving gently (0.29x), carrying into a 0.5% settle that dies to rest.
     * Speed is continuous everywhere; the first version dropped from 2.9x to 0.3x in
     * one step at contact, which reads as a hard stop.
     * Speed before contact: (12 - 3k) v^2 (1 - v) + k v^3, integrated below.
     */
    fun magnetIn(u: Float): Float {
        val c = clamp(u)
        val contact = 0.86f
        val k = 0.25f
        if (c < contact) {
            val v = c / contact
            val v3 = v * v * v
            return (12f - 3f * k) * (v3 / 3f - v3 * v / 4f) + k * v3 * v / 4f
        }
        val w = (c - contact) / (1f - contact)
        val amp = k * (1f - contact) / (contact * Math.PI.toFloat())
        return 1f + amp * kotlin.math.sin(Math.PI.toFloat() * w) * (1f - w) * (1f - w)
    }

    /**
     * Magnetic release: two things held together. They cling first (a slight creep as the
     * hold gives), break away, then glide to rest. The opposite of magnetIn.
     * The release uses smootherstep (18 Oct 2026): a rounded speed peak of 2.26x at 60%.
     * It was glide, whose speed rose to a sharp 3.6x point.
     */
    fun magnetOut(u: Float): Float {
        val c = clamp(u)
        val hold = 0.2f
        if (c < hold) return 0.035f * smooth(c / hold)
        val t = (c - hold) / (1f - hold)
        return 0.035f + 0.965f * (t * t * t * (t * (6f * t - 15f) + 10f))
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
