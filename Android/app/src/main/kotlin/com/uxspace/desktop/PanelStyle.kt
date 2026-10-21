package com.uxspace.desktop

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import com.uxspace.spatial.UxSpaceTheme

/**
 * Shared look for the desktop's modal panels (app drawer, Settings, Audio): a dark
 * rounded card with a hairline stroke, and framework widgets re-tinted to match it.
 * The framework's default control colours assume a light surface, so every CheckBox,
 * RadioButton and SeekBar placed on a card goes through [tintControl] / [tintSeek].
 */
internal object PanelStyle {

    /** Corner radius of a panel card. */
    const val CORNER_DP = 21

    /** Turn [panel] into a card: rounded background + stroke, children clipped to it. */
    fun applyCard(panel: View) {
        val density = panel.resources.displayMetrics.density
        panel.background = GradientDrawable().apply {
            cornerRadius = CORNER_DP * density
            setColor(UxSpaceTheme.panel)
            setStroke(1, UxSpaceTheme.panelStroke)
        }
        panel.clipToOutline = true
    }

    private val checkableTint = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(UxSpaceTheme.controlAccent, UxSpaceTheme.controlTrack),
    )

    fun tintControl(button: CompoundButton) {
        button.buttonTintList = checkableTint
    }

    fun tintSeek(seek: SeekBar) {
        seek.thumbTintList = ColorStateList.valueOf(UxSpaceTheme.controlAccent)
        seek.progressTintList = ColorStateList.valueOf(UxSpaceTheme.controlAccent)
        seek.progressBackgroundTintList = ColorStateList.valueOf(UxSpaceTheme.controlTrack)
    }
}
