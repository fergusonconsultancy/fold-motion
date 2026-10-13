package com.fergusonconsultancy.foldmotion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Restarts the effect after the phone reboots, and after the app is updated
 * (installing a new APK stops the running service). Only starts if it was
 * switched on with Start animation, so Stop keeps it off across restarts.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (!AutoStart.isEnabled(context)) return
        HealJob.schedule(context)
        if (!Settings.canDrawOverlays(context)) return
        runCatching {
            context.startForegroundService(Intent(context, FoldMotionService::class.java))
        }
    }
}

/** Remembers whether the user wants the effect running. */
object AutoStart {
    private const val PREFS = "fold_motion"
    private const val KEY = "auto_start"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}
