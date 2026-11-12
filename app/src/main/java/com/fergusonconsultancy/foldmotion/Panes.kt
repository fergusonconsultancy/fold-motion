package com.fergusonconsultancy.foldmotion

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.LinearLayout
import com.cerniera.kit.Fold
import com.cerniera.kit.Kit
import com.cerniera.kit.Screen
import com.cerniera.kit.px
import com.cerniera.kit.text

/*
 * The settings kit now lives in the Kit (cerniera-studio/kit), the family's shared design system
 * (12 Nov 2026): palette, type, controls, the doctrine and crash reports. This file keeps what
 * only Fold Motion uses: the notes card and the way its settings sit in each shape of the phone.
 */

/** A titled block of notes inside a fold, for the design notes on each choice. */
fun Context.notesCard(title: String, sub: String, notes: List<Pair<String, String>>): View = Fold(this, title, sub).apply {
    val ctx = this@notesCard
    notes.forEachIndexed { i, (name, text) ->
        body.addView(ctx.text(name, 15f, Kit.INK, 500), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = ctx.px(16) })
        body.addView(ctx.text(text, 13.5f, Kit.MUTED, 400), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(3) })
    }
}

/** A live view that keeps its own shape and is told how tall it may grow. */
interface Fits { var maxH: Int }

/**
 * Settings in every shape of the phone (12 Nov 2026), as in Solco. Upright on the cover: one
 * scrolling column, the live stage, its light and the main action first, the choices after.
 * Anywhere wider (the cover turned, the main screen either way round): the stage stands in a pane
 * of its own, as large as the height allows, with its light and the action beneath, and the
 * choices scroll beside it. Either way the stage and the action are in view at rest; only the
 * choices sit below the fold.
 */
class Panes(private val a: Activity, val screen: Screen) {
    val wide: Boolean = a.resources.configuration.let { it.screenWidthDp > it.screenHeightDp || it.smallestScreenWidthDp >= 600 }

    /** Holds the stage and what goes under it; in a wide shape it fits them to the pane's height. */
    val lead = object : LinearLayout(a) {
        override fun onMeasure(ws: Int, hs: Int) {
            if (MeasureSpec.getMode(hs) != MeasureSpec.UNSPECIFIED) {
                var used = paddingTop + paddingBottom
                var fits: Fits? = null
                for (i in 0 until childCount) {
                    val v = getChildAt(i)
                    if (v is Fits) { fits = v; continue }
                    if (v.visibility == GONE) continue
                    measureChild(v, ws, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    val lp = v.layoutParams as LayoutParams
                    used += v.measuredHeight + lp.topMargin + lp.bottomMargin
                }
                fits?.maxH = (MeasureSpec.getSize(hs) - used).coerceAtLeast(a.px(140))
            }
            super.onMeasure(ws, hs)
        }
    }.apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

    fun add(v: View, top: Int = 0) =
        lead.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top; gravity = Gravity.CENTER_HORIZONTAL })

    /** Call once the stage and the action are added, before the rest: returns the Activity's content. */
    fun build(): View {
        if (!wide) { screen.add(lead, a.px(26)); return screen.root }
        // The panes keep clear of the bars and the camera on every side, so nothing hides under them
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Kit.GROUND)
            setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        lead.setPadding(a.px(24), a.px(20), a.px(8), a.px(20))
        lead.gravity = Gravity.CENTER
        row.addView(lead, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(screen.root, LinearLayout.LayoutParams(0, -1, 1f))
        return row
    }

    /**
     * Records where the stage and the main action ended up, for the shapes test: both must be in
     * view at rest, in every shape. Written to files/layout.json once laid out.
     */
    fun record(root: View, live: View, action: View) {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (live.height <= 0 || action.height <= 0 || root.height <= 0) return
                fun box(v: View): String { val p = IntArray(2); v.getLocationInWindow(p); return "[${p[0]}, ${p[1]}, ${p[0] + v.width}, ${p[1] + v.height}]" }
                val inset = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
                val json = "{\"wide\": $wide, \"view\": [${root.width}, ${root.height}], \"insets\": [${inset?.left ?: 0}, ${inset?.top ?: 0}, ${inset?.right ?: 0}, ${inset?.bottom ?: 0}], " +
                    "\"live\": ${box(live)}, \"action\": ${box(action)}}"
                runCatching { java.io.File(a.filesDir, "layout.json").writeText(json) }
            }
        })
    }
}
