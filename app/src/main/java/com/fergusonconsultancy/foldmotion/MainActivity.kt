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
            textSize = 48f
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

        setContentView(root)

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
        refresh()
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val blurOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager.isCrossWindowBlurEnabled
        status.text = buildString {
            appendLine(if (hingeSensor != null) "Hinge sensor: found" else "Hinge sensor: NOT found on this phone")
            appendLine(if (overlayOk) "Display over other apps: allowed" else "Display over other apps: tap the first button")
            appendLine(
                if (blurOk) "Live blur: supported"
                else "Live blur: off (check Settings > Accessibility > Visibility enhancements > Reduce transparency and blur). Frost only."
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        angleText.text = "Hinge: ${event.values[0].toInt()}°"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
