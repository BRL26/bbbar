package dev.brl.nowbar.module

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
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
    private var dragAnimator: ValueAnimator? = null

    /** The expanded media seek slider: its touch band, and the position being dragged to. */
    private val seekTouch = RectF()
    private var seekVisible = false
    private var seeking = false
    private var seekFraction = -1f

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
            val h = heightPx.toFloat()
            if (h <= 0f) return maxWidthPx
            val r = minOf(dp(radiusOverrideDp), h / 2f)
            val pad = (dp(11f) + r * 0.12f).coerceAtMost(h / 3f)
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
                    if (appIcon(item.packageName.orEmpty()) != null) glyphSize + dp(9f) else 0f
                item.kind == ItemKind.TIMER || item.kind == ItemKind.ALARM ||
                    item.kind == ItemKind.LIVE -> glyphSize + dp(9f)
                item.art != null -> glyphSize + dp(9f)
                else -> 0f
            }
            val wanted = pad + iconW + textW + pad + dp(1f)
            // A line that does not fit keeps the tuned width: it scrolls inside it. Shrinking
            // there would leave a tiny pill scrolling a long line, which is the one thing this
            // is not for. A line that does fit shrinks the pill down to it, as far as a circle.
            if (wanted >= maxWidthPx) return maxWidthPx
            val minW = heightPx.coerceAtMost(maxWidthPx)
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
     * Colours come from Material colour roles: surface-variant for the card, on-surface for text
     * and glyphs, primary for the progress fill, outline for the hairline.
     */
    private fun applyTheme() {
        try {
            val surface = attrColor(com.google.android.material.R.attr.colorSurfaceVariant, Color.parseColor("#FF111117"))
            val onSurface = attrColor(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
            val primary = attrColor(androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
            fill.color = surface
            stackFill.color = attrColor(com.google.android.material.R.attr.colorSurface, surface)
            stroke.color = withAlpha(attrColor(com.google.android.material.R.attr.colorOutline, onSurface), 0x40)
            titlePaint.color = onSurface
            subtitlePaint.color = withAlpha(onSurface, 0xB3)
            progressTrack.color = withAlpha(onSurface, 0x33)
            progressFill.color = primary
            glyph.color = onSurface
            glyphFill.color = onSurface
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
            val drawH = if (contentHeightPx > 0) contentHeightPx.toFloat() else height.toFloat()
            // Bottom anchored while collapsing: the shrinking window crops the card from the top
            // rather than re-laying out its contents, so nothing is squished.
            if (contentHeightPx > 0) canvas.translate(0f, (height - contentHeightPx).toFloat())
            if (slidePx != 0f) canvas.translate(0f, slidePx)
            if (dragX != 0f || dragY != 0f) canvas.translate(dragX, dragY)

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
            // Keep the frame clock running only while a line is actually scrolling.
            if (marqueeActive) postInvalidateOnAnimation()
        }
    }

    private fun drawFront(canvas: Canvas, area: RectF, radius: Float, item: NowBarItem?) {
        val w = area.width()
        val h = area.height()
        if (w <= 0f || h <= 0f) return
        val r = minOf(radius, h / 2f)

        // The idle line is the label itself; a blank label means the owner has nothing to say,
        // so the card is not drawn at all rather than left as an empty shape.
        if (item == null && TuningStore.idleLabel(context).isEmpty()) return

        clipToCard(canvas, area, r)
        canvas.drawColor(fill.color)
        val art = item?.art
        if (item != null && item.kind == ItemKind.MEDIA && art != null) {
            drawArtBackground(canvas, area, art)
        }
        canvas.restore()
        canvas.drawRoundRect(area, r, r, stroke)

        clipToCard(canvas, area, r)

        val pad = (dp(11f) + r * 0.12f).coerceAtMost(h / 3f)
        val glyphSize = (h - pad * 2f).coerceAtMost(dp(34f)).coerceAtLeast(dp(14f))
        // The progress bar hugs the bottom edge of the card - two or three pixels of it - so it
        // also fits the short status bar pill (27 dp) without touching the line of text, which
        // keeps the full height to centre itself in.
        val dense = h < dp(44f)
        val barH = if (dense) dp(2f) else dp(3f)
        val showBar = item != null && item.progress >= 0f
        val barTop = h - barH
        val contentBottom = h

        var textLeft = pad
        var iconDrawn = false
        // Centred on the content band (which loses room to the progress bar when there is one),
        // not parked at the top padding: the icon used to sit visibly high in the pill.
        val glyphTop = ((contentBottom - glyphSize) / 2f).coerceAtLeast(0f)
        val glyphRect = RectF(pad, glyphTop, pad + glyphSize, glyphTop + glyphSize)
        when (item?.kind) {
            ItemKind.TIMER -> {
                drawClock(canvas, glyphRect)
                iconDrawn = true
            }
            ItemKind.ALARM -> {
                drawBell(canvas, glyphRect)
                iconDrawn = true
            }
            ItemKind.LIVE -> {
                drawLiveDot(canvas, glyphRect)
                iconDrawn = true
            }
            ItemKind.MEDIA -> {
                val icon = appIcon(item?.packageName.orEmpty())
                if (icon != null) {
                    drawAppIcon(canvas, icon, glyphRect, glyphSize * 0.28f)
                    iconDrawn = true
                }
            }
            else -> {
                if (art != null && item?.kind != ItemKind.MEDIA) {
                    artRect.set(
                        glyphRect.left.toInt(),
                        glyphRect.top.toInt(),
                        glyphRect.right.toInt(),
                        glyphRect.bottom.toInt()
                    )
                    drawCroppedArt(canvas, art, artRect, glyphSize / 2f)
                    iconDrawn = true
                }
            }
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
                val rect = RectF(x, cy - size / 2f, x + size, cy + size / 2f)
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

        val available = (textRight - textLeft).coerceAtLeast(0f)
        val title = when {
            item == null -> label
            item.kind == ItemKind.TIMER && item.subtitle.isNotBlank() -> item.subtitle
            else -> item.title.takeIf { it.isNotBlank() } ?: label
        }
        val subtitle = if (item?.kind == ItemKind.TIMER) "" else item?.subtitle.orEmpty()
        val showSubtitle = expanded && subtitle.isNotBlank() && h >= dp(40f) && available >= dp(40f)
        // A media card that knows its length gets a scrubber under the name, with the elapsed time
        // beside it, instead of the thin progress strip along the bottom edge.
        val showSeek = expanded && item?.kind == ItemKind.MEDIA && item.durationMs > 0L &&
            h >= dp(46f) && available >= dp(70f)

        val gap = dp(1f)
        val seekRow = if (showSeek) dp(SEEK_ROW_DP) else 0f
        val block = titlePaint.textSize +
            (if (showSubtitle) gap + subtitlePaint.textSize else 0f) + seekRow
        val top = ((contentBottom - block) / 2f).coerceAtLeast(dp(2f))
        val titleBaseline = top - titlePaint.ascent()
        paintEllipsized(canvas, title, titlePaint, textLeft, titleBaseline, available)
        var lineBottom = titleBaseline + titlePaint.descent()
        if (showSubtitle) {
            val subBaseline = lineBottom + gap + subtitlePaint.textSize
            paintEllipsized(canvas, subtitle, subtitlePaint, textLeft, subBaseline, available)
            lineBottom = subBaseline + subtitlePaint.descent()
        }
        if (showSeek && item != null) {
            drawSeekRow(canvas, item, textLeft, lineBottom + dp(1f), available)
        }

        if (showBar && !seekVisible && item != null) {
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
                cy - dp(11f),
                left + trackW + dp(6f),
                cy + dp(11f)
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
        } catch (t: Throwable) {
            Log.w("PillView app icon failed", t)
        } finally {
            canvas.restore()
        }
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
            val barX = cx - dir * size * 0.42f
            card.set(
                barX - size * 0.07f,
                cy - size / 2f,
                barX + size * 0.07f,
                cy + size / 2f
            )
            canvas.drawRoundRect(card, size * 0.05f, size * 0.05f, glyphFill)
            val path = Path().apply {
                moveTo(cx + dir * size * 0.34f, cy - size / 2f)
                lineTo(cx - dir * size * 0.1f, cy)
                lineTo(cx + dir * size * 0.34f, cy + size / 2f)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            // An idle status bar pill (placeholder text or logo) is not a control: there is
            // nothing to expand into and nothing to swipe to, so it neither reacts to a tap nor
            // wobbles under the finger.
            val idle = items.isEmpty()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragging = false
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
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!dragging && (abs(dx) > dp(6f) || abs(dy) > dp(6f))) {
                        dragAnimator?.cancel()
                        dragging = true
                    }
                    if (dragging) {
                        // The card follows the finger. Horizontal drags are damped, because the
                        // swipe they commit to is a skip rather than a card change.
                        if (abs(dx) >= abs(dy)) {
                            dragX = dx * 0.7f
                            dragY = 0f
                        } else {
                            dragX = 0f
                            dragY = dy
                        }
                        invalidate()
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
                        val dx = event.x - downX
                        val dy = event.y - downY
                        // A short drag springs back untouched; a long one commits.
                        val threshold = maxOf(dp(44f), height * 0.3f)
                        if (abs(dx) >= abs(dy)) {
                            if (abs(dx) >= threshold) {
                                onSwipe?.invoke(if (dx > 0f) 1 else -1, 0)
                                settleBack(240L)
                            } else {
                                springHome()
                            }
                        } else {
                            if (abs(dy) >= threshold) {
                                // Hand the vertical offset over to animateSwap, which slides the
                                // incoming card in from the side the finger left.
                                dragY = 0f
                                invalidate()
                                onSwipe?.invoke(0, if (dy > 0f) 1 else -1)
                            } else {
                                springHome()
                            }
                        }
                        return true
                    }
                    if (idle) return true
                    for (i in controlRects.indices) {
                        if (controlRects[i].contains(event.x, event.y)) {
                            onControl?.invoke(controlKeys[i])
                            return true
                        }
                    }
                    onTap?.invoke()
                    return true
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    onOutsideTouch?.invoke()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
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

    /** Where a touch falls on the seek track, as a 0..1 fraction. */
    private fun fractionAt(x: Float): Float {
        val left = seekTouch.left + dp(6f)
        val width = (seekTouch.width() - dp(12f)).coerceAtLeast(1f)
        return ((x - left) / width).coerceIn(0f, 1f)
    }

    /** Too short to commit: the card bounces back to its place with a little overshoot. */
    private fun springHome() {
        animateDragTo(0f, 0f, 280L, OvershootInterpolator(1.7f))
    }

    /** Committed: the card eases home while the new card or track lands. */
    private fun settleBack(durationMs: Long) {
        animateDragTo(0f, 0f, durationMs, DecelerateInterpolator(1.5f))
    }

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
                    invalidate()
                }
            }
            anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (dragAnimator === animation) {
                        dragX = toX
                        dragY = toY
                        invalidate()
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
