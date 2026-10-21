# Window management

How app windows behave in the workspace — what exists today, and the plan for snapping
(tiling) and resizing by dragging the border.

## Current state

A launched app is an [`AppWindow`](../spatial/src/main/kotlin/com/uxspace/spatial/AppWindow.kt):
a fixed-resolution content surface (`VirtualScreen`, 1600×900) framed by window chrome
(`UiScreen` — a dark border and a DeX-style title bar, colours from `UxSpaceTheme`). `WorkspaceRenderer` holds up to three
windows in a back-to-front list and draws them in order.

A window is in one of two states:

| State | Geometry |
|---|---|
| `NORMAL` | A framed window at a fixed fraction (`NORMAL_FRACTION`) of the desktop area. New windows open at a cascade slot, offset down-right from each other. |
| `MAXIMIZED` | The content fills the desktop area (the render band above the taskbar), at its own 16:9 aspect, with no border or title bar. |

`AppWindow.layout()` turns the state plus the window's centre into world geometry. A window
is moved by grabbing its frame (border or title bar) and dragging; a click raises the
topmost window under the cursor. The window **cannot be resized** — its size is fixed by
`NORMAL_FRACTION` — and there is no snapping.

The content surface is an Android `VirtualDisplay`. It can be resized in place with
`VirtualDisplay.resize(w, h, dpi)`: the display id is kept, the app simply sees a
configuration change and reflows — the same event a foldable or a rotation produces. This
is the key primitive both features below rely on: a window can change shape and the app
fills it properly, with no letterboxing and no relaunch.

---

## Planned: snapping / tiling — "like Microsoft Windows"

### How Windows 11 does it (researched)

- **Drag to an edge or corner.** Dragging a window's title bar so the pointer reaches a
  screen **edge** snaps it to that half; a **corner** snaps it to that quarter; the **top**
  edge maximises it. A translucent preview shows the target rectangle *before* the window
  is released.
- **Snap zones** are invisible activation regions along the edges and corners; the preview
  appears while the pointer is inside one.
- **Snap Layouts** — hovering the Maximize button opens a flyout of preset layouts (two
  halves, four quarters, 1/3 + 2/3, three columns…); clicking a zone snaps the window
  there.
- **Snap Assist** — after one window is snapped, thumbnails of the *other* open windows
  appear in the empty regions; picking one fills that region.
- **Snap Groups** — windows snapped together are remembered as a group and restored
  together from the taskbar.
- Dragging the **shared border** between two snapped windows resizes both at once.

### What UxSpace adopts

UxSpace's "screen" is the desktop area — the render band above the taskbar. Snapping tiles
windows into zones of *that* rectangle. Because at most three windows are open, the useful
zone sets are halves, quarters, and thirds.

**Zones (`SnapZone`).**

| Zone | Rectangle of the desktop area |
|---|---|
| `LEFT` / `RIGHT` | Left / right half |
| `TOP_LEFT` `TOP_RIGHT` `BOTTOM_LEFT` `BOTTOM_RIGHT` | Quarters |
| `LEFT_THIRD` `CENTER_THIRD` `RIGHT_THIRD` | Vertical thirds — natural for the wide glasses display |
| `FULL` | The whole desktop area (this is today's `MAXIMIZED`) |

### Plan

1. **Generalise the window state.** Replace the `NORMAL` / `MAXIMIZED` enum with a target
   rectangle. A window holds either a free-floating size + centre (today's `NORMAL`) or a
   `SnapZone`. `MAXIMIZED` becomes the `FULL` zone. `AppWindow.layout()` computes the frame
   and content rect from whichever it holds.

2. **Snap on drag-to-edge.** While a window is being dragged (`handleDrag`), test the
   cursor against the desktop area's edge/corner activation zones. When it enters one, draw
   a **translucent preview quad** over that zone (a flat-colour rect in the renderer, like
   the drawer scrim). On drag-end inside a zone, assign that `SnapZone` to the window.

3. **Reflow the app to the zone.** On snap, resize the content `VirtualDisplay` to the
   zone's pixel size via `VirtualDisplay.resize()`, and resize the chrome `UiScreen` to
   match (re-add `UiScreen.resize()` — `setDefaultBufferSize` + recreate the `Surface`).
   The title bar stays a fixed pixel height; the border a fixed pixel width; the content
   fills the rest. No letterboxing — the app genuinely reflows.

4. **Un-snap.** Dragging a snapped window's title bar away from its zone returns it to a
   free-floating window at the drag position, sized back to `NORMAL_FRACTION` (display
   resized back). This mirrors Windows pulling a snapped window loose.

5. **Snap from the title bar (optional, later).** A long-press of the Maximize button — or
   a small layouts popup next to it — offers the zone set directly, the UxSpace analogue of
   Snap Layouts. Skip for the first cut; drag-to-edge covers the need.

6. **Snap Assist (optional, later).** After a window snaps to one half, show the other
   open windows as thumbnails in the empty half; a tap snaps the chosen one there. Cheap
   and pleasant with ≤3 windows, but not required for v1.

**Touch points.** `AppWindow` (target-rect / `SnapZone` model, `layout()`); `WorkspaceRenderer`
(zone hit-testing in `handleDrag`, preview quad, apply snap on drag-end); `VirtualScreen`
(`resize()`); `UiScreen` (re-add `resize()`); `WindowChrome` (title bar at a fixed pixel
height regardless of frame size).

---

## Planned: resize by dragging the border

**Goal.** Grab an edge or corner of a window's frame and drag to resize it freely — the
desktop-WM gesture, on top of the discrete snapping above.

**Plan.**

1. **Resize handles.** In `handleDrag`, before falling through to a move, test the cursor
   against an inset margin of the window frame: the four edges resize one dimension, the
   four corners resize both. The title-bar interior still means *move*; the content
   interior still means *tap*.

2. **Live feedback, deferred commit.** Resizing every frame would call
   `VirtualDisplay.resize()` dozens of times a second. Instead, **scale the content quad**
   live during the drag for instant feedback (the app keeps rendering at its current
   resolution, so it stretches slightly), then call `resize()` **once on drag-end** so the
   app reflows crisply to the final size. This is the standard deferred-resize pattern and
   reuses the pinch-to-zoom open question in [TOUCHPAD.md](TOUCHPAD.md).

3. **Bounds.** Clamp to a minimum window size and to the desktop area, so a window cannot
   be resized off-screen or smaller than its title-bar buttons.

4. **Snapped-pair resize (later).** Dragging the shared border between two snapped windows
   resizes both — the Windows behaviour. Needs the zones to know they are adjacent; treat
   as a follow-up once independent resize works.

**Touch points.** `WorkspaceRenderer` (`handleDrag` — handle hit-testing, live quad scale,
`resize()` on drag-end); `AppWindow` (per-window size, min-size clamp); `VirtualScreen` /
`UiScreen` (`resize()`, shared with snapping).

---

## Sources

- [Snap your windows — Microsoft Support](https://support.microsoft.com/en-us/windows/snap-your-windows-885a9b1e-a983-a3b1-16cd-c531795e6241)
- [How to use Snap Layouts and Snap groups — Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/2337358/how-to-use-snap-layouts-and-snap-groups-in-windows)
- [How to master Snap Assist on Windows 11 — Windows Central](https://www.windowscentral.com/how-use-snap-assist-windows-11)
