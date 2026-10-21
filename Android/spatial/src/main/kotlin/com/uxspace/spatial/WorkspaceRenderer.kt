package com.uxspace.spatial

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Renders the virtual space as a 3D scene of textured quads — one quad per [Screen]
 * of the active [Layout]. Each screen is a fully independent [UiScreen] hosting its
 * own [com.uxspace.desktop.DesktopPresentation] + app stack on a trusted
 * VirtualDisplay; the workspace also draws the app-drawer overlay and touchpad cursor.
 *
 * Each screen's quad samples an external-OES texture fed by a VirtualDisplay;
 * switching the [Layout] tears down and rebuilds the screen list. The camera comes
 * from [setHeadPose]: PINNED locks the scene to the view, FREE world-fixes it as the
 * head turns. App launches arrive on any thread via [requestApp] and land on the
 * screen under the cursor in [addWindow] (GL thread).
 */
class WorkspaceRenderer(
    private val context: Context,
    private val mainHandler: Handler,
) : GLSurfaceView.Renderer {

    private val appContext: Context = context.applicationContext

    private data class AppRequest(
        val packageName: String,
        val activityName: String,
        val label: String,
        /**
         * When non-null, forces the launch onto this specific screen index (used by
         * layout-restore paths). Null means pick the screen under the cursor at launch.
         */
        val screenIdx: Int? = null,
    )

    /** Apps currently launched per screen of the active layout, keyed by screen index. */
    private val currentScreenApps = mutableMapOf<Int, AppRequest>()

    /**
     * Live list of windowed launched apps. Each entry owns its own bare trusted
     * VirtualDisplay (no Presentation attached), sampled as a quad drawn inside the
     * slot's quad. Single source of truth for "what's on the workspace right now";
     * [currentScreenApps] in parallel is the per-(layout, slot) launch record used by
     * layout persistence.
     */
    private val runningWindows = mutableListOf<AppWindow>()

    /** Increment-only counter used to name each window's bare VirtualDisplay. */
    private var nextWindowId = 0

    /**
     * Per-layout memory of which app sits on which screen. When the user cycles away from
     * a layout, its current allocation is stashed here; cycling back relaunches each saved
     * app onto its remembered screen — so Two SBS → Three SBS → Two SBS restores the
     * Two-SBS apps to the screens they were on before the detour.
     */
    private val layoutAppMemory = mutableMapOf<Layout, Map<Int, AppRequest>>()

    private val pendingApps = ConcurrentLinkedQueue<AppRequest>()
    /** Window-control actions posted from the main thread to run on the GL thread. */
    private val glTasks = ConcurrentLinkedQueue<() -> Unit>()

    /** Screen 0's UiScreen — UxSpace's own home, hosted on its own VirtualDisplay. */
    private var desktop: UiScreen? = null

    /**
     * Extra screens — one [UiScreen] per [Screen] of the active layout beyond screen 0.
     * Each is a fully independent UxSpace environment (its own VirtualDisplay, its own
     * [com.uxspace.desktop.DesktopPresentation] with wallpaper + drawer + taskbar).
     * Screen 0 uses [desktop]; screens 1..N-1 use these. Recreated on layout switch.
     */
    private val extraScreens = mutableListOf<UiScreen>()


    // Head-orientation quaternion (w, x, y, z); identity means looking straight ahead.
    @Volatile private var headW = 1f
    @Volatile private var headX = 0f
    @Volatile private var headY = 0f
    @Volatile private var headZ = 0f

    // Head position (metres) in the heading-recentred world frame, relative to the recentre
    // origin. Non-zero only on 6DOF (Carina) tracking; drives positional parallax in FREE
    // view. Orientation-only paths never call [setHeadPosition], so these stay 0.
    @Volatile private var headPosX = 0f
    @Volatile private var headPosY = 0f
    @Volatile private var headPosZ = 0f

    /**
     * "Recenter" anchor — a rotation post-multiplied onto the head pose before it drives the
     * FREE-mode view matrix. Identity by default; [recenterScene] sets it to the inverse of
     * the current head pose, so the scene snaps to "straight ahead" of wherever the user is
     * currently looking (e.g. switching from sitting to lying down). All windows rotate as
     * one rigid scene — they keep their relative spatial layout.
     */
    @Volatile private var anchorW = 1f
    @Volatile private var anchorX = 0f
    @Volatile private var anchorY = 0f
    @Volatile private var anchorZ = 0f

    /** How the scene tracks the head. */
    @Volatile private var viewMode = ViewMode.PINNED

    /** Whether the scene follows the head ([PINNED]) or stays put in the world ([FREE]). */
    enum class ViewMode { PINNED, FREE }

    // Screen-quad program — samples a SurfaceTexture as an external-OES texture.
    private var screenProgram = 0
    private var screenAPosition = 0
    private var screenATexCoord = 0
    private var screenUMvp = 0
    private var screenUTexMatrix = 0
    private var screenUTexture = 0

    private var cursorProgram = 0
    private var cursorAPosition = 0
    private var cursorUCenter = 0
    private var cursorUHalfSize = 0
    private var cursorUColor = 0

    // Solid-colour 3D program — fills geometry with a flat uColor in world space
    // (uMvp), used for the FULLSCREEN toolbar's circular reveal hint.
    private var solidProgram = 0
    private var solidAPosition = 0
    private var solidUMvp = 0
    private var solidUColor = 0
    private lateinit var circleFan: java.nio.FloatBuffer

    // HUD-icon program: textured quads in NDC space, used for the in-view toolbar.
    private var hudProgram = 0
    private var hudAPosition = 0
    private var hudATexCoord = 0
    private var hudUCenter = 0
    private var hudUHalfSize = 0
    private var hudUColor = 0
    private var hudUTexture = 0
    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    @Volatile private var cursorClickPending = false
    private var cursorFlashFrames = 0

    /** Last time the touchpad sent any cursor-affecting input; drives the idle hide. */
    @Volatile private var lastInputAtMs: Long = android.os.SystemClock.uptimeMillis()

    /**
     * Bottom-centre in-view toolbar. Idle = a 5-px peek line; on cursor hover or button
     * click the toolbar expands to 4 buttons (lock/unlock, zoom, recenter, layout) and
     * stays visible for [TOOLBAR_AUTOHIDE_MS] after the last interaction.
     */
    @Volatile private var toolbarExpanded = false
    @Volatile private var toolbarLastShownMs = 0L

    /**
     * One in-view toolbar button. The icon resource is looked up per-frame via
     * [iconResIdProvider] so state-dependent buttons (e.g. lock ↔ unlock follows
     * [viewMode]) swap their pixels without us having to rebuild the array.
     */
    private data class HudButton(
        val label: String,
        val cx: Float,
        val halfW: Float,
        val iconResIdProvider: () -> Int,
        /** Per-frame check; disabled buttons render grayed and are skipped on click. */
        val enabled: () -> Boolean = { true },
        val action: () -> Unit,
    )

    private val toolbarButtons: Array<HudButton> by lazy {
        arrayOf(
            HudButton(
                "lock", cx = -0.35f, halfW = TOOLBAR_BUTTON_HALF_W,
                // Dual-role button. With DOF live it is the lock/unlock (PINNED ↔ FREE)
                // toggle. With DOF down it becomes a "retry head tracking" button — FREE is
                // meaningless without head input, so rather than sit grayed-out the button
                // offers a way to recover a tracker that never came up (e.g. Carina VIO
                // failing to converge) without unplugging the glasses.
                iconResIdProvider = {
                    val icons = WorkspaceController.toolbarIcons
                    when {
                        icons == null -> 0
                        !WorkspaceController.headTrackingActive -> icons.dofRetry
                        viewMode == ViewMode.PINNED -> icons.lock
                        else -> icons.unlock
                    }
                },
                // Always tappable now: lock toggle when DOF is up, retry when it is down.
                enabled = { true },
            ) {
                if (WorkspaceController.headTrackingActive) {
                    val next = if (viewMode == ViewMode.PINNED) ViewMode.FREE else ViewMode.PINNED
                    WorkspaceController.setViewMode(next)
                } else {
                    // Routes to MainActivity.attemptReconnectDof, which restarts tracking
                    // and toasts / announces the success-or-failure outcome.
                    Log.i(TAG, "toolbar: DOF-retry tapped — re-attempting head tracking")
                    WorkspaceController.retryHeadTracking?.invoke()
                }
            },
            HudButton(
                "zoom", cx = -0.21f, halfW = TOOLBAR_BUTTON_HALF_W,
                iconResIdProvider = { WorkspaceController.toolbarIcons?.zoom ?: 0 },
            ) {
                WorkspaceController.cycleScreenBand()
            },
            HudButton(
                "recenter", cx = -0.07f, halfW = TOOLBAR_BUTTON_HALF_W,
                iconResIdProvider = { WorkspaceController.toolbarIcons?.recenter ?: 0 },
            ) {
                WorkspaceController.alignVerticalToHead()
            },
            HudButton(
                "layout", cx = 0.07f, halfW = TOOLBAR_BUTTON_HALF_W,
                iconResIdProvider = { WorkspaceController.toolbarIcons?.layout ?: 0 },
                // PINNED always renders SINGLE; cycleLayout is a no-op there, so the
                // button is dead weight when locked.
                enabled = { viewMode == ViewMode.FREE },
            ) {
                WorkspaceController.cycleLayout()
            },
            HudButton(
                "settings", cx = 0.21f, halfW = TOOLBAR_BUTTON_HALF_W,
                iconResIdProvider = { WorkspaceController.toolbarIcons?.settings ?: 0 },
            ) {
                // Always open on the *main* screen of the active layout, regardless of
                // where the cursor is — the in-view toolbar belongs to the whole
                // workspace, not any single slot. The per-Presentation taskbars keep
                // their own "open on this slot" semantics.
                val slot = WorkspaceController.mainScreenIdx()
                val openHere = WorkspaceController.isSettingsOpen &&
                    WorkspaceController.settingsOnScreen == slot
                WorkspaceController.setSettingsOpen(!openHere, slot)
            },
            HudButton(
                "capture", cx = 0.35f, halfW = TOOLBAR_BUTTON_HALF_W,
                // Toggles frame-sequence recording; the icon flips to a red dot while
                // recording so it doubles as an in-view recording indicator. (A one-shot
                // screenshot is a tap on the phone control panel's capture button.)
                iconResIdProvider = {
                    val icons = WorkspaceController.toolbarIcons
                    when {
                        icons == null -> 0
                        WorkspaceController.isRecording -> icons.recordOn
                        else -> icons.capture
                    }
                },
            ) {
                val on = WorkspaceController.toggleRecording()
                Log.i(TAG, "toolbar: capture button toggled recording -> $on")
            },
        )
    }

    /**
     * GL texture cache — drawable resource id → texture object name. Populated lazily
     * on the GL thread from [drawToolbar]; the renderer's [release] frees them.
     */
    private val iconTextures = HashMap<Int, Int>()

    /**
     * Active multi-screen layout. New windows pick a screen from this layout on
     * launch; [applyLayout] reflows the existing ones. Mirrors [WorkspaceController.layout].
     */
    @Volatile private var layout: Layout = Layout.SINGLE

    /** Accumulated pinch scale (1.0 = identity); applied once per frame. */
    @Volatile private var pendingPinch = 1f
    private var surfaceAspect = 1.78f

    @Volatile private var captureRequested = false
    @Volatile private var recording = false
    private var recordingFrameCounter = 0

    // --- Async capture pipeline ---------------------------------------------------------
    // glReadPixels into a Pixel-Buffer Object returns immediately (no GPU stall); the bytes
    // are mapped and consumed one capture later, then the heavy work (row-flip → ARGB,
    // bitmap, debug overlay, PNG encode, file write) runs on a single worker thread. Buffers
    // are reused; [captureBusy] caps it at one in-flight capture so encodes can't pile up
    // and the reusable buffers are never written while the worker reads them.
    private var capturePboId = 0
    private var capturePboW = 0
    private var capturePboH = 0
    @Volatile private var captureReadbackPending = false
    private var capturePendingSnapshot = false
    private val captureBusy = AtomicBoolean(false)
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "uxspace-capture").apply { isDaemon = true }
    }
    /** RGBA, bottom-up — copied out of the mapped PBO on the GL thread, read by the worker. */
    private var captureWorkBuf = ByteArray(0)
    /** ARGB top-down scratch — worker thread only. */
    private var capturePixels = IntArray(0)
    /** Reused output bitmap — worker thread only. */
    private var captureBitmap: Bitmap? = null
    @Volatile private var pendingScroll = 0f

    /**
     * Two-finger pan deltas (fractions of trackpad width / height) waiting to be applied to
     * the active view-mode's translation in [handlePan]. Used by the locked-and-zoomed pan
     * gesture — dragging two fingers shifts the zoomed viewport (magnifier convention:
     * rectangle follows fingers). [handlePan] only acts in PINNED + zoomed; otherwise the
     * deltas are dropped.
     */
    @Volatile private var pendingPanX = 0f
    @Volatile private var pendingPanY = 0f

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Height of the centred 16:9 render area, as a fraction of the display height. */
    @Volatile private var screenBand = DEFAULT_SCREEN_BAND
    @Volatile private var bandDirty = false

    /**
     * Pinch-driven projection zoom, anchored at the cursor's NDC position at the moment
     * of the pinch. 1.0 = identity; 2.0 = everything twice as large. Kept *per view mode*
     * — switching FREE ↔ PINNED restores that mode's saved zoom + cursor anchor — so a
     * mode toggle never blows the user's chosen scale away. Re-applied to [projection]
     * on the GL thread every frame the value changes.
     */
    @Volatile private var freeZoom = DEFAULT_WORKSPACE_ZOOM
    @Volatile private var freeTx = 0f
    @Volatile private var freeTy = 0f
    @Volatile private var pinnedZoom = DEFAULT_PINNED_ZOOM
    @Volatile private var pinnedTx = 0f
    @Volatile private var pinnedTy = 0f

    /** Desktop quad half-extents (metres), sized in [onSurfaceChanged] to fill the view. */
    private var desktopHalfWidth = 3.7f
    private var desktopHalfHeight = 2.1f

    private lateinit var screenQuad: FloatBuffer
    private lateinit var cursorArrow: FloatBuffer
    private lateinit var scrimQuad: FloatBuffer
    private lateinit var hudQuad: FloatBuffer

    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Scratch for unprojecting the cursor onto the desktop plane.
    private val invViewProjection = FloatArray(16)
    private val clipPoint = FloatArray(4)
    private val worldPoint = FloatArray(4)

    /** Queue an app to be opened in a window. Safe to call from any thread. */
    fun requestApp(packageName: String, activityName: String, label: String) {
        pendingApps.add(AppRequest(packageName, activityName, label))
        Log.i(
            "UxSpace/Launch",
            "3) renderer.requestApp pkg=$packageName enqueued (pending=${pendingApps.size})",
        )
    }

    /** Feed a head-orientation quaternion for the camera. Safe to call from any thread. */
    fun setHeadPose(w: Float, x: Float, y: Float, z: Float) {
        headW = w
        headX = x
        headY = y
        headZ = z
    }

    /**
     * Feed the recentred head position (metres) for positional parallax. Only 6DOF tracking
     * paths call this; the camera translates with the head in FREE view so the workspace
     * shows real parallax. Safe to call from any thread.
     */
    fun setHeadPosition(x: Float, y: Float, z: Float) {
        headPosX = x
        headPosY = y
        headPosZ = z
    }

    /** Switch how the scene tracks the head. Safe to call from any thread. */
    fun setViewMode(mode: ViewMode) {
        if (viewMode == mode) return
        val prev = viewMode
        viewMode = mode
        when {
            // Lock from FREE → snap the locked view onto the screen the user was looking at.
            prev == ViewMode.FREE && mode == ViewMode.PINNED -> anchorPinnedOnProminentScreen()
            // Unlock from PINNED → reset the FREE scene anchor so it appears centred on
            // wherever the user is currently looking (rather than drifting off-axis from
            // wherever it happened to be world-fixed before).
            prev == ViewMode.PINNED && mode == ViewMode.FREE ->
                if (WorkspaceController.autoRecenterOnUnlock) alignVerticalToHead()
        }
        // Each mode keeps its own (zoom, tx, ty); rebuild the projection so the new
        // mode's saved zoom takes effect immediately on swap.
        bandDirty = true
        // Announce the new mode's zoom so the trackpad HUD reflects the *current*
        // mode's value — otherwise it lingers on the previous mode's last zoom number
        // and reads as if the modes share a zoom level (they don't).
        mainHandler.post { WorkspaceController.notifyZoomChanged(activeZoom()) }
        Log.i(
            TAG,
            "setViewMode $mode — zoom=${"%.3f".format(activeZoom())} " +
                "tx=${"%.3f".format(activeTx())} ty=${"%.3f".format(activeTy())}",
        )
    }

    /**
     * Recenter every window vertically on the user's current head pitch — useful when
     * the workspace has drifted above / below the natural eye line in FREE mode (or
     * when the user wants to sit/stand and have the screens follow). Computes the
     * head-forward Y component and shifts each window so a quad at the workspace
     * distance lands on that line. PINNED mode: still applies, since the head pose
     * is fed even though the view matrix is identity.
     */
    /**
     * Recenter the scene to the current head pose — VR-style "reset view". The whole
     * workspace (every open window, the desktop, the taskbar) rotates as one rigid scene so
     * that the part the user was looking at the moment they pressed the button is now
     * straight ahead. Switches sitting ↔ lying-down without having to drag every screen.
     *
     * No-op in PINNED mode (the scene is already locked to the viewport — there's nothing
     * to recenter against).
     */
    /**
     * Switch the active layout: stash the outgoing layout's slot→app allocation,
     * rebuild the per-screen desktops, then restore the incoming layout's saved apps onto
     * their remembered screens (if any). Force-closes apps on the outgoing layout.
     */
    fun applyLayout(next: Layout) {
        glTasks.add {
            val layoutChanged = layout != next
            // Save the outgoing layout's screen→app allocation so cycling back to it
            // restores those apps onto the same screens. Filter out entries whose
            // VirtualDisplay no longer has an activity (user backed out of the app),
            // so a relaunch on return doesn't bring back something the user already
            // closed.
            val hasActivity = WorkspaceController.displayHasActivity
            val live = if (hasActivity == null) {
                currentScreenApps.toMap()
            } else {
                currentScreenApps.filter { (screenIdx, _) ->
                    val id = screenDisplayId(screenIdx)
                    id != null && hasActivity(id)
                }
            }
            if (live.isNotEmpty()) {
                layoutAppMemory[layout] = live
                Log.i(
                    TAG,
                    "applyLayout: saved ${live.size}/${currentScreenApps.size} app(s) for ${layout.displayName}",
                )
            } else {
                layoutAppMemory.remove(layout)
            }
            // Force-stop the outgoing apps — their screens are about to be released.
            val outgoingPkgs = currentScreenApps.values.map { it.packageName }
            val slotsWithWindow = runningWindows.map { it.slotIdx }.toSet()
            currentScreenApps.clear()
            runningWindows.forEach { it.release() }
            runningWindows.clear()
            outgoingPkgs.forEach { closeAndNotify(it) }
            slotsWithWindow.forEach { notifyWindowBoundsForSlot(it) }

            layout = next
            val screens = next.screens
            Log.i(TAG, "applyLayout ${next.displayName} screens=${screens.size}")
            // Reset the projection anchors when the layout actually changes — old NDC
            // tx/ty values were computed against the previous layout's screen geometry
            // (or by setViewMode's anchorPinnedOnProminentScreen using the outgoing
            // layout). Reapplying them to a new layout shifts the new screens off
            // centre — e.g. lock from VHV → SINGLE leaves the single screen pushed
            // off-screen unless we zero the anchor here.
            if (layoutChanged) {
                freeTx = 0f; freeTy = 0f
                pinnedTx = 0f; pinnedTy = 0f
                bandDirty = true
            }
            // Bring the screen list up to the layout's screen count — screen 0 reuses
            // the primary `desktop`, screens 1..N-1 are independent UiScreens.
            syncScreens(screens.size)

            // Restore previously-saved apps for the incoming layout — schedule them so
            // they fire after the screens have had time to come up on the main
            // thread (UiScreen.startTrusted is posted async).
            val saved = layoutAppMemory[next] ?: emptyMap()
            saved.forEach { (screenIdx, req) ->
                if (screenIdx < screens.size) {
                    Log.i(
                        TAG,
                        "applyLayout: scheduling restore of ${req.packageName} on screen $screenIdx",
                    )
                    mainHandler.postDelayed(
                        { pendingApps.add(req.copy(screenIdx = screenIdx)) },
                        300L,
                    )
                }
            }
        }
        noteInput()
    }

    /**
     * Make the screen list have exactly `screenCount` UiScreens (screen 0 = the primary
     * `desktop`; screens 1..N-1 = independents in `extraScreens`). Creating an extra
     * UiScreen also kicks off the per-screen DesktopPresentation on its own VirtualDisplay
     * via [WorkspaceController.desktopContent] — each screen is a fully independent UxSpace
     * environment (own wallpaper, drawer, taskbar). GL thread only.
     */
    private fun syncScreens(screenCount: Int) {
        val factory = WorkspaceController.desktopContent
        if (factory == null) {
            Log.w(TAG, "syncScreens: no desktop factory registered yet — skipping")
            return
        }
        // Rebuild every screen desktop on layout switch — screen.showTaskbar is baked into
        // the DesktopPresentation at construction, so config changes per screen need a
        // fresh Presentation. Cheap (just tears down the per-screen Presentation).
        desktop?.release()
        desktop = null
        extraScreens.forEach { it.release() }
        extraScreens.clear()

        layout.screens.forEachIndexed { i, screen ->
            // The backing VirtualDisplay matches the screen's declared resolution, so an
            // app launched onto a wide / vertical screen actually lays out for that shape
            // (true 3840×1200 ultrawide, 1080×1920 portrait) instead of getting a 1080p
            // surface stretched onto the screen's quad.
            val ui = UiScreen(
                createExternalTexture(),
                screen.contentWidthPx, screen.contentHeightPx,
                mainHandler,
                if (i == 0) "uxspace-desktop" else "uxspace-desktop-screen$i",
            )
            if (i == 0) desktop = ui else extraScreens.add(ui)
            val showTb = screen.showTaskbar
            val screenIdx = i
            mainHandler.post {
                // Trusted display so apps can also be launched onto this same display —
                // the per-screen Presentation (wallpaper + taskbar) sits underneath any
                // activity stacked on top by launchOnDisplay.
                ui.startTrusted(context) { ctx, disp -> factory(ctx, disp, screenIdx, showTb) }
            }
            Log.i(TAG, "syncScreens: screen $screenIdx ready (showTaskbar=$showTb)")
        }
    }

    fun alignVerticalToHead() {
        Log.i(TAG, "alignVerticalToHead() queued — viewMode=$viewMode screens=${layout.screens.size}")
        glTasks.add {
            if (viewMode == ViewMode.PINNED) {
                Log.i(TAG, "alignVerticalToHead: PINNED mode — recenter is a no-op")
                return@add
            }
            val w0 = headW; val x0 = headX; val y0 = headY; val z0 = headZ
            val norm = w0 * w0 + x0 * x0 + y0 * y0 + z0 * z0
            if (norm < 1e-6f) {
                Log.w(TAG, "alignVerticalToHead: head pose is zero — no anchor set")
                return@add
            }
            // Recenter so the screen currently taking the most view space sits straight
            // ahead and face-on — not the middle of the virtual space. In a multi-screen
            // layout the middle is the seam between screens, so aiming there leaves every
            // screen off-axis and yawed relative to the gaze, which reads as "angled". Side
            // screens are yawed to face the origin/eye, so centring the camera on the
            // dominant screen makes it fronto-parallel.
            //
            // Pick the dominant screen from the LIVE gaze: effective = head ⊗ current anchor;
            // score each screen by foreshortened apparent size (world area / distance²) ×
            // how centred its direction is in the gaze (dot with forward). The one filling
            // the most of the field of view wins; fall back to the configured main screen
            // if the gaze faces away from the scene.
            val ew = w0 * anchorW - x0 * anchorX - y0 * anchorY - z0 * anchorZ
            val ex = w0 * anchorX + x0 * anchorW + y0 * anchorZ - z0 * anchorY
            val ey = w0 * anchorY - x0 * anchorZ + y0 * anchorW + z0 * anchorX
            val ez = w0 * anchorZ + x0 * anchorY - y0 * anchorX + z0 * anchorW
            val fwdX = -2f * (ex * ez + ew * ey)
            val fwdY = 2f * (ew * ex - ey * ez)
            val fwdZ = -1f + 2f * (ex * ex + ey * ey)
            var chosenIdx = -1
            var bestScore = 0f
            layout.screens.forEachIndexed { i, screen ->
                val rr = Screen.worldRect(screen, desktopHalfWidth, desktopHalfHeight)
                val d2 = rr[0] * rr[0] + rr[1] * rr[1] + rr[2] * rr[2]
                if (d2 < 1e-4f) return@forEachIndexed
                val cosA = (fwdX * rr[0] + fwdY * rr[1] + fwdZ * rr[2]) /
                    kotlin.math.sqrt(d2)
                if (cosA <= 0f) return@forEachIndexed          // screen behind / beside gaze
                val apparent = rr[3] * rr[4] / d2               // ∝ solid angle it subtends
                val score = cosA * apparent
                if (score > bestScore) { bestScore = score; chosenIdx = i }
            }
            if (chosenIdx < 0) {
                chosenIdx = WorkspaceController.mainScreenIdx()
                    .coerceIn(0, layout.screens.size - 1)
            }
            val r = Screen.worldRect(layout.screens[chosenIdx], desktopHalfWidth, desktopHalfHeight)
            val cx = r[0]; val cy = r[1]; val cz = r[2]
            // Look-rotation that aims world-forward (−Z) at the screen centre with no roll:
            // yaw about world Y to the screen's azimuth, then pitch about X to its elevation.
            // Roll-free and gravity-level, so a subsequent head yaw stays tilt-free.
            val horiz = kotlin.math.sqrt(cx * cx + cz * cz)
            val theta = kotlin.math.atan2(-cx, -cz)   // azimuth: left screen (cx<0) → +θ
            val phi = kotlin.math.atan2(cy, horiz)     // elevation: below eye (cy<0) → −φ
            val cyq = kotlin.math.cos(theta / 2f); val syq = kotlin.math.sin(theta / 2f)
            val cpq = kotlin.math.cos(phi / 2f); val spq = kotlin.math.sin(phi / 2f)
            // R_look = qYaw(θ) ⊗ qPitch(φ)
            val rw = cyq * cpq; val rx = cyq * spq; val ry = syq * cpq; val rz = -syq * spq
            // anchor = head⁻¹ ⊗ R_look, so effective = head·anchor = R_look right now (camera
            // looks at the main screen) and world-frame head deltas compose on top afterwards.
            val invN = 1f / norm
            val pw = w0 * invN; val px = -x0 * invN; val py = -y0 * invN; val pz = -z0 * invN
            anchorW = pw * rw - px * rx - py * ry - pz * rz
            anchorX = pw * rx + px * rw + py * rz - pz * ry
            anchorY = pw * ry - px * rz + py * rw + pz * rx
            anchorZ = pw * rz + px * ry - py * rx + pz * rw
            Log.i(
                TAG,
                "alignVerticalToHead: centre on screen[$chosenIdx] " +
                    "world=(${"%.2f".format(cx)},${"%.2f".format(cy)},${"%.2f".format(cz)}) " +
                    "θ=${"%.1f".format(Math.toDegrees(theta.toDouble()))}° " +
                    "φ=${"%.1f".format(Math.toDegrees(phi.toDouble()))}°",
            )
        }
        noteInput()
    }

    /** Request a PNG snapshot of the next rendered frame. Safe to call from any thread. */
    fun requestCapture() {
        captureRequested = true
    }

    /**
     * Toggle a capture-every-Nth-frame "video" recording — the workspace surface is
     * saved as a PNG sequence under the same captures directory as [requestCapture],
     * one file per [RECORDING_FRAME_INTERVAL]th GL frame. For ad-hoc debugging of
     * cursor / touch behaviour: start, perform the gesture, stop, browse the frames.
     */
    fun setRecording(on: Boolean) {
        recording = on
        recordingFrameCounter = 0
    }

    /** Whether the workspace is currently being recorded. */
    fun isRecording(): Boolean = recording

    /** Accumulate a scroll delta (fraction of the touchpad height). Safe from any thread. */
    fun requestScroll(dyFraction: Float) {
        pendingScroll += dyFraction
        noteInput()
    }

    /**
     * Accumulate a two-finger pan delta — fractions of trackpad width / height — applied
     * to the active view-mode's translation on the next frame in [handlePan]. Magnifier
     * convention: positive dx/dy = visible rectangle moves right / down in the scene.
     * Only PINNED + zoomed acts; everything else drops the deltas silently.
     */
    fun requestPan(dxFraction: Float, dyFraction: Float) {
        pendingPanX += dxFraction
        pendingPanY += dyFraction
        noteInput()
    }

    /** Accumulate a pinch scale factor (1.0 = identity). Safe from any thread. */
    fun requestPinch(scaleFactor: Float) {
        pendingPinch *= scaleFactor
        Log.d(TAG, "requestPinch scale=${"%.4f".format(scaleFactor)} pending=${"%.4f".format(pendingPinch)}")
        noteInput()
    }

    /**
     * Begin / end / cancel a long-press gesture. The trackpad fires these via the
     * libinput drag-lock pattern (tap → second touch within DRAG_LOCK_MS → drag).
     * Per-frame the renderer checks the pending flags inside [onDrawFrame] and
     * decides whether the cursor is over a window's chrome — if so the drag is
     * latched to that window and subsequent cursor motion moves the window
     * inside its slot. Anywhere else the drag is a no-op for now (later phases:
     * resize on edge / corner press).
     */
    fun beginDrag() {
        noteInput()
        // Snapshot the cursor at the moment beginDrag is requested. The hit-test
        // in tryBeginWindowInteraction runs on the GL thread one frame later;
        // for mouse, fast motion between the press and that frame can move the
        // cursor several tens of slot-pixels — past the 16 px resize band,
        // missing resize entirely. Latching the press-position cursor here
        // makes the hit-test see where the user actually pressed.
        dragRequestCursorX = cursorX
        dragRequestCursorY = cursorY
        pendingDragStart = true
    }

    @Volatile private var dragRequestCursorX: Float = 0f
    @Volatile private var dragRequestCursorY: Float = 0f

    /**
     * Press-and-hold without a prior tap — the touchpad's standalone long-press.
     * Currently used to arm a drag-from-drawer when the cursor is over a drawer
     * cell; anywhere else it's a no-op. Handled in [onDrawFrame] so the renderer
     * sees a consistent cursor state.
     */
    fun requestLongPress() {
        noteInput()
        pendingLongPress = true
    }

    @Volatile private var pendingLongPress: Boolean = false

    fun endDrag() {
        noteInput()
        pendingDragEnd = true
    }

    fun cancelDrag() {
        noteInput()
        pendingDragEnd = true
    }

    @Volatile private var pendingDragStart: Boolean = false
    @Volatile private var pendingDragEnd: Boolean = false
    /** Window currently being dragged (chrome-strip press), or null. GL-thread only. */
    private var dragWindow: AppWindow? = null
    /** Window currently being resized (edge / corner press), or null. GL-thread only. */
    private var resizeWindow: AppWindow? = null
    /** Which edge / corner of [resizeWindow] is being dragged. */
    private var resizeEdge: ResizeEdge = ResizeEdge.E
    /** Cursor's slot-local px at the previous drag / resize update, for delta computation. */
    private var dragLastCursorPx: Int = 0
    private var dragLastCursorPy: Int = 0
    /**
     * Prospective tile target for the in-flight drag — set by [updateWindowDrag]
     * when the cursor enters a snap zone, cleared otherwise. On drag release,
     * [finishWindowInteraction] applies it via [AppWindow.setState] so the
     * window snaps to the previewed tile rectangle.
     */
    private var dragSnapHint: WorkspaceController.WindowMode? = null

    /**
     * Hit-test region around a window's outer rect for edge / corner resize.
     * Pure N (top edge) is omitted because the chrome strip occupies the top
     * of the window — drag-on-chrome takes priority there.
     */
    private enum class ResizeEdge { E, W, S, NE, NW, SE, SW }

    /** Move the cursor by a fraction of the touchpad's width. Safe to call from any thread. */
    /** Counter for throttling the per-move cursor log so logcat stays readable. */
    private var moveLogCounter: Int = 0

    fun moveCursor(dxFraction: Float, dyFraction: Float) {
        val sens = WorkspaceController.cursorSensitivity
        cursorX = (cursorX + dxFraction * sens).coerceIn(-1f, 1f)
        cursorY = (cursorY - dyFraction * sens).coerceIn(-1f, 1f)
        // Log every Nth move so we can see the workspace NDC reach against the
        // input deltas. Includes the input dx/dy so it's clear how much input
        // mapped to how much cursor delta on the way to its current position.
        if (CURSOR_DIAG && moveLogCounter++ % CURSOR_LOG_EVERY == 0) {
            Log.i(
                TAG,
                "moveCursor: in=(${"%.4f".format(dxFraction)},${"%.4f".format(dyFraction)})*${"%.2f".format(sens)} " +
                    "→ ndc=(${"%.3f".format(cursorX)},${"%.3f".format(cursorY)})",
            )
        }
        noteInput()
    }

    /** Register a cursor click. Safe to call from any thread. */
    fun cursorClick() {
        cursorClickPending = true
        noteInput()
    }

    @Volatile private var rightClickPending = false

    /** Mark a right-click at the current cursor position; processed on the
     *  next GL frame to decide if it's on the desktop (→ open context menu)
     *  or on a running window (ignored for V1). */
    fun requestRightClick() {
        rightClickPending = true
        noteInput()
    }

    /** Bump the idle timer — any touchpad activity wakes the cursor. */
    private fun noteInput() {
        lastInputAtMs = android.os.SystemClock.uptimeMillis()
    }

    /** Per-slot cache of the last cursor-in-bottom-band state, so the renderer only
     *  fires [WorkspaceController.notifyTaskbarHover] on edge transitions. Grows on
     *  demand as slot indices appear; sparsely keyed because layouts have at most a
     *  handful of slots. Read / written only on the GL thread. */
    private var taskbarHoverPrevArr: BooleanArray = BooleanArray(0)

    private fun taskbarHoverPrev(slotIdx: Int): Boolean =
        slotIdx < taskbarHoverPrevArr.size && taskbarHoverPrevArr[slotIdx]

    /** Last taskbar pointer column sent per slot (−1 = not over the bar). GL thread. */
    private var taskbarPointerPrevArr: IntArray = IntArray(0)

    private fun taskbarPointerPrev(slotIdx: Int): Int =
        if (slotIdx < taskbarPointerPrevArr.size) taskbarPointerPrevArr[slotIdx] else -1

    private fun setTaskbarPointerPrev(slotIdx: Int, value: Int) {
        if (slotIdx >= taskbarPointerPrevArr.size) {
            val grown = IntArray((slotIdx + 1).coerceAtLeast(4)) { -1 }
            taskbarPointerPrevArr.copyInto(grown)
            taskbarPointerPrevArr = grown
        }
        taskbarPointerPrevArr[slotIdx] = value
    }

    private fun setTaskbarHoverPrev(slotIdx: Int, value: Boolean) {
        if (slotIdx >= taskbarHoverPrevArr.size) {
            taskbarHoverPrevArr = taskbarHoverPrevArr.copyOf((slotIdx + 1).coerceAtLeast(4))
        }
        taskbarHoverPrevArr[slotIdx] = value
    }

    /**
     * Force-stop every launched app — called when the workspace is torn down (the glasses
     * are unplugged), so the apps close instead of being relocated onto the phone's screen.
     */
    fun closeAllWindows() {
        val pkgs = currentScreenApps.values.map { it.packageName }
        val slotsWithWindow = runningWindows.map { it.slotIdx }.toSet()
        currentScreenApps.clear()
        runningWindows.forEach { it.release() }
        runningWindows.clear()
        pkgs.forEach { closeAndNotify(it) }
        slotsWithWindow.forEach { notifyWindowBoundsForSlot(it) }
    }

    /** Force-stop an app and notify taskbar listeners so they drop its icon. */
    private fun closeAndNotify(packageName: String) {
        WorkspaceController.closeApp?.invoke(packageName)
        mainHandler.post { WorkspaceController.notifyAppClosed(packageName) }
    }

    /** Release the desktop, every screen, and their GL resources. Call on the GL thread. */
    fun releaseAll() {
        desktop?.release()
        desktop = null
        extraScreens.forEach { it.release() }
        extraScreens.clear()
        runningWindows.forEach { it.release() }
        runningWindows.clear()
        currentScreenApps.clear()
        if (ghostTextureId != 0) {
            val arr = intArrayOf(ghostTextureId)
            GLES20.glDeleteTextures(1, arr, 0)
            ghostTextureId = 0
        }
        ghostSourceBitmap = null
        if (legendTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(legendTextureId), 0)
            legendTextureId = 0
        }
        if (layoutAnnouncementTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(layoutAnnouncementTextureId), 0)
            layoutAnnouncementTextureId = 0
            layoutAnnouncementCachedText = null
        }
        // Capture PBO belongs to the now-gone context — zero it so ensureCapturePbo
        // recreates on the next capture (the id from the old context is invalid here).
        capturePboId = 0
        capturePboW = 0
        capturePboH = 0
        captureReadbackPending = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.03f, 0.04f, 0.06f, 1f)

        // A fresh GL context — discard anything bound to a previous one.
        releaseAll()

        screenProgram = buildProgram(SCREEN_VERTEX_SHADER, SCREEN_FRAGMENT_SHADER)
        screenAPosition = GLES20.glGetAttribLocation(screenProgram, "aPosition")
        screenATexCoord = GLES20.glGetAttribLocation(screenProgram, "aTexCoord")
        screenUMvp = GLES20.glGetUniformLocation(screenProgram, "uMvp")
        screenUTexMatrix = GLES20.glGetUniformLocation(screenProgram, "uTexMatrix")
        screenUTexture = GLES20.glGetUniformLocation(screenProgram, "uTexture")

        cursorProgram = buildProgram(CURSOR_VERTEX_SHADER, CURSOR_FRAGMENT_SHADER)
        cursorAPosition = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
        cursorUCenter = GLES20.glGetUniformLocation(cursorProgram, "uCenter")
        cursorUHalfSize = GLES20.glGetUniformLocation(cursorProgram, "uHalfSize")
        cursorUColor = GLES20.glGetUniformLocation(cursorProgram, "uColor")

        hudProgram = buildProgram(HUD_VERTEX_SHADER, HUD_FRAGMENT_SHADER)
        hudAPosition = GLES20.glGetAttribLocation(hudProgram, "aPosition")
        hudATexCoord = GLES20.glGetAttribLocation(hudProgram, "aTexCoord")
        hudUCenter = GLES20.glGetUniformLocation(hudProgram, "uCenter")
        hudUHalfSize = GLES20.glGetUniformLocation(hudProgram, "uHalfSize")
        hudUColor = GLES20.glGetUniformLocation(hudProgram, "uColor")
        hudUTexture = GLES20.glGetUniformLocation(hudProgram, "uTexture")

        solidProgram = buildProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
        solidAPosition = GLES20.glGetAttribLocation(solidProgram, "aPosition")
        solidUMvp = GLES20.glGetUniformLocation(solidProgram, "uMvp")
        solidUColor = GLES20.glGetUniformLocation(solidProgram, "uColor")

        screenQuad = directBufferOf(SCREEN_QUAD_VERTICES)
        cursorArrow = directBufferOf(CURSOR_ARROW_VERTICES)
        scrimQuad = directBufferOf(SCRIM_QUAD_VERTICES)
        hudQuad = directBufferOf(HUD_QUAD_VERTICES)
        circleFan = directBufferOf(buildCircleFanVertices(CIRCLE_HINT_SEGMENTS))
        // GL context just came up — any cached icon texture ids are stale.
        iconTextures.clear()

        // Screens are built later by syncScreens (triggered by the queued
        // applyLayout task from WorkspaceController.register) — screen 0 included, so
        // each per-screen Presentation gets its own showTaskbar config baked in. The
        // drawer is a child view of each screen's DesktopPresentation (not a separate
        // VirtualDisplay), so the renderer doesn't initialise or own one.
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        applyBand()
    }

    /**
     * Render the workspace into a centred 16:9 screen sized to [fraction] of the display
     * height. The glasses cover a ~52° field of view; using the whole panel — especially an
     * ultra-wide shape — is tiring, so the scene is a normal 16:9 screen, smaller and
     * centred. Safe to call from any thread.
     */
    fun setScreenBand(fraction: Float) {
        screenBand = fraction.coerceIn(MIN_SCREEN_BAND, MAX_SCREEN_BAND)
        bandDirty = true
    }

    /**
     * Apply the render band — a centred 16:9 viewport sized to the band fraction. 16:9 is the
     * comfortable screen shape and matches the desktop and app surfaces. GL thread only.
     */
    private fun applyBand() {
        bandDirty = false
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        // Largest 16:9 rectangle that is `screenBand` of the display height and fits its width.
        var areaH = (surfaceHeight * screenBand).toInt()
        var areaW = areaH * 16 / 9
        if (areaW > surfaceWidth) {
            areaW = surfaceWidth
            areaH = areaW * 9 / 16
        }
        areaW = areaW.coerceAtLeast(1)
        areaH = areaH.coerceAtLeast(1)
        GLES20.glViewport((surfaceWidth - areaW) / 2, (surfaceHeight - areaH) / 2, areaW, areaH)

        surfaceAspect = areaW.toFloat() / areaH.toFloat()
        Matrix.perspectiveM(projection, 0, FOV_Y_DEGREES, surfaceAspect, NEAR_PLANE, FAR_PLANE)
        // Pinch zoom on top — scale projection X/Y to magnify around the saved cursor
        // anchor for the active view mode. NDC math: x_ndc = zoom * x_ndc_orig + tx.
        //   projection[0,5] *= rawZoom .......... scales NDC around (0,0)
        //   projection[8,9] = -tx, -ty .......... NDC translation so the anchor stays put
        // rawZoom = displayedZoom × layout.zoomBaseScale — the per-layout multiplier
        // hides the fact that some presets (Wide, V) have smaller default world-size, so
        // the displayed % means the same visual scale across all presets.
        val rawZoom = activeZoom() * layout.zoomBaseScale
        val tx = activeTx()
        val ty = activeTy()
        projection[0] *= rawZoom
        projection[5] *= rawZoom
        projection[8] = -tx
        projection[9] = -ty

        // Size the desktop quad to exactly fill the field of view at its distance.
        val halfFov = Math.toRadians(FOV_Y_DEGREES / 2.0)
        desktopHalfHeight = (DESKTOP_DISTANCE * Math.tan(halfFov)).toFloat()
        desktopHalfWidth = desktopHalfHeight * surfaceAspect
    }

    override fun onDrawFrame(gl: GL10?) {
        if (bandDirty) applyBand()
        drainGlTasks()
        drainPendingApps()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (surfaceWidth == 0 || surfaceHeight == 0) return

        buildView(viewMatrix)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)

        GLES20.glUseProgram(screenProgram)

        // Slot desktops — one *independent* UxSpace environment per layout screen, drawn
        // at the screen's geometry. Screen 0 = the primary `desktop`; screens 1..N-1 =
        // `extraScreens`. Each one is its own VirtualDisplay-hosted Presentation
        // (own wallpaper / drawer / taskbar). The layout is "how many of these
        // are stitched together in 3D and where."
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        layout.screens.forEachIndexed { i, screen ->
            val d = if (i == 0) desktop else extraScreens.getOrNull(i - 1)
            if (d == null) return@forEachIndexed
            d.updateTexture()
            val r = Screen.worldRect(screen, desktopHalfWidth, desktopHalfHeight)
            if (screen.curveDeg > 0f) {
                drawCurvedScreen(d, screen, r[0], r[1], r[2])
            } else {
                buildModelRectYawed(modelMatrix, r[0], r[1], r[2], r[3], r[4], screen.yawDeg)
                Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
                drawExternalQuad(d.textureId, d.textureMatrix)
            }
            // Auto-hide taskbar: per-slot hover edge — fire only on transitions so
            // DesktopPresentation gets one event per enter/leave, not 60/sec.
            val cursorPx = cursorToRectPx(screen)
            val hovering = cursorPx != null &&
                cursorPx[1] >= (screen.contentHeightPx - TASKBAR_HOVER_ZONE_PX)
            if (hovering != taskbarHoverPrev(i)) {
                setTaskbarHoverPrev(i, hovering)
                WorkspaceController.notifyTaskbarHover(i, hovering)
            }
            // Taskbar tooltips: the cursor's x while it's over the bar itself, in
            // TASKBAR_POINTER_STEP_PX columns so the slot hears a change, not every frame.
            val barX = if (cursorPx != null &&
                cursorPx[1] >= screen.contentHeightPx - WINDOW_TASKBAR_PX
            ) {
                cursorPx[0].toInt() / TASKBAR_POINTER_STEP_PX * TASKBAR_POINTER_STEP_PX
            } else {
                -1
            }
            if (barX != taskbarPointerPrev(i)) {
                setTaskbarPointerPrev(i, barX)
                WorkspaceController.notifyTaskbarPointer(i, barX)
            }
        }

        // App-window pass — each launched activity is a separate trusted display sampled
        // as its own quad drawn *inside* its host slot's quad. Both the slot and the
        // window quads are at the same world Z; we just disabled depth test above so the
        // later-drawn window pixels win. Minimised windows skip the draw but keep their
        // texture updates so restore is instant.
        // Modal-state snapshot — these don't change during a frame and are read once
        // per window in the loop below. Avoid the volatile reads on the inner path.
        val drawerOpen = WorkspaceController.isDrawerOpen
        val drawerScreen = WorkspaceController.drawerOnScreen
        val settingsOpen = WorkspaceController.isSettingsOpen
        val settingsScreen = WorkspaceController.settingsOnScreen
        val audioOpen = WorkspaceController.isAudioOpen
        val audioScreen = WorkspaceController.audioOnScreen
        val trayOpen = WorkspaceController.trayPanel != null
        val trayScreen = WorkspaceController.trayPanelOnScreen
        for (window in runningWindows) {
            val slotScreen = layout.screens.getOrNull(window.slotIdx) ?: continue
            if (window.displayId == null) continue  // bare display still spinning up
            window.ui.updateTexture()
            if (window.minimized) continue
            if (slotScreen.curveDeg > 0f) {
                // Curved slot: map the window's UV range onto a sub-arc of the
                // cylinder so the app appears on the curved screen instead of
                // floating as a flat quad in front.
                drawCurvedWindow(window, slotScreen)
            } else {
                val rect = computeWindowWorldRect(window, slotScreen)
                buildModelRectYawed(
                    modelMatrix,
                    rect[0], rect[1], rect[2], rect[3], rect[4],
                    slotScreen.yawDeg,
                )
                Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
                drawExternalQuad(window.ui.textureId, window.ui.textureMatrix)
            }

            // Fullscreen floating chrome — auto-hidden, revealed only while the
            // cursor is over its top-left zone (expanded by a small margin so the
            // buttons stay reachable). The chrome view lives in the slot's
            // Presentation laid out at chromeOverlayRect; when revealed we re-sample
            // that sub-region of the slot texture and draw it on top of the activity
            // quad. Skipping the draw leaves the toolbar covered by the activity =
            // hidden, so on launch (cursor elsewhere) it stays out of the way.
            if (window.mode == WorkspaceController.WindowMode.FULLSCREEN) {
                val slotUi = if (window.slotIdx == 0) desktop
                else extraScreens.getOrNull(window.slotIdx - 1)
                if (slotUi != null) {
                    val cr = window.chromeOverlayRect(slotScreen.contentWidthPx)
                    val cur = cursorToRectPx(slotScreen)
                    val m = FULLSCREEN_TOOLBAR_REVEAL_MARGIN_PX
                    val overToolbar = cur != null &&
                        cur[0] >= cr[0] - m && cur[0] < cr[0] + cr[2] + m &&
                        cur[1] >= cr[1] - m && cur[1] < cr[1] + cr[3] + m
                    if (overToolbar) {
                        drawSlotModalOverlay(
                            slotScreen,
                            slotUi,
                            WorkspaceController.ModalBounds(cr[0], cr[1], cr[2], cr[3]),
                        )
                    } else {
                        // Hidden — show a semi-opaque circle hint where the toolbar
                        // will appear (centred on the toolbar rect, diameter = half
                        // its height) so the reveal target is discoverable.
                        drawSlotCircleHint(
                            slotScreen,
                            cr[0] + cr[2] / 2f,
                            cr[1] + cr[3] / 2f,
                            cr[3] / 2f,
                        )
                    }
                }
            }

            // Modal-on-top pass — when a drawer or settings panel is open on this
            // window's slot, re-sample the slot's Presentation surface at the modal
            // rect and draw it on top of the activity quad. The slot quad already
            // contains the modal (it's a view in the Presentation), so a UV-clipped
            // re-draw is enough; no separate display is needed.
            val drawerOpenHere = drawerOpen && drawerScreen == window.slotIdx
            val settingsOpenHere = settingsOpen && settingsScreen == window.slotIdx
            val audioOpenHere = audioOpen && audioScreen == window.slotIdx
            val trayOpenHere = trayOpen && trayScreen == window.slotIdx
            if (drawerOpenHere || settingsOpenHere || audioOpenHere || trayOpenHere) {
                val modal = WorkspaceController.modalBoundsForSlot(window.slotIdx)
                val slotUi = if (window.slotIdx == 0) desktop
                else extraScreens.getOrNull(window.slotIdx - 1)
                if (modal != null && slotUi != null) {
                    drawSlotModalOverlay(slotScreen, slotUi, modal)
                }
            }
        }

        if (pendingLongPress) {
            pendingLongPress = false
            handleLongPress()
        }
        if (pendingDragStart) {
            pendingDragStart = false
            tryBeginWindowInteraction()
        }
        if (dragWindow != null) {
            updateWindowDrag()
        }
        if (resizeWindow != null) {
            updateWindowResize()
        }
        if (pendingDragEnd) {
            pendingDragEnd = false
            finishWindowInteraction()
        }
        if (rightClickPending) {
            rightClickPending = false
            handleRightClick()
        }
        if (cursorClickPending) {
            cursorClickPending = false
            cursorFlashFrames = CURSOR_FLASH_FRAMES
            // A tap on chrome / edge that immediately enters drag or resize
            // (via tap-and-drag) shouldn't also fire as a normal click — the
            // gesture has already latched onto the window. Skip the click
            // in those cases.
            if (dragWindow == null && resizeWindow == null) handleClick()
        }
        if (pendingScroll != 0f) {
            handleScroll(pendingScroll)
            pendingScroll = 0f
        }
        if (pendingPanX != 0f || pendingPanY != 0f) {
            handlePan(pendingPanX, pendingPanY)
            pendingPanX = 0f
            pendingPanY = 0f
        }
        if (pendingPinch != 1f) {
            handlePinch(pendingPinch)
            pendingPinch = 1f
        }
        drawToolbar()
        drawRecordingIndicator()
        if (WorkspaceController.legendVisible) drawLegend()
        drawLayoutAnnouncement()
        if (WorkspaceController.isOptometryOpen) {
            // Head-locked over everything; no cursor, since a click anywhere acts on it.
            eyeChart.draw(surfaceWidth, surfaceHeight)
            applyBand()
        } else {
            eyeChart.release()
            drawCursor()
        }

        // Capture pipeline: first consume any async readback issued on an earlier frame
        // (no GPU stall — the data is long since ready), then issue a new readback if a
        // snapshot was requested or a recording sample is due. One in flight at a time.
        consumeCaptureReadback()
        val interval = WorkspaceController.recordingFrameInterval.coerceAtLeast(1)
        val recordingDue = recording && (recordingFrameCounter % interval == 0)
        recordingFrameCounter++
        if (!captureReadbackPending && !captureBusy.get()) {
            if (captureRequested) {
                captureRequested = false
                issueCaptureReadback(snapshot = true)
            } else if (recordingDue) {
                issueCaptureReadback(snapshot = false)
            }
        }
    }

    /** Draw a [screenProgram] quad textured with an external-OES texture; [mvpMatrix] is set. */
    /**
     * Render a screen's UiScreen as N angled segments arranged on a cylinder around the
     * viewer — the curved-ultrawide visual. The flat screen at world centre (cx, cy, cz)
     * of size (w, h) gets sliced into [CURVED_SEGMENTS] vertical strips; each strip is
     * positioned on the arc subtending [arcDeg] and shows its 1/N slice of the screen's
     * texture (via a slice * surfaceTransform texture matrix).
     */
    private fun drawCurvedScreen(
        ui: UiScreen,
        screen: Screen,
        cx: Float, cy: Float, cz: Float,
    ) {
        val n = CURVED_SEGMENTS
        val radius = -cz  // distance from origin (z is negative in front of the camera)
        val arcRad = Math.toRadians(screen.curveDeg.toDouble()).toFloat()
        val arcLength = radius * arcRad
        // Height keeps the screen's content aspect, applied to the *arc length* — so a
        // wider arc means a proportionally taller display (the ultrawide aspect is
        // preserved end-to-end on the cylinder). Independent of widthFraction.
        val aspect = screen.contentWidthPx.toFloat() / screen.contentHeightPx
        val halfH = arcLength / aspect / 2f
        // Build a single triangle-strip mesh that wraps around the cylinder — N+1 vertex
        // columns sharing edges, so there are zero gaps between segments (flat-quads
        // can't tile a curve without gaps in Z; a strip with shared verts can).
        val buf = curvedMeshBuffer(n + 1)
        var off = 0
        for (i in 0..n) {
            val tNorm = i.toFloat() / n
            val theta = -arcRad / 2f + tNorm * arcRad
            val sin = kotlin.math.sin(theta)
            val cos = kotlin.math.cos(theta)
            val x = cx + radius * sin
            val z = cz * cos  // cz is negative; multiplying by cos preserves sign
            // Top vertex of column (positive Y), then bottom (so the triangle strip is
            // wound consistently — top0, bot0, top1, bot1, ...).
            buf.put(off++, x);              buf.put(off++, cy + halfH); buf.put(off++, z)
            buf.put(off++, tNorm);          buf.put(off++, 1f)
            buf.put(off++, x);              buf.put(off++, cy - halfH); buf.put(off++, z)
            buf.put(off++, tNorm);          buf.put(off++, 0f)
        }

        GLES20.glUseProgram(screenProgram)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUTexMatrix, 1, false, ui.textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ui.textureId)
        GLES20.glUniform1i(screenUTexture, 0)
        bindQuad(buf, screenAPosition, screenATexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (n + 1) * 2)
    }

    /**
     * Draw a launched activity onto a curved slot. Same idea as [drawCurvedScreen],
     * but the strip only spans the sub-arc the window's slot-UV bounds occupy, and
     * the texture sampled is the window's own ExternalOES (the activity's surface)
     * with u=[0,1] across the sub-arc. The app keeps its native pixel proportions —
     * it's bent to follow the cylinder, not stretched across the whole arc.
     */
    private fun drawCurvedWindow(window: AppWindow, slot: Screen) {
        val slotRect = Screen.worldRect(slot, desktopHalfWidth, desktopHalfHeight)
        val slotCx = slotRect[0]; val slotCy = slotRect[1]; val slotCz = slotRect[2]
        val slotPxW = slot.contentWidthPx.toFloat()
        val slotPxH = slot.contentHeightPx.toFloat()
        val act = window.activityRect()  // [x, y, w, h] in slot pixel coords
        val u0 = (act[0] / slotPxW).coerceIn(0f, 1f)
        val v0 = (act[1] / slotPxH).coerceIn(0f, 1f)
        val u1 = ((act[0] + act[2]) / slotPxW).coerceIn(0f, 1f)
        val v1 = ((act[1] + act[3]) / slotPxH).coerceIn(0f, 1f)
        if (u1 <= u0 || v1 <= v0) return

        // Slot cylinder geometry (must match drawCurvedScreen).
        val arcRad = Math.toRadians(slot.curveDeg.toDouble()).toFloat()
        val radius = -slotCz
        val arcLength = radius * arcRad
        val slotAspect = slotPxW / slotPxH
        val slotHalfH = arcLength / slotAspect / 2f

        val theta0 = (u0 - 0.5f) * arcRad
        val theta1 = (u1 - 0.5f) * arcRad
        // y: v=0 is top of slot (cy + slotHalfH), v=1 is bottom (cy - slotHalfH).
        val yTop = slotCy + slotHalfH * (1f - 2f * v0)
        val yBot = slotCy + slotHalfH * (1f - 2f * v1)

        // Segment density proportional to the arc covered — keeps tessellation
        // sane on a small window without overshooting on a wide one.
        val n = (CURVED_SEGMENTS.toFloat() * (u1 - u0)).toInt().coerceIn(2, CURVED_SEGMENTS)
        val buf = curvedMeshBuffer(n + 1)
        var off = 0
        for (i in 0..n) {
            val tNorm = i.toFloat() / n
            val theta = theta0 + tNorm * (theta1 - theta0)
            val sin = kotlin.math.sin(theta)
            val cos = kotlin.math.cos(theta)
            val x = slotCx + radius * sin
            val z = slotCz * cos
            // window texture u=0..1 across the sub-arc; v top=1, bottom=0.
            buf.put(off++, x);    buf.put(off++, yTop); buf.put(off++, z)
            buf.put(off++, tNorm); buf.put(off++, 1f)
            buf.put(off++, x);    buf.put(off++, yBot); buf.put(off++, z)
            buf.put(off++, tNorm); buf.put(off++, 0f)
        }

        GLES20.glUseProgram(screenProgram)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUTexMatrix, 1, false, window.ui.textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, window.ui.textureId)
        GLES20.glUniform1i(screenUTexture, 0)
        bindQuad(buf, screenAPosition, screenATexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (n + 1) * 2)
    }

    /** Reusable FloatBuffer for the curve mesh — resized lazily; avoids per-frame alloc. */
    private var curvedMeshBuf: FloatBuffer? = null
    private fun curvedMeshBuffer(columns: Int): FloatBuffer {
        val needed = columns * 2 * FLOATS_PER_VERTEX
        var b = curvedMeshBuf
        if (b == null || b.capacity() < needed) {
            b = ByteBuffer.allocateDirect(needed * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            curvedMeshBuf = b
        }
        b.position(0)
        return b
    }

    private fun drawExternalQuad(textureId: Int, textureMatrix: FloatArray) {
        GLES20.glUniformMatrix4fv(screenUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(screenUTexMatrix, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(screenUTexture, 0)
        bindQuad(screenQuad, screenAPosition, screenATexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
    }

    /** Run window-control actions queued from the main thread. GL thread only. */
    private fun drainGlTasks() {
        var task = glTasks.poll()
        while (task != null) {
            task()
            task = glTasks.poll()
        }
    }

    /** Realise every queued app launch onto its screen's display. GL thread only. */
    private fun drainPendingApps() {
        var request = pendingApps.poll()
        while (request != null) {
            addWindow(request)
            request = pendingApps.poll()
        }
    }

    private fun addWindow(request: AppRequest) {
        // Per-app windowed model: each launch creates its *own* bare trusted
        // VirtualDisplay (no Presentation attached) — that's what suppresses Samsung
        // One UI's transient KEYGUARD_DIALOG window on the activity's display and lets
        // injected touches reach the activity. The activity's display is sampled by
        // the renderer as a quad drawn *inside* the host slot's quad; the slot
        // continues to own its own Presentation (wallpaper / taskbar / chrome /
        // drawer / settings) on a separate trusted display.
        val hint = request.screenIdx
        val targetIdx = hint?.coerceIn(0, layout.screens.size - 1)
            ?: screenIndexUnderCursor()
            ?: 0
        Log.i(
            "UxSpace/Launch",
            "4) addWindow GL pkg=${request.packageName} hint=$hint pickedSlot=$targetIdx",
        )
        if (WorkspaceController.appLauncher == null) {
            Log.w(TAG, "addWindow: no app launcher wired — dropping launch of ${request.packageName}")
            return
        }
        if (WorkspaceController.createVirtualDisplay == null) {
            Log.w(TAG, "addWindow: no privileged display creator wired — dropping launch")
            return
        }
        // Up to [WorkspaceController.maxWindowsPerSlot] windows can stack on a
        // single slot. The list order is insertion-order = z-order (last = top).
        // When the count would exceed the cap, close the oldest non-minimised
        // window on the target slot to make room. Same package re-launching is
        // still treated as "displace the existing instance" — re-using the
        // same bare display would require a relaunch on it anyway.
        val cap = WorkspaceController.maxWindowsPerSlot.coerceAtLeast(1)
        val existing = runningWindows.filter { it.slotIdx == targetIdx }
        val samePackage = existing.firstOrNull { it.packageName == request.packageName }
        val toDisplace = when {
            samePackage != null -> samePackage
            existing.size >= cap -> existing.firstOrNull { !it.minimized } ?: existing.first()
            else -> null
        }
        if (toDisplace != null) {
            Log.i(
                TAG,
                "addWindow: displacing ${toDisplace.packageName} on slot $targetIdx " +
                    "(cap=$cap, existing=${existing.size}, samePkg=${samePackage != null})",
            )
            closeAndNotify(toDisplace.packageName)
            toDisplace.release()
            runningWindows.remove(toDisplace)
        }
        // Record the screen assignment up-front so a layout switch fired before the
        // launch completes still saves the right app.
        currentScreenApps[targetIdx] = request.copy(screenIdx = targetIdx)
        // Compute the window's outer bounds (centered, smaller than the slot) and
        // build the bare UiScreen sized to its *activity* area — the activity then
        // renders fullscreen on a display that already excludes the chrome strip,
        // so the activity's frames map 1:1 onto the activity quad with no
        // letterboxing.
        val slotScreen = layout.screens[targetIdx]
        val outer = AppWindow.computeOuterBounds(
            slotScreen.contentWidthPx, slotScreen.contentHeightPx, WINDOW_TASKBAR_PX,
        )
        val activityW = outer[2]
        val activityH = (outer[3] - WINDOW_CHROME_PX).coerceAtLeast(AppWindow.MIN_USABLE_HEIGHT_PX)
        val ui = UiScreen(
            createExternalTexture(),
            activityW, activityH,
            mainHandler,
            "uxspace-app-${nextWindowId++}",
        )
        val window = AppWindow(
            ui = ui,
            packageName = request.packageName,
            activityName = request.activityName,
            label = request.label,
            slotIdx = targetIdx,
            chromePx = WINDOW_CHROME_PX,
        )
        window.xPx = outer[0]
        window.yPx = outer[1]
        window.widthPx = outer[2]
        window.heightPx = outer[3]
        runningWindows.add(window)
        notifyWindowBoundsForSlot(targetIdx)
        // Bring the bare trusted display up on the main thread, then launch the
        // activity onto it once its display id is assigned. The bare path retries
        // internally until the privileged helper is READY.
        mainHandler.post {
            window.ui.startTrustedBare()
            launchOnWindowWhenReady(window, attempt = 0)
        }
    }

    /**
     * Once the window's bare trusted display has a display id, register it as a
     * UxSpace display, fire the app-launched listener so the slot's taskbar adds an
     * icon, and `am start` the activity onto it. Retries until the display arrives
     * or the budget runs out — the bare display creation is async (helper bootstrap
     * over wireless-debugging ADB can take a second on a cold start).
     */
    private fun launchOnWindowWhenReady(window: AppWindow, attempt: Int) {
        val displayId = window.displayId
        if (displayId != null) {
            WorkspaceController.registerUxSpaceDisplay(displayId)
            Log.i(
                "UxSpace/Launch",
                "6) registered window display=$displayId pkg=${window.packageName}",
            )
            WorkspaceController.appLauncher?.invoke(
                displayId, window.packageName, window.activityName,
            )
            WorkspaceController.notifyAppLaunchedOnScreen(
                window.packageName, window.label, window.slotIdx,
            )
            Log.i(
                "UxSpace/Launch",
                "10) notifyAppLaunchedOnScreen pkg=${window.packageName} slot=${window.slotIdx}",
            )
            return
        }
        if (attempt >= LAUNCH_DISPLAY_WAIT_ATTEMPTS) {
            Log.e(TAG, "addWindow: bare display never came up for ${window.packageName}")
            return
        }
        mainHandler.postDelayed(
            { launchOnWindowWhenReady(window, attempt + 1) },
            LAUNCH_DISPLAY_WAIT_MS,
        )
    }

    /**
     * Move an app currently launched on some screen of the active layout to the next screen
     * (cyclic). Closes the app on the source screen via the wired `closeApp` hook (which
     * fires the closed listener for taskbar cleanup) and re-queues it onto the next screen
     * via `pendingApps` so the normal addWindow path handles the launch + bookkeeping.
     */
    fun moveAppToNextScreen(packageName: String) {
        glTasks.add {
            val n = layout.screens.size
            if (n < 2) {
                Log.i(TAG, "moveAppToNextScreen: layout has only $n screen(s) — nothing to do")
                return@add
            }
            val window = runningWindows.firstOrNull { it.packageName == packageName }
                ?: run {
                    Log.w(TAG, "moveAppToNextScreen: $packageName has no window")
                    return@add
                }
            moveWindowToSlot(window, (window.slotIdx + 1) % n)
        }
    }

    /**
     * Re-target [window] onto [dstSlot]. If the destination already has a non-
     * minimised window, close the oldest one (first in `runningWindows`) so the
     * mover can take its place — slot capacity is one window for now.
     *
     * The activity itself stays on its bare trusted display — only [window.slotIdx]
     * and the renderer's slot-quad routing change. Per-(layout, slot) launch
     * records and taskbar icons are kept in sync so a later layout cycle restores
     * the app onto its current slot. No-op if [window.slotIdx] is already [dstSlot].
     *
     * GL-thread only.
     */
    private fun moveWindowToSlot(window: AppWindow, dstSlot: Int) {
        if (window.slotIdx == dstSlot) return
        val srcSlot = window.slotIdx
        // Capacity check — only displace when the move would push the dst over
        // [WorkspaceController.maxWindowsPerSlot]. The mover itself isn't yet
        // on dst (we're about to move it), so the post-move count is
        // existingOnDst + 1.
        val cap = WorkspaceController.maxWindowsPerSlot.coerceAtLeast(1)
        val existingOnDst = runningWindows.filter { it !== window && it.slotIdx == dstSlot }
        val displaced = if (existingOnDst.size >= cap) {
            existingOnDst.firstOrNull { !it.minimized } ?: existingOnDst.first()
        } else null
        if (displaced != null) {
            val displacedPkg = displaced.packageName
            currentScreenApps.entries
                .firstOrNull { it.value.packageName == displacedPkg }
                ?.let { currentScreenApps.remove(it.key) }
            runningWindows.remove(displaced)
            displaced.release()
            closeAndNotify(displacedPkg)
            Log.i(
                TAG,
                "moveWindowToSlot: dst slot $dstSlot at cap $cap — closed $displacedPkg",
            )
        }
        // Aspect check: the bare display's pixel dims are frozen at launch. If
        // the destination slot's activity area aspect differs from the launch
        // aspect by more than RESPAWN_ASPECT_TOLERANCE, sampling the existing
        // bare-display texture into the new activity quad produces a visibly
        // stretched / squashed app (V→H or H→V crossing in VHV / SBS layouts).
        // Reinit: close the activity and queue a fresh launch on the dst slot
        // so the bare display comes up at the right dims. State is lost — the
        // alternative is keeping the activity but rendering it distorted.
        val dst = layout.screens[dstSlot]
        val dstOuter = AppWindow.computeOuterBounds(
            dst.contentWidthPx, dst.contentHeightPx, WINDOW_TASKBAR_PX,
        )
        val dstActivityW = dstOuter[2]
        val dstActivityH = (dstOuter[3] - WINDOW_CHROME_PX)
            .coerceAtLeast(AppWindow.MIN_USABLE_HEIGHT_PX)
        val srcAspect = window.ui.width.toFloat() / window.ui.height.toFloat()
        val dstAspect = dstActivityW.toFloat() / dstActivityH.toFloat()
        val aspectChanged = kotlin.math.abs(srcAspect - dstAspect) /
            kotlin.math.max(srcAspect, dstAspect) > RESPAWN_ASPECT_TOLERANCE
        if (aspectChanged) {
            val pkg = window.packageName
            val activityName = window.activityName
            val label = window.label
            currentScreenApps.entries
                .firstOrNull { it.value.packageName == pkg }
                ?.let { currentScreenApps.remove(it.key) }
            runningWindows.remove(window)
            window.release()
            closeAndNotify(pkg)
            pendingApps.add(AppRequest(pkg, activityName, label, screenIdx = dstSlot))
            notifyWindowBoundsForSlot(srcSlot)
            Log.i(
                TAG,
                "moveWindowToSlot: reinit $pkg srcAspect=${"%.2f".format(srcAspect)} " +
                    "dstAspect=${"%.2f".format(dstAspect)} → relaunch on slot $dstSlot",
            )
            return
        }
        window.slotIdx = dstSlot
        window.bind(dst.contentWidthPx, dst.contentHeightPx, WINDOW_TASKBAR_PX)
        // The mover becomes the topmost window on its new slot — move it to
        // the end of [runningWindows] so its z-order matches.
        if (runningWindows.lastOrNull() !== window) {
            runningWindows.remove(window)
            runningWindows.add(window)
        }
        val req = currentScreenApps.remove(srcSlot)
        if (req != null) currentScreenApps[dstSlot] = req.copy(screenIdx = dstSlot)
        val pkg = window.packageName
        val label = window.label
        mainHandler.post {
            WorkspaceController.notifyAppClosed(pkg)
            WorkspaceController.notifyAppLaunchedOnScreen(pkg, label, dstSlot)
        }
        notifyWindowBoundsForSlot(srcSlot)
        notifyWindowBoundsForSlot(dstSlot)
        Log.i(TAG, "moveWindowToSlot: $pkg slot $srcSlot -> $dstSlot")
    }

    /**
     * Slot index whose world rectangle (after the current view's zoom/anchor + per-screen
     * yaw) contains the cursor, or null if the cursor misses every screen. Uses the same
     * ray-plane intersection as [cursorToRectPx] so tilted side screens in SBS / V-H-V
     * are hit correctly.
     */
    private fun screenIndexUnderCursor(): Int? {
        layout.screens.forEachIndexed { i, screen ->
            if (cursorToRectPx(screen) != null) return i
        }
        return null
    }

    /**
     * Windows hidden by the last [toggleShowDesktop], so the next press restores exactly
     * those. GL-thread only.
     */
    private val hiddenByShowDesktop = mutableSetOf<String>()

    /**
     * Show-desktop toggle (taskbar Home, Ctrl+Alt+D), as on DeX: if any window is
     * visible, minimise every visible window; otherwise restore the ones the previous
     * press hid. Apps keep running either way. Any thread.
     */
    fun toggleShowDesktop() {
        glTasks.add {
            val visible = runningWindows.filter { !it.minimized }
            if (visible.isNotEmpty()) {
                hiddenByShowDesktop.clear()
                visible.forEach { window ->
                    window.minimized = true
                    hiddenByShowDesktop.add(window.packageName)
                    window.displayId?.let { id -> WorkspaceController.appMediaPause?.invoke(id) }
                }
                visible.map { it.slotIdx }.toSet().forEach { notifyWindowBoundsForSlot(it) }
            } else {
                val restored = runningWindows.filter { it.packageName in hiddenByShowDesktop }
                hiddenByShowDesktop.clear()
                restored.forEach { it.minimized = false }
                restored.map { it.slotIdx }.toSet().forEach { notifyWindowBoundsForSlot(it) }
            }
        }
    }

    /**
     * Apply a keyboard window action to the focused window: the last visible window in
     * [runningWindows], i.e. the most recently raised on any screen. Any thread.
     */
    fun windowCommand(command: WorkspaceController.WindowCommand) {
        glTasks.add {
            val window = runningWindows.lastOrNull { !it.minimized } ?: return@add
            val pkg = window.packageName
            val slot = layout.screens.getOrNull(window.slotIdx) ?: return@add
            fun setMode(mode: WorkspaceController.WindowMode) {
                window.setState(mode, slot.contentWidthPx, slot.contentHeightPx, WINDOW_TASKBAR_PX)
                notifyWindowBoundsForSlot(window.slotIdx)
            }
            val normal = WorkspaceController.WindowMode.NORMAL
            when (command) {
                WorkspaceController.WindowCommand.TILE_LEFT -> setMode(
                    if (window.mode == WorkspaceController.WindowMode.TILED_LEFT) normal
                    else WorkspaceController.WindowMode.TILED_LEFT,
                )
                WorkspaceController.WindowCommand.TILE_RIGHT -> setMode(
                    if (window.mode == WorkspaceController.WindowMode.TILED_RIGHT) normal
                    else WorkspaceController.WindowMode.TILED_RIGHT,
                )
                WorkspaceController.WindowCommand.MAXIMIZE ->
                    if (window.mode != WorkspaceController.WindowMode.FULLSCREEN) {
                        setMode(WorkspaceController.WindowMode.MAXIMIZED)
                    }
                WorkspaceController.WindowCommand.RESTORE_DOWN ->
                    if (window.mode == normal) minimizeWindow(pkg) else setMode(normal)
                WorkspaceController.WindowCommand.MOVE_PREV_SCREEN,
                WorkspaceController.WindowCommand.MOVE_NEXT_SCREEN -> {
                    val n = layout.screens.size
                    if (n < 2) return@add
                    val step = if (command == WorkspaceController.WindowCommand.MOVE_NEXT_SCREEN) 1 else -1
                    moveWindowToSlot(window, (window.slotIdx + step + n) % n)
                }
                WorkspaceController.WindowCommand.CLOSE ->
                    mainHandler.post { WorkspaceController.closeAppByPackage(pkg) }
            }
            Log.i(TAG, "windowCommand $command -> $pkg mode=${window.mode}")
        }
    }

    /** Open the drawer on the screen under the cursor, or screen 0 if none. Any thread. */
    fun openDrawerUnderCursor() {
        glTasks.add {
            val screenIdx = screenIndexUnderCursor() ?: 0
            mainHandler.post {
                WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
                WorkspaceController.setDrawerOpen(true, screenIdx)
            }
        }
    }

    /**
     * Bring the named app to the foreground of whichever screen it's currently on. For
     * a minimised window this just clears the flag — the activity has been running the
     * whole time and only the renderer was suppressing its quad. For a non-minimised
     * window we re-queue the launch so Android refreshes the activity stack on the
     * display (taskbar-icon tap on a window that's already visible just bounces).
     * No-op if the app isn't tracked as on any screen. Any thread.
     */
    fun focusApp(packageName: String) {
        glTasks.add {
            val window = runningWindows.lastOrNull { it.packageName == packageName }
            if (window != null) {
                // Minimised → restore (and raise). Visible-but-covered → raise.
                // Visible-and-already-top → no-op. bringWindowToFront does
                // nothing when the window is already the topmost on its slot.
                if (window.minimized) {
                    window.minimized = false
                }
                bringWindowToFront(window)
                if (window.minimized.not()) {
                    // bringWindowToFront republishes bounds on raise; if the
                    // window was already top before the un-minimise, we still
                    // need a publish to flip its visibility back on.
                    notifyWindowBoundsForSlot(window.slotIdx)
                }
                return@add
            }
            // No live window — taskbar still has an icon for this app, which only
            // happens momentarily while a launch is in flight. Re-queue so the
            // user's tap isn't dropped.
            val entry = currentScreenApps.entries.firstOrNull { it.value.packageName == packageName }
                ?: return@add
            pendingApps.add(entry.value.copy(screenIdx = entry.key))
        }
    }

    /**
     * Minimise the named app's window. Activity keeps rendering into its bare display
     * off-screen — restoring is a state flip, not a relaunch.
     */
    fun minimizeWindow(packageName: String) {
        glTasks.add {
            val window = runningWindows.firstOrNull { it.packageName == packageName }
                ?: return@add
            if (window.minimized) return@add
            window.minimized = true
            // notifyWindowBoundsForSlot now treats minimised as "no window" and
            // pushes null bounds, which the slot's Presentation reads as "hide
            // chrome." The activity keeps producing frames into its bare display.
            notifyWindowBoundsForSlot(window.slotIdx)
            // Pause any media playback on the minimised activity — otherwise
            // the activity remains visible to the OS (its bare display stays
            // ON) and apps like YouTube keep audio running off-screen.
            window.displayId?.let { id -> WorkspaceController.appMediaPause?.invoke(id) }
            Log.i(TAG, "minimizeWindow: $packageName slot=${window.slotIdx}")
        }
    }

    /**
     * Cycle the named app's window through NORMAL → MAXIMIZED → FULLSCREEN → NORMAL.
     * Re-binds the window's outer rectangle from the new mode and notifies the slot's
     * chrome so its Maximize button can swap to the right icon.
     */
    fun toggleMaximizeWindow(packageName: String) {
        glTasks.add {
            val window = runningWindows.firstOrNull { it.packageName == packageName }
                ?: return@add
            val nextMode = when (window.mode) {
                WorkspaceController.WindowMode.NORMAL,
                WorkspaceController.WindowMode.TILED_LEFT,
                WorkspaceController.WindowMode.TILED_RIGHT ->
                    WorkspaceController.WindowMode.MAXIMIZED
                WorkspaceController.WindowMode.MAXIMIZED -> WorkspaceController.WindowMode.FULLSCREEN
                WorkspaceController.WindowMode.FULLSCREEN -> WorkspaceController.WindowMode.NORMAL
            }
            val slot = layout.screens.getOrNull(window.slotIdx) ?: return@add
            window.setState(nextMode, slot.contentWidthPx, slot.contentHeightPx, WINDOW_TASKBAR_PX)
            notifyWindowBoundsForSlot(window.slotIdx)
            Log.i(TAG, "toggleMaximizeWindow: $packageName -> mode=${window.mode}")
        }
    }

    /**
     * Inject `KEYCODE_BACK` into the named app's display via the wired [appBack]
     * lambda. Looks the window up by package; no-op if the app isn't running.
     */
    fun dispatchBackToWindow(packageName: String, sendBack: (displayId: Int) -> Unit) {
        glTasks.add {
            val window = runningWindows.firstOrNull { it.packageName == packageName }
                ?: return@add
            val id = window.displayId ?: return@add
            sendBack(id)
        }
    }

    /**
     * Push the slot's current window bounds (or null when no window) to the
     * controller, which fans out to the per-slot [com.uxspace.desktop.DesktopPresentation]
     * so it can position its chrome view at the window's chrome rect.
     */
    private fun notifyWindowBoundsForSlot(slotIdx: Int) {
        // Per-window publish — every non-minimised window on the slot gets a
        // bounds entry in z-order (insertion order in [runningWindows]). The
        // last entry is the topmost = focused window. The slot Presentation
        // uses each entry's packageName as identity to position the matching
        // chrome + frame views; missing packages get their views removed.
        val slot = layout.screens.getOrNull(slotIdx)
        val windows = runningWindows.filter { it.slotIdx == slotIdx && !it.minimized }
        val boundsList = if (slot == null || windows.isEmpty()) emptyList()
        else {
            val lastIdx = windows.lastIndex
            windows.mapIndexed { i, w ->
                val cr = w.chromeOverlayRect(slot.contentWidthPx)
                WorkspaceController.WindowBounds(
                    packageName = w.packageName,
                    xPx = w.xPx, yPx = w.yPx,
                    widthPx = w.widthPx, heightPx = w.heightPx,
                    chromePx = w.chromePx, mode = w.mode,
                    chromeBoundsX = cr[0], chromeBoundsY = cr[1],
                    chromeBoundsW = cr[2], chromeBoundsH = cr[3],
                    focused = i == lastIdx,
                )
            }
        }
        mainHandler.post {
            WorkspaceController.notifyWindowBoundsChanged(slotIdx, boundsList)
        }
    }

    /**
     * Draw a sub-region of the slot's Presentation surface (the modal panel rectangle)
     * over the activity quad. The slot quad already contains the modal (drawer or
     * settings — both are views inside the same Presentation), so we re-sample that
     * sub-rectangle of the texture and project it to the same world rectangle inside
     * the slot's quad. No second virtual display needed.
     *
     * The composed texture matrix is `slotUi.textureMatrix · S`, where `S` scales
     * vertex UVs `[0,1]` to `[u0,u1] × [v0,v1]`. SurfaceTexture's own textureMatrix is
     * applied on top of S so the OES Y-flip / rotation it carries still works.
     */
    private val modalSubM = FloatArray(16)
    private val modalComposedTexM = FloatArray(16)

    private fun drawSlotModalOverlay(
        slot: Screen,
        slotUi: UiScreen,
        modal: WorkspaceController.ModalBounds,
    ) {
        val slotRect = Screen.worldRect(slot, desktopHalfWidth, desktopHalfHeight)
        val slotCx = slotRect[0]; val slotCy = slotRect[1]; val slotCz = slotRect[2]
        val slotW = slotRect[3];  val slotH = slotRect[4]
        val slotPxW = slot.contentWidthPx.toFloat()
        val slotPxH = slot.contentHeightPx.toFloat()
        val u0 = modal.xPx / slotPxW
        val v0 = modal.yPx / slotPxH
        val u1 = (modal.xPx + modal.widthPx) / slotPxW
        val v1 = (modal.yPx + modal.heightPx) / slotPxH
        val mW = slotW * (u1 - u0)
        val mH = slotH * (v1 - v0)
        val cu = (u0 + u1) / 2f
        val cv = (v0 + v1) / 2f
        val mCx = slotCx + (cu - 0.5f) * slotW
        val mCy = slotCy + (0.5f - cv) * slotH  // V flips Y.
        buildModelRectYawed(modelMatrix, mCx, mCy, slotCz, mW, mH, slot.yawDeg)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        Matrix.setIdentityM(modalSubM, 0)
        // Texture V is inverted relative to pixel-Y — the slot's SurfaceTexture
        // carries a vertical flip in textureMatrix — so the sampled sub-rect's V
        // range must be mirrored: a strip at pixel rows [v0,v1] (v0 = top) maps to
        // quad-UV [1-v1, 1-v0]. Vertically-centred modals (drawer / settings /
        // audio) are unaffected because their range is symmetric about 0.5; the
        // top-pinned FULLSCREEN chrome toolbar previously sampled the *bottom* of
        // the slot (taskbar / wallpaper) and painted it over the app's top-left.
        Matrix.translateM(modalSubM, 0, u0, 1f - v1, 0f)
        Matrix.scaleM(modalSubM, 0, u1 - u0, v1 - v0, 1f)
        Matrix.multiplyMM(modalComposedTexM, 0, slotUi.textureMatrix, 0, modalSubM, 0)
        drawExternalQuad(slotUi.textureId, modalComposedTexM)
    }

    /**
     * Unit-circle triangle-fan: a centre vertex plus [segments]+1 perimeter
     * vertices at radius 1 in the XY plane (z=0). Scaled to the desired world
     * size by [buildModelRectYawed] (which halves w/h), so passing diameter =
     * 2·radius gives a circle of that diameter.
     */
    private fun buildCircleFanVertices(segments: Int): FloatArray {
        val verts = FloatArray((segments + 2) * 3)
        var i = 0
        verts[i++] = 0f; verts[i++] = 0f; verts[i++] = 0f
        for (s in 0..segments) {
            val a = (s.toDouble() / segments) * 2.0 * Math.PI
            verts[i++] = Math.cos(a).toFloat()
            verts[i++] = Math.sin(a).toFloat()
            verts[i++] = 0f
        }
        return verts
    }

    /**
     * Draw the FULLSCREEN toolbar's reveal hint — a semi-opaque circle on the
     * slot quad, centred on the (hidden) toolbar's rect so it sits exactly where
     * the toolbar appears on hover. [centerPxX]/[centerPxY] and [diameterPx] are
     * slot-local pixels; the circle is alpha-blended over the activity quad.
     */
    private fun drawSlotCircleHint(
        slot: Screen,
        centerPxX: Float,
        centerPxY: Float,
        diameterPx: Float,
    ) {
        val slotRect = Screen.worldRect(slot, desktopHalfWidth, desktopHalfHeight)
        val slotCx = slotRect[0]; val slotCy = slotRect[1]; val slotCz = slotRect[2]
        val slotW = slotRect[3];  val slotH = slotRect[4]
        val slotPxW = slot.contentWidthPx.toFloat()
        val slotPxH = slot.contentHeightPx.toFloat()
        val cx = slotCx + (centerPxX / slotPxW - 0.5f) * slotW
        val cy = slotCy + (0.5f - centerPxY / slotPxH) * slotH  // V flips Y.
        val dW = slotW * (diameterPx / slotPxW)
        val dH = slotH * (diameterPx / slotPxH)
        buildModelRectYawed(modelMatrix, cx, cy, slotCz, dW, dH, slot.yawDeg)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(solidProgram)
        circleFan.position(0)
        GLES20.glVertexAttribPointer(solidAPosition, 3, GLES20.GL_FLOAT, false, 0, circleFan)
        GLES20.glEnableVertexAttribArray(solidAPosition)
        GLES20.glUniformMatrix4fv(solidUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(
            solidUColor, CIRCLE_HINT_R, CIRCLE_HINT_G, CIRCLE_HINT_B, CIRCLE_HINT_A,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, CIRCLE_HINT_SEGMENTS + 2)
        GLES20.glDisableVertexAttribArray(solidAPosition)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Convert a window's **activity rect** (outer bounds minus the chrome strip at
     * the top) into a world-space `[cx, cy, cz, w, h]` inside the slot's quad. The
     * slot's quad spans `[slotCx ± slotW/2, slotCy ± slotH/2]` at world Z `slotCz`;
     * the activity rect in slot pixel coords (origin top-left) maps to that range —
     * Y is flipped because slot pixel V=0 is the top while world Y grows upward.
     *
     * The chrome strip itself isn't drawn by the renderer — it lives in the slot's
     * Presentation view tree, positioned at the chrome rect by the controller bounds
     * listener, and appears in the slot's surface texture sampled by the slot quad.
     */
    private fun computeWindowWorldRect(window: AppWindow, slot: Screen): FloatArray {
        val slotRect = Screen.worldRect(slot, desktopHalfWidth, desktopHalfHeight)
        val slotCx = slotRect[0]; val slotCy = slotRect[1]; val slotCz = slotRect[2]
        val slotW = slotRect[3];  val slotH = slotRect[4]
        val slotPxW = slot.contentWidthPx.toFloat()
        val slotPxH = slot.contentHeightPx.toFloat()
        val act = window.activityRect()  // [x, y, w, h] of the activity area
        val u0 = act[0] / slotPxW
        val v0 = act[1] / slotPxH
        val u1 = (act[0] + act[2]) / slotPxW
        val v1 = (act[1] + act[3]) / slotPxH
        val winW = slotW * (u1 - u0)
        val winH = slotH * (v1 - v0)
        val centerU = (u0 + u1) / 2f
        val centerV = (v0 + v1) / 2f
        val winCx = slotCx + (centerU - 0.5f) * slotW
        val winCy = slotCy + (0.5f - centerV) * slotH  // V flips Y.
        return floatArrayOf(winCx, winCy, slotCz, winW, winH)
    }

    /** Force-stop the named app and drop it from its screen's tracking. Any thread. */
    fun closeAppByPackage(packageName: String) {
        glTasks.add {
            val entry = currentScreenApps.entries.firstOrNull { it.value.packageName == packageName }
            if (entry != null) {
                currentScreenApps.remove(entry.key)
            }
            // Release the window's bare trusted display so the activity's surfaces are
            // freed; the system tears down the task once the display is gone.
            val window = runningWindows.firstOrNull { it.packageName == packageName }
            if (window != null) {
                val slotIdx = window.slotIdx
                runningWindows.remove(window)
                window.release()
                notifyWindowBoundsForSlot(slotIdx)
            }
            closeAndNotify(packageName)
        }
    }

    /**
     * Draw the touchpad cursor — a flat arrow overlay on top of everything. The hand /
     * grab sprite that used to appear during a window-drag is gone with the per-app
     * window model; the new per-screen world has no draggable window frames.
     */
    private fun drawCursor() {
        // Hide the cursor after the configured idle timeout of no touchpad input — any
        // move / scroll / click / drag bumps lastInputAtMs and brings it back.
        if (android.os.SystemClock.uptimeMillis() - lastInputAtMs > WorkspaceController.cursorIdleTimeoutMs) {
            return
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        // While a drag-from-drawer is armed, the cursor carries the app's icon as a
        // semi-transparent ghost — the arrow itself stays the regular pointer so the
        // user still has a precise drop target.
        WorkspaceController.armedDrawerDrag?.let { drawArmedDragGhost(it) }

        // Resize-cursor mode: if we're already resizing, the cursor follows the
        // active edge. Otherwise, when not dragging and the cursor hovers a
        // resizable edge of a NORMAL window, draw the matching directional icon.
        val edge = resizeWindow?.let { resizeEdge } ?: computeHoverResizeEdge()
        if (edge != null) {
            drawResizeCursorIcon(edge)
            return
        }

        val flashing = cursorFlashFrames > 0
        if (flashing) cursorFlashFrames--
        GLES20.glUseProgram(cursorProgram)
        cursorArrow.position(0)
        GLES20.glVertexAttribPointer(
            cursorAPosition, 2, GLES20.GL_FLOAT, false, ARROW_STRIDE_BYTES, cursorArrow,
        )
        GLES20.glEnableVertexAttribArray(cursorAPosition)
        val outline = CURSOR_SCALE * 0.07f
        GLES20.glUniform2f(cursorUCenter, cursorX - outline, cursorY + outline)
        GLES20.glUniform2f(
            cursorUHalfSize, CURSOR_SCALE * 1.18f, CURSOR_SCALE * 1.18f * surfaceAspect,
        )
        GLES20.glUniform4f(cursorUColor, 0.04f, 0.04f, 0.07f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CURSOR_ARROW_VERTEX_COUNT)

        GLES20.glUniform2f(cursorUCenter, cursorX, cursorY)
        GLES20.glUniform2f(cursorUHalfSize, CURSOR_SCALE, CURSOR_SCALE * surfaceAspect)
        if (flashing) {
            GLES20.glUniform4f(cursorUColor, 0.31f, 0.76f, 0.97f, 1f)
        } else {
            GLES20.glUniform4f(cursorUColor, 1f, 1f, 1f, 1f)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CURSOR_ARROW_VERTEX_COUNT)
    }

    /**
     * If the cursor is currently hovering an outer edge / corner of a NORMAL
     * window (and no drag / resize is in flight), return the matching
     * [ResizeEdge]. Otherwise null. Mirrors [tryBeginWindowInteraction]'s
     * resize hit-test exactly so the cursor's hint matches the action.
     */
    private fun computeHoverResizeEdge(): ResizeEdge? {
        if (dragWindow != null || resizeWindow != null) return null
        val screenIdx = screenIndexUnderCursor() ?: return null
        val screen = layout.screens.getOrNull(screenIdx) ?: return null
        val px = cursorToRectPx(screen) ?: return null
        val candidate = runningWindows.firstOrNull { w ->
            !w.minimized &&
                w.slotIdx == screenIdx &&
                w.mode == WorkspaceController.WindowMode.NORMAL &&
                inResizeBand(w, px[0], px[1])
        } ?: return null
        return resizeEdgeAt(candidate, px[0], px[1])
    }

    /**
     * The currently-uploaded ghost-icon texture and the bitmap it was uploaded from.
     * When the armed drag changes, we delete the previous texture and re-upload from
     * the new bitmap; only one ghost can be in flight at a time, so a single slot is
     * enough. GL-thread only.
     */
    private var ghostTextureId: Int = 0
    private var ghostSourceBitmap: android.graphics.Bitmap? = null

    private fun drawArmedDragGhost(drag: WorkspaceController.ArmedDrag) {
        // (Re)upload the icon texture when the carried app changes — identity by
        // reference is enough because [armDrawerDrag] always creates a fresh ArmedDrag.
        if (ghostSourceBitmap !== drag.iconBitmap) {
            if (ghostTextureId != 0) {
                val arr = intArrayOf(ghostTextureId)
                GLES20.glDeleteTextures(1, arr, 0)
                ghostTextureId = 0
            }
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            ghostTextureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ghostTextureId)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, drag.iconBitmap, 0)
            ghostSourceBitmap = drag.iconBitmap
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(hudProgram)
        hudQuad.position(0)
        GLES20.glVertexAttribPointer(
            hudAPosition, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        hudQuad.position(2)
        GLES20.glVertexAttribPointer(
            hudATexCoord, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        GLES20.glEnableVertexAttribArray(hudAPosition)
        GLES20.glEnableVertexAttribArray(hudATexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ghostTextureId)
        GLES20.glUniform1i(hudUTexture, 0)
        val halfW = CURSOR_SCALE * 2.0f
        val halfH = halfW * surfaceAspect
        // Offset the ghost slightly below + right of the cursor tip so the arrow
        // is unobstructed and the icon clearly reads as "carried by" the cursor.
        val offset = CURSOR_SCALE * 0.9f
        GLES20.glUniform2f(hudUCenter, cursorX + offset, cursorY - offset * surfaceAspect)
        GLES20.glUniform2f(hudUHalfSize, halfW, halfH)
        GLES20.glUniform4f(hudUColor, 1f, 1f, 1f, 0.85f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(hudAPosition)
        GLES20.glDisableVertexAttribArray(hudATexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Resolve a click while a drag-from-drawer is armed. Tries to drop on the
     * cursor's screen as a desktop shortcut; if that fails (cursor over a window,
     * the taskbar, or otherwise outside the wallpaper area), the drag is cancelled.
     */
    private fun handleArmedDrop() {
        val drag = WorkspaceController.armedDrawerDrag ?: return
        val screenIdx = screenIndexUnderCursor()
        val screen = screenIdx?.let { layout.screens.getOrNull(it) }
        val px = screen?.let { cursorToRectPx(it) }
        if (screenIdx == null || screen == null || px == null) {
            Log.i(TAG, "drawer drag cancelled: cursor over no screen")
            mainHandler.post { WorkspaceController.cancelDrawerDrag() }
            return
        }
        // Dropping on a window or on the taskbar cancels — keep the wallpaper as
        // the only valid drop target so the user can place icons in clear space.
        val window = windowOnSlotAt(screenIdx, px[0], px[1])
        if (window != null) {
            Log.i(TAG, "drawer drag cancelled: cursor over window ${window.packageName}")
            mainHandler.post { WorkspaceController.cancelDrawerDrag() }
            return
        }
        if (px[1] >= screen.contentHeightPx - DROP_TASKBAR_GUARD_PX) {
            // Taskbar zone is the trash: a desktop-sourced drag dropped here deletes
            // the shortcut from its source desktop. Drawer-sourced drags just cancel —
            // there's nothing persisted to delete.
            if (drag.sourceSlotIdx != null) {
                Log.i(TAG, "desktop shortcut deleted: ${drag.packageName} (drop in taskbar zone)")
                val toDelete = drag
                mainHandler.post {
                    WorkspaceController.removeArmedShortcut?.invoke(toDelete)
                    WorkspaceController.cancelDrawerDrag()
                }
            } else {
                Log.i(TAG, "drawer drag cancelled: cursor over taskbar zone")
                mainHandler.post { WorkspaceController.cancelDrawerDrag() }
            }
            return
        }
        val xFraction = (px[0] / screen.contentWidthPx).coerceIn(0f, 1f)
        val yFraction = (px[1] / screen.contentHeightPx).coerceIn(0f, 1f)
        val slotForDrop = screenIdx
        val finalDrag = drag
        mainHandler.post {
            val placed = WorkspaceController.placeArmedDrop?.invoke(
                finalDrag, slotForDrop, xFraction, yFraction,
            ) ?: false
            if (!placed) {
                Log.i(
                    TAG,
                    "drawer drag drop rejected (likely duplicate): ${finalDrag.packageName} on slot $slotForDrop",
                )
            } else {
                Log.i(
                    TAG,
                    "drawer drag placed: ${finalDrag.packageName} on slot $slotForDrop at " +
                        "(${"%.2f".format(xFraction)},${"%.2f".format(yFraction)})",
                )
            }
            WorkspaceController.cancelDrawerDrag()
        }
    }

    private fun drawResizeCursorIcon(edge: ResizeEdge) {
        val icons = WorkspaceController.cursorIcons ?: return
        val resId = when (edge) {
            ResizeEdge.E, ResizeEdge.W -> icons.resizeEW
            ResizeEdge.S -> icons.resizeNS
            ResizeEdge.NE, ResizeEdge.SW -> icons.resizeNESW
            ResizeEdge.NW, ResizeEdge.SE -> icons.resizeNWSE
        }
        val texId = iconTextureFor(resId) ?: return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(hudProgram)
        hudQuad.position(0)
        GLES20.glVertexAttribPointer(
            hudAPosition, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        hudQuad.position(2)
        GLES20.glVertexAttribPointer(
            hudATexCoord, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        GLES20.glEnableVertexAttribArray(hudAPosition)
        GLES20.glEnableVertexAttribArray(hudATexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(hudUTexture, 0)
        // Match arrow cursor's visible size; the icon is a 24×24 vector so
        // CURSOR_SCALE gives a roughly equivalent on-screen footprint.
        val halfW = CURSOR_SCALE * 1.4f
        val halfH = halfW * surfaceAspect
        GLES20.glUniform2f(hudUCenter, cursorX, cursorY)
        GLES20.glUniform2f(hudUHalfSize, halfW, halfH)
        GLES20.glUniform4f(hudUColor, 1f, 1f, 1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(hudAPosition)
        GLES20.glDisableVertexAttribArray(hudATexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Resolve a cursor click. App windows sit in front of the desktop, so try them first:
     * a hit on the title bar dispatches a tap into the chrome's own view tree (its buttons),
     * a hit on the app content injects a tap into that app's display via Shizuku; otherwise
     * the click goes to the desktop's own One UI view tree.
     */
    private var curvedCursorDiagFrame: Int = 0
    private var flatCursorDiagFrame: Int = 0

    /**
     * Dispatch a press-and-drag to a window: edge / corner → resize, chrome
     * strip → drag. Activity-area presses are left dormant (the gesture is
     * consumed but does nothing this turn). Only NORMAL windows can resize;
     * MAXIMIZED / FULLSCREEN windows un-tile to NORMAL when the chrome is
     * grabbed (existing drag path).
     */
    private fun tryBeginWindowInteraction() {
        // See beginDrag(): the press-position cursor was snapshotted there.
        // Temporarily restore it onto the live cursor fields so the screen /
        // resize-band hit-tests below run against the press position. The
        // saved values are put back before this method returns so the cursor
        // continues to follow live motion afterwards.
        val savedCursorX = cursorX
        val savedCursorY = cursorY
        cursorX = dragRequestCursorX
        cursorY = dragRequestCursorY
        try { tryBeginWindowInteractionInner() }
        finally {
            cursorX = savedCursorX
            cursorY = savedCursorY
        }
    }

    private fun tryBeginWindowInteractionInner() {
        val screenIdx = screenIndexUnderCursor() ?: return
        val screen = layout.screens.getOrNull(screenIdx) ?: return
        val px = cursorToRectPx(screen) ?: return
        // The eye chart covers the whole view; nothing under it can be grabbed.
        if (WorkspaceController.isOptometryOpen) return
        // Press-and-hold on an open drawer item arms a drag-to-desktop. Checked
        // ahead of window resize/drag because the drawer overlays everything on
        // its slot — a press in the drawer panel can't reach the windows below.
        if (tryArmDrawerDragAt(screenIdx, px[0], px[1])) return
        // First pass: any NORMAL window with cursor near an outer edge wins
        // resize. Skip pure N (chrome handles it). Cursor can sit a few px
        // outside the outer rect (in the 3 px frame) and still latch resize
        // — feels natural to grab the visible border. With multi-window per
        // slot, iterate from the topmost (last in list) so the visible
        // window wins the edge.
        val resizeCandidate = if (!WorkspaceController.resizeHandlesEnabled) null
        else runningWindows.findLast { w ->
            !w.minimized &&
                w.slotIdx == screenIdx &&
                w.mode == WorkspaceController.WindowMode.NORMAL &&
                inResizeBand(w, px[0], px[1])
        }
        if (resizeCandidate != null) {
            val edge = resizeEdgeAt(resizeCandidate, px[0], px[1])
            if (edge != null) {
                bringWindowToFront(resizeCandidate)
                resizeWindow = resizeCandidate
                resizeEdge = edge
                dragLastCursorPx = px[0].toInt()
                dragLastCursorPy = px[1].toInt()
                Log.i(
                    TAG,
                    "resize begin: $edge pkg=${resizeCandidate.packageName} " +
                        "window=(${resizeCandidate.xPx},${resizeCandidate.yPx}," +
                        "${resizeCandidate.widthPx},${resizeCandidate.heightPx})",
                )
                return
            }
        }
        tryBeginWindowDrag(screenIdx, screen, px)
    }

    /** True if cursor is within the resize band of any of [window]'s outer edges. */
    private fun inResizeBand(window: AppWindow, px: Float, py: Float): Boolean {
        val out = RESIZE_BAND_OUT_PX
        val inEx = px >= window.xPx - out && px < window.xPx + window.widthPx + out &&
            py >= window.yPx - out && py < window.yPx + window.heightPx + out
        if (!inEx) return false
        val ib = RESIZE_BAND_IN_PX
        val left = px < window.xPx + ib
        val right = px > window.xPx + window.widthPx - ib
        val top = py < window.yPx + ib
        val bottom = py > window.yPx + window.heightPx - ib
        return left || right || top || bottom
    }

    /**
     * Decide which edge / corner the cursor is on. Pure-N (top edge alone)
     * returns null so the chrome drag wins there. NW / NE corners *do* win
     * over chrome — they sit at the very top corners where the chrome's
     * buttons aren't anyway.
     */
    private fun resizeEdgeAt(window: AppWindow, px: Float, py: Float): ResizeEdge? {
        val ib = RESIZE_BAND_IN_PX
        val left = px < window.xPx + ib
        val right = px > window.xPx + window.widthPx - ib
        val top = py < window.yPx + ib
        val bottom = py > window.yPx + window.heightPx - ib
        return when {
            top && left -> ResizeEdge.NW
            top && right -> ResizeEdge.NE
            bottom && left -> ResizeEdge.SW
            bottom && right -> ResizeEdge.SE
            bottom -> ResizeEdge.S
            left -> ResizeEdge.W
            right -> ResizeEdge.E
            // Pure top: leave for chrome drag.
            else -> null
        }
    }

    private fun tryBeginWindowDrag(screenIdx: Int, screen: Screen, px: FloatArray) {
        val window = windowOnSlotAt(screenIdx, px[0], px[1]) ?: return
        val cr = window.chromeOverlayRect(screen.contentWidthPx)
        val inChrome = px[0] >= cr[0] && px[0] < cr[0] + cr[2] &&
            px[1] >= cr[1] && px[1] < cr[1] + cr[3]
        if (!inChrome) return
        bringWindowToFront(window)
        if (window.mode != WorkspaceController.WindowMode.NORMAL) {
            // Cursor's fractional position in the old chrome — used to re-place
            // the window after the un-tile so the cursor stays glued to the
            // same point on the (new, smaller) chrome instead of snapping the
            // window's top-left to the cursor.
            val relX = if (cr[2] > 0) (px[0] - cr[0]) / cr[2].toFloat() else 0.5f
            window.setState(
                WorkspaceController.WindowMode.NORMAL,
                screen.contentWidthPx, screen.contentHeightPx, WINDOW_TASKBAR_PX,
            )
            val newCr = window.chromeOverlayRect(screen.contentWidthPx)
            val desiredChromeLeft = px[0].toInt() - (newCr[2] * relX).toInt()
            val dx = desiredChromeLeft - newCr[0]
            window.xPx = (window.xPx + dx)
                .coerceIn(0, screen.contentWidthPx - window.widthPx)
            window.yPx = (px[1].toInt() - window.chromePx / 2)
                .coerceIn(0, screen.contentHeightPx - window.heightPx)
        }
        dragWindow = window
        dragLastCursorPx = px[0].toInt()
        dragLastCursorPy = px[1].toInt()
        notifyWindowBoundsForSlot(window.slotIdx)
        Log.i(
            TAG,
            "drag begin: pkg=${window.packageName} at slot=" +
                "(${dragLastCursorPx},${dragLastCursorPy}) " +
                "window=(${window.xPx},${window.yPx},${window.widthPx},${window.heightPx})",
        )
    }

    /**
     * Translate the dragged window by the per-frame cursor delta. Reads the
     * cursor's current slot-local pixel via [cursorToRectPx] each frame and
     * applies the delta from the previous frame; bounds are clamped to the
     * slot so the window can't be dragged off-screen *within* a slot. When
     * the cursor crosses into a different slot, [moveWindowToSlot] transfers
     * the window (displacing any existing occupant) and the drag continues
     * in the new slot — same gesture, no release needed.
     */
    private fun updateWindowDrag() {
        val window = dragWindow ?: return
        val cursorSlotIdx = screenIndexUnderCursor() ?: return
        if (cursorSlotIdx != window.slotIdx) {
            moveWindowToSlot(window, cursorSlotIdx)
            // Aspect-mismatched cross-slot moves reinit the window — the dragged
            // AppWindow has been released. End the drag silently; the relaunched
            // window appears when the privileged launch completes.
            if (window !in runningWindows) {
                dragWindow = null
                Log.i(TAG, "drag end: window reinit'd on slot $cursorSlotIdx")
                return
            }
            // Same-aspect cross-slot move: keep dragging in the new slot.
            // Position the window so the cursor sits at the centre of the
            // chrome strip — bind() may have changed widthPx if the new slot
            // has different dims.
            val newSlot = layout.screens.getOrNull(cursorSlotIdx) ?: return
            val px = cursorToRectPx(newSlot) ?: return
            window.xPx = (px[0].toInt() - window.widthPx / 2)
                .coerceIn(0, newSlot.contentWidthPx - window.widthPx)
            window.yPx = (px[1].toInt() - window.chromePx / 2)
                .coerceIn(0, newSlot.contentHeightPx - window.heightPx)
            dragLastCursorPx = px[0].toInt()
            dragLastCursorPy = px[1].toInt()
            notifyWindowBoundsForSlot(cursorSlotIdx)
            return
        }
        val slot = layout.screens.getOrNull(window.slotIdx) ?: return
        val px = cursorToRectPx(slot) ?: return
        val newPx = px[0].toInt()
        val newPy = px[1].toInt()
        val dx = newPx - dragLastCursorPx
        val dy = newPy - dragLastCursorPy
        if (dx == 0 && dy == 0) return
        window.xPx = (window.xPx + dx).coerceIn(0, slot.contentWidthPx - window.widthPx)
        window.yPx = (window.yPx + dy).coerceIn(0, slot.contentHeightPx - window.heightPx)
        dragLastCursorPx = newPx
        dragLastCursorPy = newPy
        notifyWindowBoundsForSlot(window.slotIdx)
        updateSnapHint(slot, newPx, newPy)
    }

    /**
     * Pick the prospective tile target based on cursor position inside the
     * slot, and publish a snap preview rectangle so the slot Presentation can
     * paint a translucent overlay. Cursor near top → MAXIMIZED, left → TILED_LEFT,
     * right → TILED_RIGHT, else null (drop floating).
     */
    private fun updateSnapHint(slot: Screen, cursorPx: Int, cursorPy: Int) {
        val window = dragWindow ?: return
        if (!WorkspaceController.snapZonesEnabled) {
            if (dragSnapHint != null) {
                dragSnapHint = null
                mainHandler.post { WorkspaceController.notifySnapPreview(null) }
            }
            return
        }
        val newHint = when {
            cursorPy < SNAP_EDGE_PX -> WorkspaceController.WindowMode.MAXIMIZED
            cursorPx < SNAP_EDGE_PX -> WorkspaceController.WindowMode.TILED_LEFT
            cursorPx > slot.contentWidthPx - SNAP_EDGE_PX ->
                WorkspaceController.WindowMode.TILED_RIGHT
            else -> null
        }
        if (newHint == dragSnapHint) return
        dragSnapHint = newHint
        val preview = newHint?.let { mode ->
            val rect = snapRectFor(mode, slot)
            WorkspaceController.SnapPreview(
                window.slotIdx, rect[0], rect[1], rect[2], rect[3],
            )
        }
        mainHandler.post { WorkspaceController.notifySnapPreview(preview) }
    }

    private fun snapRectFor(mode: WorkspaceController.WindowMode, slot: Screen): IntArray =
        when (mode) {
            WorkspaceController.WindowMode.MAXIMIZED ->
                AppWindow.computeMaximizedBounds(
                    slot.contentWidthPx, slot.contentHeightPx, WINDOW_TASKBAR_PX,
                )
            WorkspaceController.WindowMode.TILED_LEFT ->
                AppWindow.computeTiledBounds(
                    slot.contentWidthPx, slot.contentHeightPx, WINDOW_TASKBAR_PX, left = true,
                )
            WorkspaceController.WindowMode.TILED_RIGHT ->
                AppWindow.computeTiledBounds(
                    slot.contentWidthPx, slot.contentHeightPx, WINDOW_TASKBAR_PX, left = false,
                )
            else -> intArrayOf(0, 0, 0, 0)
        }

    /**
     * Resize [resizeWindow]'s outer bounds by the per-frame cursor delta in
     * the direction encoded by [resizeEdge]. The opposite-side anchor stays
     * fixed (e.g. for [ResizeEdge.E] the left edge doesn't move; for
     * [ResizeEdge.W] the right edge doesn't move). Width and height are
     * clamped to a minimum of [AppWindow.MIN_USABLE_HEIGHT_PX] on each axis
     * and capped so the window stays inside the slot.
     */
    private fun updateWindowResize() {
        val window = resizeWindow ?: return
        val slot = layout.screens.getOrNull(window.slotIdx) ?: return
        val px = cursorToRectPx(slot) ?: return
        val newPx = px[0].toInt()
        val newPy = px[1].toInt()
        val dx = newPx - dragLastCursorPx
        val dy = newPy - dragLastCursorPy
        if (dx == 0 && dy == 0) return

        val minW = AppWindow.MIN_USABLE_HEIGHT_PX
        val minH = AppWindow.MIN_USABLE_HEIGHT_PX
        var x = window.xPx
        var y = window.yPx
        var w = window.widthPx
        var h = window.heightPx

        when (resizeEdge) {
            ResizeEdge.E, ResizeEdge.NE, ResizeEdge.SE -> {
                w = (w + dx).coerceAtLeast(minW).coerceAtMost(slot.contentWidthPx - x)
            }
            ResizeEdge.W, ResizeEdge.NW, ResizeEdge.SW -> {
                val right = x + w
                w = (w - dx).coerceAtLeast(minW).coerceAtMost(right)
                x = right - w
            }
            else -> {}
        }
        when (resizeEdge) {
            ResizeEdge.S, ResizeEdge.SE, ResizeEdge.SW -> {
                h = (h + dy).coerceAtLeast(minH).coerceAtMost(slot.contentHeightPx - y)
            }
            ResizeEdge.NE, ResizeEdge.NW -> {
                val bottom = y + h
                h = (h - dy).coerceAtLeast(minH).coerceAtMost(bottom)
                y = bottom - h
            }
            else -> {}
        }

        window.xPx = x
        window.yPx = y
        window.widthPx = w
        window.heightPx = h
        dragLastCursorPx = newPx
        dragLastCursorPy = newPy
        notifyWindowBoundsForSlot(window.slotIdx)
    }

    /**
     * Decide whether a right-click should open the desktop context menu. If the
     * cursor is over a running, visible window on the slot under it, ignore for
     * now (window context menus aren't a feature yet). Otherwise dispatch to
     * [WorkspaceController.setContextMenuOpen] with the slot index + press
     * coords in slot pixel space so the menu opens at the cursor.
     */
    private fun handleRightClick() {
        val screenIdx = screenIndexUnderCursor() ?: return
        val screen = layout.screens.getOrNull(screenIdx) ?: return
        val px = cursorToRectPx(screen) ?: return
        // Modal panels (drawer / settings / audio / context menu itself) eat
        // the right-click — close the open modal and don't open a new one.
        if (WorkspaceController.isOptometryOpen) {
            mainHandler.post { WorkspaceController.setOptometryOpen(false) }
            return
        }
        if (WorkspaceController.isDrawerOpen || WorkspaceController.isSettingsOpen ||
            WorkspaceController.isAudioOpen || WorkspaceController.isContextMenuOpen ||
            WorkspaceController.trayPanel != null
        ) {
            mainHandler.post {
                WorkspaceController.setDrawerOpen(false, screenIdx)
                WorkspaceController.setSettingsOpen(false, screenIdx)
                WorkspaceController.setAudioOpen(false, screenIdx)
                WorkspaceController.setTrayPanel(null)
                WorkspaceController.setContextMenuOpen(false, screenIdx)
            }
            return
        }
        // Cursor on a visible running window → desktop right-click doesn't fire.
        val onWindow = runningWindows.any { w ->
            !w.minimized && w.slotIdx == screenIdx &&
                px[0] >= w.xPx && px[0] < w.xPx + w.widthPx &&
                px[1] >= w.yPx && px[1] < w.yPx + w.heightPx
        }
        if (onWindow) return
        val x = px[0]
        val y = px[1]
        mainHandler.post {
            WorkspaceController.setContextMenuOpen(true, screenIdx, x, y)
        }
    }

    private fun finishWindowInteraction() {
        dragWindow?.let { w ->
            dragWindow = null
            val snap = dragSnapHint
            if (snap != null) {
                val slot = layout.screens.getOrNull(w.slotIdx)
                if (slot != null) {
                    w.setState(
                        snap,
                        slot.contentWidthPx, slot.contentHeightPx, WINDOW_TASKBAR_PX,
                    )
                    notifyWindowBoundsForSlot(w.slotIdx)
                    Log.i(TAG, "drag end: pkg=${w.packageName} snapped to $snap")
                }
            } else {
                Log.i(
                    TAG,
                    "drag end: pkg=${w.packageName} -> " +
                        "(${w.xPx},${w.yPx},${w.widthPx},${w.heightPx})",
                )
            }
        }
        if (dragSnapHint != null) {
            dragSnapHint = null
            mainHandler.post { WorkspaceController.notifySnapPreview(null) }
        }
        resizeWindow?.let { w ->
            resizeWindow = null
            Log.i(
                TAG,
                "resize end: pkg=${w.packageName} -> " +
                    "(${w.xPx},${w.yPx},${w.widthPx},${w.heightPx})",
            )
        }
    }

    /**
     * Handle a press-and-hold that is *not* part of a tap-and-drag sequence — i.e.
     * the touchpad's standalone long-press. Only consumed when the cursor sits on
     * an open drawer's cell, where it arms a drag-from-drawer.
     */
    private fun handleLongPress() {
        val screenIdx = screenIndexUnderCursor()
        if (screenIdx == null) {
            Log.i("UxSpace/DrawerDrag", "handleLongPress: cursor not over any screen — abort")
            return
        }
        val screen = layout.screens.getOrNull(screenIdx)
        if (screen == null) {
            Log.i("UxSpace/DrawerDrag", "handleLongPress: screenIdx=$screenIdx out of range")
            return
        }
        val px = cursorToRectPx(screen)
        if (px == null) {
            Log.i("UxSpace/DrawerDrag", "handleLongPress: cursorToRectPx returned null on screen $screenIdx")
            return
        }
        Log.i(
            "UxSpace/DrawerDrag",
            "handleLongPress: screen=$screenIdx px=(${px[0].toInt()},${px[1].toInt()}) drawerOpen=${WorkspaceController.isDrawerOpen} drawerOnScreen=${WorkspaceController.drawerOnScreen}",
        )
        if (tryArmDrawerDragAt(screenIdx, px[0], px[1])) return
        tryArmDesktopShortcutAt(screenIdx, px[0], px[1])
    }

    /**
     * Try to arm a drag carrying an already-placed desktop shortcut at the cursor.
     * Skipped if any modal panel is open on this slot — those occlude the wallpaper,
     * so a press there is for the modal, not the shortcuts beneath. Returns true if
     * an arm happened.
     */
    private fun tryArmDesktopShortcutAt(screenIdx: Int, px: Float, py: Float): Boolean {
        val anyModalHere = (
            WorkspaceController.isDrawerOpen && WorkspaceController.drawerOnScreen == screenIdx
        ) || (
            WorkspaceController.isSettingsOpen && WorkspaceController.settingsOnScreen == screenIdx
        ) || WorkspaceController.isOptometryOpen
        if (anyModalHere) {
            Log.i("UxSpace/DrawerDrag", "tryArmDesktop abort: modal open on slot $screenIdx")
            return false
        }
        val lookup = WorkspaceController.desktopShortcutLookupFor(screenIdx)
        if (lookup == null) {
            Log.i("UxSpace/DrawerDrag", "tryArmDesktop abort: no shortcut lookup for slot $screenIdx")
            return false
        }
        val drag = lookup(px.toInt(), py.toInt())
        if (drag == null) {
            Log.i("UxSpace/DrawerDrag", "tryArmDesktop abort: cursor not over a shortcut")
            return false
        }
        Log.i(
            "UxSpace/DrawerDrag",
            "desktop shortcut drag armed: ${drag.packageName} from slot=$screenIdx",
        )
        mainHandler.post { WorkspaceController.armDrawerDrag(drag) }
        return true
    }

    /**
     * If the drawer is open on [screenIdx] and the cursor's slot-local pixel hits a
     * grid item, ask the controller to arm a drag carrying that app. Returns true
     * when an arm happened so callers can stop further press-handling.
     */
    private fun tryArmDrawerDragAt(screenIdx: Int, px: Float, py: Float): Boolean {
        if (!WorkspaceController.isDrawerOpen) {
            Log.i("UxSpace/DrawerDrag", "tryArm abort: drawer not open")
            return false
        }
        if (WorkspaceController.drawerOnScreen != screenIdx) {
            Log.i(
                "UxSpace/DrawerDrag",
                "tryArm abort: drawerOnScreen=${WorkspaceController.drawerOnScreen} != screenIdx=$screenIdx",
            )
            return false
        }
        val modal = WorkspaceController.modalBoundsForSlot(screenIdx)
        if (modal == null) {
            Log.i("UxSpace/DrawerDrag", "tryArm abort: no modal bounds for slot $screenIdx")
            return false
        }
        val mx = px.toInt() - modal.xPx
        val my = py.toInt() - modal.yPx
        Log.i(
            "UxSpace/DrawerDrag",
            "tryArm: slot=$screenIdx slotPx=(${px.toInt()},${py.toInt()}) modal=(${modal.xPx},${modal.yPx},${modal.widthPx}x${modal.heightPx}) drawerLocal=($mx,$my)",
        )
        if (mx < 0 || my < 0 || mx >= modal.widthPx || my >= modal.heightPx) {
            Log.i("UxSpace/DrawerDrag", "tryArm abort: cursor outside drawer modal rect")
            return false
        }
        val lookup = WorkspaceController.drawerItemAt
        if (lookup == null) {
            Log.w("UxSpace/DrawerDrag", "tryArm abort: drawerItemAt lookup not registered")
            return false
        }
        val drag = lookup(px.toInt(), py.toInt())
        if (drag == null) {
            Log.i("UxSpace/DrawerDrag", "tryArm abort: lookup returned null (cursor not on any cell)")
            return false
        }
        Log.i("UxSpace/DrawerDrag", "drawer drag armed: ${drag.packageName} (slot=$screenIdx)")
        mainHandler.post { WorkspaceController.armDrawerDrag(drag) }
        return true
    }

    private fun handleClick() {
        // The eye chart covers the whole view; a click anywhere swaps its colours
        // (right-click closes it, see handleRightClick).
        if (WorkspaceController.isOptometryOpen) {
            eyeChart.toggleInverted()
            return
        }
        // A click while a drag is in flight resolves the drag instead of routing
        // into any window/Presentation. Dropping on a slot's wallpaper area
        // pins a shortcut; dropping anywhere else (taskbar, drawer, a window)
        // cancels — same as a desktop drag-and-drop.
        if (WorkspaceController.armedDrawerDrag != null) {
            handleArmedDrop()
            return
        }
        val screenIdxDiag = screenIndexUnderCursor()
        val screenDiag = screenIdxDiag?.let { layout.screens.getOrNull(it) }
        val pxDiag = screenDiag?.let { cursorToRectPx(it) }
        Log.i(
            TAG,
            "click: layout=${layout.displayName} screens=${layout.screens.size} " +
                "cursorNDC=(${"%.3f".format(cursorX)},${"%.3f".format(cursorY)}) " +
                "screenIdx=${screenIdxDiag ?: -1} " +
                "px=" + (if (pxDiag == null) "miss"
                else "(${pxDiag[0].toInt()},${pxDiag[1].toInt()}) of " +
                    "${screenDiag?.contentWidthPx}x${screenDiag?.contentHeightPx} " +
                    "curveDeg=${screenDiag?.curveDeg}"),
        )
        // In-view toolbar consumes the click first when expanded and a button is under
        // the cursor — its actions run on the main thread.
        if (toolbarExpanded) {
            val hit = toolbarButtonAtCursor()
            if (hit != null) {
                Log.i(TAG, "toolbar button '${hit.label}' clicked")
                mainHandler.post { hit.action() }
                toolbarLastShownMs = android.os.SystemClock.uptimeMillis()
                return
            }
        }
        val screenIdx = screenIndexUnderCursor() ?: return
        val screen = layout.screens[screenIdx]
        val displayId = screenDisplayId(screenIdx) ?: return
        val px = cursorToRectPx(screen) ?: return
        routeClickOnSlot(screenIdx, screen, displayId, px)
    }

    /**
     * Decide whether a click on a slot should go into the slot's [DesktopPresentation]'s
     * own view tree (taskbar, drawer/settings modal, scrim) or into the activity
     * stacked behind the Presentation on the same trusted display. The Presentation
     * holds FLAG_NOT_TOUCHABLE so the system input dispatcher never delivers display-
     * injected events to it — that lets `appTap`'s injected MotionEvent reach the
     * activity directly. UxSpace's own UI doesn't go through the system dispatcher
     * either: we call [UiScreen.dispatchTap] which fires the click into the view tree
     * from inside our process, bypassing the not-touchable flag.
     */
    private fun routeClickOnSlot(
        screenIdx: Int,
        screen: Screen,
        @Suppress("UNUSED_PARAMETER") displayId: Int,
        px: FloatArray,
    ) {
        val ui = if (screenIdx == 0) desktop else extraScreens.getOrNull(screenIdx - 1)
        val anyModalOpen = (
            WorkspaceController.isDrawerOpen &&
                WorkspaceController.drawerOnScreen == screenIdx
            ) || (
            WorkspaceController.isSettingsOpen &&
                WorkspaceController.settingsOnScreen == screenIdx
            ) || (
            WorkspaceController.isAudioOpen &&
                WorkspaceController.audioOnScreen == screenIdx
            ) || (
            WorkspaceController.trayPanel != null &&
                WorkspaceController.trayPanelOnScreen == screenIdx
            )
        // Window hit only when no modal is open. The window's chrome lives in the
        // slot's Presentation view tree (so its buttons work via dispatchTap);
        // only activity-area clicks are injected into the app. Activity-local
        // coords are measured by *fractional position* inside the activityRect and
        // then scaled up to the bare display's dims — that handles FULLSCREEN
        // (activity rect = full slot, bare display still its launch-time size)
        // and NORMAL / MAXIMIZED (activity rect == bare display dims) with one
        // formula. The chrome rect is the floating toolbar in FULLSCREEN or the
        // full-width strip otherwise — always present.
        if (!anyModalOpen) {
            val window = windowOnSlotAt(screenIdx, px[0], px[1])
            val winDisplayId = window?.displayId
            if (window != null && winDisplayId != null) {
                // Click anywhere inside a non-topmost window raises it to the
                // top of the slot's z-stack — and re-publishes chrome bounds
                // so the title bar follows. Same gesture continues to the
                // chrome / activity branches below.
                bringWindowToFront(window)
                val cr = window.chromeOverlayRect(screen.contentWidthPx)
                val inChrome =
                    px[0] >= cr[0] && px[0] < cr[0] + cr[2] &&
                        px[1] >= cr[1] && px[1] < cr[1] + cr[3]
                if (inChrome) {
                    // Chrome hit — fall through to the slot's Presentation
                    // dispatchTap so the ImageButtons receive the click in-process.
                } else {
                    val act = window.activityRect()
                    val uF = ((px[0] - act[0]) / act[2].toFloat()).coerceIn(0f, 1f)
                    val vF = ((px[1] - act[1]) / act[3].toFloat()).coerceIn(0f, 1f)
                    val localX = (uF * window.ui.width).toInt()
                    val localY = (vF * window.ui.height).toInt()
                    WorkspaceController.appTap?.invoke(winDisplayId, localX, localY)
                    Log.d(
                        TAG,
                        "handleClick → window ${window.packageName} display=$winDisplayId " +
                            "local=($localX,$localY) mode=${window.mode}",
                    )
                    return
                }
            }
        }
        if (ui != null) {
            mainHandler.post { ui.dispatchTap(px[0], px[1]) }
            Log.d(
                TAG,
                "handleClick → Presentation slot=$screenIdx px=(${px[0].toInt()},${px[1].toInt()}) " +
                    "modal=$anyModalOpen",
            )
        }
    }

    /**
     * **Topmost** window on the given slot whose slot-local pixel rect contains
     * the point, or null. Multi-window per slot is supported (cap =
     * [WorkspaceController.maxWindowsPerSlot]); the last window in
     * [runningWindows] for a slot is the front-most in z-order, so we iterate
     * in reverse to find the visible one under the cursor.
     */
    private fun windowOnSlotAt(slotIdx: Int, px: Float, py: Float): AppWindow? =
        runningWindows.findLast { window ->
            // Minimised windows are off-screen — clicks land on whatever the
            // slot's Presentation has at that pixel (wallpaper, taskbar, etc.),
            // *not* on the dormant window.
            !window.minimized &&
                window.slotIdx == slotIdx &&
                px >= window.xPx && px < window.xPx + window.widthPx &&
                py >= window.yPx && py < window.yPx + window.heightPx
        }

    /**
     * Re-rank [window] to the top of its slot's z-stack. Moves the window to
     * the end of [runningWindows] (which is insertion-ordered = z-ordered,
     * last = top) and republishes the slot's chrome bounds so the title bar
     * follows the new top window. No-op if [window] is already top.
     */
    private fun bringWindowToFront(window: AppWindow) {
        if (runningWindows.lastOrNull { it.slotIdx == window.slotIdx } === window) return
        runningWindows.remove(window)
        runningWindows.add(window)
        notifyWindowBoundsForSlot(window.slotIdx)
    }

    /**
     * Dispatch an accumulated scroll delta into the screen under the cursor. The drawer
     * (when open) is a child view inside that screen's Presentation, so its `GridView`
     * gets the scroll naturally via the same input-injection path — no special case.
     */
    private fun handleScroll(dyFraction: Float) {
        if (WorkspaceController.isOptometryOpen) {
            eyeChart.scroll(dyFraction)
            return
        }
        val vScroll = dyFraction * WorkspaceController.scrollSensitivity
        val screenIdx = screenIndexUnderCursor() ?: return
        val screen = layout.screens[screenIdx]
        val px = cursorToRectPx(screen) ?: return
        val ui = if (screenIdx == 0) desktop else extraScreens.getOrNull(screenIdx - 1)
        val anyModalOpen = (
            WorkspaceController.isDrawerOpen &&
                WorkspaceController.drawerOnScreen == screenIdx
            ) || (
            WorkspaceController.isSettingsOpen &&
                WorkspaceController.settingsOnScreen == screenIdx
            ) || (
            WorkspaceController.isAudioOpen &&
                WorkspaceController.audioOnScreen == screenIdx
            ) || (
            WorkspaceController.trayPanel != null &&
                WorkspaceController.trayPanelOnScreen == screenIdx
            )
        if (!anyModalOpen) {
            val window = windowOnSlotAt(screenIdx, px[0], px[1])
            val winDisplayId = window?.displayId
            if (window != null && winDisplayId != null) {
                val cr = window.chromeOverlayRect(screen.contentWidthPx)
                val inChrome =
                    px[0] >= cr[0] && px[0] < cr[0] + cr[2] &&
                        px[1] >= cr[1] && px[1] < cr[1] + cr[3]
                if (!inChrome) {
                    val act = window.activityRect()
                    val uF = ((px[0] - act[0]) / act[2].toFloat()).coerceIn(0f, 1f)
                    val vF = ((px[1] - act[1]) / act[3].toFloat()).coerceIn(0f, 1f)
                    val localX = (uF * window.ui.width).toInt()
                    val localY = (vF * window.ui.height).toInt()
                    WorkspaceController.appScroll?.invoke(winDisplayId, localX, localY, vScroll)
                    return
                }
            }
        }
        if (ui != null) {
            mainHandler.post { ui.dispatchScroll(px[0], px[1], vScroll) }
        }
    }

    /**
     * Pan the PINNED-mode zoom viewport by accumulated trackpad deltas. Magnifier
     * convention — fingers drag right / down → visible rectangle moves right / down over
     * the scene. The factor `2` converts a full-trackpad traversal to a full NDC-range
     * shift in tx/ty, which gives "1 trackpad traversal ≈ 1 viewport-width of scene pan"
     * independent of zoom level (deeper zoom needs more drags to cross the whole scene).
     *
     * Gated to PINNED + zoomed: in FREE mode the workspace doubles as a 3D scene that the
     * user explores with head movement, and zoom < 1.001 has no off-screen scene to pan.
     */
    private fun handlePan(dxFraction: Float, dyFraction: Float) {
        if (viewMode != ViewMode.PINNED) return
        if (pinnedZoom <= 1.0001f) return
        val rawZoom = pinnedZoom * layout.zoomBaseScale
        val maxAbs = (rawZoom - 1f).coerceAtLeast(0f)
        // Y is inverted between trackpad coords (down positive) and NDC (up positive); the
        // sign asymmetry below preserves the "rectangle follows fingers" feel on both axes.
        pinnedTx = (pinnedTx - dxFraction * 2f).coerceIn(-maxAbs, maxAbs)
        pinnedTy = (pinnedTy + dyFraction * 2f).coerceIn(-maxAbs, maxAbs)
        bandDirty = true
    }

    /**
     * Dispatch an accumulated pinch. The drawer is a window-level overlay that doesn't
     * handle pinch, so the input bubbles down past it to the virtual-space zoom (same
     * model as a standard view hierarchy — if the focused widget doesn't consume the
     * gesture, its parent gets a shot).
     */
    private fun handlePinch(scale: Float) {
        if (scale == 1f) return
        if (WorkspaceController.isOptometryOpen) return
        if (!WorkspaceController.pinchEnabled) return
        when (viewMode) {
            ViewMode.FREE, ViewMode.PINNED -> applyCursorAnchoredZoom(scale)
        }
    }

    /**
     * Apply a cursor-anchored projection zoom for the active view mode. Updates the
     * mode's saved (zoom, tx, ty) so the world point currently under the cursor stays
     * under the cursor as the zoom changes — same model as a desktop browser's
     * Ctrl-scroll zoom. The two modes keep independent zoom state.
     */
    private fun applyCursorAnchoredZoom(scale: Float) {
        val oldZoom = activeZoom()
        val newZoom = (oldZoom * scale).coerceIn(WORKSPACE_ZOOM_MIN, WORKSPACE_ZOOM_MAX)
        // Effective scale after clamp — keeps the anchor math consistent at the limits.
        val effScale = if (oldZoom > 0f) newZoom / oldZoom else 1f
        val oldTx = activeTx()
        val oldTy = activeTy()
        val newTx = cursorX * (1f - effScale) + oldTx * effScale
        val newTy = cursorY * (1f - effScale) + oldTy * effScale
        if (viewMode == ViewMode.FREE) {
            freeZoom = newZoom; freeTx = newTx; freeTy = newTy
        } else {
            pinnedZoom = newZoom; pinnedTx = newTx; pinnedTy = newTy
        }
        bandDirty = true
        Log.i(
            TAG,
            "pinch $viewMode scale=${"%.3f".format(scale)} zoom " +
                "${"%.3f".format(oldZoom)} -> ${"%.3f".format(newZoom)} " +
                "anchor=(${"%.2f".format(cursorX)},${"%.2f".format(cursorY)}) " +
                "tx=${"%.3f".format(newTx)} ty=${"%.3f".format(newTy)}",
        )
        mainHandler.post { WorkspaceController.notifyZoomChanged(newZoom) }
    }

    /**
     * Set the active view-mode's projection zoom (and reset its anchor to scene centre)
     * — the toolbar zoom button calls this with discrete values, so bigger %
     * really does mean bigger / closer scene (matches the pinch direction).
     */
    fun setWorkspaceZoom(zoom: Float) {
        val z = zoom.coerceIn(WORKSPACE_ZOOM_MIN, WORKSPACE_ZOOM_MAX)
        if (viewMode == ViewMode.FREE) {
            freeZoom = z; freeTx = 0f; freeTy = 0f
        } else {
            pinnedZoom = z; pinnedTx = 0f; pinnedTy = 0f
        }
        bandDirty = true
        Log.i(TAG, "setWorkspaceZoom $viewMode -> ${"%.3f".format(z)}")
        mainHandler.post { WorkspaceController.notifyZoomChanged(z) }
    }

    /** Read the active view-mode's projection zoom. */
    fun workspaceZoom(): Float = activeZoom()

    private fun activeZoom(): Float = if (viewMode == ViewMode.FREE) freeZoom else pinnedZoom
    private fun activeTx(): Float = if (viewMode == ViewMode.FREE) freeTx else pinnedTx
    private fun activeTy(): Float = if (viewMode == ViewMode.FREE) freeTy else pinnedTy

    /**
     * On FREE→PINNED swap, find the layout screen the user is currently looking at (closest
     * to the head's forward ray hitting the desktop plane) and shift the PINNED view's
     * NDC translation so that screen lands centred. So locking from a sideways head pose
     * doesn't snap the workspace away from what the user was just focused on.
     */
    private fun anchorPinnedOnProminentScreen() {
        val screens = layout.screens
        if (screens.isEmpty()) return
        // Head-forward vector in world space — rotate (0,0,-1) by the head quaternion.
        val fwdX = -2f * (headX * headZ + headW * headY)
        val fwdY = 2f * (headW * headX - headY * headZ)
        val fwdZ = -1f + 2f * (headX * headX + headY * headY)
        if (fwdZ >= -1e-3f) {
            Log.i(TAG, "anchorPinnedOnProminentScreen: head not facing scene (fwdZ=$fwdZ) — skipping")
            return
        }
        // Ray from origin along forward, intersected with the desktop plane at z=-SCREEN_DISTANCE.
        val t = -Screen.SCREEN_DISTANCE / fwdZ
        val hitX = t * fwdX
        val hitY = t * fwdY
        // Closest screen by world-space centre.
        var bestIdx = 0
        var bestScreenX = 0f
        var bestScreenY = 0f
        var bestDist = Float.POSITIVE_INFINITY
        screens.forEachIndexed { i, screen ->
            val r = Screen.worldRect(screen, desktopHalfWidth, desktopHalfHeight)
            val dx = r[0] - hitX
            val dy = r[1] - hitY
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d; bestIdx = i; bestScreenX = r[0]; bestScreenY = r[1]
            }
        }
        // Project screen centre to NDC under the current PINNED zoom (no translation yet) —
        // then set pinnedTx/Ty so x_ndc' = pinnedZoom * x_ndc_orig + tx = 0.
        // Using the FOV math: x_ndc_orig = (cot(fov/2)/aspect) * x_world / |z_world|.
        val halfFov = Math.toRadians(FOV_Y_DEGREES / 2.0)
        val cot = (1.0 / Math.tan(halfFov)).toFloat()
        val ndcX = cot / surfaceAspect * bestScreenX / Screen.SCREEN_DISTANCE
        val ndcY = cot * bestScreenY / Screen.SCREEN_DISTANCE
        // Anchor in raw NDC units, which is what applyBand puts into projection[8,9].
        val rawZoom = pinnedZoom * layout.zoomBaseScale
        pinnedTx = -rawZoom * ndcX
        pinnedTy = -rawZoom * ndcY
        Log.i(
            TAG,
            "anchorPinnedOnProminentScreen: hit=(${"%.2f".format(hitX)},${"%.2f".format(hitY)}) " +
                "-> screen[$bestIdx] centre=(${"%.2f".format(bestScreenX)},${"%.2f".format(bestScreenY)}) " +
                "ndc=(${"%.2f".format(ndcX)},${"%.2f".format(ndcY)}) " +
                "tx=${"%.3f".format(pinnedTx)} ty=${"%.3f".format(pinnedTy)}",
        )
    }

    /**
     * Render the in-view toolbar. Idle = a 5-px peek line at bottom centre; when the
     * cursor is over the peek (or over the expanded toolbar, or within
     * [TOOLBAR_AUTOHIDE_MS] of the last button click) the toolbar expands into 4
     * coloured buttons. State machine is updated here too, so this is the single point
     * of truth for hover-driven expand/collapse.
     */
    private fun drawToolbar() {
        val now = android.os.SystemClock.uptimeMillis()
        val onPeek = cursorY in (PEEK_Y - PEEK_HALF_H)..(PEEK_Y + PEEK_HALF_H) &&
            cursorX in -PEEK_HALF_W..PEEK_HALF_W
        val onExpanded = toolbarExpanded &&
            cursorY in (TOOLBAR_Y - TOOLBAR_HALF_H)..(TOOLBAR_Y + TOOLBAR_HALF_H) &&
            cursorX in -TOOLBAR_HALF_W..TOOLBAR_HALF_W
        if (onPeek || onExpanded) {
            toolbarExpanded = true
            toolbarLastShownMs = now
        } else if (toolbarExpanded && now - toolbarLastShownMs > TOOLBAR_AUTOHIDE_MS) {
            toolbarExpanded = false
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        if (toolbarExpanded) {
            // Translucent background bar behind the buttons — same cursor program as before.
            GLES20.glUseProgram(cursorProgram)
            scrimQuad.position(0)
            GLES20.glVertexAttribPointer(
                cursorAPosition, 2, GLES20.GL_FLOAT, false, ARROW_STRIDE_BYTES, scrimQuad,
            )
            GLES20.glEnableVertexAttribArray(cursorAPosition)
            GLES20.glUniform2f(cursorUCenter, 0f, TOOLBAR_Y)
            GLES20.glUniform2f(cursorUHalfSize, TOOLBAR_HALF_W, TOOLBAR_HALF_H)
            GLES20.glUniform4f(cursorUColor, 0f, 0f, 0f, 0.55f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
            GLES20.glDisableVertexAttribArray(cursorAPosition)

            // Icon textures on top, drawn with the HUD program. Buttons whose action is
            // a no-op in the current view mode (layout in PINNED) render dimmed so the
            // toolbar reads correctly without an extra disabled state.
            GLES20.glUseProgram(hudProgram)
            hudQuad.position(0)
            GLES20.glVertexAttribPointer(
                hudAPosition, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
            )
            hudQuad.position(2)
            GLES20.glVertexAttribPointer(
                hudATexCoord, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
            )
            GLES20.glEnableVertexAttribArray(hudAPosition)
            GLES20.glEnableVertexAttribArray(hudATexCoord)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(hudUTexture, 0)
            // Icons render square in pixels — compute halfW from a fixed halfH so the
            // toolbar's height drives the icon size, not the (wider) hit region.
            val iconHalfH = TOOLBAR_HALF_H * TOOLBAR_ICON_HEIGHT_FRACTION
            val iconHalfW = iconHalfH / surfaceAspect
            for (b in toolbarButtons) {
                val texId = iconTextureFor(b.iconResIdProvider()) ?: continue
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glUniform2f(hudUCenter, b.cx, TOOLBAR_Y)
                GLES20.glUniform2f(hudUHalfSize, iconHalfW, iconHalfH)
                val alpha = if (b.enabled()) 1.0f else TOOLBAR_DISABLED_ALPHA
                GLES20.glUniform4f(hudUColor, 1f, 1f, 1f, alpha)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
            }
            GLES20.glDisableVertexAttribArray(hudAPosition)
            GLES20.glDisableVertexAttribArray(hudATexCoord)
        } else {
            // Peek handle — a short bright line at the bottom centre.
            GLES20.glUseProgram(cursorProgram)
            scrimQuad.position(0)
            GLES20.glVertexAttribPointer(
                cursorAPosition, 2, GLES20.GL_FLOAT, false, ARROW_STRIDE_BYTES, scrimQuad,
            )
            GLES20.glEnableVertexAttribArray(cursorAPosition)
            GLES20.glUniform2f(cursorUCenter, 0f, PEEK_Y)
            GLES20.glUniform2f(cursorUHalfSize, PEEK_HALF_W, PEEK_HALF_H)
            GLES20.glUniform4f(cursorUColor, 1f, 1f, 1f, 0.55f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
            GLES20.glDisableVertexAttribArray(cursorAPosition)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Draw a small red dot just left of the in-view toolbar whenever a capture recording
     * is running, so it's always obvious the framebuffer-readback recording is active —
     * even while the toolbar itself is auto-hidden to its peek line. NDC-positioned; the
     * unit [circleFan] is scaled by an aspect-corrected MVP so it stays round.
     */
    private fun drawRecordingIndicator() {
        if (!recording) return
        Matrix.setIdentityM(mvpMatrix, 0)
        mvpMatrix[0] = REC_DOT_RADIUS                  // NDC x radius
        mvpMatrix[5] = REC_DOT_RADIUS * surfaceAspect  // y radius (aspect-corrected → round)
        mvpMatrix[12] = REC_DOT_CX
        mvpMatrix[13] = REC_DOT_CY
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(solidProgram)
        circleFan.position(0)
        GLES20.glVertexAttribPointer(solidAPosition, 3, GLES20.GL_FLOAT, false, 0, circleFan)
        GLES20.glEnableVertexAttribArray(solidAPosition)
        GLES20.glUniformMatrix4fv(solidUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(solidUColor, 0.90f, 0.12f, 0.12f, 0.95f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, CIRCLE_HINT_SEGMENTS + 2)
        GLES20.glDisableVertexAttribArray(solidAPosition)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * GL texture for the given drawable, lazily uploaded the first time it's needed.
     * Returns null when no icon set has been registered or the resource resolves to
     * 0. Call on the GL thread.
     */
    private fun iconTextureFor(resId: Int): Int? {
        if (resId == 0) return null
        iconTextures[resId]?.let { return it }
        val drawable = runCatching { context.getDrawable(resId) }.getOrNull() ?: return null
        val size = TOOLBAR_ICON_TEXTURE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        iconTextures[resId] = texId
        return texId
    }

    @Volatile private var legendTextureId: Int = 0
    private var legendTextureW: Int = 0
    private var legendTextureH: Int = 0

    /** Last-rendered layout-announcement text — when the controller hands us a new
     *  string we recreate the texture; same string = reuse it (cheap re-draw). */
    private var layoutAnnouncementCachedText: String? = null
    private var layoutAnnouncementTextureId: Int = 0
    private var layoutAnnouncementTextureW: Int = 0
    private var layoutAnnouncementTextureH: Int = 0

    /**
     * Render the keymap legend overlay in the bottom-left of the view while Ctrl+Alt is
     * held. Mirrors the Windows companion's on-glasses legend pass — the user reaches
     * for a modifier to recall hotkeys and the bindings appear immediately. Text is
     * rasterised once into a Bitmap and cached as a GL texture, drawn as a single
     * textured quad with the existing HUD shader.
     */
    private fun drawLegend() {
        ensureLegendTexture()
        if (legendTextureId == 0) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(hudProgram)
        hudQuad.position(0)
        GLES20.glVertexAttribPointer(
            hudAPosition, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        hudQuad.position(2)
        GLES20.glVertexAttribPointer(
            hudATexCoord, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        GLES20.glEnableVertexAttribArray(hudAPosition)
        GLES20.glEnableVertexAttribArray(hudATexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, legendTextureId)
        GLES20.glUniform1i(hudUTexture, 0)
        // Aspect-preserving quad: pin halfH and derive halfW from the bitmap aspect
        // ratio divided by the surface aspect — same shape as the toolbar icon math.
        val halfH = LEGEND_HALF_H
        val halfW = halfH * (legendTextureW.toFloat() / legendTextureH) / surfaceAspect
        GLES20.glUniform2f(hudUCenter, LEGEND_CX, LEGEND_CY)
        GLES20.glUniform2f(hudUHalfSize, halfW, halfH)
        GLES20.glUniform4f(hudUColor, 1f, 1f, 1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(hudAPosition)
        GLES20.glDisableVertexAttribArray(hudATexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Build the legend texture on first draw. Cached for the rest of the GL context's
     * life; cleaned up alongside the toolbar icon textures in [release]. The keymap
     * is intentionally hand-rolled here rather than read from a resource so adding a
     * hotkey is a one-line change to [LEGEND_ROWS] without a layout round-trip.
     */
    private fun ensureLegendTexture() {
        if (legendTextureId != 0) return
        val w = LEGEND_BITMAP_W
        val h = LEGEND_BITMAP_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.argb(210, 0, 0, 0))
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 230, 230, 130)
            textSize = 64f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.MONOSPACE
        }
        val descPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 210, 210, 210)
            textSize = 52f
        }
        canvas.drawText("Ctrl+Alt+...", 40f, 84f, titlePaint)
        // Two columns: workspace keys on the left, window keys on the right.
        listOf(LEGEND_ROWS to 40f, LEGEND_WINDOW_ROWS to w / 2f + 20f).forEach { (rows, x) ->
            var y = 164f
            for ((k, v) in rows) {
                canvas.drawText(k, x, y, keyPaint)
                canvas.drawText(v, x + 400f, y, descPaint)
                y += 64f
            }
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        legendTextureId = texId
        legendTextureW = w
        legendTextureH = h
    }

    /**
     * Render the transient "Layout: <name>" pill at the bottom of the view for ~2 s
     * after a layout cycle. Bitmap is rebuilt only when the announcement text changes
     * (so back-to-back same-layout cycles are free); fade is left as a hard cut for
     * V1.
     */
    /** Head-locked eye chart (docs/OPTOMETRY.md); drawn via [drawFullscreenHud]. */
    private val eyeChart = EyeChartOverlay(::drawFullscreenHud)

    /** Draw a GL_TEXTURE_2D opaquely over the whole current viewport with the HUD quad. */
    private fun drawFullscreenHud(textureId: Int) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(hudProgram)
        hudQuad.position(0)
        GLES20.glVertexAttribPointer(
            hudAPosition, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        hudQuad.position(2)
        GLES20.glVertexAttribPointer(
            hudATexCoord, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        GLES20.glEnableVertexAttribArray(hudAPosition)
        GLES20.glEnableVertexAttribArray(hudATexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(hudUTexture, 0)
        GLES20.glUniform2f(hudUCenter, 0f, 0f)
        GLES20.glUniform2f(hudUHalfSize, 1f, 1f)
        GLES20.glUniform4f(hudUColor, 1f, 1f, 1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(hudAPosition)
        GLES20.glDisableVertexAttribArray(hudATexCoord)
    }

    private fun drawLayoutAnnouncement() {
        val text = WorkspaceController.layoutAnnouncement ?: return
        val expiresAt = WorkspaceController.layoutAnnouncementExpiresAtMs
        val now = android.os.SystemClock.uptimeMillis()
        if (now > expiresAt) {
            WorkspaceController.layoutAnnouncement = null
            return
        }
        if (text != layoutAnnouncementCachedText) {
            buildLayoutAnnouncementTexture(text)
            layoutAnnouncementCachedText = text
        }
        if (layoutAnnouncementTextureId == 0) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(hudProgram)
        hudQuad.position(0)
        GLES20.glVertexAttribPointer(
            hudAPosition, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        hudQuad.position(2)
        GLES20.glVertexAttribPointer(
            hudATexCoord, 2, GLES20.GL_FLOAT, false, HUD_STRIDE_BYTES, hudQuad,
        )
        GLES20.glEnableVertexAttribArray(hudAPosition)
        GLES20.glEnableVertexAttribArray(hudATexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layoutAnnouncementTextureId)
        GLES20.glUniform1i(hudUTexture, 0)
        val halfH = LAYOUT_ANNOUNCE_HALF_H
        val halfW = halfH *
            (layoutAnnouncementTextureW.toFloat() / layoutAnnouncementTextureH) /
            surfaceAspect
        GLES20.glUniform2f(hudUCenter, LAYOUT_ANNOUNCE_CX, LAYOUT_ANNOUNCE_CY)
        GLES20.glUniform2f(hudUHalfSize, halfW, halfH)
        GLES20.glUniform4f(hudUColor, 1f, 1f, 1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(hudAPosition)
        GLES20.glDisableVertexAttribArray(hudATexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun buildLayoutAnnouncementTexture(text: String) {
        // Drop the previous texture before allocating a new one — GL_TEXTURE_2Ds
        // are cheap but we shouldn't leak them across cycles.
        if (layoutAnnouncementTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(layoutAnnouncementTextureId), 0)
            layoutAnnouncementTextureId = 0
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val padX = 60f
        val padY = 30f
        val textW = paint.measureText(text)
        val fm = paint.fontMetrics
        val textH = fm.descent - fm.ascent
        val w = (textW + padX * 2).toInt().coerceAtLeast(1)
        val h = (textH + padY * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.argb(210, 0, 0, 0))
        canvas.drawText(text, padX, padY - fm.ascent, paint)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        layoutAnnouncementTextureId = texId
        layoutAnnouncementTextureW = w
        layoutAnnouncementTextureH = h
    }

    /** Toolbar button whose NDC rect contains the cursor, or null. Disabled buttons are
     *  skipped so a click on a grayed icon behaves like a click on background. */
    private fun toolbarButtonAtCursor(): HudButton? {
        if (cursorY !in (TOOLBAR_Y - TOOLBAR_HALF_H)..(TOOLBAR_Y + TOOLBAR_HALF_H)) return null
        return toolbarButtons.firstOrNull {
            it.enabled() && cursorX in (it.cx - it.halfW)..(it.cx + it.halfW)
        }
    }

    /** World (x, y) where the cursor ray meets the plane z = [planeZ]; null if it misses. */
    private fun cursorRayHit(planeZ: Float): FloatArray? {
        if (!Matrix.invertM(invViewProjection, 0, viewProjection, 0)) return null
        val near = unproject(cursorX, cursorY, -1f) ?: return null
        val far = unproject(cursorX, cursorY, 1f) ?: return null
        val dirZ = far[2] - near[2]
        if (abs(dirZ) < 1e-5f) return null
        val t = (planeZ - near[2]) / dirZ
        if (t < 0f) return null
        return floatArrayOf(
            near[0] + t * (far[0] - near[0]),
            near[1] + t * (far[1] - near[1]),
        )
    }

    /**
     * Cursor → screen-local pixel `[px, py]`, or null if the cursor misses the screen rect.
     * Accounts for [Screen.yawDeg] by intersecting the cursor ray with the screen's
     * tilted plane (normal rotated by yaw around Y), then unrotating the hit into
     * screen-local space. Flat screens (yaw == 0) use the fast flat-plane path.
     */
    private fun cursorToRectPx(screen: Screen): FloatArray? {
        val r = Screen.worldRect(screen, desktopHalfWidth, desktopHalfHeight)
        val cx = r[0]; val cy = r[1]; val cz = r[2]; val w = r[3]; val h = r[4]
        if (screen.curveDeg > 0f) {
            return cursorToCurvedPx(screen, cx, cy, cz)
        }
        if (screen.yawDeg == 0f) {
            val result = cursorToRectPx(cx, cy, cz, w, h, screen.contentWidthPx, screen.contentHeightPx)
            flatCursorDiagFrame++
            if (CURSOR_DIAG && flatCursorDiagFrame % 120 == 0 && result != null) {
                Log.i(
                    TAG,
                    "flat-cursor: ndc=(${"%.3f".format(cursorX)},${"%.3f".format(cursorY)}) " +
                        "px=(${result[0].toInt()},${result[1].toInt()}) of " +
                        "${screen.contentWidthPx}x${screen.contentHeightPx}",
                )
            }
            return result
        }
        if (!Matrix.invertM(invViewProjection, 0, viewProjection, 0)) return null
        val near = unproject(cursorX, cursorY, -1f) ?: return null
        val far = unproject(cursorX, cursorY, 1f) ?: return null
        val dx = far[0] - near[0]
        val dy = far[1] - near[1]
        val dz = far[2] - near[2]
        val yawRad = Math.toRadians(screen.yawDeg.toDouble())
        val nx = Math.sin(yawRad).toFloat()
        val nz = Math.cos(yawRad).toFloat()
        // n · dir == 0 → ray parallel to plane.
        val denom = nx * dx + nz * dz
        if (abs(denom) < 1e-6f) return null
        val t = (nx * (cx - near[0]) + nz * (cz - near[2])) / denom
        if (t < 0f) return null
        val hx = near[0] + t * dx
        val hy = near[1] + t * dy
        val hz = near[2] + t * dz
        // Inverse-rotate the hit into screen-local space (yaw = 0 there, quad in XY plane).
        val cosNeg = Math.cos(-yawRad).toFloat()
        val sinNeg = Math.sin(-yawRad).toFloat()
        val localX = (hx - cx) * cosNeg + (hz - cz) * sinNeg
        val localY = hy - cy
        val hw = w / 2f
        val hh = h / 2f
        if (localX < -hw || localX > hw || localY < -hh || localY > hh) return null
        return floatArrayOf(
            (localX + hw) / (2f * hw) * screen.contentWidthPx,
            (hh - localY) / (2f * hh) * screen.contentHeightPx,
        )
    }

    /**
     * Cursor → screen-local pixel for a *curved* screen, by intersecting the
     * cursor ray with the actual cylindrical surface that [drawCurvedScreen]
     * renders. The flat-plane fallback was missing hits near the edges of a
     * wide arc: the chord's projection is narrower in NDC than the arc's
     * (the arc curls towards the viewer, so its endpoints sit at a smaller
     * |Z| and therefore a larger |X| in NDC).
     *
     * Cylinder geometry must match drawCurvedScreen exactly:
     *  - axis is the vertical line through `(cx, _, 0)`
     *  - radius = `-cz` (distance from viewer to centre of arc)
     *  - arc subtends `screen.curveDeg`, centred on the −Z direction
     *  - arc length × content aspect determines the screen height (≠ worldRect.h)
     */
    private fun cursorToCurvedPx(
        screen: Screen,
        cx: Float, cy: Float, cz: Float,
    ): FloatArray? {
        if (!Matrix.invertM(invViewProjection, 0, viewProjection, 0)) return null
        val near = unproject(cursorX, cursorY, -1f) ?: return null
        val far = unproject(cursorX, cursorY, 1f) ?: return null
        val ox = near[0]; val oy = near[1]; val oz = near[2]
        val dx = far[0] - ox; val dy = far[1] - oy; val dz = far[2] - oz

        val arcRad = Math.toRadians(screen.curveDeg.toDouble()).toFloat()
        val radius = -cz
        val arcLength = radius * arcRad
        val aspect = screen.contentWidthPx.toFloat() / screen.contentHeightPx
        val halfH = arcLength / aspect / 2f

        // Solve (ox − cx + t·dx)² + (oz + t·dz)² = radius²  for the cylinder
        // x'² + z² = radius² (axis at x=cx, z=0). Quadratic in t.
        val sx = ox - cx
        val A = dx * dx + dz * dz
        val B = 2f * (sx * dx + oz * dz)
        val C = sx * sx + oz * oz - radius * radius
        val disc = B * B - 4f * A * C
        if (disc < 0f) return null
        val sqrtDisc = kotlin.math.sqrt(disc)
        val t1 = (-B - sqrtDisc) / (2f * A)
        val t2 = (-B + sqrtDisc) / (2f * A)
        // Smallest non-negative root: the entry hit when the viewer is outside
        // the cylinder; the (sole) exit hit when inside.
        val t = when {
            t1 >= 0f -> t1
            t2 >= 0f -> t2
            else -> return null
        }
        val hx = ox + t * dx
        val hy = oy + t * dy
        val hz = oz + t * dz

        // Angle from the −Z axis (matches drawCurvedScreen: θ=0 maps to z=−r,
        // x=cx; positive θ goes to +X).
        val theta = kotlin.math.atan2(hx - cx, -hz)
        val halfArc = arcRad / 2f
        if (theta < -halfArc || theta > halfArc) return null
        if (hy < cy - halfH || hy > cy + halfH) return null
        val u = (theta + halfArc) / arcRad
        val v = (hy - (cy - halfH)) / (2f * halfH)
        val result = floatArrayOf(
            u * screen.contentWidthPx,
            (1f - v) * screen.contentHeightPx,
        )
        curvedCursorDiagFrame++
        if (CURSOR_DIAG && curvedCursorDiagFrame % 60 == 0) {
            Log.i(
                TAG,
                "curved-cursor: ndc=(${"%.3f".format(cursorX)},${"%.3f".format(cursorY)}) " +
                    "hit=(${"%.2f".format(hx)},${"%.2f".format(hy)},${"%.2f".format(hz)}) " +
                    "theta=${"%.1f".format(Math.toDegrees(theta.toDouble()))} " +
                    "px=(${result[0].toInt()},${result[1].toInt()}) of " +
                    "${screen.contentWidthPx}x${screen.contentHeightPx}",
            )
        }
        return result
    }

    /** Slot index → the VirtualDisplay that hosts that screen's UiScreen, or null if not ready. */
    private fun screenDisplayId(idx: Int): Int? {
        val ui = if (idx == 0) desktop else extraScreens.getOrNull(idx - 1)
        return ui?.displayId
    }

    /**
     * Cursor → pixel `[px, py]` within a world-space quad centred at ([x], [y], [z]), of size
     * [w] × [h] metres and [pxW] × [pxH] pixels; null if the cursor ray misses the quad.
     */
    private fun cursorToRectPx(
        x: Float, y: Float, z: Float, w: Float, h: Float, pxW: Int, pxH: Int,
    ): FloatArray? {
        val hit = cursorRayHit(z) ?: return null
        val hw = w / 2f
        val hh = h / 2f
        val lx = hit[0] - x
        val ly = hit[1] - y
        if (lx < -hw || lx > hw || ly < -hh || ly > hh) return null
        return floatArrayOf(
            (lx + hw) / (2f * hw) * pxW,
            (hh - ly) / (2f * hh) * pxH,
        )
    }

    /** Unproject an NDC point to world space via the inverse view-projection. */
    private fun unproject(ndcX: Float, ndcY: Float, ndcZ: Float): FloatArray? {
        clipPoint[0] = ndcX
        clipPoint[1] = ndcY
        clipPoint[2] = ndcZ
        clipPoint[3] = 1f
        Matrix.multiplyMV(worldPoint, 0, invViewProjection, 0, clipPoint, 0)
        val w = worldPoint[3]
        if (abs(w) < 1e-6f) return null
        return floatArrayOf(worldPoint[0] / w, worldPoint[1] / w, worldPoint[2] / w)
    }

    /** (Re)create the readback PBO + reusable CPU buffers for the current surface size.
     *  GL thread only. Surface size is stable for a session, so this runs ~once. */
    private fun ensureCapturePbo(w: Int, h: Int) {
        if (capturePboId != 0 && capturePboW == w && capturePboH == h) return
        if (capturePboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(capturePboId), 0)
            capturePboId = 0
        }
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        capturePboId = ids[0]
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, capturePboId)
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, w * h * 4, null, GLES30.GL_STREAM_READ)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        capturePboW = w; capturePboH = h
        captureWorkBuf = ByteArray(w * h * 4)
        capturePixels = IntArray(w * h)
        captureBitmap = null
        captureReadbackPending = false
    }

    /** Kick off an async framebuffer readback into the PBO — returns immediately, no GPU
     *  stall. The pixels are picked up by [consumeCaptureReadback] on a later frame. */
    private fun issueCaptureReadback(snapshot: Boolean) {
        val w = surfaceWidth; val h = surfaceHeight
        if (w == 0 || h == 0) return
        ensureCapturePbo(w, h)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, capturePboId)
        // Offset 0 into the bound PIXEL_PACK_BUFFER — the read DMAs in the background.
        GLES30.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        captureReadbackPending = true
        capturePendingSnapshot = snapshot
    }

    /** Map the previously-issued readback, copy it into a reusable buffer, and hand it to
     *  the encode worker. The map doesn't stall: the read was issued frames ago. GL thread. */
    private fun consumeCaptureReadback() {
        if (!captureReadbackPending) return
        captureReadbackPending = false
        val w = capturePboW; val h = capturePboH
        if (w == 0 || h == 0 || capturePboId == 0) return
        val bytes = w * h * 4
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, capturePboId)
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT,
        ) as? ByteBuffer
        if (mapped != null && captureBusy.compareAndSet(false, true)) {
            mapped.order(ByteOrder.nativeOrder()).position(0)
            mapped.get(captureWorkBuf, 0, bytes)
            // Snapshot the overlay inputs on the GL thread so the worker touches no live state.
            val snapshot = capturePendingSnapshot
            val cx = cursorX; val cy = cursorY
            val overlay = WorkspaceController.captureDebugOverlay
            val info = "cursor=(${"%.3f".format(cx)},${"%.3f".format(cy)})  " +
                "layout=${layout.displayName}  screens=${layout.screens.size}"
            captureExecutor.execute { encodeAndSaveCapture(w, h, cx, cy, overlay, info, snapshot) }
        }
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    /** Worker-thread: flip the RGBA bytes to top-down ARGB, draw the optional debug overlay,
     *  PNG-encode and write the file. Reuses [capturePixels] / [captureBitmap]; clears
     *  [captureBusy] when done so the next frame can issue another capture. */
    private fun encodeAndSaveCapture(
        w: Int, h: Int, cursorPxNdcX: Float, cursorPxNdcY: Float,
        overlay: Boolean, info: String, snapshot: Boolean,
    ) {
        try {
            val src = captureWorkBuf
            val px = capturePixels
            // glReadPixels rows run bottom-to-top; flip into top-down opaque ARGB pixels.
            for (y in 0 until h) {
                val s = (h - 1 - y) * w * 4
                val d = y * w
                for (x in 0 until w) {
                    val i = s + x * 4
                    val r = src[i].toInt() and 0xFF
                    val g = src[i + 1].toInt() and 0xFF
                    val b = src[i + 2].toInt() and 0xFF
                    px[d + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            var bmp = captureBitmap
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                captureBitmap = bmp
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            if (overlay) {
                val canvas = Canvas(bmp)
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f
                }
                canvas.drawCircle((cursorPxNdcX + 1f) / 2f * w, (1f - cursorPxNdcY) / 2f * h, 28f, ringPaint)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.YELLOW; textSize = 22f
                    setShadowLayer(3f, 0f, 0f, Color.BLACK)
                }
                canvas.drawText(info, 12f, h - 16f, textPaint)
            }
            val name = "uxspace-${System.currentTimeMillis()}.png"
            // One-shot snapshots land in the public Photos/Gallery (Pictures/UxSpace album) so
            // the user can see and share them. Recording frame-dumps stay in the private app
            // sandbox — sending hundreds of frames to the gallery would flood it.
            val ok: Boolean
            val where: String
            if (snapshot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ok = saveSnapshotToGallery(bmp, name)
                where = "Pictures/$CAPTURE_ALBUM/$name"
            } else {
                val dir = File(appContext.getExternalFilesDir(null), "captures").apply { mkdirs() }
                val file = File(dir, name)
                ok = runCatching {
                    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }.isSuccess
                where = file.absolutePath
            }
            Log.i(TAG, if (ok) "capture saved: $where" else "capture save failed ($where)")
            // Only toast for one-shot snapshots (the phone already toasts on tap, and a
            // per-frame toast would spam during recording) — and always surface failures.
            if (snapshot && ok) {
                mainHandler.post { Toast.makeText(appContext, "Saved to Photos", Toast.LENGTH_SHORT).show() }
            } else if (!ok) {
                mainHandler.post { Toast.makeText(appContext, "Capture failed", Toast.LENGTH_SHORT).show() }
            }
        } finally {
            captureBusy.set(false)
        }
    }

    /**
     * Write a one-shot capture into the public gallery under `Pictures/[CAPTURE_ALBUM]` via
     * [MediaStore], so it shows up in Photos / the Gallery app. Scoped-storage path (API 29+):
     * the app owns what it inserts, so no storage permission is needed. Uses the IS_PENDING
     * flow so a half-written file is never surfaced to the gallery. Worker-thread only.
     */
    private fun saveSnapshotToGallery(bmp: Bitmap, displayName: String): Boolean = runCatching {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$CAPTURE_ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return@runCatching false
        try {
            resolver.openOutputStream(uri).use { out ->
                if (out == null) throw java.io.IOException("null output stream for $uri")
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw java.io.IOException("PNG compress failed")
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (t: Throwable) {
            // Roll back the pending row so a failed write leaves no orphan in the gallery.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }.getOrElse {
        Log.w(TAG, "gallery save failed: ${it.message}")
        false
    }

    private fun bindQuad(quad: FloatBuffer, positionHandle: Int, texCoordHandle: Int) {
        quad.position(0)
        GLES20.glVertexAttribPointer(
            positionHandle, POSITION_FLOATS, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad,
        )
        GLES20.glEnableVertexAttribArray(positionHandle)

        quad.position(POSITION_FLOATS)
        GLES20.glVertexAttribPointer(
            texCoordHandle, TEXCOORD_FLOATS, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad,
        )
        GLES20.glEnableVertexAttribArray(texCoordHandle)
    }

    /**
     * Camera view matrix — the inverse of the head rotation, so the scene stays world-fixed
     * as the head turns. Built from the conjugate of the head-orientation quaternion.
     */
    private fun buildView(out: FloatArray) {
        if (viewMode == ViewMode.PINNED) {
            // Pinned: the scene is locked to the viewport — it follows the head.
            Matrix.setIdentityM(out, 0)
            return
        }
        // Compose head pose with the recenter anchor: effective = head * anchor. The view
        // matrix is the inverse of that rotation (conjugate of the unit quaternion).
        val ew = headW * anchorW - headX * anchorX - headY * anchorY - headZ * anchorZ
        val ex = headW * anchorX + headX * anchorW + headY * anchorZ - headZ * anchorY
        val ey = headW * anchorY - headX * anchorZ + headY * anchorW + headZ * anchorX
        val ez = headW * anchorZ + headX * anchorY - headY * anchorX + headZ * anchorW
        val w = ew
        val x = -ex
        val y = -ey
        val z = -ez
        val norm = w * w + x * x + y * y + z * z
        val s = if (norm > 1e-6f) 2f / norm else 0f
        val xs = x * s; val ys = y * s; val zs = z * s
        val wx = w * xs; val wy = w * ys; val wz = w * zs
        val xx = x * xs; val xy = x * ys; val xz = x * zs
        val yy = y * ys; val yz = y * zs; val zz = z * zs
        out[0] = 1f - (yy + zz); out[1] = xy + wz; out[2] = xz - wy; out[3] = 0f
        out[4] = xy - wz; out[5] = 1f - (xx + zz); out[6] = yz + wx; out[7] = 0f
        out[8] = xz + wy; out[9] = yz - wx; out[10] = 1f - (xx + yy); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f

        // Positional parallax (6DOF tracking only): translate the camera with the head so
        // moving physically shifts the viewpoint through the fixed workspace. The camera
        // sits at headPos (scaled + clamped to bound VIO drift); post-multiplying T(-cam)
        // gives view·p = R·(p - cam), the standard view transform for a translated camera.
        // headPos is 0 on orientation-only paths, so this is a no-op there.
        if (PARALLAX_SCALE != 0f && (headPosX != 0f || headPosY != 0f || headPosZ != 0f)) {
            val cx = (headPosX * PARALLAX_SCALE).coerceIn(-PARALLAX_MAX_M, PARALLAX_MAX_M)
            val cy = (headPosY * PARALLAX_SCALE).coerceIn(-PARALLAX_MAX_M, PARALLAX_MAX_M)
            val cz = (headPosZ * PARALLAX_SCALE).coerceIn(-PARALLAX_MAX_M, PARALLAX_MAX_M)
            Matrix.translateM(out, 0, -cx, -cy, -cz)
        }
    }

    /**
     * Model matrix for a quad with an extra yaw rotation about the Y axis — used by
     * multi-screen presets where side screens are tilted to face the user. Rotation is
     * applied around the screen's own centre (translate, rotate, scale).
     */
    private fun buildModelRectYawed(
        out: FloatArray, x: Float, y: Float, z: Float, w: Float, h: Float, yawDeg: Float,
    ) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, x, y, z)
        if (yawDeg != 0f) Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(out, 0, w / 2f, h / 2f, 1f)
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private companion object {
        const val TAG = "UxSpace/Renderer"

        /** Gallery album (under Pictures/) that one-shot workspace snapshots are saved into. */
        const val CAPTURE_ALBUM = "UxSpace"

        /** The desktop sits just behind the launched-app screens, filling the view. */
        const val DESKTOP_DISTANCE = 4.2f

        /**
         * Positional-parallax gain. 1.0 = true-to-life (1 cm of head motion moves the camera
         * 1 cm against a workspace placed in metres). Lower it to damp the effect or to mute
         * VIO position jitter; 0 disables parallax (orientation-only camera).
         */
        const val PARALLAX_SCALE = 1.0f

        /**
         * Hard clamp (metres) on camera displacement from the recentre origin. Carina VIO
         * position drifts slowly; without a bound the workspace could creep away over a long
         * session. Bounds the camera to a box around the origin big enough for natural sway.
         */
        const val PARALLAX_MAX_M = 0.5f

        /** Default render band — the glasses' top/bottom edges are uncomfortable to view. */
        const val DEFAULT_SCREEN_BAND = 0.83f

        /** The render band cannot shrink below this fraction of the display. */
        const val MIN_SCREEN_BAND = 0.5f

        /** Hard upper bound on the render band — beyond 1.0 the scene crops top/bottom. */
        const val MAX_SCREEN_BAND = 2.5f

        /** Pinch zoom clamps the projection scale into this range — 80% to 300%. */
        const val WORKSPACE_ZOOM_MIN = 0.8f
        const val WORKSPACE_ZOOM_MAX = 3.0f

        /** Initial workspace zoom for both PINNED and FREE modes — 120% on startup. */
        const val DEFAULT_WORKSPACE_ZOOM = 1.2f

        /** Locked (PINNED) mode starts at 1.0× — neutral baseline for the focus view. */
        const val DEFAULT_PINNED_ZOOM = 1.0f

        // In-view toolbar HUD — NDC layout. Peek = a small bright line at bottom centre;
        // when the cursor is over it (or the expanded toolbar), the toolbar expands.
        const val PEEK_Y = -0.97f
        const val PEEK_HALF_W = 0.06f
        const val PEEK_HALF_H = 0.005f
        const val TOOLBAR_Y = -0.90f
        const val TOOLBAR_HALF_W = 0.42f
        const val TOOLBAR_HALF_H = 0.06f

        // Recording indicator — a red dot just left of the toolbar, shown whenever a
        // recording is running (independent of the toolbar's expand/collapse state).
        const val REC_DOT_CX = -0.50f
        const val REC_DOT_CY = TOOLBAR_Y
        const val REC_DOT_RADIUS = 0.018f

        /**
         * NDC half-width of a button's *hit region* — wider than the icon so the cursor
         * can land near an icon and still register. Picked so 5 buttons + gaps fill the
         * `2 * TOOLBAR_HALF_W` bar.
         */
        const val TOOLBAR_BUTTON_HALF_W = 0.055f
        /** Icon visual fills this much of the bar's height (the rest is vertical padding). */
        const val TOOLBAR_ICON_HEIGHT_FRACTION = 0.78f
        /** Rasterised bitmap size for vector-drawable icon textures (pixels). */
        const val TOOLBAR_ICON_TEXTURE_PX = 96
        /** Alpha for grayed-out toolbar icons whose action isn't currently meaningful
         *  (e.g. Lock button while head tracking is off, Layout button in PINNED). */
        const val TOOLBAR_DISABLED_ALPHA = 0.35f

        // Legend overlay — bottom-left translucent card listing Ctrl+Alt+X hotkeys
        // while Ctrl+Alt is held. Sized in NDC so the placement stays put across
        // surface resolutions; the texture aspect is preserved at draw time.
        const val LEGEND_CX = 0f
        const val LEGEND_CY = -0.40f
        const val LEGEND_HALF_H = 0.44f
        const val LEGEND_BITMAP_W = 2560
        const val LEGEND_BITMAP_H = 760
        // Layout announcement — translucent "Layout name" pill at the bottom of the
        // view for 2 s after a layout switch. Sized like the legend but smaller.
        const val LAYOUT_ANNOUNCE_CX = 0f
        const val LAYOUT_ANNOUNCE_CY = -0.75f
        const val LAYOUT_ANNOUNCE_HALF_H = 0.06f

        /** Ctrl+Alt window shortcuts (docs/DEX_FEATURES.md §3), the legend's right column. */
        val LEGEND_WINDOW_ROWS: List<Pair<String, String>> = listOf(
            "← / →" to "Tile left / right",
            "↑" to "Maximize",
            "↓" to "Restore, then minimize",
            "Shift+← / →" to "Move to prev / next screen",
            "Q" to "Close window",
            "D" to "Show desktop",
            "Space" to "App drawer",
        )

        val LEGEND_ROWS: List<Pair<String, String>> = listOf(
            "Wheel" to "Zoom",
            "+ / =" to "Zoom in (step)",
            "-" to "Zoom out (step)",
            "Z" to "Cycle screen size",
            "X" to "Toggle PINNED / FREE",
            "A" to "Cycle layout (FREE)",
            "R" to "Recenter (SDK reset)",
            "C" to "Anchor at pose",
            "Drag" to "Pan zoom rect (PINNED+zoom)",
        )

        /** How long the expanded toolbar lingers after the last hover or click. */
        const val TOOLBAR_AUTOHIDE_MS = 3000L

        /**
         * How many vertical strips approximate a curved screen. Each strip is a flat
         * yawed quad — fewer strips ⇒ visible folds at boundaries (each step is a
         * larger yaw delta); more ⇒ smoother curve, more draws.
         */
        const val CURVED_SEGMENTS = 24

        /** Cursor scale (NDC); click-flash duration. */
        const val CURSOR_SCALE = 0.01405f

        /** Capture every Nth render frame while recording — 12 ≈ 5 fps at a 60 Hz GL loop.
         *  Live value lives on [WorkspaceController.recordingFrameInterval]. */
        const val CURSOR_FLASH_FRAMES = 12

        /** Cursor moves arrive at touchpad / mouse polling rate; one in N gets logged. */
        const val CURSOR_LOG_EVERY = 8

        /**
         * Master switch for the per-move / per-frame cursor diagnostics — `moveCursor`,
         * `flat-cursor`, and `curved-cursor`. Off by default: they flood logcat at input
         * and frame rate. Flip to true when debugging cursor → screen-pixel mapping.
         */
        const val CURSOR_DIAG = false

        /**
         * Window placement bands inside a slot (slot's pixel coords): the activity quad
         * occupies the slot minus a chrome strip at the top and the taskbar strip at the
         * bottom. Chrome ≈ `DesktopPresentation.CHROME_HEIGHT_DP` (39 dp) at the slot's
         * DENSITY_DPI=200 (≈49 px); taskbar ≈ dp(58) at the same density (≈73 px). The
         * window quad is sized to hug right up against both bands.
         */
        const val WINDOW_CHROME_PX = 48
        const val WINDOW_TASKBAR_PX = 73

        /**
         * Bottom band of the slot (in slot-local pixels) where cursor presence counts
         * as "hover" for the auto-hide taskbar — fairly generous (200 ≈ 17% of a
         * 1200-tall slot) so the taskbar reveals before the cursor reaches the bar
         * itself, giving the slide-in a moment to play.
         */
        const val TASKBAR_HOVER_ZONE_PX = 200

        /** Column width (slot px) for the taskbar pointer events behind tooltips. */
        const val TASKBAR_POINTER_STEP_PX = 8

        /**
         * Slack (slot-local px) added around the FULLSCREEN chrome toolbar's
         * top-left rect when deciding whether the cursor is "over" it. The
         * toolbar is auto-hidden and only drawn while the cursor is within this
         * expanded zone, so the margin keeps the buttons' edges reachable
         * without the toolbar flickering away mid-click.
         */
        const val FULLSCREEN_TOOLBAR_REVEAL_MARGIN_PX = 24

        /**
         * Slot-bottom band that's treated as "the taskbar zone" when resolving a
         * drag-from-drawer drop. Matches [WINDOW_TASKBAR_PX] so a drop on the bar
         * cancels instead of pinning a shortcut behind the taskbar.
         */
        const val DROP_TASKBAR_GUARD_PX = WINDOW_TASKBAR_PX

        /**
         * Cross-slot move triggers a window reinit (close + relaunch on the new
         * slot) when the bare-display aspect differs from the destination slot's
         * activity-area aspect by more than this fraction. Below this threshold
         * the existing bare display is reused — the texture stretches a little
         * but the activity keeps its state. Above it (V↔H crossings), state
         * loss is preferable to severe distortion. 0.25 = 25 % aspect drift.
         */
        const val RESPAWN_ASPECT_TOLERANCE = 0.25f

        /**
         * Resize hit-test bands around a window's outer rect, in slot-local
         * pixels. The cursor activates resize while it sits within
         * [RESIZE_BAND_IN_PX] inside any edge of the outer rect, or up to
         * [RESIZE_BAND_OUT_PX] outside (on the visible 3 px frame). Tuned
         * fairly generously — fine pointer aim on the trackpad doesn't have
         * to be pixel-perfect.
         */
        const val RESIZE_BAND_IN_PX = 14
        const val RESIZE_BAND_OUT_PX = 8

        /**
         * Slot-edge band (slot-local px) where releasing a drag snaps the window
         * to a tile. Cursor `y < SNAP_EDGE_PX` → MAXIMIZED; `x < SNAP_EDGE_PX`
         * → TILED_LEFT; `x > slotW - SNAP_EDGE_PX` → TILED_RIGHT.
         */
        const val SNAP_EDGE_PX = 40

        /**
         * Retry budget for waiting on a window's bare trusted display id after
         * [UiScreen.startTrustedBare] is queued. 40 × 250 ms = 10 s — well above the
         * helper bootstrap time on a cold start.
         */
        const val LAUNCH_DISPLAY_WAIT_ATTEMPTS = 40
        const val LAUNCH_DISPLAY_WAIT_MS = 250L

        const val ARROW_STRIDE_BYTES = 2 * 4

        /** HUD icon quad: x, y, u, v = 4 floats per vertex = 16 bytes. */
        const val HUD_STRIDE_BYTES = 4 * 4
        const val CURSOR_ARROW_VERTEX_COUNT = 3

        /** A pointer arrowhead as one triangle (x, y) — tip at the origin, pointing up-left. */
        val CURSOR_ARROW_VERTICES = floatArrayOf(
            0f, 0f,
            0f, -1f,
            0.7f, -0.7f,
        )

        /** A full-screen quad (x, y), drawn with the cursor program as the drawer scrim. */
        val SCRIM_QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        const val FOV_Y_DEGREES = 55f
        const val NEAR_PLANE = 0.1f
        const val FAR_PLANE = 100f

        const val POSITION_FLOATS = 3
        const val TEXCOORD_FLOATS = 2
        const val FLOATS_PER_VERTEX = POSITION_FLOATS + TEXCOORD_FLOATS
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val QUAD_VERTEX_COUNT = 4

        /**
         * Screen quad: x, y, z, u, v. Texture v rises from 0 at the bottom to 1 at the top —
         * the convention a SurfaceTexture's transform matrix is built for.
         */
        val SCREEN_QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f,
        )

        const val SCREEN_VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        // The #extension directive must be the first line of the source.
        const val SCREEN_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n"

        const val CURSOR_VERTEX_SHADER = """
            uniform vec2 uCenter;
            uniform vec2 uHalfSize;
            attribute vec4 aPosition;
            void main() {
                gl_Position = vec4(aPosition.xy * uHalfSize + uCenter, 0.0, 1.0);
            }
        """

        const val CURSOR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        // Solid-colour 3D program — flat uColor geometry transformed by uMvp.
        const val SOLID_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMvp * aPosition;
            }
        """

        const val SOLID_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        /** Triangle-fan segment count for the FULLSCREEN toolbar's reveal-hint circle. */
        const val CIRCLE_HINT_SEGMENTS = 48

        /**
         * Semi-opaque RGBA of the reveal-hint dot shown where the FULLSCREEN toolbar
         * will appear. A frosted neutral so it reads on both light and dark apps;
         * its diameter matches the toolbar height.
         */
        const val CIRCLE_HINT_R = 0.88f
        const val CIRCLE_HINT_G = 0.90f
        const val CIRCLE_HINT_B = 0.96f
        const val CIRCLE_HINT_A = 0.42f

        // HUD icon: textured NDC quad. Sampled texture is multiplied by uColor so the
        // same white-on-transparent material-symbols bitmap can be tinted per state
        // (full opacity for active buttons, faded for disabled like 'layout' in PINNED).
        const val HUD_VERTEX_SHADER = """
            uniform vec2 uCenter;
            uniform vec2 uHalfSize;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * uHalfSize + uCenter, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        const val HUD_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord) * uColor;
            }
        """

        /**
         * HUD icon quad — `(x, y, u, v)` per vertex. Triangle-strip order. Texture v is
         * inverted from the SCREEN_QUAD convention because the bitmap we upload (drawn
         * from a vector drawable through Canvas) has y=0 at the top, whereas
         * SCREEN_QUAD's v=0 is at the bottom of the framebuffer.
         */
        val HUD_QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        fun directBufferOf(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }

        fun buildProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("Program link failed: $log")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Shader compile failed: $log")
            }
            return shader
        }
    }
}
