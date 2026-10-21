# Taskbar redesign

The plan for turning today's minimal taskbar into a full DeX-style bar — a left launch
cluster, the running-app strip in the middle, and a right system-status tray.

## Current state

[`DesktopPresentation`](../app/src/main/kotlin/com/uxspace/desktop/DesktopPresentation.kt)
draws a full-width bar flush with the bottom edge holding three things: an app-drawer
button, a centred strip of one icon per open window (`runningIcons`), and a clock pinned
right. It is a real Android view hierarchy on `Theme.DeviceDefault`, so on a Samsung
device the widgets are styled as One UI.

---

## Target layout

A single horizontal bar in three zones — left and right pinned, middle centred:

```
[ ☰ | ⟳  ▭  🔍 ]      ( app  app  app )      [ 🔊  ✉  📶  ▮▮▮  🔋  12:30 PM ]
  └ left cluster ┘      └ running apps ┘       └─────── status tray ───────┘
```

Build it as a `FrameLayout` with three gravity-pinned children: the left cluster at
`START`, the centre cluster at `CENTER` (centred on the screen, not between the side
clusters, so it doesn't drift as the tray grows) and the status tray at `END`.

---

## Left cluster

The navigation trio, as on Android desktop modes: **Recents**, **Home**, **Back**.

| Item | Action |
|---|---|
| Recents (`ic_nav_recents`) | Opens the drawer on its **Recent** tab — apps recently opened **in the workspace** (Android's system recents API is not available to apps, so "recent" means UxSpace's own launch history, `WorkspaceController.recentApps`). |
| Home (`ic_nav_home`) | Minimises every open window; a second tap restores them — `WorkspaceController.toggleShowDesktop()`. |
| Back (`ic_nav_back`) | Closes a panel (drawer, Settings, Audio) open on this screen; otherwise sends Back to this screen's focused window (`WorkspaceController.sendBackToApp`), which closes the window once its activity stack empties. |

## Centre cluster

**Search**, **App drawer**, a thin **divider** (shown only while apps run), then one
icon per running app window with a small indicator pill under it.

| Item | Action |
|---|---|
| Search (`ic_search`) | Opens the drawer on **All apps**; typing goes to its search field. |
| App drawer (`ic_apps`) | Toggles the drawer on this screen — `WorkspaceController.setDrawerOpen`. |
| Running apps | Tap focuses / restores the window; long-press moves it to the next screen. |

Built-in tools such as the [eye chart](OPTOMETRY.md) live in the drawer as synthetic
entries (`BuiltInApps`), not on the taskbar.

---|---|
| App drawer (`ic_apps`, exists) | Toggles the drawer overlay — `WorkspaceController.setDrawerOpen`. Already wired; just moved to the far left. |
| Divider | A 1dp-wide view at `UxSpaceTheme` divider colour, with vertical margins, separating the launcher from the buttons. |
| Recent apps | A panel of apps recently opened **in the workspace** — Android's system recents API is not available to apps, so "recent" means UxSpace's own launch history. Needs a small most-recently-used list in `WorkspaceController` (`launchApp` pushes onto it). The panel reuses the drawer's overlay surface or is a small popup. |
| Show desktop | Minimises every open window; a second tap restores them. Each `AppWindow` already has a `minimized` flag — add `WorkspaceController.toggleShowDesktop()` that sets/clears it on all windows. |
| Optometry | Opens the [optometry pseudo-app](OPTOMETRY.md) — a Snellen-style chart overlay for dialling in the VITURE focus wheels. |
| Search | Opens the drawer with its search field focused — `DrawerPresentation` already has the field; add a way to open the drawer *and* request focus on it. |

New vector drawables needed: recent apps, show desktop, optometry (e.g. a stylised eye),
search, and the divider.

---

## Running apps (middle)

Already implemented — `runningIcons`, one icon per open window, tap to focus, double-tap
to un-maximise. No change beyond moving it into the centre zone of the new layout. A
later refinement: highlight the icon of the front-most (focused) window.

---

## Status tray (right) — full DeX-style

DeX's right edge isn't a passive readout; it's a clickable surface that opens a quick
settings / notifications panel — wifi toggle, bluetooth, mobile data, volume slider,
brightness slider, the notification list. UxSpace mirrors that.

### Always-visible row

Right-to-left as the user sees it: **Clock + date**, **Battery %**, **Signal**, **Wi-Fi**,
**Message indicator**, **Volume**. Compact icons; the whole row is one big click target
that opens the panel.

| Item | Source | Notes |
|---|---|---|
| Clock + date | `SimpleDateFormat`, re-ticked (exists) | Default: time AM/PM on top, date underneath. 12/24h + date toggles in [SETTINGS.md](SETTINGS.md). |
| Battery | `ACTION_BATTERY_CHANGED` sticky broadcast / `BatteryManager` | Icon + %, charging glyph when plugged in. No permission. |
| Signal | `TelephonyManager` + `TelephonyCallback.SignalStrengthsListener` (API 31+) | Bars 0–4. Hide on Wi-Fi-only devices. |
| Wi-Fi | `ConnectivityManager` network callback + `WifiManager` RSSI → `calculateSignalLevel` | Connected / disconnected + bars. `ACCESS_WIFI_STATE`. |
| Message indicator | `NotificationListenerService` | Dot when there are notifications. Needs notification-access opt-in. |
| Volume | `AudioManager.STREAM_MUSIC` | Speaker icon — opens the panel rather than a separate slider. |

### Quick settings panel (DeX-style)

A pop-up panel anchored to the right of the bar — appears on click of the status row,
dismissed by tapping outside or by a `×`. Layout, top to bottom:

- **Time + date** (large), shortcut to a `Settings` gear (opens phone Settings).
- **Brightness slider** — writes `Settings.System.SCREEN_BRIGHTNESS`. Needs
  `WRITE_SETTINGS`, or — easier and uniform with the rest of this list — through the
  privileged helper as `settings put system screen_brightness N`.
- **Volume slider** — `AudioManager.setStreamVolume(STREAM_MUSIC, ...)`. No permission.
- **Quick toggles row** — Wi-Fi, Bluetooth, Mobile data, Aeroplane, Auto-rotate, Torch.
  Implemented through the privileged helper (`cmd wifi enabled`, `svc bluetooth enable`,
  `svc data enable`, etc.) so UxSpace doesn't need each toggle's own permission. State is
  read normally (no permission needed for read).
- **Notifications list** — scrollable. `NotificationListenerService` mirrors current
  notifications; tap dispatches the notification's content intent; long-press = dismiss.

### Wiring

A new `SystemStatus` helper in `:app` owns the read side: registers
`ACTION_BATTERY_CHANGED` receiver, `TelephonyCallback` for signal,
`ConnectivityManager` callback for Wi-Fi, `AudioManager` listener for volume,
`NotificationListenerService` for notifications. Exposes flow-style change listeners.

A `QuickSettingsPanel` view (built in `DesktopPresentation` as a child Window /
floating `View` over the workspace) binds the tray + panel views to `SystemStatus` and
calls into either Android APIs (volume, brightness via WRITE_SETTINGS) or the
[privileged helper](PRIVILEGE.md) (wifi/bluetooth/mobile data toggles, brightness as
fallback) for the write side.

The panel is rendered as a normal Android `View` inside `DesktopPresentation`'s view
tree (same `Theme.DeviceDefault` styling as the taskbar), shown/hidden via
`View.VISIBLE` — no extra `UiScreen` or virtual display needed.

---

## Multi-screen placement

With multi-screen layouts (Two SBS, Three SBS, V/H/V) the user picks where the
taskbar lives:

- **Main-screen only** — one taskbar on the layout's designated central screen (the
  H screen in V/H/V, the middle slot in Three SBS, slot 0 in Two SBS / Single Wide).
  Cleanest visual; only one bar to scan; the side screens are app-only.
- **Spanned across screens** — one continuous bar that visually crosses the screen
  boundaries. Either rendered as a GL-drawn overlay sitting on top of the screen
  meshes, or as width-portioned per-screen pieces that line up at the world edges
  where adjacent screens butt-join. Reads as "one giant monitor" with apps grouped
  by screen.
- **Per-screen** (default today) — each screen's own `DesktopPresentation`
  renders its own taskbar, sized to the screen's pixel width (narrow on V screens,
  full on H). Per-screen filter means each bar only lists apps actually launched on
  that screen.

Choice is exposed in the [settings screen](SETTINGS.md) with a per-layout default
(Single / SBS → main-only, V/H/V → main-on-H, etc.) overridable globally. Stored in
`WorkspaceController`; overrides `Screen.showTaskbar` at render time.

## Auto-hide / always-show

The bar's visibility is a user choice, stored in [`WorkspaceSettings`](SETTINGS.md):

- **Always shown** (default) — current behaviour: the bar sits at the bottom edge of the
  workspace permanently, the desktop area reserves `TASKBAR_RESERVE` (currently 8.5% of
  the desktop height) for it.
- **Auto-hide** — the bar collapses to a thin reveal strip (a couple of dp tall) along
  the bottom edge. It animates up to full height when the cursor enters a hot-zone near
  the bottom of the workspace (or while the quick-settings panel is open), and slides
  back down once the cursor leaves it and the panel is closed. The desktop area expands
  to the full render band when the bar is collapsed, so app windows can use the extra
  room.

A toggle in the [settings screen](SETTINGS.md) plus a long-press menu item on the bar
itself ("Auto-hide taskbar") flip between the two modes.

**Touch points.** `WorkspaceSettings` (new `taskbarVisibility` enum); `DesktopPresentation`
(reveal-strip view + slide animation, cursor-Y listener); `AppWindow.layout`
(`TASKBAR_RESERVE` switches between the current value and 0 based on the setting); the
settings screen.

---

## Build order

1. Restructure `buildTaskbar()` into the three-zone layout, all buttons present (most as
   placeholders that just toast). No status reads yet.
2. Left cluster wiring: divider, **Show desktop** (`toggleShowDesktop`), **Search** (open
   drawer + focus), **Optometry** (open the chart overlay), **Recent apps** (workspace
   MRU list + panel).
3. `SystemStatus` helper for the read side; bind the always-visible row — clock (done),
   battery, volume, Wi-Fi, signal.
4. Quick-settings panel scaffold: click the right cluster to open/close. Inside: time,
   brightness slider, volume slider.
5. Quick toggles: Wi-Fi, Bluetooth, Mobile data, Aeroplane, Torch — through the
   privileged helper.
6. Notifications: notification-access opt-in flow, then the list view in the panel.
7. Message indicator wired from the notification listener.

Each step is a separate commit.

**Touch points.** `DesktopPresentation` (three-zone layout, tray row, quick-settings
panel view); new `SystemStatus` helper; new `QuickSettingsPanel` view + a small layout
include; `WorkspaceController` (`toggleShowDesktop`, `setOptometryOpen`, workspace MRU
list, open-drawer-with-search); a `IPrivilegedService.runShell(cmd: String)` method for
the toggles; new vector drawables (divider, recent, show-desktop, optometry, search,
battery, signal, wifi, volume, brightness, message, settings); notification-access
opt-in.
