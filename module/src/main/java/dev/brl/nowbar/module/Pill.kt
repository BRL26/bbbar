package dev.brl.nowbar.module

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.graphics.RectF
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The pill.
 *
 * Draws the front [NowBarItem] and, when there is more than one, a short stack of cards peeking
 * out underneath it. Content is icon-first; media artwork becomes the card background, cropped to
 * the card's aspect (never stretched) and blurred with a real box blur when that treatment is set.
 *
 * Expanded media cards carry previous / play-pause / next. Swipes are handed to the service:
 * left-right is a media skip, up-down cycles the stack. A plain tap calls [onTap].
 */
class PillView(context: Context) : View(context) {

    companion object {
        /** Still before the line starts moving, then still again once it has scrolled over. */
        private const val MARQUEE_HOLD_MS = 1_400L
        private const val MARQUEE_TAIL_MS = 1_100L

        /** Scroll speed in dp per second. */
        private const val MARQUEE_SPEED_DP = 34f

        /** Height of the expanded media seek row: the track plus the elapsed time beside it. */
        private const val SEEK_ROW_DP = 14f

        /** One full march of the AOD dot lattice. */
        private const val DOT_PERIOD_MS = 2_600L


        /** Drift bounds: a new random direction every [DRIFT_TURN_MIN_MS]..[DRIFT_TURN_MAX_MS]. */
        private const val DRIFT_TURN_MIN_MS = 2_200L
        private const val DRIFT_TURN_MAX_MS = 4_500L

        /** How quickly the dots ease from one direction into the next. */
        private const val DRIFT_EASE_MS = 900f

        /**
         * How much of the finger the card takes before the direction is clear. Low on purpose: it
         * is the "being stretched from one edge" part, and it is what stops a swipe from reading as
         * a jump in the wrong direction.
         */
        private const val DRAG_GRIP_START = 0.4f

        /** Time constant for the card catching up to the finger once the direction is guessed. */
        private const val DRAG_CATCHUP_MS = 90f

        /** However unclear the direction, the guess is made once the finger has gone this far. */
        private const val DRAG_FORCE_LOCK_DP = 26f

        /**
         * The hairline between the line and the trailing battery / network cluster: a rounded
         * 1.2 dp rule. At 27 dp the glyphs would otherwise read as loose marks inside the pill
         * instead of a group of their own.
         *
         * The rule sits exactly halfway between the line's ink and the glyph cluster: the same
         * gap on both sides is what makes it read as a separator rather than as something
         * drifting towards one of them.
         */
        private const val STATUS_DIVIDER_W_DP = 1.2f

        /**
         * Room asked for on each side of the rule when the pill is fitted to its line. The
         * drawing splits whatever space it actually gets in half, so this only sets how wide the
         * pill comes out - it never decides the two gaps separately.
         */
        private const val STATUS_DIVIDER_GAP_DP = 5.5f

        /** How far the card gives while the direction is still unknown. */
        private const val DRAG_GIVE_DP = 16f

        /** How far the finger has to go, and by how much it must dominate, to lock an axis. */
        private const val DRAG_LOCK_DP = 12f
        private const val DRAG_LOCK_RATIO = 1.25f

        /** How long the pill takes to morph between its line and its icons-only lock form. */
        private const val STATUS_ONLY_MS = 260L

        /** KDE Connect: the media pill shows a play button instead of this app's icon. */
        private const val KDE_CONNECT_PKG = "org.kde.kdeconnect"
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(TuningStore.DEFAULT_TEXT_DP.toFloat())
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f) }
    private val queuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val progressTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val progressFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * The inverse pill's own battery and signal glyphs (issue #10). They are painted with their own
     * paints so setting a stroke style here can never leak into the card's other glyphs.
     */
    private val statusDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val statusStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val statusFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Scratch rect for the earbuds' stems, so the glyph costs no allocation per frame. */
    private val statusStem = RectF()

    /** Offscreen buffer for the AOD's leading glyph, reused so a frame allocates nothing. */
    private var aodGlyphBitmap: Bitmap? = null
    private var aodGlyphCanvas: Canvas? = null
    private var aodGlyphPixels = IntArray(0)

    /**
     * The leading glyph: the player's icon, the play button, the clock, the bell or the artwork.
     * One place, so the AOD's one-bit treatment applies to all of them instead of the icon only.
     */
    private fun drawLeadingGlyph(
        canvas: Canvas,
        glyphRect: RectF,
        glyphSize: Float,
        item: NowBarItem?,
        art: Bitmap?
    ): Boolean {
        when {
            item?.kind == ItemKind.TIMER -> {
                drawClock(canvas, glyphRect)
            }
            item?.kind == ItemKind.ALARM -> {
                drawBell(canvas, glyphRect)
            }
            item?.kind == ItemKind.LIVE -> {
                drawLiveDot(canvas, glyphRect)
            }
            item?.kind == ItemKind.MEDIA -> {
                val pkg = item.packageName.orEmpty()
                if (isKdeConnect(pkg)) {
                    // KDE Connect's launcher icon is a phone with a plug: at 13 dp it reads as a
                    // smudge. A play button says "this is the thing playing" instead.
                    drawPlayButton(canvas, glyphRect)
                } else {
                    val icon = appIcon(pkg) ?: return false
                    drawAppIcon(canvas, icon, glyphRect, glyphSize * 0.28f)
                }
            }
            art != null && item?.kind != ItemKind.MEDIA -> {
                artRect.set(
                    glyphRect.left.toInt(),
                    glyphRect.top.toInt(),
                    glyphRect.right.toInt(),
                    glyphRect.bottom.toInt()
                )
                drawCroppedArt(canvas, art, artRect, glyphSize / 2f)
            }
            else -> return false
        }
        return true
    }

    /** Grows the AOD glyph buffer to the slot, keeping its canvas and pixel scratch in step. */
    private fun ensureAodGlyphBuffer(glyphRect: RectF): Bitmap? {
        return try {
            val w = kotlin.math.ceil(glyphRect.width()).toInt().coerceAtLeast(1)
            val h = kotlin.math.ceil(glyphRect.height()).toInt().coerceAtLeast(1)
            val current = aodGlyphBitmap
            if (current != null && current.width == w && current.height == h) return current
            val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            aodGlyphBitmap = fresh
            aodGlyphCanvas = Canvas(fresh)
            aodGlyphPixels = IntArray(w * h)
            fresh
        } catch (t: Throwable) {
            Log.w("PillView AOD glyph buffer failed", t)
            null
        }
    }

    /**
     * Rounds every pixel of the glyph to black or white, then flips the ones the dot lattice
     * covers: the mark inverts in dots, the way the AOD line does.
     */
    private fun aodRoundAndInvert(bmp: Bitmap, leftInCard: Float, filledRight: Float) {
        try {
            val w = bmp.width
            val h = bmp.height
            val px = aodGlyphPixels
            if (px.size != w * h) return
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val cell = aodDotCell()
            val radius = aodDotRadius(cell)
            var i = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = px[i]
                    val a = c ushr 24
                    if (a != 0) {
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = (r * 299 + g * 587 + b * 114) / 1000
                        var v = if (lum >= 128) 0xFF else 0
                        val played = leftInCard + x <= filledRight
                        if (played && inAodDot(x.toFloat(), y.toFloat(), cell, radius)) {
                            v = 0xFF - v
                        }
                        px[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
                    }
                    i++
                }
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        } catch (t: Throwable) {
            Log.w("PillView AOD glyph rounding failed", t)
        }
    }

    /**
     * Whether a lattice dot covers this pixel. The dots sit on a checkerboard one cell apart, at
     * (k + 0.5) * cell with k + m even, offset by the same drift the drawn lattice uses.
     */
    private fun inAodDot(x: Float, y: Float, cell: Float, radius: Float): Boolean {
        if (cell <= 0f) return false
        val u = x + driftX
        val v = y + driftY
        val baseK = Math.floor(((u - cell / 2f) / cell).toDouble()).toInt()
        val baseM = Math.floor(((v - cell / 2f) / cell).toDouble()).toInt()
        for (k in baseK - 1..baseK + 1) {
            for (m in baseM - 1..baseM + 1) {
                if ((k + m) % 2 != 0) continue
                val dx = u - (k * cell + cell / 2f)
                val dy = v - (m * cell + cell / 2f)
                if (dx * dx + dy * dy <= radius * radius) return true
            }
        }
        return false
    }

    private val statusDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The same dim colour as an outline, for the AOD's unlit cellular bars. */
    private val statusDimStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tintLayer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    /** Erases the cut ends of a scrolling line, so text dissolves into the card. */
    private val marqueeFade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    /** Source-app icons, so a media card says which player is running. `null` = no icon. */
    private val appIcons = HashMap<String, Drawable?>()

    /** When each overflowing line started scrolling, keyed by its own text. */
    private val marqueeStarts = HashMap<String, Long>()
    private var marqueeActive = false

    private val artClip = Path()
    private val card = RectF()
    private val backCard = RectF()
    private val artRect = Rect()
    private val srcRect = Rect()
    /** Hit areas of the expanded card's controls, rebuilt on every draw. */
    private val controlRects = ArrayList<RectF>(3)
    private val controlKeys = ArrayList<String>(3)

    private var label = TuningStore.DEFAULT_LABEL
    private var items: List<NowBarItem> = emptyList()
    private var textDp = TuningStore.DEFAULT_TEXT_DP
    private var radiusOverrideDp = 0f
    private var peekPx = 0
    private var expanded = false
    private var loggedFirstDraw = false
    private var downX = 0f
    private var downY = 0f

    /**
     * The same touch in screen coordinates. The window is moved and grown for the duration of a
     * drag, which changes where a screen point lands in view coordinates - measuring the drag in
     * view coordinates made the card chase its own window. Raw coordinates do not move.
     */
    private var downRawX = 0f
    private var downRawY = 0f
    private var blurSource: Bitmap? = null
    private var blurResult: Bitmap? = null
    private var blurStrengthCached = -1
    private var blurTintCached = -1

    /**
     * When > 0 the content is drawn at this height, anchored to the window's bottom edge, and
     * clipped by the window. Used while the popup collapses: the card keeps its size and slides
     * up out of view behind the pill instead of the glyphs and text being squashed.
     */
    private var contentHeightPx = 0

    /** Vertical offset used by the swipe-to-change-card animation. */
    private var slidePx = 0f
    private var slideAnimator: ValueAnimator? = null

    /** How far the card is dragged out of place while a finger is down (it follows the finger). */
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false

    /**
     * Which way this drag is going: 0 until it is clear, 1 sideways, 2 up/down. Decided once, so a
     * sideways swipe cannot flip to up/down halfway through - that flip is what made the card
     * wander vertically and then jump sideways.
     */
    private var dragAxis = 0

    /**
     * How much of the finger the card is taking, 0..1. It starts low: the card leans towards the
     * pull like something being stretched from one edge, then lets go and catches up to the finger.
     * That catch-up is the little bounce into place, and from then on the card tracks exactly.
     */
    private var dragGrip = 1f

    /** Timestamp of the last move event, for a frame-rate independent catch-up. */
    private var lastMoveAt = 0L

    /**
     * When true the surface's *window* is what moves, not its drawing: the offset is handed to the
     * service as a delta and the window manager moves the whole thing. A window that stays exactly
     * its own size has nothing to clip, so the card cannot be sliced at an invisible edge.
     */
    var dragMovesWindow = false

    /** Receives (dx, dy) increments while the window follows the finger. */
    var onDragMove: ((Int, Int) -> Unit)? = null

    /** Offset already handed to the window, so only the change is sent. */
    private var sentX = 0
    private var sentY = 0
    private var dragAnimator: ValueAnimator? = null
    private var dragWindowOpen = false

    /**
     * Room the window has been grown by so the card can move without being cut off at the edge,
     * and the card height to draw at while it is. A window cannot draw outside its own bounds, so
     * a drag that followed the finger used to be sliced where the window ended.
     */
    private var topPadPx = 0
    private var fixedCardH = 0

    /** The expanded media seek slider: its touch band, and the position being dragged to. */
    private val seekTouch = RectF()
    private var seekVisible = false
    private var seeking = false
    private var seekFraction = -1f

    /**
     * The AOD look: no filled card, a thin outline, the text inside it, and the progress drawn as a
     * marching checkerboard across the whole bar.
     */
    private var lineStyle = false
    private var checkerActive = false
    /** Dot tiles, keyed by colour and cell size: the bar's fill and the text use the same lattice. */
    private val dotShaders = HashMap<Long, BitmapShader>()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aodGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The AOD lattice drifts. It picks a new direction and speed every few seconds and eases into
     * it over a moment, so the dots wander instead of marching the same diagonal forever, without
     * ever changing direction in one frame.
     */
    private val driftRandom = java.util.Random()
    private var driftX = 0f
    private var driftY = 0f
    private var driftVelX = 0f
    private var driftVelY = 0f
    private var driftTargetX = 0f
    private var driftTargetY = 0f
    private var driftNextTurnAt = 0L
    private var driftLastAt = 0L
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }

    /**
     * True when this pill owns the right end of the strip and must draw the battery and signal
     * icons Android's own cluster used to show. Set only on the status bar pill in inverse mode.
     */
    private var statusGlyphs = false
    private var batteryPct = -1
    private var signalLevel = -1
    /** Custom icon order: false = battery then signal, true = signal then battery. */
    private var statusSignalFirst = false
    /** Wi-Fi, cellular, or neither: whichever connection the pill should draw (see [Networks]). */
    private var networkKind = Networks.MOBILE

    /** Last status-bar geometry that was logged, so the numbers are printed once per change. */
    private var sbGeomLogged = ""

    /** Bluetooth audio is connected, so the cluster carries the earbuds glyph. */
    private var btBuds = false

    /** Where [setStatusOnly] is heading, and the animated mix the drawing follows. */
    private var statusOnlyTarget = false
    private var statusOnlyMix = 0f
    private var statusOnlyAnimator: ValueAnimator? = null

    /** 0 = full colour, 1 = greyscale. The AOD asks for 1 and the wake fades it back. */
    private var gray = 0f
    private var grayAnimator: ValueAnimator? = null
    private val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * A context carrying the app's Material 3 theme, wrapped with the wallpaper palette. A
     * service's own context often has no Material theme, which silently made every colour role
     * fall back to the old dark pill.
     */
    private val themeContext: Context = try {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_BBBar)
        com.google.android.material.color.DynamicColors.wrapContextIfAvailable(themed)
    } catch (t: Throwable) {
        context
    }

    init {
        applyTheme()
    }

    /** Fires for a tap that was not a swipe or a control press. */
    var onTap: (() -> Unit)? = null

    /**
     * A drag started, and the window should be grown around the card for the duration; fired again
     * with the drag over once the card is back in its resting place. The owner moves the window, the
     * view only asks.
     */
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    /** Surfaces that ignore swipes (the status bar pill) must not follow the finger at all. */
    var dragEnabled = true

    /** `dx`/`dy` are -1, 0 or 1; the service decides what the gesture means per surface. */
    var onSwipe: ((Int, Int) -> Unit)? = null

    /** "prev" | "playpause" | "next" from the expanded media controls. */
    var onControl: ((String) -> Unit)? = null

    /** Where the seek slider was released, as a 0..1 fraction of the track. */
    var onSeek: ((Float) -> Unit)? = null

    /**
     * Fired when the user taps anywhere outside this window. Needs
     * [WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH] on the window.
     */
    var onOutsideTouch: (() -> Unit)? = null

    private val frontItem: NowBarItem? get() = items.firstOrNull()

    /**
     * The width the current single line actually needs, capped at [maxWidthPx]. The status bar pill
     * uses it so a short line (or the idle label) does not sit in a long empty pill: the card is
     * cut off from the right, which is the same edge the text is ellipsized at.
     */
    fun preferredWidth(maxWidthPx: Int, heightPx: Int): Int {
        return try {
            // Measure mode shows the tuned shape, not the line: fitting it to the label would
            // hide the width that is being dialled in.
            if (outlineOnly) return maxWidthPx
            val h = heightPx.toFloat()
            if (h <= 0f) return maxWidthPx
            val r = minOf(dp(radiusOverrideDp), h / 2f)
            val pad = (dp(11f) + r * 0.12f).coerceAtMost(h / 3f)
            // The lock screen's pill carries no line, so it is only as wide as its glyphs.
            if (statusOnlyTarget) {
                val iconOnly = (pad * 2f + statusClusterWidth(h) + dp(1f)).toInt()
                val floor = minOf(heightPx, maxWidthPx)
                return iconOnly.coerceIn(floor, maxWidthPx)
            }
            val glyphSize = (h - pad * 2f).coerceAtMost(dp(34f)).coerceAtLeast(dp(14f))
            val item = frontItem
            val text = when {
                item == null -> label
                item.kind == ItemKind.TIMER && item.subtitle.isNotBlank() -> item.subtitle
                else -> item.title.takeIf { it.isNotBlank() } ?: label
            }
            val textW = if (text.isEmpty()) 0f else titlePaint.measureText(text)
            // Only the cards that actually draw a leading glyph give up room to it: media
            // draws the player's icon, the rest draw their own mark or notification icon.
            val iconW = when {
                item == null -> 0f
                item.kind == ItemKind.MEDIA ->
                    if (isKdeConnect(item.packageName.orEmpty()) ||
                        appIcon(item.packageName.orEmpty()) != null
                    ) glyphSize + dp(9f) else 0f
                item.kind == ItemKind.TIMER || item.kind == ItemKind.ALARM ||
                    item.kind == ItemKind.LIVE -> glyphSize + dp(9f)
                item.art != null -> glyphSize + dp(9f)
                else -> 0f
            }
            // Room for the rule and the gap on each side of it. The line's side keeps its extra
            // margin out of this width, so adding it moves the rule away from the line instead of
            // towards the icons.
            val clusterW = if (statusGlyphs) {
                statusClusterWidth(h) + statusDividerWidth() +
                    dp(STATUS_DIVIDER_GAP_DP) * 2f
            } else {
                0f
            }
            val wanted = pad + iconW + textW + clusterW + pad + dp(1f)
            // A line that does not fit keeps the tuned width: it scrolls inside it. Shrinking
            // there would leave a tiny pill scrolling a long line, which is the one thing this
            // is not for. A line that does fit shrinks the pill down to it, as far as a circle -
            // but never so far that the trailing battery/signal cluster is cut.
            if (wanted >= maxWidthPx) return maxWidthPx
            val minW = maxOf(heightPx, (pad * 2f + clusterW).toInt()).coerceAtMost(maxWidthPx)
            wanted.toInt().coerceIn(minW, maxWidthPx)
        } catch (t: Throwable) {
            Log.w("PillView.preferredWidth failed", t)
            maxWidthPx
        }
    }

    /**
     * How tall the card actually needs to be for the front item's content, capped at
     * [maxHeightPx]. The expanded popup uses this so it hugs a single-line card instead of
     * leaving a band of empty card under it, while a two-line media card keeps the room it needs.
     */
    fun preferredHeight(maxHeightPx: Int): Int {
        return try {
            if (maxHeightPx <= 0) return maxHeightPx
            // Measure mode keeps the full tuned height, even for a one-line card.
            if (outlineOnly) return maxHeightPx
            val item = frontItem ?: return maxHeightPx
            val h = maxHeightPx.toFloat()
            val r = minOf(if (radiusOverrideDp > 0f) dp(radiusOverrideDp) else h / 2f, h / 2f)
            val pad = (dp(11f) + r * 0.12f).coerceAtMost(h / 3f)
            val glyphSize = (h - pad * 2f).coerceAtMost(dp(34f)).coerceAtLeast(dp(14f))
            val controlSize = glyphSize.coerceAtMost(dp(26f)).coerceAtLeast(dp(16f))
            val subtitle = item.subtitle
            val lines = titlePaint.textSize +
                (if (expanded && subtitle.isNotBlank()) dp(1f) + subtitlePaint.textSize else 0f)
            val seek = if (expanded && item.kind == ItemKind.MEDIA && item.durationMs > 0L) {
                dp(SEEK_ROW_DP)
            } else {
                0f
            }
            val needed = maxOf(lines + seek, controlSize, glyphSize) + pad * 2f + dp(1f)
            needed.toInt().coerceIn(dp(36f).toInt(), maxHeightPx)
        } catch (t: Throwable) {
            Log.w("PillView.preferredHeight failed", t)
            maxHeightPx
        }
    }

    /**
     * Measure mode: draw only the card's outline - no fill, artwork, text, glyphs or progress -
     * so the size and place the settings dial in can be judged on the real screen. The surfaces
     * stay up with nothing active, because the shape is the whole point there.
     */
    var outlineOnly = false

    /** Switches the AOD look on or off. The service sets it with the display, not with the theme. */
    fun setLineStyle(on: Boolean) {
        try {
            if (lineStyle == on) return
            lineStyle = on
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.setLineStyle threw", t)
        }
    }

    /**
     * Fades the whole card to greyscale. The always-on display asks for 1; waking fades it back
     * quickly, so the pill never pops from grey to colour in one frame.
     */
    fun setGray(target: Float, durationMs: Long) {
        try {
            val to = target.coerceIn(0f, 1f)
            grayAnimator?.cancel()
            if (durationMs <= 0L || abs(to - gray) < 0.01f) {
                gray = to
                invalidate()
                return
            }
            val anim = ValueAnimator.ofFloat(gray, to).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.2f)
                addUpdateListener {
                    gray = it.animatedValue as Float
                    invalidate()
                }
            }
            grayAnimator = anim
            anim.start()
        } catch (t: Throwable) {
            Log.w("PillView.setGray failed", t)
        }
    }

    /**
     * The inverse layout's own battery and signal glyphs: on when the pill sits over Android's
     * system icon cluster, with the values that cluster used to show. -1 means "unknown", which
     * draws an empty battery / empty bars rather than nothing at all.
     */
    fun setStatusGlyphs(
        on: Boolean,
        battery: Int,
        signal: Int,
        signalFirst: Boolean = false,
        network: Int = Networks.MOBILE,
        buds: Boolean = false
    ) {
        try {
            val b = battery.coerceIn(-1, 100)
            val s = signal.coerceIn(-1, 4)
            if (on == statusGlyphs && b == batteryPct && s == signalLevel &&
                signalFirst == statusSignalFirst && network == networkKind && buds == btBuds
            ) {
                return
            }
            statusGlyphs = on
            batteryPct = b
            signalLevel = s
            statusSignalFirst = signalFirst
            networkKind = network
            btBuds = buds
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.setStatusGlyphs threw", t)
        }
    }

    /**
     * The lock screen's pill: the glyphs only, in the middle of the shape, with the line faded out.
     *
     * The bottom card already carries the activity there, so the strip just stands in for the
     * phone's own battery / signal / Bluetooth icons. The change is animated rather than swapped,
     * so locking morphs the pill instead of replacing it.
     */
    fun setStatusOnly(on: Boolean) {
        try {
            if (on == statusOnlyTarget) return
            statusOnlyTarget = on
            cancelStatusOnlyAnimator()
            val from = statusOnlyMix
            val to = if (on) 1f else 0f
            val anim = ValueAnimator.ofFloat(from, to).apply {
                duration = STATUS_ONLY_MS
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    statusOnlyMix = it.animatedValue as Float
                    invalidate()
                }
            }
            statusOnlyAnimator = anim
            anim.start()
        } catch (t: Throwable) {
            Log.w("PillView.setStatusOnly threw", t)
        }
    }

    private fun cancelStatusOnlyAnimator() {
        try {
            statusOnlyAnimator?.cancel()
        } catch (t: Throwable) {
            Log.w("status-only animator cancel failed", t)
        }
        statusOnlyAnimator = null
    }

    /** Applies the measure-mode outline (see [outlineOnly]). */
    fun setOutlineMode(on: Boolean) {
        try {
            if (outlineOnly == on) return
            outlineOnly = on
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.setOutlineMode threw", t)
        }
    }

    /** Height of the trailing glyphs: small enough to fit the 27 dp status pill. */
    private fun statusIconHeight(cardH: Float): Float =
        (cardH * 0.44f).coerceIn(dp(7f), dp(13f))

    /** Width the network glyph takes: Wi-Fi, the cellular bars, or nothing at all. */
    private fun statusNetworkWidth(cardH: Float): Float {
        val iconH = statusIconHeight(cardH)
        return when (networkKind) {
            Networks.WIFI -> iconH * 1.1f
            Networks.MOBILE -> iconH * 0.95f
            else -> 0f
        }
    }

    /** Width the earbuds take, or nothing when no Bluetooth audio device is connected. */
    private fun statusBudsWidth(cardH: Float): Float =
        if (btBuds) statusIconHeight(cardH) * 1.05f else 0f

    /** Width the buds + battery + network cluster takes, for fitting the pill to its line. */
    private fun statusClusterWidth(cardH: Float): Float {
        val iconH = statusIconHeight(cardH)
        val netW = statusNetworkWidth(cardH)
        val gap = if (netW > 0f) (iconH * 0.4f).coerceAtLeast(dp(2f)) else 0f
        val budsW = statusBudsWidth(cardH)
        val budsGap = if (budsW > 0f) (iconH * 0.4f).coerceAtLeast(dp(2f)) else 0f
        return budsW + budsGap + iconH * 1.5f + gap + netW
    }

    /**
     * A pair of earbuds, the way the strip draws them: two buds, each a round body with a short
     * stem, mirrored so they read as a set rather than as two blobs.
     */
    private fun drawBuds(canvas: Canvas, area: RectF) {
        try {
            if (area.width() <= 0f || area.height() <= 0f) return
            val h = area.height()
            val bodyR = h * 0.21f
            val stemW = bodyR * 1.15f
            val bodyCy = area.top + h * 0.40f
            val stemBottom = area.top + h * 0.94f
            // On the AOD the buds are line work like everything else there.
            val paint = if (lineStyle) statusStroke else statusFill
            for (dir in intArrayOf(-1, 1)) {
                val cx = area.centerX() + dir * area.width() * 0.20f
                canvas.drawCircle(cx, bodyCy, bodyR, paint)
                val stemLeft = cx + dir * bodyR * 0.15f - stemW / 2f
                statusStem.set(stemLeft, bodyCy, stemLeft + stemW, stemBottom)
                canvas.drawRoundRect(statusStem, stemW / 2f, stemW / 2f, paint)
            }
        } catch (t: Throwable) {
            Log.w("PillView.drawBuds failed", t)
        }
    }

    /** Room the separator between the line and the cluster takes. */
    private fun statusDividerWidth(): Float = dp(STATUS_DIVIDER_W_DP)

    /**
     * Right edge of the line's ink, for placing the separator by what the eye sees.
     *
     * The advance width includes the last glyph's trailing bearing, which is empty space, so a
     * margin measured from it is wider on screen than the same margin taken from the glyphs.
     */
    private fun textInkRight(text: String, left: Float, advanceEnd: Float): Float {
        if (text.isEmpty()) return advanceEnd
        return try {
            val bounds = Rect()
            titlePaint.getTextBounds(text, 0, text.length, bounds)
            (left + bounds.right).coerceIn(left, advanceEnd)
        } catch (t: Throwable) {
            advanceEnd
        }
    }

    /** A battery outline with the charge inside it, ending in a small nub. */
    private fun drawBattery(canvas: Canvas, area: RectF, pct: Int) {
        try {
            if (area.width() <= 0f || area.height() <= 0f) return
            statusStroke.strokeWidth = (area.height() * 0.11f).coerceAtLeast(dp(0.9f))
            val nubW = (area.height() * 0.16f).coerceAtLeast(dp(1.2f))
            val radius = area.height() * 0.24f
            val body = RectF(area.left, area.top, area.right - nubW - dp(0.5f), area.bottom)
            canvas.drawRoundRect(body, radius, radius, statusStroke)
            val nubTop = area.centerY() - area.height() * 0.21f
            val nubBottom = area.centerY() + area.height() * 0.21f
            // On the AOD everything is drawn as line work, so even the nub is an outline.
            canvas.drawRoundRect(
                RectF(body.right + dp(0.5f), nubTop, area.right, nubBottom),
                dp(0.6f),
                dp(0.6f),
                if (lineStyle) statusStroke else statusFill
            )
            if (pct >= 0) {
                val inset = body.height() * 0.24f
                val innerW = (body.width() - inset * 2f).coerceAtLeast(0f)
                val fillW = innerW * (pct.coerceIn(0, 100) / 100f)
                if (fillW > 0.5f) {
                    val charge = RectF(
                        body.left + inset,
                        body.top + inset,
                        body.left + inset + fillW,
                        body.bottom - inset
                    )
                    if (lineStyle) {
                        // The AOD draws the charge as dots, packed far tighter than the pill's own
                        // lattice: it is a fill, not a texture.
                        val cell = (area.height() * 0.16f)
                            .coerceIn(dp(1.2f), dp(3f))
                        drawDottedFill(canvas, charge, cell)
                    } else {
                        val r = (radius * 0.6f).coerceAtLeast(0.5f)
                        canvas.drawRoundRect(charge, r, r, statusFill)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("PillView battery glyph failed", t)
        }
    }

    /**
     * Fills [area] with the dot lattice instead of a solid tone: the AOD is line work, and the
     * battery's charge is the one place that needs a fill rather than an outline.
     */
    private fun drawDottedFill(canvas: Canvas, area: RectF, cell: Float) {
        try {
            if (area.width() <= 0f || area.height() <= 0f) return
            val layer = canvas.saveLayer(area, null)
            canvas.clipRect(area)
            dotPaint.shader = dotShader(cell, statusFill.color)
            dotPaint.shader!!.setLocalMatrix(dotPhaseMatrix())
            canvas.drawRect(area, dotPaint)
            dotPaint.shader = null
            canvas.restoreToCount(layer)
        } catch (t: Throwable) {
            Log.w("PillView dotted fill failed", t)
        }
    }

    /** The connection glyph: Wi-Fi when on Wi-Fi, bars for mobile data, nothing when offline. */
    private fun drawNetwork(canvas: Canvas, area: RectF) {
        when (networkKind) {
            Networks.WIFI -> drawWifi(canvas, area)
            Networks.MOBILE -> drawCellular(canvas, area, signalLevel)
        }
    }

    /** The Wi-Fi fan: three nested arcs opening upwards, with a dot under them. */
    private fun drawWifi(canvas: Canvas, area: RectF) {
        try {
            if (area.width() <= 0f || area.height() <= 0f) return
            statusStroke.strokeWidth = (area.height() * 0.11f).coerceAtLeast(dp(0.9f))
            val cx = area.centerX()
            // The fan's origin sits low in the box, so all three arcs fit above it.
            val cy = area.bottom - area.height() * 0.12f
            val rMax = area.width() * 0.52f
            for (i in 0..2) {
                val r = rMax * (0.42f + i * 0.29f)
                canvas.drawArc(
                    RectF(cx - r, cy - r, cx + r, cy + r),
                    225f,
                    90f,
                    false,
                    statusStroke
                )
            }
            canvas.drawCircle(cx, cy - area.height() * 0.02f, rMax * 0.13f, statusFill)
        } catch (t: Throwable) {
            Log.w("PillView wifi glyph failed", t)
        }
    }

    /** Four rising bars, the filled ones showing [level]; -1 draws all of them dimmed. */
    private fun drawCellular(canvas: Canvas, area: RectF, level: Int) {
        try {
            if (area.width() <= 0f || area.height() <= 0f) return
            val bars = 4
            val gap = area.width() * 0.12f
            val barW = ((area.width() - gap * (bars - 1)) / bars).coerceAtLeast(dp(1f))
            val lit = if (level < 0) 0 else (level + 1).coerceIn(0, bars)
            for (i in 0 until bars) {
                val fraction = 0.34f + 0.66f * (i.toFloat() / (bars - 1))
                val barH = area.height() * fraction
                val left = area.left + i * (barW + gap)
                val rect = RectF(left, area.bottom - barH, left + barW, area.bottom)
                val r = barW * 0.34f
                if (lineStyle) {
                    // AOD: bars are outlines, only the lit ones carry the glyph colour.
                    val paint = if (i < lit) statusStroke else statusDimStroke
                    canvas.drawRoundRect(rect, r, r, paint)
                } else {
                    canvas.drawRoundRect(rect, r, r, if (i < lit) statusFill else statusDim)
                }
            }
        } catch (t: Throwable) {
            Log.w("PillView cellular glyph failed", t)
        }
    }

    /**
     * Colours come from Material colour roles: surface-variant for the card, on-surface for text
     * and glyphs, primary for the progress fill, outline for the hairline.
     */
    private fun applyTheme() {
        try {
            val surface = attrColor(com.google.android.material.R.attr.colorSurfaceVariant, Color.parseColor("#FF111117"))
            val onSurface = attrColor(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
            val primary = attrColor(androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
            // Material 3 roles: cards sit on a tonal surface container, the outline is the
            // scheme's own outline colour, and progress uses primary on secondary-container.
            fill.color = attrColor(
                com.google.android.material.R.attr.colorSurfaceContainer,
                attrColor(com.google.android.material.R.attr.colorSurface, surface)
            )
            stackFill.color = attrColor(
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                attrColor(com.google.android.material.R.attr.colorSurfaceVariant, surface)
            )
            stroke.color = withAlpha(
                attrColor(com.google.android.material.R.attr.colorOutline, onSurface),
                0x59
            )
            titlePaint.color = onSurface
            subtitlePaint.color = withAlpha(onSurface, 0xB3)
            progressTrack.color = withAlpha(
                attrColor(com.google.android.material.R.attr.colorSecondaryContainer, onSurface),
                0x80
            )
            progressFill.color = primary
            glyph.color = onSurface
            glyphFill.color = onSurface
            statusStroke.color = onSurface
            statusFill.color = onSurface
            statusDim.color = withAlpha(onSurface, 0x3D)
            statusDimStroke.color = withAlpha(onSurface, 0x3D)
            statusDimStroke.strokeWidth = statusStroke.strokeWidth
            tintLayer.color = withAlpha(primary, 0x8C)
            scrim.color = withAlpha(surface, 0xCC)
        } catch (t: Throwable) {
            Log.w("PillView theme resolve failed", t)
        }
    }

    private fun attrColor(attr: Int, fallback: Int): Int {
        return try {
            val attrs = themeContext.theme.obtainStyledAttributes(intArrayOf(attr))
            val color = attrs.getColor(0, fallback)
            attrs.recycle()
            color
        } catch (t: Throwable) {
            fallback
        }
    }

    fun setStyle(values: Tuning.Values) = setStyle(values.label, values.textDp, values.radiusDp)

    fun setStyle(text: String, textDp: Int, radiusDp: Int) {
        try {
            if (items.isEmpty()) label = text
            this.textDp = textDp
            titlePaint.textSize = dp(textDp.toFloat())
            radiusOverrideDp = radiusDp.toFloat()
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.setStyle threw", t)
        }
    }

    fun setItem(item: NowBarItem?, expanded: Boolean) =
        setItems(if (item == null) emptyList() else listOf(item), expanded, 0)

    /**
     * Front item first. [peekPx] is how far each card behind the front one shows; the window has
     * already been grown by `peekPx * (n - 1)` to make room.
     */
    /**
     * Draws the card at [cardHeightPx], inset [topPadPx] from the top of a window that has been
     * grown for a drag. `(0, 0)` returns to following the window.
     */
    fun setFixedBox(cardHeightPx: Int, topPadPx: Int) {
        try {
            fixedCardH = cardHeightPx.coerceAtLeast(0)
            this.topPadPx = topPadPx.coerceAtLeast(0)
            // The window is closing up around the card again. Any leftover drag offset would draw
            // the card outside those bounds - which is exactly how it used to vanish, because a
            // window clips to itself and a cancelled spring left the offset behind.
            if (this.topPadPx == 0) {
                dragAnimator?.cancel()
                dragAnimator = null
                dragX = 0f
                dragY = 0f
                dragAxis = 0
            }
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.setFixedBox threw", t)
        }
    }

    /** Freezes the drawn content at [px] height (bottom anchored); 0 follows the window. */
    fun setContentHeight(px: Int) {
        try {
            contentHeightPx = px.coerceAtLeast(0)
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.setContentHeight threw", t)
        }
    }

    /**
     * Swipe transition: the new card slides in from the side the finger came from and settles.
     * [vertical] is -1 for a swipe up (new card rises from below) and 1 for a swipe down.
     */
    fun animateSwap(vertical: Int) {
        try {
            slideAnimator?.cancel()
            val dist = (height * 0.3f).coerceAtLeast(dp(6f))
            val from = if (vertical < 0) dist else -dist
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200L
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    slidePx = from * (1f - f)
                    invalidate()
                }
            }
            slideAnimator = anim
            anim.start()
        } catch (t: Throwable) {
            Log.w("PillView.animateSwap threw", t)
        }
    }

    fun setItems(newItems: List<NowBarItem>, expanded: Boolean, peekPx: Int) {
        try {
            val previous = items.firstOrNull()
            val next = newItems.firstOrNull()
            // A front-card change is a card change whoever caused it: a swipe already slides
            // (animateSwap), so this covers the ones that arrive from content - the track ending,
            // the timer finishing, the next card taking the front.
            if (slideAnimator?.isRunning != true && previous != null && next != null &&
                previous.id != next.id
            ) {
                animateSwap(1)
            }
            // Only a real content change restarts the line. The notification store rebuilds every
            // item about once a second while a player is running, and each rebuild hands over a
            // fresh Bitmap for the same cover, so identity (`!==`) used to read that as "next
            // track" and restart the scroll while the same song was playing.
            val contentChanged = previous != null && next != null &&
                (previous.id != next.id ||
                    previous.title != next.title ||
                    previous.subtitle != next.subtitle ||
                    !sameArt(previous.art, next.art))
            this.items = newItems
            this.expanded = expanded
            this.peekPx = peekPx.coerceAtLeast(0)
            // A new line starts its scroll from the beginning, not mid-stride.
            if (contentChanged) {
                Log.i("content changed: '${next?.title}' '${next?.subtitle}'")
                marqueeStarts.clear()
            }
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.setItems threw", t)
        }
    }

    /**
     * True when both covers show the same pixels. The store re-decodes the artwork on every poll,
     * so the instance almost never matches even though nothing on screen changed.
     */
    private fun sameArt(a: Bitmap?, b: Bitmap?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.isRecycled || b.isRecycled) return false
        if (a.width != b.width || a.height != b.height) return false
        return try {
            a.sameAs(b)
        } catch (t: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        marqueeActive = false
        checkerActive = false
        seekVisible = false
        seekTouch.setEmpty()
        // Greyscale is a layer filter over the finished card, so the artwork, the text and the
        // controls all lose their colour together instead of each paint being rewritten.
        var layer = -1
        try {
            if (gray > 0.004f) {
                val matrix = ColorMatrix().apply { setSaturation(1f - gray) }
                grayPaint.colorFilter = ColorMatrixColorFilter(matrix)
                layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), grayPaint)
            }
        } catch (t: Throwable) {
            Log.w("PillView grey layer failed", t)
            layer = -1
        }
        canvas.save()
        try {
            // The AOD lattice moves: step it once per frame and keep the frame clock going.
            if (lineStyle) {
                checkerActive = true
                advanceDrift(aodDotCell())
            }
            val drawH = when {
                fixedCardH > 0 -> fixedCardH.toFloat()
                contentHeightPx > 0 -> contentHeightPx.toFloat()
                else -> height.toFloat()
            }
            // Bottom anchored while collapsing: the shrinking window crops the card from the top
            // rather than re-laying out its contents, so nothing is squished.
            if (contentHeightPx > 0) canvas.translate(0f, (height - contentHeightPx).toFloat())
            // Everything below is in card coordinates, so one translate puts the card where the
            // drag has taken it inside the grown window.
            if (topPadPx > 0) canvas.translate(0f, topPadPx.toFloat())
            if (slidePx != 0f) canvas.translate(0f, slidePx)
            if (!dragMovesWindow && (dragX != 0f || dragY != 0f)) canvas.translate(dragX, dragY)

            val count = items.size.coerceAtLeast(1)
            val stacked = minOf(((count - 1) * peekPx).toFloat(), drawH * 0.45f)
            val frontH = (drawH - stacked).coerceAtLeast(dp(8f))
            val radius = if (radiusOverrideDp > 0f) dp(radiusOverrideDp) else frontH / 2f
            if (!loggedFirstDraw) {
                loggedFirstDraw = true
                Log.i(
                    "PillView drew: ${width}x${height} frontH=${frontH.toInt()} cards=$count " +
                        "expanded=$expanded title='${frontItem?.title}'"
                )
            }

            // Cake layers, seen from above: every card is the same width and shifted down by one
            // band, so each hidden card shows a strip of itself. The strip carries the card's own
            // text, which is all a minimised card shows.
            val band = if (count > 1) (stacked / (count - 1)).coerceAtLeast(0f) else 0f
            // Every hidden card is its own short row underneath the front card, text only. The row
            // is exactly one band tall, so nothing is sliced off by the card above it.
            if (band >= dp(8f)) {
                for (j in count - 1 downTo 1) {
                    val backItem = items.getOrNull(j) ?: continue
                    val top = frontH + band * (j - 1)
                    val bottom = (top + band - dp(2f)).coerceAtMost(drawH)
                    if (bottom - top < dp(6f)) continue
                    backCard.set(dp(1f), top, width.toFloat() - dp(1f), bottom)
                    val backRadius = minOf(radius, (bottom - top) / 2f)
                    if (outlineOnly) {
                        outline.color = titlePaint.color
                        canvas.drawRoundRect(backCard, backRadius, backRadius, outline)
                        continue
                    }
                    canvas.drawRoundRect(backCard, backRadius, backRadius, stackFill)
                    canvas.drawRoundRect(backCard, backRadius, backRadius, stroke)

                    queuePaint.textSize = ((bottom - top) * 0.46f)
                        .coerceIn(dp(7f), dp(12f))
                    queuePaint.color = withAlpha(subtitlePaint.color, 0xF0)
                    val stripLabel = backItem.title.ifBlank { backItem.subtitle }
                    val baseline = top + (bottom - top) / 2f -
                        (queuePaint.descent() + queuePaint.ascent()) / 2f
                    paintEllipsized(
                        canvas,
                        stripLabel,
                        queuePaint,
                        dp(12f),
                        baseline,
                        width - dp(24f)
                    )
                }
            }

            card.set(dp(1f), dp(1f), width.toFloat() - dp(1f), frontH - dp(1f))
            drawFront(canvas, card, radius, frontItem)
        } catch (t: Throwable) {
            Log.w("PillView.onDraw threw", t)
        } finally {
            canvas.restore()
            if (layer >= 0) canvas.restoreToCount(layer)
            // Keep the frame clock running only while something is actually moving: a scrolling
            // line, or the AOD's checkerboard marching across the bar.
            if (marqueeActive || checkerActive) postInvalidateOnAnimation()
        }
    }

    private fun drawFront(canvas: Canvas, area: RectF, radius: Float, item: NowBarItem?) {
        val w = area.width()
        val h = area.height()
        if (w <= 0f || h <= 0f) return
        val r = minOf(radius, h / 2f)

        // Measure mode: the shape, and nothing inside it.
        if (outlineOnly) {
            outline.color = titlePaint.color
            canvas.drawRoundRect(area, r, r, outline)
            return
        }

        // The shape stays even with nothing active: the status pill is what stands in for
        // Android's battery and signal icons, so it can never be the thing that disappears. The
        // idle label is the only text there, and a blank label (or the suppression before the
        // first unlock) simply leaves the shape and its glyphs without a line.

        clipToCard(canvas, area, r)
        val art = item?.art
        if (lineStyle) {
            // AOD look: no card behind the content, the bar itself is the progress.
            drawAodFill(canvas, area, item?.progress ?: -1f)
        } else {
            canvas.drawColor(fill.color)
            if (item != null && item.kind == ItemKind.MEDIA && art != null) {
                drawArtBackground(canvas, area, art)
            }
        }
        canvas.restore()
        if (lineStyle) {
            outline.color = titlePaint.color
            canvas.drawRoundRect(area, r, r, outline)
        } else {
            canvas.drawRoundRect(area, r, r, stroke)
        }

        clipToCard(canvas, area, r)

        val pad = (dp(11f) + r * 0.12f).coerceAtMost(h / 3f)
        val glyphSize = (h - pad * 2f).coerceAtMost(dp(34f)).coerceAtLeast(dp(14f))
        // The progress bar hugs the bottom edge of the card - two or three pixels of it - so it
        // also fits the short status bar pill (27 dp) without touching the line of text, which
        // keeps the full height to centre itself in.
        val dense = h < dp(44f)
        val barH = if (dense) dp(2f) else dp(3f)
        // No strip of progress under the lock screen's icon-only pill: it carries the phone's
        // status icons and nothing else there.
        val showBar = item != null && item.progress >= 0f &&
            !(statusGlyphs && statusOnlyMix > 0.5f)
        val barTop = h - barH
        val contentBottom = h

        var textLeft = pad
        var iconDrawn = false
        // Centred on the content band (which loses room to the progress bar when there is one),
        // not parked at the top padding: the icon used to sit visibly high in the pill.
        val glyphTop = ((contentBottom - glyphSize) / 2f).coerceAtLeast(0f)
        val glyphRect = RectF(pad, glyphTop, pad + glyphSize, glyphTop + glyphSize)
        // The lock screen's icon-only pill has no room for a leading glyph - the shape narrows
        // around the status icons - so it goes with the line.
        val hideLeading = statusGlyphs && statusOnlyMix > 0.5f
        // The AOD draws the leading glyph as one-bit art: every pixel is rounded to black or
        // white, and the dot lattice flips the pixels it covers. The mark inverts in dots instead
        // of turning into a solid block, and it works the same for a bitmap, the play button and
        // the clock - nothing is special-cased.
        val aodBmp = if (lineStyle && !hideLeading) ensureAodGlyphBuffer(glyphRect) else null
        val aodCanvas = if (aodBmp != null) aodGlyphCanvas else null
        if (aodBmp != null && aodCanvas != null) {
            aodBmp.eraseColor(Color.TRANSPARENT)
            val off = aodCanvas
            val save = off.save()
            try {
                off.translate(-glyphRect.left, -glyphRect.top)
                iconDrawn = drawLeadingGlyph(off, glyphRect, glyphSize, item, art)
            } finally {
                off.restoreToCount(save)
            }
            // Only the part the bar has already reached is dotted, exactly like the line: the
            // lattice is the progress, not a texture over the whole mark.
            aodRoundAndInvert(aodBmp, glyphRect.left, w * (item?.progress ?: 0f).coerceIn(0f, 1f))
            canvas.drawBitmap(aodBmp, glyphRect.left, glyphRect.top, null)
        } else if (!hideLeading) {
            iconDrawn = drawLeadingGlyph(canvas, glyphRect, glyphSize, item, art)
        }
        if (iconDrawn) textLeft = glyphRect.right + dp(9f)

        // Controls live in the expanded card only. Media gets the transport set Android's own
        // player shows; every other card gets the source notification's own buttons (the Clock
        // app's Pause / Add 1 min, a live update's action), so no card is expanded and inert.
        var textRight = w - pad
        controlRects.clear()
        controlKeys.clear()
        val front = item
        val keys: List<String> = when {
            !expanded || front == null -> emptyList()
            front.kind == ItemKind.MEDIA -> listOf("prev", "playpause", "next")
            else -> front.actions.take(3).indices.map { "action:$it" }
        }
        if (keys.isNotEmpty() && front != null) {
            val size = glyphSize.coerceAtMost(dp(26f)).coerceAtLeast(dp(16f))
            val gap = size * 0.3f
            val totalW = size * keys.size + gap * (keys.size - 1)
            val left = (w - pad - totalW).coerceAtLeast(pad + glyphSize)
            val cy = contentBottom / 2f
            keys.forEachIndexed { i, key ->
                val x = left + i * (size + gap)
                // Hit areas live in view coordinates, so a card inset by a grown window carries
                // that inset too.
                val rect = RectF(
                    x,
                    cy - size / 2f + topPadPx,
                    x + size,
                    cy + size / 2f + topPadPx
                )
                controlRects.add(rect)
                controlKeys.add(key)
                when (key) {
                    "prev" -> drawSkip(canvas, rect, backwards = true)
                    "next" -> drawSkip(canvas, rect, backwards = false)
                    "playpause" -> drawPlayPause(canvas, rect, playing = front.playing)
                    else -> drawActionGlyph(
                        canvas,
                        rect,
                        front.actions.getOrNull(i)?.label.orEmpty()
                    )
                }
            }
            textRight = left - dp(8f)
        }

        // The line, before the rule is placed: the gap beside it depends on where its ink ends.
        val title = when {
            item == null -> if (allowIdle) label else ""
            item.kind == ItemKind.TIMER && item.subtitle.isNotBlank() -> item.subtitle
            else -> item.title.takeIf { it.isNotBlank() } ?: label
        }

        // The inverse pill owns the end of the strip, so it draws the battery and signal icons
        // Android's cluster used to: right-aligned, then the line gets whatever is left.
        if (statusGlyphs) {
            val iconH = statusIconHeight(h)
            val batteryW = iconH * 1.5f
            val networkW = statusNetworkWidth(h)
            val gap = (iconH * 0.4f).coerceAtLeast(dp(2f))
            val clusterW = statusClusterWidth(h)
            // Right end of the strip when the pill is carrying a line; the middle of the shape when
            // it is the lock screen's icon-only pill. The morph slides between the two.
            val lineLeft = w - pad - clusterW
            val centeredLeft = (w - clusterW) / 2f
            val left = lineLeft + (centeredLeft - lineLeft) * statusOnlyMix
            val top = (h - iconH) / 2f
            // The line fades out as the glyphs take the middle, so a lock morphs rather than swaps.
            val textFade = (1f - statusOnlyMix).coerceIn(0f, 1f)
            // The separator: a short rounded rule, dimmed so it groups the glyphs without
            // competing with the line of text next to it. It is placed halfway between the end of
            // the text's ink and the glyphs, so the space reads the same on both sides - fixed
            // margins never did, because a line ends on its advance width and that carries a
            // trailing bearing wider than the space a margin was meant to leave.
            val dividerW = statusDividerWidth()
            // The line has to stop where the rule's own room starts. Clamping to the pill's padding
            // instead let a long title run underneath the glyphs, because the measured end of the
            // line was already past the cluster and the clamp had nothing left to cut.
            val lineLimit = (left - dividerW - dp(STATUS_DIVIDER_GAP_DP) * 2f)
                .coerceAtLeast(textLeft)
            textRight = minOf(textRight, lineLimit)

            val textEnd = (textLeft + titlePaint.measureText(title)).coerceAtMost(textRight)
            // The gap is measured from the line's *ink*, not from its advance width: the advance
            // carries the last glyph's trailing bearing, so the same nominal margin left a wide
            // gap on the text side and a narrow one on the glyph side. Whole pixels either side,
            // which no fixed margin could do.
            val inkEnd = textInkRight(title, textLeft, textEnd)
            val space = (left - inkEnd - dividerW).coerceAtLeast(0f)
            // Half the room on each side, worked out from the space that is actually there, so
            // the two gaps are equal in pixels however wide the pill ends up being.
            val ruleGap = (space / 2f).coerceAtLeast(0f)
            val dividerLeft = inkEnd + ruleGap
            val dividerRight = dividerLeft + dividerW
            if (sbGeomLogged != "$w/$pad/$inkEnd/$left") {
                sbGeomLogged = "$w/$pad/$inkEnd/$left"
                Log.i(
                    "sb geom w=$w pad=$pad advEnd=$textEnd inkEnd=$inkEnd " +
                        "clusterLeft=$left clusterW=${statusClusterWidth(h)} space=$space " +
                        "gapL=${dividerLeft - inkEnd} gapR=${left - dividerRight}"
                )
            }
            val dividerH = iconH * 0.9f
            val dividerTop = (h - dividerH) / 2f
            // The rule belongs to the line, so it goes with it.
            if (textFade > 0.01f) {
                statusDivider.color = withAlpha(statusFill.color, (0x66 * textFade).toInt())
                canvas.drawRoundRect(
                    RectF(dividerLeft, dividerTop, dividerRight, dividerTop + dividerH),
                    dividerW / 2f,
                    dividerW / 2f,
                    statusDivider
                )
            }
            // Earbuds lead the cluster when a Bluetooth audio device is connected, the way the
            // phone's own strip puts them beside the battery.
            var cursor = left
            val budsW = statusBudsWidth(h)
            if (budsW > 0f) {
                drawBuds(canvas, RectF(cursor, top, cursor + budsW, top + iconH))
                cursor += budsW + gap
            }
            // Which glyph leads is the owner's choice: the two swap places, the cluster does not.
            // With no connection there is no network glyph at all, just the battery.
            if (statusSignalFirst) {
                if (networkW > 0f) {
                    drawNetwork(canvas, RectF(cursor, top, cursor + networkW, top + iconH))
                }
                val batteryLeft = cursor + networkW + (if (networkW > 0f) gap else 0f)
                drawBattery(
                    canvas,
                    RectF(batteryLeft, top, batteryLeft + batteryW, top + iconH),
                    batteryPct
                )
            } else {
                drawBattery(canvas, RectF(cursor, top, cursor + batteryW, top + iconH), batteryPct)
                val networkLeft = cursor + batteryW + gap
                if (networkW > 0f) {
                    drawNetwork(
                        canvas,
                        RectF(networkLeft, top, networkLeft + networkW, top + iconH)
                    )
                }
            }
            // The line stops at its ink. The rule's extra margin comes out of the room after it,
            // never out of the line itself.
            textRight = minOf(textRight, textEnd)
        }

        val available = (textRight - textLeft).coerceAtLeast(0f)
        val subtitle = if (item?.kind == ItemKind.TIMER) "" else item?.subtitle.orEmpty()
        val showSubtitle = expanded && subtitle.isNotBlank() && h >= dp(40f) && available >= dp(40f)
        // A media card that knows its length gets a scrubber under the name, with the elapsed time
        // beside it, instead of the thin progress strip along the bottom edge.
        val showSeek = expanded && !lineStyle && item?.kind == ItemKind.MEDIA &&
            item.durationMs > 0L && h >= dp(46f) && available >= dp(70f)

        val gap = dp(1f)
        val seekRow = if (showSeek) dp(SEEK_ROW_DP) else 0f
        // The text fades with the morph, so it is scaled here rather than skipped.
        val textFade = if (statusGlyphs) (1f - statusOnlyMix).coerceIn(0f, 1f) else 1f
        val titleAlpha = titlePaint.alpha
        val subtitleAlpha = subtitlePaint.alpha
        titlePaint.alpha = (titleAlpha * textFade).toInt()
        subtitlePaint.alpha = (subtitleAlpha * textFade).toInt()
        val block = titlePaint.textSize +
            (if (showSubtitle) gap + subtitlePaint.textSize else 0f) + seekRow
        val top = ((contentBottom - block) / 2f).coerceAtLeast(dp(2f))
        val titleBaseline = top - titlePaint.ascent()
        // The dots punched through the AOD text are the *same* dots as the bar's progress, so they
        // only exist as far as the playhead has reached. Anything beyond it stays solid text.
        val aodFilledRight = w * (item?.progress ?: 0f).coerceIn(0f, 1f)
        if (lineStyle) {
            // AOD: the glyphs are the inversion of the dot lattice, so they read as part of the bar.
            paintAodText(canvas, title, titlePaint, textLeft, titleBaseline, available, aodFilledRight)
        } else {
            paintEllipsized(canvas, title, titlePaint, textLeft, titleBaseline, available)
        }
        var lineBottom = titleBaseline + titlePaint.descent()
        if (showSubtitle) {
            val subBaseline = lineBottom + gap + subtitlePaint.textSize
            if (lineStyle) {
                paintAodText(
                    canvas,
                    subtitle,
                    subtitlePaint,
                    textLeft,
                    subBaseline,
                    available,
                    aodFilledRight
                )
            } else {
                paintEllipsized(canvas, subtitle, subtitlePaint, textLeft, subBaseline, available)
            }
            lineBottom = subBaseline + subtitlePaint.descent()
        }
        if (showSeek && item != null) {
            drawSeekRow(canvas, item, textLeft, lineBottom + dp(1f), available)
        }
        titlePaint.alpha = titleAlpha
        subtitlePaint.alpha = subtitleAlpha

        // The line style draws the progress as the bar's own checkerboard instead.
        if (showBar && !seekVisible && !lineStyle && item != null) {
            val insetX = (r * 0.55f).coerceAtLeast(dp(14f))
            val trackW = w - insetX * 2f
            if (barTop > dp(2f) && trackW > barH * 2f) {
                val trackRadius = barH / 2f
                card.set(insetX, barTop, insetX + trackW, barTop + barH)
                canvas.drawRoundRect(card, trackRadius, trackRadius, progressTrack)
                val barW = trackW * item.progress.coerceIn(0f, 1f)
                if (barW > barH) {
                    card.set(insetX, barTop, insetX + barW, barTop + barH)
                    canvas.drawRoundRect(card, trackRadius, trackRadius, progressFill)
                }
            }
        }
        canvas.restore()
    }

    /**
     * The AOD bar's fill: little dots on a diagonal lattice covering the played fraction, marching
     * slowly so a still screen still reads as running. One tiled shader, so a full-width bar is one
     * draw call rather than a circle per dot.
     */
    private fun drawAodFill(canvas: Canvas, area: RectF, fraction: Float) {
        try {
            if (fraction < 0f) return
            checkerActive = true
            val filled = area.width() * fraction.coerceIn(0f, 1f)
            if (filled <= dp(2f)) return
            val cell = aodDotCell()
            val shader = dotShader(cell)
            shader.setLocalMatrix(dotPhaseMatrix())
            dotPaint.shader = shader
            canvas.drawRect(area.left, area.top, area.left + filled, area.bottom, dotPaint)
            dotPaint.shader = null
        } catch (t: Throwable) {
            Log.w("PillView AOD fill failed", t)
        }
    }

    /**
     * AOD text: solid glyphs in the text colour with the same dot lattice punched through them in
     * black, so the line is the inverse of the bar it sits in.
     */
    private fun paintAodText(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        x: Float,
        baseline: Float,
        available: Float,
        filledRight: Float
    ) {
        if (available <= 0f || text.isEmpty()) return
        try {
            val shown = if (paint.measureText(text) <= available + 0.5f) {
                text
            } else {
                TextUtils.ellipsize(text, paint, available, TextUtils.TruncateAt.END).toString()
            }
            if (shown.isEmpty()) return
            val path = Path()
            paint.getTextPath(shown, 0, shown.length, x, baseline, path)
            aodGlyphPaint.color = paint.color
            canvas.drawPath(path, aodGlyphPaint)

            val top = baseline + paint.ascent()
            val bottom = baseline + paint.descent()
            // Nothing has been played yet: the line is plain text, no holes in it.
            val punchRight = filledRight.coerceAtMost(x + available)
            if (punchRight <= x) return
            val cell = aodDotCell()
            dotPaint.shader = dotShader(cell, Color.BLACK)
            dotPaint.color = Color.BLACK
            val layer = canvas.saveLayer(x, top, punchRight, bottom, null)
            canvas.clipPath(path)
            canvas.clipRect(x, top, punchRight, bottom)
            dotPaint.shader!!.setLocalMatrix(dotPhaseMatrix())
            canvas.drawRect(x, top, punchRight, bottom, dotPaint)
            canvas.restoreToCount(layer)
            dotPaint.shader = null
        } catch (t: Throwable) {
            Log.w("PillView AOD text failed", t)
        }
    }

    /** The owner's dot spacing, in pixels. */
    private fun aodDotCell(): Float = dp(TuningStore.aodDotCellDp(context).toFloat())

    /**
     * The owner's dot diameter in pixels, capped at the spacing so bigger dots thicken the lattice
     * without fusing into a solid bar.
     */
    private fun aodDotRadius(cell: Float): Float {
        val r = dp(TuningStore.aodDotSizeDp(context).toFloat()) / 2f
        return r.coerceIn(1f, (cell / 2f).coerceAtLeast(1f))
    }

    /** One dot per cell, the cells offset like a checkerboard, which reads as a diagonal lattice. */
    private fun dotShader(cell: Float = 0f, color: Int = titlePaint.color): BitmapShader {
        val cell = if (cell > 0f) cell else aodDotCell()
        val radius = aodDotRadius(cell)
        val key = ((color.toLong() and 0xFFFFFFFFL) shl 20) or
            (cell.toLong() shl 8) or radius.toLong()
        dotShaders[key]?.let { return it }
        val size = (cell * 2f).toInt().coerceAtLeast(6)
        val s = size.toFloat()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(s * 0.25f, s * 0.25f, radius, p)
        c.drawCircle(s * 0.75f, s * 0.75f, radius, p)
        val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        dotShaders[key] = shader
        return shader
    }

    /**
     * Moves the lattice on by this frame: a random target direction every few seconds, eased into,
     * then wrapped to one tile so the offset never grows without bound.
     */
    private fun advanceDrift(cell: Float) {
        val now = SystemClock.uptimeMillis()
        val last = driftLastAt
        driftLastAt = now
        if (last == 0L) return
        val dt = (now - last).coerceIn(0L, 50L)
        if (now >= driftNextTurnAt) {
            // Slow enough to read as drifting, never as a strobe.
            val speed = dp(2f) + driftRandom.nextFloat() * dp(6f)
            val angle = driftRandom.nextDouble() * Math.PI * 2
            driftTargetX = (Math.cos(angle) * speed).toFloat()
            driftTargetY = (Math.sin(angle) * speed).toFloat()
            driftNextTurnAt = now + DRIFT_TURN_MIN_MS +
                (driftRandom.nextFloat() * (DRIFT_TURN_MAX_MS - DRIFT_TURN_MIN_MS)).toLong()
        }
        // Exponential approach to the target, frame-rate independent.
        val k = (dt / DRIFT_EASE_MS).coerceIn(0f, 1f)
        driftVelX += (driftTargetX - driftVelX) * k
        driftVelY += (driftTargetY - driftVelY) * k
        val tile = cell * 2f
        if (tile <= 0f) return
        driftX = (driftX + driftVelX * (dt / 1000f)) % tile
        driftY = (driftY + driftVelY * (dt / 1000f)) % tile
    }

    /** Both the fill and the text place the lattice at the same offset, so they stay aligned. */
    private fun dotPhaseMatrix(): Matrix =
        Matrix().apply { setTranslate(-driftX, -driftY) }

    /**
     * The expanded media scrubber. Dragging it previews a position (the time label follows the
     * thumb); releasing sends one seek through [onSeek]. The touch band is taller than the visible
     * track so it can be grabbed one-handed.
     */
    private fun drawSeekRow(
        canvas: Canvas,
        item: NowBarItem,
        left: Float,
        bandTop: Float,
        available: Float
    ) {
        try {
            val duration = item.durationMs.coerceAtLeast(1L)
            val fraction = if (seeking && seekFraction >= 0f) {
                seekFraction
            } else {
                (item.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            }
            val timeText = formatClock((duration * fraction).toLong())
            val timeW = subtitlePaint.measureText(timeText)
            val timeGap = dp(6f)
            val trackW = (available - timeW - timeGap).coerceAtLeast(dp(24f))
            val bandH = dp(SEEK_ROW_DP)
            val cy = bandTop + bandH / 2f
            val trackH = dp(3f)
            val trackTop = cy - trackH / 2f

            card.set(left, trackTop, left + trackW, trackTop + trackH)
            canvas.drawRoundRect(card, trackH / 2f, trackH / 2f, progressTrack)
            val filled = (trackW * fraction).coerceIn(0f, trackW)
            if (filled > dp(1f)) {
                card.set(left, trackTop, left + filled, trackTop + trackH)
                canvas.drawRoundRect(card, trackH / 2f, trackH / 2f, progressFill)
            }
            canvas.drawCircle(left + filled, cy, dp(4f), progressFill)

            val timeBaseline = cy - (subtitlePaint.descent() + subtitlePaint.ascent()) / 2f
            canvas.drawText(timeText, left + trackW + timeGap, timeBaseline, subtitlePaint)

            seekTouch.set(
                left - dp(6f),
                cy - dp(11f) + topPadPx,
                left + trackW + dp(6f),
                cy + dp(11f) + topPadPx
            )
            seekVisible = true
        } catch (t: Throwable) {
            Log.w("PillView seek row failed", t)
        }
    }

    private fun clipToCard(canvas: Canvas, area: RectF, radius: Float) {        artClip.reset()
        artClip.addRoundRect(area, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(artClip)
    }

    // ------------------------------------------------------------- artwork

    private fun drawArtBackground(canvas: Canvas, area: RectF, source: Bitmap) {
        val mode = TuningStore.artMode(context)
        val bitmap = if (mode == "blurred" || mode == "both") blur(source) else source
        artRect.set(area.left.toInt(), area.top.toInt(), area.right.toInt(), area.bottom.toInt())
        srcRect.set(coverRect(bitmap, area.width(), area.height()))
        try {
            canvas.drawBitmap(bitmap, srcRect, artRect, artPaint)
        } catch (t: Throwable) {
            Log.w("PillView art background failed", t)
            return
        }
        if (mode == "tinted" || mode == "both") {
            // The tint strength slider (0..100) drives the overlay alpha, so the artwork can go
            // from barely washed to fully flat. "both" keeps some blur showing through.
            val strength = TuningStore.artTintStrength(context) / 100f
            val scale = if (mode == "both") 0.75f else 1f
            tintLayer.alpha = (255f * strength * scale).toInt().coerceIn(0, 255)
            canvas.drawRect(area, tintLayer)
        }
        canvas.drawRect(area, scrim)
    }

    private fun drawCroppedArt(canvas: Canvas, art: Bitmap, dest: Rect, radius: Float) {
        val path = Path().apply { addRoundRect(RectF(dest), radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        try {
            srcRect.set(coverRect(art, dest.width().toFloat(), dest.height().toFloat()))
            canvas.drawBitmap(art, srcRect, dest, artPaint)
        } catch (t: Throwable) {
            Log.w("PillView thumbnail failed", t)
        }
        canvas.restore()
    }

    /**
     * The player's own launcher icon, next to the line so it is clear which app is playing.
     * Looked up once per package: `getApplicationIcon` goes through the package manager.
     */
    /** KDE Connect answers with a play button instead of its own icon; see the media branch. */
    private fun isKdeConnect(pkg: String): Boolean =
        pkg.startsWith(KDE_CONNECT_PKG)

    /**
     * A filled play triangle, the mark the pill shows for KDE Connect.
     *
     * Sized to the glyph slot rather than to a line of text, so it sits at the same weight as the
     * clock and the bell beside it.
     */
    private fun drawPlayButton(canvas: Canvas, area: RectF) {
        try {
            if (area.width() <= 0f || area.height() <= 0f) return
            val s = minOf(area.width(), area.height())
            val cx = area.centerX()
            val cy = area.centerY()
            val half = s * 0.30f
            val triangle = Path()
            triangle.moveTo(cx - half * 0.8f, cy - half)
            triangle.lineTo(cx + half, cy)
            triangle.lineTo(cx - half * 0.8f, cy + half)
            triangle.close()
            glyph.style = Paint.Style.FILL
            canvas.drawPath(triangle, glyph)
            glyph.style = Paint.Style.STROKE
        } catch (t: Throwable) {
            Log.w("PillView play button failed", t)
        }
    }

    private fun appIcon(pkg: String): Drawable? {
        if (pkg.isBlank()) return null
        if (appIcons.containsKey(pkg)) return appIcons[pkg]
        val icon = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (t: Throwable) {
            null
        }
        appIcons[pkg] = icon
        return icon
    }

    /** Draws a drawable into [area], clipped to a rounded square that reads as an app icon. */
    private fun drawAppIcon(canvas: Canvas, icon: Drawable, area: RectF, radius: Float) {
        canvas.save()
        try {
            drawClippedIcon(canvas, icon, area, radius)
        } catch (t: Throwable) {
            Log.w("PillView app icon failed", t)
        } finally {
            canvas.restore()
        }
    }

    private fun drawClippedIcon(canvas: Canvas, icon: Drawable, area: RectF, radius: Float) {
        artClip.reset()
        artClip.addRoundRect(area, radius, radius, Path.Direction.CW)
        canvas.clipPath(artClip)
        icon.setBounds(
            area.left.toInt(),
            area.top.toInt(),
            area.right.toInt(),
            area.bottom.toInt()
        )
        icon.draw(canvas)
    }

    /**
     * Centre-crop source rect for a destination aspect. When the two aspects are close the whole
     * image is used, because cropping a near-square cover to a near-square card would cut the
     * middle out of the artwork for no gain.
     */
    private fun coverRect(src: Bitmap, dstW: Float, dstH: Float): Rect {
        val srcAspect = src.width.toFloat() / src.height.coerceAtLeast(1)
        val dstAspect = dstW / dstH.coerceAtLeast(1f)
        if (abs(srcAspect - dstAspect) < 0.12f) return Rect(0, 0, src.width, src.height)
        return if (srcAspect > dstAspect) {
            val w = (src.height * dstAspect).toInt().coerceIn(1, src.width)
            val x = (src.width - w) / 2
            Rect(x, 0, x + w, src.height)
        } else {
            val h = (src.width / dstAspect).toInt().coerceIn(1, src.height)
            val y = (src.height - h) / 2
            Rect(0, y, src.width, y + h)
        }
    }

    /**
     * Real blur: downscale, three box-blur passes per axis at low resolution, then a bilinear
     * upscale. The old 24 px round-trip was nearest-neighbour city — that is the pixelation.
     */
    private fun blur(source: Bitmap): Bitmap {
        val strength = TuningStore.blurStrength(context)
        val cached = blurSource
        val tint = TuningStore.artTintStrength(context)
        if (cached === source && blurResult != null && blurStrengthCached == strength &&
            blurTintCached == tint
        ) {
            return blurResult!!
        }
        return try {
            val smallW = (source.width / 4).coerceIn(48, 160)
            val smallH = ((smallW.toFloat() * source.height / source.width.coerceAtLeast(1)).toInt())
                .coerceIn(32, 160)
            val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
            val pixels = IntArray(smallW * smallH)
            small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
            val strength = TuningStore.blurStrength(context)
            // Radius in low-res pixels: at 160 px wide a radius of 10 still keeps shapes, where the
            // old x1.5 scaling plus extra passes flattened everything into one colour.
            val radius = strength.coerceIn(1, 12)
            val blurred = boxBlur(pixels, smallW, smallH, radius, passes = 2)
            val tiny = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
            tiny.setPixels(blurred, 0, smallW, 0, 0, smallW, smallH)
            val outW = 480
            val outH = (outW * smallH / smallW).coerceAtLeast(16)
            val out = Bitmap.createScaledBitmap(tiny, outW, outH, true)
            blurSource = source
            blurResult = out
            blurStrengthCached = strength
            blurTintCached = tint
            out
        } catch (t: Throwable) {
            Log.w("PillView blur failed", t)
            source
        }
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int, passes: Int): IntArray {
        var data = pixels
        repeat(passes.coerceIn(1, 6)) {
            data = blurPass(data, w, h, radius, horizontal = true)
            data = blurPass(data, w, h, radius, horizontal = false)
        }
        return data
    }

    private fun blurPass(src: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean): IntArray {
        val dst = IntArray(src.size)
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = radius * 2 + 1
        for (line in 0 until lines) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -radius..radius) {
                val c = src[pos(line, k.coerceIn(0, len - 1), w, horizontal)]
                sa += (c ushr 24) and 0xff
                sr += (c shr 16) and 0xff
                sg += (c shr 8) and 0xff
                sb += c and 0xff
            }
            for (k in 0 until len) {
                dst[pos(line, k, w, horizontal)] =
                    (((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div))
                val add = src[pos(line, (k + radius + 1).coerceAtMost(len - 1), w, horizontal)]
                val sub = src[pos(line, (k - radius).coerceAtLeast(0), w, horizontal)]
                sa += ((add ushr 24) and 0xff) - ((sub ushr 24) and 0xff)
                sr += ((add shr 16) and 0xff) - ((sub shr 16) and 0xff)
                sg += ((add shr 8) and 0xff) - ((sub shr 8) and 0xff)
                sb += (add and 0xff) - (sub and 0xff)
            }
        }
        return dst
    }

    private fun pos(line: Int, i: Int, w: Int, horizontal: Boolean): Int =
        if (horizontal) line * w + i else i * w + line

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    // --------------------------------------------------------------- glyphs

    private fun drawClock(canvas: Canvas, area: RectF) {
        try {
            val r = minOf(area.width(), area.height()) / 2f
            val cx = area.centerX()
            val cy = area.centerY()
            glyph.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, r * 0.82f, glyph)
            canvas.drawLine(cx, cy, cx, cy - r * 0.45f, glyph)
            canvas.drawLine(cx, cy, cx + r * 0.38f, cy + r * 0.1f, glyph)
        } catch (t: Throwable) {
            Log.w("PillView clock failed", t)
        }
    }

    private fun drawBell(canvas: Canvas, area: RectF) {
        try {
            val r = minOf(area.width(), area.height()) / 2f
            val cx = area.centerX()
            val cy = area.centerY()
            glyph.style = Paint.Style.STROKE
            val dome = RectF(cx - r * 0.7f, cy - r * 0.78f, cx + r * 0.7f, cy + r * 0.62f)
            canvas.drawArc(dome, 195f, 150f, false, glyph)
            canvas.drawLine(cx - r * 0.74f, cy + r * 0.45f, cx + r * 0.74f, cy + r * 0.45f, glyph)
            canvas.drawCircle(cx, cy + r * 0.68f, r * 0.13f, glyphFill)
        } catch (t: Throwable) {
            Log.w("PillView bell failed", t)
        }
    }

    private fun drawLiveDot(canvas: Canvas, area: RectF) {
        try {
            val r = minOf(area.width(), area.height()) / 2f
            val cx = area.centerX()
            val cy = area.centerY()
            glyph.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, r * 0.8f, glyph)
            canvas.drawCircle(cx, cy, r * 0.34f, glyphFill)
        } catch (t: Throwable) {
            Log.w("PillView live glyph failed", t)
        }
    }

    private fun drawPlayPause(canvas: Canvas, area: RectF, playing: Boolean) {
        try {
            val size = minOf(area.width(), area.height()) * 0.58f
            val cx = area.centerX()
            val cy = area.centerY()
            if (playing) {
                val barW = size * 0.28f
                val gap = size * 0.22f
                card.set(cx - gap / 2f - barW, cy - size / 2f, cx - gap / 2f, cy + size / 2f)
                canvas.drawRoundRect(card, barW / 3f, barW / 3f, glyphFill)
                card.set(cx + gap / 2f, cy - size / 2f, cx + gap / 2f + barW, cy + size / 2f)
                canvas.drawRoundRect(card, barW / 3f, barW / 3f, glyphFill)
            } else {
                val path = Path().apply {
                    moveTo(cx - size * 0.3f, cy - size / 2f)
                    lineTo(cx + size * 0.46f, cy)
                    lineTo(cx - size * 0.3f, cy + size / 2f)
                    close()
                }
                canvas.drawPath(path, glyphFill)
            }
        } catch (t: Throwable) {
            Log.w("PillView play glyph failed", t)
        }
    }

    /**
     * A source app's action drawn as a glyph, keyed off its own label: Pause, Resume, Cancel,
     * Dismiss, Snooze, +1 min and so on. Anything unrecognised falls back to its initial, which
     * is still readable at pill size.
     */
    private fun drawActionGlyph(canvas: Canvas, area: RectF, label: String) {
        try {
            val l = label.lowercase()
            val size = minOf(area.width(), area.height())
            val cx = area.centerX()
            val cy = area.centerY()
            when {
                l.contains("pause") -> {
                    val barW = size * 0.2f
                    val gap = size * 0.18f
                    card.set(cx - gap / 2f - barW, cy - size / 2f, cx - gap / 2f, cy + size / 2f)
                    canvas.drawRoundRect(card, barW / 3f, barW / 3f, glyphFill)
                    card.set(cx + gap / 2f, cy - size / 2f, cx + gap / 2f + barW, cy + size / 2f)
                    canvas.drawRoundRect(card, barW / 3f, barW / 3f, glyphFill)
                }
                l.contains("play") || l.contains("resume") || l.contains("start") ->
                    drawPlayPause(canvas, area, playing = false)
                l.contains("cancel") || l.contains("dismiss") || l.contains("stop") ||
                    l.contains("delete") || l.contains("close") -> {
                    val inset = size * 0.26f
                    canvas.drawLine(
                        cx - inset, cy - inset, cx + inset, cy + inset, glyph
                    )
                    canvas.drawLine(
                        cx - inset, cy + inset, cx + inset, cy - inset, glyph
                    )
                }
                l.contains("add") || l.contains("snooze") || l.startsWith("+") || l.contains("min") -> {
                    val arm = size * 0.34f
                    canvas.drawLine(cx - arm, cy, cx + arm, cy, glyph)
                    canvas.drawLine(cx, cy - arm, cx, cy + arm, glyph)
                }
                l.contains("next") || l.contains("skip") ->
                    drawSkip(canvas, area, backwards = false)
                else -> {
                    val letter = label.trim().take(1).uppercase()
                    if (letter.isNotEmpty()) {
                        val paint = TextPaint(subtitlePaint).apply {
                            color = glyphFill.color
                            textSize = size * 0.62f
                            textAlign = Paint.Align.CENTER
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }
                        canvas.drawText(letter, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("PillView action glyph failed", t)
        }
    }

    private fun drawSkip(canvas: Canvas, area: RectF, backwards: Boolean) {
        try {
            val size = minOf(area.width(), area.height()) * 0.5f
            val cx = area.centerX()
            val cy = area.centerY()
            val dir = if (backwards) -1f else 1f
            // The bar sits on the side the arrow points to, the way the transport glyphs read:
            // "⏮" is bar-then-left-arrow, "⏭" is right-arrow-then-bar. With the bar on the
            // opposite side both buttons showed the other one's direction.
            val barX = cx + dir * size * 0.42f
            card.set(
                barX - size * 0.07f,
                cy - size / 2f,
                barX + size * 0.07f,
                cy + size / 2f
            )
            canvas.drawRoundRect(card, size * 0.05f, size * 0.05f, glyphFill)
            val path = Path().apply {
                moveTo(cx - dir * size * 0.3f, cy - size / 2f)
                lineTo(cx + dir * size * 0.12f, cy)
                lineTo(cx - dir * size * 0.3f, cy + size / 2f)
                close()
            }
            canvas.drawPath(path, glyphFill)
        } catch (t: Throwable) {
            Log.w("PillView skip glyph failed", t)
        }
    }

    private fun paintEllipsized(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        x: Float,
        baseline: Float,
        available: Float
    ) {
        if (available <= 0f || text.isEmpty()) return
        val width = paint.measureText(text)
        if (width <= available + 0.5f) {
            // Fits: drawn plainly, and any scroll state is dropped so a line that later grows
            // starts from its beginning instead of mid-word.
            marqueeStarts.remove(text)
            canvas.drawText(text, x, baseline, paint)
            return
        }
        // Too long, with scrolling switched off: cut it with an ellipsis and leave it still.
        if (!TuningStore.scrollText(context)) {
            marqueeStarts.remove(text)
            val cut = android.text.TextUtils.ellipsize(
                text,
                paint,
                available,
                android.text.TextUtils.TruncateAt.END
            )
            canvas.drawText(cut, 0, cut.length, x, baseline, paint)
            return
        }
        // Too long: hold, slide the hidden part into view, hold again at the end, loop.
        val distance = width - available
        val started = marqueeStarts.getOrPut(text) { SystemClock.uptimeMillis() }
        if (marqueeStarts.size > 8) marqueeStarts.clear()
        val scrollMs = (distance / MARQUEE_SPEED_DP * 1000f).toLong().coerceIn(450L, 6_000L)
        val cycle = MARQUEE_HOLD_MS + scrollMs + MARQUEE_TAIL_MS
        val elapsed = (SystemClock.uptimeMillis() - started) % cycle
        val offset = when {
            elapsed < MARQUEE_HOLD_MS -> 0f
            elapsed < MARQUEE_HOLD_MS + scrollMs ->
                distance * ((elapsed - MARQUEE_HOLD_MS).toFloat() / scrollMs)
            else -> distance
        }
        marqueeActive = true
        drawScrolled(canvas, text, paint, x, baseline, available, offset, distance)
    }

    /** One scrolling line, clipped to [available] with the cut ends faded out. */
    private fun drawScrolled(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        x: Float,
        baseline: Float,
        available: Float,
        offset: Float,
        distance: Float
    ) {
        val top = baseline + paint.ascent()
        val bottom = baseline + paint.descent()
        val fade = minOf(dp(12f), available / 4f)
        val layer = canvas.saveLayer(x, top, x + available, bottom, null)
        canvas.clipRect(x, top, x + available, bottom)
        canvas.drawText(text, x - offset, baseline, paint)
        // A fade only on a side that actually has text hidden behind it.
        if (offset > 0.5f) {
            marqueeFade.shader = LinearGradient(
                x, 0f, x + fade, 0f,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(x, top, x + fade, bottom, marqueeFade)
        }
        if (offset < distance - 0.5f) {
            marqueeFade.shader = LinearGradient(
                x + available - fade, 0f, x + available, 0f,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
            )
            canvas.drawRect(x + available - fade, top, x + available, bottom, marqueeFade)
        }
        marqueeFade.shader = null
        canvas.restoreToCount(layer)
    }

    // ----------------------------------------------------------------- input

    /**
     * False while another surface owns the screen - the shade, in particular. The window is made
     * untouchable too, so this is the second line of defence rather than the only one.
     */
    var interactive = true

    /**
     * Whether a pill with no item shows the idle line. Only the status bar pill has a placeholder
     * to fall back on; the lock pill and the popup are content surfaces, and a placeholder must
     * never appear on the lock screen - not even for the first frame of a boot.
     */
    var allowIdle = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        try {
            // An idle status bar pill (placeholder text or logo) is not a control: there is
            // nothing to expand into and nothing to swipe to, so it neither reacts to a tap nor
            // wobbles under the finger.
            // Measure mode has no content yet still must be tappable, or the expanded card's
            // outline could never be looked at.
            val idle = items.isEmpty() && !outlineOnly
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    dragAxis = 0
                    dragGrip = DRAG_GRIP_START
                    lastMoveAt = event.eventTime
                    // A new touch takes over from any spring still running: leaving it to finish
                    // would fight the finger, and cancelling it without this reset is what left a
                    // stale offset behind.
                    if (dragAnimator?.isRunning == true) {
                        dragAnimator?.cancel()
                        dragAnimator = null
                        dragX = 0f
                        dragY = 0f
                        publishOffset()
                        sentX = 0
                        sentY = 0
                    }
                    seeking = seekVisible && seekTouch.contains(event.x, event.y)
                    if (seeking) {
                        seekFraction = fractionAt(event.x)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (seeking) {
                        seekFraction = fractionAt(event.x)
                        invalidate()
                        return true
                    }
                    if (idle) return true
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && dragEnabled && (abs(dx) > dp(6f) || abs(dy) > dp(6f))) {
                        dragAnimator?.cancel()
                        dragging = true
                        beginDrag()
                    }
                    if (dragging) {
                        decideDragAxis(dx, dy)
                        // Catch-up speed from the event clock, so a slow phone and a fast one feel
                        // the same.
                        val dt = (event.eventTime - lastMoveAt).coerceIn(0L, 40L)
                        lastMoveAt = event.eventTime
                        if (dragAxis != 0) {
                            dragGrip += (1f - dragGrip) *
                                (dt / DRAG_CATCHUP_MS).coerceIn(0f, 1f)
                        }
                        // Sideways drags stay a little damped even once caught up: the swipe they
                        // commit to is a skip, not a card change.
                        if (dragAxis == 0) {
                            // Still guessing: the card leans after the finger but stays rooted, with
                            // only a little give in any direction - the feel of stretching
                            // something that is attached in the middle.
                            dragX = rubberBand(dx)
                            dragY = rubberBand(dy)
                        } else {
                            // Guessed, and following freely now - both axes, not snapped to one.
                            // The direction guess only decides what a release commits to. The
                            // service keeps the moved window on screen, so no clamp belongs here:
                            // it used to be the grown window's padding, which is gone.
                            dragX = dx * dragGrip
                            dragY = dy * dragGrip
                        }
                        publishOffset()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (seeking) {
                        val f = fractionAt(event.x)
                        seeking = false
                        seekFraction = -1f
                        invalidate()
                        onSeek?.invoke(f)
                        return true
                    }
                    if (dragging) {
                        dragging = false
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        // A short drag springs back untouched; a long one commits.
                        val threshold = maxOf(dp(44f), height * 0.3f)
                        // Commit along the axis the drag actually settled on, not on whichever
                        // delta happens to be bigger at the moment the finger lifts.
                        val sideways = if (dragAxis != 0) dragAxis == 1 else abs(dx) >= abs(dy)
                        dragAxis = 0
                        if (sideways) {
                            if (abs(dx) >= threshold) {
                                onSwipe?.invoke(if (dx > 0f) 1 else -1, 0)
                                settleBack(320L)
                            } else {
                                springHome()
                            }
                        } else {
                            if (abs(dy) >= threshold) {
                                // animateSwap slides the incoming card in; the window eases back to
                                // its resting place underneath it, and endDrag runs when that
                                // settle finishes rather than on a timer.
                                onSwipe?.invoke(0, if (dy > 0f) 1 else -1)
                                settleBack(320L)
                            } else {
                                springHome()
                            }
                        }
                        return true
                    }
                    if (idle) return true
                    // The rects are in card coordinates, so a card that is still springing back
                    // from a drag has to be hit-tested where it is drawn, not where it will be.
                    val hitX = event.x - dragX
                    val hitY = event.y - dragY
                    for (i in controlRects.indices) {
                        if (controlRects[i].contains(hitX, hitY)) {
                            onControl?.invoke(controlKeys[i])
                            return true
                        }
                    }
                    onTap?.invoke()
                    return true
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    // The window moves with the finger, so the *end* of a drag can land outside the
                    // window it started in and arrive here as an outside touch. That is the same
                    // gesture, not a tap elsewhere: dropping it kept the popup from collapsing
                    // under the finger the moment the drag began.
                    if (dragging || seeking) return true
                    onOutsideTouch?.invoke()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    dragAxis = 0
                    seeking = false
                    seekFraction = -1f
                    springHome()
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.w("PillView.onTouchEvent threw", t)
        }
        return true
    }

    /**
     * Elastic give while the direction is still unknown: follows the finger a little and then
     * resists, in whichever direction it is pulled, and never beyond [room].
     */
    private fun rubberBand(delta: Float): Float {
        val limit = dp(DRAG_GIVE_DP)
        val sign = if (delta < 0f) -1f else 1f
        val pull = abs(delta)
        return sign * (pull * limit) / (pull + limit)
    }

    /**
     * Guesses which way the drag is going, once. The offset itself is not locked to that axis: after
     * the guess the card follows the finger freely in both directions, and the guess only decides
     * what a release commits to.
     */
    private fun decideDragAxis(dx: Float, dy: Float) {
        if (dragAxis != 0) return
        val ax = abs(dx)
        val ay = abs(dy)
        val far = maxOf(ax, ay)
        if (far < dp(DRAG_LOCK_DP)) return
        if (ay > ax * DRAG_LOCK_RATIO) {
            dragAxis = 2
        } else if (ax > ay * DRAG_LOCK_RATIO) {
            dragAxis = 1
        } else if (far >= dp(DRAG_FORCE_LOCK_DP)) {
            // Still diagonal after this much travel: take the longer side rather than staying in
            // the damped "stretched" state, where the card barely follows the finger.
            dragAxis = if (ax >= ay) 1 else 2
        }
        if (dragAxis != 0) {
            // Catches up from the stretched position instead of jumping: the grip starts partway
            // and eases to 1 over the next few frames.
            dragGrip = DRAG_GRIP_START
        }
    }

    /** Where a touch falls on the seek track, as a 0..1 fraction. */
    private fun fractionAt(x: Float): Float {
        val left = seekTouch.left + dp(6f)
        val width = (seekTouch.width() - dp(12f)).coerceAtLeast(1f)
        return ((x - left) / width).coerceIn(0f, 1f)
    }

    /** Asks the owner for room around the card, once per drag. */
    private fun beginDrag() {
        if (dragWindowOpen) return
        dragWindowOpen = true
        try {
            onDragStart?.invoke()
        } catch (t: Throwable) {
            Log.w("PillView.onDragStart threw", t)
        }
    }

    /** The card is back in place: the window can close up again. */
    private fun endDrag() {
        if (!dragWindowOpen) return
        dragWindowOpen = false
        try {
            onDragEnd?.invoke()
        } catch (t: Throwable) {
            Log.w("PillView.onDragEnd threw", t)
        }
    }

    /** True while the card is still sliding back into place after a drag. */
    fun isSettling(): Boolean = dragAnimator?.isRunning == true

    /**
     * Hands the current offset to the service as a delta, so the window can move with it. Called
     * from the touch handler and from every frame of the settle animation, which is what makes the
     * return to place a slide instead of a jump.
     */
    private fun publishOffset() {
        try {
            if (dragMovesWindow) {
                val tx = dragX.toInt()
                val ty = dragY.toInt()
                val ddx = tx - sentX
                val ddy = ty - sentY
                if (ddx != 0 || ddy != 0) {
                    sentX = tx
                    sentY = ty
                    onDragMove?.invoke(ddx, ddy)
                }
            }
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.publishOffset threw", t)
        }
    }

    /**
     * Too short to commit: the card slides back into place.
     *
     * Deliberately unhurried (about 0.7 s) and without an overshoot: a drag that did not take
     * should read as the card being eased back into its slot, not as it snapping away. The long
     * tail of the decelerate curve is what makes the last few dp visible instead of instant.
     */
    private fun springHome() {
        if (dragMovesWindow) {
            handOverReturn()
            return
        }
        animateDragTo(0f, 0f, RETURN_MS, DecelerateInterpolator(2.2f))
    }

    /** Committed: the card eases home while the new card or track lands. */
    private fun settleBack(durationMs: Long) {
        if (dragMovesWindow) {
            handOverReturn()
            return
        }
        animateDragTo(0f, 0f, maxOf(durationMs, RETURN_MS), DecelerateInterpolator(2.2f))
    }

    /**
     * The return belongs to whoever moved the window during the drag.
     *
     * When the window itself follows the finger, an offset animation here moves the card back as
     * well, and the two motions add up to a detour: the card goes one way, the window the other,
     * and the end position is neither. So the offsets are dropped without a word and the service
     * eases the window from wherever the finger left it straight to the resting spot.
     */
    private fun handOverReturn() {
        try {
            dragAnimator?.cancel()
            dragAnimator = null
            dragX = 0f
            dragY = 0f
            sentX = 0
            sentY = 0
            invalidate()
            endDrag()
        } catch (t: Throwable) {
            Log.w("PillView handOverReturn threw", t)
        }
    }

    /** How long an aborted drag takes to slide back home. */
    private val RETURN_MS = 700L

    private fun animateDragTo(toX: Float, toY: Float, durationMs: Long, curve: TimeInterpolator) {
        try {
            dragAnimator?.cancel()
            if (dragX == toX && dragY == toY) return
            val fromX = dragX
            val fromY = dragY
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = curve
                addUpdateListener {
                    val f = it.animatedValue as Float
                    dragX = fromX + (toX - fromX) * f
                    dragY = fromY + (toY - fromY) * f
                    publishOffset()
                }
            }
            anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (dragAnimator === animation) {
                        dragX = toX
                        dragY = toY
                        publishOffset()
                        sentX = 0
                        sentY = 0
                        if (toX == 0f && toY == 0f) endDrag()
                    }
                }
            })
            dragAnimator = anim
            anim.start()
        } catch (t: Throwable) {
            Log.w("PillView drag settle failed", t)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

/**
 * Full-size transparent container added to a surface root. Not clickable and not
 * focusable, so keyguard touches fall through to the stock UI underneath.
 */
class PillHost(context: Context) : FrameLayout(context) {

    companion object {
        const val TAG_NAME = "nowbar_pill_host"
    }

    init {
        tag = TAG_NAME
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setWillNotDraw(true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

object PillFactory {

    fun newPillLayout(context: Context): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            dp(context, TuningStore.DEFAULT_WIDTH_DP),
            dp(context, TuningStore.DEFAULT_HEIGHT_DP),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )

    fun newPill(context: Context): View = PillView(context)

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
