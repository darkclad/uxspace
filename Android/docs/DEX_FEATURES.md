# DeX features to borrow

What else Samsung DeX does well that UxSpace doesn't have yet, ranked by payoff on
glasses, with a design sketch and touch points for each. Already done and out of scope
here: the DeX-style taskbar and tray, drawer, panels and window chrome
([TASKBAR.md](TASKBAR.md)), notifications and quick settings, and the
[eye chart](OPTOMETRY.md).

Only DeX's behaviour and measurements are borrowed. No Samsung code, icons or other
assets go into this repo.

**Constraints that shape every item**

- Every UxSpace UI element lives in a slot's `DesktopPresentation` view tree or is drawn
  by `WorkspaceRenderer` (HUD overlays such as the toolbar and the eye chart). Clicks
  reach views through `UiScreen.dispatchTap`, so new UI follows the same two routes.
- Keyboard input reaches UxSpace through the privileged helper's evdev reader
  (`PrivilegedServer` → `UxSpaceApp` `hotkeyHandler`). The keyboard stays **ungrabbed**
  so focused apps still type, and One UI intercepts the **Meta (Win) key** for its own
  launcher. New shortcuts therefore use UxSpace's **Ctrl+Alt** prefix (as Ctrl+Alt+Z/X/A
  do today); Win-key variants only if the helper can claim them reliably.
- Window state is `WorkspaceController.WindowMode` (NORMAL, MAXIMIZED, FULLSCREEN,
  TILED_LEFT, TILED_RIGHT) held per `AppWindow`, laid out by `WorkspaceRenderer`.
- Each feature is checked on the [simulator](SIMULATOR.md) first, then on the glasses.

---

## 1. Alt+Tab window switcher

**DeX:** holding Alt and tapping Tab shows a centred row of window thumbnails; Tab
advances, releasing Alt switches.

**UxSpace:** the fastest way between windows when you can't glance around a crowded
taskbar. Hold-Alt + Tab cycles; Shift+Tab goes back; release focuses the selection.

- **Keys:** the helper's reader already tracks modifiers. Report Alt down/up and Tab
  to the app while Alt is held. Alt+Tab reaches the focused app too, since the keyboard
  isn't grabbed, so check whether apps react and grab Tab for the duration if they do.
- **UI:** a renderer HUD overlay (like the layout announcement), head-locked and
  centred: one card per window with its live surface texture as the thumbnail (the
  renderer already has every window's texture) plus icon and label. Order is
  most-recently-focused, which means `focusApp` keeps an MRU list.
- **Touch points:** `PrivilegedServer` (Alt/Tab events), `UxSpaceApp`,
  `WorkspaceController` (switcher state + window MRU), `WorkspaceRenderer`
  (`drawWindowSwitcher`).
- **Effort:** medium.

## 2. Pinned taskbar apps and taskbar icon menus

**DeX:** favourites are pinned next to running apps; right-clicking a taskbar icon gives
Close, Pin/Unpin and window options.

**UxSpace:** pinned apps sit in the centre cluster before the running ones (a running
pinned app shows its indicator pill instead of a second icon). Right-click on any icon
opens a small menu: Open / Close, Pin / Unpin, Move to screen N (today a hidden
long-press), Maximize.

- **Storage:** a `TaskbarPinsStore` like `DesktopShortcutsStore` (SharedPreferences,
  ordered package list).
- **Drawer:** add "Pin to taskbar" to drawer items' right-click.
- **Touch points:** `DesktopPresentation` (centre cluster, icon menu), new
  `TaskbarPinsStore`, `WorkspaceRenderer` right-click routing (taskbar hit →
  Presentation instead of the desktop context menu).
- **Effort:** medium.

## 3. Keyboard window shortcuts

**DeX:** Win+arrows snap left/right and maximize/restore, Win+D shows the desktop,
Alt+F4 closes, Win opens the app drawer.

**UxSpace:** the same actions on Ctrl+Alt (see Constraints):

| Keys | Action |
|---|---|
| Ctrl+Alt+← / → | Tile the focused window left / right (again: restore) |
| Ctrl+Alt+↑ / ↓ | Maximize / restore-then-minimize |
| Ctrl+Alt+D | Show desktop (taskbar Home) |
| Ctrl+Alt+Q | Close the focused window |
| Ctrl+Alt+Space | Open the drawer on the screen under the cursor |
| Ctrl+Alt+Shift+← / → | Move the focused window to the previous / next screen |

Check every combo against the existing hotkey set (Ctrl+Alt+Z/X/R/C/A/+/-) and against
what One UI itself takes. Alt+F4 itself only if the helper can stop it reaching the app.

- **Touch points:** `PrivilegedServer` key table, `UxSpaceApp.hotkeyHandler`,
  `WorkspaceController` (act on the focused window), docs/TOUCHPAD.md hotkey list.
- **Effort:** small.

## 4. Quarter snapping

**DeX:** dragging a window to a corner snaps it to that quarter.

**UxSpace:** add TILED_TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT / BOTTOM_RIGHT. Corner zones
win over edge zones within `SNAP_EDGE_PX` of both edges; the snap preview already shows
any rect.

- **Touch points:** `WorkspaceController.WindowMode`, `WorkspaceRenderer` snap-zone
  logic (~`snapRectFor`) and its `when`s over modes, `AppWindow` tiled bounds, and the
  Ctrl+Alt arrow shortcuts from 3 (← then ↑ = top-left).
- **Effort:** small–medium (every `when (mode)` needs the new cases).

## 5. Hover tooltips on taskbar icons

**DeX:** resting the pointer on a taskbar icon shows the app's name.

**UxSpace:** after ~600 ms of the cursor over a taskbar cell, a small dark pill with the
label appears above it; it hides on move-off or click. Useful because many icons look
alike at glasses resolution.

- **Hover:** the renderer knows the cursor's slot pixel every frame (it already drives
  taskbar auto-hide from it). Publish a "taskbar hover at x" to the slot, which maps x
  to a cell.
- **Touch points:** `WorkspaceRenderer` (hover publish), `WorkspaceController`
  listener, `DesktopPresentation` (tooltip view above the taskbar).
- **Effort:** small.

## 6. Desktop right-click menu entries

**DeX:** the desktop menu offers wallpaper, display settings, new folder and sort icons.

**UxSpace:** extend the existing desktop context menu with Change wallpaper (opens
Settings → Desktop), Sort shortcuts (by name), New folder (needs 7), and Eye chart.

- **Touch points:** `DesktopPresentation` context menu, `DesktopShortcutsStore` (sort).
- **Effort:** small.

## 7. Desktop folders

**DeX:** desktop shortcuts can be grouped into folders.

**UxSpace:** drop a shortcut onto another to make a folder; clicking the folder opens a
small card (same `PanelStyle`) with its apps; drag out to remove. A folder with one app
left dissolves.

- **Storage:** `DesktopShortcutsStore` grows a folder entry type (id, name, members).
- **Touch points:** `DesktopShortcutsStore`, `DesktopShortcutsView` (folder icon = 2×2
  mini icons), the drop handling in `WorkspaceRenderer.handleArmedDrop`.
- **Effort:** medium.

## 8. Remembered window size and position per app

**DeX:** an app reopens at the size and position it last had.

**UxSpace:** when a window closes, store its mode and its bounds as fractions of its
slot (so they survive layout changes); restore them in `launchApp`. Off by default for
FULLSCREEN, since an app reopening fullscreen surprises people.

- **Touch points:** a `WindowMemoryStore`, `WorkspaceRenderer` close / launch paths,
  `AppWindow` (initial bounds from memory), Settings → Windows toggle.
- **Effort:** small–medium.

## 9. Caption menu from the window's app icon

**DeX:** the app icon in the caption opens a menu (always on top, resize options, open
on the phone).

**UxSpace:** clicking the caption icon opens a small menu: Always on top, Snap left /
right / quarters, Move to screen N, Open on phone (launch the app on display 0 and close
the window), Close.

- **Always on top** needs a per-window flag the renderer's back-to-front order respects.
- **Touch points:** `WindowChromeView` (icon click → menu), `WorkspaceController` /
  `WorkspaceRenderer` (always-on-top ordering, open-on-phone).
- **Effort:** medium.

## 10. Multiple virtual desktops

**DeX (One UI 8):** several workspaces, each with its own windows, switched from the
taskbar or with keys.

**UxSpace:** N workspaces per layout; each `AppWindow` carries a workspace id and the
renderer only draws and hit-tests the active one's windows (others stay alive,
effectively minimized). Switch with Ctrl+Alt+PgUp / PgDn or a taskbar workspace
indicator; "Move to workspace N" joins the menus from 2 and 9.

- **Touch points:** `AppWindow`, `WorkspaceRenderer` (filter by workspace everywhere
  windows are iterated), `WorkspaceController` (active workspace, listeners), taskbar
  indicator, window MRU from 1 per workspace.
- **Effort:** large. Do it after 1–3 so the switching UX exists to build on.

## 11. Universal search

**DeX:** Finder searches apps, contacts, files and settings.

**UxSpace:** the drawer's search grows sections under the app grid: Settings pages (a
static list of `android.provider.Settings` intents), Contacts (READ_CONTACTS), and
recent files (MediaStore). Each result launches into a workspace window.

- **Permissions:** contacts and media are runtime permissions; ask on first use from the
  phone side, never on the glasses.
- **Touch points:** `DrawerView` (sectioned results), new `search/` providers.
- **Effort:** large.

---

## Suggested order

1. **Quick wins:** 3 (shortcuts), 5 (tooltips), 6 (desktop menu entries).
2. **Window handling:** 1 (Alt+Tab), 4 (quarter snapping), then 2 (pinned apps and
   icon menus).
3. **Persistence:** 8 (window memory), 9 (caption menu), 7 (folders).
4. **Big pieces:** 10 (virtual desktops), 11 (universal search).

Each item is its own commit or PR, verified on the simulator, then on the glasses.
