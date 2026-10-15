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
        intArrayOf(Color.TRANSPARENT, Color.argb(214, 1, 24, 13), Color.BLACK),
        floatArrayOf(0f, 0.5f, 1f)
    )
    // The plane: lit green at its edge, cooling to near black behind it
    private val planeShader = unitH(
        intArrayOf(Color.rgb(10, 150, 76), Color.rgb(2, 112, 56), Color.rgb(0, 70, 34), Color.rgb(0, 22, 10)),
        floatArrayOf(0f, 0.14f, 0.62f, 1f)
    )
    // Gloss: a clear-coat reflection with a bright core, drifting across the plane
    private val glossShader = unitH(
        intArrayOf(
            Color.argb(0, 235, 255, 242), Color.argb(46, 235, 255, 242), Color.argb(170, 245, 255, 248),
            Color.argb(46, 235, 255, 242), Color.argb(0, 235, 255, 242)
        ),
        floatArrayOf(0f, 0.32f, 0.5f, 0.68f, 1f)
    )
    // Lacquer: the top of the plane catches the light, like paint under a clear coat
    private val lacquerShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(Color.argb(40, 220, 255, 235), Color.argb(0, 220, 255, 235), Color.argb(0, 0, 0, 0), Color.argb(70, 0, 0, 0)),
        floatArrayOf(0f, 0.38f, 0.7f, 1f),
        Shader.TileMode.CLAMP
    )
    // A panel: solid near-black, the green confined to a narrow lit lip at its leading edge
    private val panelShader = unitH(
        intArrayOf(Color.BLACK, Color.rgb(1, 9, 5), Color.rgb(2, 26, 14), Color.rgb(10, 110, 58), Color.rgb(150, 235, 185)),
        floatArrayOf(0f, 0.7f, 0.93f, 0.985f, 1f)
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
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glossShader }
    private val lacquerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lacquerShader }

    fun show(progress: Float, alpha: Float = 1f) {
        p = FoldMath.clamp(progress)
        alphaAll = FoldMath.clamp(alpha)
        invalidate()
    }

    fun clearAll() {
        p = 0f
        alphaAll = 0f
        contentW = 0
        contentH = 0
        invalidate()
    }

    // The screen's real size when the window is deliberately larger than it (opening)
    private var contentW = 0
    private var contentH = 0

    fun setContentSize(w: Int, h: Int) {
        contentW = w
        contentH = h
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
        // Opening: until the main screen's size is known, the whole oversized window is black
        if (mode == Mode.OPEN && (contentW <= 0 || p >= 0.999f)) {
            canvas.drawColor(Color.BLACK)
            if (contentW <= 0) return
        }
        val w = (if (mode == Mode.OPEN && contentW > 0) contentW else width).toFloat()
        val h = (if (mode == Mode.OPEN && contentH > 0) contentH else height).toFloat()
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
            val left = max(0f, front)
            // Lacquer across the whole plane
            m.setScale(1f, h)
            lacquerShader.setLocalMatrix(m)
            canvas.drawRect(left, 0f, w + 1f, h, lacquerPaint)
            // Gloss: travels slower than the plane, so the reflection seems to slide over a curved surface
            val gw = w * 0.16f
            val gx = front + w * (0.06f + 0.22f * e)
            val gA = 1f - FoldMath.clamp((p - 0.7f) / 0.15f)
            if (gA > 0f && gx + gw > left) {
                m.setScale(gw, 1f); m.postTranslate(gx, 0f)
                glossShader.setLocalMatrix(m)
                glossPaint.alpha = (255 * gA).toInt()
                canvas.drawRect(max(left, gx), 0f, min(w + 1f, gx + gw), h, glossPaint)
            }
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

    // region Crease Split (opening reveal)

    /**
     * Runs on reveal progress r = 1 - p: black, then the two green panels fade
     * up with deep shadowed outer edges, then the crease flashes, then the
     * panels part outwards to show the screen. At p = 1 the screen is black.
     */
    private fun drawCreaseSplit(canvas: Canvas, w: Float, h: Float) {
        val r = 1f - p
        val cx = w / 2f
        // Panels part from the crease, the leading one first
        val eA = FoldMath.glide(FoldMath.clamp((r - 0.28f) / 0.62f))
        val eB = FoldMath.glide(FoldMath.clamp((r - 0.36f) / 0.64f))
        val xa = cx + (w - cx) * eA
        val xb = cx - cx * eB
        val sw = w * 0.045f

        panel(canvas, w, xa, h)
        panel(canvas, 0f, xb, h)
        // Deep shadows cast onto the screen as it is uncovered
        shade(canvas, xa, -sw, h, 0.85f * min(1f, eA * 8f) * (1f - eA))
        shade(canvas, xb, sw, h, 0.85f * min(1f, eB * 8f) * (1f - eB))
        edgeLine(canvas, xa, h, 0.85f * min(1f, eA * 8f) * (1f - eA), w * 0.018f, 0.25f)
        edgeLine(canvas, xb, h, 0.85f * min(1f, eB * 8f) * (1f - eB), w * 0.018f, 0.25f)

        // From black: the panels fade up
        val black = 1f - FoldMath.smooth(FoldMath.clamp(r / 0.22f))
        if (black > 0f) {
            blackPaint.alpha = (255 * black).toInt()
            canvas.drawRect(0f, 0f, w + 1f, h, blackPaint)
        }

        // The flash on the crease, just before the panels part
        val flash = FoldMath.smooth(FoldMath.clamp((r - 0.12f) / 0.12f)) *
            (1f - FoldMath.smooth(FoldMath.clamp((r - 0.30f) / 0.2f)))
        if (flash > 0f) {
            band(canvas, cx, 0f, h, w * 0.22f, 0.22f * flash)
            band(canvas, cx, 0f, h, w * 0.05f, 0.75f * flash)
            seamPaint.alpha = (255 * flash).toInt()
            canvas.drawRect(cx - 1.5f, 0f, cx + 1.5f, h, seamPaint)
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
