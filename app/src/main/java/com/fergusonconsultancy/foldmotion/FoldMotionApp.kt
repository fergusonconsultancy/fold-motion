package com.fergusonconsultancy.foldmotion

import android.app.Application
import com.cerniera.kit.Crash

/** Keeps a crash anywhere in the app (the service included) to show on the next open, through the Kit. */
class FoldMotionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this, "Fold Motion")
    }
}
