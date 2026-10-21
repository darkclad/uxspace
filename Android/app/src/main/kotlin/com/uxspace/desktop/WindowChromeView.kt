package com.uxspace.desktop

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.uxspace.R
import com.uxspace.spatial.UxSpaceTheme
import com.uxspace.spatial.WorkspaceController

/**
 * Window chrome — two physically distinct child views, only one visible at a time.
 *
 * ```
 *   ┌───────── WindowChromeView (FrameLayout) ────────────────────────┐
 *   │  titleBar  : icon + name + back/min/max/close   (NORMAL, MAX)   │
 *   │  toolbar   : back/min/max/close only            (FULLSCREEN)    │
 *   └─────────────────────────────────────────────────────────────────┘
 * ```
 *
 * [setWindowMode] is the single switch that decides which one is shown — the
 * decision lives on one line so there's nowhere else to hunt when one wants
 * to change the rule. The button click callbacks are wired identically on
 * both rows, so the action surface is identical no matter which is visible.
 *
 * Cursor clicks are routed in by [com.uxspace.spatial.WorkspaceRenderer] through
 * `UiScreen.dispatchTap`; the slot Presentation holds FLAG_NOT_TOUCHABLE so
 * system input dispatcher never delivers display-injected events here, but
 * in-process dispatch (calling `decorView.dispatchTouchEvent` directly) isn't
 * sieved by that flag.
 */
class WindowChromeView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onBack(packageName: String)
        fun onMinimize(packageName: String)
        fun onMaximize(packageName: String)
        fun onClose(packageName: String)
    }

    // ── titleBar (NORMAL, MAXIMIZED) ──────────────────────────────────────────
    private val titleBar: LinearLayout
    private val titleIcon: ImageView
    private val titleLabel: TextView
    private val titleBack: ImageButton
    private val titleMin: ImageButton
    private val titleMax: ImageButton
    private val titleClose: ImageButton

    // ── toolbar (FULLSCREEN) ──────────────────────────────────────────────────
    private val toolbar: LinearLayout
    private val toolBack: ImageButton
    private val toolMin: ImageButton
    private val toolMax: ImageButton
    private val toolClose: ImageButton

    private var packageName: String? = null
    private var listener: Listener? = null

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        // ── titleBar ──────────────────────────────────────────────────────────
        // Double-tap on the title bar (anywhere a button doesn't claim the
        // touch) toggles between NORMAL and MAXIMIZED via fireMaximize() —
        // mirrors a standard desktop-OS gesture. Button children consume their
        // own touches, so the gesture detector only sees taps on the icon /
        // label area, which is exactly the "empty" title-bar zone we want.
        val titleGesture = android.view.GestureDetector(
            context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                // Must return true: it makes the title bar the registered touch
                // target for the gesture, so the synthesized ACTION_UP from
                // UiScreen.dispatchTap is delivered and recorded. Without it the
                // UP is dropped, the first tap never completes, and the second
                // tap is never paired — so onDoubleTap never fires.
                override fun onDown(e: android.view.MotionEvent): Boolean = true
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    fireMaximize()
                    return true
                }
            },
        )
        titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UxSpaceTheme.windowTitleBar)
            setPadding(dp(12), 0, dp(4), 0)
            setOnTouchListener { _, ev -> titleGesture.onTouchEvent(ev) }
        }
        titleIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        titleBar.addView(
            titleIcon,
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) },
        )
        titleLabel = TextView(context).apply {
            setTextColor(UxSpaceTheme.windowTitleText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        titleBar.addView(titleLabel, LinearLayout.LayoutParams(0, MATCH, 1f))
        titleBack = chromeButton(R.drawable.ic_back, "Back") { fireBack() }
        titleMin = chromeButton(R.drawable.ic_minimize, "Minimize") { fireMinimize() }
        titleMax = chromeButton(R.drawable.ic_maximize, "Maximize") { fireMaximize() }
        titleClose = chromeButton(R.drawable.ic_close, "Close", close = true) { fireClose() }
        titleBar.addView(titleBack)
        titleBar.addView(titleMin)
        titleBar.addView(titleMax)
        titleBar.addView(titleClose)
        addView(titleBar, LayoutParams(MATCH, MATCH))

        // ── toolbar ───────────────────────────────────────────────────────────
        toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(UxSpaceTheme.windowChromeCompactBg)
        }
        toolBack = chromeButton(R.drawable.ic_back, "Back") { fireBack() }
        toolMin = chromeButton(R.drawable.ic_minimize, "Minimize") { fireMinimize() }
        toolMax = chromeButton(R.drawable.ic_fullscreen_exit, "Maximize") { fireMaximize() }
        toolClose = chromeButton(R.drawable.ic_close, "Close", close = true) { fireClose() }
        toolbar.addView(toolBack)
        toolbar.addView(toolMin)
        toolbar.addView(toolMax)
        toolbar.addView(toolClose)
        addView(toolbar, LayoutParams(MATCH, MATCH))

        // Default to title-bar look until setWindowMode is called.
        toolbar.visibility = View.GONE
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Binds the title-bar's icon + name to the foreground app on this slot. */
    fun bind(packageName: String?, label: String?, iconDrawable: Drawable?) {
        this.packageName = packageName
        titleLabel.text = label.orEmpty()
        titleIcon.setImageDrawable(iconDrawable)
    }

    /**
     * THE one switch. Picks which child view is shown for the current window
     * mode — title bar for NORMAL/MAXIMIZED, toolbar for FULLSCREEN — and
     * updates the Maximize button's icon to the right "next state" affordance.
     * Position and outer bounds are owned by the renderer through
     * `WindowBounds.chromeBoundsX/Y/W/H`.
     */
    fun setWindowMode(mode: WorkspaceController.WindowMode) {
        // ── THIS IS THE LINE THAT HANDLES THE CHOICE ──
        val showToolbar = mode == WorkspaceController.WindowMode.FULLSCREEN
        toolbar.visibility = if (showToolbar) View.VISIBLE else View.GONE
        titleBar.visibility = if (showToolbar) View.GONE else View.VISIBLE
        // ───────────────────────────────────────────────
        android.util.Log.i(
            "UxSpace/Chrome",
            "setWindowMode mode=$mode showToolbar=$showToolbar " +
                "titleBar.vis=${titleBar.visibility} toolbar.vis=${toolbar.visibility} " +
                "size=${width}x${height}",
        )

        // Maximize-button icon per mode: NORMAL → "go maximized", MAXIMIZED → "go fullscreen",
        // FULLSCREEN → "exit fullscreen".
        val nextStateIcon = when (mode) {
            WorkspaceController.WindowMode.NORMAL,
            WorkspaceController.WindowMode.TILED_LEFT,
            WorkspaceController.WindowMode.TILED_RIGHT -> R.drawable.ic_maximize
            WorkspaceController.WindowMode.MAXIMIZED -> R.drawable.ic_fullscreen
            WorkspaceController.WindowMode.FULLSCREEN -> R.drawable.ic_fullscreen_exit
        }
        titleMax.setImageResource(nextStateIcon)
        toolMax.setImageResource(nextStateIcon)

        // Re-tint the visible row's buttons. setImageTintList alone isn't
        // reliable when the vector drawable cache hands us a shared
        // ConstantState, so we also mutate + setColorFilter as a belt-and-braces.
        tintRow(titleBack, titleMin, titleMax, color = UxSpaceTheme.windowFrameText)
        tintRow(toolBack, toolMin, toolMax, color = UxSpaceTheme.windowChromeCompactIcon)
        tintRow(titleClose, toolClose, color = UxSpaceTheme.windowCloseGlyph)
    }

    private fun fireBack() = packageName?.let { listener?.onBack(it) }
    private fun fireMinimize() = packageName?.let { listener?.onMinimize(it) }
    private fun fireMaximize() = packageName?.let { listener?.onMaximize(it) }
    private fun fireClose() = packageName?.let { listener?.onClose(it) }

    private fun tintRow(vararg buttons: ImageButton, color: Int) {
        val list = ColorStateList.valueOf(color)
        for (b in buttons) {
            b.imageTintList = list
            val d = b.drawable?.mutate()
            d?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            b.setImageDrawable(d)
        }
    }

    private fun chromeButton(
        iconRes: Int,
        description: String,
        close: Boolean = false,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        background = buttonBackground(close)
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(
            if (close) UxSpaceTheme.windowCloseGlyph else UxSpaceTheme.windowFrameText,
        )
        contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER
        // Glyph ≈ 15 dp tall inside the 21 dp button; Close's is smaller so it sits
        // inside its disc.
        val padV = dp(if (close) 5 else 3)
        setPadding(0, padV, 0, padV)
        setOnClickListener { onClick() }
        // Fixed height (smaller than the chrome strip), so the parent
        // LinearLayout's gravity = CENTER actually has slack to centre the
        // button vertically. With MATCH_PARENT we relied on FIT_CENTER inside
        // the button to centre the icon — that wasn't producing the expected
        // symmetric margins. Now the *button* is centred as a whole, and the
        // icon fills the button.
        layoutParams = LinearLayout.LayoutParams(dp(BUTTON_WIDTH_DP), dp(BUTTON_HEIGHT_DP))
    }

    /**
     * Rounded highlight while pressed; Close additionally sits on a round disc, inset
     * so it stays circular inside the wider-than-tall button.
     */
    private fun buttonBackground(close: Boolean): Drawable {
        val pressed = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(UxSpaceTheme.windowButtonPressed)
                },
            )
        }
        if (!close) return pressed
        val discDp = BUTTON_HEIGHT_DP - 2
        val disc = InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UxSpaceTheme.windowCloseDisc)
            },
            dp((BUTTON_WIDTH_DP - discDp) / 2), dp(1),
            dp((BUTTON_WIDTH_DP - discDp) / 2), dp(1),
        )
        return LayerDrawable(arrayOf(disc, pressed))
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /**
         * Chrome button size in dp. Height is intentionally smaller than the
         * chrome strip ([DesktopPresentation.CHROME_HEIGHT_DP] tall) so the
         * parent LinearLayout's CENTER gravity has slack to vertically centre
         * the button. The four-button row width must stay in step with the
         * fullscreen toolbar's [AppWindow.FULLSCREEN_TOOLBAR_WIDTH_PX]:
         * 4 × 34 dp at the slot's 200 dpi (density 1.25) = 168 px.
         *
         * Sized down 30% from the original 48 × 30 dp for a less bulky control
         * cluster on the right of the window chrome.
         */
        const val BUTTON_WIDTH_DP = 34
        const val BUTTON_HEIGHT_DP = 21
    }
}
