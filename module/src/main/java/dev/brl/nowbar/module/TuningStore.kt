package dev.brl.nowbar.module

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the pill's numbers live while they are being dialled in.
 *
 * The Activity writes, the overlay service reads (same uid, same process family),
 * and the service listens for changes so edits apply live.
 */
object TuningStore {
    const val PREFS = "tuning"

    // Dialled in by the owner on the device (Nothing Phone 3a, 1080x2392 @ 420dpi).
    const val DEFAULT_WIDTH_DP = 340
    const val DEFAULT_HEIGHT_DP = 68
    const val DEFAULT_TEXT_DP = 21
    const val DEFAULT_RADIUS_DP = 40
    const val DEFAULT_BOTTOM_DP = 150
    const val DEFAULT_LABEL = "Now Bar"

    // Status bar pill (unlocked). It sits where the notification icons live and hides them:
    // left edge just past the clock, right edge stopping short of the camera cutout.
    // Left/top of -1 mean "place it automatically" (right of the cutout, centred vertically).
    const val DEFAULT_SB_WIDTH_DP = 106
    const val DEFAULT_SB_HEIGHT_DP = 27
    const val DEFAULT_SB_TEXT_DP = 13
    const val DEFAULT_SB_RADIUS_DP = 19
    const val DEFAULT_SB_LEFT_DP = 75
    const val DEFAULT_SB_TOP_DP = -1

    // Expanded popup (third surface), opened by tapping the status bar pill. It carries the
    // detail and controls; the status bar pill itself stays text + image only.
    const val DEFAULT_POPUP_WIDTH_DP = 393
    const val DEFAULT_POPUP_HEIGHT_DP = 120
    const val DEFAULT_POPUP_TEXT_DP = 16
    const val DEFAULT_POPUP_RADIUS_DP = 32
    const val DEFAULT_POPUP_LEFT_DP = -1
    const val DEFAULT_POPUP_TOP_DP = -1

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
