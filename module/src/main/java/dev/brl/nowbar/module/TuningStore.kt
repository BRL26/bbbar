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

    // Toggles. Everything defaults to on so a fresh install behaves like the dialled-in build.
    const val MASTER = "enabled"
    const val SURFACE_LOCK = "surfaceLock"
    const val SURFACE_STATUS = "surfaceStatus"
    const val SURFACE_POPUP = "surfacePopup"
    const val FEATURE_MEDIA = "featureMedia"
    const val FEATURE_TIMERS = "featureTimers"
    const val FEATURE_ALARM = "featureAlarm"
    const val FEATURE_LIVE = "featureLive"

    /** Which item wins, highest first. Comma separated: media,timers,live by default. */
    const val RANK_ORDER = "rankOrder"
    const val DEFAULT_RANK_ORDER = "media,timers,alarm,live"

    // Look of the stack and of media artwork.
    const val ART_MODE = "artMode"          // normal | tinted | blurred | both
    const val ART_TINT = "artTint"          // system | custom
    const val STACK_PEEK = "stackPeekDp"    // how far each card behind peeks out
    const val BLUR_STRENGTH = "blurStrength" // 1..10, how hard the artwork blur is
    const val ART_TINT_STRENGTH = "artTintStrength" // 0..100, how opaque the tint over art is

    /** What a surface does when no item is active: placeholder text, a logo mark, or nothing. */
    const val EMPTY_MODE = "emptyMode"

    /** Long lines scroll sideways instead of being cut off. */
    const val SCROLL_TEXT = "scrollText"

    fun emptyMode(context: Context): String {
        return try {
            prefs(context).getString(EMPTY_MODE, "placeholder") ?: "placeholder"
        } catch (t: Throwable) {
            "placeholder"
        }
    }

    fun scrollText(context: Context): Boolean = isOn(context, SCROLL_TEXT)

    const val DEFAULT_STACK_PEEK_DP = 22

    fun artTintStrength(context: Context): Int {
        return try {
            prefs(context).getInt(ART_TINT_STRENGTH, 55).coerceIn(0, 100)
        } catch (t: Throwable) {
            55
        }
    }

    fun blurStrength(context: Context): Int {
        return try {
            prefs(context).getInt(BLUR_STRENGTH, 3).coerceIn(1, 10)
        } catch (t: Throwable) {
            3
        }
    }

    fun rankOrder(context: Context): List<String> {
        return try {
            val raw = prefs(context).getString(RANK_ORDER, DEFAULT_RANK_ORDER) ?: DEFAULT_RANK_ORDER
            val known = listOf("media", "timers", "alarm", "live")
            val parsed = raw.split(',').map { it.trim() }.filter { it in known }
            // Any key added since the order was stored still gets a place, at the bottom.
            parsed + known.filter { it !in parsed }
        } catch (t: Throwable) {
            DEFAULT_RANK_ORDER.split(',')
        }
    }

    fun artMode(context: Context): String {
        return try {
            prefs(context).getString(ART_MODE, "both") ?: "both"
        } catch (t: Throwable) {
            "both"
        }
    }

    fun stackPeekDp(context: Context): Int {
        return try {
            prefs(context).getInt(STACK_PEEK, DEFAULT_STACK_PEEK_DP).coerceIn(0, 40)
        } catch (t: Throwable) {
            0
        }
    }

    /** Reads a toggle; missing (or unreadable) means on. */
    fun isOn(context: Context, key: String): Boolean = try {
        prefs(context).getBoolean(key, true)
    } catch (t: Throwable) {
        true
    }

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
