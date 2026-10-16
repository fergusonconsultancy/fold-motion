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
 * Closing: De Stijl, One Plane in Visconti green on the main screen, with
 * haptic ticks timed to the light. The moment One UI switches to the cover,
 * the overlay is cleared so the cover arrives untouched.
 *
 * Opening: Crease Split, played as a reveal on the main screen. When the
 * hinge reports 90 degrees on the cover, the overlay goes black at once
 * (ARMED), so the main screen is already covered when it lights. After One UI
 * switches, the reveal starts at once (REVEALING). Timers that do not rely
 * on Android's frame signal guarantee the overlay is gone on time.
 *
 * Trigger: Android's hinge sensor, which on the Fold8 Ultra only reports
 * 0, 90 and 180 degrees.
 */
class FoldMotionService : Service(), SensorEventListener {

    private enum class Phase { IDLE, PLAYING, HOLDING, FADING, ARMED, WAITING_PANEL, REVEALING }

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private var hingeSensorPlain: Sensor? = null

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
    private var revealMs = MotionConfig.REVEAL_MS.toFloat()
    private var switchedAt = 0L
    private var fadeFrom = 1f
    private var ticking = false

    private val haptics by lazy { Haptics(this) }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** If Android has delivered no frames shortly after a run starts, say so in the log. */
    private val frameCheck = Runnable {
        if (phase == Phase.PLAYING && stats.result() == null) {
            EventLog.add(this, "WARNING: Android delivered no animation frames in ${FRAME_CHECK_MS}ms")
        }
    }

    /**
     * Hard stops that do not depend on Android's frame signal. On 14 Oct the
     * frame signal stalled for 5 to 10 seconds after the switch to the main
     * screen, which left it dimmed; these clear the overlay on time regardless.
     */
    private val revealDeadline = Runnable {
        if (phase == Phase.REVEALING) {
            EventLog.add(this, "Reveal deadline: frames stalled after ${overlay?.drawCount ?: 0} drawn, screen cleared")
            finish()
        }
    }
    private val armDeadline = Runnable {
        if (phase == Phase.ARMED) {
            EventLog.add(this, "No switch to the main screen within ${MotionConfig.ARM_TIMEOUT_MS}ms: cover cleared")
            finish()
        }
    }
    private var firstRevealFrameLogged = false

    companion object {
        /** True while the service is alive in this process; shown on the home screen. */
        @Volatile var running = false

        /** Idle window size in pixels: present, so the app keeps its frame signal, but invisible. */
        private const val IDLE_SIZE = 1
        private const val FRAME_CHECK_MS = 250L
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
                Intent.ACTION_SCREEN_OFF -> { stopListening(); EventLog.add(context, "Screen off, paused") }
                Intent.ACTION_SCREEN_ON -> { startListening(); EventLog.add(context, "Screen on, listening") }
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
            } else if (isInteractive() && phase != Phase.ARMED && !catchUpDue()) {
                // Opened without a reveal: a low settle as the inner screen arrives
                haptics.settle()
            }

            playStartAt = 0L
            EventLog.add(this@FoldMotionService, if (inner) "One UI switched to the inner screen" else "One UI switched to the cover")
            recordSwitch(inner)
            if (inner && phase == Phase.ARMED) {
                // Reveal straight away; no waiting on the panel, which stalled on 14 Oct
                switchedAt = now
                startReveal()
                return
            }
            if (inner && catchUpDue()) {
                // One UI switched before the hinge sensor reported the opening (seen on
                // 15 Oct: the switch arrived first, the 0 to 90 degree step after it).
                // Cover and reveal now rather than skip the effect.
                EventLog.add(this@FoldMotionService, "Switch came before the hinge reported opening: catch-up reveal")
                switchedAt = now
                overlay?.mode = FoldOverlayView.Mode.OPEN
                overlay?.show(1f)
                enter(Phase.ARMED)
                startReveal()
                return
            }
            // Otherwise the arriving screen is always clean
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
        installCrashRecorder()

        if (!Settings.canDrawOverlays(this)) {
            EventLog.add(this, "Service stopped: display over other apps not allowed")
            stopSelf(); return
        }

        sensorManager = getSystemService(SensorManager::class.java)
        // Both variants of the hinge sensor. The wake-up variant alone may not be
        // delivered to an app in the background on some phones; listening to both
        // and ignoring duplicates costs nothing.
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, true)
        hingeSensorPlain = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, false)
        if (hingeSensor == null && hingeSensorPlain == null) {
            hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        }
        if (hingeSensor == null && hingeSensorPlain == null) {
            EventLog.add(this, "Service stopped: no hinge sensor")
            stopSelf(); return
        }
        EventLog.add(
            this,
            "Service started (hinge sensors: wake-up ${if (hingeSensor != null) "yes" else "no"}, " +
                "plain ${if (hingeSensorPlain != null) "yes" else "no"})"
        )

        addOverlay()
        startListening()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        EventLog.add(this, "Service stopped")
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

        // Starts as an invisible 1x1 pixel. It is never hidden: an app with nothing
        // visible on screen stops receiving Android's frame signal (vsync), which
        // is why the effect never ticked while another app was in front.
        params = WindowManager.LayoutParams(
            IDLE_SIZE,
            IDLE_SIZE,
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
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
        }

        val config = ctx.resources.configuration
        currentInner = FoldMath.isInnerShape(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
        notifiedInner = currentInner
        ctx.registerComponentCallbacks(displayCallbacks)

        overlay = FoldOverlayView(ctx).also {
            it.visibility = View.VISIBLE
            windowManager.addView(it, params)
        }
    }

    /**
     * While an effect is on screen: full screen, full opacity so black is truly
     * black, and the panel's top refresh rate so the sweep is smooth. Between
     * effects: an invisible 1x1 pixel at 80% opacity, which costs nothing to
     * composite but keeps the app on Android's frame schedule.
     */
    private fun setActiveWindow(active: Boolean) {
        val view = overlay ?: return
        if (!::params.isInitialized) return
        val alpha = if (active) 1f else MotionConfig.IDLE_WINDOW_ALPHA
        val hz = if (active) display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f else 0f
        // Opening uses a fixed square larger than either panel, so the window is not
        // resized when One UI switches screens. A resize means a new, empty buffer
        // until the next frame is drawn, which is when content could show through.
        val size = when {
            !active -> IDLE_SIZE
            overlay?.mode == FoldOverlayView.Mode.OPEN -> oversize()
            else -> WindowManager.LayoutParams.MATCH_PARENT
        }
        if (params.alpha == alpha && params.preferredRefreshRate == hz && params.width == size) return
        params.alpha = alpha
        params.preferredRefreshRate = hz
        params.width = size
        params.height = size
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** The longest side of any panel this display can show, so one window covers both screens. */
    private fun oversize(): Int {
        val modes = display?.supportedModes?.maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
        val now = runCatching {
            val b = windowManager.maximumWindowMetrics.bounds
            maxOf(b.width(), b.height())
        }.getOrDefault(0)
        return maxOf(modes, now, MotionConfig.OVERSIZE_MIN_PX)
    }

    // endregion

    // region Hinge sensor

    private fun startListening() {
        val a = hingeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
        val b = hingeSensorPlain?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
        if (!a && !b) EventLog.add(this, "Could not register for hinge events")
    }

    /** Records the reason if the service ever crashes, then lets Android handle it as normal. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(applicationContext, previous))
    }

    private class CrashRecorder(
        val context: Context,
        val next: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val where = e.stackTrace.firstOrNull { it.className.contains("foldmotion") }
                EventLog.add(context, "CRASH ${e.javaClass.simpleName}: ${e.message} at ${where?.fileName}:${where?.lineNumber}")
                // apply() is asynchronous; give it a moment to reach disk before the process dies
                Thread.sleep(150)
            }
            next?.uncaughtException(t, e)
        }
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

        // Record what was seen and decided, for the home screen log
        val step = "Hinge ${previous.roundToInt()}° to ${angle.roundToInt()}°"
        val decision = when {
            !closing && phase == Phase.ARMED -> "still opening, cover already blacked out"
            !closing && innerNow -> "opening, main screen already showing"
            !closing && phase != Phase.IDLE -> "opening, another effect is running"
            !closing && scale <= 0f -> "opening, but animations are off in Developer options: skipped"
            !closing -> "opening on the cover: ARMED, reveal to follow"
            phase == Phase.ARMED -> "closed again before the switch: cover cleared"
            !innerNow -> "closing, but the cover is showing: skipped"
            phase != Phase.IDLE -> "still closing, effect already playing"
            scale <= 0f -> "closing, but animations are off in Developer options: skipped"
            else -> "closing on the inner screen: PLAYED"
        }
        EventLog.add(this, "$step, $decision")

        when {
            // Opening from the cover: black out now, so the main screen is covered when it lights
            phase == Phase.IDLE && !innerNow && !closing && scale > 0f -> {
                overlay?.mode = FoldOverlayView.Mode.OPEN
                overlay?.show(1f)
                enter(Phase.ARMED)
                mainHandler.removeCallbacks(armDeadline)
                mainHandler.postDelayed(armDeadline, MotionConfig.ARM_TIMEOUT_MS)
            }
            // Changed their mind and closed again while still on the cover
            phase == Phase.ARMED && closing -> finish()
            // Closing the inner screen
            phase == Phase.IDLE && innerNow && closing -> {
                overlay?.mode = FoldOverlayView.Mode.CLOSE
                // Fitted to this owner's closing speed once enough closes are learned
                playMs = Timing.fittedMs(this, MotionConfig.EFFECT_MS * scale)
                playStartAt = now
                stats.reset()
                overlay?.drawCount = 0
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
    /** Opening noticed by the switch alone: phone last seen closed, nothing playing, animations on. */
    private fun catchUpDue(): Boolean =
        MotionConfig.CATCH_UP_REVEAL && phase == Phase.IDLE && lastAngle in 0f..1f &&
            isInteractive() && animScale() > 0f

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
        setActiveWindow(true)
        if (next == Phase.PLAYING) {
            mainHandler.removeCallbacks(frameCheck)
            mainHandler.postDelayed(frameCheck, FRAME_CHECK_MS)
        }
        if (!ticking) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun startReveal() {
        mainHandler.removeCallbacks(armDeadline)
        revealMs = MotionConfig.REVEAL_MS * animScale().coerceAtLeast(0.01f)
        stats.reset()
        overlay?.drawCount = 0
        firstRevealFrameLogged = false
        if (isInteractive()) haptics.settle()
        Status.mark(this, Status.PLAYED)
        enter(Phase.REVEALING)
        // Ask for a frame now, in case the switch dropped the pending one
        overlay?.show(1f)
        // Draw the reveal against the main screen's real size, not the oversized window
        runCatching {
            val b = windowManager.currentWindowMetrics.bounds
            overlay?.setContentSize(b.width(), b.height())
        }
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
        ticking = true
        mainHandler.removeCallbacks(revealDeadline)
        mainHandler.postDelayed(revealDeadline, revealMs.toLong() + MotionConfig.REVEAL_GRACE_MS)
        EventLog.add(this, "Revealing (display state ${display?.state ?: -1})")
    }

    private fun saveRun(completedPercent: Int) {
        val r = stats.result()
        r?.let { Status.saveRun(this, it, playMs.roundToInt(), completedPercent) }
        logOverlayCheck(r?.frames ?: 0)
    }

    /**
     * Records whether Android actually showed the overlay during the run:
     * attached to a window, the window visible, and how many frames the view
     * really drew against how many the animation asked for.
     */
    private fun logOverlayCheck(framesRequested: Int) {
        val v = overlay ?: run { EventLog.add(this, "Overlay check: no overlay view"); return }
        val win = when (v.windowVisibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "INVISIBLE"
            else -> "GONE"
        }
        EventLog.add(
            this,
            "Overlay check: attached ${if (v.isAttachedToWindow) "yes" else "NO"}, window $win, " +
                "size ${v.width}x${v.height}, drew ${v.drawCount} of $framesRequested frames"
        )
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
            Phase.ARMED, Phase.WAITING_PANEL -> {
                // Cover held black until One UI switches; armDeadline clears it if it never does
                view.show(1f)
            }
            Phase.REVEALING -> {
                if (!firstRevealFrameLogged) {
                    firstRevealFrameLogged = true
                    val lag = SystemClock.uptimeMillis() - switchedAt
                    if (lag > 100) EventLog.add(this, "First reveal frame ${lag}ms after the switch")
                    // Start the clock on the first frame Android actually delivers, so a slow
                    // panel switch cannot eat the start of the reveal
                    phaseAt = SystemClock.uptimeMillis()
                }
                val k = FoldMath.clamp(elapsed() / revealMs)
                view.show(1f - FoldMath.glide(k))
                stats.onFrame(frameTimeNanos)
                if (k >= 1f) {
                    val r = stats.result()
                    EventLog.add(
                        this,
                        "Reveal done: drew ${view.drawCount} of ${r?.frames ?: 0} frames" +
                            (r?.let { ", ${it.fps}fps, ${it.dropped} dropped" } ?: "")
                    )
                    finish(); return
                }
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
        mainHandler.removeCallbacks(frameCheck)
        mainHandler.removeCallbacks(revealDeadline)
        mainHandler.removeCallbacks(armDeadline)
        overlay?.clearAll()
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
            .setContentText("Ruled Surface closing, Nave of Light opening")
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
