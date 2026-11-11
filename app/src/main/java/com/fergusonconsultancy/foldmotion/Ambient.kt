package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.ln

/**
 * The room's light, as the lamps should answer it (11 Nov 2026). A real lamp looks different by
 * day and by night: in sunlight you see the housing and crisp, bloom-free light; at dusk a halo
 * forms and the unlit elements hold embers; in the dark the housing vanishes and only light is
 * left, blooming and casting a glow on what is around it.
 *
 * [darkness] runs from 0 (daylight) to 1 (a dark room). It comes from the ambient light sensor,
 * read continuously while the screen is on, so a fresh reading is waiting when a fold begins
 * (the sensor's first reading after registering can lag). It needs no permission and costs very
 * little. With no recent reading, or with the setting off, the clock stands in.
 */
object Ambient : SensorEventListener {
    /** A reading older than this is not trusted: the phone may have changed rooms. */
    private const val FRESH_MS = 10 * 60_000L
    /** Lux at and above which it is daylight, at and below which it is dark (log scale between). */
    private const val DAY_LUX = 1000f
    private const val DARK_LUX = 5f

    @Volatile private var smoothed = Float.NaN
    @Volatile private var lastAt = 0L
    @Volatile var lux = Float.NaN
        private set

    fun start(sm: SensorManager) {
        val s = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        runCatching { sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop(sm: SensorManager) = runCatching { sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values.firstOrNull() ?: return
        lux = v
        val d = fromLux(v)
        // Smoothed so a hand passing over the sensor doesn't flip the stage
        smoothed = if (smoothed.isNaN() || SystemClock.elapsedRealtime() - lastAt > FRESH_MS) d else smoothed + (d - smoothed) * 0.25f
        lastAt = SystemClock.elapsedRealtime()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    fun fromLux(lux: Float): Float {
        val t = (ln(1f + lux.coerceAtLeast(0f)) - ln(1f + DARK_LUX)) / (ln(1f + DAY_LUX) - ln(1f + DARK_LUX))
        return 1f - FoldMath.clamp(t)
    }

    /** The clock's guess, used when there is no fresh reading: day, evening, night. */
    fun fromHour(hour: Int): Float = when (hour) {
        in 8..16 -> 0.1f
        7, 17 -> 0.4f
        in 18..20 -> 0.6f
        else -> 0.9f
    }

    fun fresh() = !smoothed.isNaN() && SystemClock.elapsedRealtime() - lastAt < FRESH_MS

    /** Darkness for a run starting now. */
    fun darkness(c: Context, hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Float =
        if (MotionConfig.followRoom(c) && fresh()) smoothed else fromHour(hour)

    fun stage(d: Float) = when {
        d < 0.33f -> "Daylight"
        d < 0.67f -> "Dusk"
        else -> "Dark"
    }

    fun describe(c: Context): String {
        val d = darkness(c)
        val src = when {
            !MotionConfig.followRoom(c) -> "from the clock (following the room is off)"
            fresh() -> "from the room, ${lux.toInt()} lux, ${(SystemClock.elapsedRealtime() - lastAt) / 1000}s ago"
            else -> "from the clock (no recent light reading)"
        }
        return "Light: ${stage(d)} (${"%.2f".format(d)}), $src"
    }
}
