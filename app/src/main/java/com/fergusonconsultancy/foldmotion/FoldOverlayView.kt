package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
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
 * 1. Anticipation: the screen dims a touch and a glint wakes at the leading edge.
 * 2. Blades turn from edge-on to face-on in an overlapping wave. Each flashes
 *    metallic green with sparkling flake as it catches the light, casts a
 *    shadow ahead of it, and springs slightly past shut.
 * 3. Payoff: a pulse of warm light runs back along the seams.
 * 4. Dusk: a green-to-black gradient rolls in, so the screen is already black
 *    when One UI switches the panel off.
 *
 * Zero allocation per frame: every gradient is built once, across a unit
 * width, and repositioned each frame with a shared matrix. The blade's colour
 * shift is done by laying a lit gradient over an unlit one at the lit amount,
 * which composites to exactly the same colour as mixing them. This keeps the
 * garbage collector out of the animation, the usual cause of a dropped frame.
 *
 * Ported from the Fold Motion Studio preview so the phone matches what was approved.
 */
class FoldOverlayView(context: Context) : View(context) {

    private var p = 0f
    private var alphaAll = 0f

    private val density = resources.displayMetrics.density
    private val m = Matrix()

    // Visconti green (approximation; no official colour value was available)
    private val bladeEnd = Color.rgb(2, 8, 5)

    // Gradients across a unit width (0 to 1), placed per draw with setLocalMatrix
    private val shadowShader = unitGradient(intArrayOf(Color.TRANSPARENT, Color.BLACK), null)
    private val unlitShader = unitGradient(
        intArrayOf(Color.rgb(3, 14, 9), Color.rgb(12, 46, 30), Color.rgb(12, 46, 30), bladeEnd),
        floatArrayOf(0f, 0.35f, 0.55f, 1f)
    )
    private val litShader = unitGradient(
        intArrayOf(Color.rgb(56, 106, 70), Color.rgb(180, 220, 170), Color.rgb(180, 220, 170), bladeEnd),
        floatArrayOf(0f, 0.35f, 0.55f, 1f)
    )
    private val bandShader = unitGradient(
        intArrayOf(Color.argb(0, 255, 255, 255), Color.WHITE, Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.5f, 1f)
    )
    private val duskShader = unitGradient(
        intArrayOf(Color.TRANSPARENT, Color.argb(204, 2, 13, 8), Color.BLACK),
        floatArrayOf(0f, 0.5f, 1f)
    )
    // Vertical sheen across a unit height; scaled to the view height in onSizeChanged
    private val sheenShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(Color.argb(64, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(20, 255, 255, 255)),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = shadowShader }
    private val unlitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = unlitShader }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = litShader }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bandShader }
    private val duskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = duskShader }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sheenShader }
    private val flakePaint = Paint().apply {
        shader = BitmapShader(makeGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val solid = Paint()

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        m.setScale(1f, h.toFloat())
        sheenShader.setLocalMatrix(m)
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
        val edgeW = 0.75f * density

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
                place(shadowShader, left - shw, shw)
                shadowPaint.alpha = (255 * sa).toInt()
                canvas.drawRect(left - shw, 0f, left, h, shadowPaint)
            }

            if (bw < 0.5f) continue

            // Blade face: unlit base, with the lit gradient laid over at the lit amount
            val deg = th * 180f / PI.toFloat()
            val lit = 0.9f * exp(-((deg - 60f) / 16f).pow(2))
            place(unlitShader, left, bw)
            canvas.drawRect(left, 0f, right, h, unlitPaint)
            if (lit > 0.004f) {
                place(litShader, left, bw)
                litPaint.alpha = (255 * lit).toInt()
                canvas.drawRect(left, 0f, right, h, litPaint)
            }

            // Metallic flake sparkling as the blade catches the light
            if (lit > 0.05f) {
                flakePaint.alpha = (255 * flakeStrength * lit).toInt()
                canvas.drawRect(left, 0f, right, h, flakePaint)
            }

            // Vertical sheen so the flash reads as metal
            if (lit > 0.02f) {
                sheenPaint.alpha = (255 * lit).toInt()
                canvas.drawRect(left, 0f, right, h, sheenPaint)
            }

            // Crisp leading edge
            if (q < 1f) {
                solid.color = Color.argb((255 * 0.75f * (1f - q)).toInt(), 233, 223, 200)
                canvas.drawRect(left, 0f, left + edgeW, h, solid)
            }
        }

        // 3. Payoff: a pulse of light runs back along the seams
        val f = FoldMath.clamp((p - MotionConfig.PULSE_START) / MotionConfig.PULSE_LENGTH)
        if (f > 0f && f < 1f) {
            val pos = -0.1f + f * 1.2f
            val half = density / 2f
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

        // 4. Dusk: green-to-black gradient rolling in
        val dk = FoldMath.clamp((p - MotionConfig.DUSK_START) / (1f - MotionConfig.DUSK_START))
        if (dk > 0f) {
            val e = FoldMath.glide(dk)
            val feather = w * 0.75f
            val front = w - e * (w + feather)
            place(duskShader, front, feather)
            canvas.drawRect(max(0f, front), 0f, w + 1f, h, duskPaint)
        }

        canvas.restoreToCount(flip)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /** Positions a unit-width gradient to span [x, x + width]. */
    private fun place(shader: Shader, x: Float, width: Float) {
        m.setScale(max(width, 0.001f), 1f)
        m.postTranslate(x, 0f)
        shader.setLocalMatrix(m)
    }

    private fun band(canvas: Canvas, x: Float, halfWidth: Float, strength: Float, h: Float) {
        val peak = (255 * FoldMath.clamp(strength)).toInt()
        if (peak <= 0) return
        place(bandShader, x - halfWidth, halfWidth * 2f)
        bandPaint.alpha = peak
        canvas.drawRect(x - halfWidth, 0f, x + halfWidth, h, bandPaint)
    }

    /** Clamped smoothstep, matching the Studio's smooth(). */
    private fun sm(t: Float): Float = FoldMath.smooth(FoldMath.clamp(t))

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.7f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }

    private fun unitGradient(colors: IntArray, stops: FloatArray?) =
        LinearGradient(0f, 0f, 1f, 0f, colors, stops, Shader.TileMode.CLAMP)

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
