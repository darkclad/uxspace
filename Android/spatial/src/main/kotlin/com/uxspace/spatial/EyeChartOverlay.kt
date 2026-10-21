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
import kotlin.math.roundToInt

/**
 * The eye chart (docs/OPTOMETRY.md): a head-locked page of black-on-white text that
 * fills the whole glasses view, regardless of zoom, view mode or screen layout, in five
 * boxes (one per corner and a large one in the centre) so focus can be judged across
 * the field, where the optics differ, not just in the middle. The user rolls the wheel
 * to change the text size and turns the focus wheels until the smallest size they can
 * read is sharp. A click swaps black-on-white for white-on-black
 * (dark text on a bright field and the reverse blur differently); a right-click closes it.
 *
 * Text size is a real visual angle, sized the way reading charts (MNREAD, "M" print
 * sizes) size running text: the lowercase x-height is 5 arcmin × [denominator]/20 at
 * the glasses' [PX_PER_DEGREE], so the footer's "20/x" is the acuity that size tests.
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
    /** Current size as the Snellen denominator: 20/[denominator]. */
    private var denominator = START_DENOMINATOR

    /** Uptime of the last size step, for the [STEP_COOLDOWN_MS] rate limit. */
    private var lastStepMs = 0L

    private var textureId = 0
    private var textureW = 0
    private var textureH = 0
    private var textureDenominator = 0

    /** White text on black instead of black on white. */
    private var inverted = false
    private var textureInverted = false

    /**
     * Wheel / two-finger scroll: up makes the text bigger, down smaller — one
     * [STEP_DENOMINATOR] step per scroll, at most every [STEP_COOLDOWN_MS]. Counting
     * magnitude instead ran away: mice and the emulator send several ticks per notch,
     * and a touchpad swipe is a stream of events.
     */
    fun scroll(dyFraction: Float) {
        if (dyFraction == 0f) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStepMs < STEP_COOLDOWN_MS) return
        lastStepMs = now
        val step = if (dyFraction > 0f) STEP_DENOMINATOR else -STEP_DENOMINATOR
        denominator = (denominator + step).coerceIn(MIN_DENOMINATOR, MAX_DENOMINATOR)
    }

    /** Swap black-on-white ↔ white-on-black. */
    fun toggleInverted() {
        inverted = !inverted
    }

    /** Draw the page over the whole [width] × [height] surface. Leaves the viewport set to it. */
    fun draw(width: Int, height: Int) {
        if (textureId == 0 || width != textureW || height != textureH ||
            denominator != textureDenominator || inverted != textureInverted
        ) {
            upload(render(width, height))
            textureW = width
            textureH = height
            textureDenominator = denominator
            textureInverted = inverted
        }
        GLES20.glViewport(0, 0, width, height)
        drawFullscreen(textureId)
    }

    /** Free the texture once the chart is closed. Cheap no-op when there's none. */
    fun release() {
        if (textureId == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
        // Reopening starts fresh: default size, black on white.
        denominator = START_DENOMINATOR
        inverted = false
    }

    private fun render(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(if (inverted) Color.BLACK else Color.WHITE)

        val footerH = (height * FOOTER_FRACTION).roundToInt()
        val margin = (width * MARGIN_FRACTION).roundToInt()

        val xHeightPx = PX_PER_DEGREE * (5f / 60f) * denominator / 20f
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (inverted) Color.WHITE else Color.BLACK
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 100f
            val bounds = Rect()
            getTextBounds("x", 0, 1, bounds)
            textSize = 100f * xHeightPx / bounds.height()
        }
        val frame = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = if (inverted) FRAME_ON_BLACK else FRAME_ON_WHITE
        }
        val pad = (width * BOX_PADDING_FRACTION).roundToInt()
        boxes(width, height - footerH, margin).forEachIndexed { i, box ->
            canvas.drawRect(box, frame)
            // Each box starts at a different point in the text so they don't read alike.
            drawText(canvas, box, pad, body, startAt = i * LOREM.length / BOX_COUNT)
        }

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
        val acuity = "20/$denominator"
        canvas.drawText(acuity, margin.toFloat(), baseline, label)
        canvas.drawText(
            "Scroll to change the size and turn the focus wheel until it's sharp. " +
                "Click to invert colours, right-click to close.",
            margin + label.measureText(acuity) + footerH * 0.6f,
            baseline,
            hint,
        )
        return bmp
    }

    /**
     * The five text boxes in a [width] × [height] page: one per corner and a centre box
     * spanning nearly the full height between them, the largest of the five.
     */
    private fun boxes(width: Int, height: Int, margin: Int): List<Rect> {
        val m = margin
        val cornerW = (width * CORNER_W_FRACTION).roundToInt()
        val cornerH = (height * CORNER_H_FRACTION).roundToInt()
        val gap = (width * BOX_GAP_FRACTION).roundToInt()
        return listOf(
            Rect(m, m, m + cornerW, m + cornerH),
            Rect(width - m - cornerW, m, width - m, m + cornerH),
            Rect(m + cornerW + gap, m, width - m - cornerW - gap, height - m),
            Rect(m, height - m - cornerH, m + cornerW, height - m),
            Rect(width - m - cornerW, height - m - cornerH, width - m, height - m),
        )
    }

    /** Lorem ipsum filling [box] (inset by [pad]), starting [startAt] chars into it. */
    private fun drawText(canvas: Canvas, box: Rect, pad: Int, paint: TextPaint, startAt: Int) {
        val textW = box.width() - 2 * pad
        if (textW <= 0) return
        // Just enough text to fill the box at this size (20/20 is a lot of characters).
        val charW = paint.measureText(LOREM) / LOREM.length
        val lineH = paint.fontSpacing * LINE_SPACING
        val needed = (textW / charW) * (box.height() / lineH) * 1.15f + startAt
        val text = LOREM.repeat((needed / LOREM.length).toInt() + 2).substring(startAt)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.clipRect(box.left + pad, box.top + pad, box.right - pad, box.bottom - pad)
        canvas.translate((box.left + pad).toFloat(), (box.top + pad).toFloat())
        layout.draw(canvas)
        canvas.restore()
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
        const val START_DENOMINATOR = 40

        /** 20/20 (≈4 px x-height here, about the finest the panel can draw). */
        const val MIN_DENOMINATOR = 20

        /** 20/200. */
        const val MAX_DENOMINATOR = 200

        /** One scroll step: 20/40 → 20/45 → 20/50 … */
        const val STEP_DENOMINATOR = 5

        /** Minimum time between steps, so one notch or flick moves one step. */
        const val STEP_COOLDOWN_MS = 150L

        const val LINE_SPACING = 1.35f
        const val MARGIN_FRACTION = 0.02f
        const val FOOTER_FRACTION = 0.06f

        /** Box layout, as fractions of the page above the footer. */
        const val BOX_COUNT = 5
        const val CORNER_W_FRACTION = 0.27f
        const val CORNER_H_FRACTION = 0.40f
        const val BOX_GAP_FRACTION = 0.015f
        const val BOX_PADDING_FRACTION = 0.008f

        const val FRAME_ON_WHITE = 0xFFB9B8BD.toInt()
        const val FRAME_ON_BLACK = 0xFF4A494E.toInt()

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
