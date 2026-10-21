package com.uxspace

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.uxspace.databinding.ActivityMainBinding
import com.uxspace.desktop.DesktopWallpaperStore
import com.uxspace.desktop.WallpaperSource
import com.uxspace.glasses.GlassesDisplay
import com.uxspace.privileged.PairingNotifier
import com.uxspace.privileged.PrivilegedService
import com.uxspace.privileged.PrivilegedService.State
import com.uxspace.spatial.WorkspaceController
import com.uxspace.spatial.WorkspacePresentation
import com.uxspace.spatial.WorkspaceRenderer
import com.uxspace.update.AppUpdater
import java.util.Locale

/**
 * The phone-side control panel — UxSpace's input device.
 *
 * One activity, three scenes swapped by state:
 *
 *  - **Wizard** (`PrivilegedService.state != READY`) walks the user through wireless-debugging
 *    activation: turn on Wireless Debugging, then enter the 6-digit pairing code. The splash
 *    image sits at the top as a hero banner.
 *  - **Waiting** (paired but no glasses) — centered "Connect your VITURE glasses".
 *  - **Main** (paired + glasses connected) — the existing toolbar (view mode, capture, screen
 *    layout, screen size, keyboard) over the trackpad. The phone shows no app list — the
 *    glasses do, and the phone drives them.
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    /** The workspace shown on the glasses, while they are connected. */
    private var presentation: WorkspacePresentation? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Gate for the once-per-process silent update check (fired from the first [onResume]). */
    private var appUpdateChecked = false

    /** Virtual display id whose text field is currently focused, or -1. Set by the
     *  accessibility service's focus events; drives the auto-open of the phone keyboard
     *  and the routing of typed text back via [PrivilegedService.text]. */
    private var focusedAppDisplayId: Int = -1

    /** Last value of [keyboardField] so [forwardKeyboardDelta] can compute insertions
     *  and deletions per text-change event. */
    private var lastKeyboardText: String = ""

    /** Desktop index waiting for a wallpaper pick result. −1 = none in flight. */
    private var pendingWallpaperDesktopIdx: Int = -1

    /** Caps diagnostic mouse logs so a steady stream of events doesn't drown logcat. */
    private var mouseDiagFrameCount: Int = 0

    /**
     * Phone-side photo picker for wallpaper. Triggered from the in-glasses settings
     * panel via [WorkspaceController.pickWallpaperFromDevice] (the panel lives on a
     * VirtualDisplay and can't host the picker dialog itself). On a successful pick
     * we take a persistable URI permission so the URI keeps working across restarts.
     */
    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val idx = pendingWallpaperDesktopIdx
        pendingWallpaperDesktopIdx = -1
        if (uri == null || idx < 0) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        DesktopWallpaperStore.setSource(idx, WallpaperSource.Uri(uri.toString()))
    }

    /** Reacts when the glasses are plugged in or out while the panel is open. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncGlasses()
        override fun onDisplayRemoved(displayId: Int) = syncGlasses()
        override fun onDisplayChanged(displayId: Int) = syncGlasses()
    }

    /** Re-evaluates whether any physical keyboard is attached whenever the input
     *  device set changes. The HotkeyMonitor in the shell-uid helper is gated on
     *  the result — no keyboard means we don't open any /dev/input/event* nodes. */
    private val keyboardPresenceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshKeyboardPresence()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshKeyboardPresence()
        override fun onInputDeviceChanged(deviceId: Int) = refreshKeyboardPresence()
    }

    private fun inputManager(): InputManager = getSystemService(InputManager::class.java)

    /**
     * Scan every attached input device for a real physical keyboard and tell
     * [PrivilegedService] whether to keep the global hotkey spy alive.
     *
     * Naive checks (sources & SOURCE_KEYBOARD, keyboardType == ALPHABETIC) match
     * too aggressively: Android's "Virtual" soft keyboard (id=-1) and HID combo
     * mouse-receivers (e.g. Logitech "MX Anywhere 2S Keyboard") both advertise
     * ALPHABETIC even though neither has A-Z keys. Filter them out with
     *
     *   - skip [InputDevice.isVirtual] (kills the "Virtual" keyboard)
     *   - require [InputDevice.hasKeys] to return true for `KEYCODE_A` (kills the
     *     receivers whose only "keyboard" surface is media keys)
     *
     * Done as a full scan rather than tracking individual add/remove flips
     * because a single device can advertise multiple sources and the listener
     * can fire in any order.
     */
    private fun refreshKeyboardPresence() {
        val current = mutableSetOf<Int>()
        var anyMouse = false
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            val name = dev.name
            if (dev.isVirtual) {
                Log.d("UxSpace/Main", "kb scan: skip virtual id=$id name='$name'")
                continue
            }
            // Track real mice so the monitor stays alive for the raw-evdev mouse
            // delta path even when no keyboard is attached.
            if ((dev.sources and InputDevice.SOURCE_MOUSE) != 0) {
                Log.i("UxSpace/Main", "mouse present: id=$id name='$name'")
                anyMouse = true
            }
            if ((dev.sources and InputDevice.SOURCE_KEYBOARD) == 0) continue
            if (dev.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                Log.d("UxSpace/Main", "kb scan: skip non-alphabetic id=$id name='$name'")
                continue
            }
            // Wireless mice (Logitech "MX Anywhere 2S Keyboard" etc.) expose a
            // media-keys HID interface that Android classifies as ALPHABETIC and
            // even claims KEYCODE_A — but the device's primary identity is a
            // mouse. Real keyboards never advertise SOURCE_MOUSE.
            if ((dev.sources and InputDevice.SOURCE_MOUSE) != 0) {
                Log.d(
                    "UxSpace/Main",
                    "kb scan: skip mouse-combo id=$id name='$name' (sources=0x${
                        dev.sources.toString(16)
                    })",
                )
                continue
            }
            val hasA = dev.hasKeys(KeyEvent.KEYCODE_A)
            if (hasA.isEmpty() || !hasA[0]) {
                Log.d(
                    "UxSpace/Main",
                    "kb scan: skip no-KEYCODE_A id=$id name='$name' (combo HID, not a real keyboard)",
                )
                continue
            }
            Log.i("UxSpace/Main", "keyboard present: id=$id name='$name'")
            current.add(id)
        }
        PrivilegedService.setHotkeyMonitoringEnabled(current.isNotEmpty() || anyMouse)

        // Proactive glasses-USB rescan around keyboard-connect events. The first
        // scan after process start is the baseline — only later adds count.
        // Catches the Samsung quirk where pairing a BT keyboard wedges the
        // Carina endpoint; rebinding ~1.5s later (after the BT-stack USB churn
        // peaks) usually resurrects pose flow before the user sees a drop.
        if (!firstKeyboardScan) {
            val freshAdds = current - lastKnownKeyboardIds
            if (freshAdds.isNotEmpty() && WorkspaceController.isRunning) {
                Log.i(
                    "UxSpace/Main",
                    "keyboard add (ids=$freshAdds) — scheduling glasses USB rescan in ${KEYBOARD_CONNECT_RESCAN_MS}ms",
                )
                pendingKeyboardRescan?.let { mainHandler.removeCallbacks(it) }
                val r = Runnable {
                    if (!WorkspaceController.isRunning) return@Runnable
                    Log.i(
                        "UxSpace/Main",
                        "proactive rescanGlassesUsb (post-keyboard-connect)",
                    )
                    PrivilegedService.rescanGlassesUsb()
                }
                pendingKeyboardRescan = r
                mainHandler.postDelayed(r, KEYBOARD_CONNECT_RESCAN_MS)
            }
        }
        firstKeyboardScan = false
        lastKnownKeyboardIds = current
    }

    /** First-scan-after-onCreate sentinel: don't fire a proactive rescan for a keyboard
     *  that was already attached when the app launched — only for *new* attaches. */
    private var firstKeyboardScan = true
    private var lastKnownKeyboardIds: Set<Int> = emptySet()
    private var pendingKeyboardRescan: Runnable? = null

    private val privilegeListener: () -> Unit = { runOnUiThread { renderStatus() } }

    /**
     * Result-launcher for POST_NOTIFICATIONS (API 33+). The notification path is the
     * primary pairing UX; if the user denies, the form below the wizard still works as a
     * fallback so the activity ignores the result.
     */
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* ignored — the form fallback works without notifications */ }

    /**
     * Result-launcher for RECORD_AUDIO. Granted once at first launch so the OS doesn't
     * re-prompt every time an app launched into the workspace tries to use the mic.
     */
    private val requestMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* ignored — launched apps will see the system prompt themselves if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // DO NOT add FLAG_NOT_FOCUSABLE here, and do NOT make this window hold input
        // focus by any other means (e.g. Pointer Capture). This activity sits on
        // display 0; the workspace + launched apps live on secondary/glasses displays.
        // The flag was originally added to stop a trackpad tap from flipping the
        // top-focused display to 0 (which made Samsung One UI's GameBooster pause the
        // launched app on its secondary display and tear down its input channel). But
        // holding/denying focus here breaks three things, so the flag is OFF and the
        // window stays focusable:
        //   1. Secondary-display apps — if THIS window takes input focus, display 0
        //      becomes top-focused, the app's secondary display loses focus, and
        //      GameBooster pauses it. (We now feed launched apps via *injected* input
        //      on trusted displays, not focus-based dispatch, so a momentary tap-flip
        //      no longer kills them — but a window that *holds* focus still would.)
        //   2. Input/ANR — the flag blocks mouse/keyboard dispatch, causing
        //      "Application does not have a focused window" ANRs when a BT mouse is up.
        //   3. Pseudo-root bootstrap — the privileged helper is launched via the
        //      wireless-debugging pairing flow (docs/PRIVILEGE.md); the in-app
        //      pairing-code field needs keyboard focus, which the flag starves.
        // The mouse-roaming problem the flag/Pointer-Capture would "solve" is instead
        // handled WITHOUT focus, by EVIOCGRAB in the privileged helper (PrivilegedServer
        // .HotkeyMonitor) — the only approach compatible with all three constraints.
        // window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide the Android system pointer while it's anywhere over our window.
        // The workspace cursor (rendered in the glasses) is the only cursor the
        // user looks at; the on-phone pointer is just noise once raw-evdev mouse
        // deltas drive the workspace from PrivilegedService.mouseDeltaHandler.
        // Except on the emulator (docs/SIMULATOR.md): there, a middle-click hands the mouse back
        // to Android to click this toolbar, so the pointer has to be visible.
        if (!IS_EMULATOR) {
            window.decorView.pointerIcon =
                android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
        }

        // Sticky-immersive fullscreen — hide the status bar and navigation /
        // gesture bar. Two reasons:
        //  1. The mouse cursor riding past the top or bottom edge used to land
        //     clicks on system_server's status bar / Samsung gesture pill (those
        //     surfaces ignore FLAG_NOT_FOCUSABLE etc. because they aren't owned
        //     by our window). Now the area is ours and our event consumer eats
        //     the clicks before they go anywhere.
        //  2. The trackpad already takes the whole visible area; no reason to
        //     give up screen real estate to system chrome the user isn't looking
        //     at (they're looking at the glasses).
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        binding.setupButton.setOnClickListener { onSetupAction() }
        binding.viewModeButton.setOnClickListener { toggleViewMode() }
        // Long-press = full glasses reconnect (helper + display + head tracking), a heavier
        // recovery than the tap's DOF-only retry.
        binding.viewModeButton.setOnLongClickListener { attemptGlassesReconnect(); true }
        // The in-view toolbar's DOF-retry button routes here too, so both report success /
        // failure the same way. (WorkspacePresentation no longer owns this hook.)
        WorkspaceController.retryHeadTracking = { mainHandler.post { attemptReconnectDof() } }
        // Tap = one-shot screenshot; long-press = start/stop frame-sequence recording.
        binding.captureButton.setOnClickListener { onSnapshot() }
        binding.captureButton.setOnLongClickListener {
            if (WorkspaceController.isRunning) {
                val on = WorkspaceController.toggleRecording()
                Toast.makeText(
                    this,
                    if (on) "Recording started" else "Recording stopped",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            } else {
                Toast.makeText(this, R.string.waiting_for_glasses, Toast.LENGTH_SHORT).show()
                false
            }
        }
        // Keep the capture button's icon in sync with recording state, whichever toolbar
        // toggled it (phone long-press or the in-view capture button).
        WorkspaceController.addRecordingListener(recordingListener)
        renderCaptureButton(WorkspaceController.isRecording)
        binding.layoutButton.setOnClickListener {
            // The button is grayed and isEnabled=false in PINNED via renderToolbarStates,
            // but isEnabled toggling alone doesn't block click in all paths — keep the
            // mode check as a belt-and-braces guard.
            if (WorkspaceController.currentViewMode == WorkspaceRenderer.ViewMode.PINNED) return@setOnClickListener
            val next = WorkspaceController.cycleLayout()
            Toast.makeText(this, "Layout: ${next.displayName}", Toast.LENGTH_SHORT).show()
        }
        binding.screenHeightButton.setOnClickListener {
            val percent = (WorkspaceController.cycleScreenBand() * 100).toInt()
            Toast.makeText(this, "Zoom $percent%", Toast.LENGTH_SHORT).show()
        }
        binding.alignHorizonButton.setOnClickListener {
            Log.i("UxSpace/Main", "alignHorizon click: running=${WorkspaceController.isRunning}")
            if (WorkspaceController.isRunning) {
                WorkspaceController.alignVerticalToHead()
            } else {
                Toast.makeText(this, "Connect glasses first", Toast.LENGTH_SHORT).show()
            }
        }
        WorkspaceController.addZoomListener(zoomHudListener)
        binding.keyboardButton.setOnClickListener { toggleKeyboard() }
        // Long-press to jump to Accessibility settings — the auto-keyboard feature
        // needs the UxSpaceAccessibilityService toggled on there.
        binding.keyboardButton.setOnLongClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this, "Enable UxSpace under Accessibility", Toast.LENGTH_LONG,
            ).show()
            true
        }
        renderViewModeButton()
        renderToolbarStates()
        // DOF flips async from the head-pose thread; viewMode flips from any toggle path.
        // Both must refresh the lock + layout buttons' enabled/alpha. Hop to main since
        // these listeners can fire on any thread.
        WorkspaceController.addDofListener { mainHandler.post { renderToolbarStates() } }
        WorkspaceController.addViewModeListener {
            mainHandler.post {
                renderViewModeButton()
                renderToolbarStates()
            }
        }

        binding.trackpad.onMove = { dx, dy -> WorkspaceController.moveCursor(dx, dy) }
        binding.trackpad.onTap = { WorkspaceController.click() }
        binding.trackpad.onTwoFingerDrag = { dx, dy ->
            WorkspaceController.twoFingerDrag(dx, dy)
        }
        binding.trackpad.onZoom = { scale -> WorkspaceController.pinch(scale) }
        binding.trackpad.onDragStart = { WorkspaceController.beginDrag() }
        binding.trackpad.onDragEnd = { WorkspaceController.endDrag() }
        binding.trackpad.onDragCancel = { WorkspaceController.cancelDrag() }
        binding.trackpad.onLongPress = { WorkspaceController.longPress() }
        // Cursor motion + clicks come from the privileged helper's raw-evdev path
        // (PrivilegedService.mouseDeltaHandler); only the wheel still routes through
        // dispatchGenericMotionEvent below. Pointer Capture would also give raw,
        // unclamped deltas — but it requires this window to HOLD input focus, which is
        // forbidden here (it pauses secondary-display apps via GameBooster, ANRs the
        // BT mouse, and starves the pseudo-root pairing field; see the onCreate note).
        // EVIOCGRAB in the helper achieves the same "system never sees the mouse"
        // without focus, so it is the path we use — Pointer Capture is NOT an option.

        WorkspaceController.pickWallpaperFromDevice = { desktopIdx ->
            pendingWallpaperDesktopIdx = desktopIdx
            // PickVisualMedia is the modern, permission-less path for picking images
            // (Android 13+; gracefully degrades to a system picker on older OS).
            pickWallpaperLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        WorkspaceController.openSoundOutputPicker = {
            // Settings.Panel.ACTION_VOLUME is the documented "small panel" intent —
            // on Android 11+ it shows the per-stream volume sliders and the system's
            // output-device picker. Activity-context required; the panel can't host
            // it from inside a Presentation.
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.Panel.ACTION_VOLUME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        // "Check for updates" in the glasses-side Settings → About panel routes here; the
        // update dialogs + system install screen are activity-context concerns and show on
        // the phone (where the final install tap must happen anyway).
        WorkspaceController.checkForUpdates = { mainHandler.post { checkForAppUpdate(silent = false) } }

        // Forward IME keystrokes into the workspace drawer's search field while the drawer
        // is open, or into a focused text field on a launched app's virtual display
        // (detected by UxSpaceAccessibilityService). The drawer / virtual displays can't
        // host an IME, so the phone-side keyboard field is the only practical input path.
        binding.keyboardField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString().orEmpty()
                when {
                    WorkspaceController.isDrawerOpen ->
                        WorkspaceController.setDrawerSearchQuery(current)
                    focusedAppDisplayId >= 0 ->
                        forwardKeyboardDelta(focusedAppDisplayId, lastKeyboardText, current)
                }
                lastKeyboardText = current
            }
        })

        // Accessibility service callbacks — opens / closes the phone keyboard when a
        // text field gains / loses focus on one of our virtual displays.
        WorkspaceController.onAppTextFieldFocused = { displayId ->
            mainHandler.post {
                focusedAppDisplayId = displayId
                showKeyboard()
            }
        }
        WorkspaceController.onAppTextFieldUnfocused = { displayId ->
            mainHandler.post {
                if (focusedAppDisplayId == displayId) {
                    focusedAppDisplayId = -1
                    hideKeyboard()
                }
            }
        }

        // Keep the panel resumed during a session, so re-showing the workspace after a
        // glasses blip happens from a live window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PrivilegedService.addListener(privilegeListener)
        // Watch for the glasses the whole time the panel exists — not just while resumed.
        displayManager().registerDisplayListener(displayListener, mainHandler)
        // Gate the shell-uid hotkey monitor on physical-keyboard presence — when no
        // keyboard is attached, the helper opens no /dev/input/event* nodes at all.
        // BT/USB keyboards arrive as InputDevice add events on this listener.
        inputManager().registerInputDeviceListener(keyboardPresenceListener, mainHandler)
        refreshKeyboardPresence()
        requestNotificationPermissionIfNeeded()
        requestMicrophonePermissionIfNeeded()
        renderStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(perm)
        }
    }

    private fun requestMicrophonePermissionIfNeeded() {
        val perm = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission.launch(perm)
        }
    }

    /**
     * Delivered when the glasses are connected while UxSpace is already running. Receiving the
     * attach intent also grants USB access; [syncGlasses] then shows the workspace if it is
     * not already up. It must NOT tear down a running workspace first — doing so spun up a
     * second [WorkspacePresentation], and its [HeadTracking][com.uxspace.glasses.HeadTracking]
     * raced the first over the native SDK handle, crashing the process.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val dev = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
            Log.i(
                "UxSpace/Main",
                "USB_DEVICE_ATTACHED vid=0x${"%04x".format(dev?.vendorId ?: 0)} " +
                    "pid=0x${"%04x".format(dev?.productId ?: 0)} name=${dev?.productName}",
            )
            syncGlasses()
            // A VITURE attach that arrives while DOF is already live is a spurious
            // re-attach (e.g. a sibling USB interface enumerating) — force-restarting
            // then would needlessly tear down a healthy session and re-enter the SDK/USB
            // lifecycle, risking the very races the lifecycle lock now guards. Only act
            // when tracking is NOT currently streaming.
            if (!WorkspaceController.headTrackingActive) {
                // VITURE attach → force-restart: the post-rescan attach comes through here
                // with the prior SDK session bound to a USB device the kernel just unbound,
                // so a plain retry would early-out on started=true. Non-VITURE attaches just
                // need the cheap retry (covers the DisplayPort-before-IMU enumeration race).
                if (dev?.vendorId == VITURE_VENDOR_ID) presentation?.restartHeadTracking()
                else presentation?.retryHeadTracking()
            } else {
                Log.i("UxSpace/Main", "USB attach ignored — head tracking already live")
            }
        }
    }

    private fun toggleViewMode() {
        // DOF down → this button is the reconnect affordance (tap = retry DOF; long-press =
        // full glasses reconnect). Always reachable here on the phone control panel and
        // mirrored by the in-view toolbar's DOF-retry button.
        if (!WorkspaceController.headTrackingActive) {
            attemptReconnectDof()
            return
        }
        val next = when (WorkspaceController.currentViewMode) {
            WorkspaceRenderer.ViewMode.PINNED -> WorkspaceRenderer.ViewMode.FREE
            WorkspaceRenderer.ViewMode.FREE -> WorkspaceRenderer.ViewMode.PINNED
        }
        WorkspaceController.setViewMode(next)
        renderViewModeButton()
    }

    /** Pending DOF-reconnect outcome check; replaced on each new attempt. */
    private val dofReconnectOutcome = Runnable {
        if (WorkspaceController.headTrackingActive) {
            Toast.makeText(this, "Head tracking connected", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Head tracking reconnect failed — try unplugging and replugging the glasses",
                Toast.LENGTH_LONG,
            ).show()
            WorkspaceController.announceInView("Head tracking unavailable", 4_000L)
        }
    }

    /**
     * Retry head tracking (tap on the reconnect button). Full SDK + USB restart, then
     * report the outcome: if no pose stream has come up within [DOF_RECONNECT_TIMEOUT_MS]
     * we toast that the reconnect failed. Recovers a tracker that connected but never
     * produced a pose (LIBUSB_ERROR_NO_DEVICE, Carina VIO not converging, …).
     */
    private fun attemptReconnectDof() {
        if (WorkspaceController.headTrackingActive) return
        val pres = presentation
        if (pres == null) {
            Toast.makeText(this, "No glasses connected", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i("UxSpace/Main", "attemptReconnectDof: restarting head tracking")
        Toast.makeText(this, "Reconnecting head tracking…", Toast.LENGTH_SHORT).show()
        WorkspaceController.announceInView("Reconnecting head tracking…", DOF_RECONNECT_TIMEOUT_MS)
        pres.restartHeadTracking()
        mainHandler.removeCallbacks(dofReconnectOutcome)
        mainHandler.postDelayed(dofReconnectOutcome, DOF_RECONNECT_TIMEOUT_MS)
    }

    /**
     * Long-press on the reconnect button: a full glasses reconnect — re-bootstrap the
     * shell-uid helper (recovers a dead binder, which otherwise blocks the desktop's
     * trusted VirtualDisplay), re-claim the glasses display / rebuild the workspace
     * Presentation, then restart head tracking. The heavier hammer for when the desktop
     * itself didn't come up, not just DOF.
     */
    private fun attemptGlassesReconnect() {
        Log.i("UxSpace/Main", "attemptGlassesReconnect: helper + display + head tracking")
        Toast.makeText(this, "Reconnecting glasses…", Toast.LENGTH_SHORT).show()
        WorkspaceController.announceInView("Reconnecting glasses…", DOF_RECONNECT_TIMEOUT_MS)
        PrivilegedService.ensureRunning()
        syncGlasses()
        presentation?.restartHeadTracking()
        mainHandler.removeCallbacks(dofReconnectOutcome)
        mainHandler.postDelayed(dofReconnectOutcome, DOF_RECONNECT_TIMEOUT_MS)
    }

    /**
     * Refresh enabled/alpha of the buttons that depend on DOF availability and view
     * mode. Lock toggle requires DOF (FREE makes no sense without head tracking);
     * Layout button only does something in FREE (PINNED forces SINGLE). Called from
     * the WorkspaceController listeners and on resume.
     */
    private fun renderToolbarStates() {
        val free = WorkspaceController.currentViewMode == WorkspaceRenderer.ViewMode.FREE
        // viewModeButton is always actionable: lock/unlock toggle when DOF is live, a
        // reconnect button when it is down — so it is never grayed out (the icon, set in
        // renderViewModeButton, signals which role it is in).
        binding.viewModeButton.isEnabled = true
        binding.viewModeButton.alpha = 1f
        renderViewModeButton()
        binding.layoutButton.isEnabled = free
        binding.layoutButton.alpha = if (free) 1f else DISABLED_ALPHA
    }

    private fun renderViewModeButton() {
        binding.viewModeButton.setImageResource(
            when {
                !WorkspaceController.headTrackingActive -> R.drawable.ic_dof_retry
                WorkspaceController.currentViewMode == WorkspaceRenderer.ViewMode.PINNED -> R.drawable.ic_pin
                else -> R.drawable.ic_pin_off
            },
        )
    }

    /** Tap on the capture button → save one screenshot of the current workspace frame. */
    private fun onSnapshot() {
        if (!WorkspaceController.isRunning) {
            Toast.makeText(this, R.string.waiting_for_glasses, Toast.LENGTH_SHORT).show()
            return
        }
        WorkspaceController.capture()
        Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show()
    }

    /** Recording-state listener — flips the capture button to a red dot while recording. */
    private val recordingListener: (Boolean) -> Unit = { rec ->
        mainHandler.post { renderCaptureButton(rec) }
    }

    private fun renderCaptureButton(recording: Boolean) {
        binding.captureButton.setImageResource(
            if (recording) R.drawable.ic_record_on else R.drawable.ic_capture,
        )
    }

    /** Show or hide the system keyboard. Keystroke routing into the focused app is M4. */
    private fun toggleKeyboard() {
        if (binding.keyboardField.visibility == View.VISIBLE) hideKeyboard()
        else showKeyboard()
    }

    /** Bring up the phone IME and focus the hidden keyboardField. Idempotent. */
    private fun showKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val field = binding.keyboardField
        if (field.visibility != View.VISIBLE) field.visibility = View.VISIBLE
        // Clear before showing so the delta tracking starts from empty; if the user
        // had been typing a draft we'd otherwise re-send the whole thing as new keys.
        if (field.text.isNotEmpty()) {
            field.text.clear()
            lastKeyboardText = ""
        }
        field.requestFocus()
        imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hide the phone IME and clear keyboardField state. Idempotent. */
    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val field = binding.keyboardField
        imm.hideSoftInputFromWindow(field.windowToken, 0)
        field.visibility = View.GONE
        field.text.clear()
        lastKeyboardText = ""
    }

    /**
     * Forward the delta between [old] and [new] keyboardField text into the focused
     * app on [displayId] — backspaces for any characters removed, then the new tail
     * via `input text`. Lets a phone-side IME type into an out-of-process app on a
     * virtual display.
     */
    private fun forwardKeyboardDelta(displayId: Int, old: String, new: String) {
        val common = old.commonPrefixWith(new).length
        val toDelete = old.length - common
        val toAdd = new.substring(common)
        repeat(toDelete) {
            PrivilegedService.key(displayId, android.view.KeyEvent.KEYCODE_DEL)
        }
        if (toAdd.isNotEmpty()) {
            PrivilegedService.text(displayId, toAdd)
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from Developer settings or the pairing dialog may have changed things —
        // re-attempt bring-up. Idempotent and no-op once READY.
        PrivilegedService.ensureRunning()
        syncGlasses()
        // One silent update check per process. Fails soft (offline / up-to-date / error stay
        // quiet); only a genuinely newer, non-dismissed build pops the dialog.
        if (!appUpdateChecked) { appUpdateChecked = true; checkForAppUpdate(silent = true) }
    }

    // --- In-app self-updater --------------------------------------------------

    /**
     * Check the publish manifest for a newer APK. [silent] auto-checks (on launch) stay quiet when
     * up to date / offline and honour the "Later" dismissal; a manual check reports every outcome.
     * All dialogs show on the phone — the final install confirmation must happen there.
     */
    private fun checkForAppUpdate(silent: Boolean) {
        AppUpdater.check(this) { release, error ->
            when {
                release != null -> {
                    if (silent && release.versionCode <= AppUpdater.dismissedCode(this)) return@check
                    showAppUpdateDialog(release)
                }
                silent -> { /* auto-check: never nag on up-to-date / offline / error */ }
                error == "offline" -> Toast.makeText(this, "No connection — can't check for updates", Toast.LENGTH_SHORT).show()
                error != null -> Toast.makeText(this, "Update check failed: $error", Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this, "UxSpace ${BuildConfig.VERSION_NAME} is up to date", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAppUpdateDialog(r: AppUpdater.Release) {
        val mb = if (r.size > 0) String.format(Locale.US, " · %.1f MB", r.size / 1048576.0) else ""
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(
                "UxSpace ${r.versionName} (build ${r.versionCode})$mb\n\n" +
                    "You have ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}).",
            )
            .setPositiveButton("Update") { _, _ -> startAppUpdate(r) }
            .setNegativeButton("Later") { d, _ -> AppUpdater.setDismissed(this, r.versionCode); d.dismiss() }
            .show()
    }

    private fun startAppUpdate(r: AppUpdater.Release) {
        // On Android 8+ the user must first allow UxSpace to install apps; bounce them to Settings.
        if (!AppUpdater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Allow app installs")
                .setMessage(
                    "To update itself, UxSpace needs permission to install apps. Enable it on the " +
                        "next screen, then check for updates again.",
                )
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching { startActivity(AppUpdater.unknownSourcesSettings(this)) }
                        .onFailure { Toast.makeText(this, "Couldn't open settings", Toast.LENGTH_SHORT).show() }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val progress = AlertDialog.Builder(this)
            .setTitle("Downloading update")
            .setMessage("Starting…")
            .setCancelable(false)
            .create()
        progress.show()
        AppUpdater.downloadAndInstall(
            this, r,
            onProgress = { done, total ->
                progress.setMessage(
                    if (total > 0) String.format(
                        Locale.US, "Downloading… %d%%  (%.1f / %.1f MB)",
                        done * 100 / total, done / 1048576.0, total / 1048576.0,
                    )
                    else String.format(Locale.US, "Downloading… %.1f MB", done / 1048576.0),
                )
            },
            onDone = { ok, msg ->
                progress.dismiss()
                Toast.makeText(
                    this,
                    if (ok) "Opening installer…" else "Update failed: $msg",
                    if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
            },
        )
    }

    override fun onDestroy() {
        displayManager().unregisterDisplayListener(displayListener)
        runCatching { inputManager().unregisterInputDeviceListener(keyboardPresenceListener) }
        presentation?.dismiss()
        presentation = null
        setWorkspaceServiceRunning(false)
        PrivilegedService.removeListener(privilegeListener)
        WorkspaceController.removeRecordingListener(recordingListener)
        WorkspaceController.removeZoomListener(zoomHudListener)
        if (WorkspaceController.pickWallpaperFromDevice != null) {
            WorkspaceController.pickWallpaperFromDevice = null
        }
        if (WorkspaceController.openSoundOutputPicker != null) {
            WorkspaceController.openSoundOutputPicker = null
        }
        WorkspaceController.checkForUpdates = null
        super.onDestroy()
    }

    /** Hide the zoom HUD a short while after the last zoom change. */
    private val hudHideRunnable = Runnable { binding.zoomHud.visibility = View.GONE }

    /** Pops up a "Zoom NN%" HUD on the trackpad whenever the workspace zoom changes. */
    private val zoomHudListener: (Float) -> Unit = { zoom ->
        binding.zoomHud.text = "Zoom ${(zoom * 100).toInt()}%"
        binding.zoomHud.visibility = View.VISIBLE
        binding.zoomHud.removeCallbacks(hudHideRunnable)
        binding.zoomHud.postDelayed(hudHideRunnable, ZOOM_HUD_HIDE_MS)
    }

    private fun displayManager(): DisplayManager =
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** Show the workspace on the glasses while they are connected; refresh the active scene. */
    private fun syncGlasses() {
        val display = GlassesDisplay.find(this)
        if (display != null) {
            val current = presentation
            if (current == null || !current.isShowing ||
                current.display.displayId != display.displayId
            ) {
                current?.dismiss()
                presentation = try {
                    WorkspacePresentation(this, display).also { it.show() }
                } catch (e: android.view.WindowManager.BadTokenException) {
                    // Common during a glasses connect/disconnect race — the display
                    // briefly exists but isn't trusted yet; the next syncGlasses retry
                    // succeeds. Don't log a full stack trace as if it were a crash.
                    Log.w("UxSpace/Main", "presentation rejected (display not trusted yet): ${e.message}")
                    null
                } catch (e: Exception) {
                    Log.e("UxSpace/Main", "could not show workspace on the glasses", e)
                    null
                }
            }
            // Foreground service brackets the glasses session — its onDestroy is the
            // safety net that force-stops launched apps if the activity is taken away
            // without the presentation's own dismiss cleanup running first.
            setWorkspaceServiceRunning(presentation != null)
        } else {
            presentation?.dismiss()
            presentation = null
            setWorkspaceServiceRunning(false)
        }
        renderStatus()
    }

    private var workspaceServiceRunning = false
    private fun setWorkspaceServiceRunning(running: Boolean) {
        if (running == workspaceServiceRunning) return
        if (running) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException if the
            // activity isn't in the foreground when the call fires — this happens
            // when the glasses are plugged in while the user is on another app.
            // Swallow it: onResume will re-run syncGlasses and try again from the
            // foreground state where it succeeds.
            try {
                WorkspaceService.start(this)
                workspaceServiceRunning = true
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                Log.w(
                    "UxSpace/Main",
                    "WorkspaceService start deferred — activity not in foreground (${e.message})",
                )
            } catch (e: Exception) {
                Log.e("UxSpace/Main", "WorkspaceService start failed", e)
            }
        } else {
            workspaceServiceRunning = false
            runCatching { WorkspaceService.stop(this) }
        }
    }

    /**
     * Pick which of the three scenes is visible from [PrivilegedService.state] and whether the
     * glasses are connected, and populate the wizard with its current step.
     */
    private fun renderStatus() {
        val glassesShowing = presentation?.isShowing == true
        when {
            PrivilegedService.state != State.READY -> showWizard()
            !glassesShowing -> showScene(showWizard = false, showWaiting = true)
            else -> showScene(showWizard = false, showWaiting = false)
        }
        // The RemoteInput pairing notification is the primary path — post it when the user
        // is at the pair step, take it down otherwise.
        if (PrivilegedService.state == State.NEEDS_PAIRING) {
            PairingNotifier.showPairingPrompt(this)
        } else {
            PairingNotifier.cancel(this)
        }
    }

    private fun showWizard() {
        showScene(showWizard = true, showWaiting = false)
        when (PrivilegedService.state) {
            State.UNSUPPORTED -> populateWizard(
                R.string.privilege_title_unsupported,
                R.string.privilege_msg_unsupported,
                actionLabel = null,
            )
            State.NEEDS_DEVELOPER_OPTIONS -> populateWizard(
                R.string.privilege_title_dev_options,
                R.string.privilege_msg_dev_options,
                actionLabel = R.string.privilege_action_open_about,
            )
            State.NEEDS_WIRELESS_DEBUGGING -> populateWizard(
                R.string.privilege_title_wireless_debugging,
                R.string.privilege_msg_wireless_debugging,
                actionLabel = R.string.privilege_action_open_wireless_debugging,
            )
            State.NEEDS_PAIRING -> populateWizard(
                R.string.privilege_title_pair,
                R.string.privilege_msg_pair,
                actionLabel = R.string.privilege_action_open_wireless_debugging,
            )
            State.DISCOVERING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_discovering,
                actionLabel = null,
            )
            State.CONNECTING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_connecting,
                actionLabel = null,
            )
            State.STARTING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_starting,
                actionLabel = null,
            )
            State.READY -> Unit // showWizard would not have been called
        }
    }

    private fun showScene(showWizard: Boolean, showWaiting: Boolean) {
        binding.wizardScene.visibility = if (showWizard) View.VISIBLE else View.GONE
        binding.waitingScene.visibility = if (showWaiting) View.VISIBLE else View.GONE
        binding.mainScene.visibility =
            if (!showWizard && !showWaiting) View.VISIBLE else View.GONE
    }

    private fun populateWizard(title: Int, message: Int, actionLabel: Int?) {
        binding.wizardTitle.setText(title)
        binding.wizardMessage.setText(message)
        if (actionLabel == null) {
            binding.setupButton.visibility = View.GONE
        } else {
            binding.setupButton.visibility = View.VISIBLE
            binding.setupButton.setText(actionLabel)
            binding.setupButton.isEnabled = true
        }
    }

    /**
     * The wizard button: each step sends the user to wherever the next action lives in
     * Android Settings. Actual pairing is done via the notification posted while the
     * NEEDS_PAIRING state is active — see [PairingNotifier].
     */
    private fun onSetupAction() {
        when (PrivilegedService.state) {
            State.NEEDS_DEVELOPER_OPTIONS -> openAboutPhone()
            State.NEEDS_WIRELESS_DEBUGGING, State.NEEDS_PAIRING -> openWirelessDebugging()
            else -> Unit
        }
    }

    private fun openAboutPhone() {
        startFirstAvailable(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    /**
     * Open Developer Options and scroll to / highlight the Wireless Debugging row. The
     * `:settings:fragment_args_key` extra is the documented way to deep-link to a specific
     * preference inside a Settings page; on Samsung One UI it both scrolls there and
     * briefly highlights the row. Falls back to plain Developer Options, then to the top
     * of Settings.
     */
    private fun openWirelessDebugging() {
        val targeted = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(SETTINGS_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_PREF_KEY)
            putExtra(
                SETTINGS_SHOW_FRAGMENT_ARGS,
                Bundle().apply { putString(SETTINGS_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_PREF_KEY) },
            )
        }
        startFirstAvailable(
            targeted,
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    /** Try each intent in order; stop at the first one that launches. */
    private fun startFirstAvailable(vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    /**
     * BT mouse → workspace. The cursor + clicks are driven via the privileged
     * helper's raw evdev path (no phone-edge clamping); only the wheel still
     * needs this `dispatchGenericMotionEvent` route — `ACTION_SCROLL` has no
     * clean evdev equivalent and the system already delivers it correctly,
     * including the modifier state used by Ctrl+Alt+Wheel zoom. Every other
     * mouse event is consumed (`return true`) so it doesn't double-fire on
     * the trackpad view or any phone-side UI.
     *
     * (Pointer Capture would have given us raw deltas directly, but it requires this
     * window to hold input focus — which is forbidden: a focused window on display 0
     * makes it top-focused, so GameBooster pauses secondary-display apps; it also ANRs
     * the BT mouse and starves the pseudo-root pairing field. See the onCreate note.
     * The privileged helper's EVIOCGRAB gets the raw deltas with no focus instead.)
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            @Suppress("ConstantConditionIf")
            if (MOUSE_VERBOSE_LOGS && mouseDiagFrameCount < 8) {
                Log.i(
                    "UxSpace/Mouse",
                    "uncaptured ev action=${ev.actionMasked} src=0x${Integer.toHexString(ev.source)} " +
                        "x=${ev.x} y=${ev.y}",
                )
                mouseDiagFrameCount++
            }
            handleMouseEvent(ev)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * Swallow mouse-as-touch events too — once the wheel works through
     * [dispatchGenericMotionEvent], we don't want the trackpad view to treat a
     * mouse click as a finger tap. Touchscreen events are passed through.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // On the emulator a mouse only reaches Android after a middle-click released it
            // (PrivilegedServer.HotkeyMonitor). It clicks the toolbar; a click on the
            // trackpad hands it back to the workspace cursor.
            if (!IS_EMULATOR) return true
            if (isOverTrackpad(ev)) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) PrivilegedService.regrabPointers()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isOverTrackpad(ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        binding.trackpad.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX < loc[0] + binding.trackpad.width &&
            ev.rawY >= loc[1] && ev.rawY < loc[1] + binding.trackpad.height
    }

    private fun handleMouseEvent(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v == 0f) return
            val ctrlAlt = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
            if ((ev.metaState and ctrlAlt) == ctrlAlt) {
                // Ctrl+Alt+Wheel → workspace zoom (matches the Windows companion's
                // combo). Was Win+Shift originally; switched because Samsung One UI
                // hard-binds Meta to the launcher.
                val factor = if (v > 0) 1f + v * MOUSE_ZOOM_GAIN
                             else        1f / (1f - v * MOUSE_ZOOM_GAIN)
                WorkspaceController.pinch(factor)
            } else {
                WorkspaceController.scroll(v * MOUSE_SCROLL_GAIN)
            }
        }
        // All other mouse events (HOVER_MOVE, MOVE, BUTTON_PRESS / RELEASE) fall
        // through to the dispatchGenericMotionEvent `return true` consumer — they
        // arrive twice for events the helper missed (no EVIOCGRAB), but we never
        // ACT on them here. Cursor motion + clicks happen via the raw-evdev path.
    }

    private companion object {
        /** Android emulator — enables the simulator's middle-click mouse passthrough. */
        val IS_EMULATOR = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

        /** Settings preference key for the Wireless Debugging row in Developer Options. */
        const val WIRELESS_DEBUGGING_PREF_KEY = "toggle_adb_wireless"

        /** Settings deep-link extras — preserved across most OEM Settings forks. */
        const val SETTINGS_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

        /** How long the trackpad's zoom HUD lingers after the last zoom event. */
        const val ZOOM_HUD_HIDE_MS = 1200L

        /** Alpha applied to phone-side toolbar buttons whose action isn't currently
         *  available (Lock toggle without DOF, Layout toggle in PINNED). */
        const val DISABLED_ALPHA = 0.35f

        /** USB Vendor ID assigned to VITURE Technology — used in [onNewIntent] to
         *  tell a glasses USB attach apart from any other device's attach. */
        const val VITURE_VENDOR_ID = 0x35ca

        /** How long after a reconnect attempt to check whether the pose stream came
         *  up; if [WorkspaceController.headTrackingActive] is still false we report the
         *  reconnect failed. Long enough to cover SDK re-init + Carina VIO warm-up. */
        const val DOF_RECONNECT_TIMEOUT_MS = 8_000L

        /** Delay between a real keyboard appearing and the proactive glasses USB
         *  rescan. ~1.5s lets the BT pairing's peak USB churn finish before we
         *  rebind, so the rebind doesn't race against the disruption itself. */
        const val KEYBOARD_CONNECT_RESCAN_MS = 1_500L

        /** Mouse-wheel notch → workspace-scroll fraction. */
        const val MOUSE_SCROLL_GAIN = 0.08f

        /** Ctrl+Alt+Wheel: zoom multiplier per notch (1 notch up = ×1.10, down = ÷1.10). */
        const val MOUSE_ZOOM_GAIN = 0.10f

        /** Flip to true while triaging mouse plumbing; otherwise spam-free. */
        const val MOUSE_VERBOSE_LOGS = false
    }
}
