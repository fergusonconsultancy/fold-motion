package com.fergusonconsultancy.foldmotion

import kotlin.math.roundToInt

/**
 * Records frame timings while the effect plays, without allocating per frame.
 *
 * Dropped frames are counted against the fastest cadence the panel actually
 * delivered during the run, so a phone running at 60Hz is not misreported as
 * dropping every other frame at 120Hz.
 */
class FrameStats {
    private val gaps = LongArray(1024)
    private var count = 0
    private var first = 0L
    private var last = 0L

    fun reset() {
        count = 0
        first = 0L
        last = 0L
    }

    fun onFrame(frameTimeNanos: Long) {
        if (first == 0L) first = frameTimeNanos
        if (last != 0L && count < gaps.size) gaps[count++] = frameTimeNanos - last
        last = frameTimeNanos
    }

    class Result(val frames: Int, val dropped: Int, val fps: Int, val durationMs: Int)

    fun result(): Result? {
        if (count < 2) return null
        var minGap = Long.MAX_VALUE
        for (i in 0 until count) if (gaps[i] in 1 until minGap) minGap = gaps[i]
        var dropped = 0
        for (i in 0 until count) {
            val missed = (gaps[i].toDouble() / minGap).roundToInt() - 1
            if (missed > 0) dropped += missed
        }
        val durationNs = last - first
        val fps = if (durationNs > 0) (count * 1e9 / durationNs).roundToInt() else 0
        return Result(count + 1, dropped, fps, (durationNs / 1_000_000).toInt())
    }
}
