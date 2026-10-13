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
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Plays Louvre Signature in Visconti green on the inner screen as the phone closes,
 * with haptic ticks timed to the light. Opening is left clean.
 *
 * Trigger: Android's hinge sensor, which on the Fold8 Ultra only reports
 * 0, 90 and 180 degrees. The effect starts when the inner screen sees the
 * hinge drop. The moment One UI switches panels, the overlay is cleared so
 * the cover arrives untouched.
 */
class FoldMotionService : Service(), SensorEventListener {

    private enum class Phase { IDLE, PLAYING, HOLDING, FADING }

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null

    private lateinit var windowManager: WindowManager
    private var windowContext: Context? = null
    private var overlay: FoldOverlayView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var display: Display? = null

    private var lastAngle = -1f
    private var lastStepAt = 0L
    private var currentInner = false

    private var phase = Phase.IDLE
    private var phaseAt = 0L
    private var playMs = MotionConfig.EFFECT_MS.toFloat()
    private var fadeFrom = 1f
    private var ticking = false

    private val haptics by lazy { Haptics(this) }

    companion object {
        /** True while the service is alive in this process; shown on the home screen. */
        @Volatile var running = false
    }
    private var tickDone = false
    private var clickDone = false

    /** When the current close's effect started, for learning the switch timing. */
    private var playStartAt = 0L
    private val stats = FrameStats()

    /** Screen state as last reported by One UI's switch notifications; used only to detect switches. */
    private var notifiedInner = false

    private val frameCallback = Choreographer.FrameCallback { t -> onFrame(t) }

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
            if (inner == notifiedInner) return
            notifiedInner = inner
            currentInner = inner
            val now = SystemClock.uptimeMillis()

            if (!inner) {
                // Closed: learn from this close, and record how the run went
                if (playStartAt != 0L && (phase == Phase.PLAYING || phase == Phase.HOLDING)) {
                    Timing.record(this@FoldMotionService, now - playStartAt)
                    if (phase == Phase.PLAYING) {
                        saveRun((elapsed() / playMs * 100f).roundToInt().coerceIn(0, 99))
                    }
                }
            } else if (isInteractive()) {
                // Opened: a low settle as the inner screen arrives
                haptics.settle()
            }

            playStartAt = 0L
            recordSwitch(inner)
            // The arriving screen is always clean
            finish()
        }

        @Deprecated("Required override")
        override fun onLowMemory() = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        running = true
        Status.mark(this, Status.STARTED)
        HealJob.schedule(this)

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
        windowContext?.let { runCatching { it.unregisterComponentCallbacks(displayCallbacks) } }
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        running = false
        super.onDestroy()
    }

    /**
     * Reads the shape of the screen actually showing right now, rather than
     * relying only on One UI's switch notifications, which can be missed (for
     * example when the phone closes as the screen locks). A missed one used to
     * leave the app thinking the cover was showing, so closing never triggered.
     */
    private fun innerShowingNow(): Boolean = runCatching {
        val b = windowManager.currentWindowMetrics.bounds
        FoldMath.isInnerShape(b.width().toFloat(), b.height().toFloat())
    }.getOrDefault(currentInner)

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
            alpha = MotionConfig.IDLE_WINDOW_ALPHA
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "FoldMotionOverlay"
        }

        val config = ctx.resources.configuration
        currentInner = FoldMath.isInnerShape(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
        notifiedInner = currentInner
        ctx.registerComponentCallbacks(displayCallbacks)

        overlay = FoldOverlayView(ctx).also {
            it.visibility = View.GONE
            windowManager.addView(it, params)
        }
    }

    /**
     * While an effect is on screen: full opacity so black is truly black, and
     * the panel's top refresh rate so the sweep is smooth. Both are released
     * the moment the effect ends.
     */
    private fun setActiveWindow(active: Boolean) {
        val view = overlay ?: return
        if (!::params.isInitialized) return
        val alpha = if (active) 1f else MotionConfig.IDLE_WINDOW_ALPHA
        val hz = if (active) display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f else 0f
        if (params.alpha == alpha && params.preferredRefreshRate == hz) return
        params.alpha = alpha
        params.preferredRefreshRate = hz
        runCatching { windowManager.updateViewLayout(view, params) }
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
        val now = SystemClock.uptimeMillis()
        lastStepAt = now

        // Watchdog: nothing should ever run this long. If a sequence got stuck,
        // clear it, otherwise it would silently block every fold after it.
        if (phase != Phase.IDLE && now - phaseAt > MotionConfig.WATCHDOG_MS) finish()

        // Resync which screen is showing while nothing is playing
        val innerNow = innerShowingNow()
        if (phase == Phase.IDLE) currentInner = innerNow

        val closing = angle < previous
        val scale = animScale()
        when {
            // Closing the inner screen: the only direction with an effect
            phase == Phase.IDLE && innerNow && closing -> {
                // Fitted to this owner's closing speed once enough closes are learned
                playMs = Timing.fittedMs(this, MotionConfig.EFFECT_MS * scale)
                playStartAt = now
                stats.reset()
                tickDone = false
                clickDone = false
                Status.mark(this, Status.PLAYED)
                enter(Phase.PLAYING)
            }
            // Opened back up mid-fold: let the blades go
            (phase == Phase.PLAYING || phase == Phase.HOLDING) && currentInner && !closing -> {
                fadeFrom = currentProgress()
                enter(Phase.FADING)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // endregion

    // region Animation

    /** Developer options > Animator duration scale. 0 means animations off. */
    private fun animScale(): Float = runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    private fun elapsed() = (SystemClock.uptimeMillis() - phaseAt).toFloat()

    private fun currentProgress(): Float = when (phase) {
        Phase.PLAYING -> FoldMath.glide(FoldMath.clamp(elapsed() / playMs))
        Phase.HOLDING -> 1f
        else -> 0f
    }

    private fun enter(next: Phase) {
        if (animScale() <= 0f) { finish(); return }
        phase = next
        phaseAt = SystemClock.uptimeMillis()
        overlay?.visibility = View.VISIBLE
        setActiveWindow(true)
        if (!ticking) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun saveRun(completedPercent: Int) {
        stats.result()?.let { Status.saveRun(this, it, playMs.roundToInt(), completedPercent) }
    }

    private fun isInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun onFrame(frameTimeNanos: Long) {
        val view = overlay ?: run { ticking = false; return }
        val scale = animScale().coerceAtLeast(0.01f)
        val t = elapsed()

        when (phase) {
            Phase.IDLE -> { finish(); return }
            Phase.PLAYING -> {
                val k = FoldMath.clamp(t / playMs)
                val p = FoldMath.glide(k)
                view.show(p)
                stats.onFrame(frameTimeNanos)
                // Haptics timed to the light: tick as the blades start, click on the pulse
                if (!tickDone && p >= MotionConfig.TICK_AT) { tickDone = true; haptics.tick() }
                if (!clickDone && p >= MotionConfig.CLICK_AT) { clickDone = true; haptics.click() }
                if (k >= 1f) {
                    saveRun(100)
                    enter(Phase.HOLDING)
                }
            }
            Phase.HOLDING -> {
                // Blades shut and black until One UI switches to the cover
                view.show(1f)
                if (t > MotionConfig.HOLD_TIMEOUT_MS * scale) { fadeFrom = 1f; enter(Phase.FADING) }
            }
            Phase.FADING -> {
                val k = FoldMath.clamp(t / (MotionConfig.FADE_MS * scale))
                view.show(fadeFrom, 1f - FoldMath.glide(k))
                if (k >= 1f) { finish(); return }
            }
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun finish() {
        phase = Phase.IDLE
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        overlay?.let {
            it.clearAll()
            it.visibility = View.GONE
        }
        setActiveWindow(false)
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
            .setContentText("Louvre Signature when closing")
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
