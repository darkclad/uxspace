package com.uxspace.desktop

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.uxspace.R
import com.uxspace.privileged.PrivilegedService
import com.uxspace.spatial.UxSpaceTheme
import com.uxspace.spatial.WorkspaceController
import com.uxspace.system.SystemStatus
import java.text.DateFormat
import java.util.Date

/**
 * Per-slot quick-settings panel — opened from the taskbar tray's ^ button, like DeX's.
 * Same modal model as the drawer / settings / audio panels.
 *
 * Toggle tiles (Wi-Fi, Bluetooth, Do not disturb, Airplane mode) and a brightness slider.
 * Apps may not flip these radios themselves, so changes go through the privileged helper
 * ([PrivilegedService.setQuickSetting]); the tiles re-render from [SystemStatus] once the
 * system reports the new state.
 */
class QuickSettingsPanelView(context: Context, private val slotIdx: Int) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var dateLabel: TextView
    private lateinit var brightness: SeekBar
    private val tiles = mutableListOf<Tile>()
    private var tracking = false
    private var pendingBrightness = 0
    private val sendBrightness = Runnable {
        PrivilegedService.setQuickSetting("brightness", pendingBrightness)
    }

    private class Tile(
        val view: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val state: TextView,
        val isOn: () -> Boolean,
    )

    private val systemListener = object : SystemStatus.Listener {
        override fun onSystemStatusChanged() {
            mainHandler.post { render() }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        PanelStyle.applyCard(this)
        setPadding(dp(24), dp(20), dp(24), dp(20))
        addView(
            TextView(context).apply {
                text = "Quick settings"
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UxSpaceTheme.panelTitle)
            },
        )
        dateLabel = TextView(context).apply {
            textSize = 12f
            setTextColor(UxSpaceTheme.panelSection)
        }
        addView(dateLabel, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })
        addView(
            tileRow(
                tile(R.drawable.ic_wifi, "Wi-Fi", "wifi") { SystemStatus.wifiEnabled },
                tile(R.drawable.ic_bluetooth, "Bluetooth", "bluetooth") { SystemStatus.bluetoothOn },
            ),
        )
        addView(
            tileRow(
                tile(R.drawable.ic_dnd, "Do not disturb", "dnd") { SystemStatus.dndOn },
                tile(R.drawable.ic_airplane, "Airplane mode", "airplane") { SystemStatus.airplaneOn },
            ),
        )
        addView(buildBrightnessRow(), LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        addView(View(context), LayoutParams(MATCH, 0, 1f))
        addView(buildSettingsButton())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SystemStatus.addListener(systemListener)
        render()
    }

    override fun onDetachedFromWindow() {
        SystemStatus.removeListener(systemListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == VISIBLE) render()
    }

    private fun render() {
        dateLabel.text = DateFormat.getDateInstance(DateFormat.FULL).format(Date())
        tiles.forEach { t ->
            val on = t.isOn()
            (t.view.background as GradientDrawable).setColor(
                if (on) UxSpaceTheme.pillActiveBg else UxSpaceTheme.pillInactiveBg,
            )
            val fg = if (on) UxSpaceTheme.pillActiveFg else UxSpaceTheme.pillInactiveFg
            t.icon.imageTintList = ColorStateList.valueOf(fg)
            t.label.setTextColor(fg)
            t.state.setTextColor(fg)
            t.state.text = if (on) "On" else "Off"
        }
        if (!tracking) brightness.progress = SystemStatus.brightness
    }

    private fun tileRow(vararg row: View): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        row.forEachIndexed { i, v ->
            addView(v, LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) marginStart = dp(10)
                bottomMargin = dp(10)
            })
        }
    }

    private fun tile(
        @DrawableRes icon: Int,
        name: String,
        setting: String,
        isOn: () -> Boolean,
    ): View {
        val iconView = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(context).apply {
            text = name
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        val state = TextView(context).apply { textSize = 11f }
        val view = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(iconView, LayoutParams(dp(22), dp(22)))
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(label)
                    addView(state)
                },
                LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) },
            )
            setOnClickListener { PrivilegedService.setQuickSetting(setting, if (isOn()) 0 else 1) }
        }
        tiles += Tile(view, iconView, label, state, isOn)
        return view
    }

    private fun buildBrightnessRow(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_brightness)
                imageTintList = ColorStateList.valueOf(UxSpaceTheme.panelLabel)
            },
            LayoutParams(dp(20), dp(20)),
        )
        brightness = SeekBar(context).apply {
            PanelStyle.tintSeek(this)
            max = 255
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    // Each change is a shell command in the helper; send only the latest.
                    pendingBrightness = value
                    mainHandler.removeCallbacks(sendBrightness)
                    mainHandler.postDelayed(sendBrightness, BRIGHTNESS_COALESCE_MS)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { tracking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { tracking = false }
            })
        }
        addView(brightness, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
    }

    /** Footer: UxSpace's own settings panel (the tray keeps a gear for it too). */
    private fun buildSettingsButton(): View = TextView(context).apply {
        text = "UxSpace settings…"
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(UxSpaceTheme.panelTitle)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(UxSpaceTheme.pillInactiveBg)
        }
        layoutParams = LayoutParams(MATCH, WRAP)
        setOnClickListener { WorkspaceController.setSettingsOpen(true, slotIdx) }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val BRIGHTNESS_COALESCE_MS = 120L
    }
}
