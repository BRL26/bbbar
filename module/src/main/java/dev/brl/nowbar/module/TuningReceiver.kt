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

        /**
         * One SystemUI restart, through root. The hook only reloads with SystemUI, so a build that
         * changed it needs exactly this after installing - and nothing else needs restarting.
         *
         *   adb shell am broadcast -a dev.brl.nowbar.module.RESTART_SUI \
         *     -n dev.brl.nowbar.module/.TuningReceiver
         */
        const val ACTION_RESTART_SUI = "dev.brl.nowbar.module.RESTART_SUI"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == ACTION_RELOAD) {
                NowBarBridge.reload()
                Log.i("TuningReceiver: reload requested")
                return
            }
            // A card that exists only to be looked at: media | timer | live | none.
            val fake = intent.getStringExtra("fake")
            if (fake != null) {
                NowBarState.applyFake(fake)
                return
            }
            if (intent.action == ACTION_RESTART_SUI) {
                Thread {
                    val out = RootShell.run("killall com.android.systemui")
                    Log.i("TuningReceiver: SystemUI restart -> ${out ?: "no root"}")
                }.start()
                return
            }
            // Log every stored tuning value so the numbers can be baked in.
            if (intent.hasExtra("dump")) {
                val p = TuningStore.prefs(context)
                val sb = StringBuilder("prefs dump: ")
                intents(intent).forEach { k ->
                    sb.append(k).append('=').append(p.all[k] ?: "unset").append(' ')
                }
                // Every stored key, so a dialled-in file can be copied into the build's
                // defaults without guessing which values were tuned and which were left alone.
                p.all.toSortedMap().forEach { (key, value) ->
                    sb.append(key).append('=').append(value ?: "unset").append(' ')
                }
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
            if (intent.hasExtra(TuningStore.SB_SIDE)) {
                edit.putString(TuningStore.SB_SIDE, intent.getStringExtra(TuningStore.SB_SIDE))
                changed = true
            }
            if (intent.hasExtra(TuningStore.IDLE_CONTENT)) {
                edit.putString(
                    TuningStore.IDLE_CONTENT,
                    intent.getStringExtra(TuningStore.IDLE_CONTENT)
                )
                changed = true
            }
            if (intent.hasExtra(TuningStore.STATUS_ICON_ORDER)) {
                edit.putString(
                    TuningStore.STATUS_ICON_ORDER,
                    intent.getStringExtra(TuningStore.STATUS_ICON_ORDER)
                )
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
        TuningStore.AOD_GRAY,
        TuningStore.AOD_LINE_STYLE,
        TuningStore.OUTLINE
    )

    private fun intents(intent: Intent) =
        listOf(
            "widthDp", "heightDp", "textDp", "radiusDp", "bottomDp",
            "sbWidthDp", "sbHeightDp", "sbTextDp", "sbRadiusDp", "sbLeftDp", "sbTopDp",
            "sbLandscapeWidthDp", "sbLandscapeRightDp", "sbLandscapeTopDp", "sbRightDp",
            "aodDotCellDp", "aodDotSizeDp",
            "popupWidthDp", "popupHeightDp", "popupTextDp", "popupRadiusDp",
            "popupLeftDp", "popupTopDp", "popupLandscapeTopDp"
        )
}
