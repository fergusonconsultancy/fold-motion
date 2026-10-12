package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import com.fergusonconsultancy.foldmotion.MotionConfig.Effect
import kotlin.math.max

/**
 * Draws the transition over the screen being folded away. Both effects travel
 * right to left and leave the screen covered at p = 1. Ported from the
 * Fold Motion Studio preview so the phone matches what was approved.
 */
class FoldOverlayView(context: Context) : View(context) {

    var effect: Effect = MotionConfig.CLOSING_EFFECT

    private var p = 0f
    private var alphaAll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(progress: Float, alpha: Float = 1f) {
        p = FoldMath.clamp(progress)
        alphaAll = FoldMath.clamp(alpha)
        invalidate()
    }

    fun clearAll() {
        p = 0f
        alphaAll = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (alphaAll <= 0.001f || p <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()

        val layer = if (alphaAll < 1f) canvas.saveLayerAlpha(0f, 0f, w, h, (255 * alphaAll).toInt()) else -1
        when (effect) {
            Effect.ROLL -> drawRoll(canvas, w, h)
            Effect.SLIDE -> drawSlide(canvas, w, h)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /** A glossy cylinder rolls in from the right, laying a dark sheet behind it. */
    private fun drawRoll(canvas: Canvas, w: Float, h: Float) {
        val r = w * 0.075f
        val cx = w + r - p * (w + r * 2f)

        // Sheet laid down behind the roll
        if (cx < w) {
            gradient(
                cx, max(cx + 1f, w),
                intArrayOf(Color.rgb(27, 24, 40), Color.rgb(11, 10, 18), Color.rgb(3, 3, 5)),
                floatArrayOf(0f, 0.35f, 1f)
            )
            canvas.drawRect(max(0f, cx), 0f, w + 1f, h, fill)
        }

        // Shadow running ahead of the roll
        val sw = w * 0.12f
        gradient(
            cx - r - sw, cx - r,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(128, 0, 0, 0)),
            null
        )
        canvas.drawRect(cx - r - sw, 0f, cx - r, h, fill)

        // The roll: shading across a cylinder with a specular stripe
        gradient(
            cx - r, cx + r,
            intArrayOf(
                Color.rgb(11, 11, 16), Color.rgb(56, 53, 74), Color.rgb(216, 209, 244),
                Color.rgb(138, 131, 173), Color.rgb(30, 28, 42), Color.rgb(11, 11, 16)
            ),
            floatArrayOf(0f, 0.22f, 0.4f, 0.5f, 0.75f, 1f)
        )
        canvas.drawRect(cx - r, 0f, cx + r, h, fill)
    }

    /** A tinted sheet slides in from the right like a card, with a lit leading edge. */
    private fun drawSlide(canvas: Canvas, w: Float, h: Float) {
        val x = (1f - p) * w
        val sw = w * 0.14f

        // Soft shadow ahead of the sheet
        gradient(x - sw, x, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(128, 0, 0, 0)), null)
        canvas.drawRect(x - sw, 0f, x, h, fill)

        // The sheet
        gradient(
            x, x + w * 0.7f,
            intArrayOf(Color.rgb(44, 40, 64), Color.rgb(22, 20, 31), Color.rgb(7, 7, 10), Color.BLACK),
            floatArrayOf(0f, 0.18f, 0.6f, 1f)
        )
        canvas.drawRect(x, 0f, w + 1f, h, fill)

        // Lit leading edge
        plain.shader = null
        plain.color = Color.argb((255 * 0.65f * (1f - p * 0.6f)).toInt(), 233, 223, 200)
        canvas.drawRect(x, 0f, x + 2f * resources.displayMetrics.density / 2f, h, plain)

        // Faint glow just inside the edge
        val glow = (255 * 0.18f * (1f - p)).toInt()
        if (glow > 0) {
            val cxg = x + w * 0.01f
            val hw = w * 0.02f
            gradient(
                cxg - hw, cxg + hw,
                intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(glow, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.5f, 1f)
            )
            canvas.drawRect(cxg - hw, 0f, cxg + hw, h, fill)
        }
    }

    private fun gradient(x0: Float, x1: Float, colors: IntArray, stops: FloatArray?) {
        fill.shader = LinearGradient(x0, 0f, x1, 0f, colors, stops, Shader.TileMode.CLAMP)
    }
}
