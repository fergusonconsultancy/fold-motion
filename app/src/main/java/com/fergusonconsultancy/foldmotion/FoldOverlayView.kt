package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.max

/**
 * De Stijl Rules in Visconti green, drawn over the inner screen as it closes.
 *
 * 1. Rules: black bars draw a grid across the screen. Verticals drop from the
 *    top, horizontals draw in from the leading edge. The crease is one of the
 *    verticals and carries a soft light, because the hinge is the structural
 *    line of the device.
 * 2. Cells: the grid's cells fill in sequence from the leading edge. Most go
 *    black; two go Visconti green and one champagne.
 * 3. Dusk: a green-to-black gradient rolls in, so the screen is already black
 *    when One UI switches the panel off.
 *
 * Zero allocation per frame: every gradient is built once and positioned with
 * a shared matrix; the grid geometry is computed once per screen size.
 *
 * Ported from the Fold Motion Studio preview so the phone matches what was approved.
 */
class FoldOverlayView(context: Context) : View(context) {

    private var p = 0f
    private var alphaAll = 0f

    /** Frames this view actually drew since the last reset; compared against frames requested. */
    var drawCount = 0

    private val m = Matrix()

    // Grid, as fractions of the screen. The crease (0.5) is one of the vertical rules.
    private val vRules = floatArrayOf(0.2f, 0.5f, 0.71f, 0.88f)
    private val hRules = floatArrayOf(0.23f, 0.49f, 0.76f)
    private val crease = 1
    private val cols = vRules.size + 1
    private val rows = hRules.size + 1

    // Cell edges in pixels, filled in onSizeChanged
    private val xs = FloatArray(cols + 1)
    private val ys = FloatArray(rows + 1)

    // Fill order: from the leading edge (last column) inwards, then top to bottom
    private val order = IntArray(cols * rows).also { o ->
        var k = 0
        for (c in cols - 1 downTo 0) for (r in 0 until rows) o[k++] = c * rows + r
    }

    // Accent cells (column, row) and their unit-height vertical gradients
    private val accentCell = IntArray(cols * rows) { -1 }
    private val accentShaders = arrayOf(
        unitVertical(Color.rgb(44, 98, 66), Color.rgb(12, 46, 30)),      // Visconti green
        unitVertical(Color.rgb(34, 82, 56), Color.rgb(10, 40, 26)),      // Visconti green, deeper
        unitVertical(Color.rgb(228, 216, 190), Color.rgb(196, 182, 152)) // champagne
    )
    init {
        accentCell[3 * rows + 1] = 0
        accentCell[1 * rows + 2] = 1
        accentCell[0 * rows + 0] = 2
    }

    private val bandShader = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(Color.argb(0, 255, 255, 255), Color.WHITE, Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )
    private val duskShader = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(Color.TRANSPARENT, Color.argb(204, 2, 13, 8), Color.BLACK),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )

    private val cellPaint = Paint().apply { color = Color.rgb(3, 5, 4) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulePaint = Paint().apply { color = Color.BLACK }
    private val edgePaint = Paint().apply { color = Color.argb(89, 233, 223, 200) }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bandShader }
    private val duskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = duskShader }

    private var ruleW = 4f

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
        xs[0] = 0f
        for (i in vRules.indices) xs[i + 1] = vRules[i] * w
        xs[cols] = w.toFloat()
        ys[0] = 0f
        for (j in hRules.indices) ys[j + 1] = hRules[j] * h
        ys[rows] = h.toFloat()
        ruleW = max(4f, w * 0.014f)
    }

    override fun onDraw(canvas: Canvas) {
        if (alphaAll <= 0.001f || p <= 0f) return
        drawCount++
        val w = width.toFloat()
        val h = height.toFloat()
        val layer = if (alphaAll < 1f) canvas.saveLayerAlpha(0f, 0f, w, h, (255 * alphaAll).toInt()) else -1

        // Drawn right to left; mirrored so the composition builds left to right
        val flip = canvas.save()
        if (MotionConfig.LEFT_TO_RIGHT) canvas.scale(-1f, 1f, w / 2f, 0f)

        // Cells fill in sequence from the leading edge
        val n = order.size
        val dur = MotionConfig.CELL_FILL
        val stagger = (MotionConfig.CELLS_END - MotionConfig.CELLS_START - dur) / (n - 1)
        for (ord in 0 until n) {
            val q = FoldMath.glide(FoldMath.clamp((p - MotionConfig.CELLS_START - ord * stagger) / dur))
            if (q <= 0f) continue
            val cell = order[ord]
            val c = cell / rows
            val r = cell % rows
            val x0 = xs[c]; val x1 = xs[c + 1]; val y0 = ys[r]; val y1 = ys[r + 1]
            val left = x1 - (x1 - x0) * q
            val accent = accentCell[cell]
            if (accent >= 0) {
                val shader = accentShaders[accent]
                m.setScale(1f, y1 - y0)
                m.postTranslate(0f, y0)
                shader.setLocalMatrix(m)
                accentPaint.shader = shader
                canvas.drawRect(left, y0, x1, y1, accentPaint)
            } else {
                canvas.drawRect(left, y0, x1, y1, cellPaint)
            }
        }

        // Vertical rules drop from the top, the leading one first
        val half = ruleW / 2f
        for (i in vRules.indices) {
            val q = FoldMath.glide(FoldMath.clamp((p - (vRules.size - 1 - i) * 0.05f) / 0.18f))
            if (q <= 0f) continue
            val x = xs[i + 1]
            val bottom = h * q
            canvas.drawRect(x - half, 0f, x + half, bottom, rulePaint)
            canvas.drawRect(x + half, 0f, x + half + 1f, bottom, edgePaint)
            if (i == crease) {
                // The hinge carries a soft light while the grid forms
                val strength = 0.35f * q * (1f - FoldMath.clamp((p - 0.6f) / 0.2f))
                val peak = (255 * strength).toInt()
                if (peak > 0) {
                    val hw = w * 0.02f
                    m.setScale(hw * 2f, 1f)
                    m.postTranslate(x - hw, 0f)
                    bandShader.setLocalMatrix(m)
                    bandPaint.alpha = peak
                    canvas.drawRect(x - hw, 0f, x + hw, bottom, bandPaint)
                }
            }
        }

        // Horizontal rules draw in from the leading edge
        for (j in hRules.indices) {
            val q = FoldMath.glide(FoldMath.clamp((p - 0.08f - j * 0.05f) / 0.2f))
            if (q <= 0f) continue
            val y = ys[j + 1]
            val start = w - w * q
            canvas.drawRect(start, y - half, w, y + half, rulePaint)
            canvas.drawRect(start, y + half, w, y + half + 1f, edgePaint)
        }

        // Dusk: green-to-black gradient rolling in
        val dk = FoldMath.clamp((p - MotionConfig.DUSK_START) / (1f - MotionConfig.DUSK_START))
        if (dk > 0f) {
            val e = FoldMath.glide(dk)
            val feather = w * 0.75f
            val front = w - e * (w + feather)
            m.setScale(feather, 1f)
            m.postTranslate(front, 0f)
            duskShader.setLocalMatrix(m)
            canvas.drawRect(max(0f, front), 0f, w + 1f, h, duskPaint)
        }

        canvas.restoreToCount(flip)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun unitVertical(top: Int, bottom: Int) =
        LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP)
}
