package com.uxspace.privileged

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * UxSpace's shell-uid privileged helper — runs `am` / `input`, creates the workspace's
 * trusted virtual displays, and hands itself back to the app over a Binder. It exists in
 * two activation modes, both passing through this same class:
 *
 *  - **Shizuku** (transitional). Shizuku binds it as a user service; the AIDL Stub is
 *    delivered through Shizuku's `bindUserService` callback.
 *  - **UxSpace's own bootstrap** (`docs/PRIVILEGE.md`). `app_process` invokes [main], the
 *    server is constructed in the shell-uid process, and its Binder is handed to the app
 *    through [BinderReceiverProvider].
 *
 * From either entry point the privileged work is identical — only the start-up path
 * differs. `am`/`input` are shell-outs because shell uid may not call `ActivityTaskManager`
 * directly; `createVirtualDisplay` uses the `DisplayManager` from a `com.android.shell`
 * package context so the call passes `DisplayManagerService`'s package/uid check.
 */
class PrivilegedServer() : IPrivilegedService.Stub() {

    /** A Context — Shizuku-provided in the user-service path, system-context in [main]. */
    private var context: Context? = null

    /** Trusted virtual displays created for the workspace, keyed by display id. */
    private val virtualDisplays = HashMap<Int, VirtualDisplay>()

    /** Shizuku instantiates the user service with this constructor when a Context is available. */
    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context
    }

    /** Used by [main] to set the system context once the ActivityThread is up. */
    internal fun setContext(context: Context) {
        this.context = context
    }

    override fun destroy() {
        releaseAllDisplays()
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Int {
        val displayManager = displayManager()
        if (displayManager == null) {
            Log.e(TAG, "createVirtualDisplay: no DisplayManager (context unavailable)")
            return -1
        }
        return try {
            val display = displayManager.createVirtualDisplay(
                name, width, height, densityDpi, surface, TRUSTED_DISPLAY_FLAGS,
            )
            if (display == null) {
                Log.e(TAG, "createVirtualDisplay returned null")
                return -1
            }
            val id = display.display.displayId
            synchronized(virtualDisplays) { virtualDisplays[id] = display }
            Log.i(TAG, "trusted virtual display created id=$id ${width}x$height '$name'")
            id
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            -1
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        val display = synchronized(virtualDisplays) { virtualDisplays.remove(displayId) }
        if (display != null) {
            runCatching { display.release() }
            Log.i(TAG, "trusted virtual display released id=$displayId")
        }
    }

    private fun releaseAllDisplays() {
        val displays = synchronized(virtualDisplays) {
            virtualDisplays.values.toList().also { virtualDisplays.clear() }
        }
        displays.forEach { runCatching { it.release() } }
    }

    /**
     * A DisplayManager for creating the virtual display.
     *
     * The display must be created under the package that owns this process's uid (shell) —
     * `DisplayManagerService` rejects a mismatch with "packageName must match the calling
     * uid". A Shizuku-provided or app context carries the *app's* package (`com.uxspace`),
     * so the display is created from a `com.android.shell` package context instead.
     */
    private fun displayManager(): DisplayManager? {
        val base = baseContext() ?: return null
        val shellContext = runCatching {
            base.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { Log.e(TAG, "could not create a $SHELL_PACKAGE context", it) }.getOrNull()
            ?: return null
        return shellContext.getSystemService(DisplayManager::class.java)
    }

    /** The provided context, or this process's system context fetched reflectively. */
    private fun baseContext(): Context? {
        context?.let { return it }
        val ctx = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(current) as Context
        }.onFailure { Log.e(TAG, "could not obtain a system context", it) }.getOrNull()
        context = ctx
        return ctx
    }

    override fun launchOnDisplay(
        displayId: Int,
        packageName: String,
        activityName: String,
    ): Boolean {
        Log.i(
            "UxSpace/Launch",
            "11) helper.launchOnDisplay pkg=$packageName/$activityName display=$displayId",
        )
        val ok = runVerbose(
            "am", "start",
            "--display", displayId.toString(),
            // 1 = WINDOWING_MODE_FULLSCREEN — the activity fills its own bare trusted
            // display; UxSpace draws the surrounding window chrome itself. Freeform
            // mode (5) was used before per-app displays existed; it makes Samsung One
            // UI add its own freeform title bar (grey strip with a blue drag handle)
            // above the activity, which now stacks against our chrome and looks broken.
            "--windowingMode", "1",
            "-n", "$packageName/$activityName",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER",
            // NEW_TASK | MULTIPLE_TASK as raw flags. EXCLUDE_FROM_RECENTS is split out
            // into am's high-level `--activity-exclude-from-recents` argument because
            // bundling 0x00800000 into the raw -f blob broke launches on a secondary
            // trusted display — the activity record was created on the right display
            // but never produced a visible frame, leaving the taskbar with an icon
            // but the workspace showing only wallpaper.
            "-f", FLAG_NEW_TASK_MULTIPLE,
            "--activity-exclude-from-recents",
        )
        Log.i("UxSpace/Launch", "12) am-start returned ok=$ok display=$displayId")
        // ~600 ms after the launch, dump the activity stack for this display so we can
        // see whether the activity actually landed where we asked it to. The dumpsys
        // call is cheap; this only fires once per launch.
        scheduleDisplayDump(displayId, packageName)
        return ok
    }

    /**
     * Fire a one-shot delayed dump of `dumpsys activity activities` filtered to a
     * specific display id, so we can see post-launch what's actually on that display.
     * Helps diagnose the "icon shown in taskbar but no window" symptom — if the
     * dump shows the activity on a different display (or not at all), we know the
     * launch routing went wrong.
     */
    private fun scheduleDisplayDump(displayId: Int, packageName: String) {
        Thread {
            try {
                Thread.sleep(600)
                val dump = runCapture("dumpsys", "activity", "activities") ?: return@Thread
                val lines = dump.lineSequence().toList()
                var inDisplay = false
                var matched = 0
                val out = StringBuilder()
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.startsWith("Display #")) {
                        val num = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                        inDisplay = num == displayId
                        if (inDisplay) out.appendLine(line)
                    } else if (inDisplay && (
                            line.contains("ActivityRecord{") ||
                                line.contains("Stack ") ||
                                line.contains("RootTask ") ||
                                line.startsWith("* Task")
                            )
                    ) {
                        out.appendLine("    $line")
                        matched++
                    }
                }
                Log.i(
                    "UxSpace/Launch",
                    "13) dumpsys display=$displayId activities=$matched for pkg=$packageName\n" +
                        if (out.isEmpty()) "    (no display section found)" else out.toString().trimEnd(),
                )
            } catch (_: InterruptedException) {
                // Helper shutting down — fine.
            }
        }.start()
    }

    /**
     * Like [run] but always logs stdout and stderr separately on completion — used for
     * the launch path so we can see exactly what `am start` printed. The other shell-out
     * call-sites (input tap, swipe, key, force-stop) stay on [run] which logs only when
     * something printed or the exit code was non-zero, to keep input logs quiet.
     */
    private fun runVerbose(vararg command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            Log.i(TAG, "[$exit] ${command.joinToString(" ")}")
            if (stdout.isNotEmpty()) Log.i(TAG, "  stdout: $stdout")
            if (stderr.isNotEmpty()) Log.i(TAG, "  stderr: $stderr")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            false
        }
    }

    override fun tap(displayId: Int, x: Int, y: Int) {
        run("input", "-d", displayId.toString(), "tap", x.toString(), y.toString())
    }

    override fun swipe(
        displayId: Int,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Int,
    ) {
        run(
            "input", "-d", displayId.toString(), "swipe",
            fromX.toString(), fromY.toString(), toX.toString(), toY.toString(),
            durationMs.toString(),
        )
    }

    override fun key(displayId: Int, keyCode: Int) {
        run("input", "-d", displayId.toString(), "keyevent", keyCode.toString())
    }

    override fun text(displayId: Int, value: String) {
        run("input", "-d", displayId.toString(), "text", value)
    }

    override fun forceStop(packageName: String) {
        // Closes the app's windows and kills its process — so releasing the virtual display
        // it ran on has no live activity left to relocate onto the phone's screen.
        run("am", "force-stop", packageName)
    }

    /**
     * Two-finger pinch on [displayId], centred at (centerX, centerY), pointer spread
     * going from [fromSpan] to [toSpan] over [durationMs]. Builds a MotionEvent sequence
     * (DOWN, POINTER_DOWN, MOVEs, POINTER_UP, UP) and submits it through `InputManager`
     * directly — `input` only does a single pointer.
     *
     * Reflective access to the runtime-selected InputManager backend and
     * `injectInputEvent(...)` — the APIs are hidden but accessible from the shell uid this
     * process runs as (shell has `INJECT_EVENTS`).
     */
    override fun pinchOnDisplay(
        displayId: Int,
        centerX: Int,
        centerY: Int,
        fromSpan: Int,
        toSpan: Int,
        durationMs: Int,
    ) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "pinch: InputManager unavailable")
                return
            }
            val steps = (durationMs / PINCH_STEP_MS).coerceAtLeast(3)
            val downAt = SystemClock.uptimeMillis()
            // The two pointers move horizontally apart from / together to the centre.
            fun pointAtStep(step: Int): Pair<FloatArray, FloatArray> {
                val t = step.toFloat() / steps
                val span = fromSpan + (toSpan - fromSpan) * t
                val half = span / 2f
                return floatArrayOf(centerX - half, centerY.toFloat()) to
                    floatArrayOf(centerX + half, centerY.toFloat())
            }
            val (start0, start1) = pointAtStep(0)
            injectMotionEvent(injector, displayId, downAt, downAt, MotionEvent.ACTION_DOWN,
                start0, null)
            injectMotionEvent(
                injector, displayId, downAt, downAt,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                start0, start1,
            )
            for (step in 1..steps) {
                val (p0, p1) = pointAtStep(step)
                val t = downAt + step.toLong() * PINCH_STEP_MS
                injectMotionEvent(injector, displayId, downAt, t, MotionEvent.ACTION_MOVE, p0, p1)
            }
            val (end0, end1) = pointAtStep(steps)
            val finalT = downAt + steps.toLong() * PINCH_STEP_MS
            injectMotionEvent(
                injector, displayId, downAt, finalT,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                end0, end1,
            )
            injectMotionEvent(injector, displayId, downAt, finalT, MotionEvent.ACTION_UP, end0, null)
        } catch (t: Throwable) {
            Log.e(TAG, "pinch failed", t)
        }
    }

    /**
     * Streamed touch injection: one [ACTION_DOWN] / [ACTION_MOVE] / [ACTION_UP] /
     * [ACTION_CANCEL] motion event at a time on the target display. The down timestamp
     * is latched on `DOWN` and reused for the rest of the sequence, so the app sees a
     * coherent gesture (which is required by Android's input dispatching).
     */
    /**
     * Force the VITURE glasses' USB device to re-enumerate. Locates the device by
     * walking `/sys/bus/usb/devices/` for a `idVendor` that reads `35ca`; if found,
     * writes the device's bus-port identifier (the directory name, e.g. `1-1.2`) to
     * the USB driver's `unbind` file, sleeps briefly, then writes it to `bind`. The
     * kernel re-enumerates the device which then fires `USB_DEVICE_ATTACHED` back to
     * the app. Both writes need shell-uid sysfs write permission (which we have).
     *
     * Triggered from the app-side DOF-stall watchdog when the SDK reports the
     * pose stream has been quiet for several seconds — empirically the BT-keyboard
     * pair sometimes wedges the Carina endpoint until something tickles the bus.
     */
    override fun rescanGlassesUsb() {
        try {
            val devicesRoot = File("/sys/bus/usb/devices")
            val devices = devicesRoot.listFiles() ?: run {
                Log.w(TAG, "rescanGlassesUsb: /sys/bus/usb/devices unreadable")
                return
            }
            var matched = 0
            for (dev in devices) {
                val vendor = runCatching { File(dev, "idVendor").readText().trim() }
                    .getOrNull() ?: continue
                if (vendor != VITURE_VID_HEX) continue
                val busPort = dev.name
                matched++
                Log.i(TAG, "rescanGlassesUsb: rescanning $busPort (idVendor=$vendor)")
                if (rescanViaAuthorized(dev) || rescanViaDriverUnbind(busPort)) {
                    Log.i(TAG, "rescanGlassesUsb: $busPort rescan dispatched")
                } else {
                    Log.w(
                        TAG,
                        "rescanGlassesUsb: every rescan path failed for $busPort " +
                            "— shell uid likely lacks sysfs write permission on this OEM",
                    )
                }
            }
            if (matched == 0) Log.w(TAG, "rescanGlassesUsb: no VITURE device under /sys/bus/usb/devices")
        } catch (t: Throwable) {
            Log.e(TAG, "rescanGlassesUsb failed", t)
        }
    }

    /**
     * Toggle `authorized` from 1 → 0 → 1 on the device's sysfs node. On Samsung this
     * file is sometimes group-writable by `usb`, which shell is a member of — so it
     * works where `/sys/bus/usb/drivers/usb/unbind` doesn't. Same end effect: kernel
     * tears down the device, then re-enumerates.
     */
    private fun rescanViaAuthorized(devDir: File): Boolean {
        val authorized = File(devDir, "authorized")
        if (!authorized.exists()) return false
        return try {
            authorized.writeText("0")
            try { Thread.sleep(USB_REBIND_GAP_MS) } catch (_: InterruptedException) {}
            authorized.writeText("1")
            Log.i(TAG, "rescanViaAuthorized: ${devDir.name} toggled")
            true
        } catch (e: Exception) {
            Log.d(TAG, "rescanViaAuthorized failed for ${devDir.name}: ${e.message}")
            false
        }
    }

    /**
     * Classic unbind/bind via the usb driver. Requires write access to
     * `/sys/bus/usb/drivers/usb/{unbind,bind}` which is normally root-only; mentioned
     * for completeness and to keep a fallback path documented.
     */
    private fun rescanViaDriverUnbind(busPort: String): Boolean {
        return try {
            File("/sys/bus/usb/drivers/usb/unbind").writeText(busPort)
            try { Thread.sleep(USB_REBIND_GAP_MS) } catch (_: InterruptedException) {}
            File("/sys/bus/usb/drivers/usb/bind").writeText(busPort)
            Log.i(TAG, "rescanViaDriverUnbind: $busPort rebound")
            true
        } catch (e: Exception) {
            Log.d(TAG, "rescanViaDriverUnbind failed for $busPort: ${e.message}")
            false
        }
    }

    override fun injectTouch(displayId: Int, x: Int, y: Int, action: Int) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "touch: InputManager unavailable")
                return
            }
            val androidAction = when (action) {
                0 -> MotionEvent.ACTION_DOWN
                1 -> MotionEvent.ACTION_MOVE
                2 -> MotionEvent.ACTION_UP
                3 -> MotionEvent.ACTION_CANCEL
                else -> {
                    Log.e(TAG, "touch: unknown action $action")
                    return
                }
            }
            val now = SystemClock.uptimeMillis()
            val downAt = if (androidAction == MotionEvent.ACTION_DOWN) {
                touchDownAt[displayId] = now
                now
            } else {
                touchDownAt[displayId] ?: now
            }
            val pt = floatArrayOf(x.toFloat(), y.toFloat())
            val ok = injectMotionEvent(injector, displayId, downAt, now, androidAction, pt, null)
            if (!ok) {
                Log.w(TAG, "touch inject FAILED action=$androidAction display=$displayId at ($x,$y)")
            }
            if (androidAction == MotionEvent.ACTION_UP ||
                androidAction == MotionEvent.ACTION_CANCEL) {
                touchDownAt.remove(displayId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "touch injection failed", t)
        }
    }

    /** Per-display down timestamp for a streamed touch sequence. */
    private val touchDownAt = mutableMapOf<Int, Long>()

    /**
     * Inject a one-shot ACTION_SCROLL motion event on the target display — mouse-wheel
     * equivalent, fast, no synthesized touch swipe. Same shape as `UiScreen.dispatchScroll`,
     * routed through `InputManager.injectInputEvent` so the event lands on an
     * out-of-process VirtualDisplay.
     */
    override fun injectScroll(displayId: Int, x: Int, y: Int, vScroll: Float) {
        try {
            val injector = obtainInjector() ?: run {
                Log.e(TAG, "scroll: InputManager unavailable")
                return
            }
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                },
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x.toFloat()
                    this.y = y.toFloat()
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                },
            )
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0,
            )
            event.source = InputDevice.SOURCE_MOUSE
            runCatching {
                event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .invoke(event, displayId)
            }
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC.
            try {
                if (!injector.inject(event, INJECT_INPUT_EVENT_MODE_ASYNC)) {
                    Log.w(TAG, "scroll inject FAILED display=$displayId at ($x,$y)")
                }
            } finally {
                event.recycle()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scroll failed", t)
        }
    }

    /** Build and submit one frame of the pinch — one or two pointers. Returns the
     *  injectInputEvent boolean — true means the event was accepted by the dispatcher. */
    private fun injectMotionEvent(
        injector: InputEventInjector,
        displayId: Int,
        downAt: Long,
        eventAt: Long,
        action: Int,
        p0: FloatArray,
        p1: FloatArray?,
    ): Boolean {
        val count = if (p1 == null) 1 else 2
        val props = Array(count) { idx ->
            MotionEvent.PointerProperties().apply {
                id = idx
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { idx ->
            val src = if (idx == 0) p0 else p1!!
            MotionEvent.PointerCoords().apply {
                x = src[0]
                y = src[1]
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downAt, eventAt, action, count, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        // MotionEvent has setDisplayId since API 30 (hidden in some versions). Read it
        // back so we can tell if the reflection silently failed — that would route the
        // event to display 0 (the phone screen) instead of the virtual display.
        val setOk = runCatching {
            event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }.isSuccess
        val actualDisplayId = runCatching {
            event.javaClass.getMethod("getDisplayId").invoke(event) as? Int
        }.getOrNull() ?: -1
        if (!setOk || actualDisplayId != displayId) {
            Log.w(TAG, "displayId mismatch: setOk=$setOk wanted=$displayId got=$actualDisplayId")
        }
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC. Returns Boolean — false means the
        // dispatcher rejected the event (permission, no window, wrong display, …).
        return try {
            injector.inject(event, INJECT_INPUT_EVENT_MODE_ASYNC)
        } finally {
            event.recycle()
        }
    }

    /** Receiver + method for one platform implementation of hidden input injection. */
    private class InputEventInjector(
        val backendName: String,
        private val receiver: Any,
        private val method: java.lang.reflect.Method,
    ) {
        fun inject(event: InputEvent, mode: Int): Boolean =
            (method.invoke(receiver, event, mode) as? Boolean) == true
    }

    @Volatile
    private var cachedInjector: InputEventInjector? = null

    @Volatile
    private var injectorResolutionAttempted = false

    /**
     * Resolve input injection across Android/OEM implementations, once per helper process.
     *
     * Samsung's tested firmware supports the legacy InputManager singleton, so it remains the
     * first choice. Newer Pixel Android builds removed that static method and expose the same
     * operation through InputManagerGlobal. A Context-provided InputManager is the final bridge
     * for releases which have neither static singleton shape. Capability probing avoids brittle
     * manufacturer checks and preserves the already-working Samsung path unchanged.
     */
    private fun obtainInjector(): InputEventInjector? {
        cachedInjector?.let { return it }
        synchronized(this) {
            cachedInjector?.let { return it }
            if (injectorResolutionAttempted) return null
            injectorResolutionAttempted = true

            val resolution = resolveCapability(
                listOf(
                    CapabilityCandidate("InputManager.getInstance") {
                        val cls = Class.forName("android.hardware.input.InputManager")
                        buildInjector(
                            "InputManager.getInstance",
                            cls.getMethod("getInstance").invoke(null),
                        )
                    },
                    CapabilityCandidate("InputManagerGlobal.getInstance") {
                        val cls = Class.forName("android.hardware.input.InputManagerGlobal")
                        buildInjector(
                            "InputManagerGlobal.getInstance",
                            cls.getMethod("getInstance").invoke(null),
                        )
                    },
                    CapabilityCandidate("Context.getSystemService(input)") {
                        val receiver = baseContext()?.getSystemService(Context.INPUT_SERVICE)
                            ?: error("input service unavailable")
                        buildInjector("Context.getSystemService(input)", receiver)
                    },
                ),
            )
            val injector = resolution.value
            if (injector == null) {
                Log.e(
                    TAG,
                    "no input injection backend on ${Build.MANUFACTURER} ${Build.MODEL} " +
                        "API ${Build.VERSION.SDK_INT}; attempted: ${resolution.failures.joinToString()}",
                )
                return null
            }
            cachedInjector = injector
            Log.i(
                TAG,
                "input injection backend=${injector.backendName} on " +
                    "${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}",
            )
            return injector
        }
    }

    private fun buildInjector(backendName: String, receiver: Any?): InputEventInjector {
        requireNotNull(receiver) { "$backendName returned null" }
        val method = receiver.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        return InputEventInjector(backendName, receiver, method)
    }

    /**
     * Whether [displayId] currently has an activity on it. Used after a Back press to tell
     * whether Back closed the app (so UxSpace can close the now-empty window). Errs on the
     * side of `true` if the dump cannot be read or parsed, so a live app is never closed.
     */
    override fun displayHasActivity(displayId: Int): Boolean {
        val dump = runCapture("dumpsys", "activity", "activities") ?: return true
        if (!dump.contains("ActivityRecord{") || !dump.contains("Display #")) return true
        var inDisplay = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Display #")) {
                val num = line.removePrefix("Display #").takeWhile(Char::isDigit).toIntOrNull()
                inDisplay = num == displayId
            } else if (inDisplay && line.contains("ActivityRecord{")) {
                return true
            }
        }
        return false
    }

    /** Run a shell command and return its standard output, or `null` if it could not run. */
    private fun runCapture(vararg command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            null
        }
    }

    // region Hotkey monitor — mirrors the Windows companion's WH_KEYBOARD_LL global hook.
    //
    // MainActivity runs with FLAG_NOT_FOCUSABLE so it never sees physical key events. The
    // shell uid we run as can read `/dev/input/event*` directly (same path `adb getevent`
    // uses), which gives us every keyboard regardless of focused window or display. We
    // read passively — the OS still delivers the same events to whatever window has
    // focus, so this is a spy, not an interceptor.
    //
    // We track Ctrl + Alt held state and fire a [PrivilegedHotkeys] code to the
    // registered app-side listener on each ACTION_DOWN of a target key. Hot-plug
    // (BT keyboard connects later) is handled via a FileObserver on /dev/input.
    //
    // Modifier choice: Win/Meta was the natural cross-platform combo (matching the
    // Windows app), but Samsung One UI hard-binds Meta to the launcher's app-drawer
    // shortcut. Switching the Android *and* Windows hotkey set to Ctrl+Alt sidesteps
    // both Samsung's launcher and Windows' Meta-key reservations.

    @Volatile private var hotkeyMonitor: HotkeyMonitor? = null

    override fun setHotkeyListener(listener: IPrivilegedHotkeyListener?) {
        synchronized(this) {
            hotkeyMonitor?.stop()
            hotkeyMonitor = null
            if (listener != null) {
                try {
                    hotkeyMonitor = HotkeyMonitor(listener).also { it.start() }
                    Log.i(TAG, "hotkey monitor started")
                } catch (t: Throwable) {
                    Log.e(TAG, "could not start hotkey monitor", t)
                }
            } else {
                Log.i(TAG, "hotkey monitor stopped")
            }
        }
    }

    private class HotkeyMonitor(private val listener: IPrivilegedHotkeyListener) {

        private val readers = CopyOnWriteArrayList<FileInputStream>()
        private val threads = CopyOnWriteArrayList<Thread>()
        @Volatile private var stopped = false
        private val ctrlHeld = AtomicBoolean(false)
        private val altHeld = AtomicBoolean(false)
        private val modsHeld = AtomicBoolean(false)
        private var fileObserver: FileObserver? = null

        fun start() {
            val inputDir = File(INPUT_DIR)
            inputDir.listFiles { f -> f.name.startsWith("event") }?.forEach(::spawnReader)
            fileObserver = newFileObserver(inputDir).also { it.startWatching() }
        }

        fun stop() {
            stopped = true
            runCatching { fileObserver?.stopWatching() }
            readers.forEach { runCatching { it.close() } }
            // Threads exit on EOF when their FD closes; no need to interrupt.
        }

        private fun newFileObserver(dir: File): FileObserver {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(dir, CREATE) {
                    override fun onEvent(event: Int, name: String?) = handleNewDevice(name)
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(dir.absolutePath, CREATE) {
                    override fun onEvent(event: Int, name: String?) = handleNewDevice(name)
                }
            }
        }

        private fun handleNewDevice(name: String?) {
            if (stopped || name == null || !name.startsWith("event")) return
            spawnReader(File(INPUT_DIR, name))
        }

        private fun spawnReader(dev: File) {
            if (stopped) return
            val fis = try {
                FileInputStream(dev)
            } catch (e: Exception) {
                // Most event devices are readable by shell uid via the `input` group;
                // a handful (e.g. accelerometer) may be locked down. Skip silently.
                Log.d(TAG, "hotkey: skip ${dev.name} (${e.message})")
                return
            }
            // Logged at INFO so the user can correlate keyboard attach with
            // any downstream USB / DOF disturbance in a single logcat scroll.
            Log.i(TAG, "hotkey: opened ${dev.name} (${describeInputDevice(dev.name)})")
            // Mice (devices that report EV_REL relative motion) get EVIOCGRAB'd so
            // system_server stops seeing them. Without the grab, clicks land on
            // whatever phone UI sits under the (hidden) system pointer — the home
            // gesture pill, the status bar, anywhere the cursor wanders. With it,
            // the kernel only delivers mouse events to this reader; the workspace
            // cursor + click are the only consumers.
            //
            // Why EVIOCGRAB and not the "proper" Pointer Capture: Pointer Capture
            // requires MainActivity's window to HOLD input focus, which is forbidden —
            // a focused window on display 0 becomes top-focused, so Samsung GameBooster
            // pauses the launched apps on their secondary displays and tears down their
            // input channels (it also ANRs the BT mouse and starves the pseudo-root
            // pairing field; see MainActivity.onCreate). EVIOCGRAB takes the device at
            // the kernel level WITHOUT touching Android's focus system — the only
            // approach compatible with secondary-display apps + the privileged bootstrap.
            // Strategy-gated. The legacy SYSFS detector decides here, at open time.
            // The MOTION detector instead defers to readLoop, which grabs once the
            // node proves itself a pointer from its own event stream (the only path
            // that works for Bluetooth HID mice, whose sysfs caps are unreadable).
            if (MOUSE_GRAB_STRATEGY == MouseGrabStrategy.SYSFS && isMouseDevice(dev.name)) {
                grabExclusive(fis, dev.name)
            }
            readers.add(fis)
            val t = Thread({ readLoop(fis, dev) }, "uxspace-hotkey-${dev.name}").apply {
                isDaemon = true
            }
            threads.add(t)
            t.start()
        }

        /**
         * Legacy detector for [MouseGrabStrategy.SYSFS]. A device reports relative
         * motion (EV_REL bit set in its capabilities bitmap) — that's the kernel's
         * definition of a mouse. Touchscreens use EV_ABS instead, so this filter
         * never accidentally grabs the phone's actual touchscreen and breaks touch
         * input.
         *
         * Caveat (why [MouseGrabStrategy.MOTION] exists): the capabilities file is
         * empty / SELinux-unreadable from shell uid for Bluetooth HID mice, so this
         * returns false for them and their pointer leaks into the system UI. Kept
         * intact so the old behavior can be plugged back in via [MOUSE_GRAB_STRATEGY].
         */
        private fun isMouseDevice(eventName: String): Boolean {
            val cap = runCatching {
                File("/sys/class/input/$eventName/device/capabilities/ev")
                    .readText().trim()
            }.getOrNull() ?: return false
            val capLong = runCatching { java.lang.Long.parseLong(cap, 16) }.getOrNull() ?: return false
            // bit 2 = EV_REL.
            return (capLong and 0x4L) != 0L
        }

        /**
         * New pluggable mouse detector for [MouseGrabStrategy.MOTION]. Identifies a
         * pointer by what it actually emits rather than by sysfs metadata or device
         * name — so it works for any mouse on any bus (USB or Bluetooth), for combo
         * HID devices whose pointer is a separate evdev node, and needs no sysfs.
         *
         * A node is declared a pointer once it has reported two-axis relative motion
         * (REL_X *and* REL_Y) or pressed a mouse button. Requiring *both* axes is
         * deliberate: this very phone has sensors (ambient-light `als_rear`, the grip
         * sensors) that abuse REL_X as a data channel — they emit REL_X but never
         * REL_Y, so they are correctly never grabbed. Stateful; one per reader thread.
         */
        private class MotionMouseDetector {
            private var sawRelX = false
            private var sawRelY = false

            /** Feed one parsed evdev tuple; returns true once the node looks like a pointer. */
            fun observe(type: Int, code: Int, value: Int): Boolean {
                if (type == EV_KEY && value != 0 &&
                    (code == BTN_LEFT || code == BTN_RIGHT || code == BTN_MIDDLE)
                ) {
                    return true
                }
                if (type == EV_REL) {
                    if (code == REL_X) sawRelX = true
                    else if (code == REL_Y) sawRelY = true
                }
                return sawRelX && sawRelY
            }
        }

        /** Which detector gates the EVIOCGRAB. Switchable so the new MOTION layer can
         *  be A/B'd against the legacy SYSFS one and reverted instantly. */
        private enum class MouseGrabStrategy { SYSFS, MOTION }

        /**
         * Call EVIOCGRAB(1) on the device's FD so the kernel routes its events
         * exclusively to this reader. system_server (the input dispatcher behind
         * the on-screen cursor + click routing) stops receiving anything from
         * this device until the FD is closed or EVIOCGRAB(0) releases it. The
         * grab is dropped automatically when [stop] closes the FD.
         */
        private fun grabExclusive(fis: FileInputStream, devName: String) {
            // EVIOCGRAB(1) takes a non-null arg pointer to "grab"; ioctlInt passes
            // &arg, which the evdev handler reads as non-null → exclusive grab.
            //
            // ioctlInt is @hide and — critically — is NOT a member of the public
            // `android.system.Os` facade at all (getMethod there throws
            // NoSuchMethodException, which silently defeated the grab and let the
            // pointer roam). It lives on the internal `libcore.io.Os` interface,
            // whose singleton is `libcore.io.Libcore.os`. Shell uid bypasses the
            // hidden-API blocklist so reflection reaches it. Try that first, then
            // fall back to a declared-method lookup on android.system.Os in case a
            // future build relocates it.
            val fd = fis.fd
            try {
                val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
                // Find ioctlInt on the *concrete* Os impl (BlockGuardOs/ForwardingOs/
                // Linux), where it actually lives — the public `libcore.io.Os`
                // interface and `android.system.Os` facade don't expose it on this
                // build, which is why the earlier interface lookups failed.
                val ioctlInt = os.javaClass.methods.firstOrNull { it.name == "ioctlInt" }
                if (ioctlInt == null) {
                    val ioctlLike = os.javaClass.methods
                        .filter { it.name.contains("ioctl", ignoreCase = true) }
                        .joinToString("; ") { m ->
                            m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")"
                        }
                    Log.w(
                        TAG,
                        "hotkey: no ioctlInt on ${os.javaClass.name} — ioctl-like methods: [$ioctlLike]",
                    )
                    Log.w(TAG, "hotkey: EVIOCGRAB unavailable for $devName — system pointer will still roam")
                    return
                }
                // Build args positionally by parameter type: the FileDescriptor, the
                // request code (first int = EVIOCGRAB), and the arg pointer (MutableInt
                // holding 1, or a second int = 1) — non-null/non-zero means "grab".
                var sawCmd = false
                val args = ioctlInt.parameterTypes.map { p ->
                    when {
                        p == java.io.FileDescriptor::class.java -> fd
                        p == android.util.MutableInt::class.java -> android.util.MutableInt(1)
                        p == Integer.TYPE && !sawCmd -> { sawCmd = true; EVIOCGRAB }
                        p == Integer.TYPE -> 1
                        else -> null
                    }
                }.toTypedArray()
                ioctlInt.invoke(os, *args)
                Log.i(
                    TAG,
                    "hotkey: EVIOCGRAB ok for $devName via " +
                        "ioctlInt(${ioctlInt.parameterTypes.joinToString { it.simpleName }})",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "hotkey: EVIOCGRAB failed for $devName: ${t.message}")
            }
        }

        /**
         * Map an event-N node to its human-readable device name by reading
         * `/sys/class/input/eventN/device/name` — the sysfs surface for input devices.
         * `/proc/bus/input/devices` would be richer but it's `EACCES` from shell uid on
         * recent Android; sysfs files are world-readable, so this path actually works.
         * Returns "?" / a short failure string when the file isn't present.
         */
        private fun describeInputDevice(eventName: String): String {
            return runCatching {
                File("/sys/class/input/$eventName/device/name").readText().trim()
            }.getOrElse { "name unavailable: ${it.message}" }
        }

        private fun readLoop(fis: FileInputStream, dev: File) {
            val buf = ByteArray(INPUT_EVENT_SIZE)
            // MOTION strategy: identify the pointer from its own event stream and
            // EVIOCGRAB on first proof. Null under the legacy SYSFS strategy, which
            // already decided (and grabbed, or not) at open time.
            val pointerProbe =
                if (MOUSE_GRAB_STRATEGY == MouseGrabStrategy.MOTION) MotionMouseDetector() else null
            var motionGrabbed = false
            try {
                while (!stopped) {
                    var read = 0
                    while (read < buf.size) {
                        val n = fis.read(buf, read, buf.size - read)
                        if (n <= 0) return
                        read += n
                    }
                    handle(buf)
                    if (pointerProbe != null && !motionGrabbed && pointerProbe.observe(
                            u16le(buf, OFFSET_TYPE), u16le(buf, OFFSET_CODE), i32le(buf, OFFSET_VALUE),
                        )
                    ) {
                        Log.i(TAG, "hotkey: ${dev.name} proved a pointer — EVIOCGRAB (motion strategy)")
                        grabExclusive(fis, dev.name)
                        motionGrabbed = true
                    }
                }
            } catch (_: IOException) {
                // Closed (stop()) or device unplugged — exit cleanly.
            } catch (t: Throwable) {
                Log.w(TAG, "hotkey: ${dev.name} reader error", t)
            } finally {
                readers.remove(fis)
                runCatching { fis.close() }
            }
        }

        /**
         * Recompute the combined Ctrl+Alt held state and, on a transition, fire the
         * synthetic [PrivilegedHotkeys.HK_MODIFIERS_DOWN] / `_UP` hotkey so the app can
         * surface its keymap-legend overlay while the modifier pair is held. Called from
         * the key event handler whenever either Ctrl or Alt changes state.
         */
        private fun updateModifiersHeld() {
            val both = ctrlHeld.get() && altHeld.get()
            if (modsHeld.compareAndSet(!both, both)) {
                val code = if (both) PrivilegedHotkeys.HK_MODIFIERS_DOWN
                           else      PrivilegedHotkeys.HK_MODIFIERS_UP
                try {
                    listener.onHotkey(code)
                } catch (_: RemoteException) {
                    stop()
                }
            }
        }

        // Per-reader-thread accumulators for batched mouse motion. EV_REL events
        // come one axis at a time (separate REL_X and REL_Y), terminated by an
        // EV_SYN_REPORT to mark "frame ready". We sum within a frame and flush
        // on SYN so the listener gets one delta per logical mouse movement.
        private var mouseDxAccum = 0
        private var mouseDyAccum = 0
        private var mouseWheelAccum = 0

        private fun handle(buf: ByteArray) {
            val type = u16le(buf, OFFSET_TYPE)
            val code = u16le(buf, OFFSET_CODE)
            val value = i32le(buf, OFFSET_VALUE)
            if (type == EV_REL) {
                when (code) {
                    REL_X -> mouseDxAccum += value
                    REL_Y -> mouseDyAccum += value
                    REL_WHEEL -> mouseWheelAccum += value
                }
                return
            }
            if (type == EV_SYN && code == SYN_REPORT) {
                if (mouseDxAccum != 0 || mouseDyAccum != 0 || mouseWheelAccum != 0) {
                    val dx = mouseDxAccum
                    val dy = mouseDyAccum
                    val wh = mouseWheelAccum
                    mouseDxAccum = 0
                    mouseDyAccum = 0
                    mouseWheelAccum = 0
                    try {
                        listener.onMouseDelta(dx, dy, wh)
                    } catch (_: RemoteException) {
                        stop()
                    }
                }
                return
            }
            if (type != EV_KEY) return
            // Mouse buttons — forward press/release to the app. value==2 is
            // autorepeat which mice don't really emit but skip just in case.
            if (code == BTN_LEFT || code == BTN_RIGHT || code == BTN_MIDDLE) {
                if (value != 0 && value != 1) return
                try {
                    listener.onMouseButton(code, value == 1)
                } catch (_: RemoteException) {
                    stop()
                }
                return
            }
            when (code) {
                KEY_LEFTCTRL, KEY_RIGHTCTRL -> {
                    ctrlHeld.set(value != 0)
                    updateModifiersHeld()
                }
                KEY_LEFTALT, KEY_RIGHTALT -> {
                    altHeld.set(value != 0)
                    updateModifiersHeld()
                }
                else -> {
                    // value: 0 = up, 1 = down, 2 = autorepeat. Only fire on the
                    // initial press so a held key doesn't spam zoom steps.
                    if (value != 1) return
                    if (!(ctrlHeld.get() && altHeld.get())) return
                    val hk = when (code) {
                        KEY_A -> PrivilegedHotkeys.HK_CYCLE_LAYOUT
                        KEY_Z -> PrivilegedHotkeys.HK_CYCLE_SCREEN_BAND
                        KEY_X -> PrivilegedHotkeys.HK_TOGGLE_VIEW_MODE
                        KEY_R -> PrivilegedHotkeys.HK_SDK_RECENTER
                        KEY_C -> PrivilegedHotkeys.HK_ANCHOR_POSE
                        KEY_EQUAL, KEY_KPPLUS -> PrivilegedHotkeys.HK_ZOOM_IN
                        KEY_MINUS, KEY_KPMINUS -> PrivilegedHotkeys.HK_ZOOM_OUT
                        else -> return
                    }
                    try {
                        listener.onHotkey(hk)
                    } catch (_: RemoteException) {
                        // App side died — stop the whole monitor so we don't keep
                        // racing against a dead Binder.
                        stop()
                    }
                }
            }
        }

        companion object {
            /**
             * Which mouse-detection layer gates the EVIOCGRAB.
             *
             *  - [MouseGrabStrategy.SYSFS] (legacy): probe the node's capability bitmap
             *    from `/sys/class/input/eventN/device/capabilities/ev` at open time.
             *    Reliable for the phone's built-in devices but EMPTY/SELinux-locked for
             *    Bluetooth HID mice — so BT mice are never grabbed and their pointer
             *    leaks into the system UI (status bar, nav pill, screen edges).
             *  - [MouseGrabStrategy.MOTION] (new, default): ignore metadata; identify a
             *    pointer from the events it actually emits — two-axis relative motion
             *    (REL_X *and* REL_Y) or a mouse button — and grab on first proof. Bus/
             *    vendor/name independent; sensor-safe (the REL_Y requirement excludes
             *    REL_X-only sensors). Costs at most one event frame of leaked motion
             *    before the grab engages.
             *
             * Flip to SYSFS to restore the old behavior verbatim.
             */
            val MOUSE_GRAB_STRATEGY = MouseGrabStrategy.MOTION

            private const val INPUT_DIR = "/dev/input"

            // struct input_event on Android 11+ (64-bit user space):
            //   struct timeval { __kernel_long_t tv_sec; __kernel_long_t tv_usec; }  // 16 bytes
            //   __u16 type, __u16 code, __s32 value                                  //  8 bytes
            // Total: 24 bytes. 32-bit Android is no longer in scope for this app
            // (PrivilegedService gates the helper on Android 11+).
            private const val INPUT_EVENT_SIZE = 24
            private const val OFFSET_TYPE = 16
            private const val OFFSET_CODE = 18
            private const val OFFSET_VALUE = 20

            // <linux/input-event-codes.h> — kernel keycodes (not Android KeyEvent codes).
            private const val EV_SYN = 0
            private const val EV_KEY = 1
            private const val EV_REL = 2
            private const val SYN_REPORT = 0
            private const val REL_X = 0
            private const val REL_Y = 1
            private const val REL_WHEEL = 8
            // Mouse button kernel codes.
            private const val BTN_LEFT = 272
            private const val BTN_RIGHT = 273
            private const val BTN_MIDDLE = 274
            // _IOW('E', 0x90, int) — exclusive-grab ioctl for evdev devices.
            private const val EVIOCGRAB = 0x40044590
            private const val KEY_MINUS = 12
            private const val KEY_EQUAL = 13
            private const val KEY_R = 19
            private const val KEY_LEFTCTRL = 29
            private const val KEY_A = 30
            private const val KEY_Z = 44
            private const val KEY_X = 45
            private const val KEY_C = 46
            private const val KEY_LEFTALT = 56
            private const val KEY_KPMINUS = 74
            private const val KEY_KPPLUS = 78
            private const val KEY_RIGHTCTRL = 97
            private const val KEY_RIGHTALT = 100

            private fun u16le(b: ByteArray, off: Int): Int =
                (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

            private fun i32le(b: ByteArray, off: Int): Int =
                (b[off].toInt() and 0xff) or
                    ((b[off + 1].toInt() and 0xff) shl 8) or
                    ((b[off + 2].toInt() and 0xff) shl 16) or
                    ((b[off + 3].toInt() and 0xff) shl 24)
        }
    }
    // endregion

    /** Run a shell command, log anything it prints, and report a clean exit. */
    private fun run(vararg command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = (
                process.inputStream.bufferedReader().readText() +
                    process.errorStream.bufferedReader().readText()
                ).trim()
            val exit = process.waitFor()
            if (exit != 0 || output.isNotEmpty()) {
                Log.i(TAG, "[$exit] ${command.joinToString(" ")}${if (output.isEmpty()) "" else " :: $output"}")
            }
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${command.joinToString(" ")}", e)
            false
        }
    }

    companion object {
        private const val TAG = "UxSpace/Privileged"

        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK. The third bit we want
        // (FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS, 0x00800000) is no longer ORed in here —
        // we pass it via am's `--activity-exclude-from-recents` argument instead so it
        // doesn't ride in the same raw -f blob. See launchOnDisplay() for the reason.
        private const val FLAG_NEW_TASK_MULTIPLE = "0x18000000"

        /** Frame spacing for the pinch interpolation in [pinchOnDisplay]. */
        private const val PINCH_STEP_MS = 16

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC (hidden SDK constant on some releases). */
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        /** Package owning the shell uid — the virtual display is created under it. */
        private const val SHELL_PACKAGE = "com.android.shell"

        /** VITURE Technology vendor id, lowercased hex as sysfs reports it. */
        private const val VITURE_VID_HEX = "35ca"

        /** Brief pause between unbind and bind during a USB rescan, ms. */
        private const val USB_REBIND_GAP_MS = 200L

        /**
         * Flags for the workspace's virtual displays. `PUBLIC` so the system places activities
         * on it; `OWN_CONTENT_ONLY` so it never mirrors the phone; `PRESENTATION` marks it as
         * secondary content; `TRUSTED` (1 << 10 — a hidden constant) so an app launched onto
         * it may follow its own activity launches there rather than escaping to the phone.
         *
         * `OWN_DISPLAY_GROUP` (1<<11) + `ALWAYS_UNLOCKED` (1<<12) were tried as a way to keep
         * the Samsung-injected KEYGUARD_DIALOG window off this display — but together they
         * leave the display in `state OFF` (DisplayPowerManager in the new group never
         * receives a power-on), which kills rendering entirely. Don't combine them again.
         */
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val TRUSTED_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                VIRTUAL_DISPLAY_FLAG_TRUSTED

        /**
         * Entry point when this class is loaded by `app_process` from the ADB shell bootstrap
         * (see `ServerBootstrap`). Sets up a system context, builds the Stub, hands its
         * Binder to the UxSpace app through [BinderReceiverProvider], then loops forever.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                Log.i(TAG, "PrivilegedServer.main entered, pid=${android.os.Process.myPid()}")
                val launchToken = readLaunchToken()
                killOrphanPrivilegedServers()
                Log.i(TAG, "orphan sweep returned; preparing Looper")
                Looper.prepareMainLooper()
                val systemContext = obtainSystemContext()
                    ?: throw IllegalStateException("could not obtain a system context")
                val server = PrivilegedServer().also { it.setContext(systemContext) }
                sendBinderToApp(server, launchToken)
                launchToken.fill(0)
                Log.i(TAG, "PrivilegedServer ready; entering main loop")
                Looper.loop()
            } catch (t: Throwable) {
                Log.e(TAG, "PrivilegedServer crashed during start-up", t)
                exitProcess(1)
            }
        }

        /** Read the fixed-size authenticator from the ADB shell stream without logging it. */
        private fun readLaunchToken(): ByteArray {
            val token = ByteArray(PrivilegedLaunchToken.BYTE_COUNT)
            var offset = 0
            while (offset < token.size) {
                val count = System.`in`.read(token, offset, token.size - offset)
                if (count <= 0) throw IllegalStateException("launch token was not delivered")
                offset += count
            }
            return token
        }

        /**
         * Kill any other `uxspace_privileged` processes left over from previous app
         * sessions before claiming the role ourselves. `app_process` detaches once
         * started; `adb shell am force-stop com.uxspace` only kills the app uid, not
         * the shell-uid helper, so without this sweep every reinstall would leave
         * behind another orphan holding FDs on every `/dev/input/event*` node — a
         * pattern observed in logcat as multiple `hotkey monitor started` lines and
         * 3×+ FD use on every input device, which in turn correlates with the
         * Bluetooth-keyboard-kills-DOF symptom.
         *
         * Identifies peers by `--nice-name=uxspace_privileged` riding in their
         * `/proc/<pid>/cmdline`. Shell uid has signal permission for shell-owned
         * processes, so `Process.killProcess` does what we need; the SIGKILL on
         * our SDK handle / Binder is handled by Android's process teardown.
         */
        private fun killOrphanPrivilegedServers() {
            val self = android.os.Process.myPid()
            Log.i(TAG, "orphan sweep starting (self pid=$self)")
            val procRoot = File("/proc")
            val children = procRoot.listFiles()
            if (children == null) {
                Log.w(TAG, "orphan sweep: /proc.listFiles() returned null — skipping")
                return
            }
            var examined = 0
            var killed = 0
            try {
                for (entry in children) {
                    val pid = entry.name.toIntOrNull() ?: continue
                    if (pid == self) continue
                    examined++
                    val cmdline = runCatching { File(entry, "cmdline").readText() }.getOrNull()
                        ?: continue
                    // /proc/<pid>/cmdline is NUL-separated; argv[0] is everything before
                    // the first NUL. Match strictly on argv[0] — substring matches caught
                    // the parent shell whose own command line embedded the helper's
                    // command string, and killing that shell tore down the ADB stream
                    // the app reads the privileged binder over.
                    val argv0 = cmdline.substringBefore('\u0000')
                    if (argv0 != "uxspace_privileged") continue
                    Log.i(TAG, "killing orphan uxspace_privileged pid=$pid")
                    runCatching { android.os.Process.killProcess(pid) }
                    killed++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "orphan sweep loop crashed (examined=$examined)", t)
            }
            if (killed > 0) {
                // Give the kernel a tick to reap the FDs (incl. /dev/input/event* opens)
                // so the new helper's HotkeyMonitor sees a clean state.
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            }
            Log.i(TAG, "orphan sweep complete; examined=$examined killed=$killed")
        }

        /** `ActivityThread.systemMain().getSystemContext()` — the standard app_process bootstrap. */
        private fun obtainSystemContext(): Context? = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getMethod("systemMain").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
        }.onFailure { Log.e(TAG, "obtainSystemContext failed", it) }.getOrNull()

        /**
         * Pass [binder] back to the UxSpace app process by calling its
         * [BinderReceiverProvider].
         *
         * Cannot use [Context.getContentResolver] because this process wasn't started by
         * AMS — `acquireProvider` calls `IActivityManager.getContentProvider`, which
         * requires a registered application record for the calling pid and rejects us with
         * "Unable to find app for caller". Instead we go straight through
         * `IActivityManager.getContentProviderExternal` — the same hidden API the `cmd
         * content` shell command uses to call into providers from shell-uid — and invoke
         * `IContentProvider.call` directly. Both are accessed reflectively because they
         * are not in the public SDK.
         */
        private fun sendBinderToApp(binder: IBinder, launchToken: ByteArray) {
            val authority = BinderReceiverProvider.AUTHORITY
            val token = Binder()
            val extras = Bundle().apply {
                putBinder(BinderReceiverProvider.EXTRA_BINDER, binder)
                putByteArray(BinderReceiverProvider.EXTRA_LAUNCH_TOKEN, launchToken)
            }
            val activityManager = activityManagerService()
                ?: throw IllegalStateException("no IActivityManager binder")
            val iAmClass = Class.forName("android.app.IActivityManager")
            val holder = iAmClass
                .getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java,
                    String::class.java,
                )
                .invoke(activityManager, authority, 0, token, SHELL_PACKAGE)
                ?: throw IllegalStateException("getContentProviderExternal returned null")
            val provider = holder.javaClass.getField("provider").get(holder)
                ?: throw IllegalStateException("ContentProviderHolder.provider was null")
            try {
                invokeProviderCall(provider, authority, extras)
                Log.i(TAG, "sent binder via getContentProviderExternal")
            } finally {
                runCatching {
                    iAmClass.getMethod(
                        "removeContentProviderExternal",
                        String::class.java,
                        IBinder::class.java,
                    ).invoke(activityManager, authority, token)
                }
            }
        }

        /** `ActivityManager.getService()` — the singleton `IActivityManager` binder proxy. */
        private fun activityManagerService(): Any? = runCatching {
            Class.forName("android.app.ActivityManager")
                .getMethod("getService")
                .invoke(null)
        }.onFailure { Log.e(TAG, "ActivityManager.getService() failed", it) }.getOrNull()

        /**
         * Invoke `IContentProvider.call(...)` reflectively, building whichever calling
         * identity the platform expects: API 31+ uses an `AttributionSource`; older
         * versions take a plain `(callingPkg, callingFeatureId)` pair.
         */
        private fun invokeProviderCall(provider: Any, authority: String, extras: Bundle) {
            val iCpClass = Class.forName("android.content.IContentProvider")
            val callMethods = iCpClass.declaredMethods.filter {
                it.name == "call" && it.returnType == Bundle::class.java
            }
            val attribClass = runCatching { Class.forName("android.content.AttributionSource") }
                .getOrNull()
            val attribCall = if (attribClass != null) {
                callMethods.firstOrNull { it.parameterTypes.firstOrNull() == attribClass }
            } else {
                null
            }
            if (attribCall != null) {
                val ctor = attribClass!!.getConstructor(
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                )
                val src = ctor.newInstance(Process.SHELL_UID, SHELL_PACKAGE, null)
                attribCall.invoke(
                    provider, src, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                return
            }
            // Pre-API 31 fallback: (callingPkg, callingFeatureId?, authority, method, arg, extras).
            val stringCall = callMethods.firstOrNull {
                it.parameterTypes.firstOrNull() == String::class.java
            } ?: throw IllegalStateException("no usable IContentProvider.call signature")
            val params = stringCall.parameterTypes
            val args: Array<Any?> = when (params.size) {
                5 -> arrayOf(
                    SHELL_PACKAGE, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                6 -> arrayOf(
                    SHELL_PACKAGE, null, authority,
                    BinderReceiverProvider.METHOD_SET_BINDER, null, extras,
                )
                else -> throw IllegalStateException(
                    "unexpected IContentProvider.call arity ${params.size}",
                )
            }
            stringCall.invoke(provider, *args)
        }
    }
}
