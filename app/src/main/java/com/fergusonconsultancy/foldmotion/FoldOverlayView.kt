package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Full-screen, non-touchable layer that draws the frost and the veil.
 * The real blur comes from the window's blur-behind setting in the service.
 */
class FoldOverlayView(context: Context) : View(context) {

    private var intensity = 0f
    private var progress = 0f

    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** True when the window is the near-square inner display, in any orientation. */
    val isInnerDisplay: Boolean
        get() = width > 0 && height > 0 && FoldMath.isInnerShape(width.toFloat(), height.toFloat())

    fun update(intensity: Float, progress: Float) {
        this.intensity = intensity
        this.progress = progress
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Diagonal glass sheen: white into the Violet Shadow tint
        frostPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.argb(150, 255, 255, 255),
                Color.argb(80, 179, 166, 242),
                Color.argb(25, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        // Veil fades in across the crease instead of starting on a hard line
        veilPaint.shader = LinearGradient(
            w * 0.40f, 0f, w * 0.62f, 0f,
            Color.argb(0, 15, 16, 20), Color.argb(255, 15, 16, 20),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (intensity <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Veil over the moving (right) half of the inner screen
        if (isInnerDisplay) {
            val veil = MotionConfig.SEE_THROUGH * sin(PI.toFloat() * progress) * intensity
            veilPaint.alpha = (255 * FoldMath.clamp(veil)).toInt()
            canvas.drawRect(w * 0.40f, 0f, w, h, veilPaint)
        }

        frostPaint.alpha = (255 * FoldMath.clamp(MotionConfig.FROST * intensity)).toInt()
        canvas.drawRect(0f, 0f, w, h, frostPaint)
    }
}
