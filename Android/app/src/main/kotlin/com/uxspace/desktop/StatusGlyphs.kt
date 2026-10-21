package com.uxspace.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.uxspace.spatial.UxSpaceTheme

/**
 * Taskbar status glyphs that show a level, drawn in code rather than as one vector per level:
 * lit segments in the taskbar text colour, the rest dimmed — the phone status bar's look.
 */
internal abstract class LevelGlyphView(context: Context) : View(context) {

    /** Lit segments, 0..[SEGMENTS]. */
    var level: Int = SEGMENTS
        set(value) {
            val v = value.coerceIn(0, SEGMENTS)
            if (field != v) { field = v; invalidate() }
        }

    /** Draws a slash across the glyph (radio off / no connection). */
    var crossed: Boolean = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    protected val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UxSpaceTheme.taskbarText }
    protected val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UxSpaceTheme.taskbarTextDim }

    protected fun paintFor(segment: Int): Paint = if (!crossed && segment < level) lit else dim

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGlyph(canvas, width.toFloat(), height.toFloat())
        if (crossed) {
            val stroke = Paint(lit).apply {
                style = Paint.Style.STROKE
                strokeWidth = height * 0.1f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(width * 0.15f, height * 0.15f, width * 0.85f, height * 0.85f, stroke)
        }
    }

    protected abstract fun drawGlyph(canvas: Canvas, w: Float, h: Float)

    companion object {
        const val SEGMENTS = 4
    }
}

/** Mobile signal: four rising bars. */
internal class SignalGlyphView(context: Context) : LevelGlyphView(context) {

    override fun drawGlyph(canvas: Canvas, w: Float, h: Float) {
        val gap = w * 0.08f
        val barW = (w - gap * (SEGMENTS - 1)) / SEGMENTS
        val r = barW * 0.3f
        for (i in 0 until SEGMENTS) {
            val left = i * (barW + gap)
            val top = h - h * (i + 1) / SEGMENTS
            canvas.drawRoundRect(RectF(left, top, left + barW, h), r, r, paintFor(i))
        }
    }
}

/** Wi-Fi: a fan of three arcs over a dot, lit from the dot outwards. */
internal class WifiGlyphView(context: Context) : LevelGlyphView(context) {

    private val path = Path()

    override fun drawGlyph(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.92f
        val maxR = minOf(w * 0.55f, h * 0.9f)
        // Segment 0 is the dot (a 90° wedge), segments 1..3 the rings around it.
        for (i in 0 until SEGMENTS) {
            val outer = maxR * (i + 1) / SEGMENTS
            val inner = if (i == 0) 0f else outer - maxR / SEGMENTS * 0.62f
            path.reset()
            path.arcTo(RectF(cx - outer, cy - outer, cx + outer, cy + outer), 225f, 90f)
            if (inner > 0f) {
                path.arcTo(RectF(cx - inner, cy - inner, cx + inner, cy + inner), 315f, -90f)
            } else {
                path.lineTo(cx, cy)
            }
            path.close()
            canvas.drawPath(path, paintFor(i))
        }
    }
}
