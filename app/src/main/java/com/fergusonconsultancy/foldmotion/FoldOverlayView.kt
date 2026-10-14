package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Two effects in Visconti green, both one continuous motion.
 *
 * Closing, De Stijl One Plane: black rules draw a grid, the crease among them,
 * then a single green plane sweeps through the grid behind a champagne edge,
 * cooling to black. No cells appear on their own, so nothing pops.
 *
 * Opening, Crease Split, played backwards as a reveal: the main screen starts
 * black, a champagne seam lights on the hinge, and two green panels part from
 * it to the outer edges, leaving the screen clean.
 *
 * Zero allocation per frame: every gradient is a unit gradient built once and
 * positioned with a shared matrix.
 *
 * Ported from Fold Motion Studio so the phone matches what was approved.
 */
class FoldOverlayView(context: Context) : View(context) {

    enum class Mode { CLOSE, OPEN }

    var mode = Mode.CLOSE

    private var p = 0f
    private var alphaAll = 0f

    /** Frames this view actually drew since the last reset; compared against frames requested. */
    var drawCount = 0

    private val m = Matrix()

    // Grid, as fractions of the screen. The crease (0.5) is one of the vertical rules.
    private val vRules = floatArrayOf(0.2f, 0.5f, 0.71f, 0.88f)
    private val hRules = floatArrayOf(0.23f, 0.49f, 0.76f)
    private val crease = 1
    private val xs = FloatArray(vRules.size)
    private val ys = FloatArray(hRules.size)
    private var ruleW = 4f

    private fun unitH(colors: IntArray, stops: FloatArray) =
        LinearGradient(0f, 0f, 1f, 0f, colors, stops, Shader.TileMode.CLAMP)

    private val bandShader = unitH(
        intArrayOf(Color.argb(0, 255, 255, 255), Color.WHITE, Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.5f, 1f)
    )
    private val duskShader = unitH(
        intArrayOf(Color.TRANSPARENT, Color.argb(204, 2, 13, 8), Color.BLACK),
        floatArrayOf(0f, 0.5f, 1f)
    )
    // The plane: lit green at its edge, cooling to near black behind it
    private val planeShader = unitH(
        intArrayOf(Color.rgb(52, 108, 74), Color.rgb(30, 76, 50), Color.rgb(10, 38, 24), Color.rgb(2, 7, 4)),
        floatArrayOf(0f, 0.12f, 0.55f, 1f)
    )
    // A panel: dark at its outer edge, lit at its leading edge
    private val panelShader = unitH(
        intArrayOf(Color.rgb(3, 12, 8), Color.rgb(14, 50, 32), Color.rgb(44, 98, 66), Color.rgb(120, 170, 130)),
        floatArrayOf(0f, 0.62f, 0.94f, 1f)
    )
    // A soft shadow cast ahead of a moving edge: black to clear
    private val shadeShader = unitH(intArrayOf(Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 1f))

    private val rulePaint = Paint().apply { color = Color.BLACK }
    private val ruleEdgePaint = Paint().apply { color = Color.argb(89, 233, 223, 200) }
    private val linePaint = Paint().apply { color = Color.rgb(233, 223, 200) }
    private val seamPaint = Paint().apply { color = Color.rgb(255, 246, 230) }
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bandShader }
    private val duskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = duskShader }
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = planeShader }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = panelShader }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = shadeShader }

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
        for (i in vRules.indices) xs[i] = vRules[i] * w
        for (j in hRules.indices) ys[j] = hRules[j] * h
        ruleW = max(4f, w * 0.014f)
    }

    override fun onDraw(canvas: Canvas) {
        if (alphaAll <= 0.001f || p <= 0f) return
        drawCount++
        val w = width.toFloat()
        val h = height.toFloat()
        val layer = if (alphaAll < 1f) canvas.saveLayerAlpha(0f, 0f, w, h, (255 * alphaAll).toInt()) else -1

        // Drawn with the leading edge on the right; mirrored so it moves left to right
        val flip = canvas.save()
        if (MotionConfig.LEFT_TO_RIGHT) canvas.scale(-1f, 1f, w / 2f, 0f)

        if (mode == Mode.CLOSE) drawOnePlane(canvas, w, h) else drawCreaseSplit(canvas, w, h)

        canvas.restoreToCount(flip)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    // region De Stijl, One Plane (closing)

    private fun drawOnePlane(canvas: Canvas, w: Float, h: Float) {
        // One plane sweeps through the grid
        val e = FoldMath.glide(FoldMath.clamp((p - MotionConfig.PLANE_START) / MotionConfig.PLANE_SPAN))
        val front = w - e * w * 1.08f
        if (e > 0f) {
            val tail = w * 0.55f
            m.setScale(tail, 1f); m.postTranslate(front, 0f)
            planeShader.setLocalMatrix(m)
            canvas.drawRect(max(0f, front), 0f, w + 1f, h, planePaint)
            // shadow cast ahead of the plane
            shade(canvas, front, -w * 0.06f, h, 0.4f)
            // champagne leading edge, fading as the plane reaches the far side
            val la = 0.85f * (1f - FoldMath.clamp((p - 0.72f) / 0.1f))
            edgeLine(canvas, front, h, la, w * 0.02f, 0.25f)
        }

        // Rules, drawn over the plane so the grid stays crisp on green
        val half = ruleW / 2f
        for (i in vRules.indices) {
            val q = FoldMath.glide(FoldMath.clamp((p - (vRules.size - 1 - i) * 0.04f) / 0.16f))
            if (q <= 0f) continue
            val x = xs[i]
            val bottom = h * q
            canvas.drawRect(x - half, 0f, x + half, bottom, rulePaint)
            canvas.drawRect(x + half, 0f, x + half + 1f, bottom, ruleEdgePaint)
            if (i == crease) {
                band(canvas, x, 0f, bottom, w * 0.02f, 0.3f * q * (1f - FoldMath.clamp((p - 0.5f) / 0.2f)))
            }
        }
        for (j in hRules.indices) {
            val q = FoldMath.glide(FoldMath.clamp((p - 0.06f - j * 0.04f) / 0.18f))
            if (q <= 0f) continue
            val y = ys[j]
            val start = w - w * q
            canvas.drawRect(start, y - half, w, y + half, rulePaint)
            canvas.drawRect(start, y + half, w, y + half + 1f, ruleEdgePaint)
        }

        // Dusk: the last of the green rolls to black before One UI switches off
        val dk = FoldMath.clamp((p - MotionConfig.DUSK_START) / (1f - MotionConfig.DUSK_START))
        if (dk > 0f) {
            val ee = FoldMath.glide(dk)
            val feather = w * 0.75f
            val f = w - ee * (w + feather)
            m.setScale(feather, 1f); m.postTranslate(f, 0f)
            duskShader.setLocalMatrix(m)
            canvas.drawRect(max(0f, f), 0f, w + 1f, h, duskPaint)
        }
    }

    // endregion

    // region Crease Split (opening, played backwards)

    private fun drawCreaseSplit(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val eA = FoldMath.glide(FoldMath.clamp(p / 0.62f))
        val eB = FoldMath.glide(FoldMath.clamp((p - 0.08f) / 0.62f))
        val xa = w - (w - cx) * eA      // leading panel: from the leading edge to the crease
        val xb = cx * eB                // trailing panel: from the far edge to the crease
        val meet = FoldMath.clamp((p - 0.6f) / 0.1f)
        val sw = w * 0.07f

        shade(canvas, xa, -sw, h, 0.4f * min(1f, eA * 5f) * (1f - meet))
        shade(canvas, xb, sw, h, 0.4f * min(1f, eB * 5f) * (1f - meet))
        panel(canvas, w, xa, h)
        panel(canvas, 0f, xb, h)
        edgeLine(canvas, xa, h, 0.8f * min(1f, eA * 6f) * (1f - meet), w * 0.018f, 0.22f)
        edgeLine(canvas, xb, h, 0.8f * min(1f, eB * 6f) * (1f - meet), w * 0.018f, 0.22f)

        // The seam on the hinge burns where the panels meet
        val burn = meet * (1f - FoldMath.clamp((p - 0.78f) / 0.12f))
        if (burn > 0f) {
            band(canvas, cx, 0f, h, w * 0.035f, 0.45f * burn)
            seamPaint.alpha = (255 * 0.95f * burn).toInt()
            canvas.drawRect(cx - 1f, 0f, cx + 1f, h, seamPaint)
        }

        // Green cools to black; at full progress the screen is solid black
        val cool = FoldMath.smooth(FoldMath.clamp((p - 0.7f) / 0.26f))
        if (cool > 0f) {
            blackPaint.alpha = (255 * cool).toInt()
            canvas.drawRect(0f, 0f, w + 1f, h, blackPaint)
        }
    }

    /** A panel from its outer edge to its leading edge, lit at the leading edge. */
    private fun panel(canvas: Canvas, outer: Float, leading: Float, h: Float) {
        val span = leading - outer
        if (abs(span) < 0.5f) return
        m.setScale(span, 1f); m.postTranslate(outer, 0f)
        panelShader.setLocalMatrix(m)
        canvas.drawRect(min(outer, leading), 0f, max(outer, leading) + 0.5f, h, panelPaint)
    }

    // endregion

    // region Shared pieces

    /** Shadow from an edge, `length` pixels long (negative: towards the left). */
    private fun shade(canvas: Canvas, edge: Float, length: Float, h: Float, alpha: Float) {
        if (alpha <= 0.004f) return
        m.setScale(length, 1f); m.postTranslate(edge, 0f)
        shadeShader.setLocalMatrix(m)
        shadePaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(min(edge, edge + length), 0f, max(edge, edge + length), h, shadePaint)
    }

    /** A 1.5px champagne line with a soft glow either side. */
    private fun edgeLine(canvas: Canvas, x: Float, h: Float, alpha: Float, glowHalf: Float, glow: Float) {
        if (alpha <= 0.004f) return
        linePaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(x - 0.75f, 0f, x + 0.75f, h, linePaint)
        band(canvas, x, 0f, h, glowHalf, glow * alpha)
    }

    private fun band(canvas: Canvas, x: Float, top: Float, bottom: Float, half: Float, alpha: Float) {
        if (alpha <= 0.004f || bottom <= top) return
        m.setScale(half * 2f, 1f); m.postTranslate(x - half, 0f)
        bandShader.setLocalMatrix(m)
        bandPaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(x - half, top, x + half, bottom, bandPaint)
    }

    // endregion
}
