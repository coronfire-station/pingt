package app.pingt

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Continuous ping, like `ping host -t` in Windows cmd.
 * The pinging itself runs in PingService; this screen shows PingState and
 * sends Start/Stop to the service.
 */
class MainActivity : Activity(), PingState.Listener {

    private lateinit var hostInput: EditText
    private lateinit var startBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var keepAwake: CheckBox
    private lateinit var lastMs: TextView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var prefs: SharedPreferences

    private var pendingHost: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0C0C0C")
        window.navigationBarColor = Color.parseColor("#0C0C0C")
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)

        hostInput = findViewById(R.id.hostInput)
        startBtn = findViewById(R.id.startBtn)
        clearBtn = findViewById(R.id.clearBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        keepAwake = findViewById(R.id.keepAwake)
        lastMs = findViewById(R.id.lastMs)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        logView = findViewById(R.id.logView)
        scroll = findViewById(R.id.scroll)

        hostInput.setText(prefs.getString(Prefs.LAST_HOST, "google.com"))

        startBtn.setOnClickListener { if (PingState.running) stopPing() else startPing() }
        clearBtn.setOnClickListener { PingState.clearLog() }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        keepAwake.setOnCheckedChangeListener { _, _ -> applyKeepAwake() }
        hostInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                if (!PingState.running) startPing()
                true
            } else false
        }
        listOf(R.id.presetGoogle, R.id.preset8888, R.id.preset1111).forEach { id ->
            val chip = findViewById<TextView>(id)
            chip.setOnClickListener {
                if (!PingState.running) {
                    hostInput.setText(chip.text)
                    hostInput.setSelection(hostInput.text.length)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PingState.screenStarted()
        PingState.addListener(this)
        val s = BubbleSettings.load(prefs)
        PingState.setThresholds(s.greenMax, s.yellowMax)
        renderLog()
        renderState()
    }

    override fun onStop() {
        PingState.removeListener(this)
        PingState.screenStopped()
        super.onStop()
    }

    // ---------------------------------------------------------------- control

    private fun startPing() {
        val target = hostInput.text.toString().trim()
            .removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (target.isEmpty()) {
            hostInput.error = "Enter a host"
            return
        }
        hideKeyboard()
        prefs.edit().putString(Prefs.LAST_HOST, target).apply()

        // Android 13+: ask for notifications the first time. Pinging starts either way.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(Prefs.NOTIF_ASKED, false)
        ) {
            prefs.edit().putBoolean(Prefs.NOTIF_ASKED, true).apply()
            pendingHost = target
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        launchService(target)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            pendingHost?.let { launchService(it) }
            pendingHost = null
        }
    }

    private fun launchService(target: String) {
        val i = Intent(this, PingService::class.java)
            .setAction(PingService.ACTION_START)
            .putExtra(PingService.EXTRA_HOST, target)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        maybeAskOverlay()
    }

    private fun stopPing() {
        startService(Intent(this, PingService::class.java).setAction(PingService.ACTION_STOP))
    }

    private fun maybeAskOverlay() {
        val wanted = Prefs.getBool(prefs, Prefs.SHOW_BUBBLE, false, true) ||
            Prefs.getBool(prefs, Prefs.SHOW_BUBBLE, true, true)
        if (!wanted || Settings.canDrawOverlays(this)) return
        if (!prefs.getBoolean(Prefs.OVERLAY_ASKED, false)) {
            prefs.edit().putBoolean(Prefs.OVERLAY_ASKED, true).apply()
            Toast.makeText(this, "Allow \"Display over other apps\" to see the floating bubble", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (ignored: Exception) {
            }
        } else {
            Toast.makeText(this, "Bubble off: allow \"Display over other apps\" in Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyKeepAwake() {
        if (PingState.running && keepAwake.isChecked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- state

    override fun onStateChanged() = renderState()

    override fun onLogChanged() = renderLog()

    private fun renderState() {
        val running = PingState.running
        lastMs.text = PingState.lastText
        lastMs.setTextColor(PingState.lastColor())
        statusText.text = PingState.status
        statusText.setTextColor(PingState.statusColor)
        statsText.text = PingState.statsText()

        hostInput.isEnabled = !running
        if (running && hostInput.text.toString() != PingState.host) hostInput.setText(PingState.host)
        startBtn.text = if (running) "Stop" else "Start"
        startBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (running) "#B3261E" else "#1E7D3A")
        )
        applyKeepAwake()
    }

    private fun renderLog() {
        logView.text = PingState.logLines.joinToString("\n")
        scroll.post { scroll.scrollTo(0, logView.bottom) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(hostInput.windowToken, 0)
        hostInput.clearFocus()
    }

    companion object {
        private const val REQ_NOTIF = 1
    }
}
