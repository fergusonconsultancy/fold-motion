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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Louvre Signature in Visconti green, drawn over the inner screen as it closes.
 *
 * 1. Anticipation: the screen dims a touch and a glint wakes at the right edge.
 * 2. Blades turn from edge-on to face-on in an overlapping wave, right to left.
 *    Each flashes metallic green with sparkling flake as it catches the light,
 *    casts a shadow ahead of it, and springs slightly past shut.
 * 3. Payoff: a pulse of warm light runs back along the seams.
 * 4. Dusk: a green-to-black gradient rolls in from the right, so the screen is
 *    already black when One UI switches the panel off.
 *
 * Ported from the Fold Motion Studio preview so the phone matches what was approved.
 */
class FoldOverlayView(context: Context) : View(context) {

    private var p = 0f
    private var alphaAll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solid = Paint()
    private val flake = Paint().apply {
        shader = BitmapShader(makeGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // Visconti green: [unlit, lit] for the blade face centre and edge
    private val midUnlit = intArrayOf(12, 46, 30)
    private val midLit = intArrayOf(180, 220, 170)
    private val edgeUnlit = intArrayOf(3, 14, 9)
    private val edgeLit = intArrayOf(56, 106, 70)
    private val bladeEnd = Color.rgb(2, 8, 5)
    private val duskTint = Color.argb(204, 2, 13, 8)
    private val flakeStrength = 0.6f

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

        // The effect is drawn right to left; mirror it so the wave runs left to right
        val flip = canvas.save()
        if (MotionConfig.LEFT_TO_RIGHT) canvas.scale(-1f, 1f, w / 2f, 0f)

        // 1. Anticipation
        solid.color = Color.argb((255 * 0.2f * sm(p / 0.3f)).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, solid)
        val pre = sm(p / 0.05f) * (1f - sm((p - 0.08f) / 0.08f))
        band(canvas, w - w * 0.012f, w * 0.05f, 0.5f * pre, h)

        // 2. Blades
        val n = MotionConfig.BLADES
        val col = w / n
        val a0 = MotionConfig.BLADES_START
        val turnLen = MotionConfig.BLADE_TURN
        val stagger = (MotionConfig.BLADES_END - a0 - turnLen) / (n - 1)
        val edgeW = 0.75f * resources.displayMetrics.density

        for (i in 0 until n) {
            val q = FoldMath.clamp((p - a0 - i * stagger) / turnLen)
            if (q <= 0f) continue
            val idx = n - 1 - i
            val th = easeOutBack(q) * (PI.toFloat() / 2f)
            val s = abs(sin(th))
            val bw = (col + 1f) * s
            val left = idx * col + col / 2f - bw / 2f
            val right = left + bw

            // Shadow cast onto the screen ahead of the blade
            if (q < 1f) {
                val shw = col * 0.5f * (1f - s) + col * 0.1f
                val sa = 0.4f * min(1f, q * 3f) * (1f - s * 0.6f)
                fill.shader = LinearGradient(
                    left - shw, 0f, left, 0f,
                    Color.argb(0, 0, 0, 0), Color.argb((255 * sa).toInt(), 0, 0, 0),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(left - shw, 0f, left, h, fill)
            }

            // Specular flash as the blade passes about 60 degrees
            val deg = th * 180f / PI.toFloat()
            val lit = 0.9f * exp(-((deg - 60f) / 16f).pow(2))
            val mid = mix(midUnlit, midLit, lit)
            val edge = mix(edgeUnlit, edgeLit, lit)
            fill.shader = LinearGradient(
                left, 0f, right, 0f,
                intArrayOf(edge, mid, mid, bladeEnd),
                floatArrayOf(0f, 0.35f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(left, 0f, right, h, fill)

            // Metallic flake sparkling as the blade catches the light
            if (lit > 0.05f) {
                flake.alpha = (255 * flakeStrength * lit).toInt()
                canvas.drawRect(left, 0f, right, h, flake)
            }

            // Vertical sheen so the flash reads as metal
            if (lit > 0.02f) {
                fill.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(
                        Color.argb((255 * 0.25f * lit).toInt(), 255, 255, 255),
                        Color.argb(0, 255, 255, 255),
                        Color.argb((255 * 0.08f * lit).toInt(), 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(left, 0f, right, h, fill)
            }

            // Crisp leading edge
            if (q < 1f) {
                solid.color = Color.argb((255 * 0.75f * (1f - q)).toInt(), 233, 223, 200)
                canvas.drawRect(left, 0f, left + edgeW, h, solid)
            }
        }

        // 3. Payoff: a pulse of light runs back along the seams, left to right
        val f = FoldMath.clamp((p - MotionConfig.PULSE_START) / MotionConfig.PULSE_LENGTH)
        if (f > 0f && f < 1f) {
            val pos = -0.1f + f * 1.2f
            val half = resources.displayMetrics.density / 2f
            for (k in 1 until n) {
                val sx = k * col
                val d = sx / w - pos
                val b = exp(-(d * d) / (2f * 0.05f * 0.05f))
                if (b > 0.01f) {
                    solid.color = Color.argb((255 * 0.9f * b).toInt(), 255, 246, 230)
                    canvas.drawRect(sx - half, 0f, sx + half, h, solid)
                    band(canvas, sx, w * 0.03f, 0.35f * b, h)
                }
            }
        }

        // 4. Dusk: green-to-black gradient rolling in from the right
        val dk = FoldMath.clamp((p - MotionConfig.DUSK_START) / (1f - MotionConfig.DUSK_START))
        if (dk > 0f) {
            val e = FoldMath.glide(dk)
            val feather = w * 0.75f
            val front = w - e * (w + feather)
            fill.shader = LinearGradient(
                front, 0f, front + feather, 0f,
                intArrayOf(Color.argb(0, 0, 0, 0), duskTint, Color.BLACK),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(max(0f, front), 0f, w + 1f, h, fill)
        }

        canvas.restoreToCount(flip)
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

    private fun mix(a: IntArray, b: IntArray, t: Float): Int = Color.rgb(
        (a[0] + (b[0] - a[0]) * t).roundToInt(),
        (a[1] + (b[1] - a[1]) * t).roundToInt(),
        (a[2] + (b[2] - a[2]) * t).roundToInt()
    )

    /** Clamped smoothstep, matching the Studio's smooth(). */
    private fun sm(t: Float): Float = FoldMath.smooth(FoldMath.clamp(t))

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.7f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
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
