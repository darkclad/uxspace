package com.uxspace.sim

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.uxspace.apps.BuiltInApps
import com.uxspace.privileged.PrivilegedService
import com.uxspace.spatial.WorkspaceController
import com.uxspace.spatial.WorkspaceRenderer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Debug-build-only simulator control channel: lets the PC fake the glasses' head pose and
 * poke the workspace while it runs on an emulator's overlay display (docs/SIMULATOR.md).
 *
 * Listens on device-loopback [PORT]; the PC reaches it through `adb forward`. Line protocol,
 * one command per line, each answered with `ok` or `err <reason>`:
 *
 *  - `pose <yawDeg> <pitchDeg> [rollDeg]` — absolute head orientation; positive yaw looks
 *    right, positive pitch looks up.
 *  - `dof on|off` — keep the fake pose stream live (the default, so FREE mode is available)
 *    or let it drop.
 *  - `mode free|pinned` — workspace view mode.
 *  - `recenter` — the toolbar's align-to-head.
 *  - `layout` — cycle the multi-screen layout (FREE mode only).
 *  - `panel drawer|settings|audio|notifications|quick|close` — open a desktop panel on the
 *    main screen.
 *  - `launch <package>` — open an app's launcher activity as a workspace window.
 *  - `move <dx> <dy>` — cursor delta as a fraction of the touchpad (like a trackpad swipe).
 *  - `click`, `rclick`, `scroll <dy>`.
 *  - `pair <code>` — the Wireless debugging pairing code, as if typed into the pairing
 *    notification; replies once pairing finishes. `state` — the privileged helper's state.
 *
 * On the emulator it also owns the captured mouse's middle button (the emulator's mouse
 * capture swallows host keys, so no keyboard modifier can do this): a single middle-click
 * hands the mouse to the phone-side toolbar, and a double middle-click toggles look mode, in
 * which mouse motion turns the head and the wheel zooms.
 *
 * Started by this provider's [onCreate], which runs at process start before the
 * Application — so it needs no hook in the main source set. Release builds never contain it.
 */
class SimulatorBridge : ContentProvider() {

    /** Simulated head orientation in degrees; see [quaternion] for the conventions. */
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f
    @Volatile private var roll = 0f

    /** `dof off` — stop [keepDofLive] from re-marking the fake pose stream live. */
    @Volatile private var dofOff = false

    /** Look mode: mouse motion turns the head, the wheel zooms. Main thread only. */
    private var lookMode = false

    /** Raw ticks moved since the middle button went down, or -1 while it is up. */
    private var middleMotion = -1

    /** A single middle-click waiting out [DOUBLE_CLICK_MS] before it releases the mouse. */
    private val pendingRelease = Runnable {
        releasePending = false
        PrivilegedService.releasePointers()
    }
    private var releasePending = false

    override fun onCreate(): Boolean {
        thread(name = "uxspace-sim", isDaemon = true) { serve() }
        // Posted, so both run after UxSpaceApp.onCreate has installed its handlers.
        main.post(keepDofLive)
        if (IS_EMULATOR) main.post(::installMouseLook)
        return true
    }

    /**
     * There are no glasses, so nothing ever reports DOF; without it FREE mode is grayed out.
     * Mark the fake stream live (with the current pose) whenever a workspace is showing.
     */
    private val keepDofLive: Runnable = object : Runnable {
        override fun run() {
            if (!dofOff && WorkspaceController.isRunning && !WorkspaceController.headTrackingActive) {
                applyPose()
                WorkspaceController.headTrackingActive = true
            }
            main.postDelayed(this, DOF_CHECK_MS)
        }
    }

    /**
     * Wrap the helper's raw mouse handlers with the middle-button gestures. A click is a
     * press + release that moved under [CLICK_SLOP] ticks; the first click waits
     * [DOUBLE_CLICK_MS] so a second one can turn it into a double.
     */
    private fun installMouseLook() {
        val buttons = PrivilegedService.mouseButtonHandler
        val deltas = PrivilegedService.mouseDeltaHandler
        PrivilegedService.mouseButtonHandler = { code, pressed ->
            if (code == BTN_MIDDLE) {
                if (pressed) {
                    middleMotion = 0
                } else {
                    if (middleMotion in 0 until CLICK_SLOP) onMiddleClick()
                    middleMotion = -1
                }
            }
            buttons?.invoke(code, pressed)
        }
        PrivilegedService.mouseDeltaHandler = { dx, dy, wheel ->
            if (middleMotion >= 0) middleMotion += abs(dx) + abs(dy) + abs(wheel) * CLICK_SLOP
            if (lookMode) {
                if (dx != 0 || dy != 0) {
                    yaw = ((yaw + dx * LOOK_DEG_PER_TICK) + 540f) % 360f - 180f
                    pitch = (pitch - dy * LOOK_DEG_PER_TICK).coerceIn(-89f, 89f)
                    applyPose()
                }
                if (wheel != 0) {
                    zoomLogPending += wheel * ZOOM_LOG_PER_TICK
                    if (!zoomEasing) {
                        zoomEasing = true
                        main.post(zoomEase)
                    }
                }
            } else {
                deltas?.invoke(dx, dy, wheel)
            }
        }
    }

    /** Look-mode zoom still to apply, as ln(scale); [zoomEase] drains it over a few frames. */
    private var zoomLogPending = 0f
    private var zoomEasing = false

    /**
     * Apply a fraction of the pending wheel zoom per frame, so it glides instead of jumping a
     * whole notch at once. Faster scrolling piles up more pending zoom, so it zooms faster.
     */
    private val zoomEase: Runnable = object : Runnable {
        override fun run() {
            val step = if (abs(zoomLogPending) < ZOOM_LOG_EPSILON) zoomLogPending
                       else zoomLogPending * ZOOM_EASE_FRACTION
            zoomLogPending -= step
            if (step != 0f) WorkspaceController.pinch(exp(step))
            if (zoomLogPending != 0f) main.postDelayed(this, FRAME_MS) else zoomEasing = false
        }
    }

    private fun onMiddleClick() {
        if (releasePending) {
            main.removeCallbacks(pendingRelease)
            releasePending = false
            lookMode = !lookMode
            WorkspaceController.announceInView(if (lookMode) "Look mode on" else "Look mode off")
        } else {
            releasePending = true
            main.postDelayed(pendingRelease, DOUBLE_CLICK_MS)
        }
    }

    private fun applyPose() {
        val (w, x, y, z) = quaternion(yaw, pitch, roll)
        WorkspaceController.injectHeadPose(w, x, y, z)
    }

    private fun serve() {
        val server = runCatching {
            ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
        }.getOrElse {
            Log.e(TAG, "simulator bridge could not bind 127.0.0.1:$PORT", it)
            return
        }
        Log.i(TAG, "simulator bridge listening on 127.0.0.1:$PORT")
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            thread(name = "uxspace-sim-client", isDaemon = true) { handle(client) }
        }
    }

    private fun handle(socket: Socket) = socket.use {
        val reader = BufferedReader(InputStreamReader(it.getInputStream()))
        val writer = PrintWriter(it.getOutputStream(), true)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val reply = runCatching { execute(line.trim().split(Regex("\\s+"))) }
                .getOrElse { e -> "err ${e.message}" }
            writer.println(reply)
        }
    }

    private fun execute(args: List<String>): String {
        fun f(i: Int, default: Float? = null): Float =
            args.getOrNull(i)?.toFloat() ?: default ?: error("missing argument ${i}")
        when (args[0]) {
            "pose" -> {
                if (!WorkspaceController.isRunning) return "err workspace not showing"
                yaw = f(1); pitch = f(2); roll = f(3, 0f)
                applyPose()
                if (!dofOff && !WorkspaceController.headTrackingActive) {
                    WorkspaceController.headTrackingActive = true
                }
            }
            "dof" -> {
                dofOff = when (args.getOrNull(1)) {
                    "on" -> false
                    "off" -> true
                    else -> return "err usage: dof on|off"
                }
                WorkspaceController.headTrackingActive = !dofOff
            }
            "mode" -> {
                val mode = when (args.getOrNull(1)) {
                    "free" -> WorkspaceRenderer.ViewMode.FREE
                    "pinned" -> WorkspaceRenderer.ViewMode.PINNED
                    else -> return "err usage: mode free|pinned"
                }
                main.post { WorkspaceController.setViewMode(mode) }
            }
            "recenter" -> main.post { WorkspaceController.alignVerticalToHead() }
            "layout" -> main.post { WorkspaceController.cycleLayout() }
            "panel" -> {
                val open: () -> Unit = when (args.getOrNull(1)) {
                    "drawer" -> { { WorkspaceController.setDrawerOpen(true) } }
                    "settings" -> { { WorkspaceController.setSettingsOpen(true) } }
                    "audio" -> { { WorkspaceController.setAudioOpen(true) } }
                    "notifications" -> { { WorkspaceController.setTrayPanel(WorkspaceController.TrayPanel.NOTIFICATIONS) } }
                    "quick" -> { { WorkspaceController.setTrayPanel(WorkspaceController.TrayPanel.QUICK_SETTINGS) } }
                    "close" -> { {
                        WorkspaceController.setDrawerOpen(false)
                        WorkspaceController.setSettingsOpen(false)
                        WorkspaceController.setAudioOpen(false)
                        WorkspaceController.setTrayPanel(null)
                    } }
                    else -> return "err usage: panel drawer|settings|audio|notifications|quick|close"
                }
                main.post(open)
            }
            "launch" -> {
                val pkg = args.getOrNull(1) ?: return "err usage: launch <package>"
                if (BuiltInApps.isBuiltIn(pkg)) {
                    main.post { BuiltInApps.launch(pkg, 0) }
                    return "ok"
                }
                val launch = context?.packageManager?.getLaunchIntentForPackage(pkg)?.component
                    ?: return "err no launcher activity for $pkg"
                main.post { WorkspaceController.launchApp(pkg, launch.className, pkg.substringAfterLast('.')) }
            }
            "move" -> WorkspaceController.moveCursor(f(1), f(2))
            "click" -> WorkspaceController.click()
            "rclick" -> WorkspaceController.requestRightClick()
            "scroll" -> WorkspaceController.scroll(f(1))
            "pair" -> {
                val code = args.getOrNull(1)?.filter(Char::isDigit)
                if (code?.length != 6) return "err usage: pair <6-digit code>"
                val result = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                PrivilegedService.activate(code) { ok -> result.offer(ok) }
                val ok = result.poll(30, java.util.concurrent.TimeUnit.SECONDS)
                return if (ok == true) "ok paired" else "err pairing failed (ok=$ok)"
            }
            "state" -> return "ok ${PrivilegedService.state}"
            else -> return "err unknown command '${args[0]}'"
        }
        return "ok"
    }

    /**
     * Yaw (about +Y), then pitch (about +X), then roll (about −Z) — degrees in, (w, x, y, z)
     * out. GL looks down −Z, so a positive rotation about +Y turns the view left; yaw is
     * negated to make positive yaw look right.
     */
    private fun quaternion(yawDeg: Float, pitchDeg: Float, rollDeg: Float): FloatArray {
        fun half(deg: Float) = Math.toRadians(deg.toDouble()).toFloat() / 2f
        val hy = half(-yawDeg); val hp = half(pitchDeg); val hr = half(-rollDeg)
        // qYaw ⊗ qPitch
        val cy = cos(hy); val sy = sin(hy); val cp = cos(hp); val sp = sin(hp)
        val w1 = cy * cp; val x1 = cy * sp; val y1 = sy * cp; val z1 = -sy * sp
        // ⊗ qRoll (about Z)
        val cr = cos(hr); val sr = sin(hr)
        return floatArrayOf(
            w1 * cr - z1 * sr,
            x1 * cr + y1 * sr,
            y1 * cr - x1 * sr,
            z1 * cr + w1 * sr,
        )
    }

    private val main = Handler(Looper.getMainLooper())

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0

    private companion object {
        const val TAG = "UxSpace/Sim"
        /** Device-side port; `adb forward tcp:47800 tcp:47800` reaches it from the PC. */
        const val PORT = 47800

        /** How often [keepDofLive] checks for a (re)shown workspace. */
        const val DOF_CHECK_MS = 1000L

        /** Head turn per raw mouse tick in look mode. */
        const val LOOK_DEG_PER_TICK = 0.15f

        /** Raw ticks a middle press may move (a wheel notch counts as all of them) and still click. */
        const val CLICK_SLOP = 12

        /** Max gap between the two middle-clicks of a double-click. */
        const val DOUBLE_CLICK_MS = 350L

        /**
         * Look-mode zoom per raw wheel tick, as ln(scale). The emulator's virtio wheel reports
         * 8 ticks per notch, so this is about 6% per notch.
         */
        const val ZOOM_LOG_PER_TICK = 0.0073f

        /** Share of the pending zoom [zoomEase] applies each frame. */
        const val ZOOM_EASE_FRACTION = 0.3f

        /** Below this much pending ln(scale), apply the rest at once. */
        const val ZOOM_LOG_EPSILON = 0.002f

        const val FRAME_MS = 16L

        /** Kernel BTN_MIDDLE, as the helper reports it. */
        const val BTN_MIDDLE = 274

        val IS_EMULATOR = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
    }
}
