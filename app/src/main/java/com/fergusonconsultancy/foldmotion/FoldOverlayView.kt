package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.max

/**
 * Lumen Line, drawn over the screen being folded away.
 *
 * A bright blade with a soft bloom sweeps right to left. Behind it, a violet
 * afterglow cools to black, so the screen is dark by the time One UI switches.
 * Ported from the Fold Motion Studio preview so the phone matches what was approved.
 */
class FoldOverlayView(context: Context) : View(context) {

    private var p = 0f
    private var alphaAll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint().apply { color = Color.argb(242, 255, 255, 255) }

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

        val x = w * (1.06f - p * 1.16f)

        // Darkness behind the blade, with a violet afterglow right next to it
        if (x < w) {
            fill.shader = LinearGradient(
                x, 0f, x + w * 0.45f, 0f,
                intArrayOf(
                    Color.argb(140, 150, 130, 255),
                    Color.argb(235, 40, 30, 80),
                    Color.argb(255, 6, 5, 12),
                    Color.BLACK
                ),
                floatArrayOf(0f, 0.12f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(max(0f, x), 0f, w + 1f, h, fill)
        }

        // Wide bloom, then the tight glow, then the bright core
        band(canvas, x, w * 0.09f, 0.35f * (1f - p * 0.5f), h)
        band(canvas, x, w * 0.02f, 0.9f, h)
        val half = resources.displayMetrics.density
        canvas.drawRect(x - half, 0f, x + half, h, core)

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun band(canvas: Canvas, x: Float, halfWidth: Float, strength: Float, h: Float) {
        val peak = (255 * FoldMath.clamp(strength)).toInt()
        if (peak <= 0) return
        fill.shader = LinearGradient(
            x - halfWidth, 0f, x + halfWidth, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(peak, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(x - halfWidth, 0f, x + halfWidth, h, fill)
    }
}
