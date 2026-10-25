package app.pingt

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.PointF
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
    const val SNAP_MODE = "snap_mode"           // off | edges | corner
    const val FIT_STATUS_BAR = "fit_status_bar"
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
    private const val VERSION = "prefs_version"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Bubble position per orientation: top-left / (display size - bubble size), 0.0–1.0. */
    fun posXKey(landscape: Boolean) = if (landscape) "pos_land_xf" else "pos_port_xf"
    fun posYKey(landscape: Boolean) = if (landscape) "pos_land_yf" else "pos_port_yf"
    fun isPosKey(key: String?) = key != null && key.startsWith("pos_")

    private val POS_KEYS = listOf(posXKey(false), posYKey(false), posXKey(true), posYKey(true))

    /** Saved positions for both orientations (null = never set), for the positioning tool's Cancel. */
    fun readPositions(p: SharedPreferences): Map<String, Float?> =
        POS_KEYS.associateWith { if (p.contains(it)) p.getFloat(it, 0f) else null }

    fun writePositions(p: SharedPreferences, pos: Map<String, Float?>) {
        val e = p.edit()
        pos.forEach { (k, v) -> if (v == null) e.remove(k) else e.putFloat(k, v) }
        e.apply()
    }

    fun savePosition(p: SharedPreferences, landscape: Boolean, f: PointF) {
        p.edit().putFloat(posXKey(landscape), f.x).putFloat(posYKey(landscape), f.y).apply()
    }

    /**
     * One-time upgrade of v1.1 settings:
     * - pixel positions (pos_port_x …) become fractions of the free space
     * - snap_edge true/false becomes snap_mode edges/off (an untouched v1.1 install had snap on)
     */
    fun migrate(ctx: Context) {
        val p = get(ctx)
        if (p.getInt(VERSION, 1) >= 2) return
        val e = p.edit()
        if (p.all.isNotEmpty() && !p.contains(SNAP_MODE)) {
            e.putString(SNAP_MODE, if (p.getBoolean("snap_edge", true)) "edges" else "off")
        }
        e.remove("snap_edge")

        val real = realScreenSize(ctx)
        val short = minOf(real.x, real.y)
        val long = maxOf(real.x, real.y)
        val s = BubbleSettings.load(p)
        val size = BubbleView.sizePx(s.style, s.sizeMult, ctx.resources.displayMetrics.density)
        for (land in listOf(false, true)) {
            val old = if (land) "pos_land_" else "pos_port_"
            if (p.contains(old + "x") && p.contains(old + "y")) {
                val scr = if (land) Point(long, short) else Point(short, long)
                val f = BubbleGeometry.fractions(p.getInt(old + "x", 0), p.getInt(old + "y", 0), size.x, size.y, scr)
                e.putFloat(posXKey(land), f.x).putFloat(posYKey(land), f.y)
            }
            e.remove(old + "x").remove(old + "y")
        }
        e.putInt(VERSION, 2).apply()
    }

    // ---------------------------------------------------------------- per-orientation settings

    /**
     * Bubble settings that can differ between portrait and landscape. The landscape value is
     * stored under "<key>_land"; while it is unset, landscape uses the portrait (base) value.
     */
    val ORIENTED = setOf(
        SHOW_BUBBLE, STYLE, SIZE, OPACITY, BACKGROUND, CLICK_THROUGH, ONLY_WHEN_BAD,
        BLINK_TIMEOUT, SNAP_MODE, LOCK_POSITION, FIT_STATUS_BAR,
    )
    private const val LAND_SUFFIX = "_land"

    /** Key to write for this orientation. */
    fun key(base: String, landscape: Boolean) =
        if (landscape && base in ORIENTED) base + LAND_SUFFIX else base

    /** Key to read: the landscape key if set, else the shared/portrait one. */
    private fun readKey(p: SharedPreferences, base: String, landscape: Boolean): String {
        val k = key(base, landscape)
        return if (p.contains(k)) k else base
    }

    fun getBool(p: SharedPreferences, base: String, landscape: Boolean, def: Boolean) =
        p.getBoolean(readKey(p, base, landscape), def)

    fun getStr(p: SharedPreferences, base: String, landscape: Boolean, def: String) =
        p.getString(readKey(p, base, landscape), def) ?: def

    fun getInt(p: SharedPreferences, base: String, landscape: Boolean, def: Int) =
        p.getInt(readKey(p, base, landscape), def)

    fun hasLandscapeOverrides(p: SharedPreferences) = ORIENTED.any { p.contains(it + LAND_SUFFIX) }

    /** Landscape goes back to using the portrait settings. */
    fun clearLandscapeOverrides(p: SharedPreferences) {
        val e = p.edit()
        ORIENTED.forEach { e.remove(it + LAND_SUFFIX) }
        e.apply()
    }

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
    val snapMode: String,
    val fitStatusBar: Boolean,
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
        /** Settings for one orientation (shared ping settings are the same for both). */
        fun load(p: SharedPreferences, landscape: Boolean = false): BubbleSettings {
            var green = p.getInt(Prefs.GREEN_MAX, 80)
            var yellow = p.getInt(Prefs.YELLOW_MAX, 150)
            if (!Prefs.thresholdsValid(green, yellow)) {
                green = 80
                yellow = 150
            }
            return BubbleSettings(
                show = Prefs.getBool(p, Prefs.SHOW_BUBBLE, landscape, true),
                style = Prefs.getStr(p, Prefs.STYLE, landscape, "pill"),
                size = Prefs.getStr(p, Prefs.SIZE, landscape, "medium"),
                opacity = Prefs.getInt(p, Prefs.OPACITY, landscape, 90).coerceIn(20, 100),
                background = Prefs.getStr(p, Prefs.BACKGROUND, landscape, "dark"),
                clickThrough = Prefs.getBool(p, Prefs.CLICK_THROUGH, landscape, false),
                onlyWhenBad = Prefs.getBool(p, Prefs.ONLY_WHEN_BAD, landscape, false),
                blinkTimeout = Prefs.getBool(p, Prefs.BLINK_TIMEOUT, landscape, true),
                snapMode = Prefs.getStr(p, Prefs.SNAP_MODE, landscape, "off"),
                fitStatusBar = Prefs.getBool(p, Prefs.FIT_STATUS_BAR, landscape, false),
                lockPosition = Prefs.getBool(p, Prefs.LOCK_POSITION, landscape, false),
                hideInApp = p.getBoolean(Prefs.HIDE_IN_APP, true),
                vibrateAfter = p.getInt(Prefs.VIBRATE_AFTER, 3),
                greenMax = green,
                yellowMax = yellow,
                intervalMs = p.getInt(Prefs.INTERVAL_MS, 1000).coerceIn(500, 5000),
            )
        }
    }
}
