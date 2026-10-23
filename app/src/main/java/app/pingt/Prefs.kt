package app.pingt

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.view.Display

/** Every setting lives in this one SharedPreferences file. */
object Prefs {
    const val FILE = "pingt_settings"

    const val SHOW_BUBBLE = "show_bubble"
    const val STYLE = "style"
    const val SIZE = "size"
    const val OPACITY = "opacity"
    const val BACKGROUND = "background"
    const val CLICK_THROUGH = "click_through"
    const val ONLY_WHEN_BAD = "only_when_bad"
    const val BLINK_TIMEOUT = "blink_timeout"
    const val SNAP_EDGE = "snap_edge"
    const val LOCK_POSITION = "lock_position"
    const val HIDE_IN_APP = "hide_in_app"
    const val VIBRATE_AFTER = "vibrate_after"
    const val GREEN_MAX = "green_max"
    const val YELLOW_MAX = "yellow_max"
    const val INTERVAL_MS = "interval_ms"

    const val LAST_HOST = "last_host"
    const val RUNNING_HOST = "running_host" // set while pinging, used if Android restarts the service
    const val NOTIF_ASKED = "notif_asked"
    const val OVERLAY_ASKED = "overlay_asked"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun posXKey(landscape: Boolean) = if (landscape) "pos_land_x" else "pos_port_x"
    fun posYKey(landscape: Boolean) = if (landscape) "pos_land_y" else "pos_port_y"
    fun isPosKey(key: String?) = key != null && key.startsWith("pos_")

    fun thresholdsValid(green: Int, yellow: Int) = green >= 1 && green < yellow && yellow <= 2000

    /** Full physical screen size in the current rotation. */
    @Suppress("DEPRECATION")
    fun realScreenSize(ctx: Context): Point {
        val p = Point()
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }
}

data class BubbleSettings(
    val show: Boolean,
    val style: String,
    val size: String,
    val opacity: Int,
    val background: String,
    val clickThrough: Boolean,
    val onlyWhenBad: Boolean,
    val blinkTimeout: Boolean,
    val snapEdge: Boolean,
    val lockPosition: Boolean,
    val hideInApp: Boolean,
    val vibrateAfter: Int,
    val greenMax: Int,
    val yellowMax: Int,
    val intervalMs: Int,
) {
    val sizeMult: Float
        get() = when (size) {
            "small" -> 0.75f
            "large" -> 1.35f
            else -> 1f
        }

    /**
     * Window alpha for the overlay. On Android 12+ a window that touches pass through
     * must not be more opaque than InputManager's max obscuring opacity (0.8 by default),
     * otherwise the system blocks the touch to the app underneath.
     */
    fun windowAlpha(ctx: Context): Float {
        var a = opacity / 100f
        if (clickThrough && Build.VERSION.SDK_INT >= 31) {
            val im = ctx.getSystemService(Context.INPUT_SERVICE) as InputManager
            a = minOf(a, im.maximumObscuringOpacityForTouch)
        }
        return a
    }

    companion object {
        fun load(p: SharedPreferences): BubbleSettings {
            var green = p.getInt(Prefs.GREEN_MAX, 80)
            var yellow = p.getInt(Prefs.YELLOW_MAX, 150)
            if (!Prefs.thresholdsValid(green, yellow)) {
                green = 80
                yellow = 150
            }
            return BubbleSettings(
                show = p.getBoolean(Prefs.SHOW_BUBBLE, true),
                style = p.getString(Prefs.STYLE, "pill") ?: "pill",
                size = p.getString(Prefs.SIZE, "medium") ?: "medium",
                opacity = p.getInt(Prefs.OPACITY, 90).coerceIn(20, 100),
                background = p.getString(Prefs.BACKGROUND, "dark") ?: "dark",
                clickThrough = p.getBoolean(Prefs.CLICK_THROUGH, false),
                onlyWhenBad = p.getBoolean(Prefs.ONLY_WHEN_BAD, false),
                blinkTimeout = p.getBoolean(Prefs.BLINK_TIMEOUT, true),
                snapEdge = p.getBoolean(Prefs.SNAP_EDGE, true),
                lockPosition = p.getBoolean(Prefs.LOCK_POSITION, false),
                hideInApp = p.getBoolean(Prefs.HIDE_IN_APP, true),
                vibrateAfter = p.getInt(Prefs.VIBRATE_AFTER, 3),
                greenMax = green,
                yellowMax = yellow,
                intervalMs = p.getInt(Prefs.INTERVAL_MS, 1000).coerceIn(500, 5000),
            )
        }
    }
}
