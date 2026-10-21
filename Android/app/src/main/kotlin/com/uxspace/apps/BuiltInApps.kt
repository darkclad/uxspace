package com.uxspace.apps

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import com.uxspace.R
import com.uxspace.spatial.WorkspaceController

/**
 * UxSpace's own tools, listed in the app drawer next to installed apps. They're not
 * Android packages — no activity, no virtual display — so their [InstalledApp.packageName]
 * is a reserved id that can never collide with a real package, and launching one goes
 * through [launch] instead of `WorkspaceController.launchApp`.
 */
object BuiltInApps {

    /** Snellen-style chart for dialling in the glasses' focus wheels — docs/OPTOMETRY.md. */
    const val EYE_CHART = "uxspace.builtin.eyechart"

    fun isBuiltIn(packageName: String): Boolean = packageName.startsWith(PREFIX)

    fun list(context: Context): List<InstalledApp> = listOf(
        InstalledApp(
            label = "Eye chart",
            packageName = EYE_CHART,
            activityName = "",
            icon = icon(context, R.drawable.ic_eye, EYE_CHART_COLOR),
        ),
    )

    /** Open the built-in tool [packageName] on [screenIdx]. */
    fun launch(packageName: String, screenIdx: Int) {
        when (packageName) {
            EYE_CHART -> WorkspaceController.setOptometryOpen(true, screenIdx)
        }
    }

    /** A launcher-style icon: [glyph] in white on a round [color] backdrop. */
    private fun icon(context: Context, glyph: Int, color: Int): Drawable {
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val symbol = context.getDrawable(glyph)!!.mutate().apply { setTint(0xFFFFFFFF.toInt()) }
        val density = context.resources.displayMetrics.density
        val size = (ICON_DP * density).toInt()
        val glyphSize = (size * GLYPH_FRACTION).toInt()
        return LayerDrawable(arrayOf(disc, symbol)).apply {
            setLayerSize(0, size, size)
            setLayerSize(1, glyphSize, glyphSize)
            setLayerGravity(1, Gravity.CENTER)
        }
    }

    private const val PREFIX = "uxspace.builtin."
    private const val EYE_CHART_COLOR = 0xFF2F7DE1.toInt()

    /** Intrinsic icon size; the drawer scales it to its cell. */
    private const val ICON_DP = 48

    /** Glyph size as a fraction of the icon, so the symbol sits inside the disc. */
    private const val GLYPH_FRACTION = 0.56f
}
