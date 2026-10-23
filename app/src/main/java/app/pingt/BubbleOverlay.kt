package app.pingt

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

/**
 * The floating bubble window. Owned by PingService; drawn by BubbleView.
 * Position is saved per orientation (portrait / landscape).
 */
class BubbleOverlay(private val ctx: Context, private val prefs: SharedPreferences) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = BubbleView(ctx)
    private val params = WindowManager.LayoutParams()
    private val density = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var settings: BubbleSettings? = null
    private var attached = false
    private var landscape = false
    private var snapAnim: ValueAnimator? = null

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    init {
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params.format = PixelFormat.TRANSLUCENT
        params.gravity = Gravity.TOP or Gravity.START
        params.title = "Ping -t bubble"
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            params.fitInsetsTypes = 0 // x/y are plain screen coordinates, status/nav bars included
        }
        view.setOnTouchListener { _, e -> onTouch(e) }
    }

    fun applySettings(s: BubbleSettings) {
        settings = s
        view.setSettings(s)
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // Game mode: every touch goes to the app underneath
        if (s.clickThrough) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = f
        params.alpha = s.windowAlpha(ctx)
        if (attached) {
            clamp()
            relayout()
        }
    }

    fun show() {
        if (attached) return
        loadPosition()
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

    /** Rotation or screen size changed: use that orientation's saved spot. */
    fun onScreenChanged() {
        snapAnim?.cancel()
        dragging = false
        loadPosition()
        if (attached) relayout()
    }

    /** Saved position changed (e.g. a corner preset in settings). */
    fun reloadPosition() {
        if (dragging || snapAnim != null) return
        loadPosition()
        if (attached) relayout()
    }

    // ---------------------------------------------------------------- position

    private fun loadPosition() {
        val sz = Prefs.realScreenSize(ctx)
        landscape = sz.x > sz.y
        val m = (8 * density).toInt()
        params.x = prefs.getInt(Prefs.posXKey(landscape), m)
        params.y = prefs.getInt(Prefs.posYKey(landscape), if (landscape) m else (sz.y * 0.12f).toInt())
        clamp()
    }

    private fun clamp() {
        val sz = Prefs.realScreenSize(ctx)
        params.x = params.x.coerceIn(0, maxOf(0, sz.x - view.bubbleWidth()))
        params.y = params.y.coerceIn(0, maxOf(0, sz.y - view.bubbleHeight()))
    }

    private fun savePosition() {
        prefs.edit()
            .putInt(Prefs.posXKey(landscape), params.x)
            .putInt(Prefs.posYKey(landscape), params.y)
            .apply()
    }

    private fun relayout() {
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
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    if (s.snapEdge) snapToEdge() else savePosition()
                } else {
                    openApp()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    savePosition()
                }
            }
        }
        return true
    }

    private fun snapToEdge() {
        val sz = Prefs.realScreenSize(ctx)
        val w = view.bubbleWidth()
        val m = (4 * density).toInt()
        val target = if (params.x + w / 2 < sz.x / 2) m else maxOf(0, sz.x - w - m)
        snapAnim = ValueAnimator.ofInt(params.x, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (attached) relayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapAnim = null
                    savePosition()
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
}
