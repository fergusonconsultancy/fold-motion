package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.min
import kotlin.math.pow

/**
 * Louvre, drawn over the inner screen as it closes.
 *
 * Vertical blades snap shut one after another from right to left, each
 * springing open from its centre line with a lit edge that fades as it
 * closes. The screen settles to black once the last blade shuts. Ported
 * from the Fold Motion Studio preview so the phone matches what was approved.
 */
class FoldOverlayView(context: Context) : View(context) {

    private var p = 0f
    private var alphaAll = 0f

    private val blade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint()
    private val shut = Paint().apply { color = Color.BLACK }

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

        val n = MotionConfig.BLADES
        val col = w / n
        val stagger = MotionConfig.BLADE_STAGGER
        val span = 1f + stagger * (n - 1)
        val edgeWidth = 1.5f * resources.displayMetrics.density / 2f

        for (i in 0 until n) {
            // i = 0 is the rightmost blade, so the wave travels right to left
            val q = FoldMath.clamp(p * span - i * stagger)
            if (q <= 0f) continue
            val idx = n - 1 - i
            val k = min(1.08f, easeOutBack(q))
            val bw = (col + 1f) * min(1f, k)
            val cx = idx * col + col / 2f
            val left = cx - bw / 2f
            val right = cx + bw / 2f

            blade.shader = LinearGradient(
                left, 0f, right, 0f,
                intArrayOf(Color.rgb(5, 5, 10), Color.rgb(30, 27, 44), Color.rgb(5, 5, 10)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(left, 0f, right, h, blade)

            // Lit edges, fading as the blade completes
            if (q < 1f) {
                edge.color = Color.argb((255 * 0.5f * (1f - q)).toInt(), 233, 223, 200)
                canvas.drawRect(left, 0f, left + edgeWidth, h, edge)
                canvas.drawRect(right - edgeWidth, 0f, right, h, edge)
            }
        }

        // Last 10%: the seams between blades melt into solid black
        val s = FoldMath.smooth(FoldMath.clamp((p - 0.9f) / 0.1f))
        if (s > 0f) {
            shut.alpha = (255 * s).toInt()
            canvas.drawRect(0f, 0f, w, h, shut)
        }

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.7f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }
}
