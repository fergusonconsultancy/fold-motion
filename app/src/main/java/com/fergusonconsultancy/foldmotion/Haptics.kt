package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic moments: on closing, a soft tick as the blades start and a crisp
 * click on the payoff pulse; on opening, a low settle as the inner screen
 * arrives. Uses the phone's haptic primitives
 * where supported, so it follows the system vibration intensity settings.
 */
class Haptics(private val context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    private val primitives: Boolean = runCatching {
        vibrator?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK
        ) == true
    }.getOrDefault(false)

    /** LOW_TICK is a deeper, softer primitive added in Android 12. */
    private val lowTick: Boolean = runCatching {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK) == true
    }.getOrDefault(false)

    /** Opening: a soft, low settle as the inner screen arrives, bookending the closing tick and click. */
    fun settle() = play(
        when {
            lowTick && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f).compose()
            primitives -> VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f).compose()
            else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
    )

    val enabled: Boolean
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun tick() = play(
        if (primitives) VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f).compose()
        else VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    )

    fun click() = play(
        if (primitives) VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f).compose()
        else VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    )

    private fun play(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                v.vibrate(effect)
            }
        }
    }

    companion object {
        private const val PREFS = "fold_motion"
        private const val KEY = "haptics"

        fun isEnabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        }
    }
}
