package com.fergusonconsultancy.foldmotion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Giulietta's rear lamp, as light on the inner screen (23 Oct 2026). Each lamp is a ring of
 * LED beads at the outer edge with a beaded bar running in towards the crease, the bar thinning
 * and dimming towards its tip as the real one does. The left lamp sits on the left half, the
 * right lamp on the right, mirrored about the hinge like the two lamps about the car's centre.
 *
 * Every bead has its own moment to light (or go out), so a sequence is just a schedule: one
 * running head at a time, the beads behind it settled to a steady glow (design charter: one
 * highlight at a time). Unlit beads hold a faint ember, so something is visible on the first frame.
 *
 * Drawn in screen coordinates. Zero allocation per frame: one unit radial shader per colour,
 * positioned with a shared matrix.
 */
class Lamps(rosso: Boolean) {

    companion object {
        const val RING_BEADS = 30
        const val BAR_BEADS = 15
        const val EMBER = 0.1f
        /** Brightness of a lit bead once its flare has passed. */
        /** Brightness of a lit bead once its flare has passed (24 Oct 2026: brighter, for a more vibrant opening). */
        const val LIT = 0.9f
    }

    private val glowRgb = if (rosso) Color.rgb(246, 20, 34) else Color.rgb(36, 222, 120)
    private val coreRgb = if (rosso) Color.rgb(255, 118, 98) else Color.rgb(200, 255, 214)
    private val bodyRgb = if (rosso) Color.rgb(150, 6, 18) else Color.rgb(10, 96, 52)

    private fun radial(rgb: Int, mid: Int) = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            Color.argb(255, Color.red(rgb), Color.green(rgb), Color.blue(rgb)),
            Color.argb(mid, Color.red(rgb), Color.green(rgb), Color.blue(rgb)),
            Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        ),
        floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP
    )

    private val glowShader = radial(glowRgb, 140)
    private val bodyShader = radial(bodyRgb, 60)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glowShader }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bodyShader }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coreRgb }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(6, 2, 3) }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = glowRgb }
    // The ring's lit tube: one short arc per bead, so the ring reads as one continuous light
    private val tubePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = glowRgb; strokeCap = Paint.Cap.ROUND }
    private val arcBox = android.graphics.RectF()
    private val m = Matrix()

    /** Per-bead brightness, filled by the effect each frame: [side][index], ring first, then bar. */
    val level = Array(2) { FloatArray(RING_BEADS + BAR_BEADS) }

    // Geometry for the current frame
    private var w = 0f
    private var h = 0f
    private var ringR = 0f
    private var ringCx = 0f
    private var barY = 0f

    fun layout(w: Float, h: Float) {
        this.w = w; this.h = h
        ringR = min(w * 0.075f, h * 0.15f)
        ringCx = w * 0.15f
        barY = h * 0.5f - ringR * 0.18f
    }

    /** Where the bar's tip sits, as a distance in from the crease. */
    private val tipGap get() = w * 0.055f

    /** A bead's centre: side 0 is the left lamp, 1 the right; offset shifts the whole lamp sideways. */
    fun beadX(side: Int, i: Int, offset: Float): Float {
        val dir = if (side == 0) 1f else -1f // towards the crease
        val outer = if (side == 0) ringCx else w - ringCx
        return offset + if (i < RING_BEADS) outer + ringR * cos(ringAngle(side, i))
        else {
            val k = (i - RING_BEADS) / (BAR_BEADS - 1f)
            val start = outer + dir * ringR * 0.92f
            val end = w / 2f - dir * tipGap
            start + (end - start) * k
        }
    }

    fun beadY(side: Int, i: Int): Float =
        if (i < RING_BEADS) h * 0.5f + ringR * sin(ringAngle(side, i)) else barY

    /**
     * Ring beads are numbered from where the bar meets the ring, sweeping first over the top
     * and away from the crease, round the outside and back.
     */
    private fun ringAngle(side: Int, i: Int): Float {
        val k = i / RING_BEADS.toFloat()
        val attach = if (side == 0) -0.18f else (Math.PI.toFloat() + 0.18f)
        val sweep = if (side == 0) -1f else 1f
        return attach + sweep * 2f * Math.PI.toFloat() * k
    }

    /** Lights each bead on its own schedule: dark (ember) until [at], a brief flare, then steady. */
    fun ignite(t: Float, at: Float, rise: Float = 0.035f, decay: Float = 0.07f): Float {
        if (t <= at) return EMBER
        val on = FoldMath.smooth(FoldMath.clamp((t - at) / rise))
        val flare = 0.75f * on * exp(-((t - at - rise).coerceAtLeast(0f)) / decay)
        return EMBER + (LIT - EMBER) * on + flare
    }

    /** Goes out at [at]: from the steady lit level down to an ember. */
    fun extinguish(t: Float, at: Float, fall: Float = 0.045f): Float =
        LIT - (LIT - EMBER) * FoldMath.smooth(FoldMath.clamp((t - at) / fall))

    /** Schedules one lamp's ring then bar: ring from r0 to r1, bar (ring end to tip, or tip to ring) from b0 to b1. */
    fun scheduleIgnite(side: Int, t: Float, r0: Float, r1: Float, b0: Float, b1: Float, barFromTip: Boolean) {
        val lv = level[side]
        for (i in 0 until RING_BEADS) lv[i] = ignite(t, r0 + (r1 - r0) * i / RING_BEADS)
        for (j in 0 until BAR_BEADS) {
            val k = j / (BAR_BEADS - 1f)
            lv[RING_BEADS + j] = ignite(t, b0 + (b1 - b0) * (if (barFromTip) 1f - k else k))
        }
    }

    /** Schedules a lamp going out: the ring round from its far side, then the bar from the ring to the tip. */
    fun scheduleOut(side: Int, t: Float, r0: Float, r1: Float, b0: Float, b1: Float) {
        val lv = level[side]
        for (i in 0 until RING_BEADS) lv[i] = extinguish(t, r1 - (r1 - r0) * i / RING_BEADS)
        for (j in 0 until BAR_BEADS) lv[RING_BEADS + j] = extinguish(t, b0 + (b1 - b0) * j / (BAR_BEADS - 1f))
    }

    fun fillAll(v: Float) { for (s in 0..1) level[s].fill(v) }

    /** Multiplies every bead (a bloom or a fade). */
    fun scale(k: Float) { for (s in 0..1) for (i in level[s].indices) level[s][i] *= k }

    /**
     * Draws one lamp. [offset] slides it sideways with its half; [fade] dims it all, ember included.
     * The housing (a dark lens inside a faint rim) is drawn first, then the light on the bodywork,
     * then the beads.
     */
    fun draw(canvas: Canvas, side: Int, offset: Float, fade: Float = 1f) {
        if (fade <= 0.004f) return
        val lv = level[side]
        val outer = (if (side == 0) ringCx else w - ringCx) + offset
        val cy = h * 0.5f
        // Light on the bodywork around the lamp, following how bright the lamp is
        var sum = 0f
        for (v in lv) sum += v
        val mean = sum / lv.size
        val br = ringR * 3.4f
        val bx = (outer + w / 2f + offset) / 2f
        m.setScale(br * 1.5f, br); m.postTranslate(bx, cy)
        bodyShader.setLocalMatrix(m)
        bodyPaint.alpha = (255 * min(1f, 0.55f * mean * fade)).toInt()
        canvas.drawRect(bx - br * 1.5f, cy - br, bx + br * 1.5f, cy + br, bodyPaint)
        // The lens and its rim
        lensPaint.alpha = (255 * fade).toInt()
        canvas.drawCircle(outer, cy, ringR * 0.8f, lensPaint)
        rimPaint.strokeWidth = ringR * 0.05f
        rimPaint.alpha = (255 * 0.22f * fade).toInt()
        canvas.drawCircle(outer, cy, ringR * 0.62f, rimPaint)
        // The tube under the ring's beads, lit bead by bead
        tubePaint.strokeWidth = ringR * 0.15f
        arcBox.set(outer - ringR, cy - ringR, outer + ringR, cy + ringR)
        val span = 360f / RING_BEADS
        for (i in 0 until RING_BEADS) {
            val a = 0.32f * lv[i] * fade
            if (a <= 0.01f) continue
            tubePaint.alpha = (255 * min(1f, a)).toInt()
            canvas.drawArc(arcBox, Math.toDegrees(ringAngle(side, i).toDouble()).toFloat() - span * 0.6f, span * 1.2f, false, tubePaint)
        }
        // Beads
        val ringCore = ringR * 0.07f
        val pitch = (w / 2f - tipGap - (ringCx + ringR * 0.92f)) / (BAR_BEADS - 1f)
        for (i in lv.indices) {
            val x = beadX(side, i, offset)
            val y = beadY(side, i)
            val isBar = i >= RING_BEADS
            val k = if (isBar) (i - RING_BEADS) / (BAR_BEADS - 1f) else 0f
            // The bar thins and dims towards its tip
            val taper = if (isBar) 1f - 0.5f * k.pow(1.4f) else 1f
            val rc = if (isBar) min(pitch * 0.36f, ringR * 0.11f) * taper else ringCore
            bead(canvas, x, y, rc, lv[i] * fade * (if (isBar) 1f - 0.35f * k.pow(1.6f) else 1f))
        }
    }

    fun bead(canvas: Canvas, x: Float, y: Float, rc: Float, lvl: Float) {
        if (lvl <= 0.006f) return
        val rg = rc * 5.2f
        m.setScale(rg, rg); m.postTranslate(x, y)
        glowShader.setLocalMatrix(m)
        glowPaint.alpha = (255 * min(1f, 0.55f * lvl)).toInt()
        canvas.drawRect(x - rg, y - rg, x + rg, y + rg, glowPaint)
        // The core only shows once a bead is properly lit: embers are glow alone
        val ca = FoldMath.clamp((lvl - EMBER) / (LIT - EMBER))
        if (ca > 0.01f) {
            corePaint.alpha = (255 * min(1f, ca * 0.95f)).toInt()
            canvas.drawCircle(x, y, rc, corePaint)
        }
    }

    /** A soft light at (x, y) in the lamp's colour: the bloom, the seam and the last point of light. */
    fun glow(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, alpha: Float) {
        if (alpha <= 0.004f) return
        m.setScale(rx, ry); m.postTranslate(x, y)
        glowShader.setLocalMatrix(m)
        glowPaint.alpha = (255 * min(1f, alpha)).toInt()
        canvas.drawRect(x - rx, y - ry, x + rx, y + ry, glowPaint)
    }

    fun coreDot(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Float) {
        if (alpha <= 0.004f) return
        corePaint.alpha = (255 * min(1f, alpha)).toInt()
        canvas.drawCircle(x, y, r, corePaint)
    }

    /** A fine vertical line of light, e.g. on the crease or a parting lip. */
    fun line(canvas: Canvas, x: Float, top: Float, bottom: Float, width: Float, alpha: Float) {
        if (alpha <= 0.004f) return
        corePaint.alpha = (255 * min(1f, alpha)).toInt()
        canvas.drawRect(x - width / 2f, top, x + width / 2f, bottom, corePaint)
    }

    val ringRadius get() = ringR
}
