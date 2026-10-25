package app.pingt

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** "Bubble settings": every change is saved at once and the service applies it live. */
class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var preview: BubbleView
    private lateinit var previewHolder: FrameLayout
    private lateinit var overlayHint: TextView
    private lateinit var opacityNote: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val syncers = mutableListOf<() -> Unit>() // re-read widgets from prefs
    private var awaitingOverlay = false

    // Which orientation's bubble settings this screen edits
    private var editLand = false
    private lateinit var sameAsPortrait: TextView

    // Preview demo: 23 → 120 → 210 → TIMEOUT → 25, every 1.5 s
    private val demo = listOf<Double?>(23.0, 120.0, 210.0, null, 25.0)
    private var demoIdx = 0
    private var demoValue: Double? = 23.0
    private val demoHistory = ArrayDeque<Double?>()
    private val tick = object : Runnable {
        override fun run() {
            demoValue = demo[demoIdx % demo.size]
            demoIdx++
            demoHistory.addLast(demoValue)
            while (demoHistory.size > BubbleView.GRAPH_POINTS) demoHistory.removeFirst()
            updatePreview()
            ui.postDelayed(this, 1500)
        }
    }

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        prefs = Prefs.get(this)
        editLand = BubbleGeometry.screen(this).let { it.x > it.y }
        listOf(24, 22, 27, 25, 23, 30, 26, 22, 24, 28, 25, 23, 26, 24, 22).forEach { demoHistory.addLast(it.toDouble()) }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(24))
        }
        scroll.addView(root)
        setContentView(scroll)
        build()
    }

    override fun onStart() {
        super.onStart()
        PingState.screenStarted()
        prefs.registerOnSharedPreferenceChangeListener(this)
        syncers.forEach { it() }
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingOverlay) {
            awaitingOverlay = false
            if (!Settings.canDrawOverlays(this)) {
                putB(Prefs.SHOW_BUBBLE, false)
                Toast.makeText(this, "\"Display over other apps\" not allowed. Bubble stays off.", Toast.LENGTH_LONG).show()
            }
        }
        refreshHints()
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        PingState.screenStopped()
        super.onStop()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        syncers.forEach { it() }
        refreshHints()
        updatePreview()
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        root.addView(TextView(this).apply {
            text = "←  Bubble settings"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(8), 0, px(12))
            setOnClickListener { finish() }
        })

        orientationRow()

        // Live preview
        val box = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF2B5BA8.toInt(), 0xFF3F8A8F.toInt(), 0xFF2F7D4F.toInt())
            ).apply { cornerRadius = 12 * dp }
        }
        box.addView(TextView(this).apply {
            text = "PREVIEW"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(px(10), px(8), 0, 0)
        })
        previewHolder = FrameLayout(this)
        preview = BubbleView(this)
        previewHolder.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(previewHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(96)))
        updatePreview()

        // Show bubble + overlay permission
        switchRow("Show floating bubble", null, Prefs.SHOW_BUBBLE, true) { on ->
            if (on && !Settings.canDrawOverlays(this)) openOverlaySettings()
        }
        overlayHint = TextView(this).apply {
            text = "Needs \"Display over other apps\". Tap to allow."
            setTextColor(PingState.YELLOW)
            textSize = 12f
            setPadding(0, 0, 0, px(8))
            setOnClickListener { openOverlaySettings() }
        }
        root.addView(overlayHint)

        divider()
        chipRow(
            "Style",
            listOf("Pill" to "pill", "Dot" to "dot", "Text" to "text", "Bars" to "bars", "Graph" to "graph", "Ring" to "ring"),
            { getS(Prefs.STYLE, "pill") },
            { putS(Prefs.STYLE, it) }
        )
        divider()
        chipRow(
            "Size",
            listOf("Small" to "small", "Medium" to "medium", "Large" to "large"),
            { getS(Prefs.SIZE, "medium") },
            { putS(Prefs.SIZE, it) }
        )
        divider()
        opacityRow()
        divider()
        chipRow(
            "Background",
            listOf("Dark" to "dark", "Light" to "light", "None" to "none"),
            { getS(Prefs.BACKGROUND, "dark") },
            { putS(Prefs.BACKGROUND, it) }
        )
        divider()
        switchRow("Click-through (game mode)", "Hindi haharang sa touch ng game", Prefs.CLICK_THROUGH, false)
        divider()
        switchRow("Show only when bad", "Nakatago habang green", Prefs.ONLY_WHEN_BAD, false)
        divider()
        switchRow("Blink on timeout", null, Prefs.BLINK_TIMEOUT, true)
        divider()
        positionSection()
        divider()
        root.addView(sectionLabel("FOR BOTH ORIENTATIONS"))
        switchRow("Hide while Ping -t is open", null, Prefs.HIDE_IN_APP, true)
        divider()
        chipRow(
            "Vibrate after timeouts in a row",
            listOf("Off" to "0", "1" to "1", "3" to "3", "5" to "5"),
            { prefs.getInt(Prefs.VIBRATE_AFTER, 3).toString() },
            { prefs.edit().putInt(Prefs.VIBRATE_AFTER, it.toInt()).apply() }
        )
        divider()
        thresholdRow()
        divider()
        chipRow(
            "Ping every",
            listOf("0.5s" to "500", "1s" to "1000", "2s" to "2000", "5s" to "5000"),
            { prefs.getInt(Prefs.INTERVAL_MS, 1000).toString() },
            { prefs.edit().putInt(Prefs.INTERVAL_MS, it.toInt()).apply() }
        )
        refreshHints()
    }

    // Read/write for the edited orientation; shared keys ignore the orientation
    private fun getB(k: String, def: Boolean) = Prefs.getBool(prefs, k, editLand, def)
    private fun getS(k: String, def: String) = Prefs.getStr(prefs, k, editLand, def)
    private fun getI(k: String, def: Int) = Prefs.getInt(prefs, k, editLand, def)
    private fun putB(k: String, v: Boolean) = prefs.edit().putBoolean(Prefs.key(k, editLand), v).apply()
    private fun putS(k: String, v: String) = prefs.edit().putString(Prefs.key(k, editLand), v).apply()
    private fun putI(k: String, v: Int) = prefs.edit().putInt(Prefs.key(k, editLand), v).apply()

    /** Portrait | Landscape: which orientation the bubble settings below apply to. */
    private fun orientationRow() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(10))
        }
        box.addView(title("Bubble settings for"))
        val (hs, strip) = chipStrip()
        val chips = listOf("Portrait" to false, "Landscape" to true).map { (label, land) ->
            chip(label).also { c ->
                c.setOnClickListener { setEditOrientation(land) }
                strip.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, px(8), px(8), 0)
                })
            } to land
        }
        sameAsPortrait = chip("Same as portrait").apply {
            styleChip(this, false)
            setOnClickListener {
                Prefs.clearLandscapeOverrides(prefs)
                Toast.makeText(this@SettingsActivity, "Landscape now uses the portrait settings", Toast.LENGTH_SHORT).show()
            }
        }
        strip.addView(sameAsPortrait, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(px(8), px(8), 0, 0)
        })
        box.addView(hs)
        val note = subtitle("")
        box.addView(note)
        root.addView(box)
        val sync = {
            chips.forEach { (c, land) -> styleChip(c, land == editLand) }
            sameAsPortrait.visibility =
                if (editLand && Prefs.hasLandscapeOverrides(prefs)) View.VISIBLE else View.GONE
            note.text = if (editLand) {
                "Landscape uses the portrait value of each setting until you change it here. Position is always per orientation."
            } else {
                "Also used in landscape for every setting not changed there."
            }
        }
        sync()
        syncers.add(sync)
    }

    private fun setEditOrientation(land: Boolean) {
        if (land == editLand) return
        editLand = land
        syncers.forEach { it() }
        refreshHints()
        updatePreview()
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t
        setTextColor(ACCENT)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
        setPadding(0, px(10), 0, 0)
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun subtitle(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFF888888.toInt())
        textSize = 12f
    }

    private fun divider() {
        root.addView(View(this).apply { setBackgroundColor(0xFF2A2A2A.toInt()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply {
                setMargins(0, px(4), 0, px(4))
            })
    }

    private fun switchRow(t: String, sub: String?, key: String, def: Boolean, onChange: ((Boolean) -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(t))
        sub?.let { texts.addView(subtitle(it)) }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFBBBBBB.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(ACCENT_DARK, 0xFF444444.toInt()))
            isChecked = getB(key, def)
            setOnCheckedChangeListener { _, on ->
                if (getB(key, def) != on) {
                    putB(key, on)
                    onChange?.invoke(on)
                }
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        root.addView(row)
        syncers.add { sw.isChecked = getB(key, def) }
    }

    private fun chip(label: String) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(px(12), px(6), px(12), px(6))
    }

    private fun styleChip(v: TextView, selected: Boolean) {
        v.setTextColor(if (selected) Color.WHITE else 0xFFC0C0C0.toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(if (selected) ACCENT_DARK else 0xFF1E1E1E.toInt())
            setStroke(px(1), if (selected) ACCENT_DARK else 0xFF444444.toInt())
        }
    }

    private fun chipStrip(): Pair<HorizontalScrollView, LinearLayout> {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        return hs to strip
    }

    private fun chipRow(t: String, options: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(10), 0, px(10))
        }
        box.addView(title(t))
        val (hs, strip) = chipStrip()
        val chips = options.map { (label, value) ->
            chip(label).also { c ->
                c.setOnClickListener { set(value) }
                strip.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, px(8), px(8), 0)
                })
            } to value
        }
        box.addView(hs)
        root.addView(box)
        val sync = { val cur = get(); chips.forEach { (c, v) -> styleChip(c, v == cur) } }
        sync()
        syncers.add(sync)
    }

    private fun opacityRow() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(10), 0, px(10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(title("Opacity"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            setTextColor(ACCENT)
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        head.addView(value)
        box.addView(head)
        val bar = SeekBar(this).apply {
            max = 16 // 20..100 in steps of 5
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF444444.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val pct = 20 + progress * 5
                    value.text = "$pct%"
                    if (fromUser) putI(Prefs.OPACITY, pct)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, px(8), 0, 0)
        })
        opacityNote = subtitle("Max 80% while click-through is on (Android rule)").apply {
            setTextColor(PingState.YELLOW)
        }
        box.addView(opacityNote)
        root.addView(box)
        val sync = {
            val pct = getI(Prefs.OPACITY, 90).coerceIn(20, 100)
            bar.progress = (pct - 20) / 5
            value.text = "$pct%"
        }
        sync()
        syncers.add(sync)
    }

    private fun positionSection() {
        root.addView(sectionLabel("POSITION"))
        chipRow(
            "Snap after drag",
            listOf("Off" to "off", "Left/Right edges" to "edges", "Nearest corner" to "corner"),
            { getS(Prefs.SNAP_MODE, "off") },
            { putS(Prefs.SNAP_MODE, it) }
        )
        switchRow("Lock position", null, Prefs.LOCK_POSITION, false)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(10), 0, px(10))
        }
        box.addView(title("Move bubble to"))
        box.addView(subtitle("For the orientation selected at the top"))
        fun presetRow(items: List<Pair<String, String>>, short: (String) -> String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { (id, label) ->
                val c = chip(short(label)).apply {
                    textSize = 12f
                    maxLines = 1
                    setPadding(px(4), px(7), px(4), px(7))
                }
                styleChip(c, false)
                c.setOnClickListener { setPreset(id, label) }
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, px(8), px(6), 0)
                })
            }
            box.addView(row)
        }
        BubbleGeometry.PRESETS.chunked(3).forEach { presetRow(it) { l -> l } }
        box.addView(subtitle("Status bar strip (very top, y = 0)").apply { setPadding(0, px(10), 0, 0) })
        presetRow(BubbleGeometry.STATUS_BAR_PRESETS) { l -> l.substringAfter(": ").replaceFirstChar { it.uppercase() } }
        box.addView(subtitle("Status bar icons draw over the bubble unless the game is fullscreen (Android rule).").apply {
            setPadding(0, px(8), 0, 0)
        })
        root.addView(box)

        switchRow("Fit to status bar height", "Sa status bar, kasing-taas ng status bar", Prefs.FIT_STATUS_BAR, false)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(6), 0, px(10))
        }
        buttons.addView(actionButton("Adjust position on screen", ACCENT_DARK) { startPositionTool() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f).apply { marginEnd = px(8) })
        buttons.addView(actionButton("Reset position", 0xFF333333.toInt()) {
            setPreset(BubbleGeometry.DEFAULT_PRESET, "Top-right")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(px(8), px(10), px(8), px(10))
        background = GradientDrawable().apply {
            cornerRadius = 8 * dp
            setColor(color)
        }
        setOnClickListener { onClick() }
    }

    private fun setPreset(id: String, label: String) {
        val cur = BubbleGeometry.screen(this)
        val land = editLand
        // Screen as it is in the edited orientation
        val scr = if ((cur.x > cur.y) == land) cur else Point(cur.y, cur.x)
        val f = BubbleGeometry.presetFractions(
            id, BubbleSettings.load(prefs, land), scr, BubbleGeometry.statusBarHeight(this), dp
        )
        Prefs.savePosition(prefs, land, f)
        Toast.makeText(this, "Bubble: $label (${if (land) "landscape" else "portrait"})", Toast.LENGTH_SHORT).show()
    }

    /** Shows the real bubble + control panel over the home screen or a game. */
    private fun startPositionTool() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        if (!PingState.running) {
            // The tool works on the live bubble, which only exists while pinging
            val host = prefs.getString(Prefs.LAST_HOST, "google.com") ?: "google.com"
            val start = Intent(this, PingService::class.java)
                .setAction(PingService.ACTION_START)
                .putExtra(PingService.EXTRA_HOST, host)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(start) else startService(start)
            Toast.makeText(this, "Pinging $host so the bubble is live", Toast.LENGTH_SHORT).show()
        }
        startService(Intent(this, PingService::class.java).setAction(PingService.ACTION_POSITION_START))
        moveTaskToBack(true)
    }

    private fun thresholdRow() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(10), 0, px(10))
        }
        box.addView(title("Thresholds"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(0xFFC0C0C0.toInt())
            textSize = 13f
        }
        fun input(v: Int, color: Int) = EditText(this).apply {
            setText(v.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(color)
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            backgroundTintList = ColorStateList.valueOf(0xFF555555.toInt())
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            minWidth = px(64)
        }
        val s = BubbleSettings.load(prefs)
        val green = input(s.greenMax, PingState.GREEN)
        val yellow = input(s.yellowMax, PingState.YELLOW)
        row.addView(label("Green <"))
        row.addView(green)
        row.addView(label("   Yellow <"))
        row.addView(yellow)
        row.addView(label("  ms"))
        box.addView(row)
        val err = subtitle("Need 1 ≤ green < yellow ≤ 2000").apply {
            setTextColor(PingState.RED)
            visibility = View.GONE
        }
        box.addView(err)
        root.addView(box)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val g = green.text.toString().toIntOrNull()
                val y = yellow.text.toString().toIntOrNull()
                if (g != null && y != null && Prefs.thresholdsValid(g, y)) {
                    err.visibility = View.GONE
                    prefs.edit().putInt(Prefs.GREEN_MAX, g).putInt(Prefs.YELLOW_MAX, y).apply()
                } else {
                    err.visibility = View.VISIBLE
                }
            }
        }
        green.addTextChangedListener(watcher)
        yellow.addTextChangedListener(watcher)
    }

    // ---------------------------------------------------------------- helpers

    private fun refreshHints() {
        if (!::overlayHint.isInitialized) return
        val wantBubble = getB(Prefs.SHOW_BUBBLE, true)
        overlayHint.visibility =
            if (wantBubble && !Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
        opacityNote.visibility =
            if (getB(Prefs.CLICK_THROUGH, false) && Build.VERSION.SDK_INT >= 31) View.VISIBLE else View.GONE
    }

    private fun openOverlaySettings() {
        awaitingOverlay = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            awaitingOverlay = false
            Toast.makeText(this, "Open Settings > Apps > Ping -t > Display over other apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        val s = BubbleSettings.load(prefs, editLand)
        val v = demoValue
        val kind = if (v == null) PingState.Kind.LOST else PingState.Kind.OK
        preview.setSettings(s)
        preview.setData(kind, v ?: 0.0, v == null, demoHistory.toList())
        preview.setBlinking(s.blinkTimeout && v == null)
        previewHolder.alpha = s.windowAlpha(this)
    }

    companion object {
        private const val BG = 0xFF0C0C0C.toInt()
        private const val ACCENT = 0xFF3DDC84.toInt()
        private const val ACCENT_DARK = 0xFF1E7D3A.toInt()
    }
}
