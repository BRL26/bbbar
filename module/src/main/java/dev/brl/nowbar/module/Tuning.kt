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
        /** Landscape overrides; -1 means "use the portrait value above". */
        val leftLandscapeDp: Int = TuningStore.DEFAULT_SB_LANDSCAPE_LEFT_DP,
        val topLandscapeDp: Int = TuningStore.DEFAULT_SB_LANDSCAPE_TOP_DP,
        /**
         * Where to sit while the shade or Quick Settings is open; -1 means "stay where you are".
         * That screen has its own header, so the strip the pill normally shares with the clock is
         * laid out differently.
         */
        val shadeLeftDp: Int = TuningStore.DEFAULT_SB_SHADE_LEFT_DP,
        val shadeTopDp: Int = TuningStore.DEFAULT_SB_SHADE_TOP_DP,
        /** Fixed width in the shade; 0 means "fit the line", as before the setting existed. */
        val shadeWidthDp: Int = TuningStore.DEFAULT_SB_SHADE_WIDTH_DP,
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
        val landscapeTopDp: Int = TuningStore.DEFAULT_POPUP_LANDSCAPE_TOP_DP,
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
            landscapeTopDp = p.getInt(
                "popupLandscapeTopDp",
                TuningStore.DEFAULT_POPUP_LANDSCAPE_TOP_DP
            ),
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
            leftLandscapeDp = p.getInt(
                "sbLandscapeLeftDp",
                TuningStore.DEFAULT_SB_LANDSCAPE_LEFT_DP
            ),
            topLandscapeDp = p.getInt(
                "sbLandscapeTopDp",
                TuningStore.DEFAULT_SB_LANDSCAPE_TOP_DP
            ),
            shadeLeftDp = p.getInt("sbShadeLeftDp", TuningStore.DEFAULT_SB_SHADE_LEFT_DP),
            shadeTopDp = p.getInt("sbShadeTopDp", TuningStore.DEFAULT_SB_SHADE_TOP_DP),
            shadeWidthDp = p.getInt("sbShadeWidthDp", TuningStore.DEFAULT_SB_SHADE_WIDTH_DP),
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
