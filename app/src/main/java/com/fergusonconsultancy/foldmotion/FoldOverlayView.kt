package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.View

/**
 * Full-screen, non-touchable glass layer.
 *
 * Draws a frosted pane, masked by a moving feathered edge, with a band of
 * light riding that edge. The service picks the shape and drives `t`.
 */
class FoldOverlayView(context: Context) : View(context) {

    enum class Shape {
        /** Frost fills from the left (hinge) edge across. */
        FILL_FROM_HINGE,
        /** Frost clears from the left (hinge) edge across. */
        REVEAL_FROM_HINGE,
        /** Frost spreads outward from the crease. */
        FILL_FROM_CREASE,
        /** Frost clears outward from the crease. */
        REVEAL_FROM_CREASE,
        /** Whole screen frosted. */
        FULL
    }

    private var shape = Shape.FULL
    private var t = 0f
    private var alphaAll = 0f
    private var sheen = 0f

    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val opaque = Color.BLACK
    private val clear = Color.TRANSPARENT

    fun show(shape: Shape, t: Float, alpha: Float, sheen: Float) {
        this.shape = shape
        this.t = FoldMath.clamp(t)
        this.alphaAll = FoldMath.clamp(alpha)
        this.sheen = FoldMath.clamp(sheen)
        invalidate()
    }

    fun clearAll() {
        alphaAll = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Glass: bright top-left, Violet Shadow tint through the middle, cool at the base
        frostPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.argb(205, 246, 244, 252),
                Color.argb(170, 196, 186, 240),
                Color.argb(150, 120, 112, 160)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (alphaAll <= 0.001f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val feather = w * 0.22f
        val c = w / 2f

        // 1. Frost, masked by the moving edge
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        frostPaint.alpha = (255 * FoldMath.clamp(MotionConfig.FROST * alphaAll)).toInt()
        canvas.drawRect(0f, 0f, w, h, frostPaint)

        val edges = mutableListOf<Float>()
        when (shape) {
            Shape.FULL -> Unit
            Shape.FILL_FROM_HINGE -> {
                val e = t * (w + feather)
                mask(canvas, e - feather, opaque, e, clear, 0f, w, h)
                edges += e - feather / 2f
            }
            Shape.REVEAL_FROM_HINGE -> {
                val e = t * (w + feather)
                mask(canvas, e - feather, clear, e, opaque, 0f, w, h)
                edges += e - feather / 2f
            }
            Shape.FILL_FROM_CREASE -> {
                val r = t * (c + feather)
                // left half: frost where x > c - r
                mask(canvas, c - r, clear, c - r + feather, opaque, 0f, c, h)
                mirrored(canvas, c) { mask(canvas, c - r, clear, c - r + feather, opaque, 0f, c, h) }
                edges += c - r + feather / 2f; edges += c + r - feather / 2f
            }
            Shape.REVEAL_FROM_CREASE -> {
                val r = t * (c + feather)
                // left half: frost where x < c - r
                mask(canvas, c - r, opaque, c - r + feather, clear, 0f, c, h)
                mirrored(canvas, c) { mask(canvas, c - r, opaque, c - r + feather, clear, 0f, c, h) }
                edges += c - r + feather / 2f; edges += c + r - feather / 2f
            }
        }
        canvas.restoreToCount(layer)

        // 2. A band of light riding each moving edge
        if (sheen > 0.01f) {
            val band = w * 0.10f
            val peak = (150 * sheen * alphaAll).toInt()
            for (x in edges) {
                if (x < -band || x > w + band) continue
                sheenPaint.shader = LinearGradient(
                    x - band, 0f, x + band, 0f,
                    intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(peak, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(x - band, 0f, x + band, h, sheenPaint)
            }
        }
    }

    /** Keeps frost only where the gradient is opaque, across [left, right]. */
    private fun mask(canvas: Canvas, x0: Float, c0: Int, x1: Float, c1: Int, left: Float, right: Float, h: Float) {
        maskPaint.shader = LinearGradient(x0, 0f, x1, 0f, c0, c1, Shader.TileMode.CLAMP)
        canvas.drawRect(left, 0f, right, h, maskPaint)
    }

    private inline fun mirrored(canvas: Canvas, c: Float, block: () -> Unit) {
        val s = canvas.save()
        canvas.scale(-1f, 1f, c, 0f)
        block()
        canvas.restoreToCount(s)
    }
}
