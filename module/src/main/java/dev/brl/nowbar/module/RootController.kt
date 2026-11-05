package dev.brl.nowbar.module

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * App-side control of the root overlay daemon.
 *
 * Writes the tuning values to a JSON file the daemon polls, then starts the daemon with
 * `su -c app_process …`. Magisk asks the user once for root.
 */
object RootController {

    @Volatile
    var isRunning = false
        private set

    private var process: Process? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun configFile(context: Context): File = File(context.filesDir, "tuning.json")

    /** Writes current values where the root daemon can read them. */
    fun writeConfig(context: Context) {
        try {
            val t = Tuning.current(context)
            val json = JSONObject().apply {
                put("widthDp", t.widthDp)
                put("heightDp", t.heightDp)
                put("textDp", t.textDp)
                put("radiusDp", t.radiusDp)
                put("bottomDp", t.bottomDp)
                put("label", t.label)
            }
            configFile(context).writeText(json.toString())
            Log.i("root config written: $json")
        } catch (t: Throwable) {
            Log.e("writeConfig failed", t)
        }
    }

    fun start(context: Context): Boolean {
        writeConfig(context)
        val apk = context.applicationInfo.sourceDir
        val config = configFile(context).absolutePath
        val cmd = "CLASSPATH=$apk app_process /system/bin --nice-name=nowbar_root " +
            "dev.brl.nowbar.module.RootOverlayDaemon $config"
        return try {
            val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            process = proc
            isRunning = true
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        Log.i("su| $line")
                    }
                } catch (t: Throwable) {
                }
                isRunning = false
                Log.w("root daemon exited")
            }.start()
            register(context)
            Log.i("root daemon launched")
            true
        } catch (t: Throwable) {
            isRunning = false
            Log.e("root daemon launch failed", t)
            false
        }
    }

    fun stop() {
        try {
            process?.destroy()
        } catch (t: Throwable) {
        }
        process = null
        isRunning = false
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f nowbar_root"))
        } catch (t: Throwable) {
            Log.w("pkill failed", t)
        }
    }

    private fun register(context: Context) {
        if (listener != null) return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            writeConfig(context)
        }
        listener = l
        TuningStore.prefs(context).registerOnSharedPreferenceChangeListener(l)
    }
}
