package com.uxspace.desktop

import android.app.Presentation
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.uxspace.R
import com.uxspace.spatial.UxSpaceTheme
import com.uxspace.spatial.WorkspaceController
import com.uxspace.system.SystemStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop shown on the workspace's back plane — UxSpace's DeX-style home: a wallpaper and
 * a bottom taskbar with an app-drawer launcher, an icon per open app window, and a clock.
 *
 * It is a real Android view hierarchy on `Theme.DeviceDefault`, so on a Samsung device the
 * widgets are styled as One UI. The app drawer is a separate overlay ([DrawerPresentation])
 * so it can float in front of the app windows; the launcher button just toggles it.
 */
class DesktopPresentation(
    outerContext: Context,
    display: Display,
    /**
     * Index of the preset slot this Presentation belongs to (0..N-1). Used to filter
     * [WorkspaceController.onAppLaunched] events so each slot's taskbar shows only the
     * apps actually launched onto that slot.
     */
    private val slotIdx: Int = 0,
    /**
     * Whether to render the taskbar / status row at the bottom of this slot. The V slots
     * of the V/H/V preset turn this off so the H slot owns the taskbar; SBS / Single
     * presets default to true.
     */
    private val showTaskbar: Boolean = true,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var runningApps: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var outputButton: ImageButton

    /**
     * Wallpaper layer — kept as a field so we can hide it while any app is running on
     * this slot. With the translucent Presentation theme, hiding the wallpaper view
     * lets the launched activity (which renders *behind* the Presentation's window in
     * SurfaceFlinger z-order — TYPE_PRIVATE_PRESENTATION sits above TYPE_BASE_APPLICATION)
     * show through. The taskbar stays opaque on top.
     */
    private lateinit var wallpaper: View

    /** Taskbar icons for the open app windows, keyed by package, in launch order. */
    private val runningIcons = LinkedHashMap<String, View>()

    /** This screen's focused window, the target of the taskbar's Back; null when none. */
    private var focusedPackage: String? = null

    /** Divider before the running-app strip; shown only while [runningIcons] is non-empty. */
    private var runningDivider: View? = null

    /** Display label per running package — for the window-chrome title text. */
    private val runningLabels = HashMap<String, String>()

    private val chromeListener = object : WindowChromeView.Listener {
        override fun onBack(packageName: String) {
            WorkspaceController.sendBackToApp(packageName)
        }
        override fun onMinimize(packageName: String) {
            WorkspaceController.minimizeWindow(packageName)
        }
        override fun onMaximize(packageName: String) {
            WorkspaceController.toggleMaximizeWindow(packageName)
        }
        override fun onClose(packageName: String) {
            WorkspaceController.closeAppByPackage(packageName)
        }
    }

    private val systemStatusListener = object : SystemStatus.Listener {
        override fun onSystemStatusChanged() {
            mainHandler.post { refreshStatusTray() }
        }
    }

    /** Refreshes the taskbar clock; re-posts itself while the desktop is shown. */
    private val clockTick = object : Runnable {
        override fun run() {
            if (::clock.isInitialized) clock.text = clockText()
            mainHandler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private val appLaunchedListener: (String, String, Int) -> Unit =
        { packageName, label, launchSlot ->
            if (launchSlot == slotIdx) {
                android.util.Log.i(
                    "UxSpace/Launch",
                    "14) DesktopPresentation slot=$slotIdx adding taskbar icon for $packageName",
                )
                mainHandler.post { addRunningApp(packageName, label) }
            }
        }

    private val appClosedListener: (String) -> Unit = { packageName ->
        android.util.Log.i(
            "UxSpace/Launch",
            "X) DesktopPresentation slot=$slotIdx removing taskbar icon for $packageName",
        )
        mainHandler.post { removeRunningApp(packageName) }
    }

    /** Drawer view embedded as a child of the root; visibility driven by the controller. */
    private lateinit var drawer: DrawerView

    /** Settings panel embedded alongside the drawer — same modal model. */
    private lateinit var settings: SettingsView

    /** Audio popup embedded alongside the drawer / settings — same modal model. */
    private lateinit var audio: AudioPanelView

    /** Full-screen eye chart; covers everything on this slot while open. */
    private lateinit var eyeChart: EyeChartView

    /** Pinned-app icons painted directly on the wallpaper for this screen's desktop. */
    private lateinit var shortcuts: DesktopShortcutsView

    /** Slot Presentation's root FrameLayout — chrome / frame children are added here. */
    private lateinit var root: FrameLayout

    /** Built by [buildTaskbar]; referenced so [applyChromeBoundsList] can keep it on top. */
    private var taskbarContainer: View? = null

    /** Build-stamp label in the corner; tracked for z-order. */
    private var versionLabel: View? = null

    /**
     * Per-window chrome + frame views, keyed by package name. Each running
     * window on this slot has its own `WindowChromeView` (title bar with
     * icon, label, buttons) and `windowFrame` (3 px border ring). The list
     * grows / shrinks as windows launch / close, and the views' alpha is
     * lowered on non-focused windows so the top one reads clearly.
     */
    private val windowChromes = mutableMapOf<String, ChromeViewSet>()

    private data class ChromeViewSet(val chrome: WindowChromeView, val frame: View)

    /**
     * Translucent preview rectangle shown during a window drag to hint at the
     * snap zone the window will tile into on release (left half / right half /
     * maximised). Positioned on demand by [snapPreviewListener]; hidden when
     * the cursor isn't in any snap zone.
     */
    private lateinit var snapPreview: View

    /**
     * Transparent click-catcher sized to the whole screen. Shown on *every* slot
     * whenever any modal panel (drawer or settings) is open anywhere, so a click on any
     * monitor's wallpaper — including a different monitor from the one hosting the
     * panel — closes it. Taskbar + drawer + settings sit above the scrim in z-order so
     * their own clicks still register.
     */
    private lateinit var scrim: View

    private val drawerStateListener: (Boolean, Int) -> Unit = { open, screenIdx ->
        mainHandler.post {
            val showDrawerHere = open && screenIdx == slotIdx
            if (::drawer.isInitialized) {
                drawer.visibility = if (showDrawerHere) View.VISIBLE else View.GONE
            }
            refreshScrim()
            android.util.Log.i(
                "UxSpace/Drawer",
                "screen=$slotIdx listener fired open=$open targetScreen=$screenIdx " +
                    "showDrawerHere=$showDrawerHere",
            )
        }
    }

    private val settingsStateListener: (Boolean, Int) -> Unit = { open, screenIdx ->
        mainHandler.post {
            val showHere = open && screenIdx == slotIdx
            if (::settings.isInitialized) {
                settings.visibility = if (showHere) View.VISIBLE else View.GONE
            }
            refreshScrim()
        }
    }

    private val optometryStateListener: (Boolean, Int) -> Unit = { open, screenIdx ->
        mainHandler.post {
            if (!::eyeChart.isInitialized) return@post
            val showHere = open && screenIdx == slotIdx
            if (showHere && eyeChart.visibility != View.VISIBLE) {
                eyeChart.reshuffle()
                root.bringChildToFront(eyeChart)
                taskbarContainer?.let { root.bringChildToFront(it) }
            }
            eyeChart.visibility = if (showHere) View.VISIBLE else View.GONE
        }
    }

    private val audioStateListener: (Boolean, Int) -> Unit = { open, screenIdx ->
        mainHandler.post {
            val showHere = open && screenIdx == slotIdx
            if (::audio.isInitialized) {
                audio.visibility = if (showHere) View.VISIBLE else View.GONE
            }
            refreshScrim()
        }
    }

    /** Context menu created lazily on first right-click; rebuilt on each open
     *  so view-mode-dependent rows (Cycle layout, Recenter) reflect the latest
     *  state. Positioned at the click coordinates within the slot. */
    private var contextMenu: View? = null

    private val contextMenuStateListener: (Boolean, Int, Float, Float) -> Unit =
        { open, screenIdx, pxX, pxY ->
            mainHandler.post {
                val showHere = open && screenIdx == slotIdx
                if (showHere) {
                    contextMenu?.let { root.removeView(it) }
                    val menu = buildContextMenu()
                    contextMenu = menu
                    // Measure first so we can flip / clamp the position to keep the
                    // whole menu on-screen — without this, a click near the bottom
                    // or right edge crops the menu. Falls back to displayMetrics
                    // if root hasn't been laid out yet.
                    val unspecified = View.MeasureSpec.makeMeasureSpec(
                        0, View.MeasureSpec.UNSPECIFIED,
                    )
                    menu.measure(unspecified, unspecified)
                    val menuW = menu.measuredWidth
                    val menuH = menu.measuredHeight
                    val rootW = if (root.width > 0) root.width
                        else context.resources.displayMetrics.widthPixels
                    val rootH = if (root.height > 0) root.height
                        else context.resources.displayMetrics.heightPixels
                    val x = pxX.toInt()
                    val y = pxY.toInt()
                    val left = if (x + menuW <= rootW) x else (x - menuW).coerceAtLeast(0)
                    val top = if (y + menuH <= rootH) y else (y - menuH).coerceAtLeast(0)
                    val lp = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                        leftMargin = left.coerceAtMost((rootW - menuW).coerceAtLeast(0))
                        topMargin = top.coerceAtMost((rootH - menuH).coerceAtLeast(0))
                    }
                    root.addView(menu, lp)
                } else {
                    contextMenu?.let { root.removeView(it) }
                    contextMenu = null
                }
                refreshScrim()
            }
        }

    /**
     * Position + size the chrome bar from the active window's bounds on this slot.
     * Renderer calls `WorkspaceController.notifyWindowBoundsChanged(slotIdx, bounds)`
     * whenever an app is launched, moved, or closed on the slot; we mirror those
     * bounds into the chrome view's FrameLayout params so the title bar sits exactly
     * above the activity quad. A null payload means there's no window on this slot —
     * we hide the chrome.
     */
    private val windowBoundsListener: (Int, List<WorkspaceController.WindowBounds>) -> Unit =
        { boundsSlot, bounds ->
            if (boundsSlot == slotIdx) {
                // notifyWindowBoundsChanged already fires on the main thread
                // (the renderer posts it that way), so call directly — an
                // extra mainHandler.post adds a frame of lag visible during
                // drag as a gap between the chrome strip and the activity.
                applyChromeBoundsList(bounds)
            }
        }

    private val snapPreviewListener: (WorkspaceController.SnapPreview?) -> Unit =
        { preview ->
            if (::snapPreview.isInitialized) {
                if (preview == null || preview.slotIdx != slotIdx) {
                    snapPreview.visibility = View.GONE
                } else {
                    snapPreview.layoutParams = FrameLayout.LayoutParams(
                        preview.widthPx, preview.heightPx,
                    ).apply {
                        leftMargin = preview.xPx
                        topMargin = preview.yPx
                        gravity = Gravity.TOP or Gravity.START
                    }
                    snapPreview.visibility = View.VISIBLE
                }
            }
        }

    private fun applyChromeBoundsList(boundsList: List<WorkspaceController.WindowBounds>) {
        focusedPackage = boundsList.firstOrNull { it.focused }?.packageName
        if (!::root.isInitialized) return
        // Drop chrome / frame pairs for any package no longer in the list — its
        // window was closed or moved off this slot.
        val seen = boundsList.mapTo(mutableSetOf()) { it.packageName }
        val stale = windowChromes.keys.filter { it !in seen }.toList()
        stale.forEach { pkg ->
            windowChromes.remove(pkg)?.let { set ->
                root.removeView(set.chrome)
                root.removeView(set.frame)
            }
        }
        // Position / create each window's chrome + frame in z-order (last =
        // topmost). bringChildToFront moves the matching pair to the front of
        // root's child list each iteration, so by the end the topmost
        // window's chrome + frame are above all others.
        boundsList.forEach { bounds ->
            val set = windowChromes.getOrPut(bounds.packageName) {
                createWindowChromeSet(bounds.packageName)
            }
            positionWindowChromeSet(set, bounds)
            // Z-order: bring this window's frame and chrome above any earlier
            // entries in the list. Frame first (it sits *behind* the chrome
            // already from view-tree order), then chrome on top.
            root.bringChildToFront(set.frame)
            root.bringChildToFront(set.chrome)
            // Taskbar and modal panels need to stay on top of the windows —
            // re-raise them after each window's z-rank pass.
        }
        if (boundsList.isNotEmpty()) {
            // Restore taskbar / modals to the very top of the view tree so a
            // window's chrome doesn't cover them.
            taskbarContainer?.let { root.bringChildToFront(it) }
            versionLabel?.let { root.bringChildToFront(it) }
            if (::drawer.isInitialized) root.bringChildToFront(drawer)
            if (::settings.isInitialized) root.bringChildToFront(settings)
            if (::audio.isInitialized) root.bringChildToFront(audio)
            if (::snapPreview.isInitialized) root.bringChildToFront(snapPreview)
            if (::eyeChart.isInitialized && eyeChart.visibility == View.VISIBLE) {
                root.bringChildToFront(eyeChart)
                taskbarContainer?.let { root.bringChildToFront(it) }
            }
        }
    }

    private fun createWindowChromeSet(packageName: String): ChromeViewSet {
        val frame = View(context).apply {
            visibility = View.GONE
            setBackgroundColor(UxSpaceTheme.windowBorder)
        }
        root.addView(frame, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        val chrome = WindowChromeView(context).apply {
            visibility = View.GONE
            setListener(chromeListener)
        }
        root.addView(
            chrome,
            FrameLayout.LayoutParams(MATCH, dp(CHROME_HEIGHT_DP), Gravity.TOP),
        )
        // Pre-bind the icon + label so the chrome paints immediately on the
        // first positionWindowChromeSet call instead of a frame later.
        chrome.bind(packageName, runningLabels[packageName], appIcon(packageName))
        return ChromeViewSet(chrome, frame)
    }

    private fun positionWindowChromeSet(
        set: ChromeViewSet,
        bounds: WorkspaceController.WindowBounds,
    ) {
        // Frame: WINDOW_BORDER_PX larger than the outer rect on every side.
        // Hidden in FULLSCREEN (no slot real estate to border against).
        val fullscreen = bounds.mode == WorkspaceController.WindowMode.FULLSCREEN
        if (fullscreen) {
            set.frame.visibility = View.GONE
        } else {
            set.frame.layoutParams = FrameLayout.LayoutParams(
                bounds.widthPx + 2 * WINDOW_BORDER_PX,
                bounds.heightPx + 2 * WINDOW_BORDER_PX,
            ).apply {
                leftMargin = bounds.xPx - WINDOW_BORDER_PX
                topMargin = bounds.yPx - WINDOW_BORDER_PX
                gravity = Gravity.TOP or Gravity.START
            }
            set.frame.visibility = View.VISIBLE
        }
        set.chrome.layoutParams = FrameLayout.LayoutParams(
            bounds.chromeBoundsW, bounds.chromeBoundsH,
        ).apply {
            leftMargin = bounds.chromeBoundsX
            topMargin = bounds.chromeBoundsY
            gravity = Gravity.TOP or Gravity.START
        }
        set.chrome.setWindowMode(bounds.mode)
        set.chrome.visibility = View.VISIBLE
        // Make sure the chrome's per-window listener context (icon, label,
        // package) matches THIS window before any button click fires.
        set.chrome.bind(
            bounds.packageName,
            runningLabels[bounds.packageName],
            appIcon(bounds.packageName),
        )
        // Visual focus cue — dim non-focused windows' chrome + frame so the
        // top window reads clearly.
        val alpha = if (bounds.focused) 1f else UNFOCUSED_CHROME_ALPHA
        set.chrome.alpha = alpha
        set.frame.alpha = alpha
    }

    /** Scrim visible whenever any modal panel is open anywhere. */
    private fun refreshScrim() {
        if (!::scrim.isInitialized) return
        val anyOpen =
            WorkspaceController.isDrawerOpen ||
                WorkspaceController.isSettingsOpen ||
                WorkspaceController.isAudioOpen ||
                WorkspaceController.isContextMenuOpen
        scrim.visibility = if (anyOpen) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reshape the Presentation's window so it doesn't eat input or paint over the
        // launched app:
        //   - Set the window background drawable to transparent (theme has an opaque
        //     DeviceDefault background which would render a solid panel over the
        //     activity stacked beneath us on the same trusted display).
        //   - FLAG_NOT_TOUCHABLE so the system input dispatcher skips this window
        //     when it picks a target on the display. UxSpace's WorkspaceRenderer
        //     calls dispatchTap() directly on this Presentation's view tree for
        //     taskbar / drawer / settings hits, which doesn't go through the system
        //     dispatcher and therefore isn't blocked by the flag.
        //   - FLAG_DISMISS_KEYGUARD because Samsung One UI parks a transient
        //     KEYGUARD_DIALOG window (type 2009) on every Presentation-capable
        //     secondary display; that window also doesn't carry NOT_TOUCHABLE, so
        //     touches injected at the display are eaten before they ever reach our
        //     activity. The dismiss flag chases it off our display.
        window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            @Suppress("DEPRECATION")
            w.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        root = FrameLayout(context)
        wallpaper = buildWallpaper()
        root.addView(wallpaper)
        // Pinned shortcuts ride above the wallpaper but below the scrim — a click on
        // an icon launches its app, a click on bare wallpaper still closes a drawer
        // via the scrim's outside-click handler.
        shortcuts = DesktopShortcutsView(context, slotIdx).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        root.addView(shortcuts)
        // Scrim sits between wallpaper and taskbar/drawer so a click on bare
        // wallpaper closes the drawer, but taskbar buttons + the drawer itself
        // still receive their own clicks (they're above the scrim in z-order).
        scrim = View(context).apply {
            visibility = View.GONE
            setOnClickListener {
                // Close whichever modal is open. Mutually exclusive so usually one of
                // these is a no-op; the order doesn't matter.
                if (WorkspaceController.isDrawerOpen) {
                    WorkspaceController.setDrawerOpen(false, slotIdx)
                }
                if (WorkspaceController.isContextMenuOpen) {
                    WorkspaceController.setContextMenuOpen(false, slotIdx)
                }
                if (WorkspaceController.isSettingsOpen) {
                    WorkspaceController.setSettingsOpen(false, slotIdx)
                }
                if (WorkspaceController.isAudioOpen) {
                    WorkspaceController.setAudioOpen(false, slotIdx)
                }
            }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        // Snap-preview overlay — translucent rectangle shown during drag at the
        // prospective tile zone. Created early so it sits in z-order below
        // per-window chrome / frame views (which are added on demand). The
        // applyChromeBoundsList pass re-raises it after rebuilding chrome
        // z-order each frame so it stays visible above any windows it overlaps.
        snapPreview = View(context).apply {
            visibility = View.GONE
            setBackgroundColor(SNAP_PREVIEW_COLOR)
        }
        root.addView(snapPreview, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        // Per-window chrome + frame views are created lazily by
        // [applyChromeBoundsList] when the renderer publishes a window's
        // bounds — one pair per package on this slot.
        // Eye chart: over the wallpaper, windows' chrome and panels, but under the
        // taskbar so its Back stays reachable while the chart is up.
        // Full height (no wallpaper strip while the taskbar is auto-hidden); its own
        // controls sit above the taskbar band.
        eyeChart = EyeChartView(context, bottomReserveDp = TASKBAR_HEIGHT_DP).apply {
            visibility = View.GONE
        }
        root.addView(eyeChart, FrameLayout.LayoutParams(MATCH, MATCH))
        if (showTaskbar) {
            taskbarContainer = buildTaskbar().also { root.addView(it) }
        }
        versionLabel = buildVersionLabel().also { root.addView(it) }
        // Drawer goes last so it sits on top of wallpaper + taskbar in the view tree.
        // Insets from screen edges so it doesn't cover the whole surface; tap outside
        // would land on the wallpaper (no close-on-outside yet — drawer closes when an
        // app is launched or on lock/unlock).
        // Drawer size: aspect-preserved fit inside 74% of the host screen's pixel
        // dims, with both the baseline cap and the aspect transposed to portrait on
        // a V slot. Without the transpose, a V slot (1080×1920) clamps effectiveH
        // to the landscape baseline H (1080) and keeps the landscape 870×800
        // aspect, producing a stubby horizontal drawer in the middle of a tall
        // screen. Cap rule unchanged: ultrawide / panoramic screens still get the
        // *same* drawer as a single 1920×1080 screen.
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val portraitHost = metrics.heightPixels > metrics.widthPixels
        val baselineW = if (portraitHost) DRAWER_BASELINE_SCREEN_H else DRAWER_BASELINE_SCREEN_W
        val baselineH = if (portraitHost) DRAWER_BASELINE_SCREEN_W else DRAWER_BASELINE_SCREEN_H
        val effectiveW = minOf(metrics.widthPixels, baselineW)
        val effectiveH = minOf(metrics.heightPixels, baselineH)
        val maxW = (effectiveW * DRAWER_SCREEN_FRACTION).toInt()
        val maxH = (effectiveH * DRAWER_SCREEN_FRACTION).toInt()
        val aspect = if (portraitHost) DRAWER_ASPECT_H / DRAWER_ASPECT_W
        else DRAWER_ASPECT_W / DRAWER_ASPECT_H
        var dw = maxW
        var dh = (maxW / aspect).toInt()
        if (dh > maxH) { dh = maxH; dw = (maxH * aspect).toInt() }
        drawer = DrawerView(context).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dw, dh, Gravity.CENTER)
        }
        root.addView(drawer)
        // Settings panel shares the drawer's size + centring rule — it's a sibling
        // modal that opens via the taskbar's Settings button (or workspace toolbar).
        settings = SettingsView(context).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dw, dh, Gravity.CENTER)
        }
        root.addView(settings)
        // Audio panel — same centred modal slot. Sized like the others so the
        // renderer's drawSlotModalOverlay can use the shared modal rect.
        audio = AudioPanelView(context).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dw, dh, Gravity.CENTER)
        }
        root.addView(audio)
        setContentView(root)
        // Tell the renderer where the drawer + settings panels sit on the slot's
        // Presentation surface — both share the same centred rectangle. The renderer
        // uses these bounds to re-composite the modal area on top of any window
        // stacked behind it, so the activity stays visible around the modal panel.
        val modalX = ((metrics.widthPixels - dw) / 2).coerceAtLeast(0)
        val modalY = ((metrics.heightPixels - dh) / 2).coerceAtLeast(0)
        WorkspaceController.notifyModalBoundsChanged(
            slotIdx,
            WorkspaceController.ModalBounds(modalX, modalY, dw, dh),
        )
        // Per-screen taskbar — only react to launches on this screen.
        WorkspaceController.addAppLaunchedListener(appLaunchedListener)
        WorkspaceController.addAppClosedListener(appClosedListener)
        WorkspaceController.addDrawerStateListener(drawerStateListener)
        WorkspaceController.addSettingsStateListener(settingsStateListener)
        WorkspaceController.addAudioStateListener(audioStateListener)
        WorkspaceController.addOptometryStateListener(optometryStateListener)
        WorkspaceController.addContextMenuStateListener(contextMenuStateListener)
        WorkspaceController.addWindowBoundsListener(windowBoundsListener)
        WorkspaceController.addSnapPreviewListener(snapPreviewListener)
        WorkspaceController.addTaskbarHoverListener(taskbarHoverListener)
        DesktopWallpaperStore.addChangeListener(wallpaperListener)
        WorkspaceSettings.addChangeListener(taskbarSettingsListener)
    }

    override fun onDetachedFromWindow() {
        WorkspaceController.removeAppLaunchedListener(appLaunchedListener)
        WorkspaceController.removeAppClosedListener(appClosedListener)
        WorkspaceController.removeDrawerStateListener(drawerStateListener)
        WorkspaceController.removeSettingsStateListener(settingsStateListener)
        WorkspaceController.removeAudioStateListener(audioStateListener)
        WorkspaceController.removeOptometryStateListener(optometryStateListener)
        WorkspaceController.removeContextMenuStateListener(contextMenuStateListener)
        WorkspaceController.removeWindowBoundsListener(windowBoundsListener)
        WorkspaceController.removeSnapPreviewListener(snapPreviewListener)
        WorkspaceController.removeTaskbarHoverListener(taskbarHoverListener)
        DesktopWallpaperStore.removeChangeListener(wallpaperListener)
        WorkspaceSettings.removeChangeListener(taskbarSettingsListener)
        mainHandler.removeCallbacks(taskbarHideRunnable)
        super.onDetachedFromWindow()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(clockTick)
        clockTick.run()
        SystemStatus.addListener(systemStatusListener)
        refreshStatusTray()
        applyTaskbarSettings()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(clockTick)
        SystemStatus.removeListener(systemStatusListener)
        super.onStop()
    }

    /** Re-render the status tray from the latest [SystemStatus] snapshot. */
    private fun refreshStatusTray() {
        if (::batteryText.isInitialized) {
            val charging = if (SystemStatus.batteryCharging) "⚡ " else ""
            batteryText.text = "$charging${SystemStatus.batteryPercent}%"
        }
        if (::outputButton.isInitialized) {
            val iconRes = when (SystemStatus.activeOutput) {
                SystemStatus.AudioOutput.BLUETOOTH -> R.drawable.ic_bluetooth
                SystemStatus.AudioOutput.USB -> R.drawable.ic_usb
                SystemStatus.AudioOutput.WIRED -> R.drawable.ic_headphones
                SystemStatus.AudioOutput.SPEAKER -> R.drawable.ic_volume
            }
            outputButton.setImageResource(iconRes)
            outputButton.contentDescription = SystemStatus.activeOutputName
        }
    }

    /**
     * Outer container for the wallpaper — kept as a [FrameLayout] so we can swap the
     * inner ImageView / tiled background on a store change without rebuilding the
     * whole view tree. The actual image is applied by [applyWallpaperSpec].
     */
    /**
     * Build the desktop right-click context menu — a vertical list of action
     * rows. Built fresh on every open so the FREE-only rows / taskbar toggle /
     * "Lock vs Unlock" label all reflect current state. Each row dismisses the
     * menu before running its action.
     */
    private fun buildContextMenu(): View {
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xEE1C1E22.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val pinned = WorkspaceController.currentViewMode ==
            com.uxspace.spatial.WorkspaceRenderer.ViewMode.PINNED
        val free = !pinned
        val dofUp = WorkspaceController.headTrackingActive

        addContextMenuItem(container, "Arrange icons") {
            DesktopShortcutsStore.arrange(currentDesktopIdx())
        }
        addContextMenuItem(container, "Change wallpaper") {
            WorkspaceController.pickWallpaperFromDevice?.invoke(currentDesktopIdx())
        }
        addContextMenuItem(container, "Settings") {
            WorkspaceController.setSettingsOpen(true, slotIdx)
        }
        addContextMenuItem(container, "Cycle layout", enabled = free) {
            WorkspaceController.cycleLayout()
        }
        addContextMenuItem(container, "Recenter view", enabled = free) {
            WorkspaceController.alignVerticalToHead()
        }
        addContextMenuItem(
            container,
            label = if (pinned) "Unlock (FREE)" else "Lock (PINNED)",
            enabled = dofUp,
        ) {
            val next = if (pinned) {
                com.uxspace.spatial.WorkspaceRenderer.ViewMode.FREE
            } else {
                com.uxspace.spatial.WorkspaceRenderer.ViewMode.PINNED
            }
            WorkspaceController.setViewMode(next)
        }
        addContextMenuItem(container, "Reset zoom") {
            WorkspaceController.resetWorkspaceZoom()
        }
        addContextMenuItem(
            container,
            if (WorkspaceSettings.showTaskbar()) "Hide taskbar" else "Show taskbar",
        ) {
            WorkspaceSettings.setShowTaskbar(!WorkspaceSettings.showTaskbar())
        }
        return container
    }

    private fun addContextMenuItem(
        parent: android.widget.LinearLayout,
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        val row = TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(if (enabled) 0xFFEAEAEA.toInt() else 0xFF888888.toInt())
            isClickable = enabled
            isFocusable = enabled
            setPadding(dp(20), dp(12), dp(64), dp(12))
            if (enabled) {
                setOnClickListener {
                    // Close the menu first so the action lands on a clean
                    // workspace state (the action may toggle another modal
                    // like Settings, which is mutually exclusive anyway).
                    WorkspaceController.setContextMenuOpen(false, slotIdx)
                    onClick()
                }
            }
        }
        parent.addView(
            row,
            android.widget.LinearLayout.LayoutParams(MATCH, WRAP),
        )
    }

    private fun buildWallpaper(): View {
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        applyWallpaperSpec(container, currentWallpaperSpec())
        return container
    }

    private fun currentDesktopIdx(): Int =
        DesktopShortcutsStore.desktopIdxFor(WorkspaceController.layout, slotIdx)

    private fun currentWallpaperSpec(): WallpaperSpec =
        DesktopWallpaperStore.specFor(currentDesktopIdx())

    private val wallpaperListener: (Int) -> Unit = { changedDesktopIdx ->
        if (changedDesktopIdx == currentDesktopIdx() && ::wallpaper.isInitialized) {
            mainHandler.post {
                applyWallpaperSpec(wallpaper as FrameLayout, currentWallpaperSpec())
            }
        }
    }

    /**
     * Replace the wallpaper container's contents to match [spec]. Tile mode lays the
     * image as a repeating [android.graphics.drawable.BitmapDrawable] background;
     * every other mode uses an `ImageView` with the matching `ScaleType`. Unloadable
     * images fall back to the void colour so the desktop never goes transparent.
     */
    private fun applyWallpaperSpec(container: FrameLayout, spec: WallpaperSpec) {
        container.removeAllViews()
        container.background = null
        // Fall back to the default bundled wallpaper if the chosen asset can't be
        // loaded — e.g. a pref saved under an asset that was renamed/removed between
        // builds — so the desktop shows a wallpaper rather than the bare void colour.
        val bitmap = loadWallpaperBitmap(spec.source)
            ?: (spec.source as? WallpaperSource.Asset)
                ?.takeIf { it.assetPath != (DesktopWallpaperStore.DEFAULT_SPEC.source as? WallpaperSource.Asset)?.assetPath }
                ?.let { loadWallpaperBitmap(DesktopWallpaperStore.DEFAULT_SPEC.source) }
        if (bitmap == null) {
            container.setBackgroundColor(VOID_COLOR)
            return
        }
        if (spec.mode == PlacementMode.TILE) {
            container.background = android.graphics.drawable.BitmapDrawable(
                context.resources, bitmap,
            ).apply {
                setTileModeXY(
                    android.graphics.Shader.TileMode.REPEAT,
                    android.graphics.Shader.TileMode.REPEAT,
                )
            }
            return
        }
        val iv = ImageView(context).apply {
            scaleType = when (spec.mode) {
                PlacementMode.CENTER_CROP -> ImageView.ScaleType.CENTER_CROP
                PlacementMode.ONE_TO_ONE -> ImageView.ScaleType.CENTER
                PlacementMode.STRETCH -> ImageView.ScaleType.FIT_XY
                PlacementMode.FIT -> ImageView.ScaleType.FIT_CENTER
                PlacementMode.TILE -> ImageView.ScaleType.CENTER_CROP // unreachable
            }
            setImageBitmap(bitmap)
        }
        container.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun loadWallpaperBitmap(source: WallpaperSource): android.graphics.Bitmap? = runCatching {
        when (source) {
            is WallpaperSource.Asset -> context.assets.open(source.assetPath).use {
                BitmapFactory.decodeStream(it)
            }
            is WallpaperSource.Uri -> context.contentResolver
                .openInputStream(android.net.Uri.parse(source.uri))
                ?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    /**
     * A faint build stamp in the workspace's top-left corner. The time is the APK's install
     * time, so a capture can be confirmed to come from the latest build.
     */
    private fun buildVersionLabel(): View = TextView(context).apply {
        text = versionStamp()
        setTextColor(0x73FFFFFF)
        textSize = 11f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
    }

    private fun versionStamp(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val time = SimpleDateFormat("MMM d  HH:mm:ss", Locale.getDefault())
            .format(Date(info.lastUpdateTime))
        "UxSpace · build $time"
    }.getOrDefault("UxSpace")

    /**
     * The three-cluster DeX-style bar (see docs/TASKBAR.md): workspace controls pinned
     * left, the launcher group (Search, App drawer, running apps) centred on the *screen*
     * — not between the side clusters, so it doesn't drift as the tray grows — and the
     * status tray pinned right.
     */
    private fun buildTaskbar(): View {
        runningApps = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bar = FrameLayout(context).apply {
            setPadding(dp(12), 0, dp(16), 0)
            setBackgroundColor(UxSpaceTheme.taskbar)
            addView(
                buildLeftCluster(),
                FrameLayout.LayoutParams(WRAP, MATCH, Gravity.START or Gravity.CENTER_VERTICAL),
            )
            addView(
                buildCenterCluster(),
                FrameLayout.LayoutParams(WRAP, MATCH, Gravity.CENTER),
            )
            addView(
                buildRightCluster(),
                FrameLayout.LayoutParams(WRAP, MATCH, Gravity.END or Gravity.CENTER_VERTICAL),
            )
        }
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            addView(
                bar,
                FrameLayout.LayoutParams(MATCH, dp(TASKBAR_HEIGHT_DP)).apply {
                    gravity = Gravity.BOTTOM
                },
            )
        }
    }

    /**
     * Left cluster — the navigation trio: Recents (drawer on its Recent tab), Home (show
     * desktop) and Back. See docs/TASKBAR.md.
     */
    private fun buildLeftCluster(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            taskbarButton(R.drawable.ic_nav_recents, "Recents") {
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.RECENT)
                WorkspaceController.setDrawerOpen(true, slotIdx)
            },
        )
        addView(
            taskbarButton(R.drawable.ic_nav_home, "Home") {
                WorkspaceController.toggleShowDesktop()
            },
        )
        addView(taskbarButton(R.drawable.ic_nav_back, "Back") { navigateBack() })
    }

    /**
     * Taskbar Back: dismiss the eye chart or a panel open on this screen first, otherwise send Back to
     * this screen's focused window (which closes the window once its stack empties).
     */
    private fun navigateBack() {
        when {
            WorkspaceController.isOptometryOpen && WorkspaceController.optometryOnScreen == slotIdx ->
                WorkspaceController.setOptometryOpen(false, slotIdx)
            WorkspaceController.isDrawerOpen && WorkspaceController.drawerOnScreen == slotIdx ->
                WorkspaceController.setDrawerOpen(false, slotIdx)
            WorkspaceController.isSettingsOpen && WorkspaceController.settingsOnScreen == slotIdx ->
                WorkspaceController.setSettingsOpen(false, slotIdx)
            WorkspaceController.isAudioOpen && WorkspaceController.audioOnScreen == slotIdx ->
                WorkspaceController.setAudioOpen(false, slotIdx)
            else -> focusedPackage?.let { WorkspaceController.sendBackToApp(it) }
        }
    }

    /**
     * Centre cluster — the launcher group: Search, App drawer, a divider, then one icon
     * per running app window.
     */
    private fun buildCenterCluster(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            taskbarButton(R.drawable.ic_search, "Search") {
                // Search reaches all installed apps — not just the recent subset.
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
                WorkspaceController.setDrawerOpen(true, slotIdx)
            },
        )
        addView(
            taskbarButton(R.drawable.ic_apps, "App drawer") {
                // Toggle is local to *this* slot — if the drawer is open on another
                // monitor, one click moves it here instead of taking two (close-then-
                // open). Only close when the drawer is already on this slot.
                val openHere = WorkspaceController.isDrawerOpen &&
                    WorkspaceController.drawerOnScreen == slotIdx
                android.util.Log.i(
                    "UxSpace/Drawer",
                    "screen=$slotIdx drawer button clicked, openOn=" +
                        "${WorkspaceController.drawerOnScreen} openHere=$openHere",
                )
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
                WorkspaceController.setDrawerOpen(!openHere, slotIdx)
            },
        )
        // Separates the launchers from running apps; hidden while nothing is running.
        runningDivider = buildDivider().apply { visibility = View.GONE }
        addView(runningDivider)
        addView(runningApps, LinearLayout.LayoutParams(WRAP, MATCH))
    }

    /**
     * Right cluster — DeX-style status / configuration tray: volume, battery, clock for
     * now. Wi-Fi, signal, message indicator and the click-to-open quick-settings panel
     * come in follow-up commits (see docs/TASKBAR.md).
     */
    /** Container views for status tray items, kept so settings can show/hide them. */
    private var volumeItem: View? = null
    private var batteryItem: View? = null

    private fun buildRightCluster(): View {
        batteryText = statusValue()
        clock = TextView(context).apply {
            setTextColor(UxSpaceTheme.taskbarText)
            textSize = 12.5f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(2).toFloat(), 1f)
            text = clockText()
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            volumeItem = buildVolumeItem()
            batteryItem = statusItem(R.drawable.ic_battery, batteryText, "Battery")
            addView(volumeItem)
            addView(buildDivider())
            addView(batteryItem)
            addView(
                taskbarButton(R.drawable.ic_settings, "Settings", TRAY_CELL_DP) {
                    // Same one-click move semantics as the App drawer: clicking on a
                    // different slot's Settings button moves the panel here in one tap.
                    val openHere = WorkspaceController.isSettingsOpen &&
                        WorkspaceController.settingsOnScreen == slotIdx
                    WorkspaceController.setSettingsOpen(!openHere, slotIdx)
                },
            )
            addView(buildDivider())
            addView(
                clock,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) },
            )
        }
    }

    /**
     * Volume tray slot: a single icon showing the active output type (speaker /
     * headphones / BT / USB). Tap toggles the [AudioPanelView] popup on *this*
     * slot — same one-click-move semantics as the App drawer and Settings.
     */
    private fun buildVolumeItem(): View {
        outputButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_volume)
            background = cellBackground()
            contentDescription = "Audio"
            layoutParams = LinearLayout.LayoutParams(dp(TRAY_CELL_DP), dp(TRAY_CELL_DP)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                val openHere = WorkspaceController.isAudioOpen &&
                    WorkspaceController.audioOnScreen == slotIdx
                WorkspaceController.setAudioOpen(!openHere, slotIdx)
            }
        }
        return outputButton
    }

    /** Right-tray cell: a small icon next to a tiny percentage label. */
    private fun statusItem(
        @DrawableRes icon: Int,
        valueLabel: TextView,
        description: String,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        }
        contentDescription = description
        addView(
            ImageView(context).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
        )
        addView(
            valueLabel,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) },
        )
    }

    private fun statusValue(): TextView = TextView(context).apply {
        setTextColor(UxSpaceTheme.taskbarText)
        textSize = 11f
    }

    private fun taskbarButton(
        @DrawableRes icon: Int,
        description: String,
        cellDp: Int = TASKBAR_CELL_DP,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(icon)
        background = cellBackground()
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(cellDp), dp(cellDp)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        // Glyph = cell minus padding: 26 dp in a launcher cell, 22 dp in a tray cell.
        val glyphDp = if (cellDp >= TASKBAR_CELL_DP) 26 else 22
        val pad = dp((cellDp - glyphDp) / 2)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick() }
    }

    /** Rounded highlight shown while a taskbar cell is pressed; clear otherwise. */
    private fun cellBackground(): Drawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(UxSpaceTheme.taskbarPressed)
            },
        )
    }

    /** 1 px × 20 dp hairline between taskbar groups. */
    private fun buildDivider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(20)).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        }
        setBackgroundColor(UxSpaceTheme.taskbarDivider)
    }

    /**
     * Add the launched app to the taskbar — its icon raises (and un-minimises) the window on
     * a tap, and restores a maximised window to its frame on a double tap. Closing is done
     * from the window's own title bar. A no-op if the app already has an icon.
     */
    private fun addRunningApp(packageName: String, label: String) {
        if (!::runningApps.isInitialized || runningIcons.containsKey(packageName)) return
        // A cell = the app icon plus a small pill underneath marking it as running.
        val icon = FrameLayout(context).apply {
            background = cellBackground()
            contentDescription = label
            addView(
                ImageView(context).apply { setImageDrawable(appIcon(packageName)) },
                FrameLayout.LayoutParams(dp(RUNNING_ICON_DP), dp(RUNNING_ICON_DP), Gravity.CENTER),
            )
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(UxSpaceTheme.taskbarRunningIndicator)
                    }
                },
                FrameLayout.LayoutParams(dp(12), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    .apply { bottomMargin = dp(2) },
            )
        }
        val gestures = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                // onSingleTapUp (not onSingleTapConfirmed): the cursor delivers each
                // click as a synthesized DOWN+UP, and the confirmed variant defers
                // ~300 ms waiting for a double-tap that never reliably arrives — so a
                // single click felt dead. Fire restore/focus immediately on the up.
                // (Double-tap-to-close was a leftover from the old single-display
                // model; closing now lives on the window chrome's Close button.)
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    WorkspaceController.focusApp(packageName)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // Cycle the running app to the next slot of the active preset —
                    // poor-man's "drag app to another monitor" until a real drag UX
                    // ships. Repeated long-press walks the app around the slots.
                    WorkspaceController.moveAppToNextScreen(packageName)
                }
            },
        )
        // Always consume, so the icon keeps receiving events after the down — otherwise the
        // gesture detector never sees the up and single/double taps are lost.
        icon.setOnTouchListener { v, e ->
            if (gestures.onTouchEvent(e)) v.performClick()
            true
        }
        runningIcons[packageName] = icon
        runningDivider?.visibility = View.VISIBLE
        runningLabels[packageName] = label
        runningApps.addView(
            icon,
            LinearLayout.LayoutParams(dp(TASKBAR_CELL_DP), dp(TASKBAR_CELL_DP)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            },
        )
        refreshWallpaperVisibility()
        refreshChrome()
    }

    private fun appIcon(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    /** Remove an app's taskbar icon when its window is closed. */
    private fun removeRunningApp(packageName: String) {
        if (!::runningApps.isInitialized) return
        runningIcons.remove(packageName)?.let { runningApps.removeView(it) }
        if (runningIcons.isEmpty()) runningDivider?.visibility = View.GONE
        runningLabels.remove(packageName)
        refreshWallpaperVisibility()
        refreshChrome()
    }

    /**
     * Re-bind every per-window chrome's icon + label when the running-apps map
     * changes. Each [WindowChromeView] in [windowChromes] is keyed by package,
     * so the labels / icons of *other* windows on this slot aren't disturbed.
     * Position / visibility are owned by [applyChromeBoundsList] — this only
     * refreshes content for whichever chrome views already exist.
     */
    private fun refreshChrome() {
        windowChromes.forEach { (pkg, set) ->
            set.chrome.bind(pkg, runningLabels[pkg], appIcon(pkg))
        }
    }

    /**
     * Wallpaper is always visible now — activities live on *their own* bare trusted
     * displays sampled by the renderer as separate quads over the slot's quad, so the
     * slot's wallpaper shows through around the window naturally. Kept as a no-op
     * function so the addRunningApp / removeRunningApp call sites don't need to know.
     */
    private fun refreshWallpaperVisibility() {
        if (!::wallpaper.isInitialized) return
        wallpaper.visibility = View.VISIBLE
    }

    private fun clockText(): String {
        val timePattern = if (WorkspaceSettings.clockUse24h()) "HH:mm" else "h:mm a"
        val pattern = if (WorkspaceSettings.showTaskbarDate()) "$timePattern\nEEE, MMM d" else timePattern
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    /** Refresh status-tray visibility from [WorkspaceSettings]. */
    private fun applyTaskbarSettings() {
        // Global "show taskbar" off → the whole container hides regardless of
        // per-slot showTaskbar (which is the layout-baked default).
        val globalOn = WorkspaceSettings.showTaskbar()
        val baseVisible = globalOn && showTaskbar
        val autoHide = WorkspaceSettings.taskbarAutoHide()
        val effectiveVisible = if (!autoHide) baseVisible else {
            baseVisible && android.os.SystemClock.uptimeMillis() < taskbarShownUntilMs
        }
        taskbarContainer?.visibility = if (effectiveVisible) View.VISIBLE else View.GONE
        if (::clock.isInitialized) {
            clock.visibility = if (WorkspaceSettings.showTaskbarClock()) View.VISIBLE else View.GONE
            clock.text = clockText()
        }
        volumeItem?.visibility = if (WorkspaceSettings.showTaskbarVolume()) View.VISIBLE else View.GONE
        batteryItem?.visibility = if (WorkspaceSettings.showTaskbarBattery()) View.VISIBLE else View.GONE
    }

    private val taskbarSettingsListener: () -> Unit = {
        mainHandler.post { applyTaskbarSettings() }
    }

    /**
     * Auto-hide taskbar state — deadline (uptimeMillis) until which the bar stays
     * visible after the cursor last sat in the bottom hover band. While hovering,
     * the renderer keeps bumping this via [taskbarHoverListener]; when the cursor
     * leaves, [taskbarHideRunnable] is posted to re-evaluate visibility right when
     * the hold expires (no per-frame polling needed).
     */
    private var taskbarShownUntilMs: Long = 0L
    private val taskbarHideRunnable = Runnable { applyTaskbarSettings() }

    private val taskbarHoverListener: (Int, Boolean) -> Unit = { hoverSlot, hovering ->
        if (hoverSlot == slotIdx) {
            mainHandler.post {
                if (!WorkspaceSettings.taskbarAutoHide()) return@post
                mainHandler.removeCallbacks(taskbarHideRunnable)
                if (hovering) {
                    // While hovering, hold the bar visible far in the future — the
                    // next leave event will set the actual fade-out deadline.
                    taskbarShownUntilMs = Long.MAX_VALUE
                    applyTaskbarSettings()
                } else {
                    val deadline = android.os.SystemClock.uptimeMillis() + TASKBAR_HOVER_HOLD_MS
                    taskbarShownUntilMs = deadline
                    mainHandler.postAtTime(taskbarHideRunnable, deadline + 1)
                    applyTaskbarSettings()
                }
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val VOID_COLOR = 0xFF0E1018.toInt()
        const val CLOCK_INTERVAL_MS = 20_000L

        /**
         * Auto-hide hold time after the cursor leaves the slot's bottom hover band —
         * long enough to keep the bar present while the user moves between buttons,
         * short enough that it tucks away soon after the user moves on.
         */
        const val TASKBAR_HOVER_HOLD_MS = 1500L

        /**
         * Height of the window-chrome bar in dp. Mirrored on the renderer side as
         * `WINDOW_CHROME_PX` (computed with the slot Presentation's density 200) so the
         * activity quad and chrome rect align on the slot's surface texture.
         */
        const val CHROME_HEIGHT_DP = 39

        /**
         * Window-frame thickness in slot-local pixels — drawn as a colour ring
         * around the chrome + activity by sizing the frame view this many px
         * larger than the window's outer rect on every side. Pixels (not dp)
         * so the border thickness is exact regardless of slot density.
         */
        const val WINDOW_BORDER_PX = 3

        /**
         * Tile-preview overlay colour during a window drag — a translucent
         * accent tint, visible against the slot's wallpaper.
         */
        const val SNAP_PREVIEW_COLOR = 0x554090F0.toInt()

        /** Alpha applied to non-focused windows' chrome + frame views. */
        const val UNFOCUSED_CHROME_ALPHA = 0.55f

        /**
         * Taskbar geometry (dp). Height stays 58 dp because the renderer reserves that
         * band below windows (`WorkspaceRenderer.WINDOW_TASKBAR_PX` = 58 dp at the slot's
         * 200 dpi); change both together. Cells are a little larger than the desktop-mode
         * reference so glyphs stay legible through the glasses' optics.
         */
        const val TASKBAR_HEIGHT_DP = 58
        const val TASKBAR_CELL_DP = 46
        const val TRAY_CELL_DP = 36
        const val RUNNING_ICON_DP = 32

        // Drawer sizing. The effective screen is min(host px, baseline), then 60 % cap
        // per dim, aspect-preserved. So ultrawide / wider-than-baseline screens get
        // the same drawer as a single 1920×1080 screen — UI doesn't scale with extra
        // real estate, per the design rule. Aspect 1400×920 ≈ 1.52:1.
        const val DRAWER_BASELINE_SCREEN_W = 1920
        const val DRAWER_BASELINE_SCREEN_H = 1080
        const val DRAWER_SCREEN_FRACTION = 0.74f
        const val DRAWER_ASPECT_W = 870f
        const val DRAWER_ASPECT_H = 800f
    }
}
