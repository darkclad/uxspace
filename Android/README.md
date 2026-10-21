# UxSpace

An Android app that turns VITURE glasses (or any external display) into a **DeX-style
spatial workspace** — a desktop with a taskbar and an app drawer, floating in front of
you, driven from the phone as a trackpad and from a Bluetooth keyboard / mouse.

> **Status — alpha, proof-of-concept.** This is a working PoC, not a polished product.
> The desktop, app drawer, multi-window framework, head tracking, hotkeys, raw-mouse
> path, and the right-click context menu all work today; expect rough edges, occasional
> USB / BT races, debug overlays, and breaking changes between commits. See
> [§ Project standing](#project-standing) below.
>
> **YMMV.** This is built and tested on one specific hardware combination (see
> [§ Project standing](#project-standing)) and currently expects to be edited as much
> as it is run — some **coding** and **"assembly"** will likely be required to make it
> work on your own phone / glasses pair: vendoring the VITURE SDK, tweaking constants,
> chasing OEM-specific BT/USB quirks, possibly patching the source. Treat this repo
> like a starter kit, not an installer.

---

## How it works

The glasses are a USB-C external display. UxSpace owns that display with a
`Presentation` hosting an OpenGL scene:

```
VITURE glasses ──USB-C──▶ phone
   │  external Display
   ▼
Presentation + GLSurfaceView            ← 3D scene; camera = inverse head pose
   ├─ the desktop  — a UiScreen: a real Android UI (taskbar, app drawer,
   │                 wallpaper) on its own VirtualDisplay, textured onto a quad
   └─ app windows  — each a VirtualDisplay running an installed app, on a quad
```

The desktop is a real Android view hierarchy on `Theme.DeviceDefault` — on a Samsung
device it is styled as One UI, the same way Samsung DeX is. Launched apps render onto
their own *trusted* virtual displays. Head pose (from the native VITURE SDK) drives the
camera: in **FREE** view the workspace stays fixed in space as you look around; in
**PINNED** view it follows your head. With no head tracker, PINNED view and multiple
screens still work on any external display.

### Constraints

A stock, non-rooted Android app, which bounds what is possible:

- **Launching third-party apps needs shell-uid privilege.** Placing an app on a virtual
  display is not allowed for normal apps. UxSpace activates its own shell-uid helper on
  first run through Android 11+ **Wireless Debugging** (see
  [`docs/PRIVILEGE.md`](docs/PRIVILEGE.md)). You enable Wireless Debugging once, type
  the 6-digit pairing code into UxSpace, and from then on UxSpace bootstraps the helper
  itself on every launch — no separate app required.
- **The phone is the primary input device.** Trackpad gestures (one finger moves the
  cursor, two fingers scroll, hold-to-drag, etc.). A Bluetooth mouse / keyboard works
  too — the privileged helper grabs the mouse via `EVIOCGRAB` so the phone's system UI
  never sees the events, and the cursor lives entirely inside the workspace.

---

## Project standing

UxSpace is **alpha / PoC**. It is currently built and tested on a **Samsung Galaxy
Z Fold 7** (One UI, latest stable update) paired with **VITURE Luma Ultra** glasses.
Anything similar (recent Samsung phone with USB-C DisplayPort, recent VITURE glasses
with on-device DOF or Carina VIO) should work — but expect to do some debugging.

| Area                              | State                                                |
|-----------------------------------|------------------------------------------------------|
| Desktop, taskbar, drawer          | ✅  Working on the glasses                            |
| Multi-window, drag / resize       | ✅  Working                                           |
| Head tracking (Carina VIO)        | ✅  Working                                           |
| Multiple slot layouts             | ✅  Single / Single-Wide / SBS / V-H-V                |
| Right-click context menu          | ✅  Working                                           |
| Auto-hide taskbar                 | ✅  Working (Settings → Taskbar)                      |
| Privileged helper bootstrap       | ✅  Working (Wireless-Debugging pairing flow)         |
| Raw BT-mouse path (no phone leak) | ✅  Working                                           |
| Hotkey set (Ctrl+Alt+Z/X/R/C/A/+/-/Wheel) | ✅  Working — mirrors the Windows companion app |
| BT keyboard pairing kills USB DOF | ⚠️  Auto-rescan + user toast; not fully prevented    |
| Recording / capture               | ⚠️  Works but feature-flagged                        |
| Audio panel                       | ⚠️  Functional but rough                             |
| Polished install / first-run UX   | ❌  Not yet                                           |

Tested combination: **Samsung Galaxy Z Fold 7** (latest One UI) + **VITURE Luma Ultra**.
Other Android 11+ phones with USB-C DisplayPort, and other recent VITURE glasses,
should work but are unverified — Samsung-specific quirks (Meta-key launcher intercept,
BT/USB stack races) are the largest source of rough edges. VITURE Pro / Carina was the
original target hardware; Luma Ultra works on the current code path but may need
device-pid checks or SDK updates if VITURE ships them.

The repo is moving fast; commits routinely break things deliberately as the design
evolves. If you want a known-good revision, check the tags (when they exist) or pin to
a specific commit.

---

## Modules

| Module    | What it is                                              |
|-----------|---------------------------------------------------------|
| `app`     | The phone control panel and the desktop UI layout       |
| `spatial` | The 3D workspace — camera, screens, the GL renderer     |
| `glasses` | VITURE head tracking and the native-SDK JNI bridge      |

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the layered design. To work on the
desktop UI without glasses, run it on an Android emulator: see
[`docs/SIMULATOR.md`](docs/SIMULATOR.md).

---

## Prerequisites

You will need:

- **A phone running Android 11+** (API 30+) with **USB-C DisplayPort Alt-Mode** and
  Wireless Debugging available. arm64 only — the native build is `arm64-v8a`.
  Reference: **Samsung Galaxy Z Fold 7** on the latest One UI update.
- **VITURE glasses** (any external USB-C display will work, but head tracking needs
  the VITURE hardware + SDK — see [Getting the VITURE SDK](#getting-the-viture-sdk)).
  Reference: **VITURE Luma Ultra**. Pro / Carina is the original target and should
  also work; if VITURE introduces a new product id, expect to add it to the JNI
  bridge's device-id whitelist.
- A **dev machine** running Windows 10/11, macOS, or Linux with the build tools below.

### Build tools

| Tool                  | Version                  | Notes                                          |
|-----------------------|--------------------------|------------------------------------------------|
| **JDK**               | 17 or 21                 | Bundled with Android Studio, or install separately |
| **Android Studio**    | Hedgehog (2025.3) or newer with **AGP 9.x** support | The IDE bundles SDK manager, NDK, CMake, ADB |
| **Android SDK**       | Platform **API 36**       | Install via Android Studio → SDK Manager     |
| **Android Build-Tools** | 36.x                    | Same                                           |
| **Android NDK**       | `30.0.14904198`          | Pinned in `glasses/build.gradle.kts`           |
| **CMake**             | `4.1.2`                  | Pinned in `glasses/build.gradle.kts`           |
| **Gradle**            | 9.x (wrapper-managed)    | Comes with the repo via `./gradlew`            |
| **Git**               | any recent               | For `git clone`                                |
| **ADB**               | bundled with platform-tools | Used to flash and to drive Wireless Debugging |

You do **not** need Android Studio if you prefer the command line — the SDK
command-line tools + a JDK are enough. Studio is recommended for development.

### Installing the prerequisites

**Windows (PowerShell, Chocolatey shown — adapt as needed):**

```powershell
choco install -y temurin17 git
# Then download Android Studio from https://developer.android.com/studio
```

**macOS (Homebrew):**

```bash
brew install --cask temurin17 android-studio
brew install git
```

**Linux (Debian/Ubuntu):**

```bash
sudo apt install -y openjdk-17-jdk git
# Download Android Studio from https://developer.android.com/studio
```

Inside Android Studio: **SDK Manager → SDK Platforms** → check *Android 14 (API 36)*;
**SDK Tools** → check *Android SDK Build-Tools 36*, *NDK (Side by side) 30.0.14904198*,
*CMake 4.1.2*, *Android SDK Platform-Tools*. Apply.

---

## Getting the source

```bash
git clone https://github.com/darkclad/uxspace.git
cd uxspace/android
```

The Android source lives under the `android/` subdirectory of the monorepo (the
sibling `windows/` directory holds the Windows companion app — same hotkey set,
different host).

---

## Getting the VITURE SDK

The native VITURE SDK (`.so` + C headers, proprietary) is **not** committed to this
repo and must be vendored locally.

Without it, the project still builds, but head tracking is disabled and the glasses
behave as a passive external display (PINNED view + multi-screen still work; FREE view
is unavailable).

### 1. Request developer access

Apply for access to the VITURE Android SDK through the official developer programme:

- VITURE developer portal — https://developer.viture.com (search for "Android SDK"
  or the IMU library; the exact path moves between releases).
- Or email VITURE developer support and ask for the latest Android SDK archive.

VITURE typically responds with a download link to a ZIP containing:

```
viture-android-sdk/
├── libs/arm64-v8a/
│   ├── libcarina_vio.so
│   ├── libcloud_protocol.so
│   └── libglasses.so
└── include/
    ├── viture_camera_provider.h
    ├── viture_device.h
    ├── viture_device_carina.h
    ├── viture_glasses_provider.h
    ├── viture_macros_public.h
    ├── viture_protocol_public.h
    ├── viture_result.h
    ├── viture_stat_reporter.h
    └── viture_version.h
```

### 2. Vendor it into the repo

Copy the `.so` files into `glasses/src/main/jniLibs/arm64-v8a/` and the headers into
`glasses/src/main/cpp/include/`. Both directories are git-ignored.

```bash
# from the repo root (android/)
mkdir -p glasses/src/main/jniLibs/arm64-v8a
mkdir -p glasses/src/main/cpp/include
cp /path/to/viture-android-sdk/libs/arm64-v8a/*.so glasses/src/main/jniLibs/arm64-v8a/
cp /path/to/viture-android-sdk/include/*.h         glasses/src/main/cpp/include/
```

To verify the vendoring worked:

```bash
ls glasses/src/main/jniLibs/arm64-v8a
# libcarina_vio.so  libcloud_protocol.so  libglasses.so

ls glasses/src/main/cpp/include
# (the nine viture_*.h headers above)
```

The CMake build at `glasses/src/main/cpp/CMakeLists.txt` links against the `.so`s and
`#include`s the headers from these paths.

> Convention used by the maintainer's local checkout: the upstream SDK is kept at
> `..\Viture\SDK\` (a sibling directory to the repo), and `jniLibs` / `include`
> are just copies of those files. You can follow the same layout or skip the
> sibling directory entirely.

### 3. Licensing

Read the VITURE SDK license — it dictates what you can redistribute. UxSpace itself is
Apache-2.0 ([LICENSE](LICENSE)) but **does not** redistribute the VITURE binaries.

---

## Configure

Create `local.properties` in the repo root (or let Android Studio do it on first
open). It must point `sdk.dir` at your Android SDK:

```properties
# Windows
sdk.dir=C\:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk

# macOS
sdk.dir=/Users/YOUR_USER/Library/Android/sdk

# Linux
sdk.dir=/home/YOUR_USER/Android/Sdk
```

`ndk.dir` is optional — Gradle finds the NDK from `sdk.dir` plus the version pinned in
`glasses/build.gradle.kts`.

`local.properties` is git-ignored.

---

## Build

From the repo root (`android/`):

```bash
# Windows (PowerShell or cmd)
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

The output APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Install on the phone

Plug the phone in over USB (Developer options → USB debugging enabled), then:

```bash
./gradlew :app:installDebug
```

Or install the APK directly:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build flavours / clean

```bash
./gradlew clean                 # clean build outputs
./gradlew :app:assembleRelease  # release build (unsigned by default)
```

---

## Configure on the phone (first-run)

1. **Pair the privileged helper.** Open UxSpace. On first launch the setup screen asks
   you to enable **Settings → Developer options → Wireless debugging** on the phone,
   tap *Pair device with a pairing code*, and type the 6-digit code into UxSpace. From
   then on UxSpace bootstraps its own shell-uid helper on every launch (no Shizuku, no
   second app). See [`docs/PRIVILEGE.md`](docs/PRIVILEGE.md) for the details.

2. **Plug in the glasses.** A USB permission dialog will appear the first time —
   accept it. UxSpace claims the glasses display and head tracking should come up
   within ~2 seconds.

3. **Optional: pair a Bluetooth keyboard + mouse.** Standard Android pairing flow. The
   privileged helper grabs the mouse exclusively (`EVIOCGRAB`) so its events go
   straight to the workspace cursor — the phone's system UI never sees them.

4. **Hotkeys** (matches the Windows companion app at `windows/`):

   | Combo                | Action                            |
   |----------------------|-----------------------------------|
   | `Ctrl+Alt+Z`         | Cycle screen band                 |
   | `Ctrl+Alt+X`         | Toggle PINNED / FREE              |
   | `Ctrl+Alt+R`         | SDK recenter                      |
   | `Ctrl+Alt+C`         | Anchor pose                       |
   | `Ctrl+Alt+A`         | Cycle layout (FREE only)          |
   | `Ctrl+Alt + scroll`  | Workspace zoom                    |
   | `Ctrl+Alt + drag`    | Pan zoomed viewport (PINNED)      |
   | `Ctrl+Alt` held      | Keymap legend overlay             |

   Samsung's One UI launcher intercepts `Meta` — that is why UxSpace uses `Ctrl+Alt`
   on both Android and Windows.

5. **Right-click on the desktop** for: Arrange icons, Change wallpaper, Settings,
   Cycle layout, Recenter view, Lock / Unlock, Reset zoom, Show / Hide taskbar.

---

## Troubleshooting

- **"No VITURE glasses found on USB."** The display side of the glasses sometimes
  enumerates before the USB IMU endpoint. UxSpace retries on the next
  `USB_DEVICE_ATTACHED` — unplug and re-plug. If that fails, the watchdog will toast
  you after 3 failed rescans.
- **"DOF lost" after pairing a BT keyboard.** Samsung's BT / USB stack interaction can
  kill the glasses USB endpoint. UxSpace auto-rescans (up to 3 attempts) and pops a
  toast if it can't recover. Unplug + re-plug usually fixes it.
- **`./gradlew` fails on Windows with permission errors.** Make sure Wireless
  Debugging is on, but also make sure your antivirus isn't blocking `gradlew.bat`.
- **`UnsatisfiedLinkError: libglasses.so`.** You haven't vendored the VITURE SDK — see
  [Getting the VITURE SDK](#getting-the-viture-sdk).
- **Mouse cursor jumps to phone-screen edges.** Older builds — the raw EV_REL path
  shipped recently. Pull the latest `main`.

---

## Acknowledgements

UxSpace stands on the shoulders of the **[Shizuku](https://shizuku.rikka.app/)**
project by [RikkaApps](https://github.com/RikkaApps). Shizuku pioneered the mechanism
that makes apps like this one possible on a stock, non-rooted Android phone: borrow
ADB-shell privilege at runtime, bootstrapped from the device itself over Wireless
Debugging — no PC, no root. UxSpace shipped on top of the Shizuku app first; the
in-app pairing flow now built into UxSpace is modelled directly on Shizuku's.
**Thank you.**

Thanks also to **[libadb-android](https://github.com/MuntashirAkon/libadb-android)**
by Muntashir Al-Islam — the embedded ADB client that lets UxSpace pair and connect to
its own device's wireless-debugging service without a separate Shizuku install.

---

## License

Apache-2.0 — see [LICENSE](LICENSE). The vendored VITURE SDK is **not** covered by
this licence; obtain and use it under VITURE's terms.

## Contact

Maintained by Demian Vladi — [demianvladi@gmail.com](mailto:demianvladi@gmail.com).
