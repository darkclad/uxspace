package com.uxspace.spatial

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The eye chart (docs/OPTOMETRY.md): a head-locked page of black-on-white text that
 * fills the whole glasses view, regardless of zoom, view mode or screen layout. The
 * user rolls the wheel to change the text size and turns the focus wheels until the
 * smallest size they can read is sharp; a click closes it.
 *
 * Text size is a real visual angle, sized the way reading charts (MNREAD, "M" print
 * sizes) size running text: the lowercase x-height is 5 arcmin × 10^[logMar] at the
 * glasses' [PX_PER_DEGREE], so the footer's "20/x" is the acuity that size tests.
 * (Snellen's 5 arcmin is a capital's height; lorem is lowercase, so sizing by capitals
 * would make every line read ~1.5 chart lines smaller than its label.)
 *
 * [WorkspaceRenderer] owns the GL state; this class only keeps the page texture up to
 * date. All methods run on the GL thread.
 */
internal class EyeChartOverlay(
    /** Draws [textureId] over the full current viewport (the renderer's HUD quad). */
    private val drawFullscreen: (textureId: Int) -> Unit,
) {
    /** Current size as logMAR: 0 = 20/20, 0.3 ≈ 20/40, 1 = 20/200. */
    private var logMar = START_LOG_MAR

    /** Wheel travel not yet turned into a [STEP_LOG_MAR] step. */
    private var scrollAccumulator = 0f

    private var textureId = 0
    private var textureW = 0
    private var textureH = 0
    private var textureLogMar = Float.NaN

    /** Wheel / two-finger scroll: up makes the text bigger, down smaller. */
    fun scroll(dyFraction: Float) {
        scrollAccumulator += dyFraction
        val steps = (scrollAccumulator / SCROLL_PER_STEP).toInt()
        if (steps == 0) return
        scrollAccumulator -= steps * SCROLL_PER_STEP
        logMar = (logMar + steps * STEP_LOG_MAR).coerceIn(MIN_LOG_MAR, MAX_LOG_MAR)
    }

    /** Draw the page over the whole [width] × [height] surface. Leaves the viewport set to it. */
    fun draw(width: Int, height: Int) {
        if (textureId == 0 || width != textureW || height != textureH || logMar != textureLogMar) {
            upload(render(width, height))
            textureW = width
            textureH = height
            textureLogMar = logMar
        }
        GLES20.glViewport(0, 0, width, height)
        drawFullscreen(textureId)
    }

    /** Free the texture once the chart is closed. Cheap no-op when there's none. */
    fun release() {
        if (textureId == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
        // Reopening starts from the default size, like a fresh chart.
        logMar = START_LOG_MAR
        scrollAccumulator = 0f
    }

    private fun render(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val footerH = (height * FOOTER_FRACTION).roundToInt()
        val margin = (width * MARGIN_FRACTION).roundToInt()

        // Body: lorem ipsum filling everything above the footer.
        val xHeightPx = PX_PER_DEGREE * (5f / 60f) * 10f.pow(logMar)
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 100f
            val bounds = Rect()
            getTextBounds("x", 0, 1, bounds)
            textSize = 100f * xHeightPx / bounds.height()
        }
        // Just enough lorem to fill the page at this size (20/20 needs ~100k chars).
        val textW = width - 2 * margin
        val charW = body.measureText(LOREM) / LOREM.length
        val lineH = body.fontSpacing * LINE_SPACING
        val needed = (textW / charW) * (height / lineH) * 1.15f
        val page = LOREM.repeat((needed / LOREM.length).toInt() + 1)
        val layout = StaticLayout.Builder
            .obtain(page, 0, page.length, body, textW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.clipRect(0, 0, width, height - footerH)
        canvas.translate(margin.toFloat(), margin.toFloat() * 0.6f)
        layout.draw(canvas)
        canvas.restore()

        // Footer: what size this is and how to drive it, at a fixed readable size.
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FOOTER_BG }
        canvas.drawRect(0f, (height - footerH).toFloat(), width.toFloat(), height.toFloat(), footerPaint)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = footerH * 0.42f
        }
        val hint = Paint(label).apply {
            color = FOOTER_HINT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val baseline = height - footerH / 2f + label.textSize * 0.36f
        val acuity = "20/${snellenDenominator()}"
        canvas.drawText(acuity, margin.toFloat(), baseline, label)
        canvas.drawText(
            "Scroll to change the text size, turn the focus wheel until it's sharp. Click to close.",
            margin + label.measureText(acuity) + footerH * 0.6f,
            baseline,
            hint,
        )
        return bmp
    }

    /** "40", "32", "12.5" … — the denominator of the 20/x line this size matches. */
    private fun snellenDenominator(): String {
        val d = 20f * 10f.pow(logMar)
        return if (d >= 30f) d.roundToInt().toString() else "%.1f".format(d).removeSuffix(".0")
    }

    private fun upload(bmp: Bitmap) {
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        // NEAREST: the page is drawn 1:1 to the glasses' pixels; any filtering would blur
        // exactly the detail the user is trying to bring into focus.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    private companion object {
        /**
         * Glasses resolution in pixels per degree of view: VITURE's 1920-px-wide panel
         * spans roughly 44° horizontally. Sets the real visual size of the text.
         */
        const val PX_PER_DEGREE = 44f

        /** Opening size: 20/40. */
        const val START_LOG_MAR = 0.3f

        /** 20/20 (≈4 px x-height here, about the finest the panel can draw). */
        const val MIN_LOG_MAR = 0f

        /** 20/200. */
        const val MAX_LOG_MAR = 1f

        /** One wheel notch (0.08 scroll) = half a chart line. */
        const val STEP_LOG_MAR = 0.05f
        const val SCROLL_PER_STEP = 0.08f

        const val LINE_SPACING = 1.35f
        const val MARGIN_FRACTION = 0.04f
        const val FOOTER_FRACTION = 0.06f

        const val INK = 0xFF000000.toInt()
        const val FOOTER_BG = 0xFF1B1A1D.toInt()
        const val FOOTER_HINT = 0xFFB4B2B8.toInt()

        const val LOREM =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor " +
                "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis " +
                "nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. " +
                "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore " +
                "eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt " +
                "in culpa qui officia deserunt mollit anim id est laborum. Sed ut perspiciatis " +
                "unde omnis iste natus error sit voluptatem accusantium doloremque laudantium, " +
                "totam rem aperiam, eaque ipsa quae ab illo inventore veritatis et quasi " +
                "architecto beatae vitae dicta sunt explicabo. Nemo enim ipsam voluptatem quia " +
                "voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur magni dolores " +
                "eos qui ratione voluptatem sequi nesciunt. "
    }
}
