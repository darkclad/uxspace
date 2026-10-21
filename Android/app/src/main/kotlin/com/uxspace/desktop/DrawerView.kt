package com.uxspace.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.uxspace.R
import com.uxspace.apps.AppCache
import com.uxspace.apps.BuiltInApps
import com.uxspace.apps.InstalledApp
import com.uxspace.spatial.UxSpaceTheme
import com.uxspace.spatial.WorkspaceController

/**
 * UxSpace's app drawer panel — a regular Android view (not a Presentation), embedded
 * as a child of each screen's [DesktopPresentation]. The screen's surface texture
 * therefore contains everything for that screen (wallpaper, taskbar, drawer when
 * visible, launched apps on top), so the renderer just samples one surface per
 * screen — no special drawer geometry, no scrim quad, sizing always matches the
 * host screen by construction.
 *
 * Visibility is driven by the screen's `DesktopPresentation` via a
 * [WorkspaceController.addDrawerStateListener] filter on its own screen index.
 */
class DrawerView(context: Context) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter = AppGridAdapter()
    private val search: EditText
    private val grid: GridView
    private val allAppsTab: TextView
    private val recentTab: TextView

    private val searchQueryListener: (String) -> Unit = { q ->
        mainHandler.post { setSearchText(q) }
    }
    private val modeChangedListener: (WorkspaceController.DrawerMode) -> Unit = { m ->
        mainHandler.post { applyMode(m) }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        // Clipped card, so the grid's fading edge and press highlights stay inside it.
        PanelStyle.applyCard(this)
        setPadding(dp(28), dp(14), dp(28), dp(18))

        allAppsTab = tabLabel("All apps") {
            WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
        }
        recentTab = tabLabel("Recent") {
            WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.RECENT)
        }
        val tabs = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(allAppsTab)
            addView(
                recentTab,
                LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
            )
        }

        grid = GridView(context).apply {
            numColumns = DRAWER_COLUMNS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = 0
            isVerticalScrollBarEnabled = false
            // Rows dissolve into the card at the bottom edge rather than being cut off.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(32))
            selector = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(UxSpaceTheme.taskbarPressed)
            }
            adapter = this@DrawerView.adapter
            setOnItemClickListener { _, _, position, _ ->
                (this@DrawerView.adapter.getItem(position) as? InstalledApp)?.let(::launch)
            }
        }

        search = EditText(context).apply {
            hint = "Search"
            setHintTextColor(UxSpaceTheme.drawerSearchHint)
            setTextColor(UxSpaceTheme.drawerLabel)
            textSize = 15f
            setSingleLine()
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(SEARCH_HEIGHT_DP / 2).toFloat()
                setColor(UxSpaceTheme.drawerSearch)
                setStroke(1, UxSpaceTheme.panelStroke)
            }
            setPadding(dp(14), 0, dp(16), 0)
            setCompoundDrawablesRelative(
                context.getDrawable(R.drawable.ic_search)?.mutate()?.apply {
                    setBounds(0, 0, dp(20), dp(20))
                    setTint(UxSpaceTheme.drawerSearchHint)
                },
                null, null, null,
            )
            compoundDrawablePadding = dp(8)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    adapter.setQuery(s?.toString().orEmpty())
                }
            })
        }

        addView(tabs, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        addView(grid, LayoutParams(MATCH, 0, 1f))
        addView(
            search,
            LayoutParams(dp(SEARCH_WIDTH_DP), dp(SEARCH_HEIGHT_DP)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            },
        )

        applyMode(WorkspaceController.drawerMode)
        loadApps()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WorkspaceController.onDrawerSearchQuery = searchQueryListener
        WorkspaceController.addDrawerModeListener(modeChangedListener)
        registerDragLookup()
    }

    override fun onDetachedFromWindow() {
        if (WorkspaceController.onDrawerSearchQuery === searchQueryListener) {
            WorkspaceController.onDrawerSearchQuery = null
        }
        WorkspaceController.removeDrawerModeListener(modeChangedListener)
        unregisterDragLookup()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Re-sync to the controller's current mode whenever this drawer becomes visible.
        // Defensive: ensures the tab + grid reflect the mode the App-drawer button just
        // set, even if our listener was lost or this DrawerView was constructed while
        // the controller held a different (stale) mode.
        if (changedView === this && visibility == VISIBLE) {
            applyMode(WorkspaceController.drawerMode)
            registerDragLookup()
        } else if (changedView === this && visibility != VISIBLE) {
            unregisterDragLookup()
        }
    }

    /**
     * While this drawer is visible, expose a slot-pixel → grid-item lookup so the
     * renderer can arm a drag-from-drawer when the cursor press-and-holds on a cell.
     * Only one drawer is ever visible at a time (modal exclusivity is owned by
     * [WorkspaceController]), so a single global hook is enough.
     */
    private val dragLookup: (Int, Int) -> WorkspaceController.ArmedDrag? = { slotPx, slotPy ->
        // Built-in tools have no activity to drop onto the desktop as a window.
        appAtSlotPx(slotPx, slotPy)?.takeUnless { BuiltInApps.isBuiltIn(it.packageName) }?.let { app ->
            WorkspaceController.ArmedDrag(
                packageName = app.packageName,
                activityName = app.activityName,
                label = app.label,
                iconBitmap = app.icon.toBitmap(),
            )
        }
    }

    private fun registerDragLookup() {
        WorkspaceController.drawerItemAt = dragLookup
        android.util.Log.i(
            "UxSpace/DrawerDrag",
            "DrawerView.registerDragLookup attached=$isAttachedToWindow vis=${visibility} bounds=(L=$left T=$top W=$width H=$height) grid=(L=${grid.left} T=${grid.top} W=${grid.width} H=${grid.height}) items=${adapter.count}",
        )
    }

    private fun unregisterDragLookup() {
        if (WorkspaceController.drawerItemAt === dragLookup) {
            WorkspaceController.drawerItemAt = null
            android.util.Log.i("UxSpace/DrawerDrag", "DrawerView.unregisterDragLookup")
        }
    }

    /**
     * Map a slot-local pixel (cursor projected onto the slot's surface) to the drawer
     * grid item under it. Walks the slot → DrawerView → GridView coordinate chain by
     * subtracting view offsets; the GridView's own `pointToPosition` finds the cell.
     * Returns null when the cursor is outside the drawer or not over a cell.
     */
    private fun appAtSlotPx(slotPx: Int, slotPy: Int): InstalledApp? {
        val drawerX = slotPx - left
        val drawerY = slotPy - top
        if (drawerX < 0 || drawerY < 0 || drawerX >= width || drawerY >= height) return null
        val gridX = drawerX - grid.left
        val gridY = drawerY - grid.top
        if (gridX < 0 || gridY < 0 || gridX >= grid.width || gridY >= grid.height) return null
        val pos = grid.pointToPosition(gridX, gridY)
        if (pos < 0 || pos >= adapter.count) return null
        return adapter.getItem(pos) as? InstalledApp
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable) {
            bitmap?.let { return it }
        }
        val w = intrinsicWidth.takeIf { it > 0 } ?: DRAG_ICON_PX
        val h = intrinsicHeight.takeIf { it > 0 } ?: DRAG_ICON_PX
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val prev = copyBounds()
        setBounds(0, 0, w, h)
        draw(c)
        bounds = prev
        return bmp
    }

    private fun applyMode(mode: WorkspaceController.DrawerMode) {
        styleTab(allAppsTab, mode == WorkspaceController.DrawerMode.ALL)
        styleTab(recentTab, mode == WorkspaceController.DrawerMode.RECENT)
        adapter.setMode(mode, WorkspaceController.recentApps)
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        if (active) {
            tab.setTextColor(UxSpaceTheme.drawerTabActive)
            tab.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            tab.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(UxSpaceTheme.drawerTabPill)
            }
        } else {
            tab.setTextColor(UxSpaceTheme.drawerTabInactive)
            tab.typeface = Typeface.DEFAULT
            tab.background = null
        }
    }

    private fun setSearchText(query: String) {
        if (search.text.toString() == query) return
        search.setText(query)
        search.setSelection(query.length)
    }

    private fun tabLabel(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setTextColor(UxSpaceTheme.drawerTabInactive)
        setOnClickListener { onClick() }
    }

    private fun launch(app: InstalledApp) {
        if (BuiltInApps.isBuiltIn(app.packageName)) {
            BuiltInApps.launch(context, app.packageName)
            WorkspaceController.setDrawerOpen(false)
            return
        }
        android.util.Log.i(
            "UxSpace/Launch",
            "1) drawer tap pkg=${app.packageName} act=${app.activityName} label='${app.label}'",
        )
        WorkspaceController.launchApp(app.packageName, app.activityName, app.label)
        WorkspaceController.setDrawerOpen(false)
    }

    private fun loadApps() {
        AppCache.whenReady { list ->
            android.util.Log.i(
                "UxSpace/Drawer",
                "DrawerView whenReady got ${list.size} apps; mode=${WorkspaceController.drawerMode}",
            )
            val all = (list + BuiltInApps.list(context)).sortedBy { it.label.lowercase() }
            mainHandler.post { adapter.submit(all) }
        }
    }

    private inner class AppGridAdapter : BaseAdapter() {
        private val full = ArrayList<InstalledApp>()
        private val items = ArrayList<InstalledApp>()
        private var query = ""
        private var mode: WorkspaceController.DrawerMode = WorkspaceController.DrawerMode.ALL
        private var recentOrder: List<String> = emptyList()

        fun submit(apps: List<InstalledApp>) {
            full.clear()
            full.addAll(apps)
            recompute()
            android.util.Log.i(
                "UxSpace/Drawer",
                "AppGridAdapter.submit: full=${full.size} items=${items.size} mode=$mode",
            )
        }

        fun setQuery(text: String) {
            query = text.trim()
            recompute()
        }

        fun setMode(mode: WorkspaceController.DrawerMode, recentPackages: List<String>) {
            this.mode = mode
            this.recentOrder = recentPackages
            recompute()
        }

        private fun recompute() {
            val base = when (mode) {
                WorkspaceController.DrawerMode.ALL -> full
                WorkspaceController.DrawerMode.RECENT -> {
                    val byPkg = full.associateBy { it.packageName }
                    recentOrder.mapNotNull { byPkg[it] }
                }
            }
            items.clear()
            items.addAll(
                if (query.isEmpty()) base
                else base.filter { it.label.contains(query, ignoreCase = true) },
            )
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? LinearLayout ?: newCell()
            val app = items[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }

        private fun newCell(): LinearLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            addView(ImageView(context), LayoutParams(dp(ICON_DP), dp(ICON_DP)))
            addView(
                TextView(context).apply {
                    setTextColor(UxSpaceTheme.drawerLabel)
                    textSize = 11f
                    setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) },
            )
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DRAWER_COLUMNS = 8

        // Card geometry: the desktop-mode reference's px sizes ÷ 1.25, since slot
        // displays run at 200 dpi.
        const val ICON_DP = 46
        const val SEARCH_WIDTH_DP = 224
        const val SEARCH_HEIGHT_DP = 40

        /** Fallback rasterisation size for an app icon with no intrinsic dims. */
        const val DRAG_ICON_PX = 192
    }
}
