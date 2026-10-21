# Optometry pseudo-app

A built-in UxSpace tool to help the user dial in the **focus wheels** on their VITURE
glasses — VITURE One / One Pro have per-eye dioptre adjustment knobs (~±5.0 D); Luma has
fixed lenses. An optometry-style chart is the standard way to know when you've nailed it.

## Why a pseudo-app

It's not a real Android app — there's no package to launch, no Shizuku call, no virtual
display for it. It is **UxSpace's own UI** drawn on its own `UiScreen`, the same way
[`DesktopPresentation`](../app/src/main/kotlin/com/uxspace/desktop/DesktopPresentation.kt)
and [`DrawerPresentation`](../app/src/main/kotlin/com/uxspace/desktop/DrawerPresentation.kt)
are. That keeps it instant to open, free of any launch failure modes, and able to render at
the workspace's native resolution.

## Where it lives

An entry in the **app drawer**, listed alphabetically among the installed apps as
"Eye chart". It's a synthetic entry from `BuiltInApps` (reserved package id
`uxspace.builtin.eyechart`): launching it runs UxSpace code instead of starting an
activity, and it can't be dragged out as a window. Opening it overlays the chart on top
of the workspace, full bleed, hiding everything else (windows and the desktop wallpaper)
so the chart is the only thing the eye sees. An on-screen `×` (and the taskbar's Back)
dismisses it.

## The chart

A focus-calibration ladder, not a vision test: **20/40 down to 20/10 in 0.1 logMAR
steps** (20/40, 20/32, 20/25, 20/20, 20/16, 20/12.5, 20/10; each row ≈ 1.26× the next),
five Sloan letters per row (C D H K N O R S V Z, no repeats in a row), fresh letters every
time the chart opens. Big rows (20/200 …) are left out on purpose.

**Sizes are relative.** A true 20/20 letter subtends 5 arcmin; at the glasses' ~44 px per
degree that is ~4 px, and 20/10 ~2 px, below what the panel can draw legibly. So the
20/10 row is anchored at the pixel floor (`EyeChartView.MIN_LETTER_PX`, 9 px) and the other
rows scale up by the standard ratios. The point is to find the wheel position where the
smallest rows go crisp, which this preserves.

A **Tumbling E** mode (rotated Es, same ladder) is a corner toggle for readers who'd rather
judge orientation than recognise letters. Default is letters.

The taskbar stays visible above the chart so its **Back** closes it; the chart's own `×`
does too.

## Per-eye masking (not built)

UxSpace renders **one mono image** that the glasses mirror to both eyes, so blanking half
of the workspace blanks it for both eyes, not one. Real per-eye masking needs the glasses
switched into their 3D side-by-side mode (`VITURE_DISPLAY_MODE_3840_1200_*`, left half →
left eye) while the chart is open, with the chart drawn into one half. That's a display-mode
change only testable on hardware. Until then the chart tells the user to cover one eye.

## Implementation

- `EyeChartView` is a full-size child of the slot's `DesktopPresentation`, under the
  taskbar and above everything else. It draws the rows on a canvas (letters centred on
  their ink bounds so rotated Es pivot correctly), with `×` top-right and the
  chart-style toggle bottom-right, clear of the taskbar band.
- `WorkspaceController.setOptometryOpen(open, screenIdx)` holds the state (closing any
  other panel); `DesktopPresentation` shows the view on that screen and reshuffles it.
- `WorkspaceRenderer` treats the whole slot as the modal rect while the chart is open:
  it re-draws the slot surface over the windows on that screen and routes clicks,
  scrolls, right-clicks and drags to the chart instead of anything under it.
- `BuiltInApps.launch(EYE_CHART, screen)` opens it from the drawer; the taskbar's Back
  closes it first, before any panel or window.

## Sources

- [Snellen chart — StatPearls (NCBI)](https://www.ncbi.nlm.nih.gov/books/NBK558961/)
- [Landolt C / Tumbling E comparison — Nature Scientific Reports](https://www.nature.com/articles/s41598-021-97875-3)
