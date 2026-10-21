package com.uxspace.desktop

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.uxspace.spatial.Layout
import com.uxspace.spatial.UxSpaceTheme
import com.uxspace.spatial.WorkspaceController
import com.uxspace.spatial.WorkspaceRenderer.ViewMode

/**
 * UxSpace's per-slot settings panel — a regular Android view (not a Presentation),
 * embedded as a child of each screen's [DesktopPresentation] alongside the [DrawerView].
 * Same modal model as the drawer: the screen's surface texture contains the panel when
 * visible; sizing always matches the host screen; visibility is driven by the controller
 * via [WorkspaceController.addSettingsStateListener] filtered on the slot index.
 *
 * Tabbed layout: a top tab bar selects which panel to show below. Tabs that aren't fully
 * wired yet are scaffolded with their controls so the visual + interaction model is
 * settled before each panel's storage / behaviour is implemented.
 */
class SettingsView(context: Context) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class Tab(val label: String) {
        SCREENS("Screens"),
        DESKTOP("Desktop"),
        INPUT("Input"),
        VIEW("View"),
        WINDOWS("Windows"),
        TASKBAR("Taskbar"),
        CAPTURE("Capture"),
        PRIVILEGED("Privileged"),
        ABOUT("About"),
    }

    private var activeTab: Tab = Tab.SCREENS
    private val tabButtons = mutableMapOf<Tab, TextView>()
    private lateinit var contentHost: FrameLayout

    /** Identity tag marking a view that starts a settings section (for the two-column reflow). */
    private val SECTION_MARKER = Any()

    // --- Tab content controls (lateinit because each is built inside its tab) ---
    private lateinit var viewModePinned: TextView
    private lateinit var viewModeFree: TextView
    private lateinit var zoomLabel: TextView
    /** The "Screen fill" option pills, paired with the fraction each selects. */
    private val fillPills = mutableListOf<Pair<Float, TextView>>()
    private lateinit var recordingButton: TextView

    /**
     * Per-layout rows on the Screens tab — keeps the radio-group buttons alive so we
     * can re-render the active selection when the controller's layout or main-screen
     * choice changes (e.g. cycled from the toolbar).
     */
    private data class ScreenRow(
        val enableCheck: CheckBox,
        val mainGroup: RadioGroup?,
        val mainButtons: Map<Int, RadioButton>,
    )

    private val screenRows = mutableMapOf<Layout, ScreenRow>()

    private val zoomListener: (Float) -> Unit = { z ->
        if (activeTab == Tab.VIEW) mainHandler.post { renderZoom(z) }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        PanelStyle.applyCard(this)
        setPadding(dp(24), dp(20), dp(24), dp(20))

        val title = TextView(context).apply {
            text = "Settings"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TITLE_COLOR)
        }
        addView(title, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        addView(buildTabBar(), LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        contentHost = FrameLayout(context)
        addView(contentHost, LayoutParams(MATCH, 0, 1f))
        showTab(activeTab)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WorkspaceController.addZoomListener(zoomListener)
        com.uxspace.privileged.PrivilegedService.addListener(privilegedListener)
        renderAll()
    }

    override fun onDetachedFromWindow() {
        WorkspaceController.removeZoomListener(zoomListener)
        com.uxspace.privileged.PrivilegedService.removeListener(privilegedListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == VISIBLE) renderAll()
    }

    private fun renderAll() {
        // Each tab re-renders its own state on display; renderAll just resyncs the
        // currently-shown tab so a toolbar-driven change (zoom, layout cycle, …)
        // appears immediately if the user happens to be looking at this panel.
        when (activeTab) {
            Tab.SCREENS -> renderScreensTab()
            Tab.VIEW -> {
                renderViewMode(WorkspaceController.currentViewMode)
                renderZoom(WorkspaceController.currentZoom())
                renderScreenFill(WorkspaceController.currentScreenBand())
            }
            Tab.CAPTURE -> renderRecording(WorkspaceController.isRecording)
            else -> Unit
        }
    }

    // region Tab bar

    private fun buildTabBar(): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        Tab.values().forEach { tab ->
            val button = tabButton(tab.label) { showTab(tab) }
            tabButtons[tab] = button
            val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
            row.addView(button, lp)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(row, LayoutParams(WRAP, WRAP))
        }
    }

    private fun tabButton(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 13f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
        styleTab(this, active = false)
    }

    private fun styleTab(tv: TextView, active: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (active) PILL_ACTIVE_BG else PILL_INACTIVE_BG)
        }
        tv.background = bg
        tv.setTextColor(if (active) PILL_ACTIVE_FG else PILL_INACTIVE_FG)
        tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun showTab(tab: Tab) {
        activeTab = tab
        tabButtons.forEach { (t, btn) -> styleTab(btn, t == tab) }
        contentHost.removeAllViews()
        val body = when (tab) {
            Tab.SCREENS -> buildScreensTab()
            Tab.DESKTOP -> buildDesktopTab()
            Tab.INPUT -> buildInputTab()
            Tab.VIEW -> buildViewTab()
            Tab.WINDOWS -> buildWindowsTab()
            Tab.TASKBAR -> buildTaskbarTab()
            Tab.CAPTURE -> buildCaptureTab()
            Tab.PRIVILEGED -> buildPrivilegedTab()
            Tab.ABOUT -> buildAboutTab()
        }
        val laidOut = if (body is LinearLayout) reflowIntoColumns(body) else body
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
            addView(laidOut, LayoutParams(MATCH, WRAP))
        }
        contentHost.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        renderAll()
    }

    /**
     * Reflow a single-column tab [body] into two balanced columns, so the wide modal's right
     * half isn't wasted and long tabs (e.g. Screens) stop overflowing the bottom. The body's
     * top-level children are partitioned into sections at each [SECTION_MARKER]-tagged child
     * (a section label, or a self-contained layout row) so a header is never split from the
     * controls beneath it; whole sections are then greedily packed into whichever column is
     * currently shorter. Falls back to the untouched full-width column when there's only one
     * section (nothing to balance).
     */
    private fun reflowIntoColumns(body: LinearLayout): View {
        val children = ArrayList<View>(body.childCount)
        for (i in 0 until body.childCount) children.add(body.getChildAt(i))
        val sections = mutableListOf<MutableList<View>>()
        for (v in children) {
            if (sections.isEmpty() || v.tag === SECTION_MARKER) sections.add(mutableListOf())
            sections.last().add(v)
        }
        if (sections.size < 2) return body

        body.removeAllViews()
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        val right = LinearLayout(context).apply { orientation = VERTICAL }
        var wLeft = 0
        var wRight = 0
        for (section in sections) {
            val w = section.sumOf { viewWeight(it) }
            if (wLeft <= wRight) {
                section.forEach { left.addView(it) }
                wLeft += w
            } else {
                section.forEach { right.addView(it) }
                wRight += w
            }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(left, LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(24) })
            addView(right, LayoutParams(0, WRAP, 1f))
        }
    }

    /** Cheap height proxy for column balancing: total view count in the subtree. */
    private fun viewWeight(v: View): Int =
        if (v is ViewGroup) 1 + (0 until v.childCount).sumOf { viewWeight(v.getChildAt(it)) } else 1

    // endregion

    // region Screens tab

    private fun buildScreensTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        screenRows.clear()
        Layout.values().forEach { layout ->
            addView(buildLayoutRow(layout))
            addView(spacer(dp(14)))
        }
    }

    /**
     * One row per layout: a section header, an enable/disable checkbox, and (for
     * multi-screen layouts) a radio group choosing which slot is the main screen.
     * Placeholder: the "enabled" flag is not yet persisted — toggling shows the
     * intended interaction but the cycle still walks all layouts.
     */
    private fun buildLayoutRow(layout: Layout): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            // A self-contained section (header + controls) — a column-reflow boundary.
            tag = SECTION_MARKER
        }
        container.addView(sectionLabel(layout.displayName))
        val enable = CheckBox(context).apply {
            PanelStyle.tintControl(this)
            text = "Enabled in unlocked-mode cycle"
            setTextColor(LABEL_COLOR)
            isChecked = WorkspaceSettings.layoutEnabledFor(layout)
            setOnCheckedChangeListener { _, isChecked ->
                // Guard against turning the last enabled layout off — leaves the
                // workspace nowhere to switch to. Revert and toast.
                if (!isChecked && WorkspaceSettings.enabledLayouts().let { it.size == 1 && it.first() == layout }) {
                    this.isChecked = true
                    Toast.makeText(
                        context,
                        "At least one layout must stay enabled",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnCheckedChangeListener
                }
                WorkspaceSettings.setLayoutEnabledFor(layout, isChecked)
                // Active layout just got disabled → controller switches to the next
                // enabled one. No-op when the change doesn't affect the active layout.
                WorkspaceController.applyEnabledLayoutsChanged()
            }
        }
        container.addView(enable)
        val mainGroup: RadioGroup?
        val mainButtons: Map<Int, RadioButton>
        if (layout.screens.size > 1) {
            container.addView(spacer(dp(8)))
            container.addView(subLabel("Main screen"))
            val rg = RadioGroup(context).apply { orientation = HORIZONTAL }
            val map = mutableMapOf<Int, RadioButton>()
            val current = WorkspaceSettings.mainScreenFor(layout)
            for (i in layout.screens.indices) {
                val rb = RadioButton(context).apply {
                    PanelStyle.tintControl(this)
                    text = mainScreenLabelFor(layout, i)
                    setTextColor(LABEL_COLOR)
                    id = View.generateViewId()
                    isChecked = i == current
                    layoutParams = RadioGroup.LayoutParams(WRAP, WRAP).apply {
                        marginEnd = dp(12)
                    }
                    setOnClickListener {
                        WorkspaceSettings.setMainScreenFor(layout, i)
                        renderScreensTab()
                    }
                }
                rg.addView(rb)
                map[i] = rb
            }
            container.addView(rg)
            mainGroup = rg
            mainButtons = map
        } else {
            mainGroup = null
            mainButtons = emptyMap()
        }
        screenRows[layout] = ScreenRow(enable, mainGroup, mainButtons)
        return container
    }

    private fun renderScreensTab() {
        screenRows.forEach { (layout, row) ->
            val current = WorkspaceSettings.mainScreenFor(layout)
            row.mainButtons.forEach { (i, rb) -> rb.isChecked = i == current }
            // Reflect any external toggles (e.g. a future reset-defaults action).
            val enabled = WorkspaceSettings.layoutEnabledFor(layout)
            if (row.enableCheck.isChecked != enabled) row.enableCheck.isChecked = enabled
        }
    }

    private fun mainScreenLabelFor(layout: Layout, idx: Int): String = when (layout) {
        Layout.SINGLE, Layout.SINGLE_WIDE -> "Screen"
        Layout.TWO_SBS -> if (idx == 0) "Left" else "Right"
        Layout.THREE_SBS -> when (idx) { 0 -> "Left"; 1 -> "Center"; else -> "Right" }
        Layout.THREE_VHV -> when (idx) { 0 -> "Left (V)"; 1 -> "Center (H)"; else -> "Right (V)" }
    }

    // endregion

    // region Desktop tab

    /** Per-desktop UI rows on the Desktop tab, kept so we can re-render selections. */
    private data class DesktopRow(
        val deskIdx: Int,
        val thumbs: Map<String, View>,
        val customThumb: View,
        val modeButtons: Map<PlacementMode, TextView>,
    )

    private val desktopRows = mutableListOf<DesktopRow>()

    /**
     * Wallpaper picker per desktop index (0/1/2) + placement mode. Per-desktop, not
     * per-(layout, screen), so the wallpaper follows the desktop the way pinned
     * shortcuts do.
     */
    private fun buildDesktopTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        desktopRows.clear()
        for (deskIdx in 0..2) {
            addView(sectionLabel("Desktop ${deskIdx + 1}"))
            addView(buildDesktopRow(deskIdx))
            addView(spacer(dp(16)))
        }
    }

    private fun buildDesktopRow(deskIdx: Int): View {
        val container = LinearLayout(context).apply { orientation = VERTICAL }
        container.addView(subLabel("Wallpaper"))

        val thumbStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
        val thumbs = mutableMapOf<String, View>()
        DesktopWallpaperStore.BUNDLED_ASSETS.forEach { asset ->
            val thumb = buildBundledThumbnail(asset) {
                DesktopWallpaperStore.setSource(deskIdx, WallpaperSource.Asset(asset))
                renderDesktopRow(deskIdx)
            }
            val lp = LayoutParams(dp(THUMB_W_DP), dp(THUMB_H_DP)).apply { marginEnd = dp(8) }
            thumbStrip.addView(thumb, lp)
            thumbs[asset] = thumb
        }
        val customThumb = buildCustomThumbnail {
            val hook = WorkspaceController.pickWallpaperFromDevice
            if (hook == null) {
                Toast.makeText(
                    context,
                    "Open the phone app to pick an image",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                hook(deskIdx)
                Toast.makeText(context, "Pick an image on your phone", Toast.LENGTH_SHORT).show()
            }
        }
        thumbStrip.addView(
            customThumb,
            LayoutParams(dp(THUMB_W_DP), dp(THUMB_H_DP)).apply { marginEnd = dp(8) },
        )
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(thumbStrip, LayoutParams(WRAP, WRAP))
        }
        container.addView(scroll, LayoutParams(MATCH, WRAP))
        container.addView(spacer(dp(10)))

        container.addView(subLabel("Placement"))
        val placement = LinearLayout(context).apply { orientation = HORIZONTAL }
        val modeButtons = mutableMapOf<PlacementMode, TextView>()
        PlacementMode.values().forEachIndexed { i, mode ->
            val btn = pillButton(mode.displayName) {
                DesktopWallpaperStore.setMode(deskIdx, mode)
                renderDesktopRow(deskIdx)
            }
            val lp = weightedLp(); if (i > 0) lp.marginStart = dp(6)
            placement.addView(btn, lp)
            modeButtons[mode] = btn
        }
        container.addView(placement)
        desktopRows.add(DesktopRow(deskIdx, thumbs, customThumb, modeButtons))
        renderDesktopRow(deskIdx)
        return container
    }

    /**
     * Re-style the selected thumbnail and the active placement pill for [deskIdx]
     * from the current store state.
     */
    private fun renderDesktopRow(deskIdx: Int) {
        val row = desktopRows.firstOrNull { it.deskIdx == deskIdx } ?: return
        val spec = DesktopWallpaperStore.specFor(deskIdx)
        // Thumbnail selection: highlight the active bundled asset, or the "From device"
        // tile when a URI source is in use.
        row.thumbs.forEach { (asset, view) ->
            val active = spec.source is WallpaperSource.Asset && spec.source.assetPath == asset
            styleThumb(view, active)
        }
        styleThumb(row.customThumb, spec.source is WallpaperSource.Uri)
        row.modeButtons.forEach { (mode, btn) ->
            stylePill(btn, mode == spec.mode)
        }
    }

    private fun buildBundledThumbnail(assetPath: String, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                // Decode downsampled — the thumbnail strip shows one bitmap per
                // bundled wallpaper across three desktop rows, so loading each at
                // full 1920x1080 would burn ~8 MB apiece and risk OOM. ~4x down is
                // plenty for a small tile.
                val bitmap = runCatching {
                    context.assets.open(assetPath).use {
                        BitmapFactory.decodeStream(
                            it, null,
                            BitmapFactory.Options().apply { inSampleSize = 4 },
                        )
                    }
                }.getOrNull()
                if (bitmap != null) setImageBitmap(bitmap) else setBackgroundColor(UxSpaceTheme.panelPlaceholder)
            }
            addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { onClick() }
            styleThumb(this, active = false)
        }

    private fun buildCustomThumbnail(onClick: () -> Unit): View = FrameLayout(context).apply {
        val label = TextView(context).apply {
            text = "From\nphone"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(LABEL_COLOR)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(UxSpaceTheme.panelPlaceholder)
        }
        addView(
            label,
            FrameLayout.LayoutParams(MATCH, MATCH).apply { gravity = Gravity.CENTER },
        )
        setOnClickListener { onClick() }
        styleThumb(this, active = false)
    }

    private fun styleThumb(view: View, active: Boolean) {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setStroke(
                dp(if (active) 3 else 1),
                if (active) PILL_ACTIVE_BG else UxSpaceTheme.controlTrack,
            )
        }
        view.foreground = ring
    }

    // endregion

    // region Input tab

    /**
     * Trackpad + cursor tuning. Each control writes through [WorkspaceSettings] and the
     * `WorkspaceSettings.addChangeListener` registered in [UxSpaceApp] pushes the new
     * value into [WorkspaceController]'s live volatile fields. So edits take effect on
     * the next cursor / scroll / long-press the renderer or trackpad processes.
     */
    private fun buildInputTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(sectionLabel("Cursor"))
        addView(
            tunedSliderRow(
                label = "Sensitivity",
                min = 0.25f, max = 3.0f,
                current = WorkspaceSettings.cursorSensitivity(),
                format = { "${(it * 100).toInt()}%" },
            ) { WorkspaceSettings.setCursorSensitivity(it) },
        )
        addView(
            tunedSliderRow(
                label = "Idle hide",
                min = 1f, max = 30f,
                current = WorkspaceSettings.cursorIdleSeconds().toFloat(),
                format = { "${it.toInt()} s" },
            ) { WorkspaceSettings.setCursorIdleSeconds(it.toInt()) },
        )
        addView(spacer(dp(14)))

        addView(sectionLabel("Scrolling"))
        addView(
            tunedSliderRow(
                label = "Scroll speed",
                min = 2f, max = 36f,
                current = WorkspaceSettings.scrollSensitivity(),
                format = { "${"%.1f".format(it / 12f)}×" },
            ) { WorkspaceSettings.setScrollSensitivity(it) },
        )
        addView(
            tunedSliderRow(
                label = "Flick sensitivity",
                min = 0.002f, max = 0.04f,
                current = WorkspaceSettings.flickSensitivity(),
                format = { "${"%.1f".format(it / 0.01f)}×" },
            ) { WorkspaceSettings.setFlickSensitivity(it) },
        )
        addView(spacer(dp(14)))

        addView(sectionLabel("Gestures"))
        addView(
            tunedSliderRow(
                label = "Long-press",
                min = 200f, max = 1500f,
                current = WorkspaceSettings.longPressMs().toFloat(),
                format = { "${it.toInt()} ms" },
            ) { WorkspaceSettings.setLongPressMs(it.toLong()) },
        )
        addView(
            backedToggleRow(
                label = "Two-finger pinch zoom",
                current = WorkspaceSettings.pinchEnabled(),
            ) { WorkspaceSettings.setPinchEnabled(it) },
        )
    }

    // endregion

    // region View tab

    /**
     * Camera / projection. Keeps the existing wired controls (view-mode toggle,
     * zoom, render-band) plus a placeholder for the auto-recenter-on-unlock toggle.
     */
    private fun buildViewTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        viewModePinned = pillButton("Pinned to head") {
            WorkspaceController.setViewMode(ViewMode.PINNED)
            renderViewMode(ViewMode.PINNED)
        }
        viewModeFree = pillButton("Free in world") {
            WorkspaceController.setViewMode(ViewMode.FREE)
            renderViewMode(ViewMode.FREE)
        }
        val modeRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        modeRow.addView(viewModePinned, weightedLp().apply { marginEnd = dp(8) })
        modeRow.addView(viewModeFree, weightedLp())
        addView(sectionLabel("View mode"))
        addView(modeRow)
        addView(spacer(dp(14)))

        addView(sectionLabel("Workspace zoom"))
        zoomLabel = TextView(context).apply {
            setTextColor(LABEL_COLOR)
            textSize = 13f
        }
        val minusBtn = pillButton("−") { WorkspaceController.cycleScreenBand() }
        val plusBtn = pillButton("+") {
            // No backward API yet — cycle forward (N − 1) times.
            repeat(ZOOM_BACKWARD_STEPS) { WorkspaceController.cycleScreenBand() }
        }
        val zoomRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(zoomLabel, LayoutParams(0, WRAP, 1f))
            addView(minusBtn, LayoutParams(WRAP, WRAP).apply { marginStart = dp(8); minimumWidth = dp(48) })
            addView(plusBtn, LayoutParams(WRAP, WRAP).apply { marginStart = dp(8); minimumWidth = dp(48) })
        }
        addView(zoomRow)
        addView(spacer(dp(14)))

        addView(sectionLabel("Screen fill"))
        addView(
            TextView(context).apply {
                text = "How much of the glasses' display the workspace fills. " +
                    "100% is edge-to-edge; lower keeps it inside the sharper centre of the lenses."
                textSize = 11f
                setTextColor(LABEL_COLOR)
            },
        )
        addView(spacer(dp(6)))
        fillPills.clear()
        val fillRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        SCREEN_FILL_OPTIONS.forEachIndexed { i, pct ->
            val fraction = pct / 100f
            val pill = pillButton("$pct%") {
                // setScreenFill persists AND (via the WorkspaceSettings change-listener →
                // loadInputAndViewSettings) pushes the band to the live renderer; the direct
                // setScreenBand makes the change instant even if the listener is slow.
                WorkspaceSettings.setScreenFill(fraction)
                WorkspaceController.setScreenBand(fraction)
                renderScreenFill(fraction)
            }
            fillPills.add(fraction to pill)
            fillRow.addView(pill, LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        addView(fillRow)
        renderScreenFill(WorkspaceSettings.screenFill())
        addView(spacer(dp(14)))

        addView(sectionLabel("Behaviour"))
        addView(
            backedToggleRow(
                label = "Auto-recenter on unlock",
                current = WorkspaceSettings.autoRecenterOnUnlock(),
            ) { WorkspaceSettings.setAutoRecenterOnUnlock(it) },
        )
        addView(
            backedToggleRow(
                label = "6DOF head tracking — positional parallax (applies on reconnect)",
                current = WorkspaceSettings.carina6Dof(),
            ) { WorkspaceSettings.setCarina6Dof(it) },
        )
    }

    private fun renderViewMode(mode: ViewMode) {
        if (!::viewModePinned.isInitialized) return
        stylePill(viewModePinned, mode == ViewMode.PINNED)
        stylePill(viewModeFree, mode == ViewMode.FREE)
    }

    private fun renderZoom(zoom: Float) {
        if (::zoomLabel.isInitialized) zoomLabel.text = "${(zoom * 100).toInt()}%"
    }

    /** Highlight the "Screen fill" pill closest to [fraction]. */
    private fun renderScreenFill(fraction: Float) {
        if (fillPills.isEmpty()) return
        val nearest = fillPills.minByOrNull { kotlin.math.abs(it.first - fraction) }?.first
        fillPills.forEach { (f, pill) -> stylePill(pill, f == nearest) }
    }

    // endregion

    // region Windows tab

    private fun buildWindowsTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(sectionLabel("Stacking"))
        addView(
            tunedSliderRow(
                label = "Max windows per slot",
                min = 1f, max = 10f,
                current = WorkspaceSettings.maxWindowsPerSlot().toFloat(),
                format = { it.toInt().toString() },
            ) { WorkspaceSettings.setMaxWindowsPerSlot(it.toInt()) },
        )
        addView(spacer(dp(14)))

        addView(sectionLabel("App display"))
        addView(
            tunedSliderRow(
                label = "Window DPI",
                min = WorkspaceSettings.APP_DISPLAY_DPI_MIN.toFloat(),
                max = WorkspaceSettings.APP_DISPLAY_DPI_MAX.toFloat(),
                current = WorkspaceSettings.appDisplayDpi().toFloat(),
                format = { snapAppDpi(it).toString() },
            ) { WorkspaceSettings.setAppDisplayDpi(snapAppDpi(it)) },
        )
        addView(
            TextView(context).apply {
                text = "Applies to newly-launched windows. Existing windows keep their DPI."
                textSize = 11f
                setTextColor(SECTION_LABEL)
                layoutParams = LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
            },
        )
        addView(spacer(dp(14)))

        addView(sectionLabel("Interaction"))
        addView(
            backedToggleRow(
                label = "Snap zones (drag to edge)",
                current = WorkspaceSettings.snapZonesEnabled(),
            ) { WorkspaceSettings.setSnapZonesEnabled(it) },
        )
        addView(
            backedToggleRow(
                label = "Resize handles",
                current = WorkspaceSettings.resizeHandlesEnabled(),
            ) { WorkspaceSettings.setResizeHandlesEnabled(it) },
        )
    }

    /** Snap a raw slider value to the nearest [WorkspaceSettings.APP_DISPLAY_DPI_STEP]. */
    private fun snapAppDpi(v: Float): Int {
        val step = WorkspaceSettings.APP_DISPLAY_DPI_STEP
        return (Math.round(v / step) * step)
            .coerceIn(WorkspaceSettings.APP_DISPLAY_DPI_MIN, WorkspaceSettings.APP_DISPLAY_DPI_MAX)
    }

    // endregion

    // region Taskbar tab

    private fun buildTaskbarTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(sectionLabel("Behavior"))
        addView(
            backedToggleRow(
                label = "Auto-hide",
                current = WorkspaceSettings.taskbarAutoHide(),
            ) { WorkspaceSettings.setTaskbarAutoHide(it) },
        )
        addView(spacer(dp(14)))

        addView(sectionLabel("Tray indicators"))
        addView(
            backedToggleRow(
                label = "Clock",
                current = WorkspaceSettings.showTaskbarClock(),
            ) { WorkspaceSettings.setShowTaskbarClock(it) },
        )
        addView(
            backedToggleRow(
                label = "Battery",
                current = WorkspaceSettings.showTaskbarBattery(),
            ) { WorkspaceSettings.setShowTaskbarBattery(it) },
        )
        addView(
            backedToggleRow(
                label = "Volume",
                current = WorkspaceSettings.showTaskbarVolume(),
            ) { WorkspaceSettings.setShowTaskbarVolume(it) },
        )
        addView(spacer(dp(14)))

        addView(sectionLabel("Clock format"))
        val clockRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val twelveBtn = pillButton("12h") {
            WorkspaceSettings.setClockUse24h(false)
            renderClockFormat()
        }
        val twentyFourBtn = pillButton("24h") {
            WorkspaceSettings.setClockUse24h(true)
            renderClockFormat()
        }
        clockFormatButtons = mapOf(false to twelveBtn, true to twentyFourBtn)
        clockRow.addView(twelveBtn, weightedLp())
        clockRow.addView(twentyFourBtn, weightedLp().apply { marginStart = dp(6) })
        addView(clockRow)
        addView(spacer(dp(8)))
        addView(
            backedToggleRow(
                label = "Show date",
                current = WorkspaceSettings.showTaskbarDate(),
            ) { WorkspaceSettings.setShowTaskbarDate(it) },
        )
        renderClockFormat()
    }

    private var clockFormatButtons: Map<Boolean, TextView> = emptyMap()
    private fun renderClockFormat() {
        val use24 = WorkspaceSettings.clockUse24h()
        clockFormatButtons.forEach { (is24, btn) -> stylePill(btn, is24 == use24) }
    }

    // endregion

    // region Capture tab

    private fun buildCaptureTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(sectionLabel("Snapshot"))
        val snapRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        snapRow.addView(pillButton("Capture now") { WorkspaceController.capture() }, weightedLp().apply { marginEnd = dp(8) })
        recordingButton = pillButton("Record") {
            val nowRecording = WorkspaceController.toggleRecording()
            renderRecording(nowRecording)
        }
        snapRow.addView(recordingButton, weightedLp())
        addView(snapRow)
        addView(spacer(dp(14)))

        addView(sectionLabel("Recording"))
        addView(
            tunedSliderRow(
                label = "Frame interval",
                min = 1f, max = 60f,
                current = WorkspaceSettings.recordingFrameInterval().toFloat(),
                format = { "every ${it.toInt()}" },
            ) { WorkspaceSettings.setRecordingFrameInterval(it.toInt()) },
        )
        addView(
            backedToggleRow(
                label = "Annotate captures with debug overlay",
                current = WorkspaceSettings.captureDebugOverlay(),
            ) { WorkspaceSettings.setCaptureDebugOverlay(it) },
        )
    }

    private fun renderRecording(recording: Boolean) {
        if (!::recordingButton.isInitialized) return
        recordingButton.text = if (recording) "Stop recording" else "Record"
        stylePill(recordingButton, recording)
    }

    // endregion

    // region Privileged tab

    private lateinit var helperStatusLabel: TextView

    private val privilegedListener: () -> Unit = {
        mainHandler.post {
            if (::helperStatusLabel.isInitialized) {
                helperStatusLabel.text = com.uxspace.privileged.PrivilegedService.state.name
            }
        }
    }

    private fun buildPrivilegedTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(sectionLabel("Helper status"))
        helperStatusLabel = TextView(context).apply {
            text = com.uxspace.privileged.PrivilegedService.state.name
            setTextColor(LABEL_COLOR)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        addView(helperStatusLabel)
        addView(spacer(dp(14)))
        addView(sectionLabel("Actions"))
        addView(
            pillButton("Restart helper") {
                // ensureRunning is idempotent — when the helper is already up it just
                // refreshes the state; when it isn't, it kicks off discovery + connect.
                com.uxspace.privileged.PrivilegedService.ensureRunning()
                Toast.makeText(context, "Restarting helper…", Toast.LENGTH_SHORT).show()
            },
        )
        addView(spacer(dp(8)))
        addView(
            pillButton("Re-pair (Wireless Debugging)") {
                runCatching {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.onFailure {
                    Toast.makeText(
                        context, "Open Developer options on the phone", Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }

    // endregion

    // region About tab

    private fun buildAboutTab(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(sectionLabel("Build"))
        addView(
            TextView(context).apply {
                text = aboutText()
                textSize = 12f
                setTextColor(LABEL_COLOR)
            },
        )
        addView(spacer(dp(14)))
        addView(sectionLabel("Updates"))
        // Routes to MainActivity (phone-side) via the controller hook; the update dialog and
        // system install screen appear on the phone, where the final install tap must happen.
        addView(
            pillButton("Check for updates") {
                val wired = WorkspaceController.checkForUpdates
                if (wired != null) {
                    wired()
                    Toast.makeText(context, "Checking for updates… (see phone)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Updates unavailable right now", Toast.LENGTH_SHORT).show()
                }
            },
        )
        addView(spacer(dp(14)))
        addView(sectionLabel("Maintenance"))
        // Two-tap confirm: first tap arms, second tap within ARM_MS commits. Keeps the
        // panel inside its modal box (no popup dialogs).
        var armed = false
        var armResetAt = 0L
        val resetBtn = pillButton("Reset all settings to defaults") {}
        resetBtn.setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (armed && now - armResetAt < RESET_CONFIRM_MS) {
                WorkspaceSettings.resetAll()
                armed = false
                resetBtn.text = "Reset all settings to defaults"
                stylePill(resetBtn, active = false)
                Toast.makeText(context, "Settings reset", Toast.LENGTH_SHORT).show()
            } else {
                armed = true
                armResetAt = now
                resetBtn.text = "Tap again to confirm"
                stylePill(resetBtn, active = true)
                resetBtn.postDelayed({
                    if (armed && android.os.SystemClock.uptimeMillis() - armResetAt >= RESET_CONFIRM_MS) {
                        armed = false
                        resetBtn.text = "Reset all settings to defaults"
                        stylePill(resetBtn, active = false)
                    }
                }, RESET_CONFIRM_MS)
            }
        }
        addView(resetBtn)
    }

    private fun aboutText(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val time = java.text.SimpleDateFormat("MMM d  HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(info.lastUpdateTime))
        "UxSpace build $time"
    }.getOrDefault("UxSpace")

    // endregion

    // region UI helpers

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 11f
        setTextColor(SECTION_LABEL)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        layoutParams = LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        // Marks the start of a section, so reflowIntoColumns() can group a label with the
        // controls that follow it and never split them across the two columns.
        tag = SECTION_MARKER
    }

    private fun subLabel(text: String): View = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(LABEL_COLOR)
        layoutParams = LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
    }

    private fun spacer(height: Int): View = View(context).apply {
        layoutParams = LayoutParams(MATCH, height)
    }

    private fun pillButton(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 13f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
        stylePill(this, active = false)
    }

    private fun stylePill(tv: TextView, active: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (active) PILL_ACTIVE_BG else PILL_INACTIVE_BG)
        }
        tv.background = bg
        tv.setTextColor(if (active) PILL_ACTIVE_FG else PILL_INACTIVE_FG)
        tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /**
     * Labelled slider backed by storage. Maps the [min, max] real range onto a
     * 0..100 SeekBar; the current value is the third row item so the user sees
     * the live number. [onChanged] fires on every user-driven change.
     */
    private fun tunedSliderRow(
        label: String,
        min: Float,
        max: Float,
        current: Float,
        format: (Float) -> String,
        onChanged: (Float) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
        val name = TextView(context).apply {
            text = label; textSize = 12f; setTextColor(LABEL_COLOR)
        }
        val valueLabel = TextView(context).apply {
            text = format(current); textSize = 12f; setTextColor(LABEL_COLOR)
            minWidth = dp(56)
            gravity = Gravity.END
        }
        val span = max - min
        val seek = SeekBar(context).apply {
            PanelStyle.tintSeek(this)
            this.max = 100
            progress = (((current - min) / span) * 100f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + (value / 100f) * span
                    valueLabel.text = format(v)
                    onChanged(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        addView(name, LayoutParams(0, WRAP, 1f))
        addView(seek, LayoutParams(0, WRAP, 1.4f).apply { marginStart = dp(8); marginEnd = dp(8) })
        addView(valueLabel, LayoutParams(WRAP, WRAP))
    }

    /** Boolean toggle backed by storage. */
    private fun backedToggleRow(
        label: String,
        current: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
        addView(
            CheckBox(context).apply {
                PanelStyle.tintControl(this)
                text = label
                setTextColor(LABEL_COLOR)
                isChecked = current
                setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
            },
            LayoutParams(MATCH, WRAP),
        )
    }

    private fun weightedLp(): LayoutParams = LayoutParams(0, WRAP, 1f)

    // endregion

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val TITLE_COLOR = UxSpaceTheme.panelTitle
        const val SECTION_LABEL = UxSpaceTheme.panelSection
        const val LABEL_COLOR = UxSpaceTheme.panelLabel
        const val PILL_INACTIVE_BG = UxSpaceTheme.pillInactiveBg
        const val PILL_ACTIVE_BG = UxSpaceTheme.pillActiveBg
        const val PILL_INACTIVE_FG = UxSpaceTheme.pillInactiveFg
        const val PILL_ACTIVE_FG = UxSpaceTheme.pillActiveFg

        /** Discrete "Screen fill" percentages offered in Settings → View. */
        val SCREEN_FILL_OPTIONS = intArrayOf(100, 95, 90, 85, 80)

        const val ZOOM_BACKWARD_STEPS = 4

        // Wallpaper thumbnail size on the Desktop tab.
        const val THUMB_W_DP = 88
        const val THUMB_H_DP = 56

        /** How long the Reset button stays armed after the first tap. */
        const val RESET_CONFIRM_MS = 3_000L
    }
}
