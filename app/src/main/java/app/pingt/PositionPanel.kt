package app.pingt

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * On-screen positioning tool: a small draggable control panel overlay that moves the bubble
 * with arrows and presets, so it can be placed where touch can't reach (status bar strip).
 * Every move is saved right away; Cancel restores the snapshot PingService took on open.
 */
class PositionPanel(
    private val ctx: Context,
    private val overlay: BubbleOverlay,
    private val onDone: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val wm = overlay.wm
    private val density = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val params = BubbleOverlay.overlayParams()
    private val root = LinearLayout(ctx)
    private val readout = TextView(ctx)
    private val stepChips = ArrayList<Pair<TextView, Boolean>>()
    private var coarse = false
    private var attached = false
    private var repeater: Runnable? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    private fun px(v: Float) = (v * density).roundToInt()

    init {
        params.width = px(PANEL_DP)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.title = "Ping -t position"
        build()
    }

    fun show() {
        if (attached) return
        place()
        try {
            wm.addView(root, params)
            attached = true
        } catch (e: Exception) {
            attached = false
        }
        overlay.onMoved = { refresh() }
        refresh()
    }

    fun hide() {
        stopRepeat()
        overlay.onMoved = null
        if (!attached) return
        attached = false
        try {
            wm.removeViewImmediate(root)
        } catch (ignored: Exception) {
        }
    }

    fun onScreenChanged() {
        place()
        relayout()
        refresh()
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(px(10f), px(6f), px(10f), px(10f))
        root.background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(0xF2161616.toInt())
            setStroke(px(1f), 0xFF333333.toInt())
        }

        // Title bar: drag handle for the panel itself
        val title = TextView(ctx).apply {
            text = "≡  Bubble position"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(px(2f), px(6f), 0, px(6f))
            setOnTouchListener { _, e -> dragPanel(e) }
        }
        root.addView(title)

        readout.apply {
            setTextColor(0xFFC0C0C0.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(px(2f), 0, 0, px(6f))
        }
        root.addView(readout)

        // D-pad
        val cell = px(42f)
        fun row(vararg views: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            views.forEach { addView(it, LinearLayout.LayoutParams(cell, cell).apply { setMargins(px(2f), px(2f), px(2f), px(2f)) }) }
        }
        fun blank() = View(ctx)
        root.addView(row(blank(), arrow("↑", 0, -1), blank()), matchWrap())
        root.addView(row(arrow("←", -1, 0), blank(), arrow("→", 1, 0)), matchWrap())
        root.addView(row(blank(), arrow("↓", 0, 1), blank()), matchWrap())

        // Step size
        val steps = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Fine 1dp" to false, "Coarse 8dp" to true).forEach { (label, isCoarse) ->
            val c = chip(label) { coarse = isCoarse; styleSteps() }
            stepChips.add(c to isCoarse)
            steps.addView(c, weighted())
        }
        root.addView(steps, matchWrap(top = 6f))
        styleSteps()

        // Presets
        val presets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("SB L" to "sbl", "SB C" to "sbc", "SB R" to "sbr", "Center" to "c").forEach { (label, id) ->
            val c = chip(label) { overlay.applyPreset(id) }
            c.textSize = 10.5f
            styleChip(c, false)
            presets.addView(c, weighted(if (id == "c") 1.4f else 1f))
        }
        root.addView(presets, matchWrap(top = 6f))

        // Cancel / Done
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Cancel", 0xFF333333.toInt()) { onCancel() }, weighted())
        buttons.addView(button("Done", 0xFF1E7D3A.toInt()) { onDone() }, weighted())
        root.addView(buttons, matchWrap(top = 8f))
    }

    private fun matchWrap(top: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(top)
        }

    private fun weighted(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply {
        setMargins(px(2f), 0, px(2f), 0)
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(px(2f), px(6f), px(2f), px(6f))
        setOnClickListener { onClick() }
    }

    private fun styleChip(v: TextView, selected: Boolean) {
        v.setTextColor(if (selected) Color.WHITE else 0xFFC0C0C0.toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(if (selected) 0xFF1E7D3A.toInt() else 0xFF1E1E1E.toInt())
            setStroke(px(1f), if (selected) 0xFF1E7D3A.toInt() else 0xFF444444.toInt())
        }
    }

    private fun styleSteps() = stepChips.forEach { (c, isCoarse) -> styleChip(c, isCoarse == coarse) }

    private fun button(label: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(0, px(9f), 0, px(9f))
        background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(color)
        }
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- arrows

    /** Tap = one step. Hold = repeat, speeding up 1dp → 4dp → 12dp (never below the chosen step). */
    private fun arrow(label: String, dx: Int, dy: Int) = TextView(ctx).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(0xFF262626.toInt())
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.6f
                    move(dx, dy, 0)
                    startRepeat(dx, dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    stopRepeat()
                }
            }
            true
        }
    }

    private fun startRepeat(dx: Int, dy: Int) {
        stopRepeat()
        val r = object : Runnable {
            var n = 0
            override fun run() {
                n++
                move(dx, dy, n)
                handler.postDelayed(this, 60)
            }
        }
        repeater = r
        handler.postDelayed(r, 400)
    }

    private fun stopRepeat() {
        repeater?.let { handler.removeCallbacks(it) }
        repeater = null
    }

    private fun move(dx: Int, dy: Int, repeat: Int) {
        val accel = when {
            repeat < 10 -> 1f
            repeat < 25 -> 4f
            else -> 12f
        }
        val step = px(maxOf(if (coarse) 8f else 1f, accel)).coerceAtLeast(1)
        overlay.nudge(dx * step, dy * step)
    }

    // ---------------------------------------------------------------- panel window

    private fun refresh() {
        val b = overlay.bounds
        val f = overlay.fraction
        readout.text = String.format(
            Locale.US, "X %5dpx %5.1f%%\nY %5dpx %5.1f%%", b.left, f.x * 100, b.top, f.y * 100
        )
    }

    /** Middle of the screen; moved to the other side if it would cover the bubble. */
    private fun place() {
        val scr = overlay.screenSize
        val pw = params.width
        root.measure(
            View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val ph = root.measuredHeight
        var x = (scr.x - pw) / 2
        val y = maxOf(0, (scr.y - ph) / 2)
        val bubble = overlay.bounds
        if (Rect.intersects(Rect(x, y, x + pw, y + ph), bubble)) {
            x = if (bubble.centerX() < scr.x / 2) scr.x - pw - px(16f) else px(16f)
        }
        params.x = x.coerceIn(0, maxOf(0, scr.x - pw))
        params.y = y
    }

    private fun dragPanel(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startX = params.x
                startY = params.y
            }
            MotionEvent.ACTION_MOVE -> {
                val scr = overlay.screenSize
                params.x = (startX + (e.rawX - downRawX).toInt()).coerceIn(0, maxOf(0, scr.x - root.width))
                params.y = (startY + (e.rawY - downRawY).toInt()).coerceIn(0, maxOf(0, scr.y - root.height))
                relayout()
            }
        }
        return true
    }

    private fun relayout() {
        if (!attached) return
        try {
            wm.updateViewLayout(root, params)
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private const val PANEL_DP = 180f
    }
}
