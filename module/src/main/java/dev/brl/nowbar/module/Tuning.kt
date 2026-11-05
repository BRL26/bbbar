package dev.brl.nowbar.module

import android.content.Context

/** The pill's tunable numbers, read straight from shared preferences. */
object Tuning {

    data class Values(
        val widthDp: Int = TuningStore.DEFAULT_WIDTH_DP,
        val heightDp: Int = TuningStore.DEFAULT_HEIGHT_DP,
        val textDp: Int = TuningStore.DEFAULT_TEXT_DP,
        val radiusDp: Int = TuningStore.DEFAULT_RADIUS_DP,
        val bottomDp: Int = TuningStore.DEFAULT_BOTTOM_DP,
        val label: String = TuningStore.DEFAULT_LABEL
    )

    /** Numbers for the small status bar pill shown while unlocked. */
    data class Sb(
        val widthDp: Int = TuningStore.DEFAULT_SB_WIDTH_DP,
        val heightDp: Int = TuningStore.DEFAULT_SB_HEIGHT_DP,
        val textDp: Int = TuningStore.DEFAULT_SB_TEXT_DP,
        val radiusDp: Int = TuningStore.DEFAULT_SB_RADIUS_DP,
        val leftDp: Int = TuningStore.DEFAULT_SB_LEFT_DP,
        val topDp: Int = TuningStore.DEFAULT_SB_TOP_DP,
        val label: String = TuningStore.DEFAULT_LABEL
    )

    /** Numbers for the expanded popup that drops out of the status bar pill. */
    data class Popup(
        val widthDp: Int = TuningStore.DEFAULT_POPUP_WIDTH_DP,
        val heightDp: Int = TuningStore.DEFAULT_POPUP_HEIGHT_DP,
        val textDp: Int = TuningStore.DEFAULT_POPUP_TEXT_DP,
        val radiusDp: Int = TuningStore.DEFAULT_POPUP_RADIUS_DP,
        val leftDp: Int = TuningStore.DEFAULT_POPUP_LEFT_DP,
        val topDp: Int = TuningStore.DEFAULT_POPUP_TOP_DP,
        val label: String = TuningStore.DEFAULT_LABEL
    )

    fun popup(context: Context): Popup {
        val p = TuningStore.prefs(context)
        return Popup(
            widthDp = p.getInt("popupWidthDp", TuningStore.DEFAULT_POPUP_WIDTH_DP),
            heightDp = p.getInt("popupHeightDp", TuningStore.DEFAULT_POPUP_HEIGHT_DP),
            textDp = p.getInt("popupTextDp", TuningStore.DEFAULT_POPUP_TEXT_DP),
            radiusDp = p.getInt("popupRadiusDp", TuningStore.DEFAULT_POPUP_RADIUS_DP),
            leftDp = p.getInt("popupLeftDp", TuningStore.DEFAULT_POPUP_LEFT_DP),
            topDp = p.getInt("popupTopDp", TuningStore.DEFAULT_POPUP_TOP_DP),
            label = p.getString("popupLabel", TuningStore.DEFAULT_LABEL) ?: TuningStore.DEFAULT_LABEL
        )
    }

    fun sb(context: Context): Sb {
        val p = TuningStore.prefs(context)
        return Sb(
            widthDp = p.getInt("sbWidthDp", TuningStore.DEFAULT_SB_WIDTH_DP),
            heightDp = p.getInt("sbHeightDp", TuningStore.DEFAULT_SB_HEIGHT_DP),
            textDp = p.getInt("sbTextDp", TuningStore.DEFAULT_SB_TEXT_DP),
            radiusDp = p.getInt("sbRadiusDp", TuningStore.DEFAULT_SB_RADIUS_DP),
            leftDp = p.getInt("sbLeftDp", TuningStore.DEFAULT_SB_LEFT_DP),
            topDp = p.getInt("sbTopDp", TuningStore.DEFAULT_SB_TOP_DP),
            label = p.getString("label", TuningStore.DEFAULT_LABEL) ?: TuningStore.DEFAULT_LABEL
        )
    }

    fun current(context: Context): Values {
        val p = TuningStore.prefs(context)
        return Values(
            widthDp = p.getInt("widthDp", TuningStore.DEFAULT_WIDTH_DP),
            heightDp = p.getInt("heightDp", TuningStore.DEFAULT_HEIGHT_DP),
            textDp = p.getInt("textDp", TuningStore.DEFAULT_TEXT_DP),
            radiusDp = p.getInt("radiusDp", TuningStore.DEFAULT_RADIUS_DP),
            bottomDp = p.getInt("bottomDp", TuningStore.DEFAULT_BOTTOM_DP),
            label = p.getString("label", TuningStore.DEFAULT_LABEL) ?: TuningStore.DEFAULT_LABEL
        )
    }
}
