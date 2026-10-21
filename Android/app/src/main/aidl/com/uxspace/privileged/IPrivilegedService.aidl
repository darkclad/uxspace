// Interface to UxSpace's shell-uid privileged helper — the process that launches apps onto
// the workspace's virtual displays, injects input, and creates the displays themselves
// (trusted, so apps don't escape to the phone). See docs/PRIVILEGE.md.
package com.uxspace.privileged;

import android.view.Surface;
import com.uxspace.privileged.IPrivilegedHotkeyListener;

interface IPrivilegedService {

    // Transaction id used while the helper is still bound through Shizuku, which uses this
    // specific id to tear the service down. Kept after we move to our own ADB bootstrap so a
    // Shizuku-bound build remains interchangeable.
    void destroy() = 16777114;

    void exit() = 1;

    /** Launch packageName/activityName onto the given display. Returns true on success. */
    boolean launchOnDisplay(int displayId, String packageName, String activityName) = 2;

    /** Inject a tap at (x, y) on the given display. */
    void tap(int displayId, int x, int y) = 3;

    /** Inject a swipe on the given display, lasting durationMs. */
    void swipe(int displayId, int fromX, int fromY, int toX, int toY, int durationMs) = 4;

    /** Inject a key event (a KeyEvent key code) on the given display. */
    void key(int displayId, int keyCode) = 5;

    /** Type text into the focused field on the given display. */
    void text(int displayId, String value) = 6;

    /** Force-stop a package — closes its activities and kills its process. */
    void forceStop(String packageName) = 7;

    /** Whether the given display currently has an activity on it. */
    boolean displayHasActivity(int displayId) = 8;

    /**
     * Create a *trusted* virtual display rendering into surface. Created from this shell-uid
     * process so the TRUSTED flag is honoured — only a trusted display lets a launched app
     * follow its own splash-screen / new-task launches instead of escaping to the phone.
     * Returns the new display's id, or -1 on failure.
     */
    int createVirtualDisplay(String name, int width, int height, int densityDpi, in Surface surface) = 9;

    /** Release a virtual display previously created via createVirtualDisplay. */
    void releaseVirtualDisplay(int displayId) = 10;

    /**
     * Inject a two-finger pinch on the given display, centred at (centerX, centerY), with
     * the pointer spread going from fromSpan to toSpan over durationMs. The shell uid has
     * INJECT_EVENTS, so the helper can build a multi-pointer MotionEvent and submit it
     * through InputManager directly — `input` shell-outs only do one pointer at a time.
     */
    void pinchOnDisplay(int displayId, int centerX, int centerY, int fromSpan, int toSpan, int durationMs) = 11;

    /**
     * Inject an ACTION_SCROLL motion event at (x, y) on the given display, with the given
     * vertical wheel value (mouse-wheel convention: positive = scroll up). Mirrors what
     * a USB mouse wheel sends to a scrollable view — fast and identical to the drawer's
     * in-process AXIS_VSCROLL dispatch, no synthesized touch-swipe latency.
     */
    void injectScroll(int displayId, int x, int y, float vScroll) = 12;

    /**
     * Inject one frame of a touch stream on the given display. The action codes mirror
     * MotionEvent: 0 = ACTION_DOWN, 1 = ACTION_MOVE, 2 = ACTION_UP, 3 = ACTION_CANCEL.
     * The helper tracks the down timestamp per display so subsequent MOVE / UP frames
     * carry the right downAt and the app sees a consistent touch sequence.
     */
    void injectTouch(int displayId, int x, int y, int action) = 13;

    /**
     * Install a global Ctrl+Alt+X hotkey spy, mirroring the Windows app's
     * WH_KEYBOARD_LL hook. The helper reads /dev/input/event* directly from shell uid
     * and forwards matching combos as PrivilegedHotkeys.HK_* codes through
     * [listener] (oneway). Passing null tears the monitor down. Replaces any previously
     * registered listener. Events are observed passively — the focused app still sees
     * the original key presses.
     */
    void setHotkeyListener(IPrivilegedHotkeyListener listener) = 14;

    /**
     * Force-detach and re-attach the VITURE glasses' USB device. Locates the VID 0x35ca
     * device under /sys/bus/usb/devices, writes its bus-port id to the usb-driver's
     * unbind file, sleeps briefly, then writes the same id to bind. The kernel
     * re-enumerates the device, which surfaces back to the app as USB_DEVICE_ATTACHED.
     * Idempotent — silent no-op when no VITURE device is present.
     */
    void rescanGlassesUsb() = 15;

    /**
     * Emulator simulator only (docs/SIMULATOR.md): re-grab the pointer devices a middle-click
     * handed back to Android. No-op when nothing was released or on real hardware.
     */
    void regrabPointers() = 16;
}
