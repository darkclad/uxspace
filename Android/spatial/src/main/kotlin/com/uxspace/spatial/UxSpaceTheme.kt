package com.uxspace.spatial

/**
 * UxSpace's colour palette.
 *
 * Every piece of UxSpace-drawn chrome — window frames, the taskbar — takes its colours from
 * here, so the workspace has one consistent look and a single place to retheme it.
 */
object UxSpaceTheme {

    /**
     * Window border: the ring around an app. Same tone as the title bar so caption and
     * frame read as one dark shell around the app's content.
     */
    const val windowBorder: Int = 0xFF2D2D30.toInt()

    /** Window title bar (caption) background. */
    const val windowTitleBar: Int = 0xFF2D2D30.toInt()

    /** Caption button glyphs on the dark frame. */
    const val windowFrameText: Int = 0xFFC7C7CC.toInt()

    /** App name in the caption — a step brighter than the button glyphs. */
    const val windowTitleText: Int = 0xFFE6E5EA.toInt()

    /** Disc behind the caption's Close glyph. */
    const val windowCloseDisc: Int = 0xFF4B4B4F.toInt()

    /** Close glyph, on [windowCloseDisc]. */
    const val windowCloseGlyph: Int = 0xFFF3F3F9.toInt()

    /** Pressed caption button. */
    const val windowButtonPressed: Int = 0x26FFFFFF

    /**
     * Background for the floating chrome toolbar shown over an app window in
     * FULLSCREEN — a dark colour close to the taskbar's, so the toolbar reads
     * as part of the workspace shell rather than the running app. The slot's
     * Presentation can't have *real* alpha here without hiding the wallpaper
     * view under the chrome rect, so this opaque tone mimics the look of a
     * translucent dark panel.
     */
    const val windowChromeCompactBg: Int = windowTitleBar

    /** Icon tint for the compact (fullscreen) chrome — light on dark. */
    const val windowChromeCompactIcon: Int = windowFrameText

    // Desktop shell — taskbar + app drawer. A dark, slightly warm-neutral scheme with
    // translucent surfaces over the wallpaper and hairline light strokes, measured off a
    // desktop-mode reference at 1920×1080 (1 px = 1 dp there). Real backdrop blur isn't
    // available to a Presentation's views, so translucency stands in for it.

    /** Taskbar background — translucent so the wallpaper tints it. */
    const val taskbar: Int = 0xE0222124.toInt()

    /** Taskbar text (clock, battery percentage). */
    const val taskbarText: Int = 0xFFF2F1F4.toInt()

    /** Unlit segments of the tray's level glyphs (Wi-Fi, signal) — [taskbarText] at ~30 %. */
    const val taskbarTextDim: Int = 0x4DF2F1F4

    /** Hairline separators between taskbar groups. */
    const val taskbarDivider: Int = 0x66FFFFFF

    /** Pressed / hovered taskbar cell. */
    const val taskbarPressed: Int = 0x26FFFFFF

    /** Pill under a running app's taskbar icon. */
    const val taskbarRunningIndicator: Int = 0xCCFFFFFF.toInt()

    /**
     * Modal panel card — app drawer, Settings, Audio. Nearly opaque: text must stay
     * legible over any wallpaper.
     */
    const val panel: Int = 0xF2242226.toInt()

    /** 1 px inner stroke that lifts a panel card off a dark wallpaper. */
    const val panelStroke: Int = 0x1AFFFFFF

    /** Panel heading ("Settings", "Audio"). */
    const val panelTitle: Int = 0xFFF2F1F4.toInt()

    /** Small caps section labels inside a panel. */
    const val panelSection: Int = 0xFF9E9CA2.toInt()

    /** Body text and row labels inside a panel. */
    const val panelLabel: Int = 0xFFDFDDE2.toInt()

    /** Pill buttons and selectable rows: the selected one inverts to light-on-dark. */
    const val pillActiveBg: Int = 0xFFF2F1F4.toInt()
    const val pillActiveFg: Int = 0xFF1B1A1D.toInt()
    const val pillInactiveBg: Int = 0x1AFFFFFF
    const val pillInactiveFg: Int = 0xFFDFDDE2.toInt()

    /** Checked box / radio, slider thumb and filled track. */
    const val controlAccent: Int = 0xFFF2F1F4.toInt()

    /** Unchecked box / radio outline and slider track. */
    const val controlTrack: Int = 0x4DFFFFFF

    /** Empty image slot (e.g. a wallpaper thumbnail still loading). */
    const val panelPlaceholder: Int = 0xFF38363B.toInt()

    /** App labels in the drawer grid. */
    const val drawerLabel: Int = 0xFFF2F1F4.toInt()

    /** Floating search pill at the bottom of the drawer. */
    const val drawerSearch: Int = 0xFF1B1A1C.toInt()

    /** Search hint text and glyph. */
    const val drawerSearchHint: Int = 0xFFA9A7AC.toInt()

    /** Drawer mode tabs (All apps / Recent). */
    const val drawerTabActive: Int = 0xFFF2F1F4.toInt()
    const val drawerTabInactive: Int = 0x80F2F1F4.toInt()

    /** Selected-tab pill behind the active drawer tab. */
    const val drawerTabPill: Int = 0x1FFFFFFF
}
