# Optometry pseudo-app

A built-in UxSpace tool to help the user dial in the **focus wheels** on their VITURE
glasses — VITURE One / One Pro have per-eye dioptre adjustment knobs (~±5.0 D); Luma has
fixed lenses. An optometry-style chart is the standard way to know when you've nailed it.

## Why a pseudo-app

It's not a real Android app: there's no package to launch, no Shizuku call, no virtual
display for it. It is **UxSpace's own rendering**, a head-locked page the GL renderer
draws over the whole view. That keeps it instant to open, free of any launch failure
modes, and pixel-exact on the glasses' panel.

## Where it lives

An entry in the **app drawer**, listed alphabetically among the installed apps as
"Eye chart". It's a synthetic entry from `BuiltInApps` (reserved package id
`uxspace.builtin.eyechart`): launching it runs UxSpace code instead of starting an
activity, and it can't be dragged out as a window.

## What it shows

The chart takes the **whole glasses view**: a head-locked page drawn by the renderer
over everything, regardless of zoom, FREE/pinned mode or the screen layout. The page is
black lorem-ipsum text on white, filling the view, with a footer showing the current
size ("20/40") and how to drive it.

- **Wheel / two-finger scroll** changes the text size, 0.05 logMAR per wheel notch
  (half a chart line), from 20/20 up to 20/200. It opens at **20/40**.
- **Click** (or right-click) anywhere closes it. Reopening starts at 20/40 again.

The user scrolls to the smallest size they can still read, then turns the focus wheel
until it is sharp. Text instead of isolated letters makes blur obvious across the whole
field, not just the centre.

**Sizes are real visual angles.** A capital is 5 arcmin × 10^logMAR tall at
`EyeChartOverlay.PX_PER_DEGREE` (44: VITURE's 1920-px panel spans ~44°), so 20/40 is
~7 px and 20/20 ~4 px, which is about the finest the panel can draw. The page texture
is drawn 1:1 to the glasses' pixels with nearest-neighbour sampling, so the renderer
adds no blur of its own.

## Per-eye masking (not built)

UxSpace renders **one mono image** that the glasses mirror to both eyes, so blanking
half of the view blanks it for both eyes, not one. Real per-eye masking needs the
glasses switched into their 3D side-by-side mode (`VITURE_DISPLAY_MODE_3840_1200_*`,
left half → left eye) while the chart is up, with the page drawn into one half. That's
a display-mode change only testable on hardware. Until then the user covers one eye.

## Implementation

- `EyeChartOverlay` (spatial module) renders the page into a bitmap at the surface's
  size and keeps it as a GL texture, rebuilt only when the size or surface changes.
- `WorkspaceRenderer` draws it last in the frame, with the viewport widened from the
  render band to the full surface, using the HUD quad; the cursor isn't drawn while
  it's up.
- `WorkspaceController.setOptometryOpen(open)` holds the state (opening it closes any
  panel). While it's up the renderer sends scrolls to the overlay, closes it on a click
  or right-click, and ignores drags, long-presses and pinch.
- `BuiltInApps.launch(EYE_CHART, …)` opens it from the drawer.

## Sources

- [Snellen chart — StatPearls (NCBI)](https://www.ncbi.nlm.nih.gov/books/NBK558961/)
- [Landolt C / Tumbling E comparison — Nature Scientific Reports](https://www.nature.com/articles/s41598-021-97875-3)
