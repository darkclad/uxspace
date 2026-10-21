package com.uxspace.desktop

import android.content.Context
import android.content.SharedPreferences
import com.uxspace.spatial.Layout
import org.json.JSONArray
import org.json.JSONObject

/**
 * One app pinned onto a desktop. Position is fractional `[0..1]` of the screen's content
 * rect, so a shortcut placed on a wide screen still lands sensibly when the same desktop
 * is shown on a portrait one. The store rejects two entries with the same `packageName`
 * on the same desktop.
 */
data class DesktopShortcut(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** Centre of the shortcut as a fraction of the screen's content width. */
    val xFraction: Float,
    /** Centre of the shortcut as a fraction of the screen's content height. */
    val yFraction: Float,
)

/**
 * SharedPreferences-backed list of [DesktopShortcut] per *desktop index* (not per
 * layout / screenIdx). Three logical desktops — 0 = main, 1 = "next", 2 = "third" —
 * are shared across layouts: a shortcut pinned on the main desktop in Two SBS shows up
 * on the main desktop in Three SBS too, on whichever physical screen is wired to that
 * desktop in each layout.
 *
 * One JSON array per key, parsed on every read — there is no in-memory cache, so a write
 * in one Presentation is immediately visible to another's read on the next refresh. The
 * surface is small (a handful of shortcuts per desktop times three desktops), so this is
 * cheap.
 */
object DesktopShortcutsStore {

    private const val PREFS_NAME = "uxspace_desktop_shortcuts"

    @Volatile
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            purgeLegacyKeys()
        }
    }

    /**
     * The first cut of this store keyed each desktop by `${layout.name}_screen${idx}`,
     * which left stale entries behind after the move to per-desktop indices. Sweep them
     * out so the prefs file doesn't accumulate dead keys.
     */
    private fun purgeLegacyKeys() {
        val legacy = prefs.all.keys.filter { it.contains("_screen") }
        if (legacy.isEmpty()) return
        val editor = prefs.edit()
        legacy.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Maps a layout slot to its desktop index. Layout main screen = desktop 0; the
     * screen immediately to its right (cyclically) = desktop 1; the next = desktop 2.
     * Reads the user-chosen main screen from [WorkspaceSettings].
     *
     *  - Two SBS  (main = left)   : slot 0 → desktop 0, slot 1 → desktop 1
     *  - Three SBS (main = mid)   : slot 0 → desktop 2, slot 1 → desktop 0, slot 2 → desktop 1
     *  - V/H/V    (main = H)      : slot 0 → desktop 2, slot 1 → desktop 0, slot 2 → desktop 1
     *  - Single / Wide            : slot 0 → desktop 0
     */
    fun desktopIdxFor(layout: Layout, slotIdx: Int): Int {
        val n = layout.screens.size
        if (n <= 1) return 0
        val main = WorkspaceSettings.mainScreenFor(layout)
        if (slotIdx == main) return 0
        return ((slotIdx - main - 1).mod(n)) + 1
    }

    private fun keyOf(desktopIdx: Int): String = "desktop_$desktopIdx"

    /** Snapshot of pinned shortcuts for [desktopIdx]. */
    fun shortcutsFor(desktopIdx: Int): List<DesktopShortcut> {
        if (!::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(keyOf(desktopIdx), null) ?: return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    /**
     * Add [shortcut] to [desktopIdx]. Returns true on success, false if a shortcut with
     * the same package is already pinned there.
     */
    fun add(desktopIdx: Int, shortcut: DesktopShortcut): Boolean {
        if (!::prefs.isInitialized) return false
        val current = shortcutsFor(desktopIdx).toMutableList()
        if (current.any { it.packageName == shortcut.packageName }) return false
        current.add(shortcut)
        write(desktopIdx, current)
        return true
    }

    /** Remove the shortcut with [packageName] from [desktopIdx]. */
    fun remove(desktopIdx: Int, packageName: String) {
        if (!::prefs.isInitialized) return
        val current = shortcutsFor(desktopIdx).toMutableList()
        val removed = current.removeAll { it.packageName == packageName }
        if (removed) write(desktopIdx, current)
    }

    /**
     * Move an existing shortcut to a new fractional position. No-op if no shortcut with
     * [packageName] exists on [desktopIdx].
     */
    fun move(desktopIdx: Int, packageName: String, xFraction: Float, yFraction: Float) {
        if (!::prefs.isInitialized) return
        val current = shortcutsFor(desktopIdx).toMutableList()
        val idx = current.indexOfFirst { it.packageName == packageName }
        if (idx < 0) return
        current[idx] = current[idx].copy(xFraction = xFraction, yFraction = yFraction)
        write(desktopIdx, current)
    }

    private fun write(desktopIdx: Int, list: List<DesktopShortcut>) {
        prefs.edit().putString(keyOf(desktopIdx), encode(list)).apply()
        listeners.forEach { runCatching { it(desktopIdx) } }
    }

    /**
     * Re-flow every shortcut on [desktopIdx] into a clean grid — left-to-right,
     * top-to-bottom, with even fractional spacing. Stable order preserved (the
     * existing list order maps to grid cells), or alphabetical by label when
     * [byName] is set. The grid is sized so that a
     * reasonable number of icons (≤ 8 cols, ≤ 6 rows) fit edge-to-edge with a
     * margin on each side. Beyond 48 icons new ones wrap to the next column.
     */
    fun arrange(desktopIdx: Int, byName: Boolean = false) {
        if (!::prefs.isInitialized) return
        val current = shortcutsFor(desktopIdx)
            .let { list -> if (byName) list.sortedBy { it.label.lowercase() } else list }
        if (current.isEmpty()) return
        val cols = ARRANGE_COLS
        val rows = ARRANGE_ROWS
        val marginX = ARRANGE_MARGIN
        val marginY = ARRANGE_MARGIN
        val stepX = if (cols > 1) (1f - 2f * marginX) / (cols - 1) else 0f
        val stepY = if (rows > 1) (1f - 2f * marginY) / (rows - 1) else 0f
        val rearranged = current.mapIndexed { idx, s ->
            val cell = idx % (cols * rows)
            val col = cell % cols
            val row = cell / cols
            s.copy(
                xFraction = (marginX + col * stepX).coerceIn(0f, 1f),
                yFraction = (marginY + row * stepY).coerceIn(0f, 1f),
            )
        }
        write(desktopIdx, rearranged)
    }

    private const val ARRANGE_COLS = 16
    private const val ARRANGE_ROWS = 12
    private const val ARRANGE_MARGIN = 0.05f

    private fun encode(list: List<DesktopShortcut>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("pkg", s.packageName)
                    .put("act", s.activityName)
                    .put("label", s.label)
                    .put("x", s.xFraction.toDouble())
                    .put("y", s.yFraction.toDouble()),
            )
        }
        return arr.toString()
    }

    private fun parse(raw: String): List<DesktopShortcut> {
        val arr = JSONArray(raw)
        val out = ArrayList<DesktopShortcut>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                DesktopShortcut(
                    packageName = o.getString("pkg"),
                    activityName = o.getString("act"),
                    label = o.optString("label", ""),
                    xFraction = o.getDouble("x").toFloat().coerceIn(0f, 1f),
                    yFraction = o.getDouble("y").toFloat().coerceIn(0f, 1f),
                ),
            )
        }
        return out
    }

    /** Notified after every mutation. [desktopIdx] identifies the changed desktop. */
    private val listeners =
        java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    fun addChangeListener(l: (Int) -> Unit) { listeners.add(l) }
    fun removeChangeListener(l: (Int) -> Unit) { listeners.remove(l) }
}
