package com.fergusonconsultancy.foldmotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.max

/**
 * Seam Close, drawn over the screen being folded away.
 *
 * Inner screen: dark panels close from both outer edges to the crease.
 * Cover screen: one panel sweeps from the outer edge to the hinge (left side).
 * A soft line of light rides each closing edge. At p = 1 the screen is black.
 */
class FoldOverlayView(context: Context) : View(context) {

    private var p = 0f
    private var alphaAll = 0f

    private val solid = Paint().apply { color = Color.BLACK }
    private val feather = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = Paint(Paint.ANTI_ALIAS_FLAG)

    /** True on the near-square inner screen, in any orientation. */
    val isInnerDisplay: Boolean
        get() = width > 0 && height > 0 && FoldMath.isInnerShape(width.toFloat(), height.toFloat())

    fun show(progress: Float, alpha: Float = 1f) {
        p = FoldMath.clamp(progress)
        alphaAll = FoldMath.clamp(alpha)
        invalidate()
    }

    fun clearAll() {
        p = 0f
        alphaAll = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (alphaAll <= 0.001f || p <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val f = max(14f, w * 0.05f)
        val a = (255 * alphaAll).toInt()

        if (isInnerDisplay) {
            val reach = p * (w / 2f + f)
            // Left panel: solid, then feathered leading edge
            panel(canvas, 0f, reach - f, reach - f, reach, true, h, a)
            // Right panel, mirrored
            panel(canvas, w - reach + f, w, w - reach, w - reach + f, false, h, a)
            edgeLight(canvas, reach - f * 0.5f, w * 0.025f, 0.5f * (1f - p), h)
            edgeLight(canvas, w - reach + f * 0.5f, w * 0.025f, 0.5f * (1f - p), h)
        } else {
            // Cover: hinge is on the left, so the panel sweeps from the right edge to it
            val x = w - p * (w + f)
            panel(canvas, x + f, w, x, x + f, false, h, a)
            edgeLight(canvas, x + f * 0.5f, w * 0.04f, 0.55f * (1f - p), h)
        }
    }

    /**
     * Draws a black panel: solid between [solidFrom, solidTo], with a feather
     * between [fadeFrom, fadeTo] that is clear on the outward-leading side.
     */
    private fun panel(
        canvas: Canvas,
        solidFrom: Float, solidTo: Float,
        fadeFrom: Float, fadeTo: Float,
        leftToRight: Boolean, h: Float, a: Int
    ) {
        solid.alpha = a
        if (solidTo > solidFrom) canvas.drawRect(solidFrom, 0f, solidTo, h, solid)
        val black = Color.argb(a, 0, 0, 0)
        val clear = Color.argb(0, 0, 0, 0)
        feather.shader = LinearGradient(
            fadeFrom, 0f, fadeTo, 0f,
            if (leftToRight) black else clear,
            if (leftToRight) clear else black,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(fadeFrom, 0f, fadeTo, h, feather)
    }

    private fun edgeLight(canvas: Canvas, x: Float, halfWidth: Float, strength: Float, h: Float) {
        val peak = (255 * FoldMath.clamp(strength * alphaAll)).toInt()
        if (peak <= 0) return
        light.shader = LinearGradient(
            x - halfWidth, 0f, x + halfWidth, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(peak, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(x - halfWidth, 0f, x + halfWidth, h, light)
    }
}
