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

## Status tray (right) — DeX-style (built)

Modelled on phone DeX's right edge. Left to right:

| Item | Does | Source |
|---|---|---|
| Notification icons | Up to 3 apps' small icons, then "+N"; click opens the notification panel | `NotificationCenter` (a `NotificationListenerService`) |
| ^ | Opens the quick-settings panel | — |
| Keyboard | Toggles the phone IME (types into the drawer search or the focused app) | `WorkspaceController.toggleKeyboard`, set by `MainActivity` |
| Volume | Opens the audio panel; icon shows the active output | `SystemStatus` |
| Screenshot | Captures the workspace | `WorkspaceController.capture()` |
| Wi-Fi | Fan with 0–4 lit segments; slashed when not connected, hidden when Wi-Fi is off | `ConnectivityManager` callback, RSSI → bars |
| Signal | Four bars; slashed in airplane mode, hidden without a SIM | `TelephonyCallback.SignalStrengthsListener` |
| Battery | Icon + %, ⚡ when charging | `ACTION_BATTERY_CHANGED` |
| Gear | UxSpace's own settings panel | — |
| Clock + date | Click opens the notification panel, as in DeX | — |

Search stays in the centre cluster rather than being duplicated here. Wi-Fi and signal
are drawn in code (`StatusGlyphs.kt`) so they can show a level; the rest are vectors.

### Panels

Both open in the shared centred modal slot (`WorkspaceController.TrayPanel`, one open at a
time with the drawer / settings / audio panels), so the renderer re-composites them over
app windows like the others.

- **Notifications** (`NotificationsPanelView`) — one card per notification: app, age,
  title, text. Click opens the app in a workspace window (its own PendingIntent would
  launch on the phone's screen) and clears auto-cancel ones; × dismisses; "Clear all".
- **Quick settings** (`QuickSettingsPanelView`) — date, Wi-Fi / Bluetooth / Do not
  disturb / Airplane mode tiles, a brightness slider, and a link to UxSpace settings.

### Privilege

Apps can't flip those radios or read other apps' notifications without a Settings
opt-in, so the [privileged helper](PRIVILEGE.md) does both, through two narrow calls
rather than a general shell:

- `grantNotificationListener()` runs `cmd notification allow_listener` for UxSpace's own
  listener (fixed in the helper). `NotificationCenter` calls it once the helper is READY.
- `setQuickSetting(name, value)` — whitelisted names only: `wifi` / `bluetooth`
  (`svc …`), `airplane` (`cmd connectivity airplane-mode`), `dnd`
  (`cmd notification set_dnd`), `brightness` (`settings put system …`).

State is read without privilege: `Settings.Global` (Wi-Fi, Bluetooth, airplane),
`NotificationManager.currentInterruptionFilter` (DND), `Settings.System` (brightness).

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
