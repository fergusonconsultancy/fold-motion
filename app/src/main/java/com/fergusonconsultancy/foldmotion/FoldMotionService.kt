package com.fergusonconsultancy.foldmotion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import com.fergusonconsultancy.foldmotion.FoldOverlayView.Shape
import kotlin.math.abs
import kotlin.math.roundToInt

class FoldMotionService : Service(), SensorEventListener {

    private enum class Phase { IDLE, FILLING, HOLDING, REVEALING, FADING }

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null

    private lateinit var windowManager: WindowManager
    private var windowContext: Context? = null
    private var overlay: FoldOverlayView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var display: Display? = null

    private var blurSupported = false
    private var lastBlurRadius = -1

    private var lastAngle = -1f
    private var lastStepAt = 0L
    private var currentInner = false

    private var phase = Phase.IDLE
    private var phaseAt = 0L
    private var fillShape = Shape.FULL
    private var revealShape = Shape.FULL
    private var ticking = false

    private val frameCallback = Choreographer.FrameCallback { onFrame() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopListening()
                Intent.ACTION_SCREEN_ON -> startListening()
            }
        }
    }

    /** One UI switching between cover and inner screens. */
    private val displayCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(config: Configuration) {
            val inner = FoldMath.isInnerShape(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
            if (inner == currentInner) return
            currentInner = inner
            recordSwitch(inner)
            // Arriving screen: fully frosted on its first frame, then clears
            revealShape = if (inner) Shape.REVEAL_FROM_CREASE else Shape.REVEAL_FROM_HINGE
            enter(Phase.REVEALING)
            overlay?.let {
                it.visibility = View.VISIBLE
                it.show(Shape.FULL, 0f, 1f, 0f)
            }
        }

        @Deprecated("Required override")
        override fun onLowMemory() = Unit
    }

    private val blurListener = java.util.function.Consumer<Boolean> { enabled ->
        if (!enabled) applyBlur(0)
        blurSupported = enabled
        lastBlurRadius = -1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (hingeSensor == null) { stopSelf(); return }

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

    // region Overlay window

    private fun addOverlay() {
        // One logical display follows whichever panel is active
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        display = d
        val ctx = createWindowContext(d, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windowContext = ctx
        windowManager = ctx.getSystemService(WindowManager::class.java)

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

        val config = ctx.resources.configuration
        currentInner = FoldMath.isInnerShape(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
        ctx.registerComponentCallbacks(displayCallbacks)

        overlay = FoldOverlayView(ctx).also {
            it.visibility = View.GONE
            windowManager.addView(it, params)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            windowManager.addCrossWindowBlurEnabledListener(blurListener)
        }
    }

    /**
     * Asks for the panel's top refresh rate while animating. Recordings showed
     * the cover dropping to about 35fps mid-transition without this.
     */
    private fun requestHighRefresh(on: Boolean) {
        val view = overlay ?: return
        val target = if (on) {
            display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
        } else 0f
        if (params.preferredRefreshRate == target) return
        params.preferredRefreshRate = target
        runCatching { windowManager.updateViewLayout(view, params) }
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
        hingeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun stopListening() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        finish()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values[0]
        val previous = lastAngle
        lastAngle = angle
        if (previous < 0f || abs(angle - previous) < MotionConfig.MOVE_THRESHOLD_DEG) return
        lastStepAt = SystemClock.uptimeMillis()

        val closing = angle < previous
        when {
            // Folding the inner screen shut: frost spreads from the crease
            currentInner && closing && phase != Phase.REVEALING -> {
                if (phase == Phase.IDLE || phase == Phase.FADING) {
                    fillShape = Shape.FILL_FROM_CREASE
                    enter(Phase.FILLING)
                }
            }
            // Opening from the cover: frost sweeps in from the hinge edge
            !currentInner && !closing && phase != Phase.REVEALING -> {
                if (phase == Phase.IDLE || phase == Phase.FADING) {
                    fillShape = Shape.FILL_FROM_HINGE
                    enter(Phase.FILLING)
                }
            }
            // Changed your mind mid-fold: let the glass go
            (phase == Phase.FILLING || phase == Phase.HOLDING) -> enter(Phase.FADING)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // endregion

    // region Choreography

    /** Developer options > Animator duration scale. 0 means animations off. */
    private fun animScale(): Float = runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    private fun enter(next: Phase) {
        if (animScale() <= 0f) { finish(); return }
        phase = next
        phaseAt = SystemClock.uptimeMillis()
        requestHighRefresh(true)
        if (!ticking) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame() {
        val view = overlay ?: run { ticking = false; return }
        val scale = animScale().coerceAtLeast(0.01f)
        val elapsed = (SystemClock.uptimeMillis() - phaseAt).toFloat()
        fun progress(ms: Long) = FoldMath.clamp(elapsed / (ms * scale))

        var glass = 0f
        when (phase) {
            Phase.IDLE -> { finish(); return }
            Phase.FILLING -> {
                val t = FoldMath.glide(progress(MotionConfig.FILL_MS))
                view.show(fillShape, t, 1f, 1f - t)
                glass = t
                if (t >= 1f) enter(Phase.HOLDING)
            }
            Phase.HOLDING -> {
                view.show(Shape.FULL, 1f, 1f, 0f)
                glass = 1f
                if (elapsed > MotionConfig.HOLD_TIMEOUT_MS * scale) enter(Phase.FADING)
            }
            Phase.REVEALING -> {
                val t = FoldMath.glide(progress(MotionConfig.REVEAL_MS))
                view.show(revealShape, t, 1f, 1f - t * 0.6f)
                glass = 1f - t
                if (t >= 1f) { finish(); return }
            }
            Phase.FADING -> {
                val t = FoldMath.glide(progress(MotionConfig.FADE_MS))
                view.show(Shape.FULL, 1f, 1f - t, 0f)
                glass = 1f - t
                if (t >= 1f) { finish(); return }
            }
        }

        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        applyBlur((MotionConfig.PEAK_BLUR_DP * resources.displayMetrics.density * glass).roundToInt())
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun finish() {
        phase = Phase.IDLE
        ticking = false
        overlay?.let {
            it.clearAll()
            it.visibility = View.GONE
        }
        applyBlur(0)
        if (::params.isInitialized) requestHighRefresh(false)
    }

    private fun recordSwitch(toInner: Boolean) {
        val dir = if (toInner) "opening" else "closing"
        val ms = if (lastStepAt == 0L) -1L else SystemClock.uptimeMillis() - lastStepAt
        getSharedPreferences("fold_motion", MODE_PRIVATE).edit()
            .putFloat("switch_${dir}_deg", lastAngle)
            .putLong("switch_${dir}_ms", ms)
            .apply()
    }

    // endregion

    // region Foreground notification

    private fun startAsForeground() {
        val channelId = "fold_motion"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
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
