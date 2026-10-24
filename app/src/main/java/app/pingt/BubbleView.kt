package app.pingt

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Draws the floating bubble in every style with Canvas. Used by the overlay and by
 * the live preview in SettingsActivity, so both always look the same.
 */
class BubbleView(context: Context) : View(context) {

    private var bubbleStyle = "pill"
    private var userMult = 1f
    private var fitPx = 0 // > 0: scaled so the height is exactly this (fit to status bar)
    private val mult get() = if (fitPx > 0) fitPx / (baseDp(bubbleStyle).second * density) else userMult
    private var bg = "dark"
    private var greenMax = 80
    private var yellowMax = 150

    private var kind = PingState.Kind.NONE
    private var ms = 0.0
    private var alert = false
    private var history: List<Double?> = emptyList()

    private var blinkAnim: ObjectAnimator? = null
    private val density = resources.displayMetrics.density
    private var u = 1f // 1 dp at the current size, set in onDraw

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    private val light get() = bg == "light"
    private val outlined get() = bg == "none"

    fun setSettings(s: BubbleSettings) {
        val resized = s.style != bubbleStyle || s.sizeMult != userMult
        bubbleStyle = s.style
        userMult = s.sizeMult
        bg = s.background
        greenMax = s.greenMax
        yellowMax = s.yellowMax
        if (resized) requestLayout()
        invalidate()
    }

    fun setFitHeight(px: Int) {
        if (px == fitPx) return
        fitPx = px
        requestLayout()
        invalidate()
    }

    fun setData(kind: PingState.Kind, ms: Double, alert: Boolean, history: List<Double?>) {
        this.kind = kind
        this.ms = ms
        this.alert = alert
        this.history = history
        invalidate()
    }

    /** Pulse while the last result is a timeout/failure. */
    fun setBlinking(on: Boolean) {
        if (on == (blinkAnim != null)) return
        if (on) {
            blinkAnim = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.25f).apply {
                duration = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            blinkAnim?.cancel()
            blinkAnim = null
            alpha = 1f
        }
    }

    override fun onDetachedFromWindow() {
        setBlinking(false)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- size

    fun bubbleWidth() = ceil(baseDp(bubbleStyle).first * density * mult).toInt()
    fun bubbleHeight() = ceil(baseDp(bubbleStyle).second * density * mult).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(bubbleWidth(), bubbleHeight())
    }

    // ---------------------------------------------------------------- colors

    private fun colorFor(v: Double) = when {
        v < greenMax -> if (light) DARK_GREEN else PingState.GREEN
        v < yellowMax -> if (light) DARK_YELLOW else PingState.YELLOW
        else -> PingState.RED
    }

    private fun stateColor() = when (kind) {
        PingState.Kind.NONE -> PingState.GRAY
        PingState.Kind.OK -> colorFor(ms)
        PingState.Kind.LOST -> PingState.RED
    }

    private fun label() = when (kind) {
        PingState.Kind.NONE -> "--"
        PingState.Kind.LOST -> "TO"
        PingState.Kind.OK -> if (ms < 1.0) "<1" else ms.roundToLong().toString()
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        u = density * mult
        val round = bubbleStyle == "dot" || bubbleStyle == "ring"
        val col = stateColor()

        if (!outlined) {
            fill.style = Paint.Style.FILL
            fill.color = if (light) BG_LIGHT else BG_DARK
            if (round) {
                c.drawCircle(w / 2, h / 2, min(w, h) / 2, fill)
            } else {
                rect.set(0f, 0f, w, h)
                c.drawRoundRect(rect, h / 2, h / 2, fill)
            }
            if (alert) {
                line.color = PingState.RED
                line.strokeWidth = 1.5f * u
                val i = line.strokeWidth / 2
                if (round) {
                    c.drawCircle(w / 2, h / 2, min(w, h) / 2 - i, line)
                } else {
                    rect.set(i, i, w - i, h - i)
                    c.drawRoundRect(rect, h / 2 - i, h / 2 - i, line)
                }
            }
        }

        when (bubbleStyle) {
            "dot" -> drawDot(c, w / 2, h / 2, min(w, h) / 2 - (if (outlined) 1.5f else 3f) * u, col)
            "text" -> drawLabel(c, w / 2, h / 2, w - 12 * u, 14 * u, col)
            "bars" -> {
                drawBars(c, 7 * u, h / 2 + 9 * u, col)
                val left = 30 * u
                val right = w - 5 * u
                drawLabel(c, (left + right) / 2, h / 2, right - left, 13 * u, col)
            }
            "graph" -> {
                drawGraph(c, 10 * u, 7 * u, w - 40 * u, h - 7 * u, col)
                val left = w - 36 * u
                val right = w - 8 * u
                drawLabel(c, (left + right) / 2, h / 2, right - left, 13 * u, col)
            }
            "ring" -> {
                val r = min(w, h) / 2 - 5 * u
                if (outlined) {
                    line.color = OUTLINE
                    line.strokeWidth = 5 * u
                    c.drawCircle(w / 2, h / 2, r, line)
                }
                line.color = col
                line.strokeWidth = 3 * u
                c.drawCircle(w / 2, h / 2, r, line)
                drawLabel(c, w / 2, h / 2, 2 * r - 7 * u, 12 * u, col)
            }
            else -> { // pill
                drawDot(c, h / 2, h / 2, 5 * u, col)
                val left = 25 * u
                val right = w - 8 * u
                drawLabel(c, (left + right) / 2, h / 2, right - left, 14 * u, col)
            }
        }
    }

    private fun drawDot(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        fill.style = Paint.Style.FILL
        if (outlined) {
            fill.color = OUTLINE
            c.drawCircle(cx, cy, r + 1.5f * u, fill)
        }
        fill.color = col
        c.drawCircle(cx, cy, r, fill)
    }

    /** ms text centered at (cx, cy), shrunk to fit maxW. */
    private fun drawLabel(c: Canvas, cx: Float, cy: Float, maxW: Float, size: Float, col: Int) {
        val t = label()
        text.textSize = size
        val tw = text.measureText(t)
        if (tw > maxW && tw > 0) text.textSize = size * maxW / tw
        val fm = text.fontMetrics
        val base = cy - (fm.ascent + fm.descent) / 2
        if (outlined) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = 3 * u
            text.color = OUTLINE
            c.drawText(t, cx, base, text)
        }
        text.style = Paint.Style.FILL
        text.color = col
        c.drawText(t, cx, base, text)
    }

    /** 4 signal bars: green=4, yellow=3, red=1, timeout=0 with a red slash. */
    private fun drawBars(c: Canvas, x0: Float, bottom: Float, col: Int) {
        val lit = when (kind) {
            PingState.Kind.OK -> when {
                ms < greenMax -> 4
                ms < yellowMax -> 3
                else -> 1
            }
            else -> 0
        }
        val bw = 3.5f * u
        val gap = 2f * u
        val heights = floatArrayOf(6f, 10f, 14f, 18f)
        fill.style = Paint.Style.FILL
        for (i in 0 until 4) {
            val left = x0 + i * (bw + gap)
            rect.set(left, bottom - heights[i] * u, left + bw, bottom)
            if (outlined) {
                fill.color = OUTLINE
                rect.inset(-1.2f * u, -1.2f * u)
                c.drawRoundRect(rect, u, u, fill)
                rect.inset(1.2f * u, 1.2f * u)
            }
            fill.color = if (i < lit) col else if (light) DIM_ON_LIGHT else DIM_ON_DARK
            c.drawRoundRect(rect, u, u, fill)
        }
        if (kind == PingState.Kind.LOST) {
            val right = x0 + 4 * bw + 3 * gap
            line.color = PingState.RED
            line.strokeWidth = 1.8f * u
            c.drawLine(x0 - u, bottom - 19 * u, right + u, bottom + u, line)
        }
    }

    /** Sparkline of the last GRAPH_POINTS pings; timeouts are thin red ticks. */
    private fun drawGraph(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, col: Int) {
        val pts = history.takeLast(GRAPH_POINTS)
        if (pts.isEmpty()) return
        val maxV = maxOf(100.0, pts.filterNotNull().maxOrNull() ?: 0.0)
        val step = (right - left) / (GRAPH_POINTS - 1)
        val off = GRAPH_POINTS - pts.size // newest sample sits at the right edge
        path.reset()
        var penDown = false
        val ticks = ArrayList<Float>()
        for ((i, v) in pts.withIndex()) {
            val x = left + (off + i) * step
            if (v == null) {
                ticks.add(x)
                penDown = false
                continue
            }
            val y = bottom - (min(v, maxV) / maxV).toFloat() * (bottom - top)
            if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
            penDown = true
        }
        if (outlined) {
            line.color = OUTLINE
            line.strokeWidth = 3.5f * u
            c.drawPath(path, line)
        }
        line.color = col
        line.strokeWidth = 1.6f * u
        c.drawPath(path, line)
        line.color = PingState.RED
        line.strokeWidth = 1.2f * u
        for (x in ticks) c.drawLine(x, top, x, bottom, line)
    }

    companion object {
        const val GRAPH_POINTS = 20

        /** Base size in dp (width, height) before the size multiplier. */
        fun baseDp(style: String): Pair<Float, Float> = when (style) {
            "dot" -> 24f to 24f
            "text" -> 52f to 24f
            "bars" -> 58f to 28f
            "graph" -> 104f to 30f
            "ring" -> 40f to 40f
            else -> 64f to 28f
        }

        fun sizePx(style: String, mult: Float, density: Float): Point {
            val (w, h) = baseDp(style)
            return Point(ceil(w * density * mult).toInt(), ceil(h * density * mult).toInt())
        }

        /** Size when scaled to an exact height (fit to status bar). */
        fun sizeForHeight(style: String, heightPx: Int): Point {
            val (w, h) = baseDp(style)
            return Point(ceil(w * heightPx / h).toInt(), heightPx)
        }

        private const val BG_DARK = 0xDB0C0C0C.toInt()   // #0C0C0C @ ~86%
        private const val BG_LIGHT = 0xEBFAFAFA.toInt()  // #FAFAFA @ ~92%
        private const val DARK_GREEN = 0xFF1E9E57.toInt()
        private const val DARK_YELLOW = 0xFFC99400.toInt()
        private const val OUTLINE = 0xE6000000.toInt()
        private const val DIM_ON_DARK = 0x55FFFFFF
        private const val DIM_ON_LIGHT = 0x40000000
    }
}
