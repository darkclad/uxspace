package com.uxspace.sim

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug-build-only simulator control channel: lets the PC fake the glasses' head pose and
 * poke the workspace while it runs on an emulator's overlay display (docs/SIMULATOR.md).
 *
 * Listens on device-loopback [PORT]; the PC reaches it through `adb forward`. Line protocol,
 * one command per line, each answered with `ok` or `err <reason>`:
 *
 *  - `pose <yawDeg> <pitchDeg> [rollDeg]` — absolute head orientation; positive yaw looks
 *    right, positive pitch looks up. The first pose marks DOF live (ungrays FREE mode).
 *  - `dof on|off` — mark the fake pose stream live / dead.
 *  - `mode free|pinned` — workspace view mode.
 *  - `recenter` — the toolbar's align-to-head.
 *  - `layout` — cycle the multi-screen layout (FREE mode only).
 *  - `panel drawer|settings|audio|close` — open a desktop panel on the main screen.
 *  - `launch <package>` — open an app's launcher activity as a workspace window.
 *  - `move <dx> <dy>` — cursor delta as a fraction of the touchpad (like a trackpad swipe).
 *  - `click`, `rclick`, `scroll <dy>`.
 *  - `pair <code>` — the Wireless debugging pairing code, as if typed into the pairing
 *    notification; replies once pairing finishes. `state` — the privileged helper's state.
 *
 * Started by this provider's [onCreate], which runs at process start before the
 * Application — so it needs no hook in the main source set. Release builds never contain it.
 */
class SimulatorBridge : ContentProvider() {

    override fun onCreate(): Boolean {
        thread(name = "uxspace-sim", isDaemon = true) { serve() }
        return true
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
                val (w, x, y, z) = quaternion(f(1), f(2), f(3, 0f))
                WorkspaceController.injectHeadPose(w, x, y, z)
                if (!WorkspaceController.headTrackingActive) WorkspaceController.headTrackingActive = true
            }
            "dof" -> WorkspaceController.headTrackingActive = when (args.getOrNull(1)) {
                "on" -> true
                "off" -> false
                else -> return "err usage: dof on|off"
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
                    "close" -> { {
                        WorkspaceController.setDrawerOpen(false)
                        WorkspaceController.setSettingsOpen(false)
                        WorkspaceController.setAudioOpen(false)
                    } }
                    else -> return "err usage: panel drawer|settings|audio|close"
                }
                main.post(open)
            }
            "launch" -> {
                val pkg = args.getOrNull(1) ?: return "err usage: launch <package>"
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
    }
}
