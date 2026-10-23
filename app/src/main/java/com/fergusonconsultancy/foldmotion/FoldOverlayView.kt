package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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

    // ---- Lacquer Panel: deep paint, dark at the outer edge, lit at the leading lip
    private val lacquerPanelShader = unitH(
        intArrayOf(
            Color.rgb(0, 18, 11), Color.rgb(2, 84, 44), Color.rgb(10, 112, 60),
            Color.rgb(150, 132, 92), Color.rgb(228, 214, 184), Color.rgb(255, 247, 228)
        ),
        // Visconti body, turning to a champagne-gold lip at the leading edge
        floatArrayOf(0f, 0.6f, 0.88f, 0.935f, 0.975f, 1f)
    )
    private val lacquerPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lacquerPanelShader }
    // Champagne clear-coat reflection for the Lacquer Panel
    private val champagneGlossShader = unitH(
        intArrayOf(
            Color.argb(0, 240, 226, 196), Color.argb(56, 240, 226, 196), Color.argb(199, 255, 246, 226),
            Color.argb(56, 240, 226, 196), Color.argb(0, 240, 226, 196)
        ),
        floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f)
    )
    private val champagneGlossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = champagneGlossShader }

    // ---- Hinge Shade
    private val hingeFarShader = unitH(intArrayOf(Color.argb(235, 4, 18, 11), Color.BLACK), floatArrayOf(0f, 1f))
    private val hingeFarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = hingeFarShader }
    private val hingeFaceShader = unitH(intArrayOf(Color.argb(38, 0, 0, 0), Color.argb(217, 0, 0, 0)), floatArrayOf(0f, 1f))
    private val hingeFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = hingeFaceShader }
    private val hingeTintPaint = Paint().apply { color = Color.rgb(12, 46, 30) }
    private val specShader = unitH(
        intArrayOf(Color.argb(0, 180, 220, 170), Color.rgb(180, 220, 170), Color.argb(0, 180, 220, 170)),
        floatArrayOf(0f, 0.5f, 1f)
    )
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = specShader }

    // ---- Grand closing surround: Visconti paint, lit towards the crease
    private val surroundShader = unitH(
        intArrayOf(Color.rgb(0, 14, 9), Color.rgb(4, 84, 44), Color.rgb(14, 132, 70), Color.rgb(4, 84, 44), Color.rgb(0, 14, 9)),
        floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f)
    )
    private val surroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = surroundShader }
    private val glintShader = unitRadial(Color.rgb(255, 246, 228))
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glintShader }

    // ---- Frame of Light
    private val framePath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    // Motes: direction (+1 follows the leading half), speed, start height, rise, size (as Studio)
    private val moteDir = floatArrayOf(1f, 1f, 1f, 1f, -1f, -1f)
    private val moteVx = floatArrayOf(1.9f, 1.4f, 2.3f, 1.2f, 1.1f, 1.6f)
    private val moteY0 = floatArrayOf(0.38f, 0.52f, 0.61f, 0.29f, 0.47f, 0.7f)
    private val moteVy = floatArrayOf(-0.5f, -0.3f, -0.65f, -0.2f, -0.4f, -0.55f)
    private val moteSize = floatArrayOf(0.005f, 0.0065f, 0.004f, 0.0045f, 0.005f, 0.004f)
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 248, 232) }

    // ---- Lit Glass: unit radial lights, positioned and stretched with the shared matrix
    private fun radial(colors: IntArray, stops: FloatArray) = RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)
    private val glassInnerShader = radial(
        intArrayOf(Color.argb(158, 22, 128, 72), Color.argb(107, 10, 86, 46), Color.argb(0, 0, 24, 12)), floatArrayOf(0f, 0.45f, 1f))
    private val glassInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glassInnerShader }
    private val glassFrontShader = radial(
        intArrayOf(Color.argb(255, 214, 240, 214), Color.argb(128, 60, 190, 112), Color.argb(0, 40, 170, 96)), floatArrayOf(0f, 0.35f, 1f))
    private val glassFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glassFrontShader }
    private val glassCornerShader = radial(
        intArrayOf(Color.argb(255, 226, 242, 224), Color.argb(93, 40, 190, 108), Color.argb(0, 40, 190, 108)), floatArrayOf(0f, 0.35f, 1f))
    private val glassCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glassCornerShader }
    private val sparkShader = radial(intArrayOf(Color.argb(255, 200, 240, 210), Color.argb(0, 200, 240, 210)), floatArrayOf(0f, 1f))
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sparkShader }
    private val sparkCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 246, 232) }
    private val glassDarkShader = unitH(intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(240, 0, 0, 0)), floatArrayOf(0f, 1f))
    private val glassDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glassDarkShader }
    private val glassLinePaint = Paint().apply { color = Color.rgb(226, 240, 222) }

    /**
     * The window in the bezel: straight sides (the halves' lips), rounded corners, and its
     * top and bottom drawn as shallow arches, so the bezel is thickest at the corners
     * (charter rule 3: curved for the living).
     */
    private fun archWindow(path: Path, xb: Float, top: Float, xa: Float, h: Float, rad: Float, bow: Float) {
        val mid = (xa + xb) / 2f
        path.moveTo(xb, top + rad)
        path.quadTo(xb, top, xb + rad, top)
        path.quadTo(mid, top - 2f * bow, xa - rad, top)
        path.quadTo(xa, top, xa, top + rad)
        path.lineTo(xa, h - top - rad)
        path.quadTo(xa, h - top, xa - rad, h - top)
        path.quadTo(mid, h - top + 2f * bow, xb + rad, h - top)
        path.quadTo(xb, h - top, xb, h - top - rad)
        path.close()
    }

    // Time of day (charter): the effect's light, never the content beneath. Null by day.
    private var todPaint: Paint? = null

    /** Sets the light for the hour: night warmer and about a third dimmer, evening between. */
    fun setTimeOfDay(hour: Int) {
        val (bright, sepia) = when (hour) {
            in 7..17 -> 1f to 0f
            in 18..21 -> 0.88f to 0.12f
            else -> 0.68f to 0.22f
        }
        if (sepia == 0f) { todPaint = null; return }
        val k = 1f - sepia
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            k + sepia * 0.393f, sepia * 0.769f, sepia * 0.189f, 0f, 0f,
            sepia * 0.349f, k + sepia * 0.686f, sepia * 0.168f, 0f, 0f,
            sepia * 0.272f, sepia * 0.534f, k + sepia * 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(android.graphics.ColorMatrix().apply { setScale(bright, bright, bright, 1f) })
        todPaint = Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) }
    }
    private val frameStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val frameGlowShader = unitRadial(Color.rgb(40, 200, 110))
    private val frameGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = frameGlowShader }

    // ---- Arch Aperture
    private val archPath = Path()
    private val maskPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val archStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

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
        val tod = todPaint?.let { canvas.saveLayer(0f, 0f, w, h, it) } ?: -1

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
            MotionConfig.Effect.LACQUER_PANEL -> drawLacquerPanel(canvas, w, h)
            MotionConfig.Effect.ARCH_APERTURE -> drawArchAperture(canvas, w, h)
            MotionConfig.Effect.HINGE_SHADE -> drawHingeShade(canvas, w, h)
            MotionConfig.Effect.SIGNATURE -> drawSignature(canvas, w, h)
            MotionConfig.Effect.FRAME_OF_LIGHT -> drawFrameOfLight(canvas, w, h)
            MotionConfig.Effect.LIT_GLASS -> drawLitGlass(canvas, w, h)
            MotionConfig.Effect.LAMP_GLASS -> drawLitGlass(canvas, w, h, lamp = true)
            MotionConfig.Effect.FANALE -> {
                // Lamps are laid out in screen coordinates, so undo the mirror
                val c = canvas.save()
                if (MotionConfig.LEFT_TO_RIGHT) canvas.scale(-1f, 1f, w / 2f, 0f)
                if (mode == Mode.CLOSE) drawFanaleClose(canvas, w, h) else drawFanaleOpen(canvas, w, h)
                canvas.restoreToCount(c)
            }
        }

        canvas.restoreToCount(flip)
        if (tod >= 0) canvas.restoreToCount(tod)
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

    // region Signature (opening)

    /**
     * Built as a premium opening on reveal progress r = 1 - p. A champagne seam ignites
     * on the crease; Visconti lacquer floods outwards from the hinge to the edges, its
     * flood fronts lit in champagne; a champagne clear-coat reflection sweeps left to
     * right; then the champagne-edged halves part to show the screen.
     */
    private fun drawSignature(canvas: Canvas, w: Float, h: Float) {
        signatureBase(canvas, w, h)
        drawSignatureParting(canvas, w, h)
    }

    /**
     * Signature's first beats, shared with Frame of Light: the lacquer floods from the
     * hinge with lit fronts, and the seam ignites. partA and partB override how far the
     * halves have parted (NaN: Signature's own parting, on real time).
     */
    private fun signatureBase(canvas: Canvas, w: Float, h: Float, partA: Float = Float.NaN, partB: Float = Float.NaN, rtl: Boolean = false) {
        val r = 1f - p
        val cx = w / 2f
        val flood = FoldMath.glide(FoldMath.clamp((r - 0.05f) / 0.24f))
        val spread = flood * (cx + 2f)
        if (flood > 0f) {
            val c = canvas.save()
            if (flood < 1f) canvas.clipRect(cx - spread, 0f, cx + spread, h)
            drawLacquerPanel(canvas, w, h, fromBlack = false, partOnTime = true, partA = partA, partB = partB, rtl = rtl)
            canvas.restoreToCount(c)
        }
        // Black beyond the flood
        if (flood < 1f) {
            blackPaint.alpha = 255
            canvas.drawRect(0f, 0f, max(0f, cx - spread), h, blackPaint)
            canvas.drawRect(min(w + 1f, cx + spread), 0f, w + 1f, h, blackPaint)
            // The flood fronts, lit in champagne
            val fa = (1f - flood) * FoldMath.smooth(FoldMath.clamp(flood / 0.06f))
            if (fa > 0.004f) {
                for (x in floatArrayOf(cx - spread, cx + spread)) {
                    seamPaint.alpha = (255 * fa).toInt()
                    canvas.drawRect(x - 1.5f, 0f, x + 1.5f, h, seamPaint)
                    band(canvas, x, 0f, h, w * 0.05f, 0.5f * fa)
                    greenBand(canvas, x, 0f, h, w * 0.12f, 0.3f * fa, 0f, w + 1f)
                }
            }
        }
        // The seam ignites on the crease, then gives way to the paint
        // Bigger ignition: a wide green bloom, a broad white glow and a hot champagne core,
        // held a beat before the paint takes over
        val seam = FoldMath.smooth(FoldMath.clamp(r / 0.05f)) * (1f - FoldMath.smooth(FoldMath.clamp((r - 0.22f) / 0.16f)))
        if (seam > 0f) {
            greenBand(canvas, cx, 0f, h, w * 0.38f, 0.55f * seam, 0f, w + 1f)
            band(canvas, cx, 0f, h, w * 0.18f, 0.3f * seam)
            band(canvas, cx, 0f, h, w * 0.07f, 0.95f * seam)
            seamPaint.alpha = (255 * seam).toInt()
            canvas.drawRect(cx - 2.5f, 0f, cx + 2.5f, h, seamPaint)
        }
    }

    private fun drawSignatureParting(canvas: Canvas, w: Float, h: Float) {
        val r = 1f - p
        val cx = w / 2f
        // From here, real time: the reveal's ease squeezes its middle, which made the
        // cling about 45ms and the light a 120ms flash (18 Oct 2026, night).
        val t = FoldMath.unglide(r)
        // The field between the parting halves: a faint green glow that thins as the gap widens
        val eA = FoldMath.magnetOut(FoldMath.clamp((t - 0.5f) / 0.45f))
        val eB = FoldMath.magnetOut(FoldMath.clamp((t - 0.55f) / 0.42f))
        run {
            val field = 0.22f * FoldMath.smooth(FoldMath.clamp((t - 0.5f) / 0.05f)) * (1f - eA)
            if (field > 0.004f) greenBand(canvas, cx, 0f, h, w * (0.05f + 0.25f * eA), field, 0f, w + 1f)
        }
        // Break-away: a glow, not a flash. Light gathers slowly through the cling (about
        // 190ms), crests as the hold gives, and ebbs over about 260ms. Colour carries it:
        // a wide Visconti bloom and a champagne glow, with only a narrow, soft core.
        val breakT = MotionConfig.SIGNATURE_BREAK_T
        val charge = FoldMath.smooth(FoldMath.clamp((t - 0.40f) / (breakT - 0.40f)))
        val glow = charge * (1f - FoldMath.smooth(FoldMath.clamp((t - breakT) / 0.26f)))
        if (glow > 0.004f) {
            greenBand(canvas, cx, 0f, h, w * 0.5f, 0.4f * glow, 0f, w + 1f)
            band(canvas, cx, 0f, h, w * 0.22f, 0.28f * glow)
            band(canvas, cx, 0f, h, w * 0.035f, 0.5f * glow)
        }
        // The champagne lips carry the light outwards, fading as they travel
        val lip = charge * (1f - FoldMath.smooth(FoldMath.clamp((t - breakT) / 0.32f)))
        if (lip > 0.004f) {
            for (x in floatArrayOf(cx + (w - cx) * eA, cx - cx * eB)) {
                band(canvas, x, 0f, h, w * 0.07f, 0.6f * lip)
                seamPaint.alpha = (200 * lip).toInt()
                canvas.drawRect(x - 1.5f, 0f, x + 1.5f, h, seamPaint)
            }
        }
    }

    // endregion

    // region Lit Glass (opening)

    /**
     * Frame of Light, lit glass (22 Oct 2026, chosen in Studio). Honest material: no paint,
     * no reflection stripes, no flake. The screen is dark glass and Visconti is the colour of
     * the light inside it. The light wakes at the right edge, where the cover was pulled, and
     * crosses to the hinge; a fine seam of light; the halves part on the magnetic curves and
     * stop a bezel short of the edges; the bezel is dark glass with a fine line of light at the
     * window's edge. It seats with a click, holds, then slides off every edge.
     */
    private val rosso = Lamps(rosso = true)
    private val visconti = Lamps(rosso = false)

    /**
     * Lamp Glass's light (23 Oct 2026): the Giulietta's rear lamps lit in sequence inside the
     * dark glass, in place of Lit Glass's soft waking column. The right ring sweeps round where
     * the fingers left the cover, its bar runs in to the crease, the light crosses, the left bar
     * runs out and the left ring closes just as the halves' hold gives. Returns the head's
     * position on screen, which the unlit glass follows.
     */
    private fun lampGlassLights(t: Float, w: Float, h: Float): Lamps {
        val l = if (MotionConfig.LAMP_GLASS_ROSSO) rosso else visconti
        l.layout(w, h)
        l.scheduleIgnite(1, t, 0.0f, 0.1f, 0.08f, 0.22f, barFromTip = false)
        l.scheduleIgnite(0, t, 0.33f, 0.43f, 0.23f, 0.34f, barFromTip = true)
        return l
    }

    private fun lampHead(t: Float, w: Float): Float {
        val ring = w * 0.15f
        val tip = w * 0.055f
        return when {
            t < 0.1f -> w
            t < 0.22f -> (w - ring) + ((w / 2f + tip) - (w - ring)) * ((t - 0.1f) / 0.12f)
            t < 0.34f -> (w / 2f - tip) + (ring - (w / 2f - tip)) * ((t - 0.22f) / 0.12f)
            else -> -w * 0.5f
        }
    }

    /** Draws the lamps riding their halves; the canvas here is mirrored, so it is undone first. */
    private fun drawGlassLamps(canvas: Canvas, l: Lamps, w: Float, leftLip: Float, rightLip: Float) {
        val c = canvas.save()
        if (MotionConfig.LEFT_TO_RIGHT) canvas.scale(-1f, 1f, w / 2f, 0f)
        val cx = w / 2f
        l.draw(canvas, 0, leftLip - cx)
        l.draw(canvas, 1, rightLip - cx)
        canvas.restoreToCount(c)
    }

    private fun drawLitGlass(canvas: Canvas, w: Float, h: Float, lamp: Boolean = false) {
        val r = 1f - p
        val cx = w / 2f
        val t = FoldMath.unglide(r)
        val hold = MotionConfig.FRAME_HOLD_T
        val bezel = w * 0.042f
        val b = bezel * (1f - FoldMath.glide(FoldMath.clamp((t - hold) / (1f - hold))))
        val mA = FoldMath.magnetOut(FoldMath.clamp((t - 0.42f) / 0.34f))
        val mB = FoldMath.magnetOut(FoldMath.clamp((t - 0.46f) / 0.32f))
        val xa = cx + (w - b - cx) * mA
        val xb = cx - (cx - b) * mB
        // Drawn mirrored: canvas left to right is right to left on screen. On the first frame
        // the light is already waking at the right edge, where the fingers left the cover.
        val lamps = if (lamp) lampGlassLights(t, w, h) else null
        // With lamps, the glass wakes behind the running head (canvas x is mirrored screen x)
        val sx = if (lamp) w - lampHead(t, w) + w * 0.12f else w * 0.3f + FoldMath.smooth(FoldMath.clamp(t / 0.4f)) * (w * 1.15f)
        if (xa - xb <= 1.5f) {
            glassMaterial(canvas, w, h, t, sx, front = !lamp)
            lamps?.let { drawGlassLamps(canvas, it, w, w / 2f, w / 2f) }
        } else {
            val top = b * FoldMath.smooth(FoldMath.clamp((xa - xb) / (w * 0.25f)))
            val rad = min(w * 0.05f, min((xa - xb) / 2f, (h - 2f * top) / 2f))
            framePath.reset()
            framePath.addRect(0f, 0f, w + 1f, h, Path.Direction.CW)
            archWindow(framePath, xb, top, xa, h, rad, top * 0.7f)
            val c0 = canvas.save()
            canvas.clipPath(framePath)
            glassMaterial(canvas, w, h, t, sx, front = !lamp)
            // Screen left lip is the canvas's xa, screen right lip its xb
            lamps?.let { drawGlassLamps(canvas, it, w, w - xa, w - xb) }
            canvas.restoreToCount(c0)
            // The window's edge: a fine line of light, glowing softly onto the content
            val lit = FoldMath.smooth(FoldMath.clamp((min(mA, mB) - 0.15f) / 0.5f))
            val fade = min(1f, b / bezel * 1.5f)
            framePath.reset()
            archWindow(framePath, xb, top, xa, h, rad, top * 0.7f)
            strokeGlass(canvas, Color.rgb(40, 190, 108), 0.1f * lit * fade, w * 0.05f)
            strokeGlass(canvas, Color.rgb(40, 200, 112), 0.32f * lit * fade, w * 0.016f)
            strokeGlass(canvas, Color.rgb(230, 242, 226), (0.55f + 0.4f * lit) * fade, max(1.5f, w * 0.003f))
        }
        // Break-away: a soft breath of light at the crease as the hold gives
        val bt = MotionConfig.FRAME_BREAK_T
        val charge = FoldMath.smooth(FoldMath.clamp((t - 0.3f) / (bt - 0.3f)))
        val glow = charge * (1f - FoldMath.smooth(FoldMath.clamp((t - bt) / 0.22f)))
        if (glow > 0.004f) {
            greenBand(canvas, cx, 0f, h, w * 0.36f, 0.24f * glow, 0f, w + 1f)
            ellipse(canvas, glassFrontShader, glassFrontPaint, cx, h * 0.48f, w * 0.14f, h * 0.58f, 0.28f * glow)
        }
        // Sparks of light spill from the seam, carrying the swing outwards on a falling arc
        val life = 0.3f
        val u = (t - bt) / life
        if (u > 0f && u < 1f) {
            val dt = u * life
            val a = Math.pow(kotlin.math.sin(Math.PI * min(1f, u * 2.2f)), 0.7).toFloat() * (1f - u)
            for (i in moteDir.indices) {
                val x = cx + moteDir[i] * w * moteVx[i] * life * (1f - (1f - u) * (1f - u)) * 0.5f
                val y = h * (moteY0[i] + moteVy[i] * dt + 0.45f * dt * dt)
                val rr = w * moteSize[i] * 0.8f
                ellipse(canvas, sparkShader, sparkPaint, x, y, rr * 4f, rr * 4f, 0.3f * a)
                sparkCorePaint.alpha = (255 * 0.9f * a).toInt()
                canvas.drawCircle(x, y, rr, sparkCorePaint)
            }
        }
        // The frame closes: light gathers softly at each corner as the halves seat
        val cg = FoldMath.smooth(FoldMath.clamp((min(mA, mB) - 0.9f) / 0.1f)) * (1f - FoldMath.smooth(FoldMath.clamp((t - 0.8f) / 0.1f)))
        if (cg > 0.004f) {
            for (i in 0..3) {
                val x = if (i % 2 == 0) b else w - b
                val y = if (i < 2) b else h - b
                ellipse(canvas, glassCornerShader, glassCornerPaint, x, y, w * 0.1f, w * 0.1f, 0.55f * cg)
            }
        }
    }

    /** Dark glass with Visconti light inside it, unlit beyond the waking front at sx. */
    private fun glassMaterial(canvas: Canvas, w: Float, h: Float, t: Float, sx: Float, front: Boolean = true) {
        val cx = w / 2f
        blackPaint.alpha = 255
        canvas.drawRect(0f, 0f, w + 1f, h, blackPaint)
        // The light inside the glass: deepest at the edges, warmest at the heart
        ellipse(canvas, glassInnerShader, glassInnerPaint, cx, h * 0.5f, w * 0.8f, h * 0.78f, 1f, cover = true, w = w, h = h)
        // The glass not yet reached by the light stays dark
        val fe = w * 0.42f
        m.setScale(fe, 1f); m.postTranslate(sx - fe, 0f)
        glassDarkShader.setLocalMatrix(m)
        if (sx > fe * 0f) canvas.drawRect(max(0f, sx - fe), 0f, min(w + 1f, sx), h, glassDarkPaint)
        if (sx < w + 1f) { blackPaint.alpha = 240; canvas.drawRect(max(0f, sx), 0f, w + 1f, h, blackPaint); blackPaint.alpha = 255 }
        // The waking front: a soft column of light, strongest at the middle of the screen
        val fa = 1f - FoldMath.smooth(FoldMath.clamp((t - 0.34f) / 0.1f))
        if (front && fa > 0.004f) ellipse(canvas, glassFrontShader, glassFrontPaint, sx - fe * 0.55f, h * 0.48f, w * 0.13f, h * 0.56f, 0.4f * fa)
        // The seam: one fine line of light as the front crosses the crease
        val seam = FoldMath.smooth(FoldMath.clamp((t - 0.18f) / 0.06f)) * (1f - FoldMath.smooth(FoldMath.clamp((t - 0.4f) / 0.08f)))
        if (seam > 0.004f) {
            greenBand(canvas, cx, 0f, h, w * 0.07f, 0.2f * seam, 0f, w + 1f)
            glassLinePaint.alpha = (255 * 0.7f * seam).toInt()
            canvas.drawRect(cx - 0.75f, 0f, cx + 0.75f, h, glassLinePaint)
        }
    }

    /** A soft elliptical light from a unit radial shader, centred on (x, y). */
    private fun ellipse(
        canvas: Canvas, shader: Shader, paint: Paint, x: Float, y: Float, rx: Float, ry: Float, alpha: Float,
        cover: Boolean = false, w: Float = 0f, h: Float = 0f
    ) {
        if (alpha <= 0.004f) return
        m.setScale(rx, ry); m.postTranslate(x, y)
        shader.setLocalMatrix(m)
        paint.alpha = (255 * min(1f, alpha)).toInt()
        if (cover) canvas.drawRect(0f, 0f, w + 1f, h, paint)
        else canvas.drawRect(x - rx, y - ry, x + rx, y + ry, paint)
    }

    private fun strokeGlass(canvas: Canvas, color: Int, alpha: Float, width: Float) {
        if (alpha <= 0.004f) return
        frameStroke.color = color
        frameStroke.alpha = (255 * min(1f, alpha)).toInt()
        frameStroke.strokeWidth = width
        canvas.drawPath(framePath, frameStroke)
    }

    // endregion

    // region Frame of Light (opening)

    /**
     * Signature, Frame of Light (18 Oct 2026, chosen in Studio). Ends on its climax, as the
     * grand closing does. The seam ignites and the lacquer floods as in Signature; light
     * gathers while the halves cling; they part on real time and stop a bezel short of the
     * edges, leaving a Visconti lacquer frame with a champagne inner lip around the content.
     * Its corners close with a glint, it holds, then slides off every edge. Nothing remains.
     */
    private fun drawFrameOfLight(canvas: Canvas, w: Float, h: Float) {
        val r = 1f - p
        val cx = w / 2f
        val t = FoldMath.unglide(r)
        val hold = MotionConfig.FRAME_HOLD_T
        val bezel = w * 0.042f
        val b = bezel * (1f - FoldMath.glide(FoldMath.clamp((t - hold) / (1f - hold))))
        val mA = FoldMath.magnetOut(FoldMath.clamp((t - 0.42f) / 0.34f))
        val mB = FoldMath.magnetOut(FoldMath.clamp((t - 0.46f) / 0.32f))
        val xa = cx + (w - b - cx) * mA
        val xb = cx - (cx - b) * mB
        val eA = (xa - cx) / (w - cx)
        val eB = (cx - xb) / cx
        if (xa - xb <= 1.5f) {
            signatureBase(canvas, w, h, eA, eB, rtl = true)
        } else {
            // The bezel's top and bottom settle in as the window opens
            val top = b * FoldMath.smooth(FoldMath.clamp((xa - xb) / (w * 0.25f)))
            val rad = min(w * 0.05f, min((xa - xb) / 2f, (h - 2f * top) / 2f))
            framePath.reset()
            framePath.addRect(0f, 0f, w + 1f, h, Path.Direction.CW)
            archWindow(framePath, xb, top, xa, h, rad, top * 0.7f)
            val c0 = canvas.save()
            canvas.clipPath(framePath)
            signatureBase(canvas, w, h, eA, eB, rtl = true)
            if (top > 0.5f) {
                // Same paint as the halves: dark at the outer edge, champagne at the inner lip
                m.setScale(top, 1f); m.postRotate(90f)
                lacquerPanelShader.setLocalMatrix(m)
                canvas.drawRect(0f, 0f, w + 1f, top, lacquerPanelPaint)
                m.setScale(top, 1f); m.postRotate(-90f); m.postTranslate(0f, h)
                lacquerPanelShader.setLocalMatrix(m)
                canvas.drawRect(0f, h - top, w + 1f, h, lacquerPanelPaint)
                flakePaint.alpha = (255 * 0.16f).toInt()
                canvas.drawRect(0f, 0f, w + 1f, top, flakePaint)
                canvas.drawRect(0f, h - top, w + 1f, h, flakePaint)
            }
            canvas.restoreToCount(c0)
            // The champagne inner lip, with a green halo spilling onto the content
            val lit = FoldMath.smooth(FoldMath.clamp((min(mA, mB) - 0.6f) / 0.4f))
            val fade = b / bezel
            framePath.reset()
            archWindow(framePath, xb, top, xa, h, rad, top * 0.7f)
            frameStroke.color = Color.rgb(40, 200, 110)
            frameStroke.alpha = (255 * 0.45f * lit * fade).toInt()
            frameStroke.strokeWidth = w * 0.022f
            if (frameStroke.alpha > 0) canvas.drawPath(framePath, frameStroke)
            frameStroke.color = Color.rgb(255, 246, 228)
            frameStroke.alpha = (255 * (0.6f + 0.4f * lit) * min(1f, fade * 1.5f)).toInt()
            frameStroke.strokeWidth = max(1.5f, w * 0.0035f)
            if (frameStroke.alpha > 0) canvas.drawPath(framePath, frameStroke)
        }
        // Light gathers through the cling and crests as the hold gives
        val bt = MotionConfig.FRAME_BREAK_T
        val charge = FoldMath.smooth(FoldMath.clamp((t - 0.3f) / (bt - 0.3f)))
        val glow = charge * (1f - FoldMath.smooth(FoldMath.clamp((t - bt) / 0.22f)))
        if (glow > 0.004f) {
            greenBand(canvas, cx, 0f, h, w * 0.5f, 0.4f * glow, 0f, w + 1f)
            band(canvas, cx, 0f, h, w * 0.22f, 0.28f * glow)
            band(canvas, cx, 0f, h, w * 0.035f, 0.5f * glow)
        }
        // Champagne motes spill from the seam as the hold gives, carrying the swing outwards on a
        // falling arc (charter rule 6: whimsy from the mechanism). Four follow the leading half.
        val life = 0.3f
        val u = (t - bt) / life
        if (u > 0f && u < 1f) {
            val dt = u * life
            val a = Math.pow(kotlin.math.sin(Math.PI * min(1f, u * 2.2f)), 0.7).toFloat() * (1f - u)
            for (i in moteDir.indices) {
                val x = cx + moteDir[i] * w * moteVx[i] * life * (1f - (1f - u) * (1f - u)) * 0.5f
                val y = h * (moteY0[i] + moteVy[i] * dt + 0.45f * dt * dt)
                val rr = w * moteSize[i]
                m.setScale(rr * 4f, rr * 4f); m.postTranslate(x, y)
                glintShader.setLocalMatrix(m)
                glintPaint.alpha = (255 * 0.35f * a).toInt()
                canvas.drawRect(x - rr * 4f, y - rr * 4f, x + rr * 4f, y + rr * 4f, glintPaint)
                motePaint.alpha = (255 * a).toInt()
                canvas.drawCircle(x, y, rr, motePaint)
            }
        }
        // The frame closes: a glint at each corner as the halves seat
        val cg = FoldMath.smooth(FoldMath.clamp((min(mA, mB) - 0.9f) / 0.1f)) * (1f - FoldMath.smooth(FoldMath.clamp((t - 0.8f) / 0.1f)))
        if (cg > 0.004f) {
            for (i in 0..3) {
                val x = if (i % 2 == 0) b else w - b
                val y = if (i < 2) b else h - b
                val halo = w * 0.12f
                m.setScale(halo, halo); m.postTranslate(x, y)
                frameGlowShader.setLocalMatrix(m)
                frameGlowPaint.alpha = (255 * 0.28f * cg).toInt()
                canvas.drawRect(x - halo, y - halo, x + halo, y + halo, frameGlowPaint)
                val core = w * 0.045f
                m.setScale(core, core); m.postTranslate(x, y)
                glintShader.setLocalMatrix(m)
                glintPaint.alpha = (255 * 0.8f * cg).toInt()
                canvas.drawRect(x - core, y - core, x + core, y + core, glintPaint)
            }
        }
    }

    // endregion

    // region Hinge Shade

    /**
     * Ported from Fold Motion Studio. On p (0 clear, 1 black): the folding half turns
     * away from the light, foreshortening towards the crease and deepening in shade while
     * the crease glows; then the static half settles to dark. As an opening it runs
     * backwards: the static half lifts first, then the folding half swings out flat.
     */
    private fun drawHingeShade(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val a = FoldMath.glide(FoldMath.clamp(p / 0.72f))
        val th = a * (Math.PI.toFloat() / 2f)
        val sn = kotlin.math.sin(th)
        val co = kotlin.math.cos(th)
        // Where the folding half has left is dark
        val pw = (w - cx) * co
        val edge = cx + pw
        if (edge < w) {
            m.setScale(w - edge, 1f); m.postTranslate(edge, 0f)
            hingeFarShader.setLocalMatrix(m)
            canvas.drawRect(edge, 0f, w + 1f, h, hingeFarPaint)
        }
        if (pw > 0.5f) {
            hingeTintPaint.alpha = (255 * 0.55f * sn).toInt()
            canvas.drawRect(cx, 0f, edge, h, hingeTintPaint)
            m.setScale(pw, 1f); m.postTranslate(cx, 0f)
            hingeFaceShader.setLocalMatrix(m)
            hingeFacePaint.alpha = (255 * sn).toInt()
            canvas.drawRect(cx, 0f, edge, h, hingeFacePaint)
            // A specular sweep as the panel passes the light
            val deg = th * 180f / Math.PI.toFloat()
            val spec = kotlin.math.exp(-((deg - 50f) / 14f) * ((deg - 50f) / 14f))
            if (spec > 0.01f) {
                val sx = cx + pw * (0.25f + 0.5f * a)
                val bw = w * 0.08f
                m.setScale(bw * 2f, 1f); m.postTranslate(sx - bw, 0f)
                specShader.setLocalMatrix(m)
                specPaint.alpha = (255 * 0.55f * spec).toInt()
                canvas.drawRect(sx - bw, 0f, sx + bw, h, specPaint)
            }
        }
        // Shadow cast on the static half as the panel comes over
        val sw = w * 0.22f * sn
        if (sw > 1f) shade(canvas, cx, -sw, 0f, h, 0.45f * sn)
        // The crease glows brightest mid-fold
        val cl = kotlin.math.sin(Math.PI.toFloat() * a)
        band(canvas, cx, 0f, h, w * 0.025f, 0.45f * cl)
        if (cl > 0.004f) {
            linePaint.alpha = (255 * 0.85f * cl).toInt()
            canvas.drawRect(cx - 0.75f, 0f, cx + 0.75f, h, linePaint)
        }
        // The static half settles, darkness spreading from the crease to its outer edge
        val d = FoldMath.glide(FoldMath.clamp((p - 0.66f) / 0.3f))
        if (d > 0f) {
            val feather = w * 0.35f
            val front = cx - d * (cx + feather)
            m.setScale(feather, 1f); m.postTranslate(front, 0f)
            duskShader.setLocalMatrix(m)
            canvas.drawRect(max(0f, front), 0f, cx + 1f, h, duskPaint)
        }
        val shut = FoldMath.smooth(FoldMath.clamp((p - 0.94f) / 0.06f))
        if (shut > 0f) { blackPaint.alpha = (255 * shut).toInt(); canvas.drawRect(0f, 0f, w + 1f, h, blackPaint) }
    }

    // endregion

    // region Lacquer Panel

    /**
     * On reveal progress r = 1 - p (closing runs it the other way). A full panel of
     * Visconti paint with flake and lacquer; a clear-coat reflection sweeps left to
     * right; the panel splits at the crease and the halves part. Black at p = 1.
     */
    private fun drawLacquerPanel(
        canvas: Canvas, w: Float, h: Float, fromBlack: Boolean = true, partOnTime: Boolean = false,
        partA: Float = Float.NaN, partB: Float = Float.NaN, rtl: Boolean = false
    ) {
        val r = 1f - p
        val cx = w / 2f
        // The halves are held magnetically: they cling, give, then glide apart.
        // Signature parts on real time, so the cling lasts long enough to be felt.
        val pr = if (partOnTime) FoldMath.unglide(r) else r
        val eA = if (partA.isNaN()) FoldMath.magnetOut(FoldMath.clamp((pr - 0.5f) / 0.45f)) else partA
        val eB = if (partB.isNaN()) FoldMath.magnetOut(FoldMath.clamp((pr - 0.55f) / 0.42f)) else partB
        val xa = cx + (w - cx) * eA
        val xb = cx - cx * eB
        lacquerHalf(canvas, xa, w + 1f, xa, h)
        lacquerHalf(canvas, 0f, xb, xb, h)

        // Clear-coat reflection. Drawn mirrored, so travelling towards x = 0 here is left to
        // right on screen; when closing the progress runs backwards, so flip the sweep to keep
        // it moving left to right in both directions.
        val raw = FoldMath.clamp((r - 0.12f) / 0.38f)
        val sw = if (mode == Mode.CLOSE) 1f - raw else raw
        val gA = FoldMath.smooth(FoldMath.clamp(r / 0.1f)) * (1f - FoldMath.smooth(FoldMath.clamp((r - 0.5f) / 0.12f)))
        if (gA > 0.004f) {
            val gw = w * 0.22f
            // Drawn mirrored: rtl travels right to left on screen, following the cover as it is pulled open
            val gx = if (rtl) sw * (w + gw * 1.5f) - gw * 1.25f else w - sw * (w + gw * 1.5f) + gw * 0.25f
            m.setScale(gw, 1f); m.postTranslate(gx, 0f)
            champagneGlossShader.setLocalMatrix(m)
            champagneGlossPaint.alpha = (255 * gA).toInt()
            for (side in 0..1) {
                val l = if (side == 0) xa else 0f
                val rr = if (side == 0) w + 1f else xb
                val x0 = max(l, gx)
                val x1 = min(rr, gx + gw)
                if (x1 <= x0) continue
                canvas.drawRect(x0, 0f, x1, h, champagneGlossPaint)
                flakePaint.alpha = (255 * 0.6f * gA).toInt()
                val f0 = max(l, gx + gw * 0.3f)
                val f1 = min(rr, gx + gw * 0.7f)
                if (f1 > f0) canvas.drawRect(f0, 0f, f1, h, flakePaint)
            }
        }
        // Shadows cast on the screen as the halves part
        shade(canvas, xa, -w * 0.05f, 0f, h, 0.8f * min(1f, eA * 8f) * (1f - eA))
        shade(canvas, xb, w * 0.05f, 0f, h, 0.8f * min(1f, eB * 8f) * (1f - eB))
        // From black
        val black = if (fromBlack) 1f - FoldMath.smooth(FoldMath.clamp(r / 0.12f)) else 0f
        if (black > 0f) { blackPaint.alpha = (255 * black).toInt(); canvas.drawRect(0f, 0f, w + 1f, h, blackPaint) }
    }

    /** One half of the panel, from x0 to x1, lit towards its leading edge. */
    private fun lacquerHalf(canvas: Canvas, x0: Float, x1: Float, lead: Float, h: Float) {
        if (x1 - x0 < 0.5f) return
        val outer = if (lead == x0) x1 else x0
        m.setScale(lead - outer, 1f); m.postTranslate(outer, 0f)
        lacquerPanelShader.setLocalMatrix(m)
        canvas.drawRect(x0, 0f, x1, h, lacquerPanelPaint)
        flakePaint.alpha = (255 * 0.16f).toInt()
        canvas.drawRect(x0, 0f, x1, h, flakePaint)
        m.setScale(1f, h)
        lacquerShader.setLocalMatrix(m)
        canvas.drawRect(x0, 0f, x1, h, lacquerPaint)
    }

    // endregion

    // region Arch Aperture

    /**
     * On reveal progress r = 1 - p. A slit ignites down the crease and flashes, then
     * opens into a parabolic arch with a lit rim, widening past the edges. The screen
     * only shows inside the arch, from about halfway through.
     */
    private fun drawArchAperture(canvas: Canvas, w: Float, h: Float) {
        val r = 1f - p
        val cx = w / 2f
        val cy = h / 2f
        // Linear here: the magnetic curve is applied by the closing plan, not twice
        val o = FoldMath.clamp((r - 0.42f) / 0.5f)
        val a = o * w * 0.8f
        val e = h * 0.25f
        // Outside the arch: curtains of Visconti lacquer (a grand closing; 18 Oct 2026)
        if (a <= 0.5f) {
            drawSurround(canvas, w, h)
        } else {
            archPath.reset()
            archPath.moveTo(cx, -e)
            archPath.quadTo(cx + 2f * a, cy, cx, h + e)
            archPath.quadTo(cx - 2f * a, cy, cx, -e)
            archPath.close()
            maskPath.reset()
            maskPath.addRect(0f, 0f, w + 1f, h, Path.Direction.CW)
            maskPath.addPath(archPath)
            val c0 = canvas.save()
            canvas.clipPath(maskPath)
            drawSurround(canvas, w, h)
            canvas.restoreToCount(c0)
            // Inner shadow, a green halo and a champagne-cored rim
            val rim = 1f - FoldMath.smooth(FoldMath.clamp((r - 0.8f) / 0.18f))
            if (rim > 0f) {
                val c = canvas.save()
                canvas.clipPath(archPath)
                archStroke.color = Color.BLACK
                archStroke.alpha = (255 * 0.55f * rim).toInt()
                archStroke.strokeWidth = w * 0.06f
                canvas.drawPath(archPath, archStroke)
                canvas.restoreToCount(c)
                archStroke.color = Color.rgb(40, 200, 110)
                // The field strengthens as the arch closes on the crease
                archStroke.alpha = (255 * min(0.6f, 0.32f * rim * (0.6f + 0.9f * (1f - o)))).toInt()
                archStroke.strokeWidth = w * 0.025f
                canvas.drawPath(archPath, archStroke)
                archStroke.color = Color.rgb(255, 244, 222)
                archStroke.alpha = (255 * 0.95f * rim).toInt()
                archStroke.strokeWidth = max(2f, w * 0.004f)
                canvas.drawPath(archPath, archStroke)
            }
        }
        // Once sealed, the paint fades to black
        val dark = 1f - FoldMath.smooth(FoldMath.clamp((r - 0.12f) / 0.3f))
        if (dark > 0f) { blackPaint.alpha = (255 * dark).toInt(); canvas.drawRect(0f, 0f, w + 1f, h, blackPaint) }
        // The seam on the crease: it collapses to the centre as the effect ends
        val ig = FoldMath.glide(FoldMath.clamp(r / 0.14f))
        val glow = 1f - FoldMath.smooth(FoldMath.clamp((r - 0.45f) / 0.2f))
        if (ig > 0f && glow > 0f) {
            val top = cy * (1f - ig)
            val bot = cy * (1f + ig)
            greenBand(canvas, cx, top, bot, w * 0.14f, 0.4f * glow, 0f, w + 1f)
            seamPaint.alpha = (255 * 0.95f * glow).toInt()
            canvas.drawRect(cx - 2f, top, cx + 2f, bot, seamPaint)
        }
        // The seal flash was removed on 19 Oct 2026 (charter rule 4: one climax per transition).
        // The last point of light dips into the hinge with the magnetic settle, then goes out.
        val glint = FoldMath.smooth(FoldMath.clamp(r / 0.03f)) * (1f - FoldMath.smooth(FoldMath.clamp((r - 0.05f) / 0.1f)))
        if (glint > 0f) {
            val rad = w * 0.06f
            val wd = FoldMath.clamp(1f - r / 0.15f)
            val gy = cy + h * 0.016f * kotlin.math.sin(Math.PI.toFloat() * wd) * (1f - wd)
            m.setScale(rad, rad); m.postTranslate(cx, gy)
            glintShader.setLocalMatrix(m)
            glintPaint.alpha = (255 * glint).toInt()
            canvas.drawRect(cx - rad, gy - rad, cx + rad, gy + rad, glintPaint)
        }
    }

    /** Visconti lacquer for the closing curtains, with flake, clear coat and a champagne reflection. */
    private fun drawSurround(canvas: Canvas, w: Float, h: Float) {
        m.setScale(w, 1f)
        surroundShader.setLocalMatrix(m)
        canvas.drawRect(0f, 0f, w + 1f, h, surroundPaint)
        flakePaint.alpha = (255 * 0.16f).toInt()
        canvas.drawRect(0f, 0f, w + 1f, h, flakePaint)
        m.setScale(1f, h)
        lacquerShader.setLocalMatrix(m)
        canvas.drawRect(0f, 0f, w + 1f, h, lacquerPaint)
        // Reflection travels left to right on screen (drawing is mirrored) while the arch closes
        val q = FoldMath.clamp((p - 0.05f) / 0.5f)
        val gA = FoldMath.smooth(FoldMath.clamp(p / 0.1f)) * (1f - FoldMath.smooth(FoldMath.clamp((p - 0.5f) / 0.12f)))
        if (gA > 0.004f) {
            val gw = w * 0.22f
            val gx = w - q * (w + gw * 1.5f) + gw * 0.25f
            m.setScale(gw, 1f); m.postTranslate(gx, 0f)
            champagneGlossShader.setLocalMatrix(m)
            champagneGlossPaint.alpha = (255 * gA).toInt()
            canvas.drawRect(max(0f, gx), 0f, min(w + 1f, gx + gw), h, champagneGlossPaint)
            flakePaint.alpha = (255 * 0.6f * gA).toInt()
            canvas.drawRect(max(0f, gx + gw * 0.3f), 0f, min(w + 1f, gx + gw * 0.7f), h, flakePaint)
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
            // One stroke per rib (the soft glow beneath was cut on 16 Oct 2026 to halve the work)
            strokePaint.color = depthColor[d]
            strokePaint.strokeWidth = lw * 1.4f
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

    // region Fanale (23 Oct 2026): Alfa Romeo as the only influence

    /**
     * Opening: the phone wakes like the Giulietta at night. Black, with the two rear lamps' rims
     * and embers already there. The right ring sweeps round where the fingers left the cover; its
     * bar runs in to the crease; the light crosses on a fine seam; the left bar runs out and the
     * left ring closes. Lights on: a soft bloom (the climax, with the haptic click). Then the dark
     * halves part on the magnetic curves, each carrying its lamp out past the screen's edge, their
     * lips lit in red and casting the lamps' glow onto the content like tail lights on wet tarmac.
     */
    private fun drawFanaleOpen(canvas: Canvas, w: Float, h: Float) {
        val t = FoldMath.unglide(1f - p)
        val cx = w / 2f
        val l = rosso
        l.layout(w, h)
        l.scheduleIgnite(1, t, 0.02f, 0.15f, 0.13f, 0.29f, barFromTip = false)
        l.scheduleIgnite(0, t, 0.42f, 0.54f, 0.30f, 0.43f, barFromTip = true)
        val bt = MotionConfig.FANALE_LIGHTS_T
        val bloom = FoldMath.smooth(FoldMath.clamp((t - (bt - 0.03f)) / 0.04f)) * (1f - FoldMath.smooth(FoldMath.clamp((t - bt - 0.04f) / 0.16f)))
        if (bloom > 0f) l.scale(1f + 0.4f * bloom)

        val eR = FoldMath.magnetOut(FoldMath.clamp((t - 0.5f) / 0.4f))
        val eL = FoldMath.magnetOut(FoldMath.clamp((t - 0.54f) / 0.38f))
        val xr = cx + (w - cx) * eR
        val xl = cx - cx * eL
        fanaleHalves(canvas, l, w, h, xl, xr, eL, eR, 1f)

        // The seam: as the light crosses the crease, one fine line
        val seam = FoldMath.smooth(FoldMath.clamp((t - 0.27f) / 0.025f)) * (1f - FoldMath.smooth(FoldMath.clamp((t - 0.31f) / 0.06f)))
        if (seam > 0.004f && xr - xl < 2f) {
            l.glow(canvas, cx, h * 0.5f, w * 0.03f, h * 0.55f, 0.3f * seam)
            l.line(canvas, cx, 0f, h, max(1.2f, w * 0.0012f), 0.6f * seam)
        }
        // Lights on: the rings breathe out onto the bodywork
        if (bloom > 0.004f && xr - xl < w * 0.3f) {
            for (side in 0..1) {
                val off = if (side == 0) xl - cx else xr - cx
                val x = (if (side == 0) w * 0.15f else w * 0.85f) + off
                l.glow(canvas, x, h * 0.5f, l.ringRadius * 2.4f, l.ringRadius * 2.4f, 0.22f * bloom)
            }
        }
    }

    /**
     * Closing: locking the car. The dark halves slide in from the edges with their lamps lit, the
     * left a beat ahead (the fold travels left to right), and meet on the crease with the soft
     * close (haptic click at contact). Then the lamps go out as an indicator does: each ring
     * round from its far side, then its bar in towards the crease, the left lamp first. The two
     * tips meet as the last point of light on the crease, which dips into the hinge and goes out.
     */
    private fun drawFanaleClose(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val l = rosso
        l.layout(w, h)
        // The halves meet exactly at the closing plan's contact (BUILD_END); the left leads
        val eL = FoldMath.clamp(p / MotionConfig.BUILD_END)
        val eR = FoldMath.clamp((p - 0.025f) / (MotionConfig.BUILD_END - 0.025f))
        val xl = cx * eL
        val xr = w - (w - cx) * eR
        // The rings go out as the halves arrive, the bars across the contact (the finale is only 300ms)
        l.scheduleOut(0, p, 0.3f, 0.44f, 0.48f, 0.7f)
        l.scheduleOut(1, p, 0.36f, 0.5f, 0.56f, 0.8f)
        val fade = 1f - FoldMath.smooth(FoldMath.clamp((p - 0.82f) / 0.14f))
        // Shadow and lip light read the halves' approach, so they run on 1 - e
        fanaleHalves(canvas, l, w, h, xl, xr, 1f - eL, 1f - eR, fade)
        // The last point of light: the two tips meet on the crease, dip into the hinge, go out
        val glint = FoldMath.smooth(FoldMath.clamp((p - 0.74f) / 0.06f)) * (1f - FoldMath.smooth(FoldMath.clamp((p - 0.84f) / 0.12f)))
        if (glint > 0.004f) {
            val wd = FoldMath.clamp((p - 0.78f) / 0.18f)
            val gy = h * 0.5f - l.ringRadius * 0.18f + h * 0.016f * kotlin.math.sin(Math.PI.toFloat() * wd) * (1f - wd)
            l.glow(canvas, cx, gy, w * 0.045f, w * 0.045f, 0.55f * glint)
            l.coreDot(canvas, cx, gy, l.ringRadius * 0.08f, 0.9f * glint)
        }
    }

    /**
     * The two dark halves with their lamps. The left half covers [0, xl], the right [xr, w].
     * [eL] and [eR] are how far each has travelled away from the crease (0 closed, 1 gone): the
     * lips glow and cast shadows only while they are near the content's middle.
     */
    private fun fanaleHalves(canvas: Canvas, l: Lamps, w: Float, h: Float, xl: Float, xr: Float, eL: Float, eR: Float, fade: Float) {
        val cx = w / 2f
        blackPaint.alpha = 255
        if (xr - xl <= 1f) {
            canvas.drawRect(0f, 0f, w + 1f, h, blackPaint)
        } else {
            canvas.drawRect(0f, 0f, xl, h, blackPaint)
            canvas.drawRect(xr, 0f, w + 1f, h, blackPaint)
            // Shadows cast onto the content by the halves
            shade(canvas, xr, -w * 0.06f, 0f, h, 0.75f * min(1f, eR * 8f) * (1f - eR))
            shade(canvas, xl, w * 0.06f, 0f, h, 0.75f * min(1f, eL * 8f) * (1f - eL))
            // Red light from the lips falling onto the content
            val lipR = min(1f, eR * 6f) * (1f - FoldMath.smooth(FoldMath.clamp((eR - 0.35f) / 0.6f)))
            val lipL = min(1f, eL * 6f) * (1f - FoldMath.smooth(FoldMath.clamp((eL - 0.35f) / 0.6f)))
            l.glow(canvas, xr, h * 0.5f, w * 0.05f, h * 0.62f, 0.22f * lipR)
            l.glow(canvas, xl, h * 0.5f, w * 0.05f, h * 0.62f, 0.22f * lipL)
            l.line(canvas, xr, 0f, h, max(1.5f, w * 0.0014f), 0.65f * lipR)
            l.line(canvas, xl, 0f, h, max(1.5f, w * 0.0014f), 0.65f * lipL)
        }
        // Each lamp rides its half and is seen only on it
        val shut = xr - xl <= 1f
        var c = canvas.save()
        canvas.clipRect(0f, 0f, if (shut) cx else xl, h)
        l.draw(canvas, 0, xl - cx, fade)
        canvas.restoreToCount(c)
        c = canvas.save()
        canvas.clipRect(if (shut) cx else xr, 0f, w + 1f, h)
        l.draw(canvas, 1, xr - cx, fade)
        canvas.restoreToCount(c)
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
