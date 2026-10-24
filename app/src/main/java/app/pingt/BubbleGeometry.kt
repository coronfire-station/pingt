package app.pingt

import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Bubble position math, shared by the overlay and the settings screen.
 *
 * A position is stored per orientation as fractions of the free space on the FULL display
 * (status bar strip and cutout included): xf = x / (displayW - bubbleW), same for y.
 * 0.0 = left/top edge, 1.0 = right/bottom edge. That keeps the same visual anchor across
 * rotation, resolution and bubble size changes, and can never put the bubble off screen.
 */
object BubbleGeometry {

    /** Preset ids and labels, in grid order. */
    val PRESETS = listOf(
        "tl" to "Top-left", "tc" to "Top-center", "tr" to "Top-right",
        "ml" to "Middle-left", "c" to "Center", "mr" to "Middle-right",
        "bl" to "Bottom-left", "bc" to "Bottom-center", "br" to "Bottom-right",
    )
    val STATUS_BAR_PRESETS = listOf(
        "sbl" to "Status bar: left", "sbc" to "Status bar: center", "sbr" to "Status bar: right",
    )
    const val DEFAULT_PRESET = "tr"

    class Placement(val x: Int, val y: Int, val w: Int, val h: Int, val fitPx: Int)

    /** Full display size in the current rotation, system bars and cutout included. */
    fun screen(ctx: Context): Point {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val b = wm.maximumWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return Point(b.width(), b.height())
            } catch (ignored: Exception) {
            }
        }
        return Prefs.realScreenSize(ctx)
    }

    /** Status bar height in px (also when a fullscreen game hides it), 0 if unknown. */
    fun statusBarHeight(ctx: Context): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val top = wm.maximumWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                if (top > 0) return top
            } catch (ignored: Exception) {
            }
        }
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    fun fractions(x: Int, y: Int, w: Int, h: Int, scr: Point): PointF {
        val fw = scr.x - w
        val fh = scr.y - h
        return PointF(
            if (fw > 0) (x.toFloat() / fw).coerceIn(0f, 1f) else 0f,
            if (fh > 0) (y.toFloat() / fh).coerceIn(0f, 1f) else 0f,
        )
    }

    /**
     * Fractions → pixels, clamped to the full display only.
     * With "fit to status bar height", a bubble whose center sits in the status bar strip
     * is scaled to exactly the strip height (min 16dp).
     */
    fun resolve(xf: Float, yf: Float, s: BubbleSettings, scr: Point, sbH: Int, density: Float): Placement {
        val fx = xf.coerceIn(0f, 1f)
        val fy = yf.coerceIn(0f, 1f)
        val sz = BubbleView.sizePx(s.style, s.sizeMult, density)
        val x = (fx * maxOf(0, scr.x - sz.x)).roundToInt()
        val y = (fy * maxOf(0, scr.y - sz.y)).roundToInt()
        if (s.fitStatusBar && sbH > 0 && y + sz.y / 2 <= sbH) {
            val fh = maxOf(sbH, (16 * density).roundToInt())
            val fit = BubbleView.sizeForHeight(s.style, fh)
            return Placement(
                (fx * maxOf(0, scr.x - fit.x)).roundToInt(),
                (fy * maxOf(0, scr.y - fit.y)).roundToInt(),
                fit.x, fit.y, fh
            )
        }
        return Placement(x, y, sz.x, sz.y, 0)
    }

    /** Where a preset puts the bubble, as fractions for the given screen. */
    fun presetFractions(id: String, s: BubbleSettings, scr: Point, sbH: Int, density: Float): PointF {
        val sz = BubbleView.sizePx(s.style, s.sizeMult, density)
        val w = sz.x
        val h = sz.y
        val m = (8 * density).roundToInt()
        val sbm = (16 * density).roundToInt() // clear of rounded display corners
        val belowBar = if (sbH > 0) sbH + (4 * density).roundToInt() else m
        val inBar = if (s.fitStatusBar || sbH <= h) 0 else (sbH - h) / 2
        val x = when (id) {
            "tl", "ml", "bl" -> m
            "tr", "mr", "br" -> scr.x - w - m
            "sbl" -> sbm
            "sbr" -> scr.x - w - sbm
            else -> (scr.x - w) / 2
        }
        val y = when (id) {
            "tl", "tc", "tr" -> belowBar
            "ml", "c", "mr" -> (scr.y - h) / 2
            "bl", "bc", "br" -> scr.y - h - m
            else -> inBar
        }
        return fractions(x, y, w, h, scr)
    }
}
