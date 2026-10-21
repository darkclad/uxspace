package com.uxspace.desktop

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.uxspace.R
import com.uxspace.spatial.UxSpaceTheme
import com.uxspace.spatial.WorkspaceController
import com.uxspace.system.SystemStatus

/**
 * Per-slot audio popup — a regular view embedded in [DesktopPresentation], shown when
 * the taskbar's volume button is tapped. Same modal model as the drawer / settings
 * panel: only one of the three is visible at a time, the scrim catches outside clicks.
 *
 * Lays out vertically:
 *  1. Volume slider (drives [SystemStatus.setVolume]).
 *  2. Output list — one row per detected device, the routing one marked.
 *  3. "Open system picker" footer — Android won't let apps re-route media silently, so
 *     the actual route change happens in the system UI, which we launch through
 *     [WorkspaceController.openSoundOutputPicker].
 */
class AudioPanelView(context: Context) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var seek: SeekBar
    private lateinit var volumeLabel: TextView
    private lateinit var activeLabel: TextView
    private lateinit var activeIcon: ImageView
    private lateinit var deviceList: LinearLayout

    private var tracking = false

    private val systemListener = object : SystemStatus.Listener {
        override fun onSystemStatusChanged() {
            mainHandler.post { renderAll() }
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
                text = "Audio"
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TITLE_COLOR)
            },
            LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) },
        )

        addView(buildVolumeRow())
        addView(spacer(dp(18)))
        addView(sectionLabel("Active output"))
        addView(buildActiveRow())
        addView(spacer(dp(18)))
        addView(sectionLabel("Available outputs"))
        deviceList = LinearLayout(context).apply { orientation = VERTICAL }
        addView(deviceList)
        addView(spacer(dp(14)))
        addView(buildOpenSystemPickerButton())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SystemStatus.addListener(systemListener)
        renderAll()
    }

    override fun onDetachedFromWindow() {
        SystemStatus.removeListener(systemListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == VISIBLE) renderAll()
    }

    private fun renderAll() {
        if (::seek.isInitialized && !tracking) {
            val target = (SystemStatus.volumeFraction * SEEK_MAX).toInt()
            if (seek.progress != target) seek.progress = target
        }
        if (::volumeLabel.isInitialized) {
            volumeLabel.text = "${(SystemStatus.volumeFraction * 100).toInt()}%"
        }
        if (::activeLabel.isInitialized) {
            activeLabel.text = SystemStatus.activeOutputName
        }
        if (::activeIcon.isInitialized) {
            activeIcon.setImageResource(iconFor(SystemStatus.activeOutput))
        }
        renderDeviceList()
    }

    // region Volume

    private fun buildVolumeRow(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_volume)
                imageTintList = android.content.res.ColorStateList.valueOf(LABEL_COLOR)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LayoutParams(dp(20), dp(20)),
        )
        seek = SeekBar(context).apply {
            PanelStyle.tintSeek(this)
            max = SEEK_MAX
            progress = (SystemStatus.volumeFraction * SEEK_MAX).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    SystemStatus.setVolume(value.toFloat() / SEEK_MAX)
                    volumeLabel.text = "$value%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { tracking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { tracking = false }
            })
        }
        addView(seek, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        volumeLabel = TextView(context).apply {
            textSize = 13f
            setTextColor(LABEL_COLOR)
            text = "${(SystemStatus.volumeFraction * 100).toInt()}%"
            minWidth = dp(40)
            gravity = Gravity.END
        }
        addView(volumeLabel, LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
    }

    // endregion

    // region Active output header

    private fun buildActiveRow(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        activeIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(LABEL_COLOR)
        }
        addView(activeIcon, LayoutParams(dp(22), dp(22)))
        activeLabel = TextView(context).apply {
            textSize = 14f
            setTextColor(TITLE_COLOR)
            typeface = Typeface.DEFAULT_BOLD
        }
        addView(activeLabel, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }

    // endregion

    // region Available output list

    private fun renderDeviceList() {
        if (!::deviceList.isInitialized) return
        deviceList.removeAllViews()
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Group by our [AudioOutput] bucket so the same physical category (e.g. "BT")
        // shows as one row even when multiple sub-devices exist (HFP + A2DP).
        val grouped = LinkedHashMap<SystemStatus.AudioOutput, String>()
        devices.forEach { d ->
            val (bucket, name) = classify(d) ?: return@forEach
            // Prefer a non-default name when one is available for the bucket.
            grouped.compute(bucket) { _, existing ->
                if (existing.isNullOrBlank() || existing == bucket.displayName) name else existing
            }
        }
        if (grouped.isEmpty()) {
            grouped[SystemStatus.AudioOutput.SPEAKER] = SystemStatus.AudioOutput.SPEAKER.displayName
        }
        grouped.forEach { (bucket, name) ->
            deviceList.addView(buildDeviceRow(bucket, name))
        }
    }

    private fun classify(d: AudioDeviceInfo): Pair<SystemStatus.AudioOutput, String>? {
        val name = runCatching { d.productName?.toString() }.getOrNull().orEmpty()
        val bucket = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> SystemStatus.AudioOutput.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> SystemStatus.AudioOutput.USB
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> SystemStatus.AudioOutput.WIRED
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> SystemStatus.AudioOutput.SPEAKER
            else -> return null
        }
        return bucket to name.ifBlank { bucket.displayName }
    }

    private fun buildDeviceRow(bucket: SystemStatus.AudioOutput, name: String): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
            val active = bucket == SystemStatus.activeOutput
            val bg = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (active) ACTIVE_ROW_BG else INACTIVE_ROW_BG)
            }
            background = bg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(
                ImageView(context).apply {
                    setImageResource(iconFor(bucket))
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        if (active) ACTIVE_FG else LABEL_COLOR,
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LayoutParams(dp(20), dp(20)),
            )
            addView(
                TextView(context).apply {
                    text = name
                    textSize = 13f
                    setTextColor(if (active) ACTIVE_FG else TITLE_COLOR)
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                },
                LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) },
            )
            if (active) {
                addView(
                    TextView(context).apply {
                        text = "✓"
                        textSize = 14f
                        setTextColor(ACTIVE_FG)
                        typeface = Typeface.DEFAULT_BOLD
                    },
                    LayoutParams(WRAP, WRAP),
                )
            }
            setOnClickListener {
                // Android won't let apps force-route general media. Bounce to the
                // system picker, where the change actually happens.
                openSystemPicker()
            }
        }

    // endregion

    private fun buildOpenSystemPickerButton(): View = TextView(context).apply {
        text = "Open system picker…"
        gravity = Gravity.CENTER
        textSize = 13f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(INACTIVE_ROW_BG)
        }
        setTextColor(TITLE_COLOR)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LayoutParams(MATCH, WRAP)
        setOnClickListener { openSystemPicker() }
    }

    private fun openSystemPicker() {
        val hook = WorkspaceController.openSoundOutputPicker
        if (hook != null) {
            hook()
            Toast.makeText(context, "Pick output on the phone", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                context, "Open the UxSpace app on the phone first", Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun iconFor(output: SystemStatus.AudioOutput): Int = when (output) {
        SystemStatus.AudioOutput.BLUETOOTH -> R.drawable.ic_bluetooth
        SystemStatus.AudioOutput.USB -> R.drawable.ic_usb
        SystemStatus.AudioOutput.WIRED -> R.drawable.ic_headphones
        SystemStatus.AudioOutput.SPEAKER -> R.drawable.ic_volume
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 11f
        setTextColor(SECTION_LABEL)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        layoutParams = LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
    }

    private fun spacer(height: Int): View = View(context).apply {
        layoutParams = LayoutParams(MATCH, height)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SEEK_MAX = 100

        const val TITLE_COLOR = UxSpaceTheme.panelTitle
        const val SECTION_LABEL = UxSpaceTheme.panelSection
        const val LABEL_COLOR = UxSpaceTheme.panelLabel

        const val INACTIVE_ROW_BG = UxSpaceTheme.pillInactiveBg
        const val ACTIVE_ROW_BG = UxSpaceTheme.pillActiveBg
        const val ACTIVE_FG = UxSpaceTheme.pillActiveFg
    }
}
