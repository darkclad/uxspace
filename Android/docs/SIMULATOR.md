# Simulator

Run the UxSpace desktop (taskbar, app drawer, window chrome, settings and audio
panels, multi-screen layouts, cursor) on an Android emulator, with no VITURE glasses
and no phone with a second display. It's meant for iterating on the desktop UI.

It's the normal app in a debug build, plus three things:

- **A fake glasses display.** The emulator adds a 1920×1080 secondary display.
  `GlassesDisplay.find` treats any presentation display not named `uxspace-…` as
  the glasses, so the `WorkspacePresentation` lands on it unchanged.
- **Fake head pose.** `SimulatorBridge` exists only in the debug source set
  (`app/src/debug`). It listens on device loopback port 47800 and feeds poses
  straight to the renderer through `WorkspaceController.injectHeadPose`.
  `HeadTracking` finds no glasses on USB and never touches the native SDK.
- **No VITURE SDK needed.** `:glasses` builds its JNI bridge only when the SDK is
  present. `-PuxspaceNoViture` skips it even when the SDK is there, so the APK
  carries no arm64-only libraries.

The privileged helper is the real one. The emulator supports Wireless debugging, so
UxSpace pairs and starts `PrivilegedServer` exactly as it does on a phone. That
means trusted displays, app launching, the helper's evdev mouse and keyboard paths,
the taskbar's notifications and its quick-settings toggles all work too.

A few behaviours are emulator-only (checked with `Build.HARDWARE` = `ranchu`) so the
simulator can be driven with a PC mouse: the middle-button gestures, Android's pointer
showing over the phone toolbar, and the simulator hint on the touchpad. They never
change behaviour on a phone.

## One-time setup

1. **An x86_64 AVD on API 30 or newer**, with room on `/data`. The APK is about
   60 MB. A tablet profile is the most convenient: `MainActivity` is
   portrait-locked, and the whole phone screen becomes the touchpad. This repo was
   tested on `UxSpace_Sim`, a copy of the `Galaxy_Tab_A_T510` profile (1920×1200,
   API 36.1, `google_apis_playstore`). Don't share an AVD with another app's
   automation: the UI-driven `pair` step and the other app's tests will fight over
   the screen.

2. **Build and install the simulator APK** (from `Android/`):

   ```bash
   ./gradlew :app:assembleDebug -PuxspaceNoViture
   ```
   ```bash
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Start UxSpace once, then pair the helper:**

   ```bash
   adb shell am start -n com.uxspace/.MainActivity
   ```
   ```bash
   python tools/simulator/uxsim.py pair
   ```

   `pair` turns on Developer options and Wireless debugging (accepting "Always allow
   on this network"). It opens *Pair device with pairing code*, reads the code, and
   passes it to the app over the bridge. It should print `ok paired`.

With several devices attached, set `ANDROID_SERIAL` (for example `emulator-5580`).
Both `adb` and `uxsim.py` use it.

## Each session

1. **Start the emulator** cold, with a virtio relative mouse (see
   [Mouse and keyboard](#mouse-and-keyboard)), detached from your shell:

   ```bash
   emulator -avd UxSpace_Sim -port 5580 -feature VirtioMouse,-VirtioTablet -no-snapshot-load
   ```

   Cold-boot (`-no-snapshot-load`): a restored quick-boot snapshot can bring the
   workspace display back as a frozen frame.

2. **Start UxSpace and check the helper.** Wireless debugging is normally off again
   after a boot, so the helper reports `NEEDS_WIRELESS_DEBUGGING` and the desktop
   stays blank. `pair` turns it back on and re-pairs; it's safe to repeat. Then
   restart UxSpace so the desktop comes up with the helper ready:

   ```bash
   python tools/simulator/uxsim.py send state
   ```
   ```bash
   python tools/simulator/uxsim.py pair
   ```
   ```bash
   adb shell am force-stop com.uxspace
   ```
   ```bash
   adb shell am start -n com.uxspace/.MainActivity
   ```

3. **Add the fake glasses display:**

   ```bash
   python tools/simulator/uxsim.py setup
   ```

This adds emulator display 1 (1920x1080 at 320 dpi; pass another `WxH/DPI` to
change it) and runs the `adb forward`. The display opens as its own window on the PC,
and the UxSpace desktop appears there within a second or two. Use `--overlay` to get
Android's overlay display instead. That also works on a real device, but it floats
over the phone screen and hides part of the touchpad.

Then start the head-pose pad:

```bash
python tools/simulator/uxsim.py
```

| Input | Effect |
|---|---|
| Left-drag or arrow keys (Shift = 1° steps) | Look around (yaw/pitch) |
| Q / E | Roll |
| 0 | Look straight ahead |
| F | Toggle FREE (world-fixed) and PINNED |
| R | Recenter (the toolbar's align-to-head) |
| L | Next multi-screen layout (FREE only) |
| Right-drag | Move the workspace cursor |
| Space | Click |

The debug build keeps a fake DOF stream live whenever the workspace is showing. The
lock and layout buttons on the phone toolbar are therefore always enabled, so FREE
mode works the way it does with real tracking. The pad starts in FREE mode, facing
(0, 0).

### Mouse and keyboard

- **Mouse, captured (recommended):** with `-feature VirtioMouse,-VirtioTablet` (step 1
  of [Each session](#each-session)), the guest has a "QEMU Virtio Mouse" (REL_X/REL_Y). The privileged helper
  classes it MOUSE and EVIOCGRABs it, exactly like a BT mouse on the phone. Click
  inside any emulator window to capture the host mouse; it then drives the workspace
  cursor, and the wheel scrolls. The emulator swallows host keys while it has the
  mouse captured, so the gestures below all use the middle button. The touchpad
  hint lists them too.

  | Gesture | Effect |
  |---|---|
  | **Double middle-click** | Toggle look mode, with an in-view "Look mode on/off" label. In look mode, moving the mouse turns the head (pans the screens; use FREE mode) and the wheel zooms. |
  | **Middle-click** | Hand the mouse to the left (phone) window. Android's pointer appears there and you can click the toolbar icons. It acts after a ~350 ms double-click window. |
  | **Click on the touchpad area** | Give the mouse back to the workspace cursor. |
  | **Ctrl+R** | The emulator gives the mouse back to Windows. |

  This is all emulator-only. On a phone the middle button is untouched.
- **Mouse, uncaptured:** without that feature flag, the host mouse arrives as touch.
  Click-drag on the emulator's main screen. That screen is the UxSpace touchpad, so
  drag moves the cursor, tap clicks, hold grabs a window, and two fingers scroll.
  Ctrl-drag in the emulator gives you two fingers. The cursor hides when idle, so
  drag a little before you click.
- **Keyboard:** `adb emu event text chrome` types on the emulator's `qwerty2`
  evdev device, which the privileged helper reads. Plain typing goes to the drawer
  search box, and Ctrl+Alt hotkeys work (A cycles the layout, X toggles the view
  mode, = and - zoom). While the emulator has the mouse captured, it swallows host
  keystrokes, so use this path for typing.

### Scripting

`uxsim.py send` sends one bridge command and prints the reply, which is either `ok …`
or `err …`:

```bash
python tools/simulator/uxsim.py send pose 30 -10
```

| Command | Effect |
|---|---|
| `pose <yaw> <pitch> [roll]` | Absolute head orientation in degrees; +yaw looks right, +pitch looks up |
| `dof on\|off` | Keep the fake pose stream live (the default), or let it drop |
| `mode free\|pinned` | View mode |
| `recenter` | Align to head |
| `layout` | Cycle the multi-screen layout |
| `panel drawer\|settings\|audio\|notifications\|quick\|close` | Open a desktop panel on the main screen |
| `move <dx> <dy>` | Cursor delta, as a fraction of the touchpad |
| `click`, `rclick`, `scroll <dy>` | Pointer actions |
| `pair <code>` | Pair the helper with a Wireless debugging code |
| `state` | The privileged helper's state (`READY` when up) |
| `launch <package>` | Open an app as a workspace window (also `uxspace.builtin.eyechart`) |
| `hotkey <name>` | Fire a Ctrl+Alt hotkey by its `PrivilegedHotkeys.HK_<name>` suffix, e.g. `hotkey tile_left`. The emulator console can't inject Ctrl+Alt combos as kernel input, so this feeds the app the code the helper would send. |

To capture what the fake glasses show, find the display's SurfaceFlinger ID, then
pass it to `screencap`:

```bash
adb shell dumpsys SurfaceFlinger --display-id
```
```bash
adb exec-out screencap -p -d <id> > glasses.png
```

### Teardown

```bash
python tools/simulator/uxsim.py teardown
```

## Limits

- Head tracking is orientation-only. No 6DOF position is simulated, and nothing
  exercises the `HeadTracking` USB/SDK path or its stall watchdogs.
- The emulator's display is a VIRTUAL display that draws system decorations. Its
  navigation bar takes the bottom edge (the gesture-bar handle), and the workspace is
  letterboxed into what's left, so black bands show at the bottom and sides. The real
  glasses have no system bar and get the full frame.
- Airplane mode (from the taskbar's quick settings) turns Wi-Fi off. That takes down
  Wireless debugging and with it the privileged helper. Turn it back off from the PC
  with `adb shell cmd connectivity airplane-mode disable`, then run `pair`. The same
  happens on a phone.
- Rendering goes through the emulator's GPU translation. Use it for layout and
  behaviour, not for frame-rate or GL-precision work.
- Building without `-PuxspaceNoViture` while the SDK is present gives an APK with
  arm64-only VITURE libraries. It also installs on an x86_64 image, through ARM
  translation, but the flag keeps things simple.
