package dev.brl.nowbar.module

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Instant lock/unlock detection, using root.
 *
 * As an ordinary app there is no prompt keyguard callback on this build
 * (`addKeyguardLockedStateListener` throws), and `ACTION_USER_PRESENT` can be queued behind other
 * broadcasts, so the only thing that fires *at* the moment of the unlock is the system log.
 * Root can read it:
 *
 *     su -c "logcat -b main -b events -v brief -s KeyguardViewMediator …"
 *
 * Every matching line is only a wakeup: whether the phone is really locked is still decided by
 * `KeyguardManager`, so an unexpected, renamed or extra log line can never move the pill by itself.
 * Without root the stream fails, this object says so, and the poll in [NowBarA11yService] stays
 * the fallback.
 */
object RootKeyguardWatcher {

    /** Retry delay after the stream dies, doubled per consecutive failure up to the cap. */
    private const val RETRY_MS = 1500L
    private const val RETRY_MAX_MS = 30_000L

    /** Tags SystemUI / WindowManager print on every keyguard state change. */
    private val TAGS = arrayOf(
        "KeyguardViewMediator",
        "KeyguardServiceDelegate",
        "KeyguardStateMonitor",
        "StatusBarStateControllerImpl",
        "NotificationShadeWindowController"
    )

    @Volatile
    private var running = false

    @Volatile
    private var process: Process? = null

    private var thread: Thread? = null
    private var onLine: ((String) -> Unit)? = null
    private var onStatus: ((String) -> Unit)? = null

    /** True once [start] has been called and the stream has not been stopped. */
    val isRunning: Boolean get() = running

    /**
     * True while the root log stream is actually open. [isRunning] only says the watcher is
     * wanted: between a failed stream and its retry it stays true, which is why the settings row
     * used to claim "streaming" when it was doing nothing.
     */
    val isStreaming: Boolean get() = running && process != null

    /** Last thing the watcher said, for the status row. */
    @Volatile
    var lastMessage: String = ""
        private set

    /** Starts the watcher once; later calls are ignored. */
    fun start(onLine: (String) -> Unit, onStatus: (String) -> Unit = {}) {
        if (running) return
        this.onLine = onLine
        this.onStatus = onStatus
        running = true
        val t = Thread({ pump() }, "nowbar-root-keyguard")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        try {
            process?.destroy()
        } catch (t: Throwable) {
        }
        process = null
        thread = null
    }

    private fun pump() {
        val cmd = "logcat -b main -b events -v brief -s " + TAGS.joinToString(" ")
        var retry = RETRY_MS
        while (running) {
            var reader: BufferedReader? = null
            var lines = 0
            try {
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                process = p
                status("root keyguard watcher started (su -c logcat ${TAGS.size} tags)")
                reader = BufferedReader(InputStreamReader(p.inputStream))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    val tag = tagOf(line) ?: continue
                    if (!TAGS.contains(tag)) continue
                    lines++
                    retry = RETRY_MS
                    onLine?.invoke(line)
                }
                status("root keyguard stream ended after $lines lines")
            } catch (t: Throwable) {
                status("root keyguard watcher failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                try {
                    reader?.close()
                } catch (t: Throwable) {
                }
                try {
                    process?.destroy()
                } catch (t: Throwable) {
                }
                process = null
            }
            if (!running) return
            try {
                Thread.sleep(retry)
            } catch (t: InterruptedException) {
                return
            }
            if (lines == 0) retry = (retry * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    /** `brief` format is `I/Tag   ( 1234): message`; anything else (su's own output) is dropped. */
    private fun tagOf(line: String): String? {
        val slash = line.indexOf('/')
        if (slash < 0 || slash > 2) return null
        val paren = line.indexOf('(', slash)
        if (paren <= slash + 1) return null
        return line.substring(slash + 1, paren).trim()
    }

    private fun status(msg: String) {
        try {
            lastMessage = msg
            Log.i(msg)
            onStatus?.invoke(msg)
        } catch (t: Throwable) {
        }
    }
}
