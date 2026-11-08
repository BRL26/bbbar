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

    /**
     * The root logcat watcher: the instant lock/unlock trigger. Off leaves the 1 s poll and the
     * broadcasts as the only triggers, so a handover can be up to a second late.
     */
    const val ROOT_WATCHER = "rootWatcher"

    /** Which item wins, highest first. Comma separated: media,timers,live by default. */
    const val RANK_ORDER = "rankOrder"
    const val DEFAULT_RANK_ORDER = "media,timers,alarm,live"

    // Look of the stack and of media artwork.
    const val ART_MODE = "artMode"          // normal | tinted | blurred | both
    const val ART_TINT = "artTint"          // system | custom
    const val STACK_PEEK = "stackPeekDp"    // how far each card behind peeks out
    const val BLUR_STRENGTH = "blurStrength" // 1..10, how hard the artwork blur is
    const val ART_TINT_STRENGTH = "artTintStrength" // 0..100, how opaque the tint over art is

    /** The idle line. Also decides whether an idle status bar pill is drawn at all. */
    const val LABEL = "label"

    /** Long lines scroll sideways instead of being cut off. */
    const val SCROLL_TEXT = "scrollText"

    /**
     * How much the module writes to the log. 0 = warnings only, 1 = state changes (default),
     * 2 = everything including every notification read. Read by [Log.sync].
     */
    const val DEBUG_LEVEL = "debugLevel"

    fun debugLevel(context: Context): Int = try {
        prefs(context).getInt(DEBUG_LEVEL, 1).coerceIn(0, 2)
    } catch (t: Throwable) {
        1
    }

    /**
     * The always-on display is a still, colourless screen: the pill fades to greyscale with it and
     * fades back to colour on wake. Off keeps it in colour on the AOD.
     */
    const val AOD_GRAY = "aodGray"

    /**
     * The AOD look: instead of a filled card, a thin outline with the text inside and the progress
     * as a marching checkerboard across the bar. Off keeps the normal card on the AOD.
     */
    const val AOD_LINE_STYLE = "aodLineStyle"

    /**
     * AOD dot spacing in dp: how tightly packed the lattice is.
     */
    const val AOD_DOT_CELL = "aodDotCellDp"
    const val DEFAULT_AOD_DOT_CELL_DP = 8

    fun aodDotCellDp(context: Context): Int = try {
        prefs(context).getInt(AOD_DOT_CELL, DEFAULT_AOD_DOT_CELL_DP).coerceIn(3, 24)
    } catch (t: Throwable) {
        DEFAULT_AOD_DOT_CELL_DP
    }

    /**
     * AOD dot diameter in dp: how big each dot is, independent of the spacing. Never larger than the
     * spacing, so at the top of the range the dots just touch instead of merging into a solid bar.
     */
    const val AOD_DOT_SIZE = "aodDotSizeDp"
    const val DEFAULT_AOD_DOT_SIZE_DP = 5

    fun aodDotSizeDp(context: Context): Int = try {
        prefs(context).getInt(AOD_DOT_SIZE, DEFAULT_AOD_DOT_SIZE_DP).coerceIn(1, 16)
    } catch (t: Throwable) {
        DEFAULT_AOD_DOT_SIZE_DP
    }

    /**
     * What an idle status bar pill says: the label, as typed. A blank label means there is nothing
     * to say, so no pill is drawn until an activity starts.
     *
     * This replaced an explicit Text / Logo / Hide switch. That switch was a stored state nothing
     * ever reset, so it could sit on "Hide" and make a working bar look dead.
     */
    fun idleLabel(context: Context): String {
        return try {
            (prefs(context).getString(LABEL, DEFAULT_LABEL) ?: "").trim()
        } catch (t: Throwable) {
            DEFAULT_LABEL
        }
    }

    /** True when an empty pill has a line to show; a blank label hides the idle pill. */
    fun showIdle(context: Context): Boolean = idleLabel(context).isNotEmpty()

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

    /** Landscape overrides for the status bar pill. -1 = use the portrait value. */
    const val DEFAULT_SB_LANDSCAPE_LEFT_DP = -1
    const val DEFAULT_SB_LANDSCAPE_TOP_DP = -1

    /**
     * The inverse layout (issue #10): the status pill sits at the right end of the strip, where
     * Android draws its battery and network icons. The hook hides that cluster, brings the
     * notification icons back, and the pill draws its own battery and signal glyphs. The expanded
     * card grows out of the pill's left edge, because the pill now ends at the screen edge.
     */
    const val SB_INVERSE = "sbInverse"

    /**
     * The new build puts the status pill at the right end by default: it owns Android's battery /
     * network corner and draws those glyphs itself, instead of covering the notification icons.
     */
    const val DEFAULT_SB_INVERSE = true

    fun sbInverse(context: Context): Boolean = try {
        prefs(context).getBoolean(SB_INVERSE, DEFAULT_SB_INVERSE)
    } catch (t: Throwable) {
        DEFAULT_SB_INVERSE
    }

    /**
     * Order of the inverse pill's trailing glyphs. "battery,signal" (the default) keeps the
     * battery closest to the edge, "signal,battery" swaps them.
     */
    const val STATUS_ICON_ORDER = "statusIconOrder"
    const val DEFAULT_STATUS_ICON_ORDER = "battery,signal"
    const val STATUS_ICON_ORDER_SIGNAL_FIRST = "signal,battery"

    fun statusSignalFirst(context: Context): Boolean = try {
        prefs(context).getString(STATUS_ICON_ORDER, DEFAULT_STATUS_ICON_ORDER) ==
            STATUS_ICON_ORDER_SIGNAL_FIRST
    } catch (t: Throwable) {
        false
    }

    /**
     * Gap between the inverse pill and the right edge of the strip. The right edge is the fixed
     * one in that layout (the left edge follows from the width), so this replaces the left-edge
     * setting: a left value cannot place a pill that is anchored on the right.
     */
    const val SB_RIGHT_MARGIN = "sbRightDp"
    const val DEFAULT_SB_RIGHT_MARGIN_DP = 10

    fun sbRightMarginDp(context: Context): Int = try {
        prefs(context).getInt(SB_RIGHT_MARGIN, DEFAULT_SB_RIGHT_MARGIN_DP).coerceIn(0, 200)
    } catch (t: Throwable) {
        DEFAULT_SB_RIGHT_MARGIN_DP
    }

    /**
     * Measure mode: every surface is drawn as an empty outline with no content, and a surface
     * stays up even with nothing active, so sizes, edges and corner radii can be dialled in
     * against the real screen instead of a preview.
     */
    const val OUTLINE = "outlineMode"
    const val DEFAULT_OUTLINE = false

    fun outline(context: Context): Boolean = try {
        prefs(context).getBoolean(OUTLINE, DEFAULT_OUTLINE)
    } catch (t: Throwable) {
        DEFAULT_OUTLINE
    }

    // Expanded popup (third surface), opened by tapping the status bar pill. It carries the
    // detail and controls; the status bar pill itself stays text + image only.
    const val DEFAULT_POPUP_WIDTH_DP = 393
    const val DEFAULT_POPUP_HEIGHT_DP = 120
    const val DEFAULT_POPUP_TEXT_DP = 16
    const val DEFAULT_POPUP_RADIUS_DP = 32
    const val DEFAULT_POPUP_LEFT_DP = -1
    const val DEFAULT_POPUP_TOP_DP = -1

    /**
     * Extra top offset in landscape, where the status bar is shallower and its clock sits closer
     * to the edge, so the popup would otherwise clip into it.
     */
    const val DEFAULT_POPUP_LANDSCAPE_TOP_DP = 24

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Bumped whenever the stored configuration changes shape. The rewrite below replaces a file
     * written by an older build, so geometry dialled in for the old layout cannot leave the new
     * one half-configured (that is how the pill kept a left-edge value after moving to the right).
     */
    const val CONFIG_VERSION = 4
    private const val CONFIG_VERSION_KEY = "configVersion"

    /** Rewrites the whole tuning file when it was written by an older build. */
    fun maybeRewrite(context: Context) {
        try {
            val p = prefs(context)
            Log.i(
                "prefs: version=${p.getInt(CONFIG_VERSION_KEY, 0)} keys=${p.all.size} " +
                    "dir=${context.dataDir}"
            )
            if (p.getInt(CONFIG_VERSION_KEY, 0) >= CONFIG_VERSION) return
            rewrite(p)
            Log.i("prefs: rewritten for build config $CONFIG_VERSION")
        } catch (t: Throwable) {
            Log.w("prefs rewrite failed", t)
        }
    }

    /** The reset button: one complete file of this build's defaults, not an empty one. */
    fun rewriteDefaults(context: Context) {
        try {
            rewrite(prefs(context))
            Log.i("prefs: reset to build config $CONFIG_VERSION")
        } catch (t: Throwable) {
            Log.w("prefs reset failed", t)
        }
    }

    /**
     * Every setting this build reads, written explicitly. Keeping the whole set here means the
     * file says what the build is configured to do, and a default read never has to fall back.
     */
    private fun rewrite(p: SharedPreferences) {
        val e = p.edit().clear()
        e.putInt(CONFIG_VERSION_KEY, CONFIG_VERSION)

        // Switches.
        e.putBoolean(MASTER, true)
        e.putBoolean(SURFACE_LOCK, true)
        e.putBoolean(SURFACE_STATUS, true)
        e.putBoolean(SURFACE_POPUP, true)
        e.putBoolean(FEATURE_MEDIA, true)
        e.putBoolean(FEATURE_TIMERS, true)
        e.putBoolean(FEATURE_ALARM, true)
        e.putBoolean(FEATURE_LIVE, true)
        e.putBoolean(ROOT_WATCHER, true)
        e.putBoolean(AOD_GRAY, true)
        e.putBoolean(AOD_LINE_STYLE, true)
        e.putBoolean(SCROLL_TEXT, true)
        e.putBoolean(SB_INVERSE, DEFAULT_SB_INVERSE)
        e.putBoolean(OUTLINE, DEFAULT_OUTLINE)

        // Cards and the stack.
        e.putString(RANK_ORDER, DEFAULT_RANK_ORDER)
        e.putString(ART_MODE, "both")
        e.putString(ART_TINT, "system")
        e.putInt(STACK_PEEK, DEFAULT_STACK_PEEK_DP)
        e.putInt(BLUR_STRENGTH, 3)
        e.putInt(ART_TINT_STRENGTH, 55)
        e.putString(LABEL, DEFAULT_LABEL)
        e.putInt(DEBUG_LEVEL, 1)

        // Lockscreen pill.
        e.putInt("widthDp", DEFAULT_WIDTH_DP)
        e.putInt("heightDp", DEFAULT_HEIGHT_DP)
        e.putInt("textDp", DEFAULT_TEXT_DP)
        e.putInt("radiusDp", DEFAULT_RADIUS_DP)
        e.putInt("bottomDp", DEFAULT_BOTTOM_DP)

        // Status bar pill, both layouts.
        e.putInt("sbWidthDp", DEFAULT_SB_WIDTH_DP)
        e.putInt("sbHeightDp", DEFAULT_SB_HEIGHT_DP)
        e.putInt("sbTextDp", DEFAULT_SB_TEXT_DP)
        e.putInt("sbRadiusDp", DEFAULT_SB_RADIUS_DP)
        e.putInt("sbLeftDp", DEFAULT_SB_LEFT_DP)
        e.putInt("sbTopDp", DEFAULT_SB_TOP_DP)
        e.putInt("sbLandscapeLeftDp", DEFAULT_SB_LANDSCAPE_LEFT_DP)
        e.putInt("sbLandscapeTopDp", DEFAULT_SB_LANDSCAPE_TOP_DP)
        e.putString(STATUS_ICON_ORDER, DEFAULT_STATUS_ICON_ORDER)
        e.putInt(SB_RIGHT_MARGIN, DEFAULT_SB_RIGHT_MARGIN_DP)

        // Expanded card.
        e.putInt("popupWidthDp", DEFAULT_POPUP_WIDTH_DP)
        e.putInt("popupHeightDp", DEFAULT_POPUP_HEIGHT_DP)
        e.putInt("popupTextDp", DEFAULT_POPUP_TEXT_DP)
        e.putInt("popupRadiusDp", DEFAULT_POPUP_RADIUS_DP)
        e.putInt("popupLeftDp", DEFAULT_POPUP_LEFT_DP)
        e.putInt("popupTopDp", DEFAULT_POPUP_TOP_DP)
        e.putInt("popupLandscapeTopDp", DEFAULT_POPUP_LANDSCAPE_TOP_DP)
        e.putString("popupLabel", DEFAULT_LABEL)

        // AOD lattice.
        e.putInt(AOD_DOT_CELL, DEFAULT_AOD_DOT_CELL_DP)
        e.putInt(AOD_DOT_SIZE, DEFAULT_AOD_DOT_SIZE_DP)

        // Synchronous on purpose: this runs once, at startup, and the caller wants to know the
        // file actually landed instead of guessing from an async apply().
        val ok = e.commit()
        Log.i("prefs: wrote ${p.all.size} keys (commit=$ok)")
    }
}
