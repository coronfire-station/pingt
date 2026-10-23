package app.pingt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import java.util.Locale

/**
 * Foreground service that owns the ping loop, so pinging keeps going while the app
 * is minimized or the screen is off. Also owns the notification and the floating bubble.
 */
class PingService : Service(), PingState.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: BubbleSettings
    private lateinit var overlay: BubbleOverlay
    private var wakeLock: PowerManager.WakeLock? = null

    // Bumped on every start/stop; a loop thread exits as soon as it no longer matches.
    @Volatile private var generation = 0
    @Volatile private var intervalMs = 1000L
    @Volatile private var currentProc: Process? = null
    private var worker: Thread? = null

    // "Show only when bad": visible from the first non-green result until 3 greens in a row
    private var badShowing = false
    private var greenStreak = 0

    private val timeRe = Regex("""time[=<]\s*([\d.]+)\s*ms""")
    private val ttlRe = Regex("""ttl=(\d+)""", RegexOption.IGNORE_CASE)
    private val bytesRe = Regex("""^(\d+) bytes from""", RegexOption.MULTILINE)
    private val fromRe = Regex("""bytes from ([^\s:()]+)""")
    private val headerIpRe = Regex("""^PING\s+\S+\s+\(([^)]+)\)""", RegexOption.MULTILINE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        settings = BubbleSettings.load(prefs)
        intervalMs = settings.intervalMs.toLong()
        PingState.setThresholds(settings.greenMax, settings.yellowMax)
        overlay = BubbleOverlay(this, prefs)
        overlay.applySettings(settings)
        prefs.registerOnSharedPreferenceChangeListener(this)
        PingState.addListener(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_HOST)?.let { startPing(it) }
            ACTION_STOP -> stopPing()
            ACTION_TOGGLE_CLICK ->
                prefs.edit().putBoolean(Prefs.CLICK_THROUGH, !settings.clickThrough).apply()
            ACTION_TOGGLE_BUBBLE ->
                prefs.edit().putBoolean(Prefs.SHOW_BUBBLE, !settings.show).apply()
            null -> {
                // Android restarted us after killing the process: resume the last host.
                prefs.getString(Prefs.RUNNING_HOST, null)?.let { startPing(it) }
            }
        }
        if (!PingState.running) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        stopWorker()
        PingState.stop()
        releaseWakeLock()
        overlay.hide()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        PingState.removeListener(this)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- control

    private fun startPing(target: String) {
        if (PingState.running) {
            stopWorker()
            PingState.stop()
        }
        PingState.start(target)
        badShowing = false
        greenStreak = 0
        if (!goForeground()) {
            PingState.stop()
            return
        }
        prefs.edit().putString(Prefs.RUNNING_HOST, target).apply()
        acquireWakeLock()
        startWorker(target)
        updateBubble()
    }

    private fun stopPing() {
        stopWorker()
        PingState.stop()
        releaseWakeLock()
        overlay.hide()
        prefs.edit().remove(Prefs.RUNNING_HOST).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingT:ping").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------- worker

    private fun startWorker(target: String) {
        stopWorker()
        val gen = generation
        worker = Thread({ loop(target, gen) }, "ping-loop").also { it.start() }
    }

    private fun stopWorker() {
        generation++
        currentProc?.destroy()
        worker?.interrupt()
        worker = null
    }

    private fun loop(target: String, gen: Int) {
        while (gen == generation) {
            val started = SystemClock.elapsedRealtime()
            val result = try {
                pingOnce(target)
            } catch (e: Exception) {
                PingResult.Failure(e.message ?: e.javaClass.simpleName)
            }
            if (gen != generation) break
            main.post { if (gen == generation && PingState.running) onResult(result) }
            // Sleep only what is left of the interval
            val wait = intervalMs - (SystemClock.elapsedRealtime() - started)
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (ignored: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun pingOnce(target: String): PingResult {
        // One echo request, wait max 2 s for the reply.
        val proc = ProcessBuilder("ping", "-c", "1", "-W", "2", target)
            .redirectErrorStream(true)
            .start()
        currentProc = proc
        val out = try {
            proc.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try { proc.waitFor() } catch (ignored: InterruptedException) { }
            currentProc = null
        }

        val time = timeRe.find(out)?.groupValues?.get(1)?.toDoubleOrNull()
        val ip = fromRe.find(out)?.groupValues?.get(1)
            ?: headerIpRe.find(out)?.groupValues?.get(1)

        if (time != null && out.contains("bytes from")) {
            val ttl = ttlRe.find(out)?.groupValues?.get(1)?.toIntOrNull()
            val bytes = bytesRe.find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 64
            return PingResult.Reply(ip ?: target, time, ttl, bytes)
        }

        val lower = out.lowercase(Locale.US)
        return when {
            "unknown host" in lower || "name or service not known" in lower ||
                "bad address" in lower || "no address associated" in lower ||
                "temporary failure in name resolution" in lower -> PingResult.NoHost
            "network is unreachable" in lower -> PingResult.Failure("General failure. (network is unreachable)")
            "destination host unreachable" in lower -> PingResult.Failure("Destination host unreachable.")
            "permission denied" in lower || "operation not permitted" in lower ->
                PingResult.Failure("Ping not permitted on this device: ${out.trim().lines().firstOrNull() ?: ""}")
            else -> PingResult.Timeout(ip)
        }
    }

    // ---------------------------------------------------------------- results

    private fun onResult(r: PingResult) {
        PingState.handleResult(r)
        when {
            PingState.lastKind == PingState.Kind.LOST -> {
                greenStreak = 0
                badShowing = true
                val n = settings.vibrateAfter
                if (n > 0 && PingState.consecutiveLost == n) vibrate()
            }
            PingState.lastMs < PingState.greenMax -> {
                if (++greenStreak >= 3) badShowing = false
            }
            else -> {
                greenStreak = 0
                badShowing = true
            }
        }
        updateNotification()
        updateBubble()
    }

    private fun updateBubble() {
        val want = PingState.running && settings.show && Settings.canDrawOverlays(this) &&
            (!settings.hideInApp || !PingState.appVisible) &&
            (!settings.onlyWhenBad || badShowing)
        if (want) {
            overlay.show()
            overlay.update(settings)
        } else {
            overlay.hide()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 120, 100, 120) // short double pulse
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            v.vibrate(pattern, -1)
        }
    }

    // ---------------------------------------------------------------- live settings

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        settings = BubbleSettings.load(p)
        intervalMs = settings.intervalMs.toLong()
        PingState.setThresholds(settings.greenMax, settings.yellowMax)
        if (key == Prefs.ONLY_WHEN_BAD) {
            badShowing = PingState.lastKind == PingState.Kind.LOST ||
                (PingState.lastKind == PingState.Kind.OK && PingState.lastMs >= PingState.greenMax)
            greenStreak = 0
        }
        overlay.applySettings(settings)
        if (Prefs.isPosKey(key)) overlay.reloadPosition()
        if (PingState.running) {
            updateNotification()
            updateBubble()
        }
    }

    override fun onAppVisibilityChanged() {
        updateBubble()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.onScreenChanged()
        // Some devices report the new display size a moment after the config change
        main.postDelayed({ overlay.onScreenChanged() }, 300)
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL_ID, "Ping monitor", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Live ping while Ping -t runs in the background"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun updateNotification() {
        if (!PingState.running) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val text = "${PingState.lastText} · ${PingState.status} · loss ${PingState.fmtPct(PingState.lossPct())}"
        b.setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle("Ping -t: ${PingState.host}")
            .setContentText(text)
            .setColor(PingState.GREEN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .addAction(action("Stop", ACTION_STOP, 1))
            .addAction(action(if (settings.clickThrough) "Click-through: ON" else "Click-through: OFF", ACTION_TOGGLE_CLICK, 2))
            .addAction(action(if (settings.show) "Hide bubble" else "Show bubble", ACTION_TOGGLE_BUBBLE, 3))
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun action(title: String, act: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, PingService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_ping), title, pi).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, launchIntent(this),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val ACTION_START = "app.pingt.START"
        const val ACTION_STOP = "app.pingt.STOP"
        const val ACTION_TOGGLE_CLICK = "app.pingt.TOGGLE_CLICK"
        const val ACTION_TOGGLE_BUBBLE = "app.pingt.TOGGLE_BUBBLE"
        const val EXTRA_HOST = "host"

        private const val CHANNEL_ID = "ping"
        private const val NOTIF_ID = 1

        /** Brings the existing app task to the front, like tapping the launcher icon. */
        fun launchIntent(ctx: Context): Intent =
            Intent(ctx, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
