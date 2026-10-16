package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Two effects in Visconti green, both one continuous motion, built in layers
 * the way car paint is: a coloured base, metallic flake, a clear coat that
 * catches the light, and real shadows between surfaces.
 *
 * Closing, De Stijl One Plane: black rules draw a grid, raised off the screen
 * with their own shadows, then a single green plane sweeps through the grid.
 * Its colour shifts warm in the light and cool in the shade; a reflection
 * slides over it and sparks the flake as it passes.
 *
 * Opening, Crease Split reveal: black, then two near-black panels with lit
 * green lips meet on the hinge, light spilling inside them; the crease flashes
 * white with a green bloom; the panels part, casting deep shadows on the screen.
 *
 * Zero allocation per frame: every gradient and the flake texture are built
 * once and positioned with a shared matrix.
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

    // The screen's real size when the window is deliberately larger than it (opening)
    private var contentW = 0
    private var contentH = 0

    private fun unitH(colors: IntArray, stops: FloatArray) =
        LinearGradient(0f, 0f, 1f, 0f, colors, stops, Shader.TileMode.CLAMP)

    private fun unitV(colors: IntArray, stops: FloatArray) =
        LinearGradient(0f, 0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)

    private val bandShader = unitH(
        intArrayOf(Color.argb(0, 255, 255, 255), Color.WHITE, Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.5f, 1f)
    )
    private val greenBandShader = unitH(
        intArrayOf(Color.argb(0, 40, 200, 110), Color.rgb(40, 200, 110), Color.argb(0, 40, 200, 110)),
        floatArrayOf(0f, 0.5f, 1f)
    )
    private val duskShader = unitH(
        intArrayOf(Color.TRANSPARENT, Color.argb(214, 1, 24, 13), Color.BLACK),
        floatArrayOf(0f, 0.5f, 1f)
    )
    // The plane: warm, lit green at its edge, cooling through deep green to a blue-black shade
    private val planeShader = unitH(
        intArrayOf(Color.rgb(44, 172, 92), Color.rgb(6, 112, 58), Color.rgb(0, 66, 44), Color.rgb(0, 16, 13)),
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
    // A second, wide and faint reflection behind the first, for depth in the clear coat
    private val haloShader = unitH(
        intArrayOf(Color.argb(0, 200, 255, 220), Color.argb(34, 200, 255, 220), Color.argb(0, 200, 255, 220)),
        floatArrayOf(0f, 0.5f, 1f)
    )
    // Lacquer: the top catches the light and the foot falls into shade
    private val lacquerShader = unitV(
        intArrayOf(Color.argb(46, 220, 255, 235), Color.argb(0, 220, 255, 235), Color.argb(0, 0, 0, 0), Color.argb(90, 0, 0, 0)),
        floatArrayOf(0f, 0.36f, 0.66f, 1f)
    )
    // A panel: near-black, with a narrow lit lip at its leading edge
    private val panelShader = unitH(
        intArrayOf(
            Color.BLACK, Color.rgb(1, 9, 6), Color.rgb(0, 30, 20), Color.rgb(6, 96, 52),
            Color.rgb(40, 170, 96), Color.rgb(190, 245, 205)
        ),
        floatArrayOf(0f, 0.62f, 0.9f, 0.975f, 0.993f, 1f)
    )
    // Shadows: black to clear, horizontally and vertically
    private val shadeShader = unitH(intArrayOf(Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 1f))
    private val shadeVShader = unitV(intArrayOf(Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 1f))

    // Metallic flake: sparse bright specks, generated once and tiled
    private val flakeShader = BitmapShader(makeFlake(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

    private val rulePaint = Paint().apply { color = Color.BLACK }
    private val ruleEdgePaint = Paint().apply { color = Color.argb(89, 233, 223, 200) }
    private val linePaint = Paint().apply { color = Color.rgb(233, 223, 200) }
    private val seamPaint = Paint().apply { color = Color.rgb(255, 246, 230) }
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bandShader }
    private val greenBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = greenBandShader }
    private val duskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = duskShader }
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = planeShader }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = panelShader }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = shadeShader }
    private val shadeVPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = shadeVShader }

    // ---- Gaudí pieces
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private fun unitRadial(rgb: Int) = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(255, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb))),
        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
    )
    private val waistShader = unitRadial(Color.rgb(200, 255, 220))
    private val tipShader = unitRadial(Color.rgb(255, 226, 170))
    private val waistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = waistShader }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = tipShader }

    // Ruled Surface: 44 threads; colour by how far each faces the viewer
    private val threads = 44
    private val threadColor = IntArray(threads) { i ->
        val th = Math.PI * (i + 0.5) / threads
        val lit = 0.35f + 0.65f * kotlin.math.sin(th).toFloat()
        Color.rgb((6 + (70 - 6) * lit).toInt(), (80 + (200 - 80) * lit).toInt(), (44 + (120 - 44) * lit).toInt())
    }
    private val threadFront = FloatArray(threads) { i -> kotlin.math.sin(Math.PI * (i + 0.5) / threads).toFloat() }
    private val threadTheta = FloatArray(threads) { i -> (Math.PI * (i + 0.5) / threads).toFloat() }

    // Nave of Light: ordered branching, grown once with a fixed seed (matches Studio).
    // Offsets are in half-widths from each limb's root on the crease; fy is the root's height fraction.
    private val nFy = ArrayList<Float>(); private val nX1 = ArrayList<Float>(); private val nY1 = ArrayList<Float>()
    private val nX2 = ArrayList<Float>(); private val nY2 = ArrayList<Float>(); private val nDepth = ArrayList<Int>()
    private val nT0 = ArrayList<Float>(); private val nT1 = ArrayList<Float>()
    private var seed = 11L
    private fun rnd(): Double { seed = (seed * 16807L) % 2147483647L; return seed / 2147483647.0 }
    private fun grow(fy: Float, x: Double, y: Double, ang: Double, len: Double, depth: Int, t0: Double) {
        val x2 = x + kotlin.math.cos(ang) * len
        val y2 = y + kotlin.math.sin(ang) * len
        val dur = 0.15
        nFy.add(fy); nX1.add(x.toFloat()); nY1.add(y.toFloat()); nX2.add(x2.toFloat()); nY2.add(y2.toFloat())
        nDepth.add(depth); nT0.add(t0.toFloat()); nT1.add((t0 + dur).toFloat())
        if (depth >= 3) return
        val spread = 0.42
        val l = len * (0.7 + rnd() * 0.06)
        grow(fy, x2, y2, ang - spread, l, depth + 1, t0 + dur * 0.85)
        grow(fy, x2, y2, ang + spread, l, depth + 1, t0 + dur * 0.85)
    }
    init {
        for (fy in floatArrayOf(0.2f, 0.5f, 0.8f)) {
            val lean = (fy - 0.5) * 1.2
            val t0 = 0.1 + kotlin.math.abs(fy - 0.5) * 0.15
            grow(fy, 0.0, 0.0, -lean, 0.36, 0, t0)
            grow(fy, 0.0, 0.0, Math.PI + lean, 0.36, 0, t0)
        }
    }
    private val depthColor = IntArray(4) { d ->
        val t = d / 3f
        Color.rgb((60 + (244 - 60) * t).toInt(), (210 + (184 - 210) * t).toInt(), (120 + (92 - 120) * t).toInt())
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glossShader }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = haloShader }
    private val lacquerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lacquerShader }
    private val flakePaint = Paint().apply { shader = flakeShader }

    private fun makeFlake(): Bitmap {
        val n = 160
        val px = IntArray(n * n)
        var seed = 7L
        for (i in px.indices) {
            seed = (seed * 16807L) % 2147483647L
            val v = seed / 2147483647f
            // About 3% of pixels are specks, with varied brightness
            px[i] = if (v > 0.97f) Color.argb((140 + (v - 0.97f) / 0.03f * 115).toInt(), 225, 255, 235) else 0
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

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

        // Each effect is drawn on progress p (0 clear, 1 black): closing runs it forwards,
        // the opening reveal runs it backwards
        val effect = if (mode == Mode.CLOSE) MotionConfig.CLOSING_EFFECT else MotionConfig.OPENING_EFFECT
        when (effect) {
            MotionConfig.Effect.ONE_PLANE -> drawOnePlane(canvas, w, h)
            MotionConfig.Effect.CREASE_SPLIT -> drawCreaseSplit(canvas, w, h)
            MotionConfig.Effect.RULED_SURFACE -> drawRuledSurface(canvas, w, h)
            MotionConfig.Effect.NAVE_OF_LIGHT -> drawNaveOfLight(canvas, w, h)
        }

        canvas.restoreToCount(flip)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    // region De Stijl, One Plane (closing)

    private fun drawOnePlane(canvas: Canvas, w: Float, h: Float) {
        val e = FoldMath.glide(FoldMath.clamp((p - MotionConfig.PLANE_START) / MotionConfig.PLANE_SPAN))
        val front = w - e * w * 1.08f
        val gA = 1f - FoldMath.clamp((p - 0.7f) / 0.15f)
        if (e > 0f) {
            val left = max(0f, front)
            // Base colour
            m.setScale(w * 0.55f, 1f); m.postTranslate(front, 0f)
            planeShader.setLocalMatrix(m)
            canvas.drawRect(left, 0f, w + 1f, h, planePaint)
            // Metallic flake, faint everywhere on the plane
            flakePaint.alpha = (255 * 0.10f * gA).toInt()
            if (flakePaint.alpha > 0) canvas.drawRect(left, 0f, w + 1f, h, flakePaint)
            // Clear coat: lacquer top to bottom
            m.setScale(1f, h)
            lacquerShader.setLocalMatrix(m)
            canvas.drawRect(left, 0f, w + 1f, h, lacquerPaint)
            // Clear coat: a wide faint halo and a bright reflection, slower than the plane
            val gw = w * 0.16f
            val gx = front + w * (0.06f + 0.22f * e)
            if (gA > 0f) {
                val hw = w * 0.5f
                val hx = gx + gw / 2f - hw / 2f
                m.setScale(hw, 1f); m.postTranslate(hx, 0f)
                haloShader.setLocalMatrix(m)
                haloPaint.alpha = (255 * gA).toInt()
                canvas.drawRect(max(left, hx), 0f, min(w + 1f, hx + hw), h, haloPaint)
                if (gx + gw > left) {
                    m.setScale(gw, 1f); m.postTranslate(gx, 0f)
                    glossShader.setLocalMatrix(m)
                    glossPaint.alpha = (255 * gA).toInt()
                    canvas.drawRect(max(left, gx), 0f, min(w + 1f, gx + gw), h, glossPaint)
                    // The flake sparks where the reflection passes
                    val c = canvas.save()
                    canvas.clipRect(max(left, gx + gw * 0.3f), 0f, min(w + 1f, gx + gw * 0.7f), h)
                    flakePaint.alpha = (255 * 0.55f * gA).toInt()
                    canvas.drawRect(left, 0f, w + 1f, h, flakePaint)
                    canvas.restoreToCount(c)
                }
            }
            // Shadow cast ahead of the plane, and its champagne edge
            shade(canvas, front, -w * 0.06f, 0f, h, 0.45f)
            val la = 0.85f * (1f - FoldMath.clamp((p - 0.72f) / 0.1f))
            edgeLine(canvas, front, h, la, w * 0.02f, 0.25f)
        }

        // Rules sit above the plane: each casts a short shadow, then the rule itself
        val half = max(4f, w * 0.014f) / 2f
        val drop = max(3f, w * 0.012f)
        for (i in vRules.indices) {
            val q = FoldMath.glide(FoldMath.clamp((p - (vRules.size - 1 - i) * 0.04f) / 0.16f))
            if (q <= 0f) continue
            val x = vRules[i] * w
            val bottom = h * q
            shade(canvas, x - half, -drop, 0f, bottom, 0.5f)
            canvas.drawRect(x - half, 0f, x + half, bottom, rulePaint)
            canvas.drawRect(x + half, 0f, x + half + 1f, bottom, ruleEdgePaint)
            if (i == crease) {
                band(canvas, x, 0f, bottom, w * 0.02f, 0.3f * q * (1f - FoldMath.clamp((p - 0.5f) / 0.2f)))
            }
        }
        for (j in hRules.indices) {
            val q = FoldMath.glide(FoldMath.clamp((p - 0.06f - j * 0.04f) / 0.18f))
            if (q <= 0f) continue
            val y = hRules[j] * h
            val start = w - w * q
            shadeDown(canvas, start, w, y + half, drop, 0.5f)
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

    // region Gaudí: Ruled Surface (closing)

    /** Straight threads twist into a hyperbolic curve, collapse onto the crease, which flashes; then black. */
    private fun drawRuledSurface(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val show = FoldMath.smooth(FoldMath.clamp(p / 0.15f))
        val twist = FoldMath.glide(FoldMath.clamp((p - 0.05f) / 0.55f)) * Math.PI.toFloat()
        val r = (w / 2f) * (1f - FoldMath.glide(FoldMath.clamp((p - 0.45f) / 0.3f)))
        val under = 0.9f * FoldMath.smooth(FoldMath.clamp(p / 0.55f))
        if (under > 0f) { blackPaint.alpha = (255 * under).toInt(); canvas.drawRect(0f, 0f, w + 1f, h, blackPaint) }
        val fade = 1f - FoldMath.smooth(FoldMath.clamp((p - 0.74f) / 0.1f))
        val a = show * fade
        if (a > 0.004f) {
            strokePaint.strokeWidth = max(1.2f, w * 0.0028f)
            for (i in 0 until threads) {
                val th = threadTheta[i]
                val xt = cx + r * kotlin.math.cos(th)
                val xb = cx + r * kotlin.math.cos(th + twist)
                strokePaint.color = threadColor[i]
                strokePaint.alpha = (255 * a * (0.35f + 0.65f * threadFront[i])).toInt()
                canvas.drawLine(xt, 0f, xb, h, strokePaint)
            }
            // The waist of the curve catches the light
            if (twist > 0.4f) {
                val wy = h / 2f
                val rad = max(8f, abs(r * kotlin.math.cos(twist / 2f)) + w * 0.06f)
                m.setScale(rad, rad); m.postTranslate(cx, wy)
                waistShader.setLocalMatrix(m)
                waistPaint.alpha = (255 * 0.25f * a).toInt()
                canvas.drawRect(cx - rad, wy - rad, cx + rad, wy + rad, waistPaint)
            }
        }
        flashOnCrease(canvas, cx, w, h, FoldMath.smooth(FoldMath.clamp((p - 0.66f) / 0.08f)) *
            (1f - FoldMath.smooth(FoldMath.clamp((p - 0.8f) / 0.12f))))
        val end = FoldMath.smooth(FoldMath.clamp((p - 0.8f) / 0.15f))
        if (end > 0f) { blackPaint.alpha = (255 * end).toInt(); canvas.drawRect(0f, 0f, w + 1f, h, blackPaint) }
    }

    private fun flashOnCrease(canvas: Canvas, cx: Float, w: Float, h: Float, fl: Float) {
        if (fl <= 0f) return
        greenBand(canvas, cx, 0f, h, w * 0.3f, 0.5f * fl, 0f, w + 1f)
        band(canvas, cx, 0f, h, w * 0.05f, 0.75f * fl)
        seamPaint.alpha = (255 * fl).toInt()
        canvas.drawRect(cx - 1.5f, 0f, cx + 1.5f, h, seamPaint)
    }

    // endregion

    // region Gaudí: Nave of Light (opening reveal)

    /**
     * Runs on reveal progress r = 1 - p. The crease ignites from the middle and the
     * ribs branch out over solid black, green at the hinge warming to amber. Only
     * once the ribs are fully grown does the darkness lift outwards from the
     * crease, so almost nothing of the screen shows until the effect is done.
     */
    private fun drawNaveOfLight(canvas: Canvas, w: Float, h: Float) {
        val r = 1f - p
        val cx = w / 2f
        val hw = w / 2f
        // Darkness lifts outwards from the crease, feathered at its edge
        val spread = FoldMath.glide(FoldMath.clamp((r - MotionConfig.NAVE_LIFT_START) / (0.94f - MotionConfig.NAVE_LIFT_START))) * (hw + w * 0.25f)
        val feather = w * 0.25f
        blackPaint.alpha = 255
        if (spread <= 0f) {
            canvas.drawRect(0f, 0f, w + 1f, h, blackPaint)
        } else {
            val l = cx - spread
            val rr = cx + spread
            if (l > 0f) canvas.drawRect(0f, 0f, l, h, blackPaint)
            if (rr < w) canvas.drawRect(rr, 0f, w + 1f, h, blackPaint)
            shade(canvas, l, feather, 0f, h, 1f)
            shade(canvas, rr, -feather, 0f, h, 1f)
        }
        // The crease ignites, growing from the middle up and down
        val ig = FoldMath.glide(FoldMath.clamp((r - 0.02f) / 0.12f))
        val glow = 1f - FoldMath.smooth(FoldMath.clamp((r - 0.62f) / 0.28f))
        if (ig > 0f && glow > 0f) {
            val top = h / 2f * (1f - ig)
            val bot = h / 2f * (1f + ig)
            greenBand(canvas, cx, top, bot, w * 0.2f, 0.45f * glow, 0f, w + 1f)
            seamPaint.alpha = (255 * 0.95f * glow).toInt()
            canvas.drawRect(cx - 1.5f, top, cx + 1.5f, bot, seamPaint)
        }
        // Ribs: each drawn partially by its own timing, a soft glow under a fine line
        val bf = 1f - FoldMath.smooth(FoldMath.clamp((r - 0.74f) / 0.22f))
        if (bf <= 0f) return
        for (i in nFy.indices) {
            val k = FoldMath.clamp((r - nT0[i]) / (nT1[i] - nT0[i]))
            if (k <= 0f) continue
            val x1 = cx + nX1[i] * hw
            val y1 = nFy[i] * h + nY1[i] * hw
            val xe = x1 + (cx + nX2[i] * hw - x1) * k
            val ye = y1 + (nFy[i] * h + nY2[i] * hw - y1) * k
            val d = nDepth[i]
            val lw = max(1f, w * 0.007f * (1f - d * 0.22f))
            strokePaint.color = depthColor[d]
            strokePaint.strokeWidth = lw * 4f
            strokePaint.alpha = (255 * 0.22f * bf).toInt()
            canvas.drawLine(x1, y1, xe, ye, strokePaint)
            strokePaint.strokeWidth = lw
            strokePaint.alpha = (255 * 0.95f * bf).toInt()
            canvas.drawLine(x1, y1, xe, ye, strokePaint)
            if (d == 3 && k < 1f) {
                val rad = w * 0.02f
                m.setScale(rad, rad); m.postTranslate(xe, ye)
                tipShader.setLocalMatrix(m)
                tipPaint.alpha = (255 * 0.8f * bf).toInt()
                canvas.drawRect(xe - rad, ye - rad, xe + rad, ye + rad, tipPaint)
            }
        }
    }

    // endregion

    // region Crease Split (opening reveal)

    /**
     * Runs on reveal progress r = 1 - p: black, then the two panels fade up
     * with their lit lips meeting on the crease, then the crease flashes, then
     * the panels part outwards. At p = 1 the screen is black.
     */
    private fun drawCreaseSplit(canvas: Canvas, w: Float, h: Float) {
        val r = 1f - p
        val cx = w / 2f
        val eA = FoldMath.glide(FoldMath.clamp((r - 0.28f) / 0.62f))
        val eB = FoldMath.glide(FoldMath.clamp((r - 0.36f) / 0.64f))
        val xa = cx + (w - cx) * eA
        val xb = cx - cx * eB
        val sw = w * 0.045f

        // Panels: base, lacquer, light spilling inside from the lip, flake along the lip
        panel(canvas, w, xa, h)
        panel(canvas, 0f, xb, h)
        m.setScale(1f, h)
        lacquerShader.setLocalMatrix(m)
        if (xa < w) canvas.drawRect(xa, 0f, w + 1f, h, lacquerPaint)
        if (xb > 0f) canvas.drawRect(0f, 0f, xb, h, lacquerPaint)
        val spill = 0.32f
        if (xa < w) greenBand(canvas, xa + w * 0.03f, 0f, h, w * 0.05f, spill * (1f - eA), xa, w + 1f)
        if (xb > 0f) greenBand(canvas, xb - w * 0.03f, 0f, h, w * 0.05f, spill * (1f - eB), 0f, xb)
        flakeStrip(canvas, xa, xa + w * 0.06f, h, 0.35f * (1f - eA))
        flakeStrip(canvas, xb - w * 0.06f, xb, h, 0.35f * (1f - eB))

        // Deep shadows cast onto the screen as it is uncovered
        shade(canvas, xa, -sw, 0f, h, 0.85f * min(1f, eA * 8f) * (1f - eA))
        shade(canvas, xb, sw, 0f, h, 0.85f * min(1f, eB * 8f) * (1f - eB))
        edgeLine(canvas, xa, h, 0.85f * min(1f, eA * 8f) * (1f - eA), w * 0.018f, 0.25f)
        edgeLine(canvas, xb, h, 0.85f * min(1f, eB * 8f) * (1f - eB), w * 0.018f, 0.25f)

        // From black: the panels fade up
        val black = 1f - FoldMath.smooth(FoldMath.clamp(r / 0.22f))
        if (black > 0f) {
            blackPaint.alpha = (255 * black).toInt()
            canvas.drawRect(0f, 0f, w + 1f, h, blackPaint)
        }

        // The flash on the crease: a green bloom, a white glow and a hot core
        val flash = FoldMath.smooth(FoldMath.clamp((r - 0.12f) / 0.12f)) *
            (1f - FoldMath.smooth(FoldMath.clamp((r - 0.30f) / 0.2f)))
        if (flash > 0f) {
            greenBand(canvas, cx, 0f, h, w * 0.3f, 0.5f * flash, 0f, w + 1f)
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

    private fun flakeStrip(canvas: Canvas, x0: Float, x1: Float, h: Float, alpha: Float) {
        if (alpha <= 0.004f || x1 <= x0) return
        flakePaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(x0, 0f, x1, h, flakePaint)
    }

    // endregion

    // region Shared pieces

    /** Shadow from a vertical edge, `length` pixels long (negative: towards the left). */
    private fun shade(canvas: Canvas, edge: Float, length: Float, top: Float, bottom: Float, alpha: Float) {
        if (alpha <= 0.004f || bottom <= top) return
        m.setScale(length, 1f); m.postTranslate(edge, 0f)
        shadeShader.setLocalMatrix(m)
        shadePaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(min(edge, edge + length), top, max(edge, edge + length), bottom, shadePaint)
    }

    /** Shadow falling downwards from a horizontal edge at y. */
    private fun shadeDown(canvas: Canvas, x0: Float, x1: Float, y: Float, length: Float, alpha: Float) {
        if (alpha <= 0.004f || x1 <= x0) return
        m.setScale(1f, length); m.postTranslate(0f, y)
        shadeVShader.setLocalMatrix(m)
        shadeVPaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(x0, y, x1, y + length, shadeVPaint)
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

    /** A soft green glow centred on x, limited to [clipL, clipR]. */
    private fun greenBand(canvas: Canvas, x: Float, top: Float, bottom: Float, half: Float, alpha: Float, clipL: Float, clipR: Float) {
        if (alpha <= 0.004f) return
        val l = max(x - half, clipL)
        val rr = min(x + half, clipR)
        if (rr <= l) return
        m.setScale(half * 2f, 1f); m.postTranslate(x - half, 0f)
        greenBandShader.setLocalMatrix(m)
        greenBandPaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(l, top, rr, bottom, greenBandPaint)
    }

    // endregion
}
