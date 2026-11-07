package dev.brl.nowbar.module

import android.app.NotificationManager
import android.content.ComponentName
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The settings screen: Material 3, wallpaper-derived colours (Material You), grouped into
 * sections with one card per surface.
 *
 * Every control writes straight into the shared preferences the overlay service watches, so
 * edits land on the pill live. The slider ranges stay at 0..1000 dp so nothing is capped while
 * the layout is still being dialled in.
 */
class SettingsActivity : AppCompatActivity() {

    private companion object {
        /** Upper bound for every slider: the same ceiling the floating tuner uses. */
        const val MAX_DP = 1000

        val ON = Color.parseColor("#4CAF50")
        val OFF = Color.parseColor("#8A8A8A")

        /** How often the status rows are re-read while the screen is visible. */
        const val REFRESH_MS = 1500L

        /** Minimum gap between hook probes (each one is a root shell). */
        const val HOOK_PROBE_MS = 6000L

        /** A heartbeat older than this means SystemUI restarted without the hook. */
        const val HOOK_STALE_MS = 120_000L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var toolbar: MaterialToolbar
    private lateinit var column: LinearLayout
    private lateinit var applyBar: LinearLayout

    private lateinit var heroDot: View
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var overlaySwitch: MaterialSwitch
    private lateinit var masterSwitch: MaterialSwitch
    private lateinit var listenerStatus: StatusRow
    private lateinit var a11yStatus: StatusRow
    private lateinit var watcherStatus: StatusRow
    private lateinit var overlayStatus: StatusRow
    private lateinit var hookStatus: StatusRow

    private val handler = Handler(Looper.getMainLooper())

    /** Uptime of the last hook probe, so the root call is not made on every refresh tick. */
    private var hookProbedAt = 0L
    private var hookProbeBusy = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshStates()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private lateinit var lockPreview: SurfacePreview
    private lateinit var sbPreview: SurfacePreview
    private lateinit var popupPreview: SurfacePreview
    private lateinit var lockCaption: TextView
    private lateinit var sbCaption: TextView
    private lateinit var popupCaption: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TuningStore.prefs(this)
        setContentView(buildScaffold())
        buildContent()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
        // The service connects, drops a surface or loses the hook while this screen is open, so
        // the rows are re-read instead of frozen at the value they had on entry.
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // --------------------------------------------------------------- scaffold

    private fun buildScaffold(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))

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

        // Always-visible action bar for Apply: the button in the scrolling hero card was easy to
        // miss, and a settings screen needs an obvious way to push the whole config at once.
        applyBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        applyBar.addView(
            MaterialButton(this).apply {
                text = "Apply settings"
                setOnClickListener { applySettings() }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        root.addView(applyBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Edge-to-edge: keep the toolbar below the status bar and above the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(0, bars.top, 0, 0)
            column.setPadding(dp(16), dp(4), dp(16), dp(24))
            applyBar.setPadding(dp(16), dp(8), dp(16), dp(8) + bars.bottom)
            insets
        }
        return root
    }

    private fun buildContent() {
        heroCard()
        sectionHeader("Surfaces")
        lockCard()
        statusBarCard()
        androidBarCard()
        popupCard()
        sectionHeader("Look")
        lookCard()
        textCard()
        sectionHeader("Content")
        itemsCard()
        rankCard()
        labelCard()
        sectionHeader("Simulate")
        simulateCard()
        sectionHeader("Maintenance")
        toolingCard()
    }

    // ---------------------------------------------------------------- layout

    private fun sectionHeader(text: String) {
        column.addView(
            TextView(this).apply {
                this.text = text.uppercase()
                textSize = 12f
                letterSpacing = 0.1f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
                setPadding(dp(8), dp(20), dp(8), dp(8))
            }
        )
    }

    /** Material card with a heading; returns the container to put rows into. */
    private fun card(title: String, subtitle: String? = null): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        content.addView(
            TextView(this).apply {
                text = title
                textSize = 17f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        if (subtitle != null) {
            content.addView(
                TextView(this).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = themeColor(com.google.android.material.R.attr.colorOutline)
            setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceVariant))
        }
        card.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(
            card,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        )
        return content
    }

    // ------------------------------------------------------------------ cards

    private fun heroCard() {
        val body = card("Status", "What is drawing the pill right now")

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(6))
        }
        heroDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(OFF)
            }
        }
        hero.addView(heroDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) })
        val heroTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroTitle = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        heroSubtitle = TextView(this).apply {
            textSize = 12f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        heroTexts.addView(heroTitle)
        heroTexts.addView(heroSubtitle)
        hero.addView(heroTexts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        masterSwitch = switchRow(
            body,
            "Pill enabled",
            "Master switch for every surface",
            TuningStore.isOn(this, TuningStore.MASTER)
        ) { on -> prefs.edit().putBoolean(TuningStore.MASTER, on).apply() }

        a11yStatus = StatusRow(this).apply { title = "Accessibility overlay" }
        body.addView(a11yStatus, rowParams())

        watcherStatus = StatusRow(this).apply { title = "Root unlock watcher" }
        body.addView(watcherStatus, rowParams())
        switchRow(
            body,
            "Use the root watcher",
            "Reads the keyguard log for an instant handover. Off falls back to the 1 s poll.",
            TuningStore.isOn(this, TuningStore.ROOT_WATCHER)
        ) { on -> prefs.edit().putBoolean(TuningStore.ROOT_WATCHER, on).apply() }

        overlayStatus = StatusRow(this).apply { title = "Draw over apps" }
        body.addView(overlayStatus, rowParams())

        overlaySwitch = switchRow(
            body,
            "Fallback overlay",
            "Only needed if the accessibility service is off",
            OverlayService.running
        ) { checked -> setOverlay(checked) }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(
            outlinedButton("Accessibility settings") {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (t: Throwable) {
                    Log.w("accessibility settings intent failed", t)
                }
            }
        )
        body.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** Tells the running service to re-read every preference. */
    private fun applySettings() {
        try {
            sendBroadcast(
                Intent(this, TuningReceiver::class.java).setAction(TuningReceiver.ACTION_RELOAD)
            )
            Toast.makeText(this, "Settings applied", Toast.LENGTH_SHORT).show()
            Log.i("settings apply requested")
        } catch (t: Throwable) {
            Log.w("apply settings failed", t)
        }
    }

    private fun lockCard() {
        val body = card("Lockscreen pill", "Bottom centre, clear of the fingerprint circle")
        surfaceSwitch(body, "Show on the lock screen", TuningStore.SURFACE_LOCK)
        lockPreview = preview(body)
        lockCaption = caption(body)
        sliderRow(body, "Width", "widthDp", TuningStore.DEFAULT_WIDTH_DP) { refreshPreviews() }
        sliderRow(body, "Height", "heightDp", TuningStore.DEFAULT_HEIGHT_DP) { refreshPreviews() }
        sliderRow(body, "Text size", "textDp", TuningStore.DEFAULT_TEXT_DP) { refreshPreviews() }
        sliderRow(body, "Corner radius (0 = pill)", "radiusDp", TuningStore.DEFAULT_RADIUS_DP) {
            refreshPreviews()
        }
        sliderRow(body, "Height above bottom edge", "bottomDp", TuningStore.DEFAULT_BOTTOM_DP, null)
        switchRow(
            body,
            "Greyscale on the always-on display",
            "Fades to grey with the AOD screen, back to colour on wake",
            TuningStore.isOn(this, TuningStore.AOD_GRAY)
        ) { on ->
            prefs.edit().putBoolean(TuningStore.AOD_GRAY, on).apply()
        }
    }

    private fun statusBarCard() {
        val body = card("Status bar pill", "Sits over the notification icons while unlocked")
        surfaceSwitch(body, "Show in the status bar", TuningStore.SURFACE_STATUS)
        sbPreview = preview(body)
        sbCaption = caption(body)
        sliderRow(body, "Width", "sbWidthDp", TuningStore.DEFAULT_SB_WIDTH_DP) { refreshPreviews() }
        sliderRow(body, "Height", "sbHeightDp", TuningStore.DEFAULT_SB_HEIGHT_DP) { refreshPreviews() }
        sliderRow(body, "Text size", "sbTextDp", TuningStore.DEFAULT_SB_TEXT_DP, null)
        sliderRow(body, "Corner radius (0 = pill)", "sbRadiusDp", TuningStore.DEFAULT_SB_RADIUS_DP, null)
        sliderRow(body, "Left edge (-1 = auto)", "sbLeftDp", TuningStore.DEFAULT_SB_LEFT_DP, null)
        sliderRow(body, "Top edge (-1 = centred)", "sbTopDp", TuningStore.DEFAULT_SB_TOP_DP, null)
    }

    /**
     * The two things Android draws in the strip bbbar's pill uses. Both need the Xposed side, so
     * the switches write Settings.Global through root, which SystemUI can read from inside.
     */
    private fun androidBarCard() {
        val body = card("Android status bar", "What Android draws where the pill sits")
        hookStatus = StatusRow(this).apply { title = "Xposed hook" }
        body.addView(hookStatus, rowParams())
        globalSwitch(
            body,
            "Hide notification icons",
            "The app icons Android puts next to the clock",
            NowBarHook.KEY_HIDE_ICONS
        )
        globalSwitch(
            body,
            "Hide Android live updates",
            "The system's own Live Update chips (bbbar's cards stay)",
            NowBarHook.KEY_HIDE_CHIPS
        )
        captionView(
            body,
            "Needs the bbbar Xposed module enabled for com.android.systemui, and one SystemUI " +
                "restart after installing. Restart it from Test tooling below.\n\n" +
                "The hook writes a heartbeat every 30 s, and this row follows it: if SystemUI " +
                "restarts without the hook, the beat goes stale and the row says how long ago. " +
                "The pill itself is an accessibility overlay and never depends on the hook.\n\n" +
                "The hook also watches the bar's own visibility, so the pill now leaves with the " +
                "status bar when a fullscreen app hides it and comes back with it."
        )
    }

    /** A switch backed by Settings.Global: root writes it, the SystemUI hook reads it. */
    private fun globalSwitch(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        key: String
    ) {
        switchRow(parent, title, subtitle, globalFlag(key)) { on ->
            Thread {
                val value = if (on) 1 else 0
                RootShell.run("settings put global $key $value")
                runOnUiThread { Log.i("$key = $value") }
            }.start()
        }
    }

    private fun globalFlag(key: String): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, key, 0) == 1
        } catch (t: Throwable) {
            // Reading arbitrary global keys is not always allowed; root can always tell us.
            RootShell.globalInt("settings get global $key") == 1
        }
    }

    /** Small grey note inside a card. */
    private fun captionView(parent: LinearLayout, text: String) {
        parent.addView(
            TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(10), 0, 0)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
    }

    private fun popupCard() {
        val body = card("Expanded popup", "Grows out of the status bar pill on tap")
        surfaceSwitch(body, "Show the expanded pop-up", TuningStore.SURFACE_POPUP)
        popupPreview = preview(body)
        popupCaption = caption(body)
        sliderRow(body, "Width", "popupWidthDp", TuningStore.DEFAULT_POPUP_WIDTH_DP, null)
        sliderRow(body, "Height", "popupHeightDp", TuningStore.DEFAULT_POPUP_HEIGHT_DP, null)
        sliderRow(body, "Text size", "popupTextDp", TuningStore.DEFAULT_POPUP_TEXT_DP) {
            refreshPreviews()
        }
        sliderRow(body, "Corner radius (0 = pill)", "popupRadiusDp", TuningStore.DEFAULT_POPUP_RADIUS_DP, null)
        sliderRow(body, "Left edge (-1 = centred)", "popupLeftDp", TuningStore.DEFAULT_POPUP_LEFT_DP, null)
        sliderRow(body, "Top edge (-1 = below status bar)", "popupTopDp", TuningStore.DEFAULT_POPUP_TOP_DP, null)
        sliderRow(
            body,
            "Extra top offset in landscape",
            "popupLandscapeTopDp",
            TuningStore.DEFAULT_POPUP_LANDSCAPE_TOP_DP,
            null
        )
        captionView(
            body,
            "Height above is a ceiling: a one-line card gets a shorter popup so there is no empty " +
                "card under it. The landscape offset pushes it clear of the bar's clock, which sits " +
                "lower there."
        )
    }

    private fun itemsCard() {
        val body = card("Items", "What the pill is allowed to show")
        featureSwitch(body, "Media", "Album art, title and play/pause", TuningStore.FEATURE_MEDIA)
        featureSwitch(body, "Timers and stopwatch", "Countdown from the clock app", TuningStore.FEATURE_TIMERS)
        featureSwitch(body, "Next alarm", "Minutes until your next alarm", TuningStore.FEATURE_ALARM)
        featureSwitch(body, "Live updates", "Any app's progress notification", TuningStore.FEATURE_LIVE)
        // Lock screen and popup simply disappear when idle. The status bar pill keeps the label,
        // and a blank label is what hides it - so there is no separate switch that can sit in the
        // wrong state and make a working bar look dead.
        captionView(
            body,
            "When nothing is active the status bar pill shows the placeholder label below. Clear " +
                "that text to keep the pill away until an activity starts."
        )

        listenerStatus = StatusRow(this).apply { title = "Notification access" }
        body.addView(listenerStatus, rowParams())

        button(body, "Notification access settings") {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (t: Throwable) {
                Log.w("notification listener settings intent failed", t)
            }
        }
    }

    private fun featureSwitch(parent: LinearLayout, title: String, subtitle: String, key: String) {
        switchRow(parent, title, subtitle, TuningStore.isOn(this, key)) { on ->
            prefs.edit().putBoolean(key, on).apply()
        }
    }

    private fun surfaceSwitch(parent: LinearLayout, title: String, key: String) {
        switchRow(parent, title, null, TuningStore.isOn(this, key)) { on ->
            prefs.edit().putBoolean(key, on).apply()
        }
    }

    private fun lookCard() {
        val body = card("Media artwork", "The cover becomes the card background")
        choiceRow(
            body,
            "Treatment",
            TuningStore.ART_MODE,
            TuningStore.artMode(this),
            listOf("plain" to "Plain", "tinted" to "Tint", "blurred" to "Blur", "both" to "Both")
        )
        switchRow(
            body,
            "Tint with the system accent",
            "Off uses the app's own blue",
            prefs.getString(TuningStore.ART_TINT, "system") != "custom"
        ) { on -> prefs.edit().putString(TuningStore.ART_TINT, if (on) "system" else "custom").apply() }
        sliderRow(
            body,
            "Blur strength",
            TuningStore.BLUR_STRENGTH,
            3,
            null
        )
        sliderRow(
            body,
            "Tint intensity",
            TuningStore.ART_TINT_STRENGTH,
            55,
            null
        )
        sliderRow(
            body,
            "Stack spacing",
            TuningStore.STACK_PEEK,
            TuningStore.DEFAULT_STACK_PEEK_DP,
            null
        )
    }

    private fun textCard() {
        val body = card("Text", "One line per card, cut off at the edge")
        switchRow(
            body,
            "Scroll long lines",
            "Holds, slides the hidden part into view, holds again and loops. Off cuts it with \u2026.",
            TuningStore.scrollText(this)
        ) { on ->
            prefs.edit().putBoolean(TuningStore.SCROLL_TEXT, on).apply()
            NowBarBridge.reload()
        }
    }

    /** A Material toggle group: one selected option, written straight to prefs. */
    private fun choiceRow(
        parent: LinearLayout,
        title: String,
        key: String,
        current: String,
        options: List<Pair<String, String>>
    ) {
        parent.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setPadding(0, dp(16), 0, dp(8))
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        for ((value, label) in options) {
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                textSize = 12f
                isCheckable = true
                tag = value
                minWidth = 0
                insetLeft = 0
                insetRight = 0
            }
            group.addView(
                button,
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            if (value == current) button.isChecked = true
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val button = group.findViewById<MaterialButton>(checkedId) ?: return@addOnButtonCheckedListener
            prefs.edit().putString(key, button.tag as String).apply()
        }
        parent.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** Ranking: which item wins when several are active. Top of the list shows first. */
    private fun rankCard() {
        val body = card("Ranking", "Top shows first. Switch an item off under Items.")
        val labels = mapOf(
            "media" to "Media",
            "timers" to "Timers and stopwatch",
            "alarm" to "Next alarm",
            "live" to "Live updates"
        )
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val order = TuningStore.rankOrder(this)
        order.forEachIndexed { index, key ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val featureKey = when (key) {
                "media" -> TuningStore.FEATURE_MEDIA
                "timers" -> TuningStore.FEATURE_TIMERS
                "alarm" -> TuningStore.FEATURE_ALARM
                else -> TuningStore.FEATURE_LIVE
            }
            val on = TuningStore.isOn(this, featureKey)
            row.addView(
                TextView(this).apply {
                    text = "${index + 1}. ${labels[key] ?: key}" + if (on) "" else "  ·  off"
                    textSize = 15f
                    alpha = if (on) 1f else 0.5f
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            row.addView(
                smallButton("▲", enabled = index > 0) { moveRank(index, -1) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )
            row.addView(
                smallButton("▼", enabled = index < order.lastIndex) { moveRank(index, 1) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(4) }
            )
            rows.addView(row, rowParams())
        }
    }

    private fun moveRank(index: Int, delta: Int) {
        val order = TuningStore.rankOrder(this).toMutableList()
        val to = index + delta
        if (to !in order.indices) return
        val moved = order.removeAt(index)
        order.add(to, moved)
        prefs.edit().putString(TuningStore.RANK_ORDER, order.joinToString(",")).apply()
        recreate()
    }

    private fun smallButton(text: String, enabled: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            .apply {
                this.text = text
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                insetLeft = 0
                insetRight = 0
                isEnabled = enabled
                setOnClickListener { onClick() }
            }

    private fun labelCard() {
        val body = card(
            "Placeholder label",
            "Shown when nothing is active. Clear it to hide the idle pill."
        )
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
                    refreshPreviews()
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        input.addView(edit)
        body.addView(
            input,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        )
    }

    /**
     * Preview of every item type, drawn by the real [PillView] with sample data, so the look of
     * each activity can be judged without music playing or a charger plugged in.
     */
    private fun simulateCard() {
        val body = card(
            "Fake activities",
            "Switch one on to put it on the real pill; it wins over live content until switched off"
        )
        simSwitches.clear()
        button(body, "Stop all fakes") {
            NowBarState.clearSimulated()
            simSwitches.forEach { it.isChecked = false }
        }
        val textDp = prefs.getInt("textDp", TuningStore.DEFAULT_TEXT_DP)
        val radiusDp = prefs.getInt("radiusDp", TuningStore.DEFAULT_RADIUS_DP)
        val h = prefs.getInt("heightDp", TuningStore.DEFAULT_HEIGHT_DP).coerceIn(40, 120)

        addPreview(body, "Media", h, textDp, radiusDp, NowBarItem(
            id = "sim|media",
            kind = ItemKind.MEDIA,
            title = "Midnight City",
            subtitle = "M83 · Hurry Up, We're Dreaming",
            progress = 0.42f,
            art = sampleArt(),
            control = null
        ))
        addPreview(body, "Timer", h, textDp, radiusDp, NowBarItem(
            id = "sim|timer",
            kind = ItemKind.TIMER,
            title = "Timer",
            subtitle = "8:00"
        ))
        addPreview(body, "Next alarm", h, textDp, radiusDp, NowBarItem(
            id = "sim|alarm",
            kind = ItemKind.ALARM,
            title = "Next alarm",
            subtitle = "in 1h 05m"
        ))
        addPreview(body, "Live update", h, textDp, radiusDp, NowBarItem(
            id = "sim|live",
            kind = ItemKind.LIVE,
            title = "Arriving in 6 min",
            subtitle = "Driver is 2 streets away",
            progress = 0.6f
        ))
    }

    private fun addPreview(
        parent: LinearLayout,
        caption: String,
        heightDp: Int,
        textDp: Int,
        radiusDp: Int,
        item: NowBarItem
    ) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(2))
        }
        header.addView(
            TextView(this).apply {
                text = "$caption  ·  tap to expand"
                textSize = 12f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        // The switch is the point of this section: it puts the fake on the live pill too.
        val fake = MaterialSwitch(this).apply {
            isChecked = NowBarState.isSimulated(item.kind)
            contentDescription = "Show $caption on the pill"
            setOnCheckedChangeListener { _, on ->
                if (on) NowBarState.putSimulated(item) else NowBarState.removeSimulated(item.kind)
            }
        }
        simSwitches.add(fake)
        header.addView(fake)
        parent.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // Tapping the example toggles the real renderer between its collapsed and expanded form.
        val preview = PillView(this).apply {
            setStyle(TuningStore.DEFAULT_LABEL, textDp, radiusDp)
            setItems(listOf(item), false, 0)
            isClickable = true
            setOnClickListener { view ->
                val expanded = view.tag != "expanded"
                view.tag = if (expanded) "expanded" else "collapsed"
                setItems(listOf(item), expanded, 0)
            }
        }
        parent.addView(
            preview,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(heightDp))
        )
    }

    /** Colourful stand-in for album art, so every artwork treatment is visible. */
    private fun sampleArt(): android.graphics.Bitmap {
        val size = 96
        val bitmap = android.graphics.Bitmap.createBitmap(
            size,
            size,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(Color.parseColor("#FF7C4DFF"), Color.parseColor("#FF00BFA5")),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return bitmap
    }

    /** Switches in the fake-activity section, so "Stop all fakes" can reset them. */
    private val simSwitches = ArrayList<com.google.android.material.materialswitch.MaterialSwitch>()

    private fun toolingCard() {
        val body = card("Test tooling", "Temporary while the layout is being dialled in")
        button(body, "Open floating tuner") {
            try {
                startForegroundService(Intent(this, TunerService::class.java))
            } catch (t: Throwable) {
                Log.e("could not start tuner", t)
            }
        }
        // The hook lives in SystemUI, so it only picks up a new build after SystemUI restarts.
        button(body, "Restart SystemUI") {
            Thread { RootShell.run("killall com.android.systemui") }.start()
        }
        button(body, "Reset to defaults") { reset() }
    }

    // ------------------------------------------------------------------- rows

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }

    private fun preview(parent: LinearLayout): SurfacePreview {
        val view = SurfacePreview(this)
        view.setColors(
            themeColor(androidx.appcompat.R.attr.colorPrimary),
            themeColor(com.google.android.material.R.attr.colorOnPrimary)
        )
        parent.addView(
            view,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
        )
        return view
    }

    private fun caption(parent: LinearLayout): TextView {
        val view = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(6), 0, 0)
        }
        parent.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return view
    }

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
        texts.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            }
        )
        if (subtitle != null) {
            texts.addView(
                TextView(this).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
            )
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
            TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val valueView = TextView(this).apply {
            textSize = 14f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        headers.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // -1 is the "auto" marker some of these keys use. It cannot go straight into the slider
        // (values below valueFrom are rejected), so the label carries it and the thumb sits at 0
        // until the slider is actually touched.
        val stored = prefs.getInt(key, default)
        val start = stored.coerceIn(0, MAX_DP)
        fun render(v: Int) {
            valueView.text = if (v < 0) "auto" else "$v dp"
        }
        render(if (stored < 0) -1 else start)

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
        val b = MaterialButton(this)
        b.text = text
        b.setOnClickListener { onClick() }
        parent.addView(
            b,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        )
    }

    private fun outlinedButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            .apply {
                this.text = text
                setOnClickListener { onClick() }
            }

    // ---------------------------------------------------------------- actions

    private fun refreshStates() {
        // Everything here is read from something that actually exists right now: the master switch,
        // the service object in this same process, and how many of its windows are attached. The
        // old version OR-ed in "the service is listed as enabled", so it said Running with the
        // master switch off, and said Stopped while the pill was drawing.
        val master = TuningStore.isOn(this, TuningStore.MASTER)
        val service = NowBarBridge.service
        val windows = service?.windowsUp() ?: 0
        val enabled = a11yServiceEnabled()
        val overlay = OverlayService.running
        val live = master && (service != null && windows > 0 || overlay)

        heroDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (live) ON else OFF)
        }
        heroTitle.text = if (live) "Running" else "Stopped"
        heroSubtitle.text = when {
            !master -> "Pill disabled by the master switch"
            service == null && enabled -> "Accessibility service enabled, not connected yet"
            service == null && overlay -> "Drawn by the fallback overlay (apps only)"
            service == null -> "Accessibility service is off"
            windows == 0 -> "Connected - nothing on screen (idle pill is hidden)"
            windows == 1 -> "Drawing one surface"
            else -> "Drawing $windows surfaces"
        }

        a11yStatus.on = service != null
        a11yStatus.state = when {
            service != null -> "connected"
            enabled -> "enabled, not connected"
            else -> "off"
        }
        val watcherWanted = TuningStore.isOn(this, TuningStore.ROOT_WATCHER)
        watcherStatus.on = watcherWanted && RootKeyguardWatcher.isStreaming
        watcherStatus.state = when {
            !watcherWanted -> "off (switch)"
            RootKeyguardWatcher.isStreaming -> "streaming (root)"
            RootKeyguardWatcher.isRunning -> "reconnecting"
            else -> "not running"
        }
        overlayStatus.state = if (overlay) "running" else "off"
        overlayStatus.on = overlay
        overlaySwitch.isChecked = overlay
        masterSwitch.isChecked = master

        val listening = listenerGranted()
        listenerStatus.on = listening
        listenerStatus.state = if (listening) "granted" else "not granted"

        probeHook()
        refreshPreviews()
    }

    /**
     * The hook lives inside SystemUI, so the app asks it through the one channel it publishes to:
     * a `Settings.Global` heartbeat, refreshed every 30 s. A logcat line is only the fallback for
     * a SystemUI that has not been restarted since this build (it has no heartbeat yet) - and the
     * log rotates, which is what made a working hook report "not loaded".
     */
    private fun probeHook() {
        if (!::hookStatus.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (hookProbeBusy || now - hookProbedAt < HOOK_PROBE_MS) return
        hookProbedAt = now
        hookProbeBusy = true
        Thread {
            var on = false
            var state = "not loaded"
            try {
                val raw = RootShell.run(
                    "test -f ${NowBarHook.KILL_SWITCH} && echo KILL; " +
                        "settings get global ${NowBarHook.KEY_HOOK_BEAT}; " +
                        "logcat -d -s NowBar | grep 'hook:' | grep -v 'root: logcat' | " +
                        "grep -E 'attached|kill switch' | tail -1"
                ).orEmpty()
                val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val kill = lines.any { it == "KILL" }
                val beat = lines.firstOrNull { it.toLongOrNull() != null }?.toLongOrNull() ?: 0L
                val logLine = lines.lastOrNull { it.contains("hook:") }
                val age = SystemClock.elapsedRealtime() - beat
                when {
                    kill -> state = "off (kill switch)"
                    beat > 0L && age in -60_000L..HOOK_STALE_MS -> {
                        on = true
                        state = "loaded"
                    }
                    beat > 0L -> state = "not loaded (last beat ${age / 1000}s ago)"
                    logLine?.contains("attached") == true -> {
                        // Running an older hook build: loaded, but no heartbeat to confirm it.
                        on = true
                        state = "loaded (no heartbeat - restart SystemUI)"
                    }
                    logLine?.contains("kill switch") == true -> state = "off (kill switch)"
                    else -> state = "not loaded"
                }
            } catch (t: Throwable) {
                Log.w("hook probe failed", t)
                state = "unknown (root unavailable)"
            }
            val finalOn = on
            val finalState = state
            runOnUiThread {
                hookProbeBusy = false
                if (::hookStatus.isInitialized) {
                    hookStatus.on = finalOn
                    hookStatus.state = finalState
                }
            }
        }.start()
    }

    private fun refreshPreviews() {
        if (!::lockPreview.isInitialized) return
        val label = prefs.getString("label", TuningStore.DEFAULT_LABEL) ?: TuningStore.DEFAULT_LABEL
        val lockW = prefs.getInt("widthDp", TuningStore.DEFAULT_WIDTH_DP)
        val lockH = prefs.getInt("heightDp", TuningStore.DEFAULT_HEIGHT_DP)
        val lockR = prefs.getInt("radiusDp", TuningStore.DEFAULT_RADIUS_DP)
        val lockT = prefs.getInt("textDp", TuningStore.DEFAULT_TEXT_DP)
        lockPreview.update(lockW, lockH, lockR, lockT, label)
        lockCaption.text = "$lockW × $lockH dp"

        val sbW = prefs.getInt("sbWidthDp", TuningStore.DEFAULT_SB_WIDTH_DP)
        val sbH = prefs.getInt("sbHeightDp", TuningStore.DEFAULT_SB_HEIGHT_DP)
        sbPreview.update(
            sbW,
            sbH,
            prefs.getInt("sbRadiusDp", TuningStore.DEFAULT_SB_RADIUS_DP),
            prefs.getInt("sbTextDp", TuningStore.DEFAULT_SB_TEXT_DP),
            label
        )
        sbCaption.text = "$sbW × $sbH dp"

        val popW = prefs.getInt("popupWidthDp", TuningStore.DEFAULT_POPUP_WIDTH_DP)
        val popH = prefs.getInt("popupHeightDp", TuningStore.DEFAULT_POPUP_HEIGHT_DP)
        popupPreview.update(
            popW,
            popH,
            prefs.getInt("popupRadiusDp", TuningStore.DEFAULT_POPUP_RADIUS_DP),
            prefs.getInt("popupTextDp", TuningStore.DEFAULT_POPUP_TEXT_DP),
            label
        )
        popupCaption.text = "$popW × $popH dp"
    }

    /** True when this app is allowed to read ongoing notifications. */
    /** True when the system lists our accessibility service as enabled. */
    private fun a11yServiceEnabled(): Boolean = try {
        val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val me = ComponentName(this, NowBarA11yService::class.java)
        manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { info ->
            val service = info.resolveInfo?.serviceInfo ?: return@any false
            service.packageName == me.packageName && service.name == me.className
        }
    } catch (t: Throwable) {
        Log.w("accessibility state unreadable", t)
        false
    }

    private fun listenerGranted(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        nm.isNotificationListenerAccessGranted(
            ComponentName(this, NowBarNotifications::class.java)
        )
    } catch (t: Throwable) {
        false
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

        private val titleView = TextView(context).apply {
            textSize = 15f
            setTextColor(context.themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        private val dot = View(context)
        private val stateView = TextView(context).apply {
            textSize = 13f
            setTextColor(context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }

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

    /**
     * Draws one surface at its configured shape, scaled down to the card width and capped in
     * height, so a 3× expanded popup still fits in the preview row.
     */
    private class SurfacePreview(context: Context) : View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private val density = resources.displayMetrics.density

        private var widthDp = TuningStore.DEFAULT_WIDTH_DP
        private var heightDp = TuningStore.DEFAULT_HEIGHT_DP
        private var radiusDp = TuningStore.DEFAULT_RADIUS_DP
        private var textDp = TuningStore.DEFAULT_TEXT_DP
        private var label = TuningStore.DEFAULT_LABEL

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

        /** Scale that fits both the card width and the 72 dp preview height. */
        private fun scaleFor(availableW: Float): Float {
            val naturalW = dp(widthDp.toFloat()).coerceAtLeast(1f)
            val naturalH = dp(heightDp.toFloat()).coerceAtLeast(1f)
            val byWidth = if (naturalW > availableW) availableW / naturalW else 1f
            val byHeight = if (naturalH > dp(72f)) dp(72f) / naturalH else 1f
            return minOf(byWidth, byHeight)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec).toFloat()
            val s = scaleFor(available)
            val h = (dp(heightDp.toFloat()).coerceAtLeast(1f) * s + dp(8f)).toInt()
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.coerceAtLeast(1))
        }

        override fun onDraw(canvas: Canvas) {
            try {
                val s = scaleFor(width.toFloat())
                val w = dp(widthDp.toFloat()).coerceAtLeast(1f) * s
                val h = dp(heightDp.toFloat()).coerceAtLeast(1f) * s
                val left = (width - w) / 2f
                val top = (height - h) / 2f
                val radius = if (radiusDp > 0) minOf(dp(radiusDp.toFloat()) * s, h / 2f) else h / 2f
                rect.set(left, top, left + w, top + h)
                canvas.drawRoundRect(rect, radius, radius, fill)

                text.textSize = dp(textDp.toFloat()) * s
                val maxW = w - dp(12f)
                val shown = if (text.measureText(label) > maxW && maxW > 0f) {
                    TextUtils.ellipsize(label, text, maxW, TextUtils.TruncateAt.END).toString()
                } else {
                    label
                }
                val baseline = top + h / 2f - (text.descent() + text.ascent()) / 2f
                canvas.drawText(shown, left + w / 2f, baseline, text)
            } catch (t: Throwable) {
                Log.w("SurfacePreview.onDraw threw", t)
            }
        }
    }
}

/** Theme attribute lookup used by the small widgets above, which have no Activity context. */
private fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    return try {
        if (!theme.resolveAttribute(attr, value, true)) Color.GRAY
        else if (value.resourceId != 0) getColor(value.resourceId) else value.data
    } catch (t: Throwable) {
        Color.GRAY
    }
}
