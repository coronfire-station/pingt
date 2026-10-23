package app.pingt

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

sealed class PingResult {
    data class Reply(val ip: String, val ms: Double, val ttl: Int?, val bytes: Int) : PingResult()
    data class Timeout(val ip: String?) : PingResult()
    object NoHost : PingResult()
    data class Failure(val message: String) : PingResult()
}

/**
 * The live ping session: log, stats, verdict, history. Written only by PingService,
 * read by MainActivity and the bubble. Main thread only.
 * Listeners let open screens follow along without binding to the service.
 */
object PingState {

    interface Listener {
        fun onStateChanged() {}
        fun onLogChanged() {}
        fun onAppVisibilityChanged() {}
    }

    enum class Kind { NONE, OK, LOST }

    const val MAX_LOG_LINES = 500
    const val RECENT_WINDOW = 30
    const val HISTORY_SIZE = 60

    const val GREEN = 0xFF3DDC84.toInt()
    const val YELLOW = 0xFFFFC107.toInt()
    const val RED = 0xFFF44336.toInt()
    const val GRAY = 0xFF888888.toInt()

    private val listeners = mutableListOf<Listener>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    var running = false
        private set
    var host = ""
        private set
    val logLines = ArrayDeque<String>()

    // Stats
    private var sent = 0
    private var received = 0
    private var minMs = Double.MAX_VALUE
    private var maxMs = 0.0
    private var sumMs = 0.0
    private var prevMs: Double? = null
    private var jitterSum = 0.0
    private var jitterCount = 0
    private val recent = ArrayDeque<Double?>() // last RECENT_WINDOW results, null = lost

    /** Last HISTORY_SIZE results for the bubble graph, null = lost. */
    val history = ArrayDeque<Double?>()
    var consecutiveLost = 0
        private set

    var lastKind = Kind.NONE
        private set
    var lastMs = 0.0
        private set
    var lastText = "-- ms"
        private set
    var status = "IDLE"
        private set
    var statusColor = GRAY
        private set

    var greenMax = 80
        private set
    var yellowMax = 150
        private set

    private var visibleScreens = 0
    val appVisible get() = visibleScreens > 0

    private var logDirty = false

    // ---------------------------------------------------------------- listeners

    fun addListener(l: Listener) {
        if (l !in listeners) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun publish() {
        val ls = listeners.toList()
        if (logDirty) {
            logDirty = false
            ls.forEach { it.onLogChanged() }
        }
        ls.forEach { it.onStateChanged() }
    }

    fun screenStarted() {
        visibleScreens++
        if (visibleScreens == 1) listeners.toList().forEach { it.onAppVisibilityChanged() }
    }

    fun screenStopped() {
        visibleScreens = maxOf(0, visibleScreens - 1)
        if (visibleScreens == 0) listeners.toList().forEach { it.onAppVisibilityChanged() }
    }

    // ---------------------------------------------------------------- session

    fun start(target: String) {
        resetStats()
        host = target
        running = true
        if (logLines.isNotEmpty()) appendLog("")
        appendLog("Pinging $target -t   (started ${timeFmt.format(Date())})")
        appendLog("")
        setStatus("WAIT", GRAY)
        publish()
    }

    fun stop() {
        if (!running) return
        running = false
        printSummary()
        setStatus("STOPPED", GRAY)
        publish()
    }

    fun clearLog() {
        logLines.clear()
        logDirty = true
        publish()
    }

    fun setThresholds(green: Int, yellow: Int) {
        if (green == greenMax && yellow == yellowMax) return
        greenMax = green
        yellowMax = yellow
        if (running) updateVerdict()
        publish()
    }

    // ---------------------------------------------------------------- results

    fun handleResult(r: PingResult) {
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
                consecutiveLost = 0

                val t = if (ms < 1.0) "time<1ms" else "time=${ms.roundToLong()}ms"
                val ttl = r.ttl?.let { " TTL=$it" } ?: ""
                appendLog("[$ts] Reply from ${r.ip}: bytes=${r.bytes} $t$ttl")

                lastKind = Kind.OK
                lastMs = ms
                lastText = if (ms < 1.0) "<1 ms" else "${ms.roundToLong()} ms"
            }
            is PingResult.Timeout -> {
                lost("[$ts] Request timed out.")
                lastText = "TIMEOUT"
            }
            is PingResult.NoHost -> {
                lost("[$ts] Ping request could not find host $host.")
                lastText = "NO DNS"
            }
            is PingResult.Failure -> {
                lost("[$ts] ${r.message}")
                lastText = "FAIL"
            }
        }
        updateVerdict()
        publish()
    }

    private fun lost(line: String) {
        prevMs = null
        pushRecent(null)
        consecutiveLost++
        appendLog(line)
        lastKind = Kind.LOST
    }

    private fun pushRecent(v: Double?) {
        recent.addLast(v)
        while (recent.size > RECENT_WINDOW) recent.removeFirst()
        history.addLast(v)
        while (history.size > HISTORY_SIZE) history.removeFirst()
    }

    fun colorFor(ms: Double) = when {
        ms < greenMax -> GREEN
        ms < yellowMax -> YELLOW
        else -> RED
    }

    fun lastColor() = when (lastKind) {
        Kind.NONE -> GRAY
        Kind.OK -> colorFor(lastMs)
        Kind.LOST -> RED
    }

    /** Bubble gets a red border: bad verdict or the last ping failed. */
    val alert get() = status == "UNSTABLE" || status == "DOWN" || lastKind == Kind.LOST

    fun lossPct() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent

    fun statsText(): String {
        val lostCount = sent - received
        val avg = if (received == 0) null else sumMs / received
        val jitter = if (jitterCount == 0) null else jitterSum / jitterCount
        return buildString {
            append("Sent $sent · Recv $received · Lost $lostCount (${fmtPct(lossPct())})\n")
            append("Min ${fmtMs(if (received == 0) null else minMs)} · ")
            append("Avg ${fmtMs(avg)} · Max ${fmtMs(if (received == 0) null else maxMs)} · ")
            append("Jitter ${fmtMs(jitter)}")
        }
    }

    // Stability verdict over the last RECENT_WINDOW pings
    private fun updateVerdict() {
        val n = recent.size
        val replies = recent.filterNotNull()
        val recentLoss = if (n == 0) 0.0 else (n - replies.size) * 100.0 / n
        val recentJitter = if (replies.size < 2) 0.0
            else replies.zipWithNext { a, b -> abs(b - a) }.average()
        val recentAvg = if (replies.isEmpty()) 0.0 else replies.average()

        when {
            n < 5 -> setStatus("WAIT", GRAY)
            replies.isEmpty() -> setStatus("DOWN", RED)
            recentLoss == 0.0 && recentJitter < 20 && recentAvg < yellowMax -> setStatus("STABLE", GREEN)
            recentLoss <= 5.0 && recentJitter < 50 -> setStatus("FAIR", YELLOW)
            else -> setStatus("UNSTABLE", RED)
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
        history.clear()
        consecutiveLost = 0
        lastKind = Kind.NONE
        lastMs = 0.0
        lastText = "-- ms"
    }

    private fun setStatus(text: String, color: Int) {
        status = text
        statusColor = color
    }

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        logDirty = true
    }

    private fun fmtMs(v: Double?) = if (v == null) "--" else "${v.roundToLong()}ms"

    fun fmtPct(v: Double) =
        if (v == 0.0 || v >= 10) "${v.roundToLong()}%" else String.format(Locale.US, "%.1f%%", v)
}
