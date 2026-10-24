package com.fergusonconsultancy.foldmotion

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fold Motion's settings, rebuilt to Bellezza Necessaria (23 Oct 2026). The transition itself
 * is the first thing on the page, playing live on a stage whose controls stay hidden until you
 * touch it. Then the style, the options that belong to it (only when they apply), what the
 * phone must allow, one main action, and the doctrine and design notes folded away.
 */
class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private lateinit var screen: Screen
    private lateinit var stage: Stage
    private lateinit var presets: Chips
    private lateinit var presetDetail: TextView
    private lateinit var openingChips: Chips
    private lateinit var openingDetail: TextView
    private lateinit var closingChips: Chips
    private lateinit var closingDetail: TextView
    private lateinit var rimBlock: LinearLayout
    private lateinit var overlayRow: Row
    private lateinit var overlayValue: TextView
    private lateinit var backgroundValue: TextView
    private lateinit var actionSlot: LinearLayout
    private lateinit var diagnostics: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Kit.GROUND
        window.navigationBarColor = Kit.GROUND
        MotionConfig.apply(this)
        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        screen = Screen(this, "Fold Motion", "Galaxy Z Fold 8 Ultra")

        // ---- The stage: the transition, live
        stage = Stage(this)
        screen.add(stage, px(26))

        // ---- Presets: a matched opening and closing in one tap
        screen.section("Transition")
        val styles = MotionConfig.Style.entries
        presets = Chips(this, styles.map { it.label }, styles.indexOf(MotionConfig.matchingStyle(this))) { i ->
            MotionConfig.setStyle(this, styles[i])
            openingChips.select(MotionConfig.Opening.entries.indexOf(MotionConfig.opening(this)))
            closingChips.select(MotionConfig.Closing.entries.indexOf(MotionConfig.closing(this)))
            describe(); showRim(); stage.play(open = true)
        }
        screen.add(presets)
        presetDetail = text("", 13.5f, Kit.MUTED).apply { tag = SECONDARY; setPadding(px(4), px(10), 0, 0) }
        screen.add(presetDetail)

        // ---- Opening: its own effect, brightness and speed
        screen.section("Opening")
        val openings = MotionConfig.Opening.entries
        openingChips = Chips(this, openings.map { it.label }, openings.indexOf(MotionConfig.opening(this))) { i ->
            MotionConfig.setOpening(this, openings[i]); describe(); showRim(); stage.play(open = true)
        }
        screen.add(openingChips)
        openingDetail = text("", 13.5f, Kit.MUTED).apply { tag = SECONDARY; setPadding(px(4), px(10), 0, 0) }
        screen.add(openingDetail)
        screen.add(tuningCard(open = true), px(14))

        // ---- Closing
        screen.section("Closing")
        val closings = MotionConfig.Closing.entries
        closingChips = Chips(this, closings.map { it.label }, closings.indexOf(MotionConfig.closing(this))) { i ->
            MotionConfig.setClosing(this, closings[i]); describe(); showRim(); stage.play(open = false)
        }
        screen.add(closingChips)
        closingDetail = text("", 13.5f, Kit.MUTED).apply { tag = SECONDARY; setPadding(px(4), px(10), 0, 0) }
        screen.add(closingDetail)
        screen.add(tuningCard(open = false), px(14))

        // Membrana's rim colour: only there when Membrana opens or closes (principle 3)
        rimBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rimBlock.addView(text("Rim light", 14f, Kit.MUTED, 450).apply { setPadding(px(4), 0, 0, px(8)) })
        val rims = MotionConfig.Rim.entries
        rimBlock.addView(Segmented(this, rims.map { it.label }, rims.indexOf(MotionConfig.rim(this))) { i ->
            MotionConfig.setRim(this, rims[i]); stage.play(open = true)
        }, LinearLayout.LayoutParams(-1, -2))
        screen.add(rimBlock, px(18))
        describe(); showRim(animate = false)

        screen.add(Card(this).apply {
            row(Row(this@MainActivity, "Concentrato", "The faster you fold, the emptier the transition: only the movement stays",
                Toggle(this@MainActivity, MotionConfig.concentrato(this@MainActivity)) { MotionConfig.setConcentrato(this@MainActivity, it) }))
            row(Row(this@MainActivity, "Haptics", "A click at the climax, a settle on opening",
                Toggle(this@MainActivity, Haptics.isEnabled(this@MainActivity)) { Haptics.setEnabled(this@MainActivity, it); refresh() }))
        }, px(18))

        // ---- Permissions
        screen.section("Permissions")
        overlayValue = endValue("")
        backgroundValue = endValue("")
        overlayRow = Row(this, "Display over other apps", "Needed to draw the transition", overlayValue) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val backgroundRow = Row(this, "Run in the background", "Stops Android putting it to sleep", backgroundValue) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        screen.add(Card(this).apply { row(overlayRow); row(backgroundRow) })

        actionSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.add(actionSlot, px(28))

        // ---- Doctrine, notes, diagnostics
        screen.section("Doctrine")
        screen.add(doctrineCard(DOCTRINE_NOTES))
        screen.add(notesCard("Design notes", "Each transition, and why it moves as it does", DESIGN_NOTES), px(12))

        screen.section("Diagnostics")
        val diag = Fold(this, "Status and event log", "For Claude when something misbehaves")
        diagnostics = text("", 12.5f, Kit.MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        logText = text("", 12f, Kit.MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        diag.body.addView(diagnostics)
        diag.body.addView(View(this).apply { setBackgroundColor(Kit.HAIRLINE) }, LinearLayout.LayoutParams(-1, px(1)).apply { topMargin = px(14); bottomMargin = px(14) })
        diag.body.addView(logText)
        diag.body.addView(primary("Clear log", filled = false) { EventLog.clear(this); refresh() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        screen.add(diag)

        setContentView(screen.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** The rim colour materialises under the choices when Membrana is used, and folds away when not. */
    private fun showRim(animate: Boolean = true) {
        val on = MotionConfig.opening(this) == MotionConfig.Opening.MEMBRANA || MotionConfig.closing(this) == MotionConfig.Closing.MEMBRANA
        val v = if (on) View.VISIBLE else View.GONE
        if (rimBlock.visibility == v) return
        if (animate) android.transition.TransitionManager.beginDelayedTransition(screen.column)
        rimBlock.visibility = v
    }

    /** Keeps the lines under each choice true, and the preset row honest: a mix shows as Custom. */
    private fun describe() {
        val match = MotionConfig.matchingStyle(this)
        presets.select(MotionConfig.Style.entries.indexOf(match))
        presetDetail.text = match?.detail ?: "Custom: your own opening and closing. Pick a preset to match them again."
        openingDetail.text = MotionConfig.opening(this).detail
        closingDetail.text = MotionConfig.closing(this).detail
    }

    /** Brightness of the light and speed of the movement, for one direction. The centre tick is as designed. */
    private fun tuningCard(open: Boolean): View = Card(this).apply {
        val bKey = if (open) "open_brightness" else "close_brightness"
        val sKey = if (open) "open_speed" else "close_speed"
        val pct = { v: Float -> if (kotlin.math.abs(v - 1f) < 0.01f) "As designed" else "${(v * 100).toInt()}%" }
        addView(Slider(this@MainActivity, "Brightness", MotionConfig.BRIGHTNESS_MIN, MotionConfig.BRIGHTNESS_MAX,
            if (open) MotionConfig.openBrightness(this@MainActivity) else MotionConfig.closeBrightness(this@MainActivity), format = pct) {
            MotionConfig.setTuning(this@MainActivity, bKey, it); stage.play(open)
        })
        addView(Slider(this@MainActivity, "Speed", MotionConfig.SPEED_MIN, MotionConfig.SPEED_MAX,
            if (open) MotionConfig.openSpeed(this@MainActivity) else MotionConfig.closeSpeed(this@MainActivity),
            format = { v -> if (kotlin.math.abs(v - 1f) < 0.01f) "As designed" else "%.2fx".format(v) }) {
            MotionConfig.setTuning(this@MainActivity, sKey, it); stage.play(open)
        })
    }

    override fun onResume() {
        super.onResume()
        hingeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        refresh()
        stage.postDelayed({ stage.play(open = true) }, 450)
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    private fun start() {
        AutoStart.setEnabled(this, true)
        startForegroundService(Intent(this, FoldMotionService::class.java))
        HealJob.schedule(this)
        stage.postDelayed({ refresh() }, 400)
        refresh()
    }

    private fun stop() {
        AutoStart.setEnabled(this, false)
        HealJob.cancel(this)
        stopService(Intent(this, FoldMotionService::class.java))
        refresh()
    }

    private fun refresh() {
        val running = FoldMotionService.running
        val overlayOk = Settings.canDrawOverlays(this)
        val exempt = getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        screen.status.set(if (running) "Running" else "Stopped", if (running) Kit.GOOD else Kit.MUTED)
        overlayValue.text = if (overlayOk) "Allowed" else "Allow  ›"
        overlayValue.setTextColor(if (overlayOk) Kit.MUTED else Kit.CHAMPAGNE)
        backgroundValue.text = if (exempt) "Allowed" else "Allow  ›"
        backgroundValue.setTextColor(if (exempt) Kit.MUTED else Kit.CHAMPAGNE)

        actionSlot.removeAllViews()
        actionSlot.addView(
            if (running) primary("Stop Fold Motion", filled = false) { stop() }
            else primary(if (overlayOk) "Start Fold Motion" else "Allow display over other apps first") {
                if (overlayOk) start() else overlayRow.performClick()
            }
        )

        logText.text = EventLog.read(this).ifBlank { "Nothing logged yet." }
        diagnostics.text = buildString {
            appendLine(if (hingeSensor != null) "Hinge sensor: found" else "Hinge sensor: NOT found on this phone")
            val prefs = getSharedPreferences("fold_motion", MODE_PRIVATE)
            for (dir in listOf("closing", "opening")) {
                val deg = prefs.getFloat("switch_${dir}_deg", -1f)
                val ms = prefs.getLong("switch_${dir}_ms", -1L)
                appendLine("Switch when $dir: " + if (deg < 0) "not seen yet" else "${deg.toInt()}°, ${ms}ms after the last hinge step")
            }
            val scale = runCatching {
                Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
            appendLine(if (scale <= 0f) "Animator duration scale: off, so the transition is skipped" else "Animator duration scale: ${scale}x")
            appendLine("Animates at up to ${display?.supportedModes?.maxOfOrNull { it.refreshRate }?.toInt() ?: "?"}Hz")
            appendLine("Opening: ${MotionConfig.opening(this@MainActivity).label} (light ${(MotionConfig.openBrightness(this@MainActivity) * 100).toInt()}%, speed ${MotionConfig.openSpeed(this@MainActivity)}x)")
            appendLine("Closing: ${MotionConfig.closing(this@MainActivity).label} (light ${(MotionConfig.closeBrightness(this@MainActivity) * 100).toInt()}%, speed ${MotionConfig.closeSpeed(this@MainActivity)}x)")
            appendLine("Rim ${MotionConfig.RIM.label.lowercase()}, Concentrato ${if (MotionConfig.concentrato(this@MainActivity)) "on" else "off"}")
            appendLine("Starts after a restart: " + if (AutoStart.isEnabled(this@MainActivity)) "on" else "off")
            appendLine("Service last started: ${Status.describe(this@MainActivity, Status.STARTED)}")
            appendLine("Effect last played: ${Status.describe(this@MainActivity, Status.PLAYED)}")
            appendLine(Status.describeHeals(this@MainActivity))
            appendLine(Timing.describe(this@MainActivity, MotionConfig.EFFECT_MS * maxOf(scale, 0.01f)))
            append(Status.describeRun(this@MainActivity))
        }
    }

    override fun onSensorChanged(event: SensorEvent) = stage.setHinge(event.values[0])

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        val DOCTRINE_NOTES = listOf(
            "Membrane" to "Membrana treats the whole screen as one skin. It parts on two hanging curves, its middle leading, thins where it is stretched so your screen shows faintly through, and snaps taut as it lets go. On closing it is drawn back in until the opening is a lens and surface tension seals it.",
            "Concentrato" to "Every style reads how fast you folded. Opening, from the hinge passing 90 degrees to One UI switching; closing, from how quickly you usually close. A fast fold keeps only the movement: no sparks, no glow, no bloom. A slow one keeps every detail. Switch it off and every fold gets everything.",
            "The Giulietta principle" to "The preview's controls stay hidden in the stage until your finger lands on it. Membrana's rim colour only appears when Membrana is chosen. The eyebrows and the lines under each title fall away while you fling the page, and the page itself stretches like one surface.",
        )
        val DESIGN_NOTES = listOf(
            "Visconti" to "A lacquer arch closes in on the crease and the last point of light dips into the hinge. Opening, dark glass with Visconti light inside it parts into a lit bezel that seats with a click and slides away.",
            "Giulietta" to "The same glass opening, woken by the Giulietta's rear lamps: the right ring sweeps round where your fingers left the cover, its bar runs in to the crease, the light crosses, and the left ring closes as the halves let go.",
            "Fanale" to "Alfa Romeo alone. Opening is unlocking the car: both lamps light in sequence, bloom once with the click, and the halves carry them off the edges, their lips casting red light on the screen. Closing is locking it: the lamps go out like an indicator and the last light dips into the hinge.",
            "Circuito" to "Fanale in symmetry. Both lamps light at once at their outer edge, nine o'clock on the left and three on the right, and the light runs round each ring both ways before travelling down the bars. The last stretch crosses the gap and the two lights meet as one point on the crease: the bloom and the click. Closing drains the light along the same path to that same point, which dips into the hinge.",
            "Fanale reversed" to "The Fanale closing run backwards as an opening. The last point of light rises out of the hinge, the lamps come back on like an indicator in reverse, and the halves part with them lit. It is the lock played as an unlock, so a Fanale closing and this opening read as one continuous gesture.",
            "Your own pairing" to "Presets match an opening to its closing. Choose them separately and the preset row reads Custom; nothing is lost, and tapping a preset matches them again.",
            "Brightness and speed" to "Each direction has its own. Brightness runs from 60% to 160% of the designed light and lifts the glow, not only the colour. Speed runs from 0.7x to 1.4x. The closing still finishes before One UI switches, because its build is planned against your fold; a slower closing simply starts earlier. The tick under each slider is the designed value, and releasing near it settles there.",
            "Membrana" to "The doctrine's own transition. No lamps and no lacquer: one membrane and one line of light on each moving edge, brightest at its middle and falling off towards its anchors. The rim takes champagne, rosso or Visconti.",
            "One climax" to "Every style keeps one moment: the seated bezel, the lights coming on, the membrane snapping taut. The haptic click sits on it, and touch comes back the moment your screen is whole.",
        )
    }
}

/**
 * The stage: a sample home screen with the real overlay on top, playing the chosen transition.
 * Proximity (principle 3): its controls are hidden until a finger lands on the stage, then
 * gather beneath it and recede a few seconds after the last touch.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class Stage(ctx: Context) : FrameLayout(ctx) {
    private val content = SampleScreen(ctx)
    private val overlay = FoldOverlayView(ctx)
    private val controls = LinearLayout(ctx)
    private val hinge = ctx.text("", 12f, Kit.INK, 500).apply { letterSpacing = 0.12f; alpha = 0.7f }
    private var anim: ValueAnimator? = null
    private var fast = false
    private val fastChip: TextView
    private val hide = Runnable { showControls(false) }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, ctx.px(24).toFloat())
        }
        addView(content, LayoutParams(-1, -1))
        addView(overlay, LayoutParams(-1, -1))
        addView(hinge, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ctx.px(16), ctx.px(14), 0, 0) })
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        fun chip(label: String, onTap: () -> Unit) = ctx.text(label, 14f, Kit.INK, 500).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.px(18), ctx.px(10), ctx.px(18), ctx.px(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC0E1311.toInt()); cornerRadius = ctx.px(22).toFloat(); setStroke(ctx.px(1), 0x40F6F1E6) }
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onTap() }
        }
        controls.addView(chip("Open") { play(open = true) })
        controls.addView(chip("Close") { play(open = false) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.px(10) })
        fastChip = chip("Slow fold") { fast = !fast; paintFast(); play(open = true) }
        controls.addView(fastChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.px(10) })
        controls.alpha = 0f
        controls.visibility = View.INVISIBLE
        controls.translationY = ctx.px(14).toFloat()
        addView(controls, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ctx.px(18) })
        setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) showControls(true); false }
        isClickable = true
    }

    private fun paintFast() {
        fastChip.text = if (fast) "Fast fold" else "Slow fold"
        fastChip.setTextColor(if (fast) Kit.CHAMPAGNE else Kit.INK)
    }

    private fun showControls(on: Boolean) {
        removeCallbacks(hide)
        // Hidden controls take no taps: the first touch only brings them to the surface
        if (on) controls.visibility = View.VISIBLE
        controls.animate().alpha(if (on) 1f else 0f).translationY(if (on) 0f else context.px(14).toFloat())
            .setDuration(if (on) 260 else 380).setInterpolator(Kit.ease)
            .withEndAction { if (!on) controls.visibility = View.INVISIBLE }.start()
        if (on) postDelayed(hide, 3200)
    }

    fun setHinge(a: Float) { hinge.text = "HINGE ${a.toInt()}°" }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        super.onMeasure(w, MeasureSpec.makeMeasureSpec((width / 1.16f).toInt(), MeasureSpec.EXACTLY))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        overlay.setContentSize(w, h)
    }

    /** Plays the opening or the closing of the chosen style, as the service would. */
    fun play(open: Boolean) {
        anim?.cancel()
        MotionConfig.apply(context)
        overlay.mode = if (open) FoldOverlayView.Mode.OPEN else FoldOverlayView.Mode.CLOSE
        overlay.density = if (fast && MotionConfig.concentrato(context)) MotionConfig.DENSITY_FLOOR else 1f
        overlay.brightness = if (open) MotionConfig.openBrightness(context) else MotionConfig.closeBrightness(context)
        overlay.setContentSize(width, height)
        // Speed as the service applies it: the whole run shortens or stretches
        val speed = if (open) MotionConfig.openSpeed(context) else MotionConfig.closeSpeed(context)
        val build = 600f / speed; val finale = MotionConfig.FINALE_MS / speed
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (open) (MotionConfig.REVEAL_MS / speed).toLong() else (build + finale).toLong()
            interpolator = null
            addUpdateListener {
                val k = it.animatedFraction
                val p = if (open) 1f - FoldMath.glide(k) else {
                    val ms = k * (build + finale)
                    if (ms < build) MotionConfig.BUILD_END * FoldMath.magnetIn(ms / build)
                    else MotionConfig.BUILD_END + (1f - MotionConfig.BUILD_END) * FoldMath.smooth(FoldMath.clamp((ms - build) / finale))
                }
                overlay.show(p)
            }
            // A closing ends on black; after a beat the stage clears for the next play
            if (!open) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { postDelayed({ if (anim?.isRunning != true) overlay.show(0f, 0f) }, 700) }
            })
            start()
        }
    }
}

/** A stand-in home screen for the stage: a dusk wallpaper, the time, and a grid of icons. */
class SampleScreen(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val colours = intArrayOf(0xFFC8463A.toInt(), 0xFF3F7FD1.toInt(), 0xFFE3A13B.toInt(), 0xFF4A9D6A.toInt(), 0xFF8A5CC7.toInt(), 0xFFD9BB86.toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF1D2B3A.toInt(), 0xFF2A2233.toInt(), 0xFF3A2A24.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * 0.72f, h * 0.28f, w * 0.5f, 0x55D67850, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.color = Kit.INK
        p.typeface = Kit.sans(context, 300)
        p.textSize = h * 0.14f
        c.drawText("22:38", w * 0.07f, h * 0.25f, p)
        val n = 6; val s = w * 0.075f; val gx = (w - n * s) / (n + 1)
        for (row in 0..2) for (i in 0 until n) {
            val x = gx + i * (s + gx); val y = h * 0.46f + row * (s + h * 0.07f)
            p.color = colours[(row * n + i) % colours.size]
            r.set(x, y, x + s, y + s)
            c.drawRoundRect(r, s * 0.28f, s * 0.28f, p)
        }
    }
}
