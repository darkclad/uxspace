# UxSpace

> **U✦Space** — *Your space. Any screen.*

Turn **any AR/XR glasses** (or any external display) into a **head-tracked spatial
workspace** — multiple virtual monitors floating in front of you, arranged in selectable
layouts, that stay fixed in space as you look around.

UxSpace is vendor-agnostic by design: head tracking sits behind an interface, so adding
another vendor (XREAL, Rokid, OpenXR, …) means writing one provider, not rewriting the
app. It was **developed and tested on [VITURE](https://www.viture.com) glasses**, which
is the current reference implementation.

This is a monorepo with two independent implementations of the same idea, sharing a
common architecture vocabulary (`tracking` / `viture` / `spatial`), the `UxSpace`
identifier, and the proprietary VITURE SDK:

| Platform | Path | What it is | Status |
|----------|------|------------|--------|
| **Android** | [`Android/`](Android/) | A DeX-style spatial desktop driven from the phone — taskbar, app drawer, multi-window framework, phone-as-trackpad, BT keyboard/mouse. The glasses are a USB-C external display the app owns. | **Alpha PoC** — desktop, windowing, head tracking, hotkeys, raw-mouse path all work today; runs on an Android emulator without glasses ([simulator](Android/docs/SIMULATOR.md)). |
| **Windows** | [`Windows/`](Windows/) | Up to three **virtual monitors** captured and rendered into the glasses in stereo, with 6DOF head tracking. Uses a forked IddCx virtual-display driver. | **W3 in progress** — driver, capture, stereo output, head tracking, multi-monitor live; settings/lifecycle still ahead. |

Android is the canonical reference for the shared layering; the Windows side mirrors
its module contracts. See each project's `docs/ARCHITECTURE.md`.

## Repository layout

```
UxSpace/
├── Android/      Android app (Kotlin/Compose + native VITURE SDK)  — see Android/README.md
├── Windows/      Windows app + IddCx driver (C++17 / DX11 / WDK)   — see Windows/README.md
└── Viture/       Proprietary VITURE SDK (Android + Windows)        — NOT committed (see below)
```

Both projects were merged in with their full upstream histories preserved via
`git subtree`; the original per-project commits are reachable as ancestors of `main`.

## Developing without glasses: the Android simulator

The Android desktop runs on a stock **Android emulator**, no glasses or phone needed:
the emulator's second display stands in for the glasses, a small PC tool
(`Android/tools/simulator/uxsim.py`) fakes head pose and drives the workspace over
`adb`, and your mouse and keyboard work inside it like a Bluetooth mouse and keyboard
on the phone. It builds without the VITURE SDK (`-PuxspaceNoViture`), so it is also the
quickest way to try UxSpace from a fresh clone. Setup and daily steps:
[`Android/docs/SIMULATOR.md`](Android/docs/SIMULATOR.md).

## The VITURE SDK is not committed

`Viture/` holds the proprietary VITURE SDK (~174 MB: native `.so`/`.dll` binaries,
headers, demos). It **must not be committed or redistributed** and is git-ignored at
the repo root.

Download it from the [VITURE Developer portal](https://www.viture.com/developer) and
place it at `Viture/SDK/` so both builds can find it:

- **Android** vendors it into the `:glasses` module's `jniLibs` / `cpp/include` (paths
  git-ignored). See `Android/docs/ARCHITECTURE.md`.
- **Windows** resolves `Viture/SDK/Windows` automatically (`viture/CMakeLists.txt` tries
  a vendored `viture/sdk/` copy first, then this sibling path; override with
  `-DUXSPACE_VITURE_SDK_DIR=<path>`). Five runtime DLLs ship next to the exe.

## Hardware

UxSpace targets any AR/XR glasses that present as an external display and can supply a
head pose. The capability tiers it adapts to:

- **6DOF (rotation + translation)** — the full experience; the workspace stays fixed in
  world space as you move and look around.
- **3DOF (rotation only)** — the same tracker interface degrades gracefully; the UI
  hides translation controls.
- **No head tracker / any external display** — `PINNED` view + multiple virtual screens
  keep working.

**Reference hardware (developed and tested on):**

- **VITURE Luma / Luma Pro / Carina** — 6DOF via the SDK's bundled VIO.
- **VITURE Gen1 / Gen2** — 3DOF.

Other vendors (XREAL, Rokid, OpenXR, …) are not yet implemented but are a deliberate,
low-friction extension point — only `app` knows which vendor is in use.

## License

The Android project is licensed under the Apache License 2.0 (`Android/LICENSE`). The
Windows `driver/` is a fork of Microsoft's `IddSampleDriver` and retains its MIT license
(`Windows/driver/UPSTREAM_LICENSE`). The VITURE SDK is proprietary and is not part of
this repository.

## Contact

Maintained by Demian Vladi — [demianvladi@gmail.com](mailto:demianvladi@gmail.com).
