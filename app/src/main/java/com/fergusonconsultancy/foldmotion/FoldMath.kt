package com.fergusonconsultancy.foldmotion

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The same curves used in Fold Motion Studio, so the phone matches the preview. */
object FoldMath {
    fun clamp(v: Float, lo: Float = 0f, hi: Float = 1f): Float = min(hi, max(lo, v))

    /** Smoothstep. */
    fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    /** "Glide" easing: cubic in and out. */
    fun glide(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

    /** Blur on the cover display while it opens. Peaks by about 80 degrees. */
    fun coverMotion(progress: Float): Float = smooth(clamp(progress / 0.45f))

    /** Blur on the inner display, fading out as it reaches flat. */
    fun innerMotion(progress: Float): Float = smooth(clamp((1f - progress) / 0.55f))
}
