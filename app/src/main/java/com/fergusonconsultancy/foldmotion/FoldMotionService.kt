package com.fergusonconsultancy.foldmotion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

class FoldMotionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null

    private lateinit var windowManager: WindowManager
    private var overlay: FoldOverlayView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var blurSupported = false
    private var lastBlurRadius = -1

    private var angle = 0f
    private var anchorAngle = 0f
    private var lastMoveAt = 0L
    private var ticking = false

    // Which panel is showing, and when One UI last switched between them
    private var currentInner = false
    private var arrivalAt = 0L
    private var windowContext: Context? = null

    private val displayCallbacks = object : android.content.ComponentCallbacks {
        override fun onConfigurationChanged(config: android.content.res.Configuration) {
            val inner = FoldMath.isInnerShape(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
            if (inner != currentInner) {
                currentInner = inner
                arrivalAt = SystemClock.uptimeMillis()
                recordSwitchAngle(inner)
                // Paint the arriving screen dark and frosted on its very first frame
                overlay?.let {
                    it.visibility = View.VISIBLE
                    it.update(1f, FoldMath.clamp(angle / 180f), 1f)
                }
                setWindowAlpha(1f)
                startTicking()
            }
        }

        @Deprecated("Required override")
        override fun onLowMemory() = Unit
    }

    private val frameCallback = Choreographer.FrameCallback { onFrame() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopListening()
                Intent.ACTION_SCREEN_ON -> startListening()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (hingeSensor == null) {
            stopSelf()
            return
        }

        addOverlay()
        startListening()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopListening()
        runCatching { unregisterReceiver(screenReceiver) }
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && overlay != null) {
            runCatching { windowManager.removeCrossWindowBlurEnabledListener(blurListener) }
        }
        windowContext?.let { runCatching { it.unregisterComponentCallbacks(displayCallbacks) } }
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    // region Overlay

    private fun addOverlay() {
        // One logical display follows whichever panel is active, so a single
        // overlay on the default display covers both cover and inner screens.
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windowManager = windowContext.getSystemService(WindowManager::class.java)
        this.windowContext = windowContext

        // Watch for One UI switching between cover and inner screens
        val config = windowContext.resources.configuration
        currentInner = FoldMath.isInnerShape(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
        windowContext.registerComponentCallbacks(displayCallbacks)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = MotionConfig.MAX_WINDOW_ALPHA
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "FoldMotionOverlay"
        }

        overlay = FoldOverlayView(windowContext).also {
            it.visibility = View.GONE
            windowManager.addView(it, params)
        }

        // Blur can switch on and off while running (battery saver, accessibility),
        // so follow it live rather than checking once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            windowManager.addCrossWindowBlurEnabledListener(blurListener)
        }
    }

    private val blurListener = java.util.function.Consumer<Boolean> { enabled ->
        if (!enabled) applyBlur(0)
        blurSupported = enabled
        lastBlurRadius = -1
    }

    private fun applyBlur(radius: Int) {
        if (!blurSupported || radius == lastBlurRadius) return
        val view = overlay ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (radius > 0) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = radius
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                params.blurBehindRadius = 0
            }
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        lastBlurRadius = radius
    }

    // endregion

    // region Hinge sensor

    private fun startListening() {
        hingeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopListening() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        hideNow()
    }

    // On the Fold8 Ultra the hinge sensor only reports 0, 90 and 180 degrees.
    // Each step becomes a target, and `angle` glides smoothly towards it.
    private var rawAngle = 0f
    private var tweenFrom = 0f
    private var tweenStart = 0L
    private var lastStepAt = 0L

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values[0]
        val now = SystemClock.uptimeMillis()
        if (lastStepAt == 0L) {
            // First reading: jump straight there, no animation on start
            rawAngle = raw; angle = raw; anchorAngle = raw; lastStepAt = now
            return
        }
        if (abs(raw - anchorAngle) > MotionConfig.MOVE_THRESHOLD_DEG) {
            anchorAngle = raw
            tweenFrom = angle
            rawAngle = raw
            tweenStart = now
            lastStepAt = now
            lastMoveAt = now
            startTicking()
        }
    }

    private fun tweenActive(now: Long) = tweenStart != 0L && now - tweenStart < MotionConfig.TWEEN_MS

    private fun advanceTween(now: Long) {
        if (tweenStart == 0L) { angle = rawAngle; return }
        val t = FoldMath.clamp((now - tweenStart).toFloat() / MotionConfig.TWEEN_MS)
        angle = tweenFrom + (rawAngle - tweenFrom) * FoldMath.glide(t)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // endregion

    // region Animation loop

    private fun startTicking() {
        if (ticking) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame() {
        val view = overlay ?: run { ticking = false; return }
        val now = SystemClock.uptimeMillis()
        advanceTween(now)
        val progress = FoldMath.clamp(angle / 180f)

        // Fade the effect out once the hinge sits still, so Flex mode
        // (phone held half open) does not leave the screen blurred.
        val still = SystemClock.uptimeMillis() - lastMoveAt
        val settle = if (still < MotionConfig.HOLD_MS) 1f
        else 1f - FoldMath.glide(FoldMath.clamp((still - MotionConfig.HOLD_MS).toFloat() / MotionConfig.SETTLE_MS))

        val motion = if (currentInner) FoldMath.innerMotion(progress) else FoldMath.coverMotion(progress)

        // The screen One UI just switched to arrives frosted and clears
        val sinceSwitch = SystemClock.uptimeMillis() - arrivalAt
        val arrival = if (arrivalAt == 0L) 0f
        else 1f - FoldMath.glide(FoldMath.clamp(sinceSwitch.toFloat() / MotionConfig.ARRIVAL_MS))

        val intensity = maxOf(motion * settle, arrival)

        // Darkness: inner fades to black near the switch angle, and the
        // arriving screen lifts out of black at the start of its arrival.
        val closingDark = if (!currentInner) 0f else 1f - FoldMath.smooth(
            FoldMath.clamp(
                (angle - MotionConfig.CLOSE_FADE_END_DEG) /
                    (MotionConfig.CLOSE_FADE_START_DEG - MotionConfig.CLOSE_FADE_END_DEG)
            )
        )
        val arrivalDark = if (arrivalAt == 0L) 0f else 1f - FoldMath.glide(
            FoldMath.clamp(sinceSwitch.toFloat() / (MotionConfig.ARRIVAL_MS * MotionConfig.ARRIVAL_DARK_SHARE))
        )
        // settle stops the screen staying black if the phone is held nearly shut
        val darkness = maxOf(closingDark * settle, arrivalDark)

        // Full opacity is only allowed while dark, when nobody is tapping;
        // otherwise stay under Android's 80% touch-through limit.
        setWindowAlpha(if (darkness > 0.05f) 1f else MotionConfig.MAX_WINDOW_ALPHA)

        if (intensity > 0.002f || darkness > 0.002f || tweenActive(now)) {
            if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
            view.update(intensity, progress, darkness)
            val radiusPx = (MotionConfig.PEAK_BLUR_DP * resources.displayMetrics.density * intensity).roundToInt()
            applyBlur(radiusPx)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            hideNow()
            ticking = false
        }
    }

    private fun hideNow() {
        overlay?.let {
            it.update(0f, 0f, 0f)
            it.visibility = View.GONE
        }
        applyBlur(0)
        setWindowAlpha(MotionConfig.MAX_WINDOW_ALPHA)
    }

    private fun setWindowAlpha(alpha: Float) {
        val view = overlay ?: return
        if (!::params.isInitialized || params.alpha == alpha) return
        params.alpha = alpha
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** Saves the hinge angle at each screen switch so the fade can be tuned to this phone. */
    private fun recordSwitchAngle(toInner: Boolean) {
        // The sensor is stepped, so also record how long after the last
        // step the screens switched. That timing is what the fade needs.
        val dir = if (toInner) "opening" else "closing"
        val msAfterStep = if (lastStepAt == 0L) -1L else SystemClock.uptimeMillis() - lastStepAt
        getSharedPreferences("fold_motion", MODE_PRIVATE).edit()
            .putFloat("switch_${dir}_deg", rawAngle)
            .putLong("switch_${dir}_ms", msAfterStep)
            .apply()
    }

    // endregion

    // region Foreground notification

    private fun startAsForeground() {
        val channelId = "fold_motion"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Fold Motion", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("Fold Motion is running")
            .setContentText("Animating your fold and unfold")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    // endregion
}
