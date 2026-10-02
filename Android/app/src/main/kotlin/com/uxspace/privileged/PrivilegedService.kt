package com.uxspace.privileged

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Orchestrates UxSpace's own shell-uid helper: pairs with the device's wireless-debugging
 * dialog, discovers and connects to the ADB connect service, starts a [PrivilegedServer]
 * over `app_process`, and exposes the privileged-call surface UxSpace uses to launch apps,
 * inject input, and create trusted virtual displays.
 *
 * Built in stage 4 of [docs/PRIVILEGE.md]; not yet wired by UxSpaceApp — the app still goes
 * through [ShizukuManager]. Stage 5 swaps the wiring and removes the Shizuku path.
 *
 * Lifecycle:
 *
 *  - First run, after [activate] pairs with a 6-digit code, the ADB identity (key + cert)
 *    is persisted; the device trusts it permanently.
 *  - Every launch, [ensureRunning] discovers the connect port via mDNS, connects, and
 *    starts the server. The returned [AdbStream] is held open so the server stays alive;
 *    closing it (or the app dying) tears the server down.
 *  - The server sends its Binder back through [BinderReceiverProvider]; that arrives at
 *    [onPrivilegedBinder] and moves the state machine to [State.READY].
 */
object PrivilegedService {

    private const val TAG = "UxSpace/Privileged"

    /** Steps the user (or UxSpace) must clear before privileged calls work. */
    enum class State {
        /** Android < 11 — the wireless-debugging path does not exist. */
        UNSUPPORTED,

        /**
         * Developer options is locked. Wireless Debugging can't be turned on until the user
         * unlocks Developer options (Settings → About → tap Build number 7×), so the wizard
         * walks them through that first.
         */
        NEEDS_DEVELOPER_OPTIONS,

        /** Paired before, but the connect service is not on mDNS (wireless debugging off). */
        NEEDS_WIRELESS_DEBUGGING,

        /**
         * Never paired (or the device forgot our key — connect failed with
         * AdbPairingRequiredException, which clears the paired marker). The wizard asks for
         * a pairing code.
         */
        NEEDS_PAIRING,

        /** mDNS / TCP work in progress. */
        DISCOVERING,
        CONNECTING,
        STARTING,

        /** Helper bound — privileged calls go through. */
        READY,
    }

    @Volatile
    var state: State = State.NEEDS_PAIRING
        private set

    @Volatile
    private var service: IPrivilegedService? = null

    /** Held to keep the helper alive — closing the stream brings the server down with it. */
    @Volatile
    private var adbStream: AdbStream? = null

    private val launchToken = PrivilegedLaunchToken()

    private var appContext: Context? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "uxspace-privileged") }
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "uxspace-privileged-sched")
    }

    /** Call once, from the Application. Computes the initial state from persisted identity. */
    fun init(context: Context) {
        appContext = context.applicationContext
        state = computeInitialState(context)
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun computeInitialState(context: Context): State = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> State.UNSUPPORTED
        !isDevOptionsEnabled(context) -> State.NEEDS_DEVELOPER_OPTIONS
        hasPaired(context) -> State.NEEDS_WIRELESS_DEBUGGING
        else -> State.NEEDS_PAIRING
    }

    /** Developer options unlocked? Read-only access to Settings.Global; no permission needed. */
    private fun isDevOptionsEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) != 0
    }.getOrDefault(false)

    /**
     * Whether UxSpace has previously paired successfully. A separate marker file from the key
     * — the key is generated and persisted on first use of [AdbConnectionManager], whether or
     * not the device ever accepted it, so the key file's existence proves nothing.
     */
    private fun hasPaired(context: Context): Boolean =
        File(context.filesDir, PAIRED_MARKER).exists()

    private fun markPaired(context: Context, paired: Boolean) {
        val marker = File(context.filesDir, PAIRED_MARKER)
        if (paired) {
            marker.parentFile?.mkdirs()
            runCatching { marker.createNewFile() }
        } else {
            runCatching { marker.delete() }
        }
    }

    private fun setState(next: State) {
        if (next == state) return
        Log.i(TAG, "state: $state -> $next")
        state = next
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }

    /**
     * Try to make the helper live — discover the connect port via mDNS, connect, start the
     * server. Idempotent; safe to call from anywhere. The state machine reflects progress.
     */
    fun ensureRunning() {
        worker.execute {
            val ctx = appContext ?: return@execute
            if (state == State.UNSUPPORTED) return@execute
            if (state == State.READY && service != null) return@execute
            // Bring-up already in flight: a previous ensureRunning() task is spawning
            // a server and its binder hasn't published yet. Without this guard, every
            // call between the spawn and the READY transition fires another server,
            // and each one runs its own /dev/input/event* HotkeyMonitor — observed in
            // logcat as three identical "hotkey monitor started" lines and 3× FD use
            // on every input device. The dropped binders are leaked; the displaced
            // server processes never exit.
            if (state == State.DISCOVERING ||
                state == State.CONNECTING ||
                state == State.STARTING
            ) {
                Log.d(TAG, "ensureRunning ignored — bring-up already in flight (state=$state)")
                return@execute
            }
            // Re-evaluate prerequisites on every attempt — the user may have just unlocked
            // Developer options or toggled Wireless Debugging.
            if (!isDevOptionsEnabled(ctx)) {
                setState(State.NEEDS_DEVELOPER_OPTIONS)
                return@execute
            }
            if (!hasPaired(ctx)) {
                setState(State.NEEDS_PAIRING)
                return@execute
            }

            setState(State.DISCOVERING)
            val endpoints = AdbDiscovery.discoverConnect(ctx, DISCOVERY_TIMEOUT_MS)
            if (endpoints.isEmpty()) {
                Log.w(TAG, "connect service not advertised — wireless debugging off?")
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                return@execute
            }

            val adb = AdbConnectionManager.getInstance(ctx)
            if (!adb.isConnected) {
                setState(State.CONNECTING)
                // Android can advertise a stale `_adb-tls-connect._tcp` next to the active
                // one; try each until one accepts the connect, so a refused stale endpoint
                // doesn't masquerade as "wireless debugging off".
                var connected = false
                var lastError: Exception? = null
                for (endpoint in endpoints) {
                    try {
                        if (adb.connect(endpoint.host, endpoint.port)) {
                            connected = true
                            break
                        }
                        Log.w(TAG, "ADB connect to ${endpoint.host}:${endpoint.port} returned false")
                    } catch (e: AdbPairingRequiredException) {
                        // The device no longer trusts our key — wipe the marker and walk
                        // the user back through pairing. No point trying other endpoints.
                        Log.w(TAG, "device requires (re-)pairing — invalidating the paired marker")
                        markPaired(ctx, false)
                        setState(State.NEEDS_PAIRING)
                        return@execute
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(
                            TAG,
                            "ADB connect to ${endpoint.host}:${endpoint.port} failed: ${e.message}",
                        )
                    }
                }
                if (!connected) {
                    Log.e(
                        TAG,
                        "all ${endpoints.size} connect endpoint(s) refused — wireless debugging off?",
                        lastError,
                    )
                    setState(State.NEEDS_WIRELESS_DEBUGGING)
                    return@execute
                }
            }

            val token = launchToken.issue()
            setState(State.STARTING)
            val stream = try {
                ServerBootstrap.start(ctx, adb, token)
            } finally {
                token.fill(0)
            }
            if (stream == null) {
                launchToken.clear()
                Log.w(TAG, "could not start PrivilegedServer over ADB")
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                return@execute
            }
            adbStream = stream
            // BinderReceiverProvider.onPrivilegedBinder() drives us to READY when the server
            // publishes its Binder back to the app.
        }
    }

    /**
     * Pair using the 6-digit [pairingCode] from Wireless Debugging's "Pair device with a
     * pairing code" dialog. The pairing port is discovered via mDNS — this only works
     * while the dialog is still in the foreground, which is why UxSpace reads the code
     * from a notification's `RemoteInput` (the shade overlays the dialog instead of
     * backgrounding it). [done] runs on the worker thread.
     */
    fun activate(pairingCode: String, done: (Boolean) -> Unit) {
        worker.execute {
            val ctx = appContext ?: run { done(false); return@execute }
            try {
                val endpoint = AdbDiscovery.discoverPairing(ctx, DISCOVERY_TIMEOUT_MS)
                if (endpoint == null) {
                    Log.w(TAG, "pair: mDNS did not find the service — is the dialog still up?")
                    markPaired(ctx, false)
                    done(false); return@execute
                }
                val adb = AdbConnectionManager.getInstance(ctx)
                val paired = adb.pair(endpoint.host, endpoint.port, pairingCode)
                Log.i(TAG, "ADB pair ${endpoint.host}:${endpoint.port} ok=$paired")
                markPaired(ctx, paired)
                if (!paired) { done(false); return@execute }
                done(true)
                ensureRunning()
            } catch (e: Exception) {
                Log.e(TAG, "activate failed", e)
                markPaired(ctx, false)
                done(false)
            }
        }
    }

    /**
     * Called by [BinderReceiverProvider] when [PrivilegedServer] hands its Binder back. The
     * The provider gates this on the calling uid (shell or self); the one-use launch token
     * additionally proves that this is the helper started by the current ADB bootstrap.
     */
    fun onPrivilegedBinder(binder: IBinder, token: ByteArray) {
        if (!binder.pingBinder()) {
            Log.w(TAG, "onPrivilegedBinder: ping failed")
            return
        }
        if (!launchToken.consume(token, state == State.STARTING)) {
            Log.w(TAG, "onPrivilegedBinder: rejected unexpected or unauthenticated publication")
            return
        }
        // Displace any previously-bound server. Without this, the spawn-race window
        // can hand us a second (or third) binder for a separate PrivilegedServer
        // process — each one running its own /dev/input/event* HotkeyMonitor and
        // its own native input injector. The orphans never exit on their own.
        val previous = service
        if (previous != null) {
            Log.w(TAG, "displacing previously-bound privileged service — calling exit()")
            runCatching { previous.setHotkeyListener(null) }
            runCatching { previous.exit() }
        }
        service = IPrivilegedService.Stub.asInterface(binder)
        Log.i(TAG, "privileged binder bound — READY")
        // Only install the hotkey listener if a keyboard is actually attached. With
        // no keyboard the helper opens no /dev/input/event* nodes at all — minimum
        // footprint, no contention against the glasses' USB IMU endpoint on OEMs
        // (Samsung) where BT input attach disturbs USB hosts.
        if (hotkeyMonitoringRequested) installHotkeyListener()
        setState(State.READY)
    }

    /**
     * App-level dispatcher for the Ctrl+Alt+X global hotkeys the shell-uid server captures
     * off `/dev/input/event*`. Set once by the app at startup (alongside the other
     * `WorkspaceController.*` lambdas in `UxSpaceApp.onCreate`). Invoked on the main thread.
     * Codes are [PrivilegedHotkeys].HK_*.
     */
    @Volatile
    var hotkeyHandler: ((code: Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val hotkeyListenerStub = object : IPrivilegedHotkeyListener.Stub() {
        override fun onHotkey(code: Int) {
            // Binder thread → main thread; controller methods aren't always main-only,
            // but the actions (zoom presets, view mode, recenter) ultimately touch
            // the renderer and UI, so jumping once here is safest.
            mainHandler.post { hotkeyHandler?.invoke(code) }
        }
        override fun onMouseDelta(dx: Int, dy: Int, wheel: Int) {
            // Raw evdev mouse deltas — never quantised to the phone-screen edge.
            mainHandler.post { mouseDeltaHandler?.invoke(dx, dy, wheel) }
        }
        override fun onMouseButton(code: Int, pressed: Boolean) {
            // With the helper holding EVIOCGRAB on the mouse, button events never
            // reach `dispatchGenericMotionEvent` either — so we have to forward
            // them here and the app dispatches workspace clicks ourselves.
            mainHandler.post { mouseButtonHandler?.invoke(code, pressed) }
        }
        override fun onKey(keyCode: Int, pressed: Boolean, shift: Boolean) {
            // Hardware-keyboard text (non-hotkey) — the app routes it to the drawer search
            // box when the drawer is open. keyCode is the kernel KEY_* code.
            mainHandler.post { keyHandler?.invoke(keyCode, pressed, shift) }
        }
        override fun onZoom(scale: Float) {
            // Two-finger touchpad pinch — driven into the workspace zoom.
            mainHandler.post { zoomHandler?.invoke(scale) }
        }
    }

    /** App-level handler for a touchpad pinch ([scale] = incremental span ratio, 1 = none). */
    @Volatile
    var zoomHandler: ((scale: Float) -> Unit)? = null

    /**
     * App-level handler for hardware-keyboard text keys (non Ctrl+Alt). [keyCode] is the
     * kernel KEY_* code, [pressed] true on press/autorepeat, [shift] the shift-held state.
     */
    @Volatile
    var keyHandler: ((keyCode: Int, pressed: Boolean, shift: Boolean) -> Unit)? = null

    /**
     * App-level handler for raw evdev mouse motion (REL_X, REL_Y, REL_WHEEL batched
     * per SYN_REPORT). Set once by the app at startup. dx/dy are kernel ticks;
     * the handler is responsible for any scaling to workspace NDC units.
     */
    @Volatile
    var mouseDeltaHandler: ((dx: Int, dy: Int, wheel: Int) -> Unit)? = null

    /**
     * App-level handler for mouse button transitions (BTN_LEFT/RIGHT/MIDDLE
     * press + release). [code] is the kernel BTN_* number, [pressed] true on
     * press / false on release.
     */
    @Volatile
    var mouseButtonHandler: ((code: Int, pressed: Boolean) -> Unit)? = null

    private fun installHotkeyListener() {
        val helper = service ?: return
        runCatching {
            helper.setHotkeyListener(hotkeyListenerStub)
        }.onFailure { Log.w(TAG, "setHotkeyListener failed", it) }
    }

    /**
     * Whether the app has currently asked the helper to monitor hotkeys. Controlled by
     * [setHotkeyMonitoringEnabled] from the keyboard-presence listener in MainActivity;
     * stays true once any keyboard appears so the next privileged-binder bind installs
     * the listener immediately.
     */
    @Volatile
    private var hotkeyMonitoringRequested: Boolean = false

    /**
     * Toggle the shell-uid HotkeyMonitor based on whether a physical keyboard is
     * attached. When `enabled = false` the listener Binder is nulled out, the helper
     * tears down its FileObserver + reader threads + every `/dev/input/event*` FD.
     * When `enabled = true` it re-opens them and resumes Ctrl+Alt+X dispatch.
     */
    fun setHotkeyMonitoringEnabled(enabled: Boolean) {
        if (hotkeyMonitoringRequested == enabled) return
        hotkeyMonitoringRequested = enabled
        Log.i(TAG, "hotkey monitoring requested -> $enabled")
        val helper = service ?: return
        runCatching {
            helper.setHotkeyListener(if (enabled) hotkeyListenerStub else null)
        }.onFailure { Log.w(TAG, "setHotkeyListener toggle failed", it) }
    }

    /**
     * Force-detach and re-attach the VITURE glasses' USB device via the helper, so the
     * kernel re-enumerates it. Triggered by the app's DOF-stall watchdog after the SDK
     * stops producing poses for an extended period — observed empirically that a stale
     * Carina USB endpoint sometimes only recovers from a bus reset, which the BT-pair
     * USB churn occasionally does on its own. Returns immediately; the actual rescan
     * runs on the helper's worker thread and the resulting USB_DEVICE_ATTACHED intent
     * re-triggers [com.uxspace.spatial.WorkspacePresentation.retryHeadTracking].
     */
    fun rescanGlassesUsb() {
        val helper = service ?: run {
            Log.w(TAG, "rescanGlassesUsb ignored — helper not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching { helper.rescanGlassesUsb() }
                .onFailure { Log.w(TAG, "rescanGlassesUsb failed", it) }
        }
    }

    // region Privileged-call surface (mirrors ShizukuManager so UxSpaceApp swaps cleanly)

    fun launchApp(displayId: Int, packageName: String, activityName: String) {
        Log.i(
            "UxSpace/Launch",
            "7) PrivilegedService.launchApp pkg=$packageName display=$displayId state=$state",
        )
        val helper = service
        if (helper == null) {
            Log.w(TAG, "launchApp ignored — not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching {
                Log.i(
                    "UxSpace/Launch",
                    "8) helper.launchOnDisplay AIDL pkg=$packageName display=$displayId",
                )
                val ok = helper.launchOnDisplay(displayId, packageName, activityName)
                Log.i(
                    "UxSpace/Launch",
                    "9) helper returned ok=$ok pkg=$packageName display=$displayId",
                )
            }.onFailure { Log.e(TAG, "launchApp failed", it) }
        }
    }

    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Int? {
        val helper = service
        if (helper == null) {
            Log.w(TAG, "createVirtualDisplay ignored — not READY (state=$state)")
            return null
        }
        return runCatching {
            helper.createVirtualDisplay(name, width, height, densityDpi, surface)
                .takeIf { it >= 0 }
        }.onFailure { Log.e(TAG, "createVirtualDisplay failed", it) }.getOrNull()
    }

    fun releaseVirtualDisplay(displayId: Int) =
        onWorker { service?.releaseVirtualDisplay(displayId) }

    /**
     * Inject an ACTION_SCROLL motion event into the app on [displayId] at content
     * pixel (x, y). Identical path to `UiScreen.dispatchScroll` but routed through the
     * shell-uid helper so the event reaches an out-of-process VirtualDisplay; one
     * AIDL call per frame, no swipe synthesis.
     */
    fun scrollOnDisplay(displayId: Int, x: Int, y: Int, vScroll: Float) =
        onWorker { service?.injectScroll(displayId, x, y, vScroll) }

    /**
     * Streamed touch injection: one frame at a time, action ∈ {DOWN=0, MOVE=1, UP=2,
     * CANCEL=3}. Lets the trackpad surface a touch-move-lift gesture into an app's
     * VirtualDisplay so taps and swipes work the same way they would on a real screen.
     */
    fun injectTouch(displayId: Int, x: Int, y: Int, action: Int) {
        val name = when (action) {
            0 -> "DOWN"; 1 -> "MOVE"; 2 -> "UP"; 3 -> "CANCEL"; else -> "?($action)"
        }
        Log.d(TAG, "injectTouch $name @ ($x,$y) display=$displayId")
        onWorker { service?.injectTouch(displayId, x, y, action) }
    }

    fun key(displayId: Int, keyCode: Int) = onWorker { service?.key(displayId, keyCode) }

    fun text(displayId: Int, value: String) = onWorker { service?.text(displayId, value) }

    fun forceStop(packageName: String) = onWorker { service?.forceStop(packageName) }

    /** Whether the given VirtualDisplay currently has any activity stacked on it. */
    fun displayHasActivity(displayId: Int): Boolean =
        runCatching { service?.displayHasActivity(displayId) == true }.getOrDefault(false)

    /**
     * Injects KEYCODE_MEDIA_PAUSE at the given display so any media playback on
     * the activity (YouTube audio, etc.) stops when the window is minimised.
     */
    fun sendMediaPause(displayId: Int) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_MEDIA_PAUSE) }
    }

    fun sendBack(displayId: Int, onEmptied: () -> Unit) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_BACK) }
        scheduler.schedule(
            {
                runCatching {
                    if (service?.displayHasActivity(displayId) == false) onEmptied()
                }.onFailure { Log.e(TAG, "back-empty check failed", it) }
            },
            BACK_SETTLE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    // endregion

    private fun onWorker(block: () -> Unit) {
        worker.execute { runCatching { block() } }
    }

    /** How long to wait for an mDNS hit before giving up. */
    private const val DISCOVERY_TIMEOUT_MS = 5_000L

    /** How long to wait after a Back press before checking whether it closed the app. */
    private const val BACK_SETTLE_MS = 800L

    /** Path of the marker file created on a successful [activate] (relative to filesDir). */
    private const val PAIRED_MARKER = "adb/paired.flag"
}
