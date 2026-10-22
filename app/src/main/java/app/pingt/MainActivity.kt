package app.pingt

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Continuous ping, like `ping host -t` in Windows cmd.
 * Runs the phone's own /system/bin/ping once per second (real ICMP),
 * prints cmd-style lines, and keeps running stats.
 */
class MainActivity : Activity() {

    private sealed class PingResult {
        data class Reply(val ip: String, val ms: Double, val ttl: Int?, val bytes: Int) : PingResult()
        data class Timeout(val ip: String?) : PingResult()
        object NoHost : PingResult()
        data class Failure(val message: String) : PingResult()
    }

    private lateinit var hostInput: EditText
    private lateinit var startBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var keepAwake: CheckBox
    private lateinit var lastMs: TextView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val ui = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile private var running = false
    @Volatile private var currentProc: Process? = null
    private var worker: Thread? = null

    // Stats (touched only on the UI thread)
    private var host = ""
    private var sent = 0
    private var received = 0
    private var minMs = Double.MAX_VALUE
    private var maxMs = 0.0
    private var sumMs = 0.0
    private var prevMs: Double? = null
    private var jitterSum = 0.0
    private var jitterCount = 0
    private val recent = ArrayDeque<Double?>() // last N results, null = lost

    private val timeRe = Regex("""time[=<]\s*([\d.]+)\s*ms""")
    private val ttlRe = Regex("""ttl=(\d+)""", RegexOption.IGNORE_CASE)
    private val bytesRe = Regex("""^(\d+) bytes from""", RegexOption.MULTILINE)
    private val fromRe = Regex("""bytes from ([^\s:()]+)""")
    private val headerIpRe = Regex("""^PING\s+\S+\s+\(([^)]+)\)""", RegexOption.MULTILINE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0C0C0C")
        window.navigationBarColor = Color.parseColor("#0C0C0C")
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.hostInput)
        startBtn = findViewById(R.id.startBtn)
        clearBtn = findViewById(R.id.clearBtn)
        keepAwake = findViewById(R.id.keepAwake)
        lastMs = findViewById(R.id.lastMs)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        logView = findViewById(R.id.logView)
        scroll = findViewById(R.id.scroll)

        startBtn.setOnClickListener { if (running) stopPing() else startPing() }
        clearBtn.setOnClickListener {
            logLines.clear()
            logView.text = ""
        }
        keepAwake.setOnCheckedChangeListener { _, _ -> applyKeepAwake() }
        hostInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                if (!running) startPing()
                true
            } else false
        }
    }

    override fun onDestroy() {
        stopWorker()
        super.onDestroy()
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
        resetStats()
        host = target
        running = true
        hostInput.isEnabled = false
        startBtn.text = "Stop"
        startBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B3261E"))
        applyKeepAwake()

        if (logLines.isNotEmpty()) appendLog("")
        appendLog("Pinging $target -t   (started ${timeFmt.format(Date())})")
        appendLog("")
        setStatus("WAIT", "#888888")

        worker = Thread({ loop(target) }, "ping-loop").also { it.start() }
    }

    private fun stopPing() {
        stopWorker()
        hostInput.isEnabled = true
        startBtn.text = "Start"
        startBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E7D3A"))
        applyKeepAwake()
        printSummary()
        setStatus("STOPPED", "#888888")
    }

    private fun stopWorker() {
        running = false
        currentProc?.destroy()
        worker?.interrupt()
        worker = null
    }

    private fun applyKeepAwake() {
        if (running && keepAwake.isChecked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- worker

    private fun loop(target: String) {
        while (running) {
            val started = System.currentTimeMillis()
            val result = try {
                pingOnce(target)
            } catch (e: Exception) {
                PingResult.Failure(e.message ?: e.javaClass.simpleName)
            }
            if (!running) break
            ui.post { if (running) handleResult(result) }
            val wait = 1000L - (System.currentTimeMillis() - started)
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

    private fun handleResult(r: PingResult) {
        sent++
        val ts = timeFmt.format(Date())
        when (r) {
            is PingResult.Reply -> {
                received++
                val ms = r.ms
                minMs = minOf(minMs, ms)
                maxMs = maxOf(maxMs, ms)
                sumMs += ms
                prevMs?.let { jitterSum += abs(ms - it); jitterCount++ }
                prevMs = ms
                pushRecent(ms)

                val t = if (ms < 1.0) "time<1ms" else "time=${ms.roundToLong()}ms"
                val ttl = r.ttl?.let { " TTL=$it" } ?: ""
                appendLog("[$ts] Reply from ${r.ip}: bytes=${r.bytes} $t$ttl")

                lastMs.text = if (ms < 1.0) "<1 ms" else "${ms.roundToLong()} ms"
                lastMs.setTextColor(Color.parseColor(colorFor(ms)))
            }
            is PingResult.Timeout -> {
                lost("[$ts] Request timed out.")
                lastMs.text = "TIMEOUT"
            }
            is PingResult.NoHost -> {
                lost("[$ts] Ping request could not find host $host.")
                lastMs.text = "NO DNS"
            }
            is PingResult.Failure -> {
                lost("[$ts] ${r.message}")
                lastMs.text = "FAIL"
            }
        }
        updateStats()
    }

    private fun lost(line: String) {
        prevMs = null
        pushRecent(null)
        appendLog(line)
        lastMs.setTextColor(Color.parseColor("#F44336"))
    }

    private fun pushRecent(v: Double?) {
        recent.addLast(v)
        while (recent.size > RECENT_WINDOW) recent.removeFirst()
    }

    private fun colorFor(ms: Double) = when {
        ms < 80 -> "#3DDC84"
        ms < 150 -> "#FFC107"
        else -> "#F44336"
    }

    private fun updateStats() {
        val lostCount = sent - received
        val lossPct = if (sent == 0) 0.0 else lostCount * 100.0 / sent
        val avg = if (received == 0) null else sumMs / received
        val jitter = if (jitterCount == 0) null else jitterSum / jitterCount

        statsText.text = buildString {
            append("Sent $sent · Recv $received · Lost $lostCount (${fmtPct(lossPct)})\n")
            append("Min ${fmtMs(if (received == 0) null else minMs)} · ")
            append("Avg ${fmtMs(avg)} · Max ${fmtMs(if (received == 0) null else maxMs)} · ")
            append("Jitter ${fmtMs(jitter)}")
        }

        // Stability verdict over the last RECENT_WINDOW pings
        val n = recent.size
        val replies = recent.filterNotNull()
        val recentLoss = if (n == 0) 0.0 else (n - replies.size) * 100.0 / n
        val recentJitter = if (replies.size < 2) 0.0
            else replies.zipWithNext { a, b -> abs(b - a) }.average()
        val recentAvg = if (replies.isEmpty()) 0.0 else replies.average()

        when {
            n < 5 -> setStatus("WAIT", "#888888")
            replies.isEmpty() -> setStatus("DOWN", "#F44336")
            recentLoss == 0.0 && recentJitter < 20 && recentAvg < 150 -> setStatus("STABLE", "#3DDC84")
            recentLoss <= 5.0 && recentJitter < 50 -> setStatus("FAIR", "#FFC107")
            else -> setStatus("UNSTABLE", "#F44336")
        }
    }

    private fun printSummary() {
        if (sent == 0) return
        val lostCount = sent - received
        val lossPct = (lostCount * 100.0 / sent).roundToLong()
        appendLog("")
        appendLog("Ping statistics for $host:")
        appendLog("    Packets: Sent = $sent, Received = $received, Lost = $lostCount ($lossPct% loss),")
        if (received > 0) {
            appendLog("Approximate round trip times in milli-seconds:")
            appendLog(
                "    Minimum = ${minMs.roundToLong()}ms, Maximum = ${maxMs.roundToLong()}ms, " +
                    "Average = ${(sumMs / received).roundToLong()}ms"
            )
        }
        appendLog("Stopped at ${timeFmt.format(Date())}")
    }

    private fun resetStats() {
        sent = 0
        received = 0
        minMs = Double.MAX_VALUE
        maxMs = 0.0
        sumMs = 0.0
        prevMs = null
        jitterSum = 0.0
        jitterCount = 0
        recent.clear()
        lastMs.text = "-- ms"
        lastMs.setTextColor(Color.parseColor("#888888"))
        updateStats()
    }

    // ---------------------------------------------------------------- ui helpers

    private fun setStatus(text: String, color: String) {
        statusText.text = text
        statusText.setTextColor(Color.parseColor(color))
    }

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
        scroll.post { scroll.scrollTo(0, logView.bottom) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(hostInput.windowToken, 0)
        hostInput.clearFocus()
    }

    private fun fmtMs(v: Double?) = if (v == null) "--" else "${v.roundToLong()}ms"

    private fun fmtPct(v: Double) =
        if (v == 0.0 || v >= 10) "${v.roundToLong()}%" else String.format(Locale.US, "%.1f%%", v)

    companion object {
        private const val MAX_LOG_LINES = 500
        private const val RECENT_WINDOW = 30
    }
}
