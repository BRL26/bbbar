package dev.brl.nowbar.module

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * One-shot root commands. Magisk's `su -c` is how the keyguard watcher already runs, and the
 * status bar switches use the same door: `settings put global` is a write the app cannot make
 * itself, but SystemUI can read the result straight from [android.provider.Settings.Global].
 */
object RootShell {

    /** Runs [command] as root and returns its combined output, or null when root is unavailable. */
    fun run(command: String): String? {
        var process: Process? = null
        var reader: BufferedReader? = null
        return try {
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            reader = BufferedReader(InputStreamReader(process.inputStream))
            val out = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                out.append(line).append('\n')
            }
            process.waitFor()
            val text = out.toString().trim()
            Log.i("root: $command -> ${if (text.isEmpty()) "(no output)" else text}")
            text
        } catch (t: Throwable) {
            Log.w("root: $command failed", t)
            null
        } finally {
            try {
                reader?.close()
            } catch (t: Throwable) {
            }
            try {
                process?.destroy()
            } catch (t: Throwable) {
            }
        }
    }

    /** Reads a Settings.Global int without needing the settings permission. */
    fun globalInt(command: String): Int? = run(command)?.trim()?.toIntOrNull()
}
