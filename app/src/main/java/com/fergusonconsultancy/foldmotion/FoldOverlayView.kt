package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lumen Line with a smoked glass finish, drawn over the screen being folded away.
 *
 * A bright blade with a soft bloom sweeps right to left. Behind it the screen
 * becomes dark smoked glass: content faintly visible through a violet-to-black
 * tint, two soft reflections, fine grain, and the afterglow caught next to the
 * blade. Ported from the Fold Motion Studio preview so the phone matches what
 * was approved.
 */
class FoldOverlayView(context: Context) : View(context) {

    private var p = 0f
    private var alphaAll = 0f
    private var glow = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint().apply { color = Color.argb(242, 255, 255, 255) }
    private val grain = Paint().apply {
        shader = BitmapShader(makeGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        alpha = (255 * 0.55f).roundToInt()
    }

    /** glow scales the blade's bloom and core, for the resting afterglow on opening. */
    fun show(progress: Float, alpha: Float = 1f, glow: Float = 1f) {
        p = FoldMath.clamp(progress)
        alphaAll = FoldMath.clamp(alpha)
        this.glow = FoldMath.clamp(glow)
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
        if (x < w) drawGlass(canvas, x, w, h)

        // Wide bloom, then the tight glow, then the bright core
        band(canvas, x, w * 0.09f, 0.35f * (1f - p * 0.5f) * glow, h)
        band(canvas, x, w * 0.02f, 0.9f * glow, h)
        val half = resources.displayMetrics.density
        core.alpha = (242 * glow).toInt()
        canvas.drawRect(x - half, 0f, x + half, h, core)

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawGlass(canvas: Canvas, x: Float, w: Float, h: Float) {
        val x0 = max(0f, x)
        val save = canvas.save()
        canvas.clipRect(x0, 0f, w + 1f, h)

        // Smoked tint: dark, with the content faintly visible through it
        fill.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.argb(209, 34, 28, 58), Color.argb(222, 14, 12, 24), Color.argb(230, 7, 7, 12)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(x0, 0f, w + 1f, h, fill)

        // Violet afterglow caught in the glass next to the blade
        fill.shader = LinearGradient(
            x, 0f, x + w * 0.28f, 0f,
            Color.argb(107, 150, 130, 255), Color.argb(0, 150, 130, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(x0, 0f, w + 1f, h, fill)

        // Two soft reflections across the surface, drifting slightly with the sweep
        val diag = hypot(w, h)
        val inner = canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(-31.5f)
        val sx = -diag * 0.15f + p * diag * 0.06f
        reflection(canvas, sx, diag * 0.14f, 0.07f, diag)
        reflection(canvas, sx + diag * 0.2f, diag * 0.04f, 0.05f, diag)
        canvas.restoreToCount(inner)

        // Fine grain so it reads as a material
        canvas.drawRect(x0, 0f, w + 1f, h, grain)

        canvas.restoreToCount(save)
    }

    private fun reflection(canvas: Canvas, cx: Float, bw: Float, strength: Float, diag: Float) {
        val a = (255 * strength).roundToInt()
        fill.shader = LinearGradient(
            cx - bw, 0f, cx + bw, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(a, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - bw, -diag, cx + bw, diag, fill)
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

    /** 96x96 tile of faint white specks, the same recipe as the Studio preview. */
    private fun makeGrain(): Bitmap {
        val size = 96
        val pixels = IntArray(size * size)
        var seed = 11L
        for (i in pixels.indices) {
            seed = (seed * 16807L) % 2147483647L
            val v = seed / 2147483647f
            pixels[i] = Color.argb((v * v * 60f).roundToInt(), 255, 255, 255)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
