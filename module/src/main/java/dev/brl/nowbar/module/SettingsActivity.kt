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
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
 * The settings screen.
 *
 * Five tabs — Home, Look, Activities, Debug, About — and the Look tab drills into one sub-page per
 * surface, so the numbers for the lockscreen pill are not mixed in with the ones for the expanded
 * card. Every control writes straight into the shared preferences the overlay watches, so an edit
 * lands on the pill immediately; "Apply settings" is there for the cases where a value needs the
 * service to re-read everything at once.
 *
 * The previews are drawn by the real [PillView] with sample content, so what the card looks like
 * here is what it looks like on the lock screen, including the artwork treatment and the stack.
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

        const val TAB_HOME = "home"
        const val TAB_LOOK = "look"
        const val TAB_ACTIVITIES = "activities"
        const val TAB_DEBUG = "debug"
        const val TAB_ABOUT = "about"

        /** Sub-pages of the Look tab. */
        const val SUB_LOCK = "lock"
        const val SUB_STATUS = "status"
        const val SUB_POPUP = "popup"
        const val SUB_ART = "art"
        const val SUB_TEXT = "text"
        const val SUB_AOD = "aod"
        const val SUB_IDLE = "idle"

        const val REPO_URL = "https://github.com/BRL26/bbbar"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var toolbar: MaterialToolbar
    private lateinit var column: LinearLayout
    private lateinit var applyBar: LinearLayout
    private lateinit var tabChips: MaterialButtonToggleGroup
    private lateinit var contentScroll: NestedScrollView

    private var tab = TAB_HOME
    private var sub: String? = null

    // Widgets the refresh loop touches. They only exist on the page that built them, so they are
    // nullable and cleared whenever the page changes.
    private var heroDot: View? = null
    private var heroTitle: TextView? = null
    private var heroSubtitle: TextView? = null
    private var overlaySwitch: MaterialSwitch? = null
    private var masterSwitch: MaterialSwitch? = null
    private var listenerStatus: StatusRow? = null
    private var a11yStatus: StatusRow? = null
    private var watcherStatus: StatusRow? = null
    private var overlayStatus: StatusRow? = null
    private var hookStatus: StatusRow? = null
    private var screenRow: StatusRow? = null
    private var clockRow: StatusRow? = null
    private var shadeRow: StatusRow? = null
    private var windowsRow: StatusRow? = null

    private var lockPreview: SurfacePreview? = null
    private var sbPreview: SurfacePreview? = null
    private var popupPreview: SurfacePreview? = null
    private var lockCaption: TextView? = null
    private var sbCaption: TextView? = null
    private var popupCaption: TextView? = null

    /** Whether the Home preview is in its expanded form. */
    private var previewExpanded = false
    private var homePreview: PillView? = null
    private var homePreviewCaption: TextView? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TuningStore.prefs(this)
        Log.sync(this)
        setContentView(buildScaffold())
        renderPage()
        registerBackHandling()
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

    /**
     * Back walks the screen instead of leaving it: a sub-page goes back to its tab, a side tab
     * goes home, and only then does back close the app.
     */
    private fun registerBackHandling() {
        try {
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        when {
                            sub != null -> {
                                sub = null
                                renderPage()
                            }
                            tab != TAB_HOME -> {
                                tab = TAB_HOME
                                sub = null
                                renderPage()
                            }
                            // Home is the root: let the system take the back press.
                            else -> {
                                isEnabled = false
                                onBackPressedDispatcher.onBackPressed()
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w("back handling failed", t)
        }
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

        // Tabs, in a horizontal scroller so a narrow screen scrolls them rather than squeezing
        // every label into a stub.
        tabChips = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(0, 0, 0, 0)
        }
        val tabs = listOf(
            TAB_HOME to "Home",
            TAB_LOOK to "Look",
            TAB_ACTIVITIES to "Activities",
            TAB_DEBUG to "Debug",
            TAB_ABOUT to "About"
        )
        tabs.forEach { (key, label) ->
            val chip = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                textSize = 12f
                isCheckable = true
                isAllCaps = false
                tag = key
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                // Five tabs across the screen: Material's internal padding is the difference
                // between all of them fitting and the last one being clipped.
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    if (tab == key && sub == null) return@setOnClickListener
                    tab = key
                    sub = null
                    renderPage()
                }
            }
            tabChips.addView(
                chip,
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
            )
            if (key == tab) chip.isChecked = true
        }

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(48))
        }
        val scroll = NestedScrollView(this).apply { isFillViewport = true }
        scroll.addView(column, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        contentScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Always-visible action bar for Apply: a settings screen needs an obvious way to push the
        // whole config at once, even though edits already land live.
        applyBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        applyBar.addView(
            MaterialButton(this).apply {
                text = "Apply settings"
                textSize = 13f
                setOnClickListener { applySettings() }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        root.addView(applyBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Tabs pinned to the bottom, the way a phone app is navigated: the thumb reaches them and
        // the content above keeps the whole screen to scroll in.
        root.addView(
            tabChips,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(6)
            }
        )

        // Edge-to-edge: keep the toolbar below the status bar and above the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(0, bars.top, 0, 0)
            column.setPadding(dp(16), dp(4), dp(16), dp(24))
            applyBar.setPadding(dp(16), dp(4), dp(16), dp(4))
            tabChips.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        return root
    }

    /** Rebuilds the visible page. Cheap: these are a few dozen views, built on a tab press. */
    private fun renderPage() {
        column.removeAllViews()
        // Everything the ticker touches belongs to the page that just went away.
        heroDot = null
        heroTitle = null
        heroSubtitle = null
        overlaySwitch = null
        masterSwitch = null
        listenerStatus = null
        a11yStatus = null
        watcherStatus = null
        overlayStatus = null
        hookStatus = null
        screenRow = null
        clockRow = null
        shadeRow = null
        windowsRow = null
        lockPreview = null
        sbPreview = null
        popupPreview = null
        lockCaption = null
        sbCaption = null
        popupCaption = null
        homePreview = null
        homePreviewCaption = null

        val page = sub
        if (page != null) {
            backRow()
            when (page) {
                SUB_LOCK -> lockPage()
                SUB_STATUS -> statusBarPage()
                SUB_POPUP -> popupPage()
                SUB_ART -> artPage()
                SUB_TEXT -> textPage()
                SUB_AOD -> aodPage()
                SUB_IDLE -> idlePage()
            }
        } else {
            when (tab) {
                TAB_HOME -> homePage()
                TAB_LOOK -> lookPage()
                TAB_ACTIVITIES -> activitiesPage()
                TAB_DEBUG -> debugPage()
                else -> aboutPage()
            }
        }
        // A new page starts at its own top: keeping the old offset landed sub-pages half-scrolled,
        // with the back button above the fold.
        contentScroll.scrollTo(0, 0)
        // The chips are told which tab is current: a reset (or any code that moves the tab) has
        // to move the selection with it, or the highlighted tab lies about the page below it.
        syncTabChips()
        // The page is fresh, so the rows read out of nothing would look empty until the next tick.
        refreshStates()
    }

    private fun openSub(name: String) {
        sub = name
        renderPage()
    }

    private fun backRow() {
        column.addView(
            outlinedButton("\u2190  Back") { sub = null; renderPage() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(4) }
        )
    }

    // ------------------------------------------------------------------ pages

    private fun homePage() {
        heroCard()
        homePreviewCard()
        labelCard(
            "Idle line",
            "What the pill says when nothing is active. A blank line leaves it without text."
        )
    }

    private fun lookPage() {
        val quick = card("Surfaces", "Where the pill is allowed to appear")
        surfaceSwitch(quick, "Lockscreen", TuningStore.SURFACE_LOCK)
        // The status bar pill has no switch: it is what replaces Android's battery and signal
        // icons, so it belongs on the strip whenever the phone is unlocked.
        surfaceSwitch(quick, "Expanded card", TuningStore.SURFACE_POPUP)
        switchRow(
            quick,
            "Measure mode (outline only)",
            "Every surface is drawn as an empty outline with no content, and stays up with " +
                "nothing active, so sizes, edges and corner radii can be dialled in on the " +
                "real screen.",
            TuningStore.outline(this)
        ) { on -> prefs.edit().putBoolean(TuningStore.OUTLINE, on).apply() }

        val body = card("Customization", "One page per surface and for the look of the cards")
        navRow(
            body,
            "Lockscreen pill",
            "Size, corner radius, height above the bottom edge",
            3
        ) { openSub(SUB_LOCK) }
        navRow(
            body,
            "Status bar pill",
            "Size, left and top edges, landscape and Quick Settings",
            2
        ) { openSub(SUB_STATUS) }
        navRow(
            body,
            "Expanded card",
            "The detail view that grows out of the pill",
            3
        ) { openSub(SUB_POPUP) }
        navRow(body, "Media artwork", "Plain, tinted, blurred or both", 3) { openSub(SUB_ART) }
        navRow(body, "Text and motion", "Scrolling lines, stack spacing", 3) { openSub(SUB_TEXT) }
        navRow(body, "Always-on display", "Greyscale, outline style, dot lattice", 3) {
            openSub(SUB_AOD)
        }
        navRow(body, "Idle line", "The text shown when nothing is active", 3) { openSub(SUB_IDLE) }
        captionView(
            body,
            "The idle line also appears on Home: it is the one thing edited from both places."
        )
    }

    private fun activitiesPage() {
        itemsCard()
        rankCard()
        androidBarCard()
        simulateCard()
    }

    private fun debugPage() {
        serviceCard()
        debugLevelCard()
        debugDetailCard()
        toolingCard()
    }

    /** The plumbing: what is connected, what is not, and the permissions behind both. */
    private fun serviceCard() {
        val body = card("Service", "What is running behind the pill")
        a11yStatus = StatusRow(this).apply { label = "Accessibility overlay" }
        body.addView(a11yStatus, rowParams())

        watcherStatus = StatusRow(this).apply { label = "Root unlock watcher" }
        body.addView(watcherStatus, rowParams())
        switchRow(
            body,
            "Use the root watcher",
            "Reads the keyguard log for an instant handover. Off falls back to the 1 s poll.",
            TuningStore.isOn(this, TuningStore.ROOT_WATCHER)
        ) { on -> prefs.edit().putBoolean(TuningStore.ROOT_WATCHER, on).apply() }

        overlayStatus = StatusRow(this).apply { label = "Draw over apps" }
        body.addView(overlayStatus, rowParams())
        overlaySwitch = switchRow(
            body,
            "Fallback overlay",
            "Only needed if the accessibility service is off",
            OverlayService.running
        ) { checked -> setOverlay(checked) }

        listenerStatus = StatusRow(this).apply { label = "Notification access" }
        body.addView(listenerStatus, rowParams())

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
        actions.addView(
            outlinedButton("Notification access") {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (t: Throwable) {
                    Log.w("notification listener settings intent failed", t)
                }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) }
        )
        body.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun aboutPage() {
        val body = card("bbbar", "Now Bar for Nothing OS")
        captionView(
            body,
            "A Now Bar style pill for the lockscreen, the always-on display and the status bar: " +
                "one line that grows into the card you tap it for. The card follows whatever is " +
                "playing or counting down, and stacks the rest underneath."
        )
        captionView(
            body,
            "There is no network code in this app or in the module: everything it draws comes " +
                "from notifications you already have and the media session on your phone."
        )

        val info = card("Build", "What is installed right now")
        infoRow(info, "Version", versionName())
        infoRow(info, "Package", packageName)
        infoRow(info, "Surfaces", "lockscreen · status bar · expanded")
        infoRow(info, "Hook", "LSPosed, com.android.systemui only")
        button(info, "Open the repository") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
            } catch (t: Throwable) {
                Log.w("repository link failed", t)
                Toast.makeText(this, REPO_URL, Toast.LENGTH_LONG).show()
            }
        }

        val reset = card("Reset", "Back to the defaults this build ships with")
        captionView(
            reset,
            "Clears every stored value: sizes, positions, colours, the rank order and the " +
                "idle line. The service and the hook stay installed and enabled, and the pill " +
                "reloads straight away."
        )
        button(reset, "Reset all settings") { confirmReset() }

        val notes = card("Thanks", "Standing on other people's work")
        captionView(
            notes,
            "Built for a Nothing Phone (3a) on Nothing OS 5.0 / Android 17, with Magisk and " +
                "LSPosed. The pill is an accessibility overlay; the module's hook only steps out " +
                "of the way by hiding Android's own notification icons in the status bar."
        )
    }

    /** Wipes the tuning prefs after a confirmation, then reloads the module. */
    private fun confirmReset() {
        try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Reset all settings?")
                .setMessage(
                    "Every stored setting goes back to its default and the pill reloads. " +
                        "This cannot be undone."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ -> doReset() }
                .show()
        } catch (t: Throwable) {
            Log.w("reset dialog failed", t)
        }
    }

    private fun doReset() {
        try {
            // A complete default file, not an empty one: this build's layout is explicit there.
            TuningStore.rewriteDefaults(this)
            // The "an unlock happened in this boot" marker lives in its own file, so it has to be
            // cleared by hand - it is a session fact, not a setting.
            getSharedPreferences("nowbar_session", MODE_PRIVATE).edit().clear().apply()
            Log.sync(this)
            // The hook's flags live in Settings.Global, not in these prefs: put both back too, or
            // a reset would leave Android's icons hidden (or its clock) with the pill elsewhere.
            pushHookLayout()
            sendBroadcast(
                Intent(this, TuningReceiver::class.java).setAction(TuningReceiver.ACTION_RELOAD)
            )
            tab = TAB_HOME
            sub = null
            renderPage()
            Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show()
            Log.i("settings reset to defaults")
        } catch (t: Throwable) {
            Log.w("reset failed", t)
        }
    }

    // ------------------------------------------------------------- home cards

    private fun heroCard() {
        val body = card("Status", "What is drawing the pill right now")

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(6))
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(OFF)
            }
        }
        heroDot = dot
        hero.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) })
        val heroTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val subtitle = TextView(this).apply {
            textSize = 12f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        heroTitle = title
        heroSubtitle = subtitle
        heroTexts.addView(title)
        heroTexts.addView(subtitle)
        hero.addView(heroTexts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        masterSwitch = MaterialSwitch(this).apply {
            isChecked = TuningStore.isOn(this@SettingsActivity, TuningStore.MASTER)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(TuningStore.MASTER, on).apply()
                if (on) AllOff.on(this@SettingsActivity) else AllOff.off(this@SettingsActivity)
            }
        }
        hero.addView(masterSwitch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        body.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        captionView(
            body,
            "Off stops the bar and everything bbbar runs in the background: the root keeper, the " +
                "keyguard log stream and the watchdog. The accessibility permission stays as it " +
                "is, so turning it back on needs nothing else. The switches for the service and " +
                "the hook are under Debug."
        )
    }

    /**
     * The bar itself, not a mock-up of it: the pill draws exactly what the service is drawing -
     * the same items, in the same rank order, at the same sizes. Tap it to see the expanded card.
     */
    private fun homePreviewCard() {
        val body = card("Preview", "The bar as it is right now, drawn by the pill itself")

        val surface = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = themeColor(com.google.android.material.R.attr.colorOutline)
            setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(18), dp(10), dp(18))
        }
        val pill = PillView(this).apply {
            isClickable = true
            setOnClickListener {
                previewExpanded = !previewExpanded
                renderPreview()
            }
        }
        homePreview = pill
        holder.addView(pill, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        surface.addView(holder)
        body.addView(
            surface,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        )
        homePreviewCaption = TextView(this).apply {
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(10), 0, 0)
        }
        body.addView(homePreviewCaption, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        renderPreview()
    }

    private fun renderPreview() {
        val pill = homePreview ?: return
        try {
            // Read from the same place the service reads: rank order, fakes and whatever the
            // notification listener found. With nothing active this is the idle line, exactly as
            // the status bar would show it.
            val items = NowBarState.items(this)
            val t = Tuning.current(this)
            pill.allowIdle = true
            pill.setStyle(t.label, t.textDp, t.radiusDp)
            pill.setItems(items, previewExpanded, TuningStore.stackPeekDp(this))
            // Keep the card inside the page: the pill lays itself out at the tuned size, and a
            // 393 dp expanded card is wider than the space between the card's padding.
            val w = if (previewExpanded) {
                maxOf(t.widthDp, prefs.getInt("popupWidthDp", TuningStore.DEFAULT_POPUP_WIDTH_DP))
            } else {
                t.widthDp
            }
            val h = if (previewExpanded) maxOf(t.heightDp, 140) else t.heightDp
            val density = resources.displayMetrics.density
            val wantW = (w * density).toInt()
            val wantH = (h * density).toInt()
            val lp = pill.layoutParams
            if (lp == null || lp.width != wantW || lp.height != wantH) {
                pill.layoutParams = LinearLayout.LayoutParams(wantW, wantH)
                pill.requestLayout()
            }
            homePreviewCaption?.text = when {
                items.isEmpty() ->
                    "Nothing active, so the status bar shows the idle line \"${t.label}\"."
                items.size == 1 -> {
                    val item = items.first()
                    val form = if (previewExpanded) "expanded" else "collapsed"
                    "${item.kind.name.lowercase()} \u00b7 $form \u00b7 tap to ${
                        if (previewExpanded) "collapse" else "expand"
                    }"
                }
                else -> "${items.size} stacked \u00b7 the first one is what the bar shows " +
                    "\u00b7 tap to ${if (previewExpanded) "collapse" else "expand"}"
            }
        } catch (t: Throwable) {
            Log.w("home preview failed", t)
        }
    }

    // ------------------------------------------------------------- look pages

    private fun lockPage() {
        val body = card("Lockscreen pill", "Bottom centre, clear of the fingerprint circle")
        surfaceSwitch(body, "Show on the lock screen", TuningStore.SURFACE_LOCK)
        lockPreview = preview(body)
        lockCaption = caption(body)
        sliderRow(body, "Width", "widthDp", TuningStore.DEFAULT_WIDTH_DP) { refreshPreviews() }
        sliderRow(body, "Height", "heightDp", TuningStore.DEFAULT_HEIGHT_DP) { refreshPreviews() }
        sliderRow(body, "Text size", "textDp", TuningStore.DEFAULT_TEXT_DP) {
            refreshPreviews()
            renderPreview()
        }
        sliderRow(body, "Corner radius (0 = pill)", "radiusDp", TuningStore.DEFAULT_RADIUS_DP) {
            refreshPreviews()
            renderPreview()
        }
        sliderRow(body, "Height above bottom edge", "bottomDp", TuningStore.DEFAULT_BOTTOM_DP, null)
        captionView(
            body,
            "The height above the bottom edge keeps the pill clear of the under-screen " +
                "fingerprint circle."
        )
    }

    private fun statusBarPage() {
        val body = card(
            "Status bar pill",
            "Always on the strip; right end, left end, or the whole left side"
        )
        sbPreview = preview(body)
        sbCaption = caption(body)
        // Placement: a store change reaches the overlay; the global flags reach the hook, which
        // owns hiding Android's cluster (right end) or its clock (clock-less left end).
        choiceRow(
            body,
            "Placement",
            TuningStore.SB_SIDE,
            TuningStore.sbSide(this),
            // Short on purpose: three buttons share one row, and a longer label is ellipsized
            // into something unreadable. The caption below spells out what each one does.
            listOf(
                TuningStore.SB_SIDE_RIGHT to "Right",
                TuningStore.SB_SIDE_LEFT to "Left",
                TuningStore.SB_SIDE_LEFT_NO_CLOCK to "Left, no clock"
            )
        ) {
            pushHookLayout()
            // The edge control follows the layout: a left edge only means something when the
            // pill is not anchored to a fixed edge.
            renderPage()
        }
        choiceRow(
            body,
            "When idle",
            TuningStore.IDLE_CONTENT,
            TuningStore.idleContent(this),
            listOf(
                TuningStore.IDLE_CONTENT_LABEL to "Text",
                TuningStore.IDLE_CONTENT_CLOCK to "Clock",
                TuningStore.IDLE_CONTENT_CLOCK_THEN_LABEL to "Clock + text"
            )
        ) { refreshPreviews() }
        choiceRow(
            body,
            "Battery / signal order",
            TuningStore.STATUS_ICON_ORDER,
            prefs.getString(TuningStore.STATUS_ICON_ORDER, TuningStore.DEFAULT_STATUS_ICON_ORDER)
                ?: TuningStore.DEFAULT_STATUS_ICON_ORDER,
            listOf(
                TuningStore.STATUS_ICON_ORDER_BATTERY_FIRST to "Battery first",
                TuningStore.STATUS_ICON_ORDER_SIGNAL_FIRST to "Signal first"
            )
        )
        sliderRow(body, "Width", "sbWidthDp", TuningStore.DEFAULT_SB_WIDTH_DP) { refreshPreviews() }
        sliderRow(body, "Height", "sbHeightDp", TuningStore.DEFAULT_SB_HEIGHT_DP) {
            refreshPreviews()
        }
        sliderRow(body, "Text size", "sbTextDp", TuningStore.DEFAULT_SB_TEXT_DP, null)
        sliderRow(body, "Corner radius (0 = pill)", "sbRadiusDp", TuningStore.DEFAULT_SB_RADIUS_DP, null)
        when (TuningStore.sbSide(this)) {
            TuningStore.SB_SIDE_RIGHT -> sliderRow(
                body,
                "Right margin",
                TuningStore.SB_RIGHT_MARGIN,
                TuningStore.DEFAULT_SB_RIGHT_MARGIN_DP,
                null
            )
            // The clock-less left side is the right-hand layout mirrored, so it moves with the
            // same number - read as a left margin.
            TuningStore.SB_SIDE_LEFT_NO_CLOCK -> sliderRow(
                body,
                "Left margin",
                TuningStore.SB_RIGHT_MARGIN,
                TuningStore.DEFAULT_SB_RIGHT_MARGIN_DP,
                null
            )
            else -> sliderRow(
                body,
                "Left edge (-1 = auto)",
                "sbLeftDp",
                TuningStore.DEFAULT_SB_LEFT_DP,
                null
            )
        }
        sliderRow(body, "Top edge (-1 = centred)", "sbTopDp", TuningStore.DEFAULT_SB_TOP_DP, null)
        // Landscape is right-anchored, so it takes a width of its own (a landscape strip is wide)
        // and its own right margin, in place of the left edge the portrait layout uses.
        sliderRow(
            body,
            "Landscape width (-1 = portrait width)",
            TuningStore.SB_LANDSCAPE_WIDTH,
            TuningStore.DEFAULT_SB_LANDSCAPE_WIDTH_DP,
            null
        )
        sliderRow(
            body,
            "Landscape right margin (-1 = portrait margin)",
            TuningStore.SB_LANDSCAPE_RIGHT,
            TuningStore.DEFAULT_SB_LANDSCAPE_RIGHT_DP,
            null
        )
        sliderRow(
            body,
            "Landscape top edge (-1 = same as portrait)",
            "sbLandscapeTopDp",
            TuningStore.DEFAULT_SB_LANDSCAPE_TOP_DP,
            null
        )
        captionView(
            body,
            "Right is Android's battery / network corner: the hook hides that cluster and the " +
                "privacy dot, brings the notification icons back, and the pill draws the battery " +
                "and signal itself. Left puts it back over the notification icons, after the clock. " +
                "Left, no clock hides the bar's clock (portrait) so the pill has that whole side, " +
                "and takes it off the lock screen too: " +
                "it uses this page's measurements with the margin read from the left. Landscape " +
                "always takes the right end, so the right margin is what moves it there and -1 " +
                "means \"use the portrait value\". \"When idle\" applies only with nothing active: " +
                "clock draws the time in the pill, clock + text puts the placeholder after it."
        )
    }

    /**
     * Writes both strip flags to Settings.Global at once: the hook owns what Android draws in the
     * strip - the right-hand icon cluster and privacy dot for the right-end layout, the clock for
     * the clock-less left layout.
     */
    private fun pushHookLayout() {
        val inverse = if (TuningStore.sbInverse(this)) 1 else 0
        // The clock is only hidden by the clock-less left side, and each element also needs its
        // own switch (issue #12), so a visible element stays visible.
        val clock = if (TuningStore.sbClockHidden(this) && TuningStore.hookHideClock(this)) 1 else 0
        val cluster = if (inverse == 1 && TuningStore.hookHideCluster(this)) 1 else 0
        val privacy = if (inverse == 1 && TuningStore.hookHidePrivacy(this)) 1 else 0
        Thread {
            RootShell.run("settings put global ${NowBarHook.KEY_INVERSE} $inverse")
            RootShell.run("settings put global ${NowBarHook.KEY_HIDE_CLOCK} $clock")
            RootShell.run("settings put global ${NowBarHook.KEY_HIDE_SYS_ICONS} $cluster")
            RootShell.run("settings put global ${NowBarHook.KEY_HIDE_PRIVACY} $privacy")
            runOnUiThread {
                Log.i("strip flags: inverse=$inverse clock=$clock cluster=$cluster privacy=$privacy")
            }
        }.start()
    }

    private fun popupPage() {
        val body = card("Expanded card", "Grows out of the status bar pill on tap")
        surfaceSwitch(body, "Show the expanded card", TuningStore.SURFACE_POPUP)
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
            "Height is a ceiling: a one-line card gets a shorter card, so there is no empty space " +
                "under it. The card is centred in both orientations."
        )
    }

    private fun artPage() {
        val body = card("Media artwork", "The cover becomes the card background")
        choiceRow(
            body,
            "Treatment",
            TuningStore.ART_MODE,
            TuningStore.artMode(this),
            listOf("plain" to "Plain", "tinted" to "Tint", "blurred" to "Blur", "both" to "Both")
        ) { renderPreview() }
        switchRow(
            body,
            "Tint with the system accent",
            "Off uses the app's own blue",
            prefs.getString(TuningStore.ART_TINT, "system") != "custom"
        ) { on -> prefs.edit().putString(TuningStore.ART_TINT, if (on) "system" else "custom").apply() }
        sliderRow(body, "Blur strength", TuningStore.BLUR_STRENGTH, 3, null)
        sliderRow(body, "Tint intensity", TuningStore.ART_TINT_STRENGTH, 55, null)
        captionView(
            body,
            "Blur is a down-scaled copy of the cover, so a high strength is soft rather than " +
                "blocky. Both applies the tint over the blurred copy, which keeps the text " +
                "readable."
        )
    }

    private fun textPage() {
        val body = card("Text and motion", "How a long line and a stack of cards behave")
        switchRow(
            body,
            "Scroll long lines",
            "Holds, slides the hidden part into view, holds again and loops. Off cuts it with \u2026.",
            TuningStore.scrollText(this)
        ) { on ->
            prefs.edit().putBoolean(TuningStore.SCROLL_TEXT, on).apply()
            NowBarBridge.reload()
        }
        sliderRow(body, "Stack spacing", TuningStore.STACK_PEEK, TuningStore.DEFAULT_STACK_PEEK_DP) {
            renderPreview()
        }
        captionView(
            body,
            "Stack spacing is how much of each card behind the front one stays visible: the " +
                "cards underneath are drawn as their own line, so a stack reads as a list."
        )
    }

    private fun aodPage() {
        val body = card("Always-on display", "The still screen, drawn by the same pill")
        switchRow(
            body,
            "Greyscale on the always-on display",
            "Fades to grey with the AOD screen, back to colour on wake",
            TuningStore.isOn(this, TuningStore.AOD_GRAY)
        ) { on -> prefs.edit().putBoolean(TuningStore.AOD_GRAY, on).apply() }
        switchRow(
            body,
            "Line + dots on the AOD",
            "Outline instead of a filled card, with the progress as a drifting dot pattern",
            TuningStore.isOn(this, TuningStore.AOD_LINE_STYLE)
        ) { on -> prefs.edit().putBoolean(TuningStore.AOD_LINE_STYLE, on).apply() }
        sliderInRange(
            body,
            "AOD dot spacing",
            TuningStore.AOD_DOT_CELL,
            TuningStore.DEFAULT_AOD_DOT_CELL_DP,
            2..24
        )
        sliderInRange(
            body,
            "AOD dot size",
            TuningStore.AOD_DOT_SIZE,
            TuningStore.DEFAULT_AOD_DOT_SIZE_DP,
            1..16
        )
        captionView(
            body,
            "An AOD overlay is not composited while the display dozes, so these are read when the " +
                "screen is still on with the pill fading to grey."
        )
    }

    private fun idlePage() {
        labelCard(
            "Idle line",
            "Shown when nothing is active. Clear it to leave the pill with just its icons."
        )
        val body = card("Where it shows", "The idle line is not drawn everywhere")
        captionView(
            body,
            "The lockscreen pill and the expanded card simply disappear when nothing is active. " +
                "The status bar pill never does: it stands in for Android's battery and signal " +
                "icons, so a blank line just means it draws those without any text."
        )
    }

    // -------------------------------------------------------- activities cards

    private fun itemsCard() {
        val body = card("Items", "What the pill is allowed to show")
        featureSwitch(body, "Media", "Album art, title and play/pause", TuningStore.FEATURE_MEDIA)
        featureSwitch(body, "Timers and stopwatch", "Countdown from the clock app", TuningStore.FEATURE_TIMERS)
        featureSwitch(body, "Next alarm", "Minutes until your next alarm", TuningStore.FEATURE_ALARM)
        featureSwitch(body, "Live updates", "Any app's progress notification", TuningStore.FEATURE_LIVE)

        captionView(
            body,
            "Notification access is under Debug, with the rest of the permissions."
        )
    }

    /** Ranking: which item wins when several are active. Top of the list shows first. */
    private fun rankCard() {
        val body = card("Ranking", "Top shows first. Swiping the cards reorders them on the fly.")
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
                smallButton("\u25b2", enabled = index > 0) { moveRank(index, -1) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )
            row.addView(
                smallButton("\u25bc", enabled = index < order.lastIndex) { moveRank(index, 1) },
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
        renderPage()
    }

    /**
     * The two things Android draws in the strip bbbar's pill uses. Both need the Xposed side, so
     * the switches write Settings.Global through root, which SystemUI can read from inside.
     */
    private fun androidBarCard() {
        val body = card("Android status bar", "What Android draws where the pill sits")
        hookStatus = StatusRow(this).apply { label = "Xposed hook" }
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
        // The other three hook switches are per placement element: they live here with the hook's
        // own status row, instead of at the bottom of the strip page where they were unfindable.
        switchRow(
            body,
            "Hide Android's clock",
            "The clock-less left side: the pill draws the time instead",
            TuningStore.hookHideClock(this)
        ) { on ->
            prefs.edit().putBoolean(TuningStore.HOOK_HIDE_CLOCK, on).apply()
            pushHookLayout()
        }
        switchRow(
            body,
            "Hide battery / network icons",
            "The right side: the pill draws its own battery and signal",
            TuningStore.hookHideCluster(this)
        ) { on ->
            prefs.edit().putBoolean(TuningStore.HOOK_HIDE_CLUSTER, on).apply()
            pushHookLayout()
            refreshPreviews()
        }
        switchRow(
            body,
            "Hide the privacy indicator",
            "The right side: the camera / microphone dot would sit on the pill",
            TuningStore.hookHidePrivacy(this)
        ) { on ->
            prefs.edit().putBoolean(TuningStore.HOOK_HIDE_PRIVACY, on).apply()
            pushHookLayout()
        }
        captionView(
            body,
            "Needs the bbbar module enabled for com.android.systemui in LSPosed, and one SystemUI " +
                "restart after installing a new build - restart it from Debug. The hook writes a " +
                "heartbeat every 30 s, which is what the row above follows."
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
                setItems(listOf(item), expanded, TuningStore.stackPeekDp(this@SettingsActivity))
            }
        }
        parent.addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, dp(heightDp)))
    }

    // ---------------------------------------------------------------- debug

    private fun debugLevelCard() {
        val body = card("Debug level", "How much the module writes to the log while it runs")
        choiceRow(
            body,
            "Level",
            TuningStore.DEBUG_LEVEL,
            TuningStore.debugLevel(this).toString(),
            listOf("0" to "Off", "1" to "Basic", "2" to "Verbose")
        ) {
            Log.sync(this)
        }
        captionView(
            body,
            "Off: warnings and errors only. Basic: what each surface placed and when a card " +
                "changed. Verbose: everything, including every notification read and every " +
                "placement decision - useful when the pill is in the wrong place, loud otherwise.\n\n" +
                "Read the log with:  adb logcat -s NowBar"
        )
    }

    private fun debugDetailCard() {
        val body = card("Live state", "Read from the device, refreshed while this screen is open")
        windowsRow = StatusRow(this).apply { label = "Windows on screen" }
        body.addView(windowsRow, rowParams())
        screenRow = StatusRow(this).apply { label = "Screen" }
        body.addView(screenRow, rowParams())
        clockRow = StatusRow(this).apply { label = "Clock right edge" }
        body.addView(clockRow, rowParams())
        shadeRow = StatusRow(this).apply { label = "Quick Settings" }
        body.addView(shadeRow, rowParams())
        val level = TuningStore.debugLevel(this)
        captionView(
            body,
            if (level >= 2) {
                "Verbose is on: every notification read and every placement is in the log."
            } else {
                "Switch the level to Verbose to see every notification read and placement in the log."
            }
        )
    }

    private fun toolingCard() {
        val body = card("Tools", "For when a value needs a nudge or the hook needs a restart")
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
            Toast.makeText(this, "SystemUI restarting", Toast.LENGTH_SHORT).show()
        }
        outlinedButtonOrButton(body, "Write the current tuning to the log") {
            sendBroadcast(
                Intent(this, TuningReceiver::class.java).setAction(TuningReceiver.ACTION_RELOAD)
            )
            prefs.edit().apply()
            val dump = Intent(this, TuningReceiver::class.java)
                .putExtra("dump", true)
            sendBroadcast(dump)
            Toast.makeText(this, "Dumped to logcat -s NowBar", Toast.LENGTH_SHORT).show()
        }
        button(body, "Reset to defaults") { reset() }
    }

    private fun outlinedButtonOrButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(
            outlinedButton(text, onClick),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        )
    }

    // ------------------------------------------------------------------ cards

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

    /** A tappable row that opens a sub-page: title, subtitle, chevron. */
    private fun navRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        indent: Int,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            }
        )
        texts.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(
            TextView(this).apply {
                text = "\u203a"
                textSize = 20f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) }
        )
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // A hairline under the row, so a list of them reads as a list rather than a paragraph.
        parent.addView(
            View(this).apply {
                setBackgroundColor(themeColor(com.google.android.material.R.attr.colorOutline))
                alpha = 0.25f
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(10) }
        )
        if (indent < 0) {
            Log.w("navRow: negative indent $indent ignored")
        }
    }

    private fun infoRow(parent: LinearLayout, title: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )
        parent.addView(row, rowParams())
    }

    /** Tells the running service to re-read every preference. */
    private fun applySettings() {
        try {
            Log.sync(this)
            sendBroadcast(
                Intent(this, TuningReceiver::class.java).setAction(TuningReceiver.ACTION_RELOAD)
            )
            Toast.makeText(this, "Settings applied", Toast.LENGTH_SHORT).show()
            Log.i("settings apply requested")
        } catch (t: Throwable) {
            Log.w("apply settings failed", t)
        }
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
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(10), 0, 0)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
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

    /** A Material toggle group: one selected option, written straight to prefs. */
    private fun choiceRow(
        parent: LinearLayout,
        title: String,
        key: String,
        current: String,
        options: List<Pair<String, String>>,
        onChosen: (() -> Unit)? = null
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
                isAllCaps = false
                tag = value
                minWidth = 0
                insetLeft = 0
                insetRight = 0
            }
            group.addView(button, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (value == current) button.isChecked = true
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val button = group.findViewById<MaterialButton>(checkedId) ?: return@addOnButtonCheckedListener
            val value = button.tag as String
            if (key == TuningStore.DEBUG_LEVEL) {
                prefs.edit().putInt(key, value.toIntOrNull() ?: 1).apply()
            } else {
                prefs.edit().putString(key, value).apply()
            }
            onChosen?.invoke()
        }
        parent.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

    /**
     * The idle line, in a text field.
     *
     * Also lives on Home: it is the one value worth editing without hunting for the page it
     * belongs to, and the same editor is what keeps both copies in step.
     */
    private fun labelCard(title: String, subtitle: String) {
        val body = card(title, subtitle)
        val input = TextInputLayout(this).apply {
            hint = "Text shown in the pill"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            val corner = dp(14).toFloat()
            setBoxCornerRadii(corner, corner, corner, corner)
        }
        val edit = TextInputEditText(this).apply {
            setText(prefs.getString(TuningStore.LABEL, TuningStore.DEFAULT_LABEL))
            setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit()
                        .putString(TuningStore.LABEL, s?.toString() ?: TuningStore.DEFAULT_LABEL)
                        .apply()
                    refreshPreviews()
                    renderPreview()
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
        captionView(
            body,
            "A blank line is simply not drawn: the status pill keeps its battery and signal, " +
                "with no text beside them."
        )
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

    /** A dp slider over the full `0..1000` range, which is what every size uses. */
    private fun sliderRow(
        parent: LinearLayout,
        title: String,
        key: String,
        default: Int,
        onChange: (() -> Unit)?
    ) = slider(parent, title, key, default, 0..MAX_DP, onChange)

    /** A slider over a narrow range, for settings whose useful values are a handful of dp. */
    private fun sliderInRange(
        parent: LinearLayout,
        title: String,
        key: String,
        default: Int,
        range: IntRange
    ) = slider(parent, title, key, default, range, null)

    private fun slider(
        parent: LinearLayout,
        title: String,
        key: String,
        default: Int,
        range: IntRange,
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
        // A slider over 0…1000 dp cannot be landed on an exact number by hand, so the value is
        // also a text field: type the measurement, press done, and the thumb follows it.
        val valueBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val valueView = EditText(this).apply {
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            minWidth = dp(44)
            setSelectAllOnFocus(true)
            contentDescription = "$title value in dp, or -1 for auto"
        }
        val unitView = TextView(this).apply {
            text = "dp"
            textSize = 14f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(2), 0, 0, 0)
        }
        valueBox.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        valueBox.addView(unitView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        headers.addView(valueBox, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // -1 is the "auto" marker some of these keys use. It cannot go straight into the slider
        // (values below valueFrom are rejected), so the field carries it and the thumb sits at 0
        // until the value is actually changed.
        val stored = prefs.getInt(key, default)
        val start = stored.coerceIn(range.first, range.last)

        fun render(v: Int) {
            // Never rewrite the text under a finger that is typing it.
            if (valueView.hasFocus()) return
            valueView.setText(if (v < 0) "auto" else v.toString())
            unitView.visibility = if (v < 0) View.GONE else View.VISIBLE
        }
        render(if (stored < 0) -1 else start)

        var sliderRef: Slider? = null

        /** Applies what was typed: a number, -1, or "auto". Clamped to this slider's range. */
        fun commitTyped() {
            val raw = valueView.text?.toString()?.trim().orEmpty()
            val parsed = when {
                raw.isEmpty() -> if (stored < 0) -1 else start
                raw.equals("auto", ignoreCase = true) -> -1
                else -> raw.toIntOrNull()
            }
            if (parsed == null) {
                render(stored)
                return
            }
            val value = if (parsed < 0) -1 else parsed.coerceIn(range.first, range.last)
            prefs.edit().putInt(key, value).apply()
            // Setting the thumb does not run the fromUser path, so this cannot loop.
            sliderRef?.value = value.coerceIn(range.first, range.last).toFloat()
            render(value)
            onChange?.invoke()
        }

        valueView.setOnFocusChangeListener { _, focused -> if (!focused) commitTyped() }
        valueView.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitTyped()
                valueView.clearFocus()
                true
            } else {
                false
            }
        }

        val slider = Slider(this).apply {
            valueFrom = range.first.toFloat()
            valueTo = range.last.toFloat()
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
        sliderRef = slider

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
        // the service object in this same process, and how many of its windows are attached.
        val master = TuningStore.isOn(this, TuningStore.MASTER)
        val service = NowBarBridge.service
        val windows = service?.windowsUp() ?: 0
        val enabled = a11yServiceEnabled()
        val overlay = OverlayService.running
        val live = master && (service != null && windows > 0 || overlay)

        heroDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (live) ON else OFF)
        }
        heroTitle?.text = if (live) "Running" else "Stopped"
        heroSubtitle?.text = when {
            !master -> "Pill disabled by the master switch"
            service == null && enabled -> "Accessibility service enabled, not connected yet"
            service == null && overlay -> "Drawn by the fallback overlay (apps only)"
            service == null -> "Accessibility service is off"
            windows == 0 -> "Connected - nothing on screen (idle pill is hidden)"
            windows == 1 -> "Drawing one surface"
            else -> "Drawing $windows surfaces"
        }

        a11yStatus?.apply {
            on = service != null
            state = when {
                service != null -> "connected"
                enabled -> "enabled, not connected"
                else -> "off"
            }
        }
        val watcherWanted = TuningStore.isOn(this, TuningStore.ROOT_WATCHER)
        watcherStatus?.apply {
            on = watcherWanted && RootKeyguardWatcher.isStreaming
            state = when {
                !watcherWanted -> "off (switch)"
                RootKeyguardWatcher.isStreaming -> "streaming (root)"
                RootKeyguardWatcher.isRunning -> "reconnecting"
                else -> "not running"
            }
        }
        overlayStatus?.apply {
            on = overlay
            state = if (overlay) "running" else "off"
        }
        overlaySwitch?.isChecked = overlay
        masterSwitch?.isChecked = master

        val listening = listenerGranted()
        listenerStatus?.apply {
            on = listening
            state = if (listening) "granted" else "not granted"
        }

        windowsRow?.apply {
            on = windows > 0
            state = when (windows) {
                0 -> "none"
                1 -> "1 window"
                else -> "$windows windows"
            }
        }
        val screenW = globalInt(NowBarHook.KEY_SCREEN_W)
        val screenH = globalInt(NowBarHook.KEY_SCREEN_H)
        screenRow?.apply {
            on = screenW > 0 && screenH > 0
            state = if (screenW > 0 && screenH > 0) {
                "${screenW}x$screenH" + if (screenW > screenH) " landscape" else " portrait"
            } else {
                "unknown"
            }
        }
        val clock = globalInt(NowBarHook.KEY_CLOCK_RIGHT)
        clockRow?.apply {
            on = clock > 0
            state = if (clock > 0) "${clock}px" else "no clock on screen"
        }
        val shade = globalInt(NowBarHook.KEY_SHADE_OPEN) == 1
        shadeRow?.apply {
            on = shade
            state = if (shade) "open" else "closed"
        }

        probeHook()
        refreshPreviews()
    }

    private fun globalInt(key: String): Int = try {
        Settings.Global.getInt(contentResolver, key, 0)
    } catch (t: Throwable) {
        0
    }

    /**
     * The hook lives inside SystemUI, so the app asks it through the one channel it publishes to:
     * a `Settings.Global` heartbeat, refreshed every 30 s. A logcat line is only the fallback for
     * a SystemUI that has not been restarted since this build (it has no heartbeat yet).
     */
    private fun probeHook() {
        val row = hookStatus
        if (row == null) return
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
                hookStatus?.apply {
                    this.on = finalOn
                    this.state = finalState
                }
            }
        }.start()

        // The Home preview is the bar itself, so it follows it: a new track, a timer tick or a
        // card someone swiped away on the pill shows up here within a second.
        if (tab == TAB_HOME && sub == null) renderPreview()    }

    private fun syncTabChips() {
        try {
            for (i in 0 until tabChips.childCount) {
                val chip = tabChips.getChildAt(i) as? MaterialButton ?: continue
                chip.isChecked = chip.tag == tab
            }
        } catch (t: Throwable) {
            Log.w("tab chip sync failed", t)
        }
    }

    private fun refreshPreviews() {
        val label = prefs.getString(TuningStore.LABEL, TuningStore.DEFAULT_LABEL)
            ?: TuningStore.DEFAULT_LABEL
        lockPreview?.let { view ->
            val w = prefs.getInt("widthDp", TuningStore.DEFAULT_WIDTH_DP)
            val h = prefs.getInt("heightDp", TuningStore.DEFAULT_HEIGHT_DP)
            view.update(
                w,
                h,
                prefs.getInt("radiusDp", TuningStore.DEFAULT_RADIUS_DP),
                prefs.getInt("textDp", TuningStore.DEFAULT_TEXT_DP),
                label
            )
            lockCaption?.text = "$w × $h dp"
        }
        sbPreview?.let { view ->
            val w = prefs.getInt("sbWidthDp", TuningStore.DEFAULT_SB_WIDTH_DP)
            val h = prefs.getInt("sbHeightDp", TuningStore.DEFAULT_SB_HEIGHT_DP)
            view.update(
                w,
                h,
                prefs.getInt("sbRadiusDp", TuningStore.DEFAULT_SB_RADIUS_DP),
                prefs.getInt("sbTextDp", TuningStore.DEFAULT_SB_TEXT_DP),
                label
            )
            // The preview follows the layout: each placement is shown against its own edge.
            val side = TuningStore.sbSide(this)
            view.setAlignRight(side == TuningStore.SB_SIDE_RIGHT)
            view.setAlignLeft(side == TuningStore.SB_SIDE_LEFT_NO_CLOCK)
            sbCaption?.text = "$w × $h dp"
        }
        popupPreview?.let { view ->
            val w = prefs.getInt("popupWidthDp", TuningStore.DEFAULT_POPUP_WIDTH_DP)
            val h = prefs.getInt("popupHeightDp", TuningStore.DEFAULT_POPUP_HEIGHT_DP)
            view.update(
                w,
                h,
                prefs.getInt("popupRadiusDp", TuningStore.DEFAULT_POPUP_RADIUS_DP),
                prefs.getInt("popupTextDp", TuningStore.DEFAULT_POPUP_TEXT_DP),
                label
            )
            popupCaption?.text = "$w × $h dp"
        }
    }

    /** Same shape as the pill's own dot pattern, so a preview matches what is on the screen. */
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.3"
    } catch (t: Throwable) {
        "1.0.3"
    }

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
                    overlaySwitch?.isChecked = false
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
            .putString(TuningStore.LABEL, TuningStore.DEFAULT_LABEL)
            .apply()
        Toast.makeText(this, "Defaults restored", Toast.LENGTH_SHORT).show()
        renderPage()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun pairOf(a: Int, b: Int): Pair<Int, Int> = a to b

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
    private val simSwitches = ArrayList<MaterialSwitch>()

    // --------------------------------------------------------------- widgets

    /** Title on the left, state on the right, with a coloured dot in front of the state. */
    private class StatusRow(context: Context) : LinearLayout(context) {

        private val labelView = TextView(context).apply {
            textSize = 15f
            setTextColor(context.themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        private val dot = View(context)
        private val stateView = TextView(context).apply {
            textSize = 13f
            setTextColor(context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }

        var label: String
            get() = labelView.text.toString()
            set(value) {
                labelView.text = value
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
            addView(labelView, LayoutParams(0, WRAP_CONTENT, 1f))
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
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * resources.displayMetrics.density
        }

        /** Status pill preview: which end of the row the shape is drawn against. */
        private var alignRight = false
        private var alignLeft = false

        fun setAlignRight(on: Boolean) {
            if (alignRight == on) return
            alignRight = on
            invalidate()
        }

        fun setAlignLeft(on: Boolean) {
            if (alignLeft == on) return
            alignLeft = on
            invalidate()
        }
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
                val left = when {
                    alignRight -> (width - w).coerceAtLeast(0f)
                    alignLeft -> 0f
                    else -> (width - w) / 2f
                }
                val top = (height - h) / 2f
                val radius = if (radiusDp > 0) minOf(dp(radiusDp.toFloat()) * s, h / 2f) else h / 2f
                rect.set(left, top, left + w, top + h)
                // Measure mode is the real look now: the preview shows the same empty outline,
                // so a size can be judged here before it is dialled in on the screen.
                if (TuningStore.outline(context)) {
                    stroke.color = fill.color
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                    return
                }
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
