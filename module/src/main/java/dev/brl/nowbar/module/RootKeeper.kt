package dev.brl.nowbar.module

/**
 * Root keeper for the accessibility service.
 *
 * The alarm in [NowBarWatchdog] cannot be relied on: an inexact repeating alarm sits in a batching
 * window and can fire minutes late, which is exactly when nothing is drawing the pill. With root
 * there is a much better tool - a small shell loop that outlives the app process and keeps our own
 * component in `enabled_accessibility_services`, restoring it within seconds of a drop.
 *
 * It obeys the same rule as the in-app watchdog: only a drop it did not cause is repaired. The loop
 * leaves the service alone when the last shutdown was a graceful one (the owner switched the
 * accessibility service off), and when the master switch is off.
 */
object RootKeeper {

    private const val SCRIPT = "/data/local/tmp/bbbar-watchdog.sh"
    private const val PID_FILE = "/data/local/tmp/bbbar-watchdog.pid"

    private val body: String = """
        #!/system/bin/sh
        # bbbar root keeper: keeps the accessibility service enabled, outliving the app process.
        PREFS=/data/user/0/dev.brl.nowbar.module/shared_prefs/tuning.xml
        SVC=dev.brl.nowbar.module/dev.brl.nowbar.module.NowBarA11yService
        exec 2> /data/local/tmp/bbbar-watchdog.err
        set -x
        if [ -f $PID_FILE ]; then
          OLD=`cat $PID_FILE`
          if [ -n "${'$'}OLD" ] && kill -0 "${'$'}OLD" 2>/dev/null; then
            exit 0
          fi
        fi
        echo ${'$'}${'$'} > $PID_FILE
        while true; do
          echo alive > /data/local/tmp/bbbar-watchdog.alive
          if [ ! -f "${'$'}PREFS" ]; then
            # Uninstalled: nothing left to keep alive.
            exit 0
          fi
          if ! grep -q 'name="enabled" value="false"' "${'$'}PREFS"; then
            if ! grep -q 'name="wasConnected" value="false"' "${'$'}PREFS"; then
              if ! settings get secure enabled_accessibility_services | grep -q NowBarA11yService; then
                CUR=`settings get secure enabled_accessibility_services`
                if [ "${'$'}CUR" = "null" ] || [ -z "${'$'}CUR" ]; then
                  settings put secure enabled_accessibility_services "${'$'}SVC"
                else
                  settings put secure enabled_accessibility_services "${'$'}CUR:${'$'}SVC"
                fi
                settings put secure accessibility_enabled 1
                log -t NowBar "root keeper: restored the accessibility service"
              fi
            fi
          fi
          sleep 20
        done
    """.trimIndent()

    /** Off the main thread: starting the loop spawns a root shell, which can take a moment. */
    fun startAsync() {
        try {
            Thread { start() }.start()
        } catch (t: Throwable) {
            Log.w("root keeper thread failed", t)
        }
    }

    /** Writes the script and starts it if it is not already running. Safe to call repeatedly. */
    fun start() {
        try {
            // Written beside the live script and moved into place: a running loop keeps reading the
            // old file, so rewriting it in place could feed it half a script.
            val wrote = RootShell.run(
                "cat > $SCRIPT.new <<'BBEOF'\n$body\nBBEOF\nchmod 755 $SCRIPT.new && mv $SCRIPT.new $SCRIPT"
            )
            RootShell.run("nohup setsid sh $SCRIPT </dev/null >/dev/null 2>&1 &")
            Log.i("root keeper: requested${if (wrote == null) " (root unavailable)" else ""}")
        } catch (t: Throwable) {
            Log.w("root keeper start failed", t)
        }
    }
}
