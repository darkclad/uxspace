package com.uxspace.desktop

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.uxspace.R
import com.uxspace.spatial.UxSpaceTheme
import com.uxspace.spatial.WorkspaceController
import com.uxspace.system.NotificationCenter

/**
 * Per-slot notification panel — opened from the taskbar tray's notification icons or the
 * clock, like DeX's. Same modal model as the drawer / settings / audio panels.
 *
 * One card per notification (app, time, title, text). Clicking a card opens its app in a
 * workspace window — the notification's own PendingIntent would launch on the phone's
 * screen — and clears it if it's auto-cancel. × dismisses one; "Clear all" the rest.
 */
class NotificationsPanelView(context: Context) : LinearLayout(context) {

    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private lateinit var clearAll: TextView

    private val centerListener = NotificationCenter.Listener { render() }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        PanelStyle.applyCard(this)
        setPadding(dp(24), dp(20), dp(24), dp(20))
        addView(buildHeader(), LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })
        addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(list, ViewGroup.LayoutParams(MATCH, WRAP))
            },
            LayoutParams(MATCH, 0, 1f),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NotificationCenter.addListener(centerListener)
        render()
    }

    override fun onDetachedFromWindow() {
        NotificationCenter.removeListener(centerListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == VISIBLE) render()
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(context).apply {
                text = "Notifications"
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UxSpaceTheme.panelTitle)
            },
            LayoutParams(0, WRAP, 1f),
        )
        clearAll = pill("Clear all") { NotificationCenter.dismissAll() }
        addView(clearAll)
    }

    private fun render() {
        list.removeAllViews()
        val items = NotificationCenter.items
        clearAll.visibility = if (items.any { it.clearable }) VISIBLE else GONE
        when {
            !NotificationCenter.connected ->
                list.addView(emptyState("Waiting for notification access…"))
            items.isEmpty() -> list.addView(emptyState("No notifications"))
            else -> items.forEach { list.addView(card(it)) }
        }
    }

    private fun card(item: NotificationCenter.Item): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(UxSpaceTheme.pillInactiveBg)
        }
        setPadding(dp(14), dp(12), dp(10), dp(12))
        layoutParams = LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        addView(
            ImageView(context).apply {
                val icon = NotificationCenter.iconFor(item, context, UxSpaceTheme.panelLabel)
                if (icon != null) setImageDrawable(icon) else setImageResource(R.drawable.ic_notifications)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LayoutParams(dp(18), dp(18)).apply { topMargin = dp(2) },
        )
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(
                    TextView(context).apply {
                        text = "${item.appLabel} · ${relativeTime(item.postTime)}"
                        textSize = 11f
                        setTextColor(UxSpaceTheme.panelSection)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
                if (item.title.isNotBlank()) {
                    addView(
                        TextView(context).apply {
                            text = item.title
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(UxSpaceTheme.panelTitle)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) },
                    )
                }
                if (item.text.isNotBlank()) {
                    addView(
                        TextView(context).apply {
                            text = item.text
                            textSize = 13f
                            setTextColor(UxSpaceTheme.panelLabel)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) },
                    )
                }
            },
            LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) },
        )
        if (item.clearable) {
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_close)
                    imageTintList = ColorStateList.valueOf(UxSpaceTheme.panelSection)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    contentDescription = "Dismiss"
                    setOnClickListener { NotificationCenter.dismiss(item.key) }
                },
                LayoutParams(dp(26), dp(26)),
            )
        }
        setOnClickListener { open(item) }
    }

    /** Open the notification's app on the workspace. */
    private fun open(item: NotificationCenter.Item) {
        val component = context.packageManager.getLaunchIntentForPackage(item.packageName)?.component
        WorkspaceController.setTrayPanel(null)
        if (component != null) {
            WorkspaceController.launchApp(component.packageName, component.className, item.appLabel)
        }
        if (item.autoCancel) NotificationCenter.dismiss(item.key)
    }

    private fun relativeTime(postTime: Long): String =
        DateUtils.getRelativeTimeSpanString(
            postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()

    private fun emptyState(message: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(48), 0, dp(48))
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_notifications)
                imageTintList = ColorStateList.valueOf(UxSpaceTheme.panelSection)
            },
            LayoutParams(dp(36), dp(36)),
        )
        addView(
            TextView(context).apply {
                text = message
                textSize = 13f
                setTextColor(UxSpaceTheme.panelSection)
            },
            LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) },
        )
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(UxSpaceTheme.pillInactiveFg)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(UxSpaceTheme.pillInactiveBg)
        }
        setOnClickListener { onClick() }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
