package com.uxspace

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.uxspace.apps.AppCache
import com.uxspace.desktop.DesktopPresentation
import com.uxspace.desktop.DesktopShortcutsStore
import com.uxspace.desktop.DesktopWallpaperStore
import com.uxspace.desktop.WorkspaceSettings
import com.uxspace.glasses.NativeGlasses
import com.uxspace.privileged.PrivilegedHotkeys
import com.uxspace.privileged.PrivilegedService
import com.uxspace.spatial.WorkspaceController
import com.uxspace.spatial.WorkspaceController.ToolbarIcons
import com.uxspace.spatial.WorkspaceRenderer
import com.uxspace.system.NotificationCenter
import com.uxspace.system.SystemStatus

private const val TAP_DOWN_UP_GAP_MS = 40L
private val tapDelayHandler = Handler(Looper.getMainLooper())

private const val HOTKEY_TAG = "UxSpace/Hotkey"
private const val DOF_TAG = "UxSpace/DOFWatchdog"

/**
 * Ctrl+Alt++ / - multiplier per press. 1.25 matches the Windows companion's `kZoomStep`
 * (0.25 added to a linear 1.0-4.0 range, which is ≈ ×1.25 per step at the low end).
 */
private const val HOTKEY_ZOOM_FACTOR = 1.25f

/** Wait this long after a DOF drop before triggering a rescan — gives spontaneous
 *  Carina recovery a chance, since several runs showed pose stream sometimes
 *  resumes on its own after a brief LIBUSB error. */
private const val DOF_RESCAN_GRACE_MS = 5_000L

/** Max consecutive auto-rescan attempts before deferring to the user. */
private const val DOF_RESCAN_MAX = 3

/** Raw evdev REL_X / REL_Y ticks per full NDC unit at cursorSensitivity 1.0. */
private const val RAW_MOUSE_REF_TICKS = 800f

/** Raw evdev REL_WHEEL ticks → workspace scroll fraction. Mirrors MOUSE_SCROLL_GAIN
 *  in MainActivity for the in-activity AXIS_VSCROLL path. */
private const val RAW_MOUSE_WHEEL_GAIN = 0.08f

/** Kernel mouse button codes. */
private const val BTN_LEFT = 272
private const val BTN_RIGHT = 273

// Kernel KEY_* editing codes for the hardware-keyboard drawer-search path.
private const val KEY_BACKSPACE = 14
private const val KEY_ENTER = 28
private const val KEY_KPENTER = 96

/**
 * Map a kernel KEY_* code to the character it types, for the drawer search box. Covers
 * letters, digits, space and a few name-friendly symbols — enough to search installed
 * apps. Search matching is case-insensitive, so shift is ignored (letters come back
 * lowercase). Returns null for keys with no textual character (arrows, F-keys, …).
 */
private fun evdevToChar(code: Int): Char? = when (code) {
    // Letters, in kernel-code order (KEY_Q row, KEY_A row, KEY_Z row).
    16 -> 'q'; 17 -> 'w'; 18 -> 'e'; 19 -> 'r'; 20 -> 't'
    21 -> 'y'; 22 -> 'u'; 23 -> 'i'; 24 -> 'o'; 25 -> 'p'
    30 -> 'a'; 31 -> 's'; 32 -> 'd'; 33 -> 'f'; 34 -> 'g'
    35 -> 'h'; 36 -> 'j'; 37 -> 'k'; 38 -> 'l'
    44 -> 'z'; 45 -> 'x'; 46 -> 'c'; 47 -> 'v'; 48 -> 'b'; 49 -> 'n'; 50 -> 'm'
    // Digits KEY_1..KEY_9, KEY_0.
    2 -> '1'; 3 -> '2'; 4 -> '3'; 5 -> '4'; 6 -> '5'
    7 -> '6'; 8 -> '7'; 9 -> '8'; 10 -> '9'; 11 -> '0'
    57 -> ' '            // KEY_SPACE
    12 -> '-'            // KEY_MINUS
    52 -> '.'            // KEY_DOT
    else -> null
}

/** Max ms a BTN_LEFT press can stay held before its release is no longer
 *  counted as a click (it was a long-press, intentional discard). */
private const val MOUSE_CLICK_MAX_MS = 350L

/**
 * Application entry point — initialises the privileged-helper orchestrator and wires the
 * workspace's injectable hooks once at process start, before any activity or the renderer
 * runs.
 */
class UxSpaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivilegedService.init(this)
        // The helper bootstraps over wireless-debugging ADB. Try to bring it up on every
        // launch — if pairing is needed or wireless debugging is off, the state machine
        // reflects it and MainActivity shows the setup card.
        PrivilegedService.ensureRunning()
        SystemStatus.init(this)
        NotificationCenter.init(this)
        WorkspaceSettings.init(this)
        DesktopShortcutsStore.init(this)
        DesktopWallpaperStore.init(this)
        loadInputAndViewSettings()
        WorkspaceSettings.addChangeListener { loadInputAndViewSettings() }
        // Pre-load the app list and rasterise icons on a background thread now, so the
        // drawer's first open is instant and scrolling doesn't hitch on icon draws.
        AppCache.preload(this)

        // Keeps the rendering layer free of the desktop UI and the privileged path.
        WorkspaceController.desktopContent = { context, display, slotIdx, showTaskbar ->
            DesktopPresentation(context, display, slotIdx, showTaskbar)
        }
        WorkspaceController.createVirtualDisplay = { name, width, height, dpi, surface ->
            PrivilegedService.createVirtualDisplay(name, width, height, dpi, surface)
        }
        WorkspaceController.releaseVirtualDisplay = { displayId ->
            PrivilegedService.releaseVirtualDisplay(displayId)
        }
        WorkspaceController.appLauncher = { displayId, packageName, activityName ->
            PrivilegedService.launchApp(displayId, packageName, activityName)
        }
        WorkspaceController.appTap = { displayId, x, y ->
            // Use the same InputManager.injectInputEvent path as the drag — `input tap`
            // shell-outs aren't reliable on out-of-process virtual displays. Some apps
            // reject 0-duration taps (DOWN and UP at the same eventTime), so space
            // them ~40 ms apart with a delayed UP.
            PrivilegedService.injectTouch(displayId, x, y, 0) // TOUCH_DOWN
            tapDelayHandler.postDelayed({
                PrivilegedService.injectTouch(displayId, x, y, 2) // TOUCH_UP
            }, TAP_DOWN_UP_GAP_MS)
        }
        WorkspaceController.appScroll = { displayId, x, y, vScroll ->
            PrivilegedService.scrollOnDisplay(displayId, x, y, vScroll)
        }
        WorkspaceController.appBack = { displayId, onEmptied ->
            PrivilegedService.sendBack(displayId, onEmptied)
        }
        WorkspaceController.appMediaPause = { displayId ->
            PrivilegedService.sendMediaPause(displayId)
        }
        WorkspaceController.closeApp = { packageName ->
            PrivilegedService.forceStop(packageName)
        }
        WorkspaceController.displayHasActivity = { displayId ->
            PrivilegedService.displayHasActivity(displayId)
        }
        // In-view toolbar icon resources — the renderer turns these into GL textures
        // when its surface comes up. Same Material-Symbols set the taskbar uses, so
        // both toolbars read identically.
        WorkspaceController.toolbarIcons = ToolbarIcons(
            lock = R.drawable.ic_pin,
            unlock = R.drawable.ic_pin_off,
            zoom = R.drawable.ic_zoom_in,
            recenter = R.drawable.ic_recenter,
            layout = R.drawable.ic_layout,
            settings = R.drawable.ic_settings,
            dofRetry = R.drawable.ic_dof_retry,
            capture = R.drawable.ic_capture,
            recordOn = R.drawable.ic_record_on,
        )
        WorkspaceController.mainScreenForLayout = { layout ->
            WorkspaceSettings.mainScreenFor(layout)
        }
        WorkspaceController.enabledLayoutsProvider = { WorkspaceSettings.enabledLayouts() }
        WorkspaceController.removeArmedShortcut = { drag ->
            val src = drag.sourceSlotIdx
            if (src != null) {
                val layout = WorkspaceController.layout
                val srcDesktop = DesktopShortcutsStore.desktopIdxFor(layout, src)
                DesktopShortcutsStore.remove(srcDesktop, drag.packageName)
            }
        }
        WorkspaceController.placeArmedDrop = { drag, slotIdx, xFraction, yFraction ->
            val layout = WorkspaceController.layout
            val destDesktop = DesktopShortcutsStore.desktopIdxFor(layout, slotIdx)
            val source = drag.sourceSlotIdx
            if (source != null) {
                val srcDesktop = DesktopShortcutsStore.desktopIdxFor(layout, source)
                if (srcDesktop == destDesktop) {
                    DesktopShortcutsStore.move(destDesktop, drag.packageName, xFraction, yFraction)
                    true
                } else {
                    // Cross-desktop transfer: try to add to dest first; on success drop
                    // from source. If dest already holds the same package, leave source
                    // untouched so the icon doesn't disappear into a rejection.
                    val placed = DesktopShortcutsStore.add(
                        destDesktop,
                        com.uxspace.desktop.DesktopShortcut(
                            packageName = drag.packageName,
                            activityName = drag.activityName,
                            label = drag.label,
                            xFraction = xFraction,
                            yFraction = yFraction,
                        ),
                    )
                    if (placed) DesktopShortcutsStore.remove(srcDesktop, drag.packageName)
                    placed
                }
            } else {
                DesktopShortcutsStore.add(
                    destDesktop,
                    com.uxspace.desktop.DesktopShortcut(
                        packageName = drag.packageName,
                        activityName = drag.activityName,
                        label = drag.label,
                        xFraction = xFraction,
                        yFraction = yFraction,
                    ),
                )
            }
        }
        WorkspaceController.cursorIcons = WorkspaceController.CursorIcons(
            resizeEW = R.drawable.ic_cursor_resize_ew,
            resizeNS = R.drawable.ic_cursor_resize_ns,
            resizeNESW = R.drawable.ic_cursor_resize_nesw,
            resizeNWSE = R.drawable.ic_cursor_resize_nwse,
        )

        // SDK-level tracker reset — Ctrl+Alt+R, matching the Windows companion's
        // reset_origin action. Ctrl+Alt+C goes through alignVerticalToHead() (the
        // app-side anchor, same path the visual-space toolbar's recenter uses).
        WorkspaceController.sdkRecenter = {
            // runCatching: a build without the VITURE SDK (simulator) has no native bridge,
            // and touching NativeGlasses there throws UnsatisfiedLinkError.
            val rc = runCatching { NativeGlasses.resetOriginCarina() }
            Log.i(HOTKEY_TAG, "Ctrl+Alt+R: resetOriginCarina rc=${rc.getOrNull() ?: rc.exceptionOrNull()}")
        }

        // Raw evdev mouse deltas — bypass the Android system cursor's screen-edge
        // clamping. dx/dy come straight from the kernel as REL_X/REL_Y ticks, so
        // the workspace cursor keeps moving even when the (hidden) system pointer
        // is pinned against the phone-screen edge. Tick scaling: at 800-DPI mouse
        // sensitivity 1.0, ~800 ticks crosses a full NDC unit — roughly mirrors
        // the trackpad's "full sweep ≈ one screen" feel; cursorSensitivity
        // multiplies on top in WorkspaceController.moveCursor.
        PrivilegedService.mouseDeltaHandler = { dx, dy, wheel ->
            if (dx != 0 || dy != 0) {
                // First motion while the primary button is held — fire beginDrag
                // BEFORE applying the delta to the cursor. The renderer's
                // beginDrag snapshots the cursor at that moment, which is the
                // press position (since no motion has been applied yet). The
                // resize-band / chrome hit-tests next frame then run against
                // press position instead of "press + N ticks".
                if (mousePrimaryDown && !mouseDragging) {
                    mouseDragging = true
                    WorkspaceController.beginDrag()
                }
                WorkspaceController.moveCursor(
                    dx / RAW_MOUSE_REF_TICKS,
                    dy / RAW_MOUSE_REF_TICKS,
                )
            }
            if (wheel != 0) {
                // REL_WHEEL ticks: 1 per notch on a notched wheel. Same gain as
                // the in-activity AXIS_VSCROLL path.
                WorkspaceController.scroll(wheel * RAW_MOUSE_WHEEL_GAIN)
            }
        }

        PrivilegedService.mouseButtonHandler = { code, pressed ->
            when (code) {
                BTN_LEFT -> handleMousePrimary(pressed)
                // Right-click opens the desktop context menu (Arrange icons,
                // Change wallpaper, Settings, etc.). Only on press — release is
                // a no-op so a single press+release shows the menu once.
                BTN_RIGHT -> if (pressed) WorkspaceController.requestRightClick()
                // Middle is consumed (system never sees it) but no action yet.
            }
        }

        // Global Ctrl+Alt+X hotkeys captured by the shell-uid PrivilegedServer from
        // /dev/input/event*. Dispatched here on the main thread.
        PrivilegedService.hotkeyHandler = { code ->
            when (code) {
                PrivilegedHotkeys.HK_ZOOM_IN -> {
                    Log.i(HOTKEY_TAG, "Ctrl+Alt++ (zoom in)")
                    WorkspaceController.pinch(HOTKEY_ZOOM_FACTOR)
                }
                PrivilegedHotkeys.HK_ZOOM_OUT -> {
                    Log.i(HOTKEY_TAG, "Ctrl+Alt+- (zoom out)")
                    WorkspaceController.pinch(1f / HOTKEY_ZOOM_FACTOR)
                }
                PrivilegedHotkeys.HK_CYCLE_SCREEN_BAND -> {
                    Log.i(HOTKEY_TAG, "Ctrl+Alt+Z (cycle screen band)")
                    WorkspaceController.cycleScreenBand()
                }
                PrivilegedHotkeys.HK_TOGGLE_VIEW_MODE -> {
                    val next = if (WorkspaceController.currentViewMode ==
                        WorkspaceRenderer.ViewMode.PINNED
                    ) WorkspaceRenderer.ViewMode.FREE else WorkspaceRenderer.ViewMode.PINNED
                    Log.i(HOTKEY_TAG, "Ctrl+Alt+X (toggle view mode -> $next)")
                    WorkspaceController.setViewMode(next)
                }
                PrivilegedHotkeys.HK_SDK_RECENTER -> {
                    WorkspaceController.sdkRecenter?.invoke()
                }
                PrivilegedHotkeys.HK_ANCHOR_POSE -> {
                    Log.i(HOTKEY_TAG, "Ctrl+Alt+C (anchor at current pose)")
                    WorkspaceController.alignVerticalToHead()
                }
                PrivilegedHotkeys.HK_CYCLE_LAYOUT -> {
                    // cycleLayout already early-returns in PINNED — safe to call always.
                    val next = WorkspaceController.cycleLayout()
                    Log.i(HOTKEY_TAG, "Ctrl+Alt+A (cycle layout -> $next)")
                }
                PrivilegedHotkeys.HK_TILE_LEFT ->
                    WorkspaceController.windowCommand(WorkspaceController.WindowCommand.TILE_LEFT)
                PrivilegedHotkeys.HK_TILE_RIGHT ->
                    WorkspaceController.windowCommand(WorkspaceController.WindowCommand.TILE_RIGHT)
                PrivilegedHotkeys.HK_MAXIMIZE ->
                    WorkspaceController.windowCommand(WorkspaceController.WindowCommand.MAXIMIZE)
                PrivilegedHotkeys.HK_RESTORE_DOWN ->
                    WorkspaceController.windowCommand(WorkspaceController.WindowCommand.RESTORE_DOWN)
                PrivilegedHotkeys.HK_MOVE_PREV_SCREEN ->
                    WorkspaceController.windowCommand(WorkspaceController.WindowCommand.MOVE_PREV_SCREEN)
                PrivilegedHotkeys.HK_MOVE_NEXT_SCREEN ->
                    WorkspaceController.windowCommand(WorkspaceController.WindowCommand.MOVE_NEXT_SCREEN)
                PrivilegedHotkeys.HK_CLOSE_WINDOW ->
                    WorkspaceController.windowCommand(WorkspaceController.WindowCommand.CLOSE)
                PrivilegedHotkeys.HK_SHOW_DESKTOP -> WorkspaceController.toggleShowDesktop()
                PrivilegedHotkeys.HK_OPEN_DRAWER -> WorkspaceController.openDrawerUnderCursor()
                PrivilegedHotkeys.HK_MODIFIERS_DOWN -> {
                    WorkspaceController.legendVisible = true
                }
                PrivilegedHotkeys.HK_MODIFIERS_UP -> {
                    WorkspaceController.legendVisible = false
                }
                else -> Log.w(HOTKEY_TAG, "unknown hotkey code=$code")
            }
        }

        // Hardware-keyboard text (non Ctrl+Alt) forwarded from the shell-uid reader. The
        // desktop Presentation is non-focusable (launched apps keep Android input focus), so
        // a USB/BT keyboard's keys never reach the drawer's search box on their own — feed it
        // here. Launched apps still get their own keys via normal focus, so we only act while
        // the drawer is open, and reset the typed query at the start of each drawer session.
        PrivilegedService.keyHandler = { keyCode, pressed, _ ->
            if (pressed && WorkspaceController.isDrawerOpen) {
                when (keyCode) {
                    KEY_BACKSPACE ->
                        if (drawerQuery.isNotEmpty()) drawerQuery.deleteCharAt(drawerQuery.length - 1)
                    KEY_ENTER, KEY_KPENTER -> Unit // keep the query; the user taps a result
                    else -> evdevToChar(keyCode)?.let { drawerQuery.append(it) }
                }
                WorkspaceController.setDrawerSearchQuery(drawerQuery.toString())
            }
        }
        WorkspaceController.addDrawerStateListener { open, _ -> if (open) drawerQuery.setLength(0) }

        // Two-finger touchpad pinch → workspace zoom, the same entry point the phone
        // trackpad's pinch uses. [scale] is the per-frame span ratio (>1 = zoom in).
        PrivilegedService.zoomHandler = { scale -> WorkspaceController.pinch(scale) }

        // DOF-stall watchdog: when DOF goes down while the glasses display is
        // still attached AND we had DOF earlier this session, try a USB rescan
        // (helper unbinds + binds the VITURE device). Cap at DOF_RESCAN_MAX
        // attempts so we don't loop forever — past that, surface a Toast and
        // let the user replug. Reset attempt count on any successful recovery.
        WorkspaceController.addDofListener { active ->
            if (active) {
                hadDofThisSession = true
                dofRescanAttempts = 0
                pendingRescan?.let { rescanHandler.removeCallbacks(it) }
                pendingRescan = null
                return@addDofListener
            }
            // DOF down.
            if (!hadDofThisSession) return@addDofListener
            if (!WorkspaceController.isRunning) return@addDofListener
            if (dofRescanAttempts >= DOF_RESCAN_MAX) {
                Log.w(
                    DOF_TAG,
                    "DOF still down after $DOF_RESCAN_MAX rescans — prompting user",
                )
                Toast.makeText(
                    applicationContext,
                    "Glasses tracking didn't recover. Try unplugging and reconnecting.",
                    Toast.LENGTH_LONG,
                ).show()
                return@addDofListener
            }
            val runnable = Runnable {
                // Re-check at fire time: DOF may have come back during the grace.
                if (WorkspaceController.headTrackingActive) return@Runnable
                if (!WorkspaceController.isRunning) return@Runnable
                dofRescanAttempts++
                Log.i(
                    DOF_TAG,
                    "auto-rescan attempt $dofRescanAttempts/$DOF_RESCAN_MAX",
                )
                Toast.makeText(
                    applicationContext,
                    "Rescanning glasses USB ($dofRescanAttempts/$DOF_RESCAN_MAX)…",
                    Toast.LENGTH_SHORT,
                ).show()
                PrivilegedService.rescanGlassesUsb()
            }
            pendingRescan = runnable
            rescanHandler.postDelayed(runnable, DOF_RESCAN_GRACE_MS)
        }
    }

    private var hadDofThisSession = false
    private var dofRescanAttempts = 0
    private var pendingRescan: Runnable? = null
    private val rescanHandler = Handler(Looper.getMainLooper())

    /** Mouse primary-button + drag state, mirroring MainActivity's old in-window
     *  state machine but driven by raw evdev events from the privileged helper. */
    private var mousePrimaryDown = false
    private var mousePrimaryDownAtMs = 0L
    private var mouseDragging = false

    /** Text typed on a hardware keyboard into the drawer search box this drawer session. */
    private val drawerQuery = StringBuilder()

    /** Press / release of the left mouse button. Press starts a potential drag;
     *  motion past [MOUSE_DRAG_TICK_THRESHOLD] promotes it. Release ends the
     *  drag if one was running, otherwise fires a click if the hold was short
     *  enough to count as a click rather than a long-press of a stationary
     *  button. */
    private fun handleMousePrimary(pressed: Boolean) {
        if (pressed) {
            mousePrimaryDown = true
            mousePrimaryDownAtMs = android.os.SystemClock.uptimeMillis()
            mouseDragging = false
            return
        }
        mousePrimaryDown = false
        if (mouseDragging) {
            mouseDragging = false
            WorkspaceController.endDrag()
            return
        }
        val held = android.os.SystemClock.uptimeMillis() - mousePrimaryDownAtMs
        if (held in 0..MOUSE_CLICK_MAX_MS) {
            WorkspaceController.click()
        }
    }

    /**
     * Pulls every Input + View tab value from [WorkspaceSettings] into the controller's
     * live volatile fields. Invoked on startup and whenever the user mutates a setting,
     * so changes take effect on the next renderer/trackpad read with no app restart.
     */
    private fun loadInputAndViewSettings() {
        WorkspaceController.cursorSensitivity = WorkspaceSettings.cursorSensitivity()
        WorkspaceController.cursorIdleTimeoutMs =
            WorkspaceSettings.cursorIdleSeconds().toLong() * 1000L
        WorkspaceController.scrollSensitivity = WorkspaceSettings.scrollSensitivity()
        WorkspaceController.flickSensitivity = WorkspaceSettings.flickSensitivity()
        WorkspaceController.longPressMs = WorkspaceSettings.longPressMs()
        WorkspaceController.pinchEnabled = WorkspaceSettings.pinchEnabled()
        WorkspaceController.autoRecenterOnUnlock = WorkspaceSettings.autoRecenterOnUnlock()
        WorkspaceController.carina6Dof = WorkspaceSettings.carina6Dof()
        // Screen fill (how much of the glasses display the workspace occupies). Pushed as the
        // render band so it's applied to the live renderer and re-applied on every reconnect
        // via WorkspaceController.register(). Replaces the old hardcoded 0.83 band.
        WorkspaceController.setScreenBand(WorkspaceSettings.screenFill())
        WorkspaceController.maxWindowsPerSlot = WorkspaceSettings.maxWindowsPerSlot()
        WorkspaceController.snapZonesEnabled = WorkspaceSettings.snapZonesEnabled()
        WorkspaceController.resizeHandlesEnabled = WorkspaceSettings.resizeHandlesEnabled()
        WorkspaceController.recordingFrameInterval = WorkspaceSettings.recordingFrameInterval()
        WorkspaceController.captureDebugOverlay = WorkspaceSettings.captureDebugOverlay()
        WorkspaceController.appDisplayDpi = WorkspaceSettings.appDisplayDpi()
    }
}
