package com.fergusonconsultancy.foldmotion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), SensorEventListener {

    private lateinit var status: TextView
    private lateinit var angleText: TextView
    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private var fineSensor: Sensor? = null
    private var stdValue = "--"
    private var fineValue = "no data yet"

    private val ink = Color.rgb(236, 232, 223)
    private val muted = Color.rgb(154, 151, 166)
    private val violet = Color.rgb(179, 166, 242)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        fineSensor = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .firstOrNull { it.stringType == MotionConfig.SAMSUNG_FOLDING_ANGLE }

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(15, 16, 20))
        }

        root.addView(TextView(this).apply {
            text = "Fold Motion"
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        })

        angleText = TextView(this).apply {
            text = "Hinge: --"
            textSize = 26f
            setTextColor(violet)
            setPadding(0, pad, 0, pad / 2)
        }
        root.addView(angleText)

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(muted)
            setLineSpacing(0f, 1.3f)
        }
        root.addView(status)

        root.addView(button("Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("Start animation") {
            startForegroundService(Intent(this, FoldMotionService::class.java))
            refresh()
        })
        root.addView(button("Stop animation") {
            stopService(Intent(this, FoldMotionService::class.java))
            refresh()
        })

        // Scrolls, so a long sensor list never pushes the buttons off screen
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.rgb(15, 16, 20))
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
        gravity = Gravity.CENTER
    }

    override fun onResume() {
        super.onResume()
        hingeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        fineSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            windowManager.addCrossWindowBlurEnabledListener(blurListener)
        }
        refresh()
    }

    private val blurListener = java.util.function.Consumer<Boolean> { refresh() }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            windowManager.removeCrossWindowBlurEnabledListener(blurListener)
        }
        super.onPause()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val blurOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager.isCrossWindowBlurEnabled
        status.text = buildString {
            appendLine(if (hingeSensor != null) "Hinge sensor: found" else "Hinge sensor: NOT found on this phone")
            appendLine(if (overlayOk) "Display over other apps: allowed" else "Display over other apps: tap the first button")
            val prefs = getSharedPreferences("fold_motion", MODE_PRIVATE)
            for (dir in listOf("closing", "opening")) {
                val deg = prefs.getFloat("switch_${dir}_deg", -1f)
                val ms = prefs.getLong("switch_${dir}_ms", -1L)
                appendLine(
                    "Switch when $dir: " +
                        if (deg < 0) "not seen yet" else "${deg.toInt()}°, ${ms}ms after the last hinge step"
                )
            }
            appendLine()
            appendLine("Hinge-related sensors on this phone:")
            val keywords = listOf("hinge", "fold", "angle", "flip", "hall", "posture")
            sensorManager.getSensorList(Sensor.TYPE_ALL)
                .filter { s -> keywords.any { s.name.contains(it, ignoreCase = true) || s.stringType.contains(it, ignoreCase = true) } }
                .forEach { appendLine("• ${it.name} (${it.stringType}, type ${it.type}, resolution ${it.resolution})") }
            appendLine(
                if (blurOk) "Live blur: supported"
                else "Live blur: off. Frost only until it turns on.\n" +
                    "Check: Power saving off, Reduce transparency and blur off, " +
                    "and Developer options > Allow window-level blurs on."
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor == fineSensor) {
            fineValue = "%.1f°".format(event.values[0])
        } else {
            stdValue = "${event.values[0].toInt()}°"
        }
        angleText.text = "Samsung fine: $fineValue\nAndroid standard: $stdValue"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
