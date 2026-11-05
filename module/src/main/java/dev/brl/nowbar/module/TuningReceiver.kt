package dev.brl.nowbar.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * TEMPORARY test hook: lets adb push tuning values while the UI is locked, which
 * exercises the same prefs-change path the sliders use.
 *
 *   adb shell am broadcast -a dev.brl.nowbar.module.SET \
 *     -n dev.brl.nowbar.module/.TuningReceiver --ei heightDp 90 --ei bottomDp 260
 */
class TuningReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            // Temporary: lets adb start/stop the root overlay (Magisk will prompt once).
            if (intent.hasExtra("root")) {
                val enable = intent.getBooleanExtra("root", false)
                val started = if (enable) RootController.start(context) else {
                    RootController.stop()
                    true
                }
                Log.i("TuningReceiver root=$enable started=$started")
                return
            }

            // Temporary: log every stored tuning value so the numbers can be baked in.
            if (intent.hasExtra("dump")) {
                val p = TuningStore.prefs(context)
                val sb = StringBuilder("prefs dump: ")
                intents(intent).forEach { k -> sb.append(k).append('=').append(p.all[k] ?: "unset").append(' ') }
                sb.append("popupLabel=").append(p.all["popupLabel"] ?: "unset")
                Log.i(sb.toString())
                return
            }

            val prefs = TuningStore.prefs(context)
            val edit = prefs.edit()
            var changed = false
            intents(intent).forEach { key ->
                if (intent.hasExtra(key)) {
                    edit.putInt(key, intent.getIntExtra(key, prefs.getInt(key, 0)))
                    changed = true
                }
            }
            if (intent.hasExtra("label")) {
                edit.putString("label", intent.getStringExtra("label"))
                changed = true
            }
            if (!changed) {
                Log.w("TuningReceiver: no recognised extras")
                return
            }
            edit.apply()
            Log.i("TuningReceiver applied: ${intent.extras?.keySet()?.joinToString()}")
        } catch (t: Throwable) {
            Log.e("TuningReceiver failed", t)
        }
    }

    private fun intents(intent: Intent) =
        listOf(
            "widthDp", "heightDp", "textDp", "radiusDp", "bottomDp",
            "sbWidthDp", "sbHeightDp", "sbTextDp", "sbRadiusDp", "sbLeftDp", "sbTopDp",
            "popupWidthDp", "popupHeightDp", "popupTextDp", "popupRadiusDp",
            "popupLeftDp", "popupTopDp"
        )
}
