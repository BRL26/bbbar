package dev.brl.nowbar.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Test hook: lets adb push tuning values, which exercises the same prefs-change path the
 * sliders use. Handy when the pill is covering something and the UI cannot be reached.
 *
 *   adb shell am broadcast -a dev.brl.nowbar.module.SET \
 *     -n dev.brl.nowbar.module/.TuningReceiver --ei heightDp 90 --ei bottomDp 260
 *   adb shell am broadcast -a dev.brl.nowbar.module.SET \
 *     -n dev.brl.nowbar.module/.TuningReceiver --ez dump true
 */
class TuningReceiver : BroadcastReceiver() {

    companion object {
        /** Sent by the settings screen's "Apply settings" button. */
        const val ACTION_RELOAD = "dev.brl.nowbar.module.RELOAD"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == ACTION_RELOAD) {
                NowBarBridge.reload()
                Log.i("TuningReceiver: reload requested")
                return
            }
            // Log every stored tuning value so the numbers can be baked in.
            if (intent.hasExtra("dump")) {
                val p = TuningStore.prefs(context)
                val sb = StringBuilder("prefs dump: ")
                intents(intent).forEach { k ->
                    sb.append(k).append('=').append(p.all[k] ?: "unset").append(' ')
                }
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
            booleans(intent).forEach { key ->
                if (intent.hasExtra(key)) {
                    edit.putBoolean(key, intent.getBooleanExtra(key, true))
                    changed = true
                }
            }
            if (intent.hasExtra(TuningStore.RANK_ORDER)) {
                edit.putString(TuningStore.RANK_ORDER, intent.getStringExtra(TuningStore.RANK_ORDER))
                changed = true
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

    /** Boolean toggles: master, surfaces, features and the test switch. */
    private fun booleans(intent: Intent) = listOf(
        TuningStore.MASTER,
        TuningStore.SURFACE_LOCK,
        TuningStore.SURFACE_STATUS,
        TuningStore.SURFACE_POPUP,
        TuningStore.FEATURE_MEDIA,
        TuningStore.FEATURE_TIMERS,
        TuningStore.FEATURE_ALARM,
        TuningStore.FEATURE_LIVE,
        TuningStore.AOD_GRAY
    )

    private fun intents(intent: Intent) =
        listOf(
            "widthDp", "heightDp", "textDp", "radiusDp", "bottomDp",
            "sbWidthDp", "sbHeightDp", "sbTextDp", "sbRadiusDp", "sbLeftDp", "sbTopDp",
            "popupWidthDp", "popupHeightDp", "popupTextDp", "popupRadiusDp",
            "popupLeftDp", "popupTopDp"
        )
}
