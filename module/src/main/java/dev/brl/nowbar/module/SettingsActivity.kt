package dev.brl.nowbar.module

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The settings screen: Material 3, wallpaper-derived colours (Material You), one card per surface.
 *
 * Every control writes straight into the shared preferences that the overlay service watches, so
 * edits land on the pill live. The slider ranges stay at 0..1000 dp so nothing is capped while the
 * layout is still being dialled in.
 */
class SettingsActivity : AppCompatActivity() {

    private companion object {
        /** Upper bound for every slider: the same ceiling the floating tuner uses. */
        const val MAX_DP = 1000

        val ON = Color.parseColor("#4CAF50")
        val OFF = Color.parseColor("#8A8A8A")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var toolbar: MaterialToolbar
    private lateinit var column: LinearLayout
    private lateinit var preview: PillPreview
    private lateinit var overlaySwitch: MaterialSwitch
    private lateinit var lockPillStatus: StatusRow
    private lateinit var sbPillStatus: StatusRow
    private lateinit var watcherStatus: StatusRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TuningStore.prefs(this)
        setContentView(buildScaffold())
        buildContent()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    // --------------------------------------------------------------- scaffold

    private fun buildScaffold(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.app_name)
            subtitle = getString(R.string.settings_subtitle)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(48))
        }
        val scroll = NestedScrollView(this).apply { isFillViewport = true }
        scroll.addView(column, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Edge-to-edge: keep the toolbar below the status bar and above the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(0, bars.top, 0, 0)
            column.setPadding(dp(16), dp(4), dp(16), dp(48) + bars.bottom)
            insets
        }
        return root
    }

    private fun buildContent() {
        statusCard()
        previewCard()
        lockPillCard()
        statusBarCard()
        popupCard()
        labelCard()
        advancedCard()
    }

    // ------------------------------------------------------------------ cards

    /** Material card with a heading; returns the container to put rows into. */
    private fun card(title: String, subtitle: String? = null): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        content.addView(
            TextView(this).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        if (subtitle != null) {
            content.addView(
                TextView(this).apply {
                    text = subtitle
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(1).toFloat()
        }
        card.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(
            card,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        )
        return content
    }

    private fun statusCard() {
        val body = card("Status", "What is drawing the pill right now")

        lockPillStatus = StatusRow(this)
        lockPillStatus.title = "Lockscreen pill"
        body.addView(lockPillStatus, rowParams())

        sbPillStatus = StatusRow(this)
        sbPillStatus.title = "Status bar pill"
        body.addView(sbPillStatus, rowParams())

        watcherStatus = StatusRow(this)
        watcherStatus.title = "Root unlock watcher"
        body.addView(watcherStatus, rowParams())

        overlaySwitch = switchRow(
            body,
            "Draw over apps (overlay)",
            "Needed only if the accessibility service is off",
            OverlayService.running
        ) { checked -> setOverlay(checked) }

        button(body, "Open accessibility settings") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (t: Throwable) {
                Log.w("accessibility settings intent failed", t)
            }
        }
    }

    private fun previewCard() {
        val body = card("Preview", "Scaled to fit; the pill on screen uses the real size")
        preview = PillPreview(this)
        preview.setColors(
            themeColor(com.google.android.material.R.attr.colorSurfaceVariant),
            themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        body.addView(
            preview,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        )
        refreshPreview()
    }

    private fun lockPillCard() {
        val body = card("Lockscreen pill", "Bottom centre, above the fingerprint circle")
        sliderRow(body, "Width", "widthDp", TuningStore.DEFAULT_WIDTH_DP, { refreshPreview() })
        sliderRow(body, "Height", "heightDp", TuningStore.DEFAULT_HEIGHT_DP, { refreshPreview() })
        sliderRow(body, "Text size", "textDp", TuningStore.DEFAULT_TEXT_DP, { refreshPreview() })
        sliderRow(body, "Corner radius (0 = pill)", "radiusDp", TuningStore.DEFAULT_RADIUS_DP, { refreshPreview() })
        sliderRow(body, "Height above bottom edge", "bottomDp", TuningStore.DEFAULT_BOTTOM_DP, null)
    }

    private fun statusBarCard() {
        val body = card("Status bar pill", "Sits over the notification icons while unlocked")
        sliderRow(body, "Width", "sbWidthDp", TuningStore.DEFAULT_SB_WIDTH_DP, { refreshPreview() })
        sliderRow(body, "Height", "sbHeightDp", TuningStore.DEFAULT_SB_HEIGHT_DP, { refreshPreview() })
        sliderRow(body, "Text size", "sbTextDp", TuningStore.DEFAULT_SB_TEXT_DP, null)
        sliderRow(body, "Corner radius (0 = pill)", "sbRadiusDp", TuningStore.DEFAULT_SB_RADIUS_DP, null)
        sliderRow(body, "Left edge", "sbLeftDp", TuningStore.DEFAULT_SB_LEFT_DP, null)
        sliderRow(body, "Top edge (0 = centred)", "sbTopDp", TuningStore.DEFAULT_SB_TOP_DP, null)
    }

    private fun popupCard() {
        val body = card("Expanded popup", "Grows out of the status bar pill on tap")
        sliderRow(body, "Width", "popupWidthDp", TuningStore.DEFAULT_POPUP_WIDTH_DP, null)
        sliderRow(body, "Height", "popupHeightDp", TuningStore.DEFAULT_POPUP_HEIGHT_DP, null)
        sliderRow(body, "Text size", "popupTextDp", TuningStore.DEFAULT_POPUP_TEXT_DP, null)
        sliderRow(body, "Corner radius (0 = pill)", "popupRadiusDp", TuningStore.DEFAULT_POPUP_RADIUS_DP, null)
        sliderRow(body, "Left edge", "popupLeftDp", TuningStore.DEFAULT_POPUP_LEFT_DP, null)
        sliderRow(body, "Top edge", "popupTopDp", TuningStore.DEFAULT_POPUP_TOP_DP, null)
    }

    private fun labelCard() {
        val body = card("Label", "Placeholder text until real items arrive")
        val input = TextInputLayout(this).apply {
            hint = "Text shown in the pill"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            val corner = dp(14).toFloat()
            setBoxCornerRadii(corner, corner, corner, corner)
        }
        val edit = TextInputEditText(this).apply {
            setText(prefs.getString("label", TuningStore.DEFAULT_LABEL))
            setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit().putString("label", s?.toString() ?: TuningStore.DEFAULT_LABEL).apply()
                    refreshPreview()
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        input.addView(edit)
        body.addView(
            input,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        )
    }

    private fun advancedCard() {
        val body = card("Test tooling", "Temporary while the layout is being dialled in")
        button(body, "Open floating tuner") {
            try {
                startForegroundService(Intent(this, TunerService::class.java))
            } catch (t: Throwable) {
                Log.e("could not start tuner", t)
            }
        }
        button(body, "Reset to defaults") { reset() }
    }

    // ------------------------------------------------------------------ rows

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }

    private fun switchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): MaterialSwitch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 15f })
        if (subtitle != null) {
            texts.addView(TextView(this).apply { text = subtitle; textSize = 12f; alpha = 0.7f })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val toggle = MaterialSwitch(this).apply { isChecked = checked }
        toggle.setOnCheckedChangeListener { _, value -> onChange(value) }
        row.addView(toggle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        parent.addView(row, rowParams())
        return toggle
    }

    private fun sliderRow(
        parent: LinearLayout,
        title: String,
        key: String,
        default: Int,
        onChange: (() -> Unit)?
    ) {
        val headers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headers.addView(
            TextView(this).apply { text = title; textSize = 15f },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val valueView = TextView(this).apply { textSize = 14f; alpha = 0.7f }
        headers.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val start = prefs.getInt(key, default).coerceIn(0, MAX_DP)
        fun render(v: Int) {
            valueView.text = if (key.endsWith("TopDp") || key.endsWith("LeftDp")) "$v dp (0 = auto)" else "$v dp"
        }
        render(start)

        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = MAX_DP.toFloat()
            // No stepSize on purpose: stepSize = 1 over 0…1000 draws a thousand tick marks.
            value = start.toFloat()
            contentDescription = title
            addOnChangeListener { _, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val rounded = v.toInt()
                render(rounded)
                prefs.edit().putInt(key, rounded).apply()
                onChange?.invoke()
            }
        }

        parent.addView(headers, rowParams())
        parent.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text
        b.setOnClickListener { onClick() }
        parent.addView(
            b,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        )
    }

    // ---------------------------------------------------------------- actions

    private fun refreshStates() {
        val a11y = NowBarBridge.service != null
        lockPillStatus.state = if (a11y) "on (accessibility overlay)" else "off"
        lockPillStatus.on = a11y
        sbPillStatus.state = if (a11y) "on (same overlay)" else "off"
        sbPillStatus.on = a11y
        watcherStatus.state = if (RootKeyguardWatcher.isRunning) "streaming (root)" else "not running"
        watcherStatus.on = RootKeyguardWatcher.isRunning
        overlaySwitch.isChecked = OverlayService.running
        refreshPreview()
    }

    private fun refreshPreview() {
        if (!::preview.isInitialized) return
        preview.update(
            prefs.getInt("widthDp", TuningStore.DEFAULT_WIDTH_DP),
            prefs.getInt("heightDp", TuningStore.DEFAULT_HEIGHT_DP),
            prefs.getInt("radiusDp", TuningStore.DEFAULT_RADIUS_DP),
            prefs.getInt("textDp", TuningStore.DEFAULT_TEXT_DP),
            prefs.getString("label", TuningStore.DEFAULT_LABEL) ?: TuningStore.DEFAULT_LABEL
        )
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        return try {
            if (!theme.resolveAttribute(attr, value, true)) Color.GRAY
            else if (value.resourceId != 0) getColor(value.resourceId) else value.data
        } catch (t: Throwable) {
            Color.GRAY
        }
    }

    private fun setOverlay(show: Boolean) {
        try {
            if (show) {
                if (!Settings.canDrawOverlays(this)) {
                    overlaySwitch.isChecked = false
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return
                }
                startForegroundService(Intent(this, OverlayService::class.java))
            } else {
                stopService(Intent(this, OverlayService::class.java))
            }
        } catch (t: Throwable) {
            Log.e("setOverlay($show) failed", t)
        }
    }

    private fun reset() {
        prefs.edit()
            .putInt("widthDp", TuningStore.DEFAULT_WIDTH_DP)
            .putInt("heightDp", TuningStore.DEFAULT_HEIGHT_DP)
            .putInt("textDp", TuningStore.DEFAULT_TEXT_DP)
            .putInt("radiusDp", TuningStore.DEFAULT_RADIUS_DP)
            .putInt("bottomDp", TuningStore.DEFAULT_BOTTOM_DP)
            .putInt("sbWidthDp", TuningStore.DEFAULT_SB_WIDTH_DP)
            .putInt("sbHeightDp", TuningStore.DEFAULT_SB_HEIGHT_DP)
            .putInt("sbTextDp", TuningStore.DEFAULT_SB_TEXT_DP)
            .putInt("sbRadiusDp", TuningStore.DEFAULT_SB_RADIUS_DP)
            .putInt("sbLeftDp", TuningStore.DEFAULT_SB_LEFT_DP)
            .putInt("sbTopDp", TuningStore.DEFAULT_SB_TOP_DP)
            .putInt("popupWidthDp", TuningStore.DEFAULT_POPUP_WIDTH_DP)
            .putInt("popupHeightDp", TuningStore.DEFAULT_POPUP_HEIGHT_DP)
            .putInt("popupTextDp", TuningStore.DEFAULT_POPUP_TEXT_DP)
            .putInt("popupRadiusDp", TuningStore.DEFAULT_POPUP_RADIUS_DP)
            .putInt("popupLeftDp", TuningStore.DEFAULT_POPUP_LEFT_DP)
            .putInt("popupTopDp", TuningStore.DEFAULT_POPUP_TOP_DP)
            .putString("label", TuningStore.DEFAULT_LABEL)
            .apply()
        recreate()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // --------------------------------------------------------------- widgets

    /** Title on the left, state on the right, with a coloured dot in front of the state. */
    private class StatusRow(context: Context) : LinearLayout(context) {

        private val titleView = TextView(context).apply { textSize = 15f }
        private val dot = View(context)
        private val stateView = TextView(context).apply { textSize = 13f; alpha = 0.7f }

        var title: String
            get() = titleView.text.toString()
            set(value) {
                titleView.text = value
            }

        var state: String
            get() = stateView.text.toString()
            set(value) {
                stateView.text = value
            }

        var on: Boolean = false
            set(value) {
                field = value
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (value) ON else OFF)
                }
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LayoutParams(0, WRAP_CONTENT, 1f))
            addView(dot, LayoutParams(dp(context, 8), dp(context, 8)).apply { rightMargin = dp(context, 8) })
            addView(stateView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        private fun dp(context: Context, v: Int) =
            (v * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    /** Draws the lockscreen pill at its configured shape, scaled down to the card width. */
    private class PillPreview(context: Context) : View(context) {

        private var widthDp = TuningStore.DEFAULT_WIDTH_DP
        private var heightDp = TuningStore.DEFAULT_HEIGHT_DP
        private var radiusDp = TuningStore.DEFAULT_RADIUS_DP
        private var textDp = TuningStore.DEFAULT_TEXT_DP
        private var label = TuningStore.DEFAULT_LABEL

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
        }
        private val rect = RectF()
        private val density = resources.displayMetrics.density

        fun setColors(fillColor: Int, textColor: Int) {
            fill.color = fillColor
            text.color = textColor
            invalidate()
        }

        fun update(width: Int, height: Int, radius: Int, textSize: Int, label: String) {
            widthDp = width
            heightDp = height
            radiusDp = radius
            textDp = textSize
            this.label = label.ifBlank { TuningStore.DEFAULT_LABEL }
            requestLayout()
            invalidate()
        }

        private fun dp(v: Float) = v * density
        private fun naturalW() = dp(widthDp.toFloat()).coerceAtLeast(1f)
        private fun naturalH() = dp(heightDp.toFloat()).coerceAtLeast(1f)

        private fun scaleFor(available: Float): Float =
            if (naturalW() > available) available / naturalW() else 1f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - dp(0f)
            val s = scaleFor(available)
            val h = (naturalH() * s + dp(16f)).toInt().coerceAtLeast((dp(24f)).toInt())
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
        }

        override fun onDraw(canvas: Canvas) {
            try {
                val s = scaleFor(width.toFloat())
                val w = naturalW() * s
                val h = naturalH() * s
                val left = (width - w) / 2f
                val top = (height - h) / 2f
                val radius = if (radiusDp > 0) dp(radiusDp.toFloat()) * s else h / 2f
                rect.set(left, top, left + w, top + h)
                canvas.drawRoundRect(rect, radius, radius, fill)
                text.textSize = dp(textDp.toFloat()) * s
                val baseline = top + h / 2f - (text.descent() + text.ascent()) / 2f
                canvas.drawText(label, left + w / 2f, baseline, text)
            } catch (t: Throwable) {
                Log.w("PillPreview.onDraw threw", t)
            }
        }
    }
}
