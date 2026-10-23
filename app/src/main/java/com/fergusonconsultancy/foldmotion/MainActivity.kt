package com.fergusonconsultancy.foldmotion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
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
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fold Motion's settings (redesigned 23 Oct 2026). The live hinge at the top, then the
 * transition to choose, what the phone must allow, one main action, and the diagnostics
 * folded away beneath.
 */
class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private lateinit var screen: Screen
    private lateinit var dial: HingeDial
    private lateinit var overlayRow: Row
    private lateinit var backgroundRow: Row
    private lateinit var overlayValue: TextView
    private lateinit var backgroundValue: TextView
    private lateinit var actionSlot: LinearLayout
    private lateinit var diagnostics: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Kit.GROUND
        window.navigationBarColor = Kit.GROUND
        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        screen = Screen(this, "Fold Motion", "Galaxy Z Fold 8 Ultra")

        dial = HingeDial(this)
        screen.add(Card(this).apply { row(dial) }, px(26))

        screen.section("Transition")
        val styles = MotionConfig.Style.entries
        screen.add(ChoiceList(this, styles.map { s -> Triple(s.label, s.detail, swatchFor(s)) }, styles.indexOf(MotionConfig.style(this))) { i ->
            MotionConfig.setStyle(this, styles[i])
            refresh()
        })

        screen.section("Permissions")
        overlayValue = endValue("")
        backgroundValue = endValue("")
        overlayRow = Row(this, "Display over other apps", "Needed to draw the transition", overlayValue) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        backgroundRow = Row(this, "Run in the background", "Stops Android putting it to sleep", backgroundValue) {
            // Exempts the app from battery optimisation so Android stops putting it to sleep
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        screen.add(Card(this).apply {
            row(overlayRow)
            row(backgroundRow)
            row(Row(this@MainActivity, "Haptics", "A click as the halves meet, a settle on opening",
                Toggle(this@MainActivity, Haptics.isEnabled(this@MainActivity)) { Haptics.setEnabled(this@MainActivity, it); refresh() }))
        })

        actionSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.add(actionSlot, px(28))

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

    override fun onResume() {
        super.onResume()
        hingeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        refresh()
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    private fun start() {
        AutoStart.setEnabled(this, true)
        startForegroundService(Intent(this, FoldMotionService::class.java))
        HealJob.schedule(this)
        dial.postDelayed({ refresh() }, 400)
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
            appendLine("Style: ${MotionConfig.style(this@MainActivity).label} (${MotionConfig.CLOSING_EFFECT.name.lowercase()} / ${MotionConfig.OPENING_EFFECT.name.lowercase()})")
            appendLine("Starts after a restart: " + if (AutoStart.isEnabled(this@MainActivity)) "on" else "off")
            appendLine("Service last started: ${Status.describe(this@MainActivity, Status.STARTED)}")
            appendLine("Effect last played: ${Status.describe(this@MainActivity, Status.PLAYED)}")
            appendLine(Status.describeHeals(this@MainActivity))
            appendLine(Timing.describe(this@MainActivity, MotionConfig.EFFECT_MS * maxOf(scale, 0.01f)))
            append(Status.describeRun(this@MainActivity))
        }
    }

    override fun onSensorChanged(event: SensorEvent) = dial.setAngle(event.values[0])

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Each style's tile: a still of its signature moment. */
    private fun swatchFor(s: MotionConfig.Style): (Canvas, RectF) -> Unit = { c, r ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.BLACK)
        val cx = r.centerX(); val cy = r.centerY()
        fun glow(x: Float, y: Float, rad: Float, col: Int, a: Int) {
            p.shader = RadialGradient(x, y, rad, Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, p); p.shader = null
        }
        fun lamp(x: Float, dir: Float) {
            val ringR = r.height() * 0.17f
            glow(x, cy, ringR * 2.6f, Kit.ROSSO, 120)
            p.color = Color.rgb(255, 128, 112)
            for (i in 0 until 14) {
                val a = Math.PI * 2 * i / 14
                c.drawCircle(x + ringR * Math.cos(a).toFloat(), cy + ringR * Math.sin(a).toFloat(), ringR * 0.11f, p)
            }
            val end = cx - dir * r.width() * 0.05f
            val start = x + dir * ringR
            for (k in 0..6) c.drawCircle(start + (end - start) * k / 6f, cy - ringR * 0.2f, ringR * 0.1f * (1f - 0.4f * k / 6f), p)
        }
        when (s) {
            MotionConfig.Style.VISCONTI -> {
                glow(cx, cy, r.width() * 0.6f, Color.rgb(22, 128, 72), 200)
                p.color = Color.rgb(226, 240, 222)
                c.drawRect(cx - 0.75f, r.top, cx + 0.75f, r.bottom, p)
            }
            MotionConfig.Style.GIULIETTA -> {
                glow(cx, cy, r.width() * 0.6f, Color.rgb(22, 128, 72), 140)
                lamp(r.right - r.width() * 0.2f, -1f)
            }
            MotionConfig.Style.FANALE -> {
                lamp(r.left + r.width() * 0.2f, 1f)
                lamp(r.right - r.width() * 0.2f, -1f)
            }
        }
    }
}

/**
 * The hinge, live: the two halves drawn as a pair of leaves meeting at the hinge, the moving
 * one swinging with the phone, and the angle in large light numerals beside them.
 */
@SuppressLint("ViewConstructor")
class HingeDial(ctx: Context) : View(ctx) {
    private var angle = -1f
    private val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Kit.INK }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val numerals = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.INK; typeface = Kit.sans(ctx, 300) }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.MUTED; typeface = Kit.sans(ctx, 500); letterSpacing = 0.2f }
    private val r = RectF()

    fun setAngle(a: Float) { angle = a; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(150))

    override fun onDraw(c: Canvas) {
        val pad = context.px(22).toFloat()
        val h = height.toFloat()
        // The leaves: the fixed half lies flat to the right of the hinge, the other swings
        val hx = pad + context.px(70); val hy = h - pad - context.px(14)
        val len = context.px(66).toFloat()
        val a = if (angle < 0) 180f else angle.coerceIn(0f, 180f)
        arc.strokeWidth = context.px(1.5f).toFloat()
        arc.color = Kit.HAIRLINE
        r.set(hx - len, hy - len, hx + len, hy + len)
        c.drawArc(r, 180f, 180f, false, arc)
        arc.color = Kit.CHAMPAGNE
        c.drawArc(r, 360f - a, a, false, arc)
        leaf.strokeWidth = context.px(3).toFloat()
        leaf.alpha = 255
        c.drawLine(hx, hy, hx + len, hy, leaf)
        val rad = Math.toRadians(a.toDouble())
        leaf.color = Kit.CHAMPAGNE
        c.drawLine(hx, hy, hx + len * Math.cos(rad).toFloat(), hy - len * Math.sin(rad).toFloat(), leaf)
        leaf.color = Kit.INK
        c.drawCircle(hx, hy, context.px(3).toFloat(), numerals)
        // The figure
        val x = hx + len + context.px(36)
        caption.textSize = context.px(11).toFloat()
        c.drawText("HINGE, LIVE", x, pad + context.px(14), caption)
        numerals.textSize = context.px(64).toFloat()
        c.drawText(if (angle < 0) "--°" else "${angle.toInt()}°", x - context.px(3), hy + context.px(2), numerals)
    }
}
