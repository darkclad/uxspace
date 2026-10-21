package com.uxspace.spatial

import android.app.Presentation
import android.content.Context
import android.view.Display
import android.view.Surface
import kotlin.math.abs

/**
 * Process-wide handle to the workspace running inside the glasses' `Presentation`, so the
 * phone-side control panel can launch apps into it and switch its view mode.
 *
 * It also holds the workspace's injectable hooks ([desktopContent], [appLauncher]) — the
 * app wires these at startup, which keeps the rendering layer free of any dependency on the
 * desktop UI or on Shizuku.
 */
object WorkspaceController {

    @Volatile
    private var renderer: WorkspaceRenderer? = null

    @Volatile
    private var viewMode = WorkspaceRenderer.ViewMode.PINNED

    /** How much of the glasses display the scene fills, centred (1.0 = full). */
    @Volatile
    private var screenBandState = 0.83f

    /**
     * Zoom layouts the toolbar zoom button cycles through — bigger value = scene
     * appears bigger / closer (matches the pinch direction). 120% = startup default.
     */
    private val zoomPresets = floatArrayOf(1.2f, 1.0f, 1.5f, 1.8f, 0.8f)

    /** Active multi-screen layout layout. The taskbar layout button cycles through these. */
    @Volatile
    private var layoutState: Layout = Layout.SINGLE

    /** Builds the desktop UI shown on the workspace's UiScreen. Set by the app at startup. */
    @Volatile
    /**
     * Per-screen desktop factory — given the host context, the screen's VirtualDisplay, the
     * screen index (0..N-1 of the active layout), and whether the screen should render its
     * taskbar/status row, returns the screen's [com.uxspace.desktop.DesktopPresentation].
     * `screenIdx` lets each Presentation filter [onAppLaunched] events to only its screen.
     */
    var desktopContent: (
        (Context, Display, screenIdx: Int, showTaskbar: Boolean) -> Presentation
    )? = null

    /** Launches an app onto a virtual display. Set by the app at startup. */
    @Volatile
    var appLauncher: ((displayId: Int, packageName: String, activityName: String) -> Unit)? = null

    /**
     * Creates a *trusted* virtual display rendering into the given surface and returns its id
     * (or `null` on failure). A trusted display is created through Shizuku's shell-uid helper;
     * it is what lets a launched app keep its splash-screen / new-task launches on the
     * workspace instead of escaping to the phone. Set by the app at startup.
     */
    @Volatile
    var createVirtualDisplay: (
        (name: String, width: Int, height: Int, densityDpi: Int, surface: Surface) -> Int?
    )? = null

    /** Releases a virtual display created via [createVirtualDisplay]. Set by the app at startup. */
    @Volatile
    var releaseVirtualDisplay: ((displayId: Int) -> Unit)? = null

    /** Injects a tap into a launched app's display. Set by the app at startup. */
    @Volatile
    var appTap: ((displayId: Int, x: Int, y: Int) -> Unit)? = null

    /**
     * Injects a vertical scroll into a launched app's display at the cursor's content
     * coordinates — [vScroll] is the same sign convention as a mouse wheel (positive →
     * scroll up / content moves down). Implemented through the privileged helper as a
     * quick touch swipe, since `input` doesn't expose `ACTION_SCROLL` directly.
     * Set by the app at startup.
     */
    @Volatile
    var appScroll: ((displayId: Int, x: Int, y: Int, vScroll: Float) -> Unit)? = null

    /**
     * Injects a back key event into a launched app's display — the window chrome's
     * Back button wires here. The [onEmptied] callback fires (on the privileged
     * helper's scheduler thread) shortly after the back if the activity stack on
     * [displayId] has fully drained, so the workspace can close the now-empty
     * window instead of leaving it as a black surface. Set by the app at startup.
     */
    @Volatile
    var appBack: ((displayId: Int, onEmptied: () -> Unit) -> Unit)? = null

    /**
     * Injects KEYCODE_MEDIA_PAUSE into a launched app's display — fired when a window
     * is minimised so any media playback (audio especially) stops while the activity
     * is off-screen. Set by the app at startup; no-op when unwired.
     */
    @Volatile
    var appMediaPause: ((displayId: Int) -> Unit)? = null

    /**
     * SDK-level reset of the head tracker (`NativeGlasses.resetOriginCarina`). Wired by the
     * app to the glasses module. Invoked by the Ctrl+Alt+R global hotkey, matching the
     * Windows companion's `reset_origin` action. Distinct from [alignVerticalToHead], which
     * is the app-side anchor used by the visual-space toolbar's recenter button and by
     * Ctrl+Alt+C.
     */
    @Volatile
    var sdkRecenter: (() -> Unit)? = null

    /**
     * Re-attempt head tracking (full SDK + USB teardown, then a fresh start). Wired by
     * [com.uxspace.spatial.WorkspacePresentation], which owns the HeadTracking instance.
     * Invoked by the in-view toolbar's DOF-retry button — the lock button turns into this
     * while [headTrackingActive] is false — so the user can recover a tracker that never
     * came up (e.g. Carina VIO failing to converge) without unplugging the glasses. No-op
     * when unwired (no workspace shown).
     */
    @Volatile
    var retryHeadTracking: (() -> Unit)? = null

    /**
     * Trigger a manual in-app update check. Wired by [com.uxspace.MainActivity] (the updater's
     * dialogs + system install screen are phone-side / activity-context concerns); invoked by the
     * glasses-side Settings → About "Check for updates" button. No-op when unwired.
     */
    @Volatile
    var checkForUpdates: (() -> Unit)? = null

    /** Forward a pinch scale factor (1.0 = identity) into the workspace zoom. */
    fun pinch(scaleFactor: Float) {
        renderer?.requestPinch(scaleFactor)
    }

    /**
     * Whether the head tracker is currently producing pose data — gates UI affordances
     * that only make sense when DOF is live (FREE-mode toggle, recenter). Driven by
     * [com.uxspace.spatial.WorkspacePresentation] from the first pose callback / dismiss.
     * Independent from [currentViewMode]: PINNED still works without DOF; FREE is the one
     * that needs head input to be meaningful.
     */
    /**
     * Whether the in-view keymap legend should be drawn. Toggled by the shell-uid
     * helper through the [com.uxspace.privileged.PrivilegedHotkeys.HK_MODIFIERS_DOWN]
     * / `_UP` synthetic hotkeys — i.e. it tracks "Ctrl+Alt is held". Read once per
     * frame by [WorkspaceRenderer]; no listener needed since the renderer is already
     * drawing every frame.
     */
    @Volatile
    var legendVisible: Boolean = false

    /**
     * Transient "Layout: <name>" announcement shown for ~2 s after a layout switch,
     * so the user knows which layout they just landed on. Set together with
     * [layoutAnnouncementExpiresAtMs]; the renderer compares the timestamp to
     * `SystemClock.uptimeMillis()` each frame and stops drawing once it expires.
     * The null state means "nothing to show".
     */
    @Volatile
    var layoutAnnouncement: String? = null
    @Volatile
    var layoutAnnouncementExpiresAtMs: Long = 0L

    @Volatile
    var headTrackingActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            android.util.Log.i(
                "UxSpace/DOF",
                "headTrackingActive -> $value (viewMode=$viewMode)",
            )
            // When DOF dies in FREE, the camera would just sit still — a confusing
            // "stuck unlock". Auto-revert to PINNED. The user has to explicitly
            // re-unlock once DOF resumes, matching the Windows companion's gate
            // that disables zoom when tracking is up but the user is in FREE.
            if (!value && viewMode == WorkspaceRenderer.ViewMode.FREE) {
                android.util.Log.i(
                    "UxSpace/DOF",
                    "auto-reverting to PINNED — DOF lost while FREE",
                )
                setViewMode(WorkspaceRenderer.ViewMode.PINNED)
            }
            dofListeners.forEach { runCatching { it(value) } }
        }

    private val dofListeners =
        java.util.concurrent.CopyOnWriteArrayList<(active: Boolean) -> Unit>()
    private val viewModeListeners =
        java.util.concurrent.CopyOnWriteArrayList<(mode: WorkspaceRenderer.ViewMode) -> Unit>()

    /** Notified whenever [headTrackingActive] transitions; fired on whichever thread set it. */
    fun addDofListener(listener: (active: Boolean) -> Unit) { dofListeners.add(listener) }
    fun removeDofListener(listener: (active: Boolean) -> Unit) { dofListeners.remove(listener) }

    /** Notified after [setViewMode] commits a change; fired on whichever thread called it. */
    fun addViewModeListener(listener: (mode: WorkspaceRenderer.ViewMode) -> Unit) {
        viewModeListeners.add(listener)
    }
    fun removeViewModeListener(listener: (mode: WorkspaceRenderer.ViewMode) -> Unit) {
        viewModeListeners.remove(listener)
    }

    /** Force-stops a launched app when its screen is torn down. Set by the app at startup. */
    @Volatile
    var closeApp: ((packageName: String) -> Unit)? = null

    /**
     * Whether the given VirtualDisplay has any activity stacked on it. The persistence
     * save uses this to skip screens whose app self-closed (e.g. user backed out) so the
     * layout memory doesn't relaunch a dead app on the next switch. Set by the app at
     * startup; AIDL-backed, so call off the GL thread.
     */
    @Volatile
    var displayHasActivity: ((displayId: Int) -> Boolean)? = null

    /**
     * Listeners notified (on the main thread) when an app is launched onto a screen —
     * each per-screen DesktopPresentation registers its own listener so it can filter to
     * its screen. A list (not a single callback) because there are N concurrent screen
     * presentations.
     */
    private val appLaunchedListeners =
        java.util.concurrent.CopyOnWriteArrayList<(packageName: String, label: String, screenIdx: Int) -> Unit>()

    fun addAppLaunchedListener(listener: (packageName: String, label: String, screenIdx: Int) -> Unit) {
        appLaunchedListeners.add(listener)
    }

    fun removeAppLaunchedListener(listener: (packageName: String, label: String, screenIdx: Int) -> Unit) {
        appLaunchedListeners.remove(listener)
    }

    /** Listeners notified when a launched app is closed — same multi-listener pattern. */
    private val appClosedListeners =
        java.util.concurrent.CopyOnWriteArrayList<(packageName: String) -> Unit>()

    fun addAppClosedListener(listener: (packageName: String) -> Unit) {
        appClosedListeners.add(listener)
    }

    fun removeAppClosedListener(listener: (packageName: String) -> Unit) {
        appClosedListeners.remove(listener)
    }

    /**
     * Listeners notified (on the main thread) every time the active view-mode's
     * workspace zoom changes — pinch-driven or button-driven. The phone-side trackpad
     * uses this to surface a live "Zoom NN%" HUD during a pinch.
     */
    private val zoomListeners =
        java.util.concurrent.CopyOnWriteArrayList<(zoom: Float) -> Unit>()

    fun addZoomListener(listener: (zoom: Float) -> Unit) { zoomListeners.add(listener) }
    fun removeZoomListener(listener: (zoom: Float) -> Unit) { zoomListeners.remove(listener) }

    /** Internal: the renderer fires this after every zoom change. */
    internal fun notifyZoomChanged(zoom: Float) {
        zoomListeners.forEach { runCatching { it(zoom) } }
    }

    /**
     * Routes typed text from the phone control panel into the drawer's search field. The
     * panel's keyboard is the only IME path in the workspace; the drawer lives on a
     * secondary display and can't receive a system IME directly. Wired by the drawer when
     * it is shown; called by `MainActivity`'s keyboardField text watcher while the drawer
     * is open.
     */
    @Volatile
    var onDrawerSearchQuery: ((query: String) -> Unit)? = null

    /** Forward [query] into the drawer's search box. No-op if the drawer isn't listening. */
    fun setDrawerSearchQuery(query: String) {
        onDrawerSearchQuery?.invoke(query)
    }

    @Volatile
    private var drawerOpenState = false

    /** Which view the drawer is showing — every-installed-app, or only recently launched. */
    enum class DrawerMode { ALL, RECENT }

    @Volatile
    var drawerMode: DrawerMode = DrawerMode.ALL
        private set

    /**
     * Notified (on the main thread) when the drawer mode changes — every DrawerView
     * registers one, so all per-screen drawers re-style their tabs and re-filter the
     * grid in sync. Multi-listener (not a single var) because V/H/V has three
     * DrawerViews and the single-slot version would leave two of them stale.
     */
    private val drawerModeListeners =
        java.util.concurrent.CopyOnWriteArrayList<(DrawerMode) -> Unit>()

    fun addDrawerModeListener(listener: (DrawerMode) -> Unit) {
        drawerModeListeners.add(listener)
    }

    fun removeDrawerModeListener(listener: (DrawerMode) -> Unit) {
        drawerModeListeners.remove(listener)
    }

    /** Most-recent-first package names of apps launched into the workspace. */
    private val recentAppsList = ArrayList<String>()

    /** A snapshot of the workspace's recent-app launch history. */
    val recentApps: List<String> get() = synchronized(recentAppsList) { recentAppsList.toList() }

    /** Whether a workspace is currently shown on the glasses. */
    val isRunning: Boolean get() = renderer != null

    /**
     * Drawable resource ids for the in-view toolbar buttons. The spatial module has no
     * own `res/`, so the app wires these at startup (alongside [appLauncher] etc.) and
     * the renderer dereferences via its host [android.content.Context]. Null until set.
     */
    data class ToolbarIcons(
        val lock: Int,
        val unlock: Int,
        val zoom: Int,
        val recenter: Int,
        val layout: Int,
        val settings: Int,
        /** Shown in the lock button's place while DOF is down — taps re-attempt tracking. */
        val dofRetry: Int,
        /** In-view capture button — toggles frame-sequence recording. */
        val capture: Int,
        /** Red dot shown in the capture button's place while recording is running. */
        val recordOn: Int,
    )

    @Volatile
    var toolbarIcons: ToolbarIcons? = null

    /**
     * Drawable resource ids for resize cursors — shown by the renderer in place
     * of the default arrow while the cursor hovers a window's edge / corner.
     */
    data class CursorIcons(
        val resizeEW: Int,
        val resizeNS: Int,
        val resizeNESW: Int,
        val resizeNWSE: Int,
    )

    @Volatile
    var cursorIcons: CursorIcons? = null

    /**
     * Maximum number of windows that can stack on a single slot. New launches
     * push the oldest off the slot once the count would exceed this. Default
     * 5; can be tuned higher / lower at startup. Always positive; values < 1
     * are treated as 1.
     */
    @Volatile
    var maxWindowsPerSlot: Int = 5

    /** Whether the app drawer is currently open. */
    val isDrawerOpen: Boolean get() = drawerOpenState

    /**
     * Set the drawer's filter mode. Always notifies listeners — even if the stored
     * mode is unchanged — so the App-drawer button's "force to ALL" semantics can't
     * be defeated by a stale tab state on a DrawerView that was constructed while
     * the mode was something else (e.g. RECENT left over from a prior unlocked
     * session).
     */
    fun setDrawerMode(mode: DrawerMode) {
        drawerMode = mode
        drawerModeListeners.forEach { runCatching { it(mode) } }
    }

    /** The current view mode — pinned to the head, or free in the world. */
    val currentViewMode: WorkspaceRenderer.ViewMode get() = viewMode

    /**
     * The layout actually rendered right now. PINNED mode always shows a single display
     * (focus mode); FREE mode shows the user's chosen layout. [layoutState] holds the
     * FREE-mode choice across lock/unlock cycles.
     */
    private fun effectiveLayout(): Layout =
        if (viewMode == WorkspaceRenderer.ViewMode.PINNED) Layout.SINGLE else layoutState

    internal fun register(renderer: WorkspaceRenderer) {
        this.renderer = renderer
        drawerOpenState = false
        // Clamp on startup — layoutState defaults to SINGLE every process launch, but
        // the user's enabled-layouts list lives in persisted settings. Without this,
        // a user with (say) only VHV enabled would land in FREE on SINGLE and be
        // stuck (the cycle is a no-op when only one layout is enabled).
        clampLayoutStateToEnabled()
        renderer.setViewMode(viewMode)
        renderer.setScreenBand(screenBandState)
        renderer.applyLayout(effectiveLayout())
    }

    private fun clampLayoutStateToEnabled() {
        val enabled = enabledLayouts()
        if (layoutState !in enabled) layoutState = enabled.first()
    }

    internal fun unregister(renderer: WorkspaceRenderer) {
        if (this.renderer === renderer) this.renderer = null
    }

    /**
     * Open an app in a window on the workspace.
     *
     * @return `true` if a workspace was running, `false` if the glasses are not connected.
     */
    fun launchApp(packageName: String, activityName: String, label: String): Boolean {
        val current = renderer
        android.util.Log.i(
            "UxSpace/Launch",
            "2) controller.launchApp pkg=$packageName running=${current != null}",
        )
        if (current == null) return false
        current.requestApp(packageName, activityName, label)
        synchronized(recentAppsList) {
            recentAppsList.remove(packageName)
            recentAppsList.add(0, packageName)
            while (recentAppsList.size > MAX_RECENT_APPS) recentAppsList.removeAt(MAX_RECENT_APPS)
        }
        // onAppLaunched fires later from the renderer (per-screen), once the launch target
        // screen has been picked — so each screen's DesktopPresentation can filter by screenIdx.
        return true
    }

    /** Internal: the renderer fires this once it knows which screen received the launch. */
    internal fun notifyAppLaunchedOnScreen(packageName: String, label: String, screenIdx: Int) {
        appLaunchedListeners.forEach { runCatching { it(packageName, label, screenIdx) } }
    }

    /**
     * Move a launched app from its current screen to the next screen of the active layout
     * (wraps around). Closes the activity on the source screen and relaunches it on the
     * target — currentSlotApps + persistence are updated through the normal paths.
     * No-op when the layout has only one screen or when the app isn't on any screen.
     */
    fun moveAppToNextScreen(packageName: String) {
        renderer?.moveAppToNextScreen(packageName)
    }

    /** Internal: notify listeners that an app has been closed. */
    internal fun notifyAppClosed(packageName: String) {
        appClosedListeners.forEach { runCatching { it(packageName) } }
    }

    /**
     * Force-stop every app UxSpace has launched into the workspace. Called by the
     * workspace foreground service on tear-down so a launched app never outlives its
     * glasses session (otherwise releasing the trusted display would re-home the
     * activity onto the phone's own screen). No-op if no renderer is registered —
     * the renderer's own dismiss path already ran the same cleanup.
     */
    fun closeAllLaunchedApps() {
        renderer?.closeAllWindows()
    }

    /**
     * Open or close the app-drawer. `screenIdx` is the index of the screen whose
     * drawer button triggered this — the drawer is a view embedded in *that* screen's
     * [com.uxspace.desktop.DesktopPresentation], not a separate 3D quad. Closing
     * ignores `screenIdx` (every drawer view hides). Fires every registered
     * [drawerStateListeners] entry on the main thread.
     */
    fun setDrawerOpen(open: Boolean, screenIdx: Int = 0) {
        if (open == drawerOpenState && (!open || screenIdx == drawerOnScreen)) return
        drawerOpenState = open
        drawerOnScreen = if (open) screenIdx else -1
        if (open && settingsOpenState) {
            // Mutually exclusive — opening the drawer closes the settings panel.
            setSettingsOpen(false, screenIdx)
        }
        if (open && audioOpenState) {
            setAudioOpen(false, screenIdx)
        }
        if (open && contextMenuOpenState) {
            setContextMenuOpen(false, screenIdx)
        }
        drawerStateListeners.forEach { runCatching { it(open, screenIdx) } }
    }

    /** Index of the screen that owns the currently-open drawer, or −1 when closed. */
    @Volatile
    var drawerOnScreen: Int = -1
        private set

    /**
     * Listeners notified (main thread) when the drawer opens/closes. Each per-screen
     * DesktopPresentation registers one and toggles its embedded DrawerView's
     * visibility iff `screenIdx == own slotIdx` (open) or unconditionally (close).
     */
    private val drawerStateListeners =
        java.util.concurrent.CopyOnWriteArrayList<(open: Boolean, screenIdx: Int) -> Unit>()

    fun addDrawerStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        drawerStateListeners.add(listener)
    }

    fun removeDrawerStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        drawerStateListeners.remove(listener)
    }

    @Volatile
    private var settingsOpenState = false

    /** Whether the settings panel is currently open on some screen. */
    val isSettingsOpen: Boolean get() = settingsOpenState

    /** Index of the screen that owns the currently-open settings panel, or −1 when closed. */
    @Volatile
    var settingsOnScreen: Int = -1
        private set

    /**
     * Listeners notified (main thread) when the settings panel opens/closes. Each
     * per-screen [com.uxspace.desktop.DesktopPresentation] registers one and toggles its
     * own embedded SettingsView's visibility iff `screenIdx == own slotIdx`. Mirrors the
     * drawer's plumbing — different modal panel, same per-screen visibility model.
     */
    private val settingsStateListeners =
        java.util.concurrent.CopyOnWriteArrayList<(open: Boolean, screenIdx: Int) -> Unit>()

    fun addSettingsStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        settingsStateListeners.add(listener)
    }

    fun removeSettingsStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        settingsStateListeners.remove(listener)
    }

    /**
     * Window-size states. NORMAL is the centred floating rectangle (or the
     * user-positioned one after drag); MAXIMIZED fills the slot above the
     * taskbar; FULLSCREEN covers the entire slot. TILED_LEFT / TILED_RIGHT are
     * half-width snapped rectangles entered via drag-to-edge in Phase 4. The
     * chrome's Maximize button cycles NORMAL/TILED → MAXIMIZED → FULLSCREEN
     * → NORMAL.
     */
    enum class WindowMode { NORMAL, MAXIMIZED, FULLSCREEN, TILED_LEFT, TILED_RIGHT }

    /**
     * Outer bounds of an app window inside a slot, in slot-local pixels. Includes the
     * chrome strip at the top (height [chromePx]) plus the activity area below it.
     * Pushed from the renderer to the slot's [com.uxspace.desktop.DesktopPresentation]
     * which uses [packageName] as the per-window identity to find / create the
     * matching chrome + frame views. [focused] is true for the topmost window on
     * the slot — the slot Presentation paints non-focused chromes dimmed.
     */
    data class WindowBounds(
        val packageName: String,
        val xPx: Int,
        val yPx: Int,
        val widthPx: Int,
        val heightPx: Int,
        val chromePx: Int,
        val mode: WindowMode = WindowMode.NORMAL,
        /**
         * Rectangle the chrome view occupies inside the slot's Presentation. In
         * NORMAL / MAXIMIZED this equals `(xPx, yPx, widthPx, chromePx)` — the
         * strip above the activity. In FULLSCREEN it's a thin centred toolbar at
         * the top of the slot, separate from the window's outer rectangle.
         */
        val chromeBoundsX: Int = xPx,
        val chromeBoundsY: Int = yPx,
        val chromeBoundsW: Int = widthPx,
        val chromeBoundsH: Int = chromePx,
        val focused: Boolean = true,
    )

    private val windowBoundsListeners =
        java.util.concurrent.CopyOnWriteArrayList<(slotIdx: Int, bounds: List<WindowBounds>) -> Unit>()

    fun addWindowBoundsListener(listener: (slotIdx: Int, bounds: List<WindowBounds>) -> Unit) {
        windowBoundsListeners.add(listener)
    }

    fun removeWindowBoundsListener(listener: (slotIdx: Int, bounds: List<WindowBounds>) -> Unit) {
        windowBoundsListeners.remove(listener)
    }

    /**
     * [bounds] is in z-order (last entry = topmost, focused). An empty list
     * means no windows on the slot — Presentations should hide their chrome /
     * frame views.
     */
    internal fun notifyWindowBoundsChanged(slotIdx: Int, bounds: List<WindowBounds>) {
        windowBoundsListeners.forEach { runCatching { it(slotIdx, bounds) } }
    }

    /**
     * Per-slot taskbar-hover edge: fires from the renderer when the cursor enters or
     * leaves the slot's bottom hover band. Used by each slot's
     * [com.uxspace.desktop.DesktopPresentation] to drive the taskbar's auto-hide.
     */
    private val taskbarHoverListeners =
        java.util.concurrent.CopyOnWriteArrayList<(slotIdx: Int, hovering: Boolean) -> Unit>()

    fun addTaskbarHoverListener(listener: (slotIdx: Int, hovering: Boolean) -> Unit) {
        taskbarHoverListeners.add(listener)
    }

    fun removeTaskbarHoverListener(listener: (slotIdx: Int, hovering: Boolean) -> Unit) {
        taskbarHoverListeners.remove(listener)
    }

    internal fun notifyTaskbarHover(slotIdx: Int, hovering: Boolean) {
        taskbarHoverListeners.forEach { runCatching { it(slotIdx, hovering) } }
    }

    /**
     * Bounds of the modal panel (drawer or settings) inside a slot, in slot-local
     * pixels. The drawer + settings panels share the same centred size, so a single
     * payload covers both — the renderer uses it to sample the slot's Presentation
     * surface and re-composite that rectangle on top of the activity quad, so the
     * modal appears in front of any window stacked behind it.
     */
    data class ModalBounds(
        val xPx: Int,
        val yPx: Int,
        val widthPx: Int,
        val heightPx: Int,
    )

    /**
     * Latest modal-bounds payload per slot, indexed by slot ordinal. Holds the
     * centred drawer / settings rectangle so the renderer can read it on every
     * frame without subscribing. Each per-slot DesktopPresentation calls
     * [notifyModalBoundsChanged] once at onCreate.
     */
    private val modalBoundsBySlot = java.util.concurrent.ConcurrentHashMap<Int, ModalBounds>()

    /** Snapshot of the modal rectangle for [slotIdx], or null if none set yet. */
    fun modalBoundsForSlot(slotIdx: Int): ModalBounds? = modalBoundsBySlot[slotIdx]

    /**
     * Per-slot DesktopPresentation calls this once it has computed its drawer /
     * settings rectangle (they share dimensions). Idempotent — only stored.
     */
    fun notifyModalBoundsChanged(slotIdx: Int, bounds: ModalBounds) {
        modalBoundsBySlot[slotIdx] = bounds
    }

    /**
     * Drag-to-edge tile preview. Renderer fires this during a window drag when
     * the cursor enters / leaves a snap zone (left edge → TILED_LEFT, right
     * edge → TILED_RIGHT, top edge → MAXIMIZED). `rect` is the prospective
     * snap rectangle in slot-local px; null means "no snap right now, drop
     * floating." Slot Presentations show a translucent preview at that rect
     * so the user sees where the window will land before releasing.
     */
    data class SnapPreview(
        val slotIdx: Int,
        val xPx: Int,
        val yPx: Int,
        val widthPx: Int,
        val heightPx: Int,
    )

    private val snapPreviewListeners =
        java.util.concurrent.CopyOnWriteArrayList<(SnapPreview?) -> Unit>()

    fun addSnapPreviewListener(listener: (SnapPreview?) -> Unit) {
        snapPreviewListeners.add(listener)
    }

    fun removeSnapPreviewListener(listener: (SnapPreview?) -> Unit) {
        snapPreviewListeners.remove(listener)
    }

    internal fun notifySnapPreview(preview: SnapPreview?) {
        snapPreviewListeners.forEach { runCatching { it(preview) } }
    }

    /**
     * Open or close the settings panel. `screenIdx` is the index of the screen whose
     * Settings button (or in-view toolbar) triggered this — the panel opens on that
     * screen, exactly like the drawer. Opening on one slot while another already holds
     * an open settings panel implicitly moves it (each per-screen listener checks its
     * own slotIdx). Closing also implicitly closes the drawer if one is open on the
     * same screen, since only one modal panel can be visible at a time.
     */
    fun setSettingsOpen(open: Boolean, screenIdx: Int = 0) {
        if (open == settingsOpenState && (!open || screenIdx == settingsOnScreen)) return
        settingsOpenState = open
        settingsOnScreen = if (open) screenIdx else -1
        if (open && drawerOpenState) {
            // Mutually exclusive — opening settings closes the drawer.
            setDrawerOpen(false, screenIdx)
        }
        if (open && audioOpenState) {
            setAudioOpen(false, screenIdx)
        }
        if (open && contextMenuOpenState) {
            setContextMenuOpen(false, screenIdx)
        }
        settingsStateListeners.forEach { runCatching { it(open, screenIdx) } }
    }

    // Desktop right-click context menu state — same per-screen visibility model
    // as drawer / settings / audio, plus a click-position (slot pixel coords) so
    // the menu opens at the cursor instead of always at the slot's centre.
    @Volatile
    private var contextMenuOpenState = false

    val isContextMenuOpen: Boolean get() = contextMenuOpenState

    @Volatile
    var contextMenuOnScreen: Int = -1
        private set

    @Volatile
    var contextMenuPxX: Float = 0f
        private set

    @Volatile
    var contextMenuPxY: Float = 0f
        private set

    private val contextMenuStateListeners =
        java.util.concurrent.CopyOnWriteArrayList<(open: Boolean, screenIdx: Int, x: Float, y: Float) -> Unit>()

    fun addContextMenuStateListener(
        listener: (open: Boolean, screenIdx: Int, x: Float, y: Float) -> Unit,
    ) { contextMenuStateListeners.add(listener) }

    fun removeContextMenuStateListener(
        listener: (open: Boolean, screenIdx: Int, x: Float, y: Float) -> Unit,
    ) { contextMenuStateListeners.remove(listener) }

    /**
     * Open or close the desktop right-click context menu on [screenIdx], anchored
     * at slot pixel coordinates [pxX], [pxY] (the press position). Mutually
     * exclusive with drawer / settings / audio; opening it closes the others.
     * Called by [WorkspaceRenderer] when a right-click lands on the desktop
     * (cursor not over any running window).
     */
    fun setContextMenuOpen(
        open: Boolean,
        screenIdx: Int = 0,
        pxX: Float = 0f,
        pxY: Float = 0f,
    ) {
        if (!open && !contextMenuOpenState) return
        contextMenuOpenState = open
        contextMenuOnScreen = if (open) screenIdx else -1
        contextMenuPxX = pxX
        contextMenuPxY = pxY
        if (open) {
            if (drawerOpenState) setDrawerOpen(false, screenIdx)
            if (settingsOpenState) setSettingsOpen(false, screenIdx)
            if (audioOpenState) setAudioOpen(false, screenIdx)
        }
        contextMenuStateListeners.forEach { runCatching { it(open, screenIdx, pxX, pxY) } }
    }

    /**
     * Request that the renderer treat the current cursor position as a
     * right-click. Routed via the renderer because it owns the hit-test for
     * "is the cursor on the desktop vs over a running window".
     */
    fun requestRightClick() {
        renderer?.requestRightClick()
    }

    /** Set workspace zoom back to 1.0×. Convenience for the context menu. */
    fun resetWorkspaceZoom() {
        renderer?.setWorkspaceZoom(1.0f)
    }

    @Volatile
    private var audioOpenState = false

    /** Whether the audio panel is currently open on some screen. */
    val isAudioOpen: Boolean get() = audioOpenState

    /** Index of the screen that owns the currently-open audio panel, or −1 when closed. */
    @Volatile
    var audioOnScreen: Int = -1
        private set

    private val audioStateListeners =
        java.util.concurrent.CopyOnWriteArrayList<(open: Boolean, screenIdx: Int) -> Unit>()

    fun addAudioStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        audioStateListeners.add(listener)
    }

    fun removeAudioStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        audioStateListeners.remove(listener)
    }

    /**
     * Open / close the audio panel on [screenIdx]. Mutually exclusive with the drawer
     * and settings panel — opening this closes them.
     */
    fun setAudioOpen(open: Boolean, screenIdx: Int = 0) {
        if (open == audioOpenState && (!open || screenIdx == audioOnScreen)) return
        audioOpenState = open
        audioOnScreen = if (open) screenIdx else -1
        if (open && drawerOpenState) setDrawerOpen(false, screenIdx)
        if (open && settingsOpenState) setSettingsOpen(false, screenIdx)
        audioStateListeners.forEach { runCatching { it(open, screenIdx) } }
    }

    @Volatile
    private var optometryOpenState = false

    /** Whether the eye chart (docs/OPTOMETRY.md) is showing on some screen. */
    val isOptometryOpen: Boolean get() = optometryOpenState

    /** Index of the screen showing the eye chart, or −1 when closed. */
    @Volatile
    var optometryOnScreen: Int = -1
        private set

    private val optometryStateListeners =
        java.util.concurrent.CopyOnWriteArrayList<(open: Boolean, screenIdx: Int) -> Unit>()

    fun addOptometryStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        optometryStateListeners.add(listener)
    }

    fun removeOptometryStateListener(listener: (open: Boolean, screenIdx: Int) -> Unit) {
        optometryStateListeners.remove(listener)
    }

    /**
     * Show / hide the eye chart on [screenIdx]. It covers the whole screen — windows,
     * taskbar and all — so opening it closes any other panel. The renderer draws the
     * screen's full surface over its windows and routes every click to it while open.
     */
    fun setOptometryOpen(open: Boolean, screenIdx: Int = 0) {
        if (open == optometryOpenState && (!open || screenIdx == optometryOnScreen)) return
        if (open && drawerOpenState) setDrawerOpen(false, screenIdx)
        if (open && settingsOpenState) setSettingsOpen(false, screenIdx)
        if (open && audioOpenState) setAudioOpen(false, screenIdx)
        optometryOpenState = open
        optometryOnScreen = if (open) screenIdx else -1
        optometryStateListeners.forEach { runCatching { it(open, screenIdx) } }
    }

    /**
     * Show-desktop toggle for the taskbar button: if any window is visible, minimise
     * everything; if everything is already minimised, restore them all.
     */
    fun toggleShowDesktop() {
        renderer?.toggleShowDesktop()
    }

    /**
     * Bring an app's activity to the front of its screen — a taskbar-icon tap.
     *
     * Also dismisses any modal panel that happens to be open: the taskbar lives
     * above the scrim in z-order, so the scrim's outside-click handler never
     * fires for a taskbar tap. Without this, restoring a minimised app would
     * leave the drawer (or settings) stuck open on top of the just-restored
     * window — which contradicts the "click anywhere outside the drawer closes
     * it" rule the user expects.
     */
    fun focusApp(packageName: String) {
        if (drawerOpenState) setDrawerOpen(false)
        if (settingsOpenState) setSettingsOpen(false)
        if (audioOpenState) setAudioOpen(false)
        renderer?.focusApp(packageName)
    }

    /** Close an app — fired by the window chrome's Close button. */
    fun closeAppByPackage(packageName: String) {
        renderer?.closeAppByPackage(packageName)
    }

    /**
     * Chrome's Minimize button — hide the window without tearing down the activity.
     * The taskbar icon stays so a tap there ([focusApp]) restores it.
     */
    fun minimizeWindow(packageName: String) {
        renderer?.minimizeWindow(packageName)
    }

    /**
     * Chrome's Maximize button — toggle the window between the centred default
     * rectangle and a full-slot rectangle above the taskbar.
     */
    fun toggleMaximizeWindow(packageName: String) {
        renderer?.toggleMaximizeWindow(packageName)
    }

    /**
     * Chrome's Back button — inject `KEYCODE_BACK` into the app's display. No-op
     * when [appBack] hasn't been wired (e.g. helper not running) or when the named
     * package has no live window.
     */
    fun sendBackToApp(packageName: String) {
        val cb = appBack ?: return
        renderer?.dispatchBackToWindow(packageName) { displayId ->
            cb(displayId) {
                // Activity stack drained on the bare display — close the window so
                // it doesn't sit as a black surface stuck in front of the desktop.
                closeAppByPackage(packageName)
            }
        }
    }

    /**
     * Press-and-hold without prior tap — used to arm a drag-from-drawer. The trackpad's
     * normal [beginDrag] needs a preceding tap (libinput tap-and-drag); a long press is
     * the only gesture that can pick up a drawer cell without first launching its app.
     */
    fun longPress() {
        android.util.Log.i(
            "UxSpace/DrawerDrag",
            "WorkspaceController.longPress(): renderer=${renderer != null} drawerOpen=$drawerOpenState drawerOn=$drawerOnScreen drawerItemAtSet=${drawerItemAt != null}",
        )
        renderer?.requestLongPress()
    }

    /** Begin / end a window drag — the touchpad reports a press-and-hold as a drag. */
    fun beginDrag() {
        renderer?.beginDrag()
    }

    fun endDrag() {
        renderer?.endDrag()
    }

    /**
     * Switch how the screen tracks the head, and reshape the workspace to match. PINNED
     * always shows a single display (focus mode); FREE restores the user's chosen layout.
     * Closes the drawer if it was open — switching modes makes the previous drawer's
     * anchor screen meaningless.
     */
    fun setViewMode(mode: WorkspaceRenderer.ViewMode) {
        if (viewMode == mode) return
        viewMode = mode
        if (drawerOpenState) setDrawerOpen(false)
        // Entering FREE — make sure the active layout is one the user still has
        // enabled. (PINNED renders SINGLE regardless; this only matters going FREE.)
        if (mode == WorkspaceRenderer.ViewMode.FREE) clampLayoutStateToEnabled()
        renderer?.setViewMode(mode)
        renderer?.applyLayout(effectiveLayout())
        viewModeListeners.forEach { runCatching { it(mode) } }
    }

    /** Set the render band — the fraction of the display the scene fills, centred. */
    fun setScreenBand(fraction: Float) {
        screenBandState = fraction
        renderer?.setScreenBand(fraction)
    }

    /** Current render band fraction — survives renderer teardown. */
    fun currentScreenBand(): Float = screenBandState

    /**
     * Current workspace zoom in the active view mode. Returns 1.0 when no renderer is
     * registered (used by the settings panel to render a sensible default before the
     * glasses are connected).
     */
    fun currentZoom(): Float = renderer?.workspaceZoom() ?: 1.0f

    /** Recenter every window's vertical position on the user's current head pitch. */
    fun alignVerticalToHead() {
        val r = renderer
        android.util.Log.i(
            "UxSpace/Renderer",
            "alignVerticalToHead() controller call — renderer=${r != null}",
        )
        r?.alignVerticalToHead()
    }

    /**
     * Cycle the active view-mode's workspace zoom through the [zoomPresets]; returns
     * the new zoom (1.0 = 100% = default size, bigger = closer / larger). Replaces the
     * old screen-band cycle whose letterbox semantics felt backwards (smaller % looked
     * "zoomed in" because the rendered scene shrank into a centred box).
     */
    fun cycleScreenBand(): Float {
        val r = renderer ?: return 1.0f
        val current = r.workspaceZoom()
        val index = zoomPresets.indexOfFirst { abs(it - current) < 0.01f }
        val next = zoomPresets[(index + 1).mod(zoomPresets.size)]
        r.setWorkspaceZoom(next)
        return next
    }

    /**
     * Returns the main-screen index for [layout]. Wired by the app to read from its
     * persisted settings; defaults to [Layout.defaultMainScreen] when unset. The
     * renderer / launchers use this to decide where new apps land if the cursor
     * isn't currently over any screen (e.g. PINNED mode launches, restore paths).
     */
    @Volatile
    var mainScreenForLayout: ((Layout) -> Int)? = null

    /**
     * Layouts the user has chosen to include in the toolbar layout-cycle. Wired by the
     * app to read from settings; when null (no hook), the cycle walks every layout —
     * the original behaviour. The list is always non-empty.
     */
    @Volatile
    var enabledLayoutsProvider: (() -> List<Layout>)? = null

    /**
     * Trigger the phone-side photo picker so the user can pick a wallpaper for
     * [desktopIdx]. The settings panel lives on a virtual display and can't host a
     * system picker dialog, so this hops out to MainActivity. Wired by the app at
     * startup; null when the activity isn't in the foreground.
     */
    @Volatile
    var pickWallpaperFromDevice: ((desktopIdx: Int) -> Unit)? = null

    /**
     * Open the system audio-output picker (Settings.Panel.ACTION_VOLUME). The
     * taskbar's sound-output button calls this; MainActivity handles the launch
     * because Presentations can't start activities.
     */
    @Volatile
    var openSoundOutputPicker: (() -> Unit)? = null

    // region Input + view settings — read live by renderer / trackpad. Defaults match
    // the original constants so behaviour is unchanged before the app wires settings.

    @Volatile var cursorSensitivity: Float = 1.0f
    @Volatile var cursorIdleTimeoutMs: Long = 5_000L
    @Volatile var scrollSensitivity: Float = 12f
    @Volatile var flickSensitivity: Float = 0.01f
    @Volatile var longPressMs: Long = 500L
    @Volatile var pinchEnabled: Boolean = true
    @Volatile var autoRecenterOnUnlock: Boolean = true
    /** Carina 6DOF (positional parallax) vs 3DOF (orientation only). Read by HeadTracking
     *  on each reconnect via [WorkspacePresentation]; applies on the next reconnect. */
    @Volatile var carina6Dof: Boolean = false
    @Volatile var snapZonesEnabled: Boolean = true
    @Volatile var resizeHandlesEnabled: Boolean = true
    @Volatile var recordingFrameInterval: Int = 12
    @Volatile var captureDebugOverlay: Boolean = true

    /**
     * Density-DPI for the per-app trusted VirtualDisplay — read by
     * [UiScreen.startTrustedBare] when a new window comes up. Existing windows
     * keep the DPI they were created at; changes take effect on the next launch.
     */
    @Volatile var appDisplayDpi: Int = 200

    // endregion

    private fun enabledLayouts(): List<Layout> =
        enabledLayoutsProvider?.invoke()?.takeIf { it.isNotEmpty() }
            ?: Layout.values().toList()

    /**
     * An app being "carried" from the drawer to a desktop. Created when the user
     * press-and-holds a drawer icon; cleared either on a successful drop into a
     * screen's wallpaper area, or on cancel (a click on the drawer / taskbar / a
     * window). The icon bitmap is used by the renderer to draw a ghost at the
     * cursor while the drag is in flight.
     */
    data class ArmedDrag(
        val packageName: String,
        val activityName: String,
        val label: String,
        val iconBitmap: android.graphics.Bitmap,
        /**
         * Slot the drag originated on, when the source is an already-placed desktop
         * shortcut (a long-press *on the desktop* picked it up). Null when the source
         * is the drawer. The drop handler uses it to remove the old entry — and the
         * source's [DesktopShortcutsView] hides the icon at this position while the
         * drag is in flight so the user doesn't see two copies.
         */
        val sourceSlotIdx: Int? = null,
    )

    @Volatile
    var armedDrawerDrag: ArmedDrag? = null
        private set

    /**
     * Notified (on the main thread) whenever the armed-drag state changes — used by
     * the renderer to wake up its render loop so the ghost icon appears immediately,
     * and by Presentations to close the drawer once a drag has been picked up.
     */
    private val armedDragListeners =
        java.util.concurrent.CopyOnWriteArrayList<(ArmedDrag?) -> Unit>()

    fun addArmedDragListener(l: (ArmedDrag?) -> Unit) { armedDragListeners.add(l) }
    fun removeArmedDragListener(l: (ArmedDrag?) -> Unit) { armedDragListeners.remove(l) }

    /**
     * Start carrying [drag] toward a desktop. Closes the drawer so the user can see the
     * screens beneath it. Replaces any in-flight drag (no error if one was already
     * armed; the user effectively chose a different app).
     */
    fun armDrawerDrag(drag: ArmedDrag) {
        armedDrawerDrag = drag
        if (drawerOpenState) setDrawerOpen(false)
        armedDragListeners.forEach { runCatching { it(drag) } }
    }

    /** Cancel the in-flight drag (no shortcut placed). */
    fun cancelDrawerDrag() {
        if (armedDrawerDrag == null) return
        armedDrawerDrag = null
        armedDragListeners.forEach { runCatching { it(null) } }
    }

    /**
     * Resolves a successful drop. Returns true on success (caller may animate / toast
     * accordingly), false if the destination already holds the same app. Implementation
     * is wired by the app to write into its desktop-shortcuts store. When [drag]
     * carries a `sourceSlotIdx`, the drop is treated as a *move* — the source desktop's
     * entry is removed (or repositioned, if source and destination map to the same
     * desktop index).
     */
    @Volatile
    var placeArmedDrop: ((drag: ArmedDrag, slotIdx: Int, xFraction: Float, yFraction: Float) -> Boolean)? = null

    /**
     * Remove a shortcut for [drag.packageName] from its source desktop — the trash
     * path. Called by the renderer when the user drops a desktop-sourced drag in a
     * deletion zone (the taskbar guard band). No-op if [drag.sourceSlotIdx] is null
     * (drawer-sourced drags can't be deleted; they were never persisted).
     */
    @Volatile
    var removeArmedShortcut: ((drag: ArmedDrag) -> Unit)? = null

    /**
     * Looks up the drawer item under a slot-local pixel. Set by the active [DrawerView]
     * while it is visible; null otherwise. The renderer queries this when a press
     * lands on an open drawer panel — if it returns non-null, the renderer arms a
     * drag with that result.
     */
    @Volatile
    var drawerItemAt: ((slotPx: Int, slotPy: Int) -> ArmedDrag?)? = null

    /**
     * Per-slot lookup for "is the cursor on an existing desktop shortcut?". Each
     * [com.uxspace.desktop.DesktopShortcutsView] registers its own slot's lookup
     * when attached, removes it on detach. The renderer queries the lookup for the
     * slot under the cursor on a long-press.
     */
    private val desktopShortcutLookups =
        java.util.concurrent.ConcurrentHashMap<Int, (Int, Int) -> ArmedDrag?>()

    fun registerDesktopShortcutLookup(slotIdx: Int, lookup: (Int, Int) -> ArmedDrag?) {
        desktopShortcutLookups[slotIdx] = lookup
    }

    fun unregisterDesktopShortcutLookup(slotIdx: Int, lookup: (Int, Int) -> ArmedDrag?) {
        if (desktopShortcutLookups[slotIdx] === lookup) desktopShortcutLookups.remove(slotIdx)
    }

    fun desktopShortcutLookupFor(slotIdx: Int): ((Int, Int) -> ArmedDrag?)? =
        desktopShortcutLookups[slotIdx]

    /**
     * Effective main-screen index of whichever layout is *actually rendered* right now.
     * PINNED forces SINGLE (so the only slot is 0); FREE uses the user's saved layout
     * and main-screen choice. Callers that target a "main screen" slot (e.g. the
     * in-view toolbar's settings button) need this — the saved layout's main index
     * may not exist in the live scene.
     */
    fun mainScreenIdx(): Int {
        val live = effectiveLayout()
        val hook = mainScreenForLayout
        val raw = hook?.invoke(live) ?: live.defaultMainScreen
        return raw.coerceIn(0, (live.screens.size - 1).coerceAtLeast(0))
    }

    /** The user's saved layout — what's rendered in FREE mode. */
    val layout: Layout get() = layoutState

    /**
     * Update the FREE-mode layout. In PINNED the choice is *stored* but not rendered
     * (lock always shows SINGLE); the new layout takes effect on next unlock.
     */
    fun setLayout(layout: Layout) {
        layoutState = layout
        if (viewMode == WorkspaceRenderer.ViewMode.FREE) {
            renderer?.applyLayout(layout)
        }
    }

    /**
     * Cycle to the next [Layout]; only meaningful in FREE — PINNED is single-display
     * focus. Returns the new (or unchanged) layout.
     */
    fun cycleLayout(): Layout {
        if (viewMode != WorkspaceRenderer.ViewMode.FREE) return layoutState
        val enabled = enabledLayouts()
        // Nothing to cycle to — leave the workspace alone instead of re-applying the
        // same layout (which would tear down + rebuild every screen for no reason).
        if (enabled.size <= 1) return layoutState
        val currentIdx = enabled.indexOf(layoutState)
        val next = if (currentIdx < 0) enabled.first() else enabled[(currentIdx + 1).mod(enabled.size)]
        setLayout(next)
        // Surface the new layout's name as a transient bottom-of-view announcement,
        // so a Ctrl+Alt+A switch or toolbar tap that doesn't otherwise change the
        // visible scene (no apps yet) still confirms where the user landed.
        announceLayout(next.displayName)
        return next
    }

    /**
     * Show a 2-second "layout name" overlay at the bottom of the glasses view.
     * Idempotent; calling again resets the timer so back-to-back cycles always
     * show the latest name. The renderer picks up [layoutAnnouncement] and
     * [layoutAnnouncementExpiresAtMs] each frame.
     */
    fun announceLayout(name: String) {
        announceInView(name, LAYOUT_ANNOUNCE_MS)
    }

    /**
     * Show an arbitrary transient message overlay in the glasses view for [durationMs].
     * Used for layout names and for head-tracking reconnect status, so feedback is visible
     * in-view (not only as a phone toast). Idempotent; resets the timer each call.
     */
    fun announceInView(text: String, durationMs: Long = LAYOUT_ANNOUNCE_MS) {
        layoutAnnouncement = text
        layoutAnnouncementExpiresAtMs =
            android.os.SystemClock.uptimeMillis() + durationMs
    }

    private val LAYOUT_ANNOUNCE_MS = 2_000L

    /**
     * Re-evaluate the enabled-layouts list — call after the user toggles a layout's
     * "enabled" checkbox in settings. If the active layout has just become disabled,
     * switch to the next enabled one so the workspace doesn't keep rendering a
     * layout the user no longer wants in the rotation. PINNED keeps the stored
     * choice but renders SINGLE either way; FREE re-applies the new active layout.
     */
    fun applyEnabledLayoutsChanged() {
        val enabled = enabledLayouts()
        if (layoutState in enabled) return
        // Pick the first still-enabled layout — preserves declaration order so it
        // feels predictable.
        setLayout(enabled.first())
    }

    /** Save a PNG snapshot of the current workspace frame to the device's storage. */
    fun capture() {
        renderer?.requestCapture()
    }

    /** Toggle frame-sequence recording — saves a PNG every Nth render frame until stopped.
     *  Notifies [recordingListeners] so every surface (phone button, in-view toolbar) can
     *  reflect the new state regardless of which one toggled it. */
    fun toggleRecording(): Boolean {
        val r = renderer ?: return false
        val next = !r.isRecording()
        r.setRecording(next)
        recordingListeners.forEach { runCatching { it(next) } }
        return next
    }

    /** Whether a recording is currently in progress. */
    val isRecording: Boolean get() = renderer?.isRecording() == true

    private val recordingListeners =
        java.util.concurrent.CopyOnWriteArrayList<(recording: Boolean) -> Unit>()

    /** Notified (on the caller's thread) whenever recording starts or stops. */
    fun addRecordingListener(l: (recording: Boolean) -> Unit) { recordingListeners.add(l) }
    fun removeRecordingListener(l: (recording: Boolean) -> Unit) { recordingListeners.remove(l) }

    /**
     * Feed a head orientation (w, x, y, z) straight to the renderer, bypassing
     * [com.uxspace.glasses.HeadTracking]. Used by the debug-build simulator to drive the
     * camera from the PC when no glasses are attached. Safe to call from any thread.
     */
    fun injectHeadPose(w: Float, x: Float, y: Float, z: Float) {
        renderer?.setHeadPose(w, x, y, z)
    }

    /** Move the workspace cursor by a fraction of the touchpad's width. */
    fun moveCursor(dxFraction: Float, dyFraction: Float) {
        renderer?.moveCursor(dxFraction, dyFraction)
    }

    /** Scroll the screen under the cursor by a fraction of the touchpad's height. */
    fun scroll(dyFraction: Float) {
        renderer?.requestScroll(dyFraction)
    }

    /**
     * Trackpad two-finger drag — fractions of trackpad width / height. In PINNED mode with
     * the workspace zoomed past 1.0× this pans the zoomed viewport (the "zoom rectangle"
     * follows the fingers, matching the Windows companion's magnifier feel). In every other
     * state it falls back to the in-window vertical scroll, ignoring [dxFraction].
     *
     * Mouse-wheel scrolls go through [scroll] directly, not here — wheel input doesn't pan
     * even when zoomed (Ctrl+Alt+Wheel handles zoom; plain wheel stays a content scroll).
     */
    fun twoFingerDrag(dxFraction: Float, dyFraction: Float) {
        val r = renderer ?: return
        if (viewMode == WorkspaceRenderer.ViewMode.PINNED && r.workspaceZoom() > 1.0001f) {
            r.requestPan(dxFraction, dyFraction)
        } else {
            r.requestScroll(dyFraction)
        }
    }

    /** Register a cursor click in the workspace. */
    fun click() {
        renderer?.cursorClick()
    }

    /** Cancel an in-flight press (second finger lands, scroll/pinch takes over). */
    fun cancelDrag() {
        renderer?.cancelDrag()
    }

    /**
     * Display ids of the workspace's currently-live trusted virtual displays — the
     * displays UxSpace has launched apps onto. Used by [UxSpaceAccessibilityService]
     * to filter focus events: typing-into-app only fires for these displays, not the
     * phone's own screen.
     */
    private val uxspaceDisplayIds = java.util.concurrent.CopyOnWriteArraySet<Int>()

    /** Register a virtual display as UxSpace-owned. Called by the renderer when an
     *  app window is created. */
    fun registerUxSpaceDisplay(displayId: Int) {
        if (displayId >= 0) uxspaceDisplayIds.add(displayId)
    }

    /** True if [displayId] is one of UxSpace's own virtual displays. Any thread. */
    fun isUxSpaceDisplay(displayId: Int): Boolean = uxspaceDisplayIds.contains(displayId)

    /**
     * Fires when an [AccessibilityService]-detected text-input field on one of our
     * virtual displays gains focus — the phone's keyboard should pop up. The
     * [displayId] argument is the display where the field lives, so text typed on
     * the phone can be forwarded back to that display via `PrivilegedService.text`.
     */
    @Volatile
    var onAppTextFieldFocused: ((displayId: Int) -> Unit)? = null

    /** Fires when focus leaves the previously-focused text field on [displayId]. */
    @Volatile
    var onAppTextFieldUnfocused: ((displayId: Int) -> Unit)? = null


    /** How many distinct apps to remember in [recentApps]. */
    private const val MAX_RECENT_APPS = 32
}
