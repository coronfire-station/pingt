package app.pingt

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The floating bubble window. Owned by PingService; drawn by BubbleView.
 * Uses full-display coordinates (y = 0 is the physical top, status bar strip included).
 * Position is saved per orientation as fractions, see BubbleGeometry.
 */
class BubbleOverlay(private val ctx: Context, private val prefs: SharedPreferences) {

    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = BubbleView(ctx)
    private val params = overlayParams()
    private val density = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var settings: BubbleSettings? = null
    private var attached = false
    private var landscape = false
    private var screen = Point(1, 1)
    private var statusBarH = 0
    private var curW = 0
    private var curH = 0
    private var snapAnim: ValueAnimator? = null

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    /** Positioning tool open: a tap does not open the app. */
    var positioning = false

    /** Called whenever the bubble moved (positioning tool readout). */
    var onMoved: (() -> Unit)? = null

    init {
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.title = "Ping -t bubble"
        view.setOnTouchListener { _, e -> onTouch(e) }
    }

    fun applySettings(s: BubbleSettings) {
        settings = s
        view.setSettings(s)
        var f = BASE_FLAGS
        // Game mode: every touch goes to the app underneath
        if (s.clickThrough) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = f
        params.alpha = s.windowAlpha(ctx)
        // Style/size may have changed: same saved anchor, new pixel spot
        if (!dragging && snapAnim == null) place() else relayout()
    }

    fun show() {
        if (attached) return
        place()
        try {
            wm.addView(view, params)
            attached = true
        } catch (e: Exception) {
            attached = false // permission revoked or window rejected
        }
    }

    fun hide() {
        dragging = false
        if (attached) {
            attached = false
            try {
                wm.removeViewImmediate(view)
            } catch (ignored: Exception) {
            }
        }
        // Last: ending the snap saves the position, which re-enters the prefs listener
        snapAnim?.cancel()
    }

    /** Push the latest ping result into the bubble. */
    fun update(s: BubbleSettings) {
        view.setData(
            PingState.lastKind, PingState.lastMs, PingState.alert,
            PingState.history.toList().takeLast(BubbleView.GRAPH_POINTS)
        )
        view.setBlinking(attached && s.blinkTimeout && PingState.lastKind == PingState.Kind.LOST)
    }

    /** Rotation or display change: use that orientation's saved spot. */
    fun onScreenChanged() {
        snapAnim?.cancel()
        dragging = false
        place()
    }

    /** Saved position changed (preset, Cancel, the other screen). */
    fun reloadPosition() {
        if (dragging || snapAnim != null) return
        place()
    }

    // ---------------------------------------------------------------- positioning tool API

    val isAttached get() = attached
    val screenSize get() = Point(screen)
    val bounds get() = Rect(params.x, params.y, params.x + curW, params.y + curH)
    val fraction: PointF get() = BubbleGeometry.fractions(params.x, params.y, curW, curH, screen)

    fun nudge(dx: Int, dy: Int) {
        snapAnim?.cancel()
        params.x += dx
        params.y += dy
        commitPosition()
    }

    fun applyPreset(id: String) {
        val s = settings ?: return
        snapAnim?.cancel()
        refreshScreen()
        Prefs.savePosition(prefs, landscape, BubbleGeometry.presetFractions(id, s, screen, statusBarH, density))
        place()
    }

    // ---------------------------------------------------------------- position

    private fun refreshScreen() {
        screen = BubbleGeometry.screen(ctx)
        landscape = screen.x > screen.y
        statusBarH = BubbleGeometry.statusBarHeight(ctx)
    }

    /** Saved fractions for this orientation → pixels. */
    private fun place() {
        val s = settings ?: return
        refreshScreen()
        val f = if (prefs.contains(Prefs.posXKey(landscape))) {
            PointF(prefs.getFloat(Prefs.posXKey(landscape), 1f), prefs.getFloat(Prefs.posYKey(landscape), 0f))
        } else {
            BubbleGeometry.presetFractions(BubbleGeometry.DEFAULT_PRESET, s, screen, statusBarH, density)
        }
        val p = BubbleGeometry.resolve(f.x, f.y, s, screen, statusBarH, density)
        view.setFitHeight(p.fitPx)
        params.x = p.x
        params.y = p.y
        curW = p.w
        curH = p.h
        relayout()
        onMoved?.invoke()
    }

    /** Only keeps the bubble fully on the display. Status bar strip and cutout are allowed. */
    private fun clamp() {
        params.x = params.x.coerceIn(0, maxOf(0, screen.x - curW))
        params.y = params.y.coerceIn(0, maxOf(0, screen.y - curH))
    }

    /** Save the current pixel spot as fractions, then re-place (fit to status bar may change the size). */
    private fun commitPosition() {
        clamp()
        Prefs.savePosition(prefs, landscape, BubbleGeometry.fractions(params.x, params.y, curW, curH, screen))
        place()
    }

    private fun relayout() {
        if (!attached) return
        try {
            wm.updateViewLayout(view, params)
        } catch (ignored: Exception) {
        }
    }

    // ---------------------------------------------------------------- touch

    private fun onTouch(e: MotionEvent): Boolean {
        val s = settings ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnim?.cancel()
                downRawX = e.rawX
                downRawY = e.rawY
                startX = params.x
                startY = params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (s.lockPosition) return true
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > slop) dragging = true
                if (dragging) {
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    clamp()
                    relayout()
                    onMoved?.invoke()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    // The touch began on the bubble, so a drop in the status bar strip stays there
                    if (s.snapMode == "edges" || s.snapMode == "corner") snap(s.snapMode) else commitPosition()
                } else if (!positioning) {
                    openApp()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    commitPosition()
                }
            }
        }
        return true
    }

    /** edges: nearest left/right edge. corner: nearest corner; top corners sit at y = 0. */
    private fun snap(mode: String) {
        val m = (4 * density).roundToInt()
        val fromX = params.x
        val fromY = params.y
        val toX = if (params.x + curW / 2 < screen.x / 2) m else screen.x - curW - m
        val toY = if (mode != "corner") params.y
            else if (params.y + curH / 2 < screen.y / 2) 0 else screen.y - curH - m
        snapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                params.x = (fromX + (toX - fromX) * t).roundToInt()
                params.y = (fromY + (toY - fromY) * t).roundToInt()
                clamp()
                relayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapAnim = null
                    commitPosition()
                }
            })
            start()
        }
    }

    private fun openApp() {
        try {
            ctx.startActivity(PingService.launchIntent(ctx))
        } catch (ignored: Exception) {
        }
    }

    companion object {
        /** Full-display coordinates: y = 0 is the physical top, the status bar strip included. */
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** LayoutParams shared by the bubble and the positioning panel. */
        fun overlayParams() = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = BASE_FLAGS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            // May sit next to / in the notch
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0 // don't push the window below the status bar
            }
        }
    }
}
