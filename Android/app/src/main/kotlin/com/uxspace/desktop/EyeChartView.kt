package com.uxspace.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.uxspace.spatial.WorkspaceController
import kotlin.random.Random

/**
 * The eye chart (docs/OPTOMETRY.md): a full-screen chart for dialling in the glasses'
 * focus wheels. Black optotypes on white, rows from 20/40 down to 20/10 in 0.1 logMAR
 * steps, with the smallest row at the panel's pixel floor (see [MIN_LETTER_PX]). A corner toggle
 * switches between Sloan letters and a Tumbling E for readers who'd rather judge
 * orientation than recognise letters; × (or the taskbar's Back) closes it. The
 * taskbar stays above the chart, so its controls and rows keep clear of the bottom
 * [bottomReserveDp].
 *
 * Fresh letters on every [reshuffle] (called each time the chart opens) so the rows
 * can't be read from memory.
 */
class EyeChartView(
    context: Context,
    /** Height kept clear at the bottom for the taskbar, which stays on top of the chart. */
    private val bottomReserveDp: Int = 0,
) : FrameLayout(context) {

    private enum class Mode { LETTERS, TUMBLING_E }

    private var mode = Mode.LETTERS
    private val chart = ChartCanvas(context)
    private val lettersPill: TextView
    private val tumblingPill: TextView

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        setBackgroundColor(PAPER)
        // Swallow clicks that miss a control so they don't fall through to the desktop.
        isClickable = true
        addView(chart, LayoutParams(MATCH, MATCH))

        addView(
            TextView(context).apply {
                text = "✕"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(INK)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(CONTROL_BG)
                }
                contentDescription = "Close eye chart"
                setOnClickListener { WorkspaceController.setOptometryOpen(false) }
            },
            LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(16)
                marginEnd = dp(16)
            },
        )

        lettersPill = pill("Letters") { setMode(Mode.LETTERS) }
        tumblingPill = pill("Tumbling E") { setMode(Mode.TUMBLING_E) }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(lettersPill)
                addView(
                    tumblingPill,
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
                )
            },
            LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(bottomReserveDp + 12)
                marginEnd = dp(16)
            },
        )

        addView(
            TextView(context).apply {
                text = "Turn the focus wheel until the smallest row you can read is sharp.\n" +
                    "Cover one eye to set each wheel on its own."
                textSize = 12f
                setTextColor(HINT)
                setLineSpacing(dp(2).toFloat(), 1f)
            },
            LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(bottomReserveDp + 12)
                marginStart = dp(20)
            },
        )

        setMode(Mode.LETTERS)
    }

    /** New random optotypes for every row; call when the chart opens. */
    fun reshuffle() {
        chart.reshuffle()
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        stylePill(lettersPill, newMode == Mode.LETTERS)
        stylePill(tumblingPill, newMode == Mode.TUMBLING_E)
        chart.invalidate()
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { onClick() }
    }

    private fun stylePill(pill: TextView, active: Boolean) {
        pill.setTextColor(if (active) PAPER else INK)
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (active) INK else CONTROL_BG)
        }
    }

    /** Draws the rows. Sizes are recomputed from the view height on every draw. */
    private inner class ChartCanvas(context: Context) : View(context) {

        private val optotypePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            // LEFT, because getTextBounds measures from a left-aligned origin and
            // drawCentred positions glyphs from those bounds.
            textAlign = Paint.Align.LEFT
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HINT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 13f * context.resources.displayMetrics.scaledDensity
        }
        private val capBounds = Rect()

        /** Per row: the optotypes (letters, or "E" + a rotation in quarter turns). */
        private var letters: List<String> = emptyList()
        private var rotations: List<List<Int>> = emptyList()

        init {
            reshuffle()
        }

        fun reshuffle() {
            val rnd = Random(System.nanoTime())
            letters = ROWS.map { row ->
                // No letter twice in a row, as on printed charts.
                SLOAN.toList().shuffled(rnd).take(row.count).joinToString("")
            }
            rotations = ROWS.map { row -> List(row.count) { rnd.nextInt(4) } }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            // Pixel-anchored, not screen-filling: the smallest row sits at the panel's
            // legibility floor so a slightly soft focus is visible, and each row up is
            // one 0.1 logMAR step. Rows are centred as a block in the chart area.
            val heights = ROWS.map { MIN_LETTER_PX * it.denominator / 10f }
            // Gap below a row: half its letter height, but never so tight that the
            // small rows (and their labels) run into each other.
            val gaps = heights.map { maxOf(it * ROW_GAP, dp(MIN_ROW_GAP_DP).toFloat()) }
            val blockHeight = heights.sum() + gaps.sum()
            val chartHeight = height - dp(bottomReserveDp)
            var y = (chartHeight - blockHeight) / 2f
            ROWS.forEachIndexed { i, row ->
                val h = heights[i]
                // textSize such that the capital's measured height equals h.
                optotypePaint.textSize = 100f
                optotypePaint.getTextBounds("E", 0, 1, capBounds)
                optotypePaint.textSize = 100f * h / capBounds.height()
                val pitch = h * LETTER_PITCH
                val rowLeft = (width - pitch * row.count) / 2f
                val cy = y + h / 2f
                for (k in 0 until row.count) {
                    val cx = rowLeft + pitch * (k + 0.5f)
                    val glyph = if (mode == Mode.LETTERS) letters[i].substring(k, k + 1) else "E"
                    canvas.save()
                    if (mode == Mode.TUMBLING_E) canvas.rotate(90f * rotations[i][k], cx, cy)
                    drawCentred(canvas, glyph, cx, cy)
                    canvas.restore()
                }
                canvas.drawText(
                    "20/${row.label}",
                    rowLeft - pitch * 0.5f - labelPaint.measureText("20/${row.label}") - dp(12),
                    cy + labelPaint.textSize * 0.35f,
                    labelPaint,
                )
                y += h + gaps[i]
            }
        }

        /**
         * Draw [glyph] centred on ([cx], [cy]): horizontally on its own ink bounds (not
         * its advance box), vertically on the capital height, so round letters' overshoot
         * doesn't shift them off the row's baseline.
         */
        private fun drawCentred(canvas: Canvas, glyph: String, cx: Float, cy: Float) {
            optotypePaint.getTextBounds("E", 0, 1, capBounds)
            val dy = capBounds.exactCenterY()
            optotypePaint.getTextBounds(glyph, 0, glyph.length, capBounds)
            canvas.drawText(glyph, cx - capBounds.exactCenterX(), cy - dy, optotypePaint)
        }
    }

    /** One chart line: its Snellen denominator (20/[denominator]) and letter count. */
    private data class Row(val denominator: Float, val count: Int = 5) {
        val label: String
            get() = if (denominator % 1f == 0f) denominator.toInt().toString() else denominator.toString()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /**
         * 20/40 down to 20/10 in 0.1 logMAR steps (each row ≈ 1.26× the next), five
         * letters per row as on an ETDRS chart. Large rows are left out on purpose: this
         * is for fine focus, not for measuring poor vision.
         */
        val ROWS = listOf(
            Row(40f), Row(32f), Row(25f), Row(20f), Row(16f), Row(12.5f), Row(10f),
        )

        /**
         * Height of the 20/10 row in slot pixels — the smallest letter the glasses draw
         * cleanly. A true 20/20 letter (5 arcmin) is only ~4 px at the glasses' ~44 px/°,
         * below what the panel can render, so the ladder is relative: anchored at the
         * pixel floor, scaled by the standard ratios.
         */
        const val MIN_LETTER_PX = 9f

        /** The ten Sloan letters, the standard optotypes for Snellen / ETDRS charts. */
        const val SLOAN = "CDHKNORSVZ"

        /** Vertical gap below a row, as a fraction of its letter height… */
        const val ROW_GAP = 0.5f

        /** …but at least this much. */
        const val MIN_ROW_GAP_DP = 14

        /** Horizontal distance between letter centres, as a multiple of letter height. */
        const val LETTER_PITCH = 2f

        const val PAPER = 0xFFFFFFFF.toInt()
        const val INK = 0xFF111111.toInt()
        const val HINT = 0xFF77767B.toInt()
        const val CONTROL_BG = 0xFFEDEDF0.toInt()
    }
}
