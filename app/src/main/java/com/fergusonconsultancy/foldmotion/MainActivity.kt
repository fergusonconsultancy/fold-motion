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

    private val ink = Color.rgb(236, 232, 223)
    private val muted = Color.rgb(154, 151, 166)
    private val violet = Color.rgb(179, 166, 242)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

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
            AutoStart.setEnabled(this, true)
            startForegroundService(Intent(this, FoldMotionService::class.java))
            refresh()
        })
        root.addView(button("Stop animation") {
            AutoStart.setEnabled(this, false)
            stopService(Intent(this, FoldMotionService::class.java))
            refresh()
        })
        root.addView(button("Let it run in the background") {
            // Exempts the app from battery optimisation so Android stops putting it to sleep
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }.onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        hapticsButton = button(hapticsLabel()) {
            Haptics.setEnabled(this, !Haptics.isEnabled(this))
            hapticsButton.text = hapticsLabel()
            refresh()
        }
        root.addView(hapticsButton)

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

    private lateinit var hapticsButton: Button

    private fun hapticsLabel() =
        if (Haptics.isEnabled(this)) "Haptics: on (tap to turn off)" else "Haptics: off (tap to turn on)"

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
        refresh()
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
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
            val scale = runCatching {
                Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
            appendLine(
                if (scale <= 0f) "Animator duration scale: off, so the transition is skipped"
                else "Animator duration scale: ${scale}x (Developer options)"
            )
            val topHz = display?.supportedModes?.maxOfOrNull { it.refreshRate }?.toInt()
            appendLine("Animates at up to ${topHz ?: "?"}Hz")
            appendLine("Effect: Louvre Signature in Visconti green when closing. Opening is left clean.")
            appendLine(
                if (AutoStart.isEnabled(this@MainActivity)) "Starts automatically after a restart: on"
                else "Starts automatically after a restart: off (tap Start animation)"
            )
            appendLine()
            val exempt = getSystemService(android.os.PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(packageName) == true
            appendLine(
                if (exempt) "Background running: allowed"
                else "Background running: restricted (tap Let it run in the background)"
            )
            appendLine(if (FoldMotionService.running) "Service: running" else "Service: NOT running")
            appendLine("Service last started: ${Status.describe(this@MainActivity, Status.STARTED)}")
            appendLine("Effect last played: ${Status.describe(this@MainActivity, Status.PLAYED)}")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        angleText.text = "Hinge: ${event.values[0].toInt()}°"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
