package dev.brl.nowbar.module

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * The pill. M1 draws it with a placeholder label and no data; its size, text and corner
 * radius come from the temporary tuning store so the owner can dial them in live.
 *
 * Everything is defensive - a throw during a custom view's draw would take SystemUI with it.
 */
class PillView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Fully opaque: the status bar pill sits where the notification icons are and has to
        // hide them, so even 5% transparency shows them ghosting through.
        color = Color.parseColor("#FF111117")
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(TuningStore.DEFAULT_TEXT_DP.toFloat())
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private var label = TuningStore.DEFAULT_LABEL
    private var radiusOverrideDp = 0f
    private var loggedFirstDraw = false

    /**
     * Fired when the user taps anywhere outside this window. Needs
     * [WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH] on the window; taps *inside* the
     * window never trigger it, which is what separates "tap outside to close" from
     * "tap the thing itself to close".
     */
    var onOutsideTouch: (() -> Unit)? = null

    /** Applies tuning values: label, text size and corner radius (0 = full pill). */
    fun setStyle(values: Tuning.Values) = setStyle(values.label, values.textDp, values.radiusDp)

    /** Same styling, so the smaller status bar pill can pick its own text size and radius. */
    fun setStyle(text: String, textDp: Int, radiusDp: Int) {
        try {
            label = text
            labelPaint.textSize = dp(textDp.toFloat())
            radiusOverrideDp = radiusDp.toFloat()
            invalidate()
        } catch (t: Throwable) {
            Log.w("PillView.setStyle threw", t)
        }
    }

    override fun onDraw(canvas: Canvas) {
        try {
            if (!loggedFirstDraw) {
                loggedFirstDraw = true
                Log.i("PillView drew: ${width}x${height} visible=$isShown attached=$isAttachedToWindow label='$label'")
            }
            val radius = if (radiusOverrideDp > 0f) dp(radiusOverrideDp) else height / 2f
            rect.set(stroke.strokeWidth, stroke.strokeWidth, width - stroke.strokeWidth, height - stroke.strokeWidth)
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, stroke)

            val maxTextWidth = (width - dp(24f)).coerceAtLeast(0f)
            val shown = if (labelPaint.measureText(label) > maxTextWidth) {
                TextUtils.ellipsize(label, labelPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
            } else {
                label
            }
            val baseline = height / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(shown, width / 2f, baseline, labelPaint)
        } catch (t: Throwable) {
            Log.w("PillView.onDraw threw", t)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch?.invoke()
                return true
            }
        } catch (t: Throwable) {
            Log.w("PillView.onTouchEvent threw", t)
        }
        return super.onTouchEvent(event)
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
