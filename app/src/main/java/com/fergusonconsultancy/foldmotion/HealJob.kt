package com.fergusonconsultancy.foldmotion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Self-healing: every 15 minutes (Android's minimum), checks that the effect
 * is still running if it should be, and restarts it if Samsung or Android has
 * killed it. Survives reboots. Cancelled when the effect is switched off.
 */
class HealJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        if (AutoStart.isEnabled(this) && Settings.canDrawOverlays(this) && !FoldMotionService.running) {
            runCatching {
                startForegroundService(Intent(this, FoldMotionService::class.java))
                Status.countHeal(this)
            }
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val ID = 4107
        private const val PERIOD_MS = 15 * 60 * 1000L

        /** Schedules the check if it is not already pending, so its timer is not reset. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            runCatching {
                js.schedule(
                    JobInfo.Builder(ID, ComponentName(context, HealJob::class.java))
                        .setPeriodic(PERIOD_MS)
                        .setPersisted(true)
                        .build()
                )
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(ID)
        }
    }
}
