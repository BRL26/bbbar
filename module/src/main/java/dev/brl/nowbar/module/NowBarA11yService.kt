package dev.brl.nowbar.module

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * Draws the pill as accessibility overlays (TYPE_ACCESSIBILITY_OVERLAY).
 *
 * This window type sits above the keyguard, unlike TYPE_APPLICATION_OVERLAY, and needs no
 * root: the user enables "Now Bar" under Settings -> Accessibility. It never inspects
 * window content (canRetrieveWindowContent=false); it only owns its own views.
 *
 * Surfaces:
 *  - locked:   pill at the bottom centre of the keyguard; tapping it expands to 2.5x
 *  - unlocked: small pill in the status bar, over the notification icons
 *  - expanded: the popup window, which grows out of the mini pill's own rect and shrinks back
 *
 * Lock state comes from KeyguardManager's locked-state listener, so the handover starts the
 * instant the phone unlocks or locks; the 1 s poll stays as a failsafe only.
 */
class NowBarA11yService : AccessibilityService() {

    private companion object {
        /** Pref keys for "an unlock has happened in this boot" (see [unlockSeenThisBoot]). */
        const val UNLOCK_BOOT = "unlockBootMarker"
        const val UNLOCK_UPTIME = "unlockUptime"

        /** Lock screen expansion factor. */
        const val SCALE = 2.5f

        /** Distance the popup sits below the status bar when its top is set to auto. */
        const val POPUP_GAP_DP = 8

        /**
         * How long an expanded form stays open on its own. After this it settles back to the pill;
         * the collapsed card keeps showing the same activity.
         */
        const val AUTO_CLOSE_MS = 10_000L

        /** AOD fades the pill to greyscale with the rest of the display; waking snaps it back. */
        const val AOD_FADE_MS = 650L
        const val WAKE_FADE_MS = 160L

        /** A hook beat older than this means SystemUI restarted without the hook loaded. */
        const val HOOK_STALE_MS = 180_000L

        const val SLIDE_MS = 180L
        const val EXPAND_MS = 280L
        const val COLLAPSE_MS = 220L

        /** Width morph of the status bar pill when its line changes. */
        const val WIDTH_MS = 230L

        /** Failsafe poll while the phone is unlocked. */
        const val POLL_MS = 1000L

        /** How long the pill takes to leave with the bar, and to come back down with it. */
        const val SB_BAR_MS = 180L

        /**
         * How long a released drag takes to slide back onto its resting spot. Unhurried on
         * purpose: the whole way home is one visible motion, so it reads as sliding into place.
         */
        const val DRAG_HOME_MS = 560L

        /**
         * Poll reads of "unlocked" needed in a row before the state is acted on: 8 at the 40 ms
         * locked cadence is about 320 ms of a steady answer.
         */
        const val UNLOCK_CONFIRM = 8


        /**
         * Gap kept between the clock and the pill in portrait. Touching the time made the pill look
         * like part of the clock, and clamping it exactly onto the clock's edge is what moved it off
         * the spot it was tuned to.
         */
        const val SB_CLOCK_GAP_DP = 8

        /** While the keyguard is showing and the screen is on, poll every frame-ish interval. */
        const val FAST_POLL_MS = 40L

        /** After the screen comes on, check hard for this long (fingerprint unlocks land here). */
        const val BURST_MS = 5000L

        /** Delay used during a burst: effectively "as fast as is sane". */
        const val BURST_POLL_MS = 40L

        /** While the display is off there is nothing to update, so back right off. */
        const val DOZE_POLL_MS = 800L

        /**
         * Gap between the status pill leaving and the keyguard pill arriving. The screen-off
         * animation runs for roughly this long, so landing the pill inside it looks like a jump;
         * bar-pill-off -> delay -> lock-pill-on reads as one movement.
         */
        const val SCREEN_OFF_LOCK_DELAY_MS = 450L

        /** While a root keyguard signal is pending confirmation, check this often (one frame). */
        const val CONFIRM_POLL_MS = 8L

        /** How often the watchdog checks that the windows it means to draw are actually up. */
        const val WATCHDOG_MS = 2500L

        /** Delay before the watchdog looks again after a suppressed handover. */
        const val RESYNC_MS = 320L

        /** Give up on a root signal that never turns into a keyguard change after this long. */
        const val CONFIRM_MS = 1500L

        /** How long the lagging `isKeyguardLocked` flag is ignored after ACTION_USER_PRESENT. */
        const val TRUST_MS = 800L

        /**
         * SystemUI's own words for "the keyguard is going away". These lines are printed while the
         * keyguard is still animating out, i.e. before `isKeyguardLocked` flips, so acting on them
         * is the earliest any outside process can react. Matched case-insensitively.
         */
        val UNLOCK_WORDS = arrayOf(
            "going away",
            "keyguardgoingaway",
            "startkeyguardexitanimation",
            "keyguard exit",
            "hide keyguard",
            "keyguard hidden",
            "onkeyguardshowingchanged( showing=false",
            "keyguardshowing: false",
            "occluded=true"
        )

        /** SystemUI's words for "the keyguard is closing over the screen again". */
        val LOCK_WORDS = arrayOf(
            "onstartedgoingtosleep",
            "going to sleep",
            "locknow",
            "keyguardshowing: true",
            "occluded=false",
            "showing=true"
        )
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var keyguard: KeyguardManager

    private var lockPill: PillView? = null
    private var lockParams: WindowManager.LayoutParams? = null
    private var lockExpanded = false
    private var lockAnimator: ValueAnimator? = null

    /**
     * The card the user swiped to the front. Ranking still decides the default order; this is the
     * one selection shared by every surface, so swiping on the lock pill also decides what the
     * status bar pill shows.
     */
    private var frontKey: String? = null

    /**
     * True while SystemUI has the status bar hidden (a fullscreen or immersive app). The pill sits
     * in that strip, so it has to leave with it instead of floating over the app on its own.
     */
    private var sbHidden = false
    private var sbHiddenWarned = false

    /** Runs the return of a released drag, so it is one motion and never a jump. */
    private var dragSettleAnimator: ValueAnimator? = null

    /** True while that return is animating, so nothing else moves the window under it. */
    private var dragReturning = false

    /** Session facts that outlive the process but not a reboot (see [unlockSeenThisBoot]). */
    private val session by lazy { getSharedPreferences("nowbar_session", MODE_PRIVATE) }

    /** True once a sustained "unlocked" read has been seen, so surfaces are not drawn early. */
    private var sawUnlockConfirm = false

    /**
     * True once an unlock has happened for real in this process - a trusted signal, not the
     * keyguard flag. It decides whether the status pill may show its placeholder: on a fresh boot
     * the flag answers "not locked" while the lock screen is still being built, and the label
     * ("Now Bar") was drawn on that lock screen for a split second.
     */
    private var unlockedOnce = false

    /** True from the first move of a drag until the card is back home. */
    private var dragActive = false

    /**
     * True while a window other than the keyguard is on top of a locked phone (camera, an
     * incoming call, the bouncer, anything else that overlays it). The lockscreen pill is taken
     * down for the duration, so it can never sit over controls the owner is using. Issue #9.
     */
    private var lockPillSuppressed = false
    private var coveringWindow = ""

    /** Last active window type reported, so the log only shows real changes. */
    private var lastActiveWindowType = -1

    /** Battery percentage for the inverse pill's own glyph; -1 until the first sticky read. */
    private var batteryPct = -1
    private var batteryReceiver: BroadcastReceiver? = null

    /** Instant heads-up when the phone joins or drops a network, so the glyph follows. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var sbPill: PillView? = null
    private var sbParams: WindowManager.LayoutParams? = null
    private var sbAnimator: ValueAnimator? = null

    /** The width morph, kept apart from [sbAnimator] (which slides/fades the whole window). */
    private var sbWidthAnimator: ValueAnimator? = null

    /** True while the pill is on its way out with the bar, so it is not re-added mid-flight. */
    private var sbLeavingWithBar = false



    /** Width the running morph is heading for; -1 when no morph is running. */
    private var sbWidthTarget = -1

    private var popupPill: PillView? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var popupAnimator: ValueAnimator? = null

    /** Consecutive "unlocked" reads; one alone is not trusted on this build. */
    private var unlockStreak = 0

    private var receiver: BroadcastReceiver? = null
    private var keyguardListener: KeyguardManager.KeyguardLockedStateListener? = null
    private var transitioning = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastLocked: Boolean? = null

    /** Guards [applyContent] against the add* helpers calling back into it. */
    private var applyingContent = false

    /** uptimeMillis until which the poll runs at BURST_POLL_MS. */
    private var burstUntil = 0L

    /** uptimeMillis until which a root keyguard signal is being confirmed at CONFIRM_POLL_MS. */
    private var confirmUntil = 0L

    /** uptimeMillis of the first root signal in the current burst, for latency reporting. */
    private var signalBurstStart = 0L

    /** uptimeMillis of the last root line, so a burst is logged once instead of thirty times. */
    private var lastSignalAt = 0L

    /** uptimeMillis until which the platform's lagging keyguard flag is ignored. */
    private var trustedUnlockUntil = 0L

    /** uptimeMillis of the trusted USER_PRESENT, for the lag report. */
    private var trustedUnlockAt = 0L

    /** uptimeMillis of a root-forced unlock, for the "flag finally agreed" measurement. */
    private var rootForcedAt = 0L

    /** uptimeMillis of the last SCREEN_OFF, so a lagging "unlocked" read cannot undo the lock. */
    private var lastScreenOffAt = 0L

    /** uptimeMillis of the last completed handover, to swallow duplicated triggers. */
    private var lastHandoverAt = 0L

    /** Last surface mismatch the watchdog saw, and how often it has repeated. */
    private var watchSignature = ""
    private var watchStreak = 0

    /**
     * Keeps a pill on screen.
     *
     * Anything that leaves the service with no window - a handover suppressed as a duplicate, a
     * surface switched back on in the settings app, a window the system dropped after a display or
     * SystemUI change, a half-finished repair - is put right here within a couple of seconds,
     * instead of staying blank until some unrelated event happened to call applyAll().
     */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                watchdogCheck(force = false)
            } catch (t: Throwable) {
                Log.w("watchdog check failed", t)
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    /** Places the keyguard pill once the screen-off animation has finished. */
    private val showLockPillAfterScreenOff = object : Runnable {
        override fun run() {
            try {
                if (lastLocked != true) return
                if (lockPillSuppressed) {
                    Log.i("screen off: lock pill stays away, $coveringWindow covers the keyguard")
                    return
                }
                if (lockPill == null) addLockPill(slideIn = false)
                Log.i("screen off: lock pill placed after the ${SCREEN_OFF_LOCK_DELAY_MS}ms delay")
            } catch (t: Throwable) {
                Log.w("deferred lock pill failed", t)
            }
        }
    }

    /**
     * Settles an expanded form back to the pill after [AUTO_CLOSE_MS]. Controls, swipes and a seek
     * re-arm it, so it never closes while it is being used.
     */
    private val autoClose = object : Runnable {
        override fun run() {
            try {
                when {
                    popupPill != null -> {
                        Log.i("auto close after ${AUTO_CLOSE_MS}ms: popup")
                        collapseToSbPill()
                    }
                    lockExpanded -> {
                        Log.i("auto close after ${AUTO_CLOSE_MS}ms: lock pill")
                        toggleLockExpand()
                    }
                }
            } catch (t: Throwable) {
                Log.w("auto close failed", t)
            }
        }
    }

    private fun armAutoClose() {
        try {
            handler.removeCallbacks(autoClose)
            handler.postDelayed(autoClose, AUTO_CLOSE_MS)
        } catch (t: Throwable) {
            Log.w("armAutoClose failed", t)
        }
    }

    private fun disarmAutoClose() {
        try {
            handler.removeCallbacks(autoClose)
        } catch (t: Throwable) {
            Log.w("disarmAutoClose failed", t)
        }
    }

    /** Screen size the windows were last placed for. */
    private var placedScreenW = 0
    private var placedScreenH = 0

    private fun rePlaceIfScreenChanged() {
        try {
            val s = screenSizePx()
            if (s.x == placedScreenW && s.y == placedScreenH) return
            placedScreenW = s.x
            placedScreenH = s.y
            if (sbPill == null && lockPill == null && popupPill == null) return
            Log.i("screen now ${s.x}x${s.y}: re-placing the pill")
            replaceAllWindows()
        } catch (t: Throwable) {
            Log.w("screen change check failed", t)
        }
    }

    /** Re-renders the visible pill once a second, so a timer counts down between updates. */
    private val timerTick = object : Runnable {
        override fun run() {
            try {
                applyContent()
            } catch (t: Throwable) {
                Log.w("timer tick failed", t)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val keyguardPoll = object : Runnable {
        override fun run() {
            var delay = POLL_MS
            try {
                val locked = isKeyguardLocked()
                val t = SystemClock.uptimeMillis()
                if (t < trustedUnlockUntil && locked) {
                    // The platform flag is still catching up. ACTION_USER_PRESENT already said
                    // the keyguard is gone, so do not undo that flip with the lag.
                } else {
                    if (rootForcedAt > 0L && !locked) {
                        Log.i(
                            "keyguard flag agreed ${t - rootForcedAt}ms after the root unlock " +
                                "signal (that gap was pure reaction time before)"
                        )
                        rootForcedAt = 0L
                    }
                    if (trustedUnlockAt > 0L && !locked) {
                        Log.i("keyguard flag agreed ${t - trustedUnlockAt}ms after USER_PRESENT")
                        trustedUnlockAt = 0L
                        trustedUnlockUntil = 0L
                    }
                    // A single "unlocked" read cannot be trusted on this build: the flag reports
                    // false for a moment while the keyguard is still up, and acting on it tore
                    // the lockscreen pill off a locked phone. Two consecutive reads, with the
                    // display awake, are required - at a 40 ms poll that is still under 100 ms.
                    val awake = isInteractive()
                    val settledAfterScreenOff = t - lastScreenOffAt > 1000L
                    if (!locked && awake && settledAfterScreenOff) {
                        unlockStreak++
                    } else if (locked) {
                        unlockStreak = 0
                    }
                    // 8 reads at the 40 ms locked cadence is ~320 ms of continuous "unlocked".
                    // Two reads (80 ms) was short enough for the mid-transition false reads to
                    // fake it, which is what made the pill unlock, relock, then unlock again.
                    val unlockedConfirmed = !locked && unlockStreak >= UNLOCK_CONFIRM
                    if (lastLocked != locked && (locked || unlockedConfirmed)) {
                        Log.i("poll caught the lock change (locked=$locked, unlockStreak=$unlockStreak)")
                        updateSurfaces()
                    } else if (unlockedConfirmed && !sawUnlockConfirm) {
                        // The surfaces were held back while the keyguard was still settling; now
                        // the unlock is real, so the status pill can appear.
                        sawUnlockConfirm = true
                        Log.i("unlock confirmed: drawing the surfaces that waited")
                        updateSurfaces()
                    } else if (!unlockedConfirmed) {
                        sawUnlockConfirm = false
                    }
                }
                // The pill shares its strip with the notification icons: when SystemUI hides the
                // status bar for a fullscreen app, the pill leaves with it and comes back after.
                if (!locked && isInteractive()) syncStatusBarVisibility()
                // A turn that nothing else noticed, or a display size that was still settling when
                // the service started, leaves the pill placed for the old screen - which is how it
                // ends up off the right edge. Re-place whenever the size really changes.
                rePlaceIfScreenChanged()
                val now = t
                delay = when {
                    now < burstUntil -> BURST_POLL_MS
                    !isInteractive() -> DOZE_POLL_MS
                    locked -> FAST_POLL_MS
                    else -> POLL_MS
                }
            } catch (t: Throwable) {
                Log.w("keyguard poll failed", t)
            }
            handler.postDelayed(this, delay)
        }
    }

    /**
     * Runs while a root keyguard line is pending: checks every frame until the platform's own
     * `isKeyguardLocked` agrees, which is the oldest moment a transition may start.
     */
    private val confirmPoll = object : Runnable {
        override fun run() {
            try {
                val locked = isKeyguardLocked()
                if (SystemClock.uptimeMillis() < trustedUnlockUntil && locked) {
                    handler.postDelayed(this, CONFIRM_POLL_MS)
                    return
                }
                if (lastLocked != locked) {
                    val took = SystemClock.uptimeMillis() - signalBurstStart
                    confirmUntil = 0L
                    Log.i("root signal confirmed the lock change (locked=$locked) ${took}ms after the first keyguard line")
                    lastLocked = locked
                    updateSurfaces()
                    return
                }
                if (SystemClock.uptimeMillis() < confirmUntil) {
                    handler.postDelayed(this, CONFIRM_POLL_MS)
                } else {
                    Log.i("root signal expired without a keyguard change")
                }
            } catch (t: Throwable) {
                Log.w("root confirm poll failed", t)
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // The debug level is one of the preferences, so a change to it has to reach the logger
        // before anything new is written.
        Log.sync(this)
        applyAll()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            windowManager = getSystemService(WindowManager::class.java)
            keyguard = getSystemService(KeyguardManager::class.java)
            TuningStore.maybeRewrite(this)
            prefs = TuningStore.prefs(this)
            Log.sync(this)
            syncHookInverse()
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            registerKeyguardReceiver()
            registerLockStateListener()
            startRootWatcher()
            lastLocked = isKeyguardLocked()
            unlockedOnce = unlockSeenThisBoot()
            if (lastLocked == true) armBurst()
            NowBarBridge.service = this
            // Remembered so the watchdog can tell a crash from the owner switching the service off.
            prefs.edit().putBoolean(NowBarWatchdog.WAS_CONNECTED, true).apply()
            // Make sure the root keeper loop is running: it is what restores this service after
            // the process is killed and Android drops it from enabled accessibility services.
            RootKeeper.startAsync()
            NowBarState.onChange = { applyContent() }
            handler.post(timerTick)
            updateSurfaces()
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, WATCHDOG_MS)
            handler.post(keyguardPoll)
            registerStatusBarObserver()
            registerBattery()
            registerNetwork()
            registerDisplayListener()
            Log.i("accessibility overlay connected (locked=$lastLocked)")
        } catch (t: Throwable) {
            Log.e("a11y onServiceConnected failed", t)
        }
    }

    /**
     * Window-state changes are the closest thing to an unlock hook an accessibility service
     * gets: SystemUI's keyguard window going away fires almost immediately. No content is read.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val type = event.eventType
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                return
            }
            // The lockscreen pill belongs to the lock screen only. Every window that comes to
            // the front while locked is classified here, before the lock-change check, so a
            // camera / call / any other overlay takes the pill away even when the keyguard flag
            // itself does not move.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg.isNotEmpty()) onWindowOverKeyguard(pkg, event.className?.toString())
            }
            val locked = isKeyguardLocked()
            if (lastLocked != locked) {
                Log.i("a11y event caught the lock change (locked=$locked)")
                lastLocked = locked
                if (!locked) clearLockPillSuppression()
                updateSurfaces()
            }
        } catch (t: Throwable) {
            Log.w("a11y event handling failed", t)
        }
    }

    override fun onInterrupt() {}

    /**
     * Keeps the lockscreen pill on the lock screen and nowhere else (issue #9).
     *
     * Only another *app* over the keyguard counts as covering: the camera, the dialer / in-call
     * screen, an app opened from a notification, anything the owner launched. SystemUI's own
     * windows are the lock screen itself, including the fingerprint overlay and the bouncer, and
     * must not switch the pill off - they fire window events too, which is what made it disappear
     * on a perfectly normal lock screen.
     */
    private fun onWindowOverKeyguard(pkg: String, cls: String?) {
        try {
            if (pkg == packageName) return
            if (lastLocked != true && !isKeyguardLocked()) {
                clearLockPillSuppression()
                return
            }
            val active = activeWindowType()
            if (active != null && active != lastActiveWindowType) {
                lastActiveWindowType = active
                Log.i("window: active type=$active ($pkg/${cls.orEmpty()})")
            }
            // Window metadata (type only, never content) is the honest answer: the keyguard is a
            // system window, so an active application window is an app sitting on top of it. The
            // event package is the fallback for a service that was not granted the window list.
            val covering = if (active != null) {
                active == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    active == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } else {
                coversKeyguardFallback(pkg, cls)
            }
            if (covering) {
                if (lockPillSuppressed) return
                lockPillSuppressed = true
                coveringWindow = if (cls.isNullOrBlank()) pkg else "$pkg/$cls"
                Log.i("lockscreen pill steps aside: $coveringWindow is over the keyguard")
                removeLockPill()
            } else if (lockPillSuppressed) {
                clearLockPillSuppression()
                Log.i("lockscreen pill back: the keyguard is in front again ($pkg)")
                if (lastLocked == true) addLockPill(fadeIn = true)
            }
        } catch (t: Throwable) {
            Log.w("keyguard window check failed", t)
        }
    }

    private fun clearLockPillSuppression() {
        if (!lockPillSuppressed) return
        lockPillSuppressed = false
        coveringWindow = ""
    }

    /**
     * Type of the window that currently has focus, read from the accessibility window list. This
     * is metadata only - the service still has `canRetrieveWindowContent=false` - but it is the
     * difference between an app over the keyguard and an app behind it. Null when the list is
     * unavailable, in which case the event package decides.
     */
    private fun activeWindowType(): Int? {
        return try {
            val list = windows
            if (list.isNullOrEmpty()) null else list.firstOrNull { it.isActive }?.type
        } catch (t: Throwable) {
            Log.w("window list unreadable", t)
            null
        }
    }

    /**
     * Fallback without the window list. Only a window that is clearly a camera or a call UI counts
     * as covering; a launcher, the keyboard and SystemUI are part of the lock screen and never do.
     */
    private fun coversKeyguardFallback(pkg: String, cls: String?): Boolean {
        if (pkg == "com.android.systemui") return false
        val p = pkg.lowercase()
        if (p.contains("launcher")) return false
        if (p.contains("inputmethod") || p.contains("keyboard") || p.contains("latin")) return false
        val c = cls.orEmpty().lowercase()
        return p.contains("camera") || p.contains("dialer") || p.contains("incall") ||
            p.contains("telecom") || c.contains("camera") || c.contains("incall") ||
            c.contains("dialer")
    }

    /**
     * Rotation changes what the status bar strip looks like - the clock moves and resizes, the
     * cutout changes side, the landscape overrides apply - so every window that is up is placed
     * again. Without this the pill kept the position it had before the turn, which is how a
     * landscape QS pill ended up in the middle of a portrait screen.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i("config changed (orientation=${newConfig.orientation}): re-placing the pill")
        replaceAllWindows()
    }

    /**
     * Places every window that is up again. Rotation changes what the strip looks like - the clock
     * moves and resizes, the cutout changes side, the landscape rules apply - so a window that kept
     * its old coordinates ended up in the wrong place until something else moved it.
     */
    private fun replaceAllWindows() {
        handler.post {
            try {
                if (shadeOpen()) {
                    syncShadeSurfaces()
                } else {
                    if (sbPill != null) applySbTuning()
                    if (lockPill != null) applyLockTuning()
                    if (popupPill != null) applyPopupTuning()
                }
            } catch (t: Throwable) {
                Log.w("re-place failed", t)
            }
        }
    }

    /**
     * Rotation, in a form a service actually receives: `onConfigurationChanged` is not reliable for
     * an accessibility service, but a display change always arrives when the screen turns.
     */
    private var displayListener: DisplayManager.DisplayListener? = null

    private fun registerDisplayListener() {
        if (displayListener != null) return
        try {
            val dm = getSystemService(DisplayManager::class.java) ?: return
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    Log.i("display changed: re-placing the pill")
                    replaceAllWindows()
                }
            }
            dm.registerDisplayListener(listener, handler)
            displayListener = listener
        } catch (t: Throwable) {
            Log.w("display listener not registered", t)
        }
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        displayListener = null
        try {
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener)
        } catch (t: Throwable) {
            Log.w("display listener not removed", t)
        }
    }

    override fun onDestroy() {
        NowBarState.onChange = null
        handler.removeCallbacks(timerTick)
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (t: Throwable) {
        }
        try {
            receiver?.let { unregisterReceiver(it) }
        } catch (t: Throwable) {
        }
        receiver = null
        unregisterLockStateListener()
        try {
            RootKeyguardWatcher.stop()
        } catch (t: Throwable) {
        }
        try {
            handler.removeCallbacks(confirmPoll)
            handler.removeCallbacks(keyguardPoll)
            handler.removeCallbacks(watchdog)
            unregisterStatusBarObserver()
            unregisterBattery()
            unregisterNetwork()
            unregisterDisplayListener()
        } catch (t: Throwable) {
        }
        if (NowBarBridge.service === this) NowBarBridge.service = null
        try {
            // A graceful stop is the owner's choice: the watchdog must not undo it.
            prefs.edit().putBoolean(NowBarWatchdog.WAS_CONNECTED, false).apply()
        } catch (t: Throwable) {
            Log.w("watchdog flag clear failed", t)
        }
        removePopup()
        removeLockPill()
        removeSbPill()
        super.onDestroy()
    }

    // ------------------------------------------------------------ lock state

    /**
     * The instant trigger: fires as soon as the keyguard locks or unlocks, so no polling delay.
     */
    private fun registerLockStateListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val listener = KeyguardManager.KeyguardLockedStateListener { locked ->
                Log.i("keyguard listener: locked=$locked")
                onLockStateChanged(locked)
            }
            keyguard.addKeyguardLockedStateListener(mainExecutor, listener)
            keyguardListener = listener
        } catch (t: Throwable) {
            Log.w("keyguard listener registration failed, relying on poll", t)
        }
    }

    private fun unregisterLockStateListener() {
        val listener = keyguardListener ?: return
        try {
            keyguard.removeKeyguardLockedStateListener(listener)
        } catch (t: Throwable) {
            Log.w("keyguard listener removal failed", t)
        }
        keyguardListener = null
    }

    /**
     * Root logcat watcher: the earliest signal the platform gives an app that the keyguard is
     * going away. Without root it logs a failure and the poll above remains the fallback.
     */
    /**
     * Follows the owner's switch: starts the root watcher, or stops it so its `su` child and the
     * logcat stream go away. Reading is idempotent, so this is called on every apply as well.
     */
    private fun syncRootWatcher() {
        try {
            val want = TuningStore.isOn(this, TuningStore.ROOT_WATCHER)
            if (want && !RootKeyguardWatcher.isRunning) {
                startRootWatcher()
            } else if (!want && RootKeyguardWatcher.isRunning) {
                RootKeyguardWatcher.stop()
                Log.i("root keyguard watcher switched off by the owner")
            }
        } catch (t: Throwable) {
            Log.w("root watcher sync failed", t)
        }
    }

    private fun startRootWatcher() {
        try {
            if (!TuningStore.isOn(this, TuningStore.ROOT_WATCHER)) return
            RootKeyguardWatcher.start(onLine = { line ->
                handler.post { onRootKeyguardSignal(line) }
            })
        } catch (t: Throwable) {
            Log.w("root keyguard watcher unavailable", t)
        }
    }

    /** A keyguard log line arrived: confirm the real state immediately instead of on the next tick. */
    private fun onRootKeyguardSignal(line: String) {
        try {
            val now = SystemClock.uptimeMillis()
            // KeyguardViewMediator prints a dozen lines per unlock; log the burst once.
            if (now - lastSignalAt > 1000L) {
                signalBurstStart = now
                Log.i("root keyguard signal: $line")
            }
            lastSignalAt = now
            // Root hands us SystemUI's own words, which land before the public flag flips. Act on
            // the unlock line directly instead of waiting for `isKeyguardLocked` to agree.
            val lower = line.lowercase()
            when {
                UNLOCK_WORDS.any { lower.contains(it) } -> forceUnlock("root line: $line")
                LOCK_WORDS.any { lower.contains(it) } -> forceLock("root line: $line")
            }
            // Root can read SystemUI's own keyguard log, which is printed before any state an
            // app can query exists. Act on those lines directly instead of waiting to confirm
            // with a flag that lags behind them.
            if (looksLikeUnlock(line)) {
                forceUnlockFromRoot(line)
                return
            }
            if (looksLikeLock(line)) {
                forceLockFromRoot(line)
                return
            }
            confirmUntil = now + CONFIRM_MS
            handler.removeCallbacks(confirmPoll)
            handler.post(confirmPoll)
        } catch (t: Throwable) {
            Log.w("root keyguard signal handling failed", t)
        }
    }

    /**
     * Checks hard for a few seconds after the screen comes on: that is the window in which a
     * fingerprint unlock happens, so the handover should not wait for the slow poll.
     */
    private fun armBurst(durationMs: Long = BURST_MS) {
        burstUntil = SystemClock.uptimeMillis() + durationMs
        Log.i("fast poll armed for ${durationMs}ms")
    }

    /** Called by [UserPresentReceiver]: the keyguard just went away, start the handover now. */
    /**
     * Unlock as soon as any trusted source says so - no waiting for `isKeyguardLocked`.
     * The lock-screen window is torn down immediately rather than animated.
     */
    private fun forceUnlock(source: String) {
        try {
            val now = SystemClock.uptimeMillis()
            if (lastLocked == false && sbPill != null) return
            lastLocked = false
            markUnlocked()
            trustedUnlockAt = now
            trustedUnlockUntil = now + TRUST_MS
            Log.i("instant unlock ($source)")
            if (!transitioning) {
                removePopup()
                slideLockPillOut()
            }
        } catch (t: Throwable) {
            Log.w("forceUnlock failed", t)
        }
    }

    private fun forceLock(source: String) {
        try {
            if (lastLocked == true && lockPill != null) return
            Log.i("instant lock ($source)")
            onScreenOff()
        } catch (t: Throwable) {
            Log.w("forceLock failed", t)
        }
    }

    /** Logs when the change actually reaches the display, not just when the call returned. */
    private fun logFirstFrame(label: String, since: Long) {
        try {
            Choreographer.getInstance().postFrameCallback {
                Log.i("$label: first frame ${SystemClock.uptimeMillis() - since}ms after the change")
            }
        } catch (t: Throwable) {
            Log.w("logFirstFrame failed", t)
        }
    }

    fun onUserPresent() {
        try {
            // Trust the broadcast itself. It is only sent once the keyguard has actually gone,
            // while `isKeyguardLocked` keeps reporting "locked" for a few hundred ms afterwards -
            // that lag is exactly what the pill and the debug dot used to sit behind.
            if (lastLocked == false && sbPill != null) return
            val now = SystemClock.uptimeMillis()
            lastLocked = false
            markUnlocked()
            trustedUnlockAt = now
            trustedUnlockUntil = now + TRUST_MS
            Log.i("USER_PRESENT -> unlocked immediately (flag ignored for ${TRUST_MS}ms)")
            if (!transitioning) {
                removePopup()
                transitionToUnlocked()
            }
        } catch (t: Throwable) {
            Log.w("onUserPresent failed", t)
        }
    }

    /**
     * Lines SystemUI prints on the way out of the keyguard. Deliberately narrow: a wrong match
     * would move the pill while the phone is locked, so only explicit exit wording counts.
     */
    private fun looksLikeUnlock(line: String): Boolean {
        val l = line.lowercase()
        // Two kinds of line talk about unlocking without being one. Error lines here are guards
        // and no-ops, and the platform prints this one right after a relock:
        //   E/KeyguardViewMediator: … Do not proceed to finish keyguard exit and unlock.
        // Believing that at boot slid the lock pill out of the way and drew the idle status pill
        // - "Now Bar" - on the lock screen for a split second.
        if (l.startsWith("e/") || l.contains("do not proceed") || l.contains("mpendinglock=true")) {
            return false
        }
        return l.contains("goingaway") ||
            l.contains("keyguard exit") ||
            l.contains("startkeyguardexitanimation") ||
            l.contains("keyguardgone") ||
            l.contains("onunlock") ||
            l.contains("keyguardoccluded=true")
    }

    /** Lines SystemUI prints when the keyguard comes back. */
    private fun looksLikeLock(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("onstartedgoingtosleep") ||
            l.contains("locknow") ||
            l.contains("keyguardshowing=true") ||
            l.contains("mkeyguardshowing=true") ||
            l.contains("keyguardoccluded=false")
    }

    /** Unlock, forced from the root stream: tear the keyguard window down now, no flag wait. */
    private fun forceUnlockFromRoot(line: String) {
        try {
            if (lastLocked == false) return
            val now = SystemClock.uptimeMillis()
            lastLocked = false
            markUnlocked()
            rootForcedAt = now
            trustedUnlockAt = now
            trustedUnlockUntil = now + TRUST_MS
            Log.i("root unlock: ${line.take(110)} -> handover now, no flag confirmation")
            if (!transitioning) {
                removePopup()
                slideLockPillOut()
            }
        } catch (t: Throwable) {
            Log.w("root unlock handover failed", t)
        }
    }

    /** Lock, forced from the root stream: put the keyguard window back now. */
    private fun forceLockFromRoot(line: String) {
        try {
            if (lastLocked == true) return
            Log.i("root lock: ${line.take(110)} -> handover now, no flag confirmation")
            onScreenOff()
        } catch (t: Throwable) {
            Log.w("root lock handover failed", t)
        }
    }

    private fun onLockStateChanged(locked: Boolean) {
        try {
            if (lastLocked == locked) return
            lastLocked = locked
            updateSurfaces()
        } catch (t: Throwable) {
            Log.e("onLockStateChanged failed", t)
        }
    }

    private fun updateSurfaces() {
        try {
            if (!TuningStore.isOn(this, TuningStore.MASTER)) {
                Log.i("pill disabled: no surfaces drawn")
                removeAllSurfaces()
                return
            }
            // The shade owns the screen while it is open: one rule for it, whatever the keyguard
            // thinks, so a lock-state read cannot pull the bottom pill up behind the notices.
            if (shadeOpen()) {
                syncShadeSurfaces()
                return
            }
            val locked = isKeyguardLocked()
            lastLocked = locked
            Log.i("surfaces: keyguardLocked=$locked")
            // The dot is deliberately NOT driven from here: this value is a bare flag read, and
            // the flag lies for a few hundred ms around screen-off. It follows the two trusted
            // events instead - ACTION_SCREEN_OFF and ACTION_USER_PRESENT.
            if (transitioning) return
            if (locked) {
                removePopup()
                transitionToLocked()
            } else {
                removePopup()
                transitionToUnlocked()
            }
        } catch (t: Throwable) {
            Log.e("updateSurfaces failed", t)
        }
    }

    /**
     * `isKeyguardLocked` alone is not trustworthy on this build: it reports false mid-transition
     * (right after screen-off and while dreaming), which is what once turned the debug dot red on
     * a locked phone. `isDeviceLocked` is credential based and stays true there, so the device
     * counts as locked if either says so.
     */
    private fun isKeyguardLocked(): Boolean = try {
        keyguard.isKeyguardLocked || keyguard.isDeviceLocked
    } catch (t: Throwable) {
        Log.w("keyguard state unreadable, assuming locked", t)
        true
    }

    private fun registerKeyguardReceiver() {
        try {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action
                    Log.i("state broadcast: $action")
                    when (action) {
                        // Screen off is handled directly: the keyguard only reports itself locked
                        // a few hundred ms later, by which time the display is already in doze
                        // and nothing we draw is visible.
                        Intent.ACTION_SCREEN_OFF -> onScreenOff()
                        // Trusted unlock: the broadcast is only sent once the keyguard is gone.
                        Intent.ACTION_USER_PRESENT -> onUserPresent()
                        else -> {
                            if (action == Intent.ACTION_SCREEN_ON) {
                                armBurst()
                                // Off the AOD and back to a live screen: colour returns quickly,
                                // and the card look comes back with it.
                                lockPill?.setGray(0f, WAKE_FADE_MS)
                                lockPill?.setLineStyle(false)
                            }
                            updateSurfaces()
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // Protected system broadcasts. Registered plainly (legal for system broadcasts); the
            // exported variant is only a fallback. RECEIVER_NOT_EXPORTED is what silently
            // swallowed SCREEN_OFF / USER_PRESENT before - no "state broadcast:" line ever showed.
            try {
                registerReceiver(r, filter)
            } catch (t: Throwable) {
                Log.w("plain receiver registration failed, retrying exported", t)
                registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            }
            receiver = r
        } catch (t: Throwable) {
            Log.w("keyguard receiver registration failed", t)
        }
    }

    // ----------------------------------------------------------- transitions

    /**
     * Unlock: the keyguard pill slides up off the top, the status pill slides in from there.
     *
     * Unlocking from the AOD is the awkward case: the fingerprint fires while the display is
     * still dozing, and window moves sent during the doze exit are dropped by the system, so the
     * pill used to vanish in two pieces with no animation. Wait for the display to be
     * interactive, then slide.
     */
    private fun transitionToUnlocked() {
        if (!isInteractive()) {
            Log.i("unlock from doze: waiting for the display to wake before sliding")
            awaitInteractive(0)
            return
        }
        slideLockPillOut()
    }

    private fun isInteractive(): Boolean = try {
        getSystemService(PowerManager::class.java)?.isInteractive ?: true
    } catch (t: Throwable) {
        Log.w("interactivity unreadable, assuming awake", t)
        true
    }

    /** Checks every frame until the display is awake (max ~400 ms), then slides. */
    private fun awaitInteractive(attempt: Int) {
        try {
            if (isInteractive() || attempt >= 25) {
                Log.i("display awake after ${attempt * 16}ms, sliding after two frames")
                // Two frames of grace: starting the slide on the very first frame the display
                // draws again drops those frames, which reads as the pill being torn in half
                // instead of sliding.
                waitForFrames(2)
                return
            }
            handler.postDelayed({ awaitInteractive(attempt + 1) }, 16L)
        } catch (t: Throwable) {
            Log.w("awaitInteractive failed", t)
            slideLockPillOut()
        }
    }

    private fun waitForFrames(remaining: Int) {
        if (remaining <= 0) {
            slideLockPillOut()
            return
        }
        try {
            Choreographer.getInstance().postFrameCallback { waitForFrames(remaining - 1) }
        } catch (t: Throwable) {
            Log.w("frame wait failed", t)
            slideLockPillOut()
        }
    }

    /**
     * Screen off: hand over now instead of waiting for the keyguard to report itself locked.
     * The display fades out over the next few hundred ms, so the status pill's slide is still
     * drawn; when the handover waited for the keyguard it ran in doze, where nothing renders.
     */
    private fun onScreenOff() {
        lastScreenOffAt = SystemClock.uptimeMillis()
        // The always-on display is a still, colourless screen, so the pill fades to greyscale with
        // it and fades back on wake. Set before the early return: a phone that is already locked
        // still dims, and this is the only notification of that.
        disarmAutoClose()
        if (TuningStore.isOn(this, TuningStore.AOD_GRAY)) lockPill?.setGray(1f, AOD_FADE_MS)
        lockPill?.setLineStyle(TuningStore.isOn(this, TuningStore.AOD_LINE_STYLE))
        // Already locked with nothing to swap: a second trigger must not add a second pill.
        if (lockPill != null && sbPill == null) return
        if (!handoverAllowed()) {
            scheduleResync()
            return
        }
        transitioning = true
        handler.postDelayed({ transitioning = false }, 250L)
        try {
            removePopup()
            lastLocked = true
            // Order matters: the status pill leaves at once, the keyguard pill only lands after
            // the screen-off animation, so the two are never on screen together.
            removeSbPillNow()
            handler.removeCallbacks(showLockPillAfterScreenOff)
            handler.postDelayed(showLockPillAfterScreenOff, SCREEN_OFF_LOCK_DELAY_MS)
            Log.i("screen off: status pill removed, lock pill in ${SCREEN_OFF_LOCK_DELAY_MS}ms")
        } catch (t: Throwable) {
            Log.w("onScreenOff failed", t)
            transitioning = false
        }
    }

    /**
     * Zero-delay window removal. `removeView` waits for the next layout pass to hand the surface
     * back, which is visible as the pill hanging around after the keyguard is gone;
     * `removeViewImmediate` tears the window down on the spot.
     */
    private fun removeImmediate(view: View) {
        val t0 = SystemClock.uptimeMillis()
        try {
            // Frame-based proof of when the removal is actually on screen, not just requested.
            try {
                Choreographer.getInstance().postFrameCallback {
                    Log.i("handover: window removal drawn ${SystemClock.uptimeMillis() - t0}ms after removeViewImmediate")
                }
            } catch (t: Throwable) {
                Log.w("frame probe failed", t)
            }
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w("removeViewImmediate failed, falling back to removeView", t)
            try {
                windowManager.removeView(view)
            } catch (ignored: Throwable) {
                Log.w("removeView also failed", ignored)
            }
        }
    }

    private fun removeSbPillNow() {
        sbWidthAnimator?.cancel()
        sbWidthAnimator = null
        val view = sbPill ?: return
        removeImmediate(view)
        sbPill = null
        sbParams = null
    }

    private fun removeLockPillNow() {
        lockAnimator?.cancel()
        lockAnimator = null
        lockExpanded = false
        val view = lockPill ?: return
        removeImmediate(view)
        lockPill = null
        lockParams = null
    }

    /**
     * Unlock handover, with no animation: the keyguard window is torn down immediately and the
     * status pill is put in its place, so nothing of the lockscreen pill is left on screen after
     * the keyguard is gone. The 180 ms slide was what made this feel slow.
     */
    private fun slideLockPillOut() {
        // Already handed over: a duplicated trigger must not tear the pill down and put it back.
        if (lockPill == null && sbPill != null) return
        if (!handoverAllowed()) {
            scheduleResync()
            return
        }
        // Unlocked before the deferred keyguard pill landed: do not put it up now.
        handler.removeCallbacks(showLockPillAfterScreenOff)
        transitioning = true
        handler.postDelayed({ transitioning = false }, 200L)
        try {
            // The lock screen is gone, so any suppression belonged to that screen.
            clearLockPillSuppression()
            val t0 = SystemClock.uptimeMillis()
            removeLockPillNow()
            if (sbPill == null) addSbPill(fadeIn = true)
            Log.i("unlock handover: lock window removed in ${SystemClock.uptimeMillis() - t0}ms (removeViewImmediate)")
            logFirstFrame("unlock handover", t0)
        } catch (t: Throwable) {
            Log.w("transitionToUnlocked failed", t)
            transitioning = false
            removeLockPill()
            addSbPill(slideIn = false)
        }
    }

    /** Lock handover, also with no animation: status pill removed, keyguard pill put in place. */
    private fun transitionToLocked() {
        if (sbPill == null && lockPill != null) return
        if (!handoverAllowed()) {
            scheduleResync()
            return
        }
        transitioning = true
        handler.postDelayed({ transitioning = false }, 200L)
        try {
            removeSbPillNow()
            // A window that was already over the keyguard keeps it away until it leaves.
            if (lockPill == null && !lockPillSuppressed) addLockPill(fadeIn = true)
            Log.i("lock handover: status window removed, keyguard pill in place instantly")
        } catch (t: Throwable) {
            Log.w("transitionToLocked failed", t)
            transitioning = false
            removeSbPill()
            addLockPill(slideIn = false)
        }
    }

    private fun animateY(
        view: View,
        lp: WindowManager.LayoutParams,
        fromY: Int,
        toY: Int,
        durationMs: Long,
        interpolator: TimeInterpolator,
        onEnd: (() -> Unit)?
    ) {
        // Each surface owns its animator. Before this, every slide cancelled `lockAnimator`, so a
        // status bar slide that happened to be running was killed mid-flight - that is what made
        // the handover look like a jump instead of motion.
        val slot = when {
            view === lockPill -> 0
            view === sbPill -> 1
            else -> 2
        }
        cancelAnimator(slot)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.y = (fromY + (toY - fromY) * f).toInt()
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (t: Throwable) {
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Land exactly on the target: int rounding left the pill a pixel short, which
                    // read as a twitch at the end of the slide.
                    lp.y = toY
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t: Throwable) {
                    }
                    onEnd?.invoke()
                }
            })
        }
        setAnimator(slot, anim)
        anim.start()
    }

    /**
     * One handover at a time, minimum 400 ms apart. The keyguard flag, the broadcasts and the
     * root log all report the same change within a few hundred ms of each other; acting on every
     * one of them is what made the pill vanish, come back, then vanish again.
     */
    private fun handoverAllowed(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandoverAt < 400L) {
            Log.i("handover suppressed: ${now - lastHandoverAt}ms since the last one")
            return false
        }
        lastHandoverAt = now
        return true
    }

    /**
     * A suppressed handover used to just return, which - when the earlier one had already taken
     * its window down - left the screen with no pill at all, and nothing left to retry. Look again
     * once the duplicate has had time to settle.
     */
    private fun scheduleResync() {
        try {
            handler.postDelayed({
                try {
                    watchdogCheck(force = true)
                } catch (t: Throwable) {
                    Log.w("handover resync failed", t)
                }
            }, RESYNC_MS)
        } catch (t: Throwable) {
            Log.w("resync scheduling failed", t)
        }
    }

    /**
     * Compares the windows on screen with the ones this lock state asks for and repairs a
     * difference that persists. [force] skips the two-strike rule for the resync after a
     * suppressed handover, where the state is already known to be wrong.
     */
    private fun watchdogCheck(force: Boolean) {
        if (!TuningStore.isOn(this, TuningStore.MASTER)) {
            watchSignature = ""
            watchStreak = 0
            return
        }
        if (transitioning) return
        // The keyguard pill lands on a deliberate delay after screen-off; do not race that.
        if (handler.hasCallbacks(showLockPillAfterScreenOff)) return

        val locked = lastLocked ?: isKeyguardLocked().also { lastLocked = it }
        val items = NowBarState.items(this)
        // Suppressed while a camera / call / other window covers the keyguard: the lock pill is
        // meant to be absent there, so the watchdog must not "repair" it back on top.
        // Measure mode keeps a surface up with nothing active: the shape is what is being
        // configured, so an empty pill still belongs on screen.
        val outline = TuningStore.outline(this)
        val wantLock = locked && !lockPillSuppressed &&
            enabled(TuningStore.SURFACE_LOCK) && (items.isNotEmpty() || outline)
        // The status bar pill has no idle case to hide in: it stands in for Android's own battery
        // and signal icons, so it belongs on the strip whenever the phone is unlocked - with the
        // idle label as its text, or no text at all when that label is blank.
        val wantSb = !locked && enabled(TuningStore.SURFACE_STATUS)
        val haveLock = lockPill?.isAttachedToWindow == true
        val haveSb = sbPill?.isAttachedToWindow == true

        if (wantLock == haveLock && wantSb == haveSb) {
            watchSignature = ""
            watchStreak = 0
            return
        }

        val signature = "locked=$locked wantLock=$wantLock haveLock=$haveLock " +
            "wantSb=$wantSb haveSb=$haveSb"
        if (!force) {
            // One disagreement is normal: a window is added or removed across a frame or two.
            if (signature != watchSignature) {
                watchSignature = signature
                watchStreak = 1
                Log.i("watchdog: surfaces disagree ($signature) - checking again")
                return
            }
            watchStreak++
            if (watchStreak < 2) return
        }
        watchSignature = ""
        watchStreak = 0
        Log.i("watchdog: repairing surfaces ($signature)")
        try {
            if (!wantLock && lockPill != null) removeLockPill()
            if (!wantSb && sbPill != null) removeSbPill()
            if (wantLock && !haveLock) {
                lockPill?.let {
                    detachDetached(it)
                    lockPill = null
                    lockParams = null
                }
                addLockPill(fadeIn = true)
            }
            if (wantSb && !haveSb) {
                sbPill?.let {
                    detachDetached(it)
                    sbPill = null
                    sbParams = null
                }
                addSbPill(fadeIn = true)
            }
            // A popup whose window is gone cannot be collapsed back into the pill: drop the state
            // so the next tap opens it properly instead of doing nothing.
            val pop = popupPill
            if (pop != null && !pop.isAttachedToWindow) {
                popupAnimator?.cancel()
                popupAnimator = null
                popupPill = null
                popupParams = null
                Log.i("watchdog: dropped a detached popup")
            }
        } catch (t: Throwable) {
            Log.w("watchdog repair failed", t)
        }
    }

    /** Removes a window the watchdog found detached, ignoring the error that means it is gone. */
    private fun detachDetached(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            // Already gone: nothing to remove.
        }
    }

    private fun cancelAnimator(slot: Int) {
        val animator = when (slot) {
            0 -> lockAnimator
            1 -> sbAnimator
            else -> popupAnimator
        }
        animator?.cancel()
        setAnimator(slot, null)
    }

    private fun setAnimator(slot: Int, animator: ValueAnimator?) {
        when (slot) {
            0 -> lockAnimator = animator
            1 -> sbAnimator = animator
            else -> popupAnimator = animator
        }
    }

    // ----------------------------------------------------------- lock screen

    private fun addLockPill(slideIn: Boolean = false, fadeIn: Boolean = false) {
        try {
            if (lockPill != null) return
            // Another window is over the keyguard (camera, call, an overlay): the lockscreen
            // pill stays down until that window is gone.
            if (lockPillSuppressed) {
                Log.i("lock pill skipped: $coveringWindow is over the keyguard")
                return
            }
            // The shade shows the top pill on purpose; a lock-state event landing while it opens
            // must not put the bottom one back on top of the notification list.
            if (shadeOpen()) {
                Log.i("lock pill skipped: the shade owns the screen")
                return
            }
            if (!enabled(TuningStore.SURFACE_LOCK)) return
            if (emptyForLock()) return
            val tuning = Tuning.current(this)
            val view = PillView(this).apply {
                // Collapsed: a tap opens the expansion. Expanded: a tap opens the source app.
                onTap = { if (lockExpanded) openCurrentApp(true) else toggleLockExpand() }
                onSwipe = { dx, dy -> handleSwipe(true, dx, dy) }
                onControl = { action -> handleControl(true, action) }
                onSeek = { fraction -> seekFront(fraction) }
                // Content only: the lock screen never shows the idle placeholder.
                allowIdle = false
            }
            view.setOutlineMode(TuningStore.outline(this))
            val w = dp(tuning.widthDp)
            val h = dp(tuning.heightDp)
            val targetY = lockTopY(tuning.bottomDp, h)
            // The front card keeps the tuned position; the stack grows downwards from it, so the
            // window gets taller without the card moving.
            val windowH = h + peekExtra()
            // TOP gravity from the very first frame. Flipping gravity mid-animation makes
            // WindowManager recompute the window frame, which is what tore the pill in half
            // when unlocking out of the AOD.
            val lp = WindowManager.LayoutParams(
                w,
                windowH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                overlayFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = if (slideIn) screenHeightPx() else targetY
                if (fadeIn) alpha = 0f
            }
            windowManager.addView(view, lp)
            // A window cannot draw outside itself, so the card gets room to move for the length
            // of a drag and the window closes up again when it is back in place.
            view.onDragStart = { beginWindowDrag(view) }
            view.onDragMove = { dx, dy -> moveDragWindow(view, dx, dy) }
            view.onDragEnd = { endWindowDrag(view) }
            lockPill = view
            lockParams = lp
            lockExpanded = false
            disarmAutoClose()
            // A pill that appears while the display is off is part of the AOD, so it starts grey
            // and in the owner's AOD look.
            val dozing = !isInteractive()
            view.setGray(
                if (dozing && TuningStore.isOn(this, TuningStore.AOD_GRAY)) 1f else 0f,
                0L
            )
            view.setLineStyle(dozing && TuningStore.isOn(this, TuningStore.AOD_LINE_STYLE))
            view.setStyle(tuning)
            if (slideIn) {
                animateY(view, lp, lp.y, targetY, SLIDE_MS, DecelerateInterpolator(1.6f), null)
                Log.i("lock pill sliding in from the bottom")
            } else {
                lp.y = targetY
                windowManager.updateViewLayout(view, lp)
                if (fadeIn) animateAlphaIn(view, lp)
            }
            applyContent()
            Log.i("lock pill added: ${w}x${h}px at y=$targetY (top gravity)")
        } catch (t: Throwable) {
            Log.e("a11y addLockPill failed", t)
        }
    }

    private fun removeLockPill() {
        lockAnimator?.cancel()
        lockAnimator = null
        lockExpanded = false
        disarmAutoClose()
        dragActive = false
        dragReturning = false
        dragSettleAnimator?.cancel()
        dragSettleAnimator = null
        val view = lockPill ?: return
        try {
            windowManager.removeView(view)
            Log.i("lock pill removed")
        } catch (t: Throwable) {
            Log.w("a11y removeLockPill failed", t)
        }
        lockPill = null
        lockParams = null
    }

    /**
     * True while a finger is dragging this very surface. Moving or resizing a window mid-drag is
     * what made the card jump under the finger and, when the drag then shrank to a stale height,
     * disappear; the change is applied once the drag is over instead.
     */
    private fun dragOwns(view: PillView): Boolean {
        if (!dragActive && !dragReturning) return false
        Log.i("layout deferred: a drag owns this window")
        return true
    }

    private fun applyLockTuning() {
        val view = lockPill ?: return
        val lp = lockParams ?: return
        if (dragOwns(view)) return
        try {
            val t = Tuning.current(this)
            val target = lockTargetSize(t, lockExpanded)
            lp.width = target.w
            lp.height = target.h
            lp.y = lockTopY(t.bottomDp, lockCardHeight(t, lockExpanded))
            view.setStyle(t)
            view.setOutlineMode(TuningStore.outline(this))
            windowManager.updateViewLayout(view, lp)
            Log.i("lock pill updated: ${lp.width}x${lp.height}px expanded=$lockExpanded y=${lp.y}px")
        } catch (t: Throwable) {
            Log.w("lock applyTuning failed", t)
        }
    }

    /**
     * 2.5x while expanded, with the width clamped to the screen: 340 dp x 2.5 would be 850 dp
     * on a 411 dp display, so the height scales fully and the width stops at the screen edge.
     */
    /** Whole window: the front card plus the room the peeking cards behind it need. */
    private fun lockTargetSize(t: Tuning.Values, expanded: Boolean): Size {
        val card = lockCardSize(t, expanded)
        return Size(card.w, card.h + peekExtra())
    }

    /**
     * The front card on its own, which is what the tuned geometry describes.
     *
     * Expanded, the card is 2.5x the tuned size in width but only as tall as its content needs,
     * with 2.5x as the ceiling and the collapsed height as the floor: the same rule the popup
     * follows, so a card with one line of text does not grow into a mostly empty panel.
     */
    private fun lockCardSize(t: Tuning.Values, expanded: Boolean): Size {
        val w = dp(t.widthDp)
        val h = dp(t.heightDp)
        if (!expanded) return Size(w, h)
        val maxW = (screenWidthPx() - dp(16)).coerceAtLeast(w)
        val ceiling = (h * SCALE).toInt()
        // Measured on the live view, so it follows the text size and the card that is in front.
        val needed = lockPill?.preferredHeight(ceiling) ?: ceiling
        return Size(minOf((w * SCALE).toInt(), maxW), needed.coerceIn(h, ceiling))
    }

    private fun lockCardHeight(t: Tuning.Values, expanded: Boolean): Int =
        lockCardSize(t, expanded).h

    /** Extra window height for the cards peeking below the front one. */
    private fun peekExtra(): Int {
        val count = NowBarState.stackSize(this)
        if (count <= 1) return 0
        return dp(TuningStore.stackPeekDp(this)) * (count - 1)
    }

    /** Tap: grow to 2.5x, tap again (or tap anywhere else) to settle back. */
    private fun toggleLockExpand() {
        val view = lockPill ?: return
        val lp = lockParams ?: return
        try {
            val t = Tuning.current(this)
            val expanding = !lockExpanded
            lockExpanded = expanding
            val peek = dp(TuningStore.stackPeekDp(this))
            // Content without touching the size: applyContent() would resize the window to the
            // expanded target immediately and there would be nothing left to animate.
            //
            // Growing switches content now, so the card fills out as it grows. Shrinking does NOT:
            // swapping the card back to one line at full size and then cropping it with a shrinking
            // window is what made the collapse visibly jump. On the way down the expanded layout
            // stays until the window has closed.
            if (expanding) view.setItems(ordered(), true, peek)
            val target = lockTargetSize(t, expanding)
            lockAnimator?.cancel()
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                val fromW = lp.width
                val fromH = lp.height
                duration = EXPAND_MS
                // No overshoot, and the same curve in both directions: the bounce at the end read
                // as a stutter, and an ease-in-out collapse started by crawling for the first few
                // frames, which is what made closing feel sluggish next to opening.
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                var lastW = -1
                var lastH = -1
                addUpdateListener {
                    val f = it.animatedValue as Float
                    val w = Math.round(fromW + (target.w - fromW) * f)
                    val h = Math.round(fromH + (target.h - fromH) * f)
                    // One relayout per changed size. Pushing a frame when the rounded size had not
                    // moved was most of the visible jitter.
                    if (w == lastW && h == lastH) return@addUpdateListener
                    lastW = w
                    lastH = h
                    lp.width = w
                    lp.height = h
                    // With TOP gravity the bottom edge has to be held manually while it grows.
                    // The stack's extra height sits below the card, so it is subtracted here.
                    lp.y = lockTopY(t.bottomDp, (h - peekExtra()).coerceAtLeast(1))
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t2: Throwable) {
                    }
                }
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Finish exactly on the target, so a second tap never grows from a half size.
                    lp.width = target.w
                    lp.height = target.h
                    lp.y = lockTopY(t.bottomDp, lockCardHeight(t, expanding))
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t2: Throwable) {
                    }
                    // Closed: now that the card is at its final size, one line replaces two.
                    if (!expanding) view.setItems(ordered(), false, peek)
                }
            })
            lockAnimator = anim
            anim.start()
            if (expanding) armAutoClose() else disarmAutoClose()
            // Only a tap outside the expansion closes it; taps inside belong to the controls.
            view.onOutsideTouch = if (expanding) {
                { if (lockExpanded) toggleLockExpand() }
            } else {
                null
            }
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Log.i("lock pill ${if (expanding) "expanded" else "collapsed"}: ${target.w}x${target.h}px")
        } catch (t: Throwable) {
            Log.w("lock expand failed", t)
        }
    }

    // ------------------------------------------------------------ status bar

    private fun addSbPill(slideIn: Boolean = false, fadeIn: Boolean = false) {
        try {
            // The bar came back while the pill was on its way out: catch it mid-flight and put it
            // back, instead of leaving it half-faded and then removing it once the animation ends.
            if (sbPill != null && sbLeavingWithBar) {
                sbLeavingWithBar = false
                cancelAnimator(1)
                val view = sbPill
                val lp = sbParams
                if (view != null && lp != null) {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    try {
                        animateSbReturn(view, lp)
                    } catch (t: Throwable) {
                        Log.w("sb bar return failed", t)
                        view.visibility = View.VISIBLE
                        lp.alpha = 1f
                        lp.y = sbPillY()
                    }
                }
                Log.i("sb pill caught coming back with the status bar")
                return
            }
            if (sbPill != null) return
            // Read fresh, not cached: during an unlock the cached value can still describe the
            // AOD, where the bar is hidden, and that would swallow the handover.
            sbHidden = readStatusBarHidden()
            if (sbHidden) {
                Log.i("sb pill skipped: the status bar is hidden")
                return
            }
            if (!enabled(TuningStore.SURFACE_STATUS)) return
            val t = Tuning.sb(this)
            val view = PillView(this).apply {
                // The mini pill has no controls of its own: a tap is what opens the expansion.
                onTap = { expandFromSbPill() }
                onSwipe = { _, _ -> }
                onControl = { }
                // It ignores swipes, so it must not follow the finger either: a drag on something
                // that cannot change cards was just the card sliding under its own clipped edge.
                dragEnabled = false
                // The placeholder belongs to an unlocked phone. Until one real unlock has been
                // seen, the pill draws content only.
                allowIdle = unlockedOnce
            }
            val lp = WindowManager.LayoutParams(
                dp(t.widthDp),
                dp(t.heightDp),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                overlayFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            placeSb(lp, t)
            // Opened from the shade (so, on the lock screen too): visible only, no taps.
            if (shadeOpen()) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                view.interactive = false
            }
            val targetY = lp.y
            if (slideIn) lp.y = -lp.height
            // Alpha 0 goes into the LayoutParams *before* addView. Setting it afterwards makes the
            // window draw once at full alpha, disappear, then fade back in - the flicker.
            if (fadeIn) lp.alpha = 0f
            view.setStyle(t.label, t.textDp, t.radiusDp)
            view.setOutlineMode(TuningStore.outline(this))
            // The inverse glyphs are part of the measured width, so set them before fitting.
            view.setStatusGlyphs(
                t.inverse,
                batteryPct,
                cellLevel(),
                t.signalFirst,
                Networks.kind(this)
            )
            // Content before the add, so the first frame is already the real item.
            view.setItems(NowBarState.items(this).take(1), false, 0)
            // A short line gets a short pill: the tuned width is the maximum, not a fixed size.
            lp.width = view.preferredWidth(sbMaxWidthPx(), lp.height)
            windowManager.addView(view, lp)
            sbPill = view
            sbParams = lp
            if (slideIn) {
                animateY(view, lp, -lp.height, targetY, SLIDE_MS, DecelerateInterpolator(1.6f), null)
            } else if (fadeIn) {
                animateAlphaIn(view, lp)
            }
            Log.i(
                "sb pill added: ${t.widthDp}x${t.heightDp}dp at x=${lp.x} y=$targetY " +
                    "(slideIn=$slideIn statusBarTop=${statusBarTopPx()}px cutoutRight=${cutoutRightPx()}px)"
            )
        } catch (t: Throwable) {
            Log.e("sb pill add failed", t)
        }
    }

    /**
     * Width of the status bar window follows its line: the tuned width is a maximum.
     *
     * The change is animated rather than snapped: a new title, a timer appearing or the card
     * going away makes the pill flow to its new width (and back) like the same shape stretching,
     * which is what a one-line pill reads as. Short, so it stays in step with the card animations.
     */
    private fun fitSbWidth(view: PillView) {
        try {
            val lp = sbParams ?: return
            // The shade and the status bar show the same pill: always fit the line.
            val wanted = view.preferredWidth(sbMaxWidthPx(), lp.height)
            if (lp.width == wanted && sbWidthAnimator?.isRunning != true) return
            // Several preferences can land in the same applyAll: an animation already heading for
            // this width is left alone instead of being restarted from its current frame.
            if (wanted == sbWidthTarget && sbWidthAnimator?.isRunning == true) return
            // A different line arrived mid-morph: retarget from the width currently on screen.
            sbWidthAnimator?.cancel()
            val from = lp.width
            sbWidthTarget = wanted
            Log.i("sb width morph: ${from}px -> ${wanted}px (max ${sbMaxWidthPx()}px)")
            val anim = ValueAnimator.ofInt(from, wanted).apply {
                duration = WIDTH_MS
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    if (sbPill !== view || sbParams !== lp) return@addUpdateListener
                    lp.width = it.animatedValue as Int
                    // Where the right edge is the fixed one (inverse, and landscape always) the
                    // left edge has to follow the new width, or the pill grows to the right and
                    // drifts away from the margin it was tuned to.
                    val ts = Tuning.sb(this@NowBarA11yService)
                    if (isLandscape() || ts.inverse) lp.x = sbXFor(lp.width, ts)
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t: Throwable) {
                        Log.w("sb width frame failed", t)
                    }
                }
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (sbWidthAnimator === anim) sbWidthTarget = -1
                }
            })
            sbWidthAnimator = anim
            anim.start()
        } catch (t: Throwable) {
            Log.w("sb fit failed", t)
        }
    }

    private fun removeSbPill() {
        sbAnimator?.cancel()
        sbAnimator = null
        sbWidthAnimator?.cancel()
        sbWidthAnimator = null
        sbLeavingWithBar = false
        val view = sbPill ?: return
        try {
            windowManager.removeView(view)
            Log.i("sb pill removed")
        } catch (t: Throwable) {
            Log.w("sb removeView failed", t)
        }
        sbPill = null
        sbParams = null
    }

    private fun applySbTuning() {
        val view = sbPill ?: return
        val lp = sbParams ?: return
        if (dragOwns(view)) return
        try {
            val t = Tuning.sb(this)
            // Style first, so the width is measured from the new label and text size, not the old.
            view.setStyle(t.label, t.textDp, t.radiusDp)
            view.setOutlineMode(TuningStore.outline(this))
            view.setStatusGlyphs(
                t.inverse,
                batteryPct,
                cellLevel(),
                t.signalFirst,
                Networks.kind(this)
            )
            val onScreen = lp.width
            placeSb(lp, t)
            // Position and height change at once, the width flows to its new value below. Keeping
            // the current width for that first frame is what stops the pill flashing wide when a
            // preference lands.
            lp.width = onScreen
            // ...and the edge has to be worked out from that width, not from the tuned maximum:
            // centring with the maximum put the pill half the difference off centre, which is the
            // "centred for a second, then it shifts" jolt.
            if (isLandscape() || t.inverse) lp.x = sbXFor(lp.width, t)
            windowManager.updateViewLayout(view, lp)
            fitSbWidth(view)
            Log.i("sb pill updated: ${lp.width}x${lp.height}px at x=${lp.x} y=${lp.y}px")
        } catch (t: Throwable) {
            Log.w("sb applyTuning failed", t)
        }
    }

    private fun isLandscape(): Boolean = screenWidthPx() > screenHeightPx()

    private fun sbTopDp(t: Tuning.Sb): Int =
        if (isLandscape() && t.topLandscapeDp >= 0) t.topLandscapeDp else t.topDp

    private fun placeSb(lp: WindowManager.LayoutParams, t: Tuning.Sb) {
        lp.width = sbWidthPx(t)
        lp.height = dp(t.heightDp)
        Log.i(
            "placing sb pill: screen=${screenWidthPx()}x${screenHeightPx()} " +
                "landscape=${isLandscape()} shade=${shadeOpen()}"
        )
        // A value saved in another orientation (or from before a rotation) can land outside the
        // screen; that is how the pill vanished in landscape. Out-of-range values fall back to
        // the automatic place, which is itself clamped to the screen.
        lp.y = sbYFor(t, lp.height)
        // Normal portrait layout: the tuned left edge or just past the clock. Inverse, and every
        // landscape layout, anchor the pill's right edge instead.
        lp.x = sbXFor(lp.width, t)
        // Never off the screen, whatever the numbers say.
        lp.x = lp.x.coerceIn(0, (screenWidthPx() - lp.width).coerceAtLeast(0))
    }

    /**
     * Where the status pill's left edge goes.
     *
     * Portrait, normal layout: the tuned left edge, or clear of the clock. Portrait inverse, and
     * landscape in either layout: the pill occupies the right end of the strip (Android's battery /
     * signal corner there too), so its left edge follows from its width and that orientation's
     * right margin.
     */
    private fun sbXFor(widthPx: Int, t: Tuning.Sb): Int {
        if (isLandscape()) return rightAnchoredX(widthPx, sbRightMarginDp(t))
        if (t.inverse) return rightAnchoredX(widthPx, t.rightMarginDp)
        val left = t.leftDp
        return if (left >= 0) dp(left) else autoSbLeft(widthPx)
    }

    /** Landscape's right margin: its own value, or the portrait one when unset. */
    private fun sbRightMarginDp(t: Tuning.Sb): Int =
        if (t.rightMarginLandscapeDp >= 0) t.rightMarginLandscapeDp else t.rightMarginDp

    /** Left edge that leaves [marginDp] between the pill and the screen's right edge. */
    private fun rightAnchoredX(widthPx: Int, marginDp: Int): Int =
        (screenWidthPx() - widthPx - dp(marginDp)).coerceAtLeast(0)

    /**
     * How wide the pill may grow: the tuned width for this orientation. Landscape has its own width
     * (falling back to the portrait one), which is how it can take up a whole landscape strip
     * instead of being held to a status-bar-sized box.
     */
    private fun sbMaxWidthPx(): Int = sbWidthPx(Tuning.sb(this))

    /** The width the pill is laid out at: portrait width, or the landscape override. */
    private fun sbWidthPx(t: Tuning.Sb): Int =
        if (isLandscape() && t.widthLandscapeDp >= 0) dp(t.widthLandscapeDp) else dp(t.widthDp)

    /**
     * The clock's right edge as the hook saw it, 0 when there is no clock on screen. The pill is
     * clamped to the right of it, so a tuned left edge (or the landscape one) can never end up
     * underneath the time.
     */
    private fun clockRightPx(): Int = try {
        Settings.Global.getInt(contentResolver, NowBarHook.KEY_CLOCK_RIGHT, 0)
    } catch (t: Throwable) {
        0
    }

    /**
     * The pill is only there to be looked at while the shade owns the screen: a tap would land on
     * the shade's own header, and the shade has its own controls for everything. The whole window
     * is made untouchable, so touches fall through to the shade rather than being swallowed.
     */
    private fun setSbTouchable(touchable: Boolean) {
        val view = sbPill ?: return
        val lp = sbParams ?: return
        try {
            val flags = lp.flags
            val want = if (touchable) {
                flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            view.interactive = touchable
            if (want == flags) return
            lp.flags = want
            windowManager.updateViewLayout(view, lp)
            Log.i("sb pill ${if (touchable) "touchable" else "only visible (shade open)"}")
        } catch (t: Throwable) {
            Log.w("sb touchability change failed", t)
        }
    }

    /**
     * While the shade or Quick Settings is open its own header owns the top of the screen, and the
     * keyguard's bottom pill would be behind the notification list. So: the top pill is what shows,
     * locked or not, and it is not interactive there.
     */
    private fun syncShadeSurfaces() {
        try {
            if (shadeOpen()) {
                removeLockPill()
                removePopup()
                if (sbPill == null && enabled(TuningStore.SURFACE_STATUS)) addSbPill()
                if (sbPill != null) {
                    setSbTouchable(false)
                    applySbTuning()
                }
                Log.i("shade open: top pill shown, not interactive")
            } else {
                setSbTouchable(true)
                if (sbPill != null) applySbTuning()
                // Back to the normal rules: the keyguard pill if locked, the top pill if not.
                updateSurfaces()
            }
        } catch (t: Throwable) {
            Log.w("shade surface sync failed", t)
        }
    }

    /** Wall-clock time the device booted, which stays fixed for one boot and changes per boot. */
    private fun bootMarker(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Whether an unlock has happened in *this* boot. Remembered across restarts of the service so
     * a restart while the phone is already unlocked does not lose the placeholder until the next
     * unlock - while a fresh boot still starts without it, which is what keeps the placeholder off
     * the boot lock screen.
     */
    private fun unlockSeenThisBoot(): Boolean = try {
        val p = session
        val stored = p.getLong(UNLOCK_BOOT, 0L)
        val when_ = p.getLong(UNLOCK_UPTIME, 0L)
        stored != 0L &&
            kotlin.math.abs(bootMarker() - stored) < 5_000L &&
            when_ > 0L &&
            when_ <= SystemClock.elapsedRealtime()
    } catch (t: Throwable) {
        false
    }

    /** A trusted unlock landed: from now on the status pill may fall back to its placeholder. */
    private fun markUnlocked() {
        if (unlockedOnce) return
        unlockedOnce = true
        try {
            // A file of its own, not the tuning prefs: writing there fires the listener that
            // re-places every window, which is the last thing a handover needs.
            session.edit()
                .putLong(UNLOCK_BOOT, bootMarker())
                .putLong(UNLOCK_UPTIME, SystemClock.elapsedRealtime())
                .apply()
        } catch (t: Throwable) {
            Log.w("markUnlocked: could not remember the unlock", t)
        }
        try {
            // The window may exist but be drawing nothing at all; a repaint is what brings the
            // placeholder on screen, since no item change is coming to trigger one.
            sbPill?.let {
                it.allowIdle = true
                it.invalidate()
            }
        } catch (t: Throwable) {
            Log.w("markUnlocked failed", t)
        }
    }

    /** True while the shade / Quick Settings is open, as pushed by the hook. */
    private fun shadeOpen(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, NowBarHook.KEY_SHADE_OPEN, 0) == 1
        } catch (t: Throwable) {
            false
        }
    }

    private fun sbPillX(): Int {
        val t = Tuning.sb(this)
        // The width on screen, not the tuned maximum: the pill is fitted to its line, so the edge
        // it is anchored to has to be worked out from where it is actually drawn.
        val width = sbParams?.width ?: dp(t.widthDp)
        return sbXFor(width, t).coerceIn(0, (screenWidthPx() - width).coerceAtLeast(0))
    }

    private fun sbPillY(): Int = sbYFor(Tuning.sb(this), dp(Tuning.sb(this).heightDp))

    /**
     * Top edge for the status pill, clamped to the screen. A tuned value saved in the other
     * orientation (a landscape-only top) used to put the pill below the display after a rotation;
     * an out-of-range value now falls back to the automatic place instead.
     */
    private fun sbYFor(t: Tuning.Sb, heightPx: Int): Int {
        val maxY = (screenHeightPx() - heightPx).coerceAtLeast(0)
        val tunedY = if (sbTopDp(t) >= 0) dp(sbTopDp(t)) else -1
        if (tunedY in 0..maxY) return tunedY
        val inset = statusBarTopPx().coerceIn(0, maxY)
        return ((inset - heightPx) / 2).coerceIn(0, maxY)
    }

    /**
     * Where the pill goes when no left edge is tuned: clear of the camera cutout, and with a gap
     * after the clock.
     *
     * Only this branch is fitted around the clock. A tuned left edge is used exactly as given, even
     * when that puts the pill over the time - the number is the owner's, and clamping it made every
     * value under about 70 dp land on the same spot, so the slider stopped doing anything.
     */
    private fun autoSbLeft(pillWidthPx: Int): Int {
        var x = defaultSbLeft(pillWidthPx)
        val clockRight = clockRightPx()
        if (clockRight > 0) x = x.coerceAtLeast(clockRight + dp(SB_CLOCK_GAP_DP))
        return x.coerceIn(0, (screenWidthPx() - pillWidthPx).coerceAtLeast(0))
    }

    private fun defaultSbLeft(pillWidthPx: Int): Int {
        val cut = cutoutRightPx()
        return if (cut > 0) cut + dp(8) else dp(16)
    }

    // ------------------------------------------------------------- dismissal

    // Dismissal used to be a full-screen transparent catcher window added above everything.
    // That window sat above the expansion itself, so tapping the pill closed it. The windows now
    // carry FLAG_WATCH_OUTSIDE_TOUCH and handle ACTION_OUTSIDE instead: taps inside reach the
    // pill (its controls), taps outside arrive as ACTION_OUTSIDE and close the expansion.

    // ---------------------------------------------------------------- popup

    private data class Size(val w: Int, val h: Int)

    private data class Rect4(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun pillRect(): Rect4 {
        val t = Tuning.sb(this)
        val lp = sbParams
        // The size on screen, not the tuned maximum: the pill shrinks to its line, and the
        // expansion has to start from the pill the owner actually sees.
        return Rect4(
            sbPillX(),
            sbPillY(),
            lp?.width ?: dp(t.widthDp),
            lp?.height ?: dp(t.heightDp)
        )
    }

    private fun popupTargetRect(view: PillView? = popupPill): Rect4 {
        val p = Tuning.popup(this)
        val w = dp(p.widthDp)
        val tuned = dp(p.heightDp)
        // The tuned height is a ceiling, not a target: a one-line card with no scrubber gets a
        // shorter popup instead of a band of empty card under it.
        val front = view?.preferredHeight(tuned) ?: tuned
        val h = front + peekExtra()
        // The card is centred in both layouts, whatever the pill is doing (left, right or
        // landscape): an expanded card that hung off one edge read as a panel stuck to the pill
        // rather than the pill having opened up. Only an explicit left edge overrides this.
        val landscape = screenWidthPx() > screenHeightPx()
        val x = if (p.leftDp >= 0) {
            dp(p.leftDp).coerceIn(0, (screenWidthPx() - w).coerceAtLeast(0))
        } else {
            ((screenWidthPx() - w) / 2).coerceAtLeast(0)
        }
        val baseY = if (p.topDp >= 0) dp(p.topDp) else statusBarTopPx() + dp(POPUP_GAP_DP)
        // In landscape the bar's own clock sits closer to the top, and the inset above the app is
        // shallower, so the popup is pushed down by an adjustable amount to clear it.
        val y = if (landscape) baseY + dp(p.landscapeTopDp) else baseY
        return Rect4(x, y, w, h)
    }

    /**
     * Opens the popup from the mini pill's own rect and animates it to its full size, so it
     * looks like the pill itself sized up; the mini pill stays visible underneath.
     */
    private fun expandFromSbPill() {
        try {
            if (popupPill != null) return
            if (!enabled(TuningStore.SURFACE_POPUP)) return
            val pop = Tuning.popup(this)
            val start = pillRect()

            val view = PillView(this).apply {
                setStyle(pop.label, pop.textDp, pop.radiusDp)
                setOutlineMode(TuningStore.outline(this@NowBarA11yService))
                // The popup is the expanded status view: it is the only status surface with a control.
                setItems(
                    ordered(),
                    true,
                    dp(TuningStore.stackPeekDp(this@NowBarA11yService))
                )
                onTap = { openCurrentApp(false) }
                onSwipe = { dx, dy -> handleSwipe(false, dx, dy) }
                onControl = { action -> handleControl(false, action) }
                onSeek = { fraction -> seekFront(fraction) }
                // Taps inside the popup are for its controls; an outside tap closes it.
                onOutsideTouch = { collapseToSbPill() }
                allowIdle = false
            }
            // Measured from the view itself, because the height now follows the card's content.
            val target = popupTargetRect(view)
            val lp = WindowManager.LayoutParams(
                start.w,
                start.h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                overlayFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = start.x
                y = start.y
            }

            windowManager.addView(view, lp)
            view.onDragStart = { beginWindowDrag(view) }
            view.onDragMove = { dx, dy -> moveDragWindow(view, dx, dy) }
            view.onDragEnd = { endWindowDrag(view) }
            popupPill = view
            popupParams = lp
            armAutoClose()
            // Material's emphasised decelerate, with a light fade-up: the old overshoot bounced the
            // window past its own size and snapped back, which read as two separate motions.
            lp.alpha = 0.7f
            animatePopupRect(
                view,
                lp,
                start,
                target,
                PathInterpolator(0.2f, 0f, 0f, 1f),
                EXPAND_MS,
                fadeTo = 1f,
                fadeFrom = 0.7f,
                onEnd = null
            )
            Log.i(
                "popup expanding from pill ${start.w}x${start.h} at ${start.x},${start.y} " +
                    "to ${target.w}x${target.h} at ${target.x},${target.y}"
            )
        } catch (t: Throwable) {
            Log.e("popup expand failed", t)
        }
    }

    private fun collapseToSbPill() {
        val view = popupPill ?: return
        val lp = popupParams ?: return
        disarmAutoClose()
        try {
            val start = Rect4(lp.x, lp.y, lp.width, lp.height)
            val pill = pillRect()
            // Freeze the card at its full size and let the shrinking window crop it from the top:
            // the controls and text slide up behind the pill instead of being squashed into it.
            view.setContentHeight(lp.height)
            animatePopupRect(
                view,
                lp,
                start,
                pill,
                PathInterpolator(0.3f, 0f, 0.7f, 0.4f),
                COLLAPSE_MS,
                fadeTo = 0.1f
            ) {
                removePopup()
                Log.i("popup collapsed back into the status bar pill (pill stayed visible)")
            }
        } catch (t: Throwable) {
            Log.e("popup collapse failed", t)
        }
    }

    private fun removePopup() {
        popupAnimator?.cancel()
        popupAnimator = null
        disarmAutoClose()
        dragActive = false
        dragReturning = false
        dragSettleAnimator?.cancel()
        dragSettleAnimator = null
        val view = popupPill ?: return
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            Log.w("popup removeView failed", t)
        }
        popupPill = null
        popupParams = null
        Log.i("popup removed")
    }

    private fun applyPopupTuning() {
        val view = popupPill ?: return
        val lp = popupParams ?: return
        if (dragOwns(view)) return
        try {
            val pop = Tuning.popup(this)
            val target = popupTargetRect()
            lp.x = target.x
            lp.y = target.y
            lp.width = target.w
            lp.height = target.h
            view.setStyle(pop.label, pop.textDp, pop.radiusDp)
            view.setOutlineMode(TuningStore.outline(this))
            windowManager.updateViewLayout(view, lp)
            Log.i("popup updated: ${target.w}x${target.h} at ${target.x},${target.y}")
        } catch (t: Throwable) {
            Log.w("popup tuning failed", t)
        }
    }

    private fun animatePopupRect(
        view: View,
        lp: WindowManager.LayoutParams,
        from: Rect4,
        to: Rect4,
        interpolator: TimeInterpolator,
        durationMs: Long,
        fadeTo: Float? = null,
        fadeFrom: Float? = null,
        onEnd: (() -> Unit)?
    ) {
        popupAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.x = (from.x + (to.x - from.x) * f).toInt()
                lp.y = (from.y + (to.y - from.y) * f).toInt()
                lp.width = (from.w + (to.w - from.w) * f).toInt()
                lp.height = (from.h + (to.h - from.h) * f).toInt()
                // Fading while it falls into the pill hides the last, smallest frames where the
                // clipped card would still show a sliver of itself. Expanding fades up from
                // [fadeFrom] instead, so the card arrives rather than being stretched into place.
                if (fadeTo != null) {
                    val from = fadeFrom ?: 1f
                    lp.alpha = from + f * (fadeTo - from)
                }
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (t: Throwable) {
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Land exactly on the target rect: the overshoot interpolator plus int
                    // rounding used to leave the popup a few pixels off its own tuning, which is
                    // why it never quite matched the numbers.
                    lp.x = to.x
                    lp.y = to.y
                    lp.width = to.w
                    lp.height = to.h
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t: Throwable) {
                    }
                    onEnd?.invoke()
                }
            })
        }
        popupAnimator = anim
        anim.start()
    }

    /**
     * Short fade for the pill that takes over during a handover. The outgoing window is still
     * removed immediately; only the incoming one eases in, so the swap has motion without delay.
     */
    private fun animateAlphaIn(view: View, lp: WindowManager.LayoutParams, durationMs: Long = 120L) {
        try {
            val slot = if (view === lockPill) 0 else 1
            cancelAnimator(slot)
            val anim = ValueAnimator.ofFloat(lp.alpha.coerceAtLeast(0f), 1f).apply {
                duration = durationMs
                addUpdateListener {
                    lp.alpha = it.animatedValue as Float
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t: Throwable) {
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        lp.alpha = 1f
                        try {
                            windowManager.updateViewLayout(view, lp)
                        } catch (t: Throwable) {
                        }
                    }
                })
            }
            setAnimator(slot, anim)
            anim.start()
        } catch (t: Throwable) {
            Log.w("fadeIn failed", t)
        }
    }

    // ----------------------------------------------------------------- misc

    private fun overlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    /**
     * Dragging moves the window itself.
     *
     * The old mechanic grew the window and drew the card offset inside it, which cannot work: a
     * window clips to its own bounds, so the card was sliced at an invisible edge (the "box" around
     * it), and every offset, growth and shrink had to be undone in the right order or the card was
     * left outside its window and vanished. Moving the window keeps it exactly the size of the card,
     * so there is nothing to clip and nothing to restore.
     */
    private fun beginWindowDrag(view: PillView) {
        try {
            dragActive = true
            // An expansion still in flight would fight the finger and then replay afterwards - that
            // replay is the "falls down from the top" jump. Its own easing also leaves the window a
            // few pixels short of the resting spot, which is handled on the way back.
            cancelAnimator(if (view === lockPill) 0 else 2)
            dragSettleAnimator?.cancel()
            dragSettleAnimator = null
            dragReturning = false
            view.dragMovesWindow = true
            val lp = (if (view === lockPill) lockParams else popupParams)
            Log.i("drag: window follows the finger from ${lp?.x},${lp?.y}")
        } catch (t: Throwable) {
            Log.w("drag start failed", t)
        }
    }

    /** Applies one frame of the drag to the window, kept fully on screen. */
    private fun moveDragWindow(view: PillView, dx: Int, dy: Int) {
        try {
            if (!dragActive) return
            // Read the params fresh every frame: expanding replaces the object, so a captured one
            // would be a copy the window no longer uses - which is exactly why the drag computed
            // its actions but the card never moved.
            val lp = (if (view === lockPill) lockParams else popupParams) ?: return
            if (view !== lockPill && view !== popupPill) return
            lp.x = dragX(lp, dx)
            lp.y = (lp.y + dy).coerceIn(0, (screenHeightPx() - lp.height).coerceAtLeast(0))
            windowManager.updateViewLayout(view, lp)
        } catch (t: Throwable) {
            Log.w("drag move failed", t)
        }
    }

    /**
     * A window's own x is not always its left edge: the lock pill is centred horizontally, so its
     * x is an offset from the centre and may be negative. Clamping that to zero is what left the
     * lock pill able to move only up and down.
     */
    private fun dragX(lp: WindowManager.LayoutParams, dx: Int): Int {
        val centred = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
        return if (centred) {
            val room = ((screenWidthPx() - lp.width) / 2).coerceAtLeast(0)
            (lp.x + dx).coerceIn(-room, room)
        } else {
            (lp.x + dx).coerceIn(0, (screenWidthPx() - lp.width).coerceAtLeast(0))
        }
    }

    /** Where this window belongs right now, in the coordinates its gravity uses. */
    private fun restPointOf(view: PillView): android.graphics.Point? {
        return try {
            if (view === lockPill) {
                val t = Tuning.current(this)
                android.graphics.Point(0, lockTopY(t.bottomDp, lockCardHeight(t, lockExpanded)))
            } else {
                val r = popupTargetRect(popupPill)
                android.graphics.Point(r.x, r.y)
            }
        } catch (t: Throwable) {
            Log.w("resting point unreadable", t)
            null
        }
    }

    /**
     * The card is home (or is easing home): drop the drag and land exactly on the resting spot.
     *
     * The offsets the view owns only bring the card back to where the finger found it, and that is
     * not always where it belongs: the card can be resized by content arriving while the drag is in
     * flight, and an expansion may still have been running when the drag began. So the last few
     * pixels are eased here, over the same unhurried curve, instead of being snapped.
     */
    private fun endWindowDrag(view: PillView) {
        try {
            dragActive = false
            view.dragMovesWindow = false
            if (view !== lockPill && view !== popupPill) return
            val lp = (if (view === lockPill) lockParams else popupParams) ?: return
            // One motion, from wherever the finger left the window to where it belongs. The view
            // no longer animates its offsets when the window is what moved (see
            // PillView.handOverReturn), so there is nothing to add up and nothing to overshoot.
            val rest = restPointOf(view)
            if (rest != null && (lp.x != rest.x || lp.y != rest.y)) {
                slideWindowHome(view, lp, rest.x, rest.y)
            } else {
                placeAfterDrag(view)
            }
            Log.i("drag: returning to ${rest?.x},${rest?.y} from ${lp.x},${lp.y}")
        } catch (t: Throwable) {
            Log.w("drag end failed", t)
        }
    }

    /** Eases a window the last few pixels onto its resting spot. */
    private fun slideWindowHome(
        view: PillView,
        lp: WindowManager.LayoutParams,
        toX: Int,
        toY: Int
    ) {
        try {
            dragSettleAnimator?.cancel()
            dragReturning = true
            val fromX = lp.x
            val fromY = lp.y
            Log.i("drag: sliding home ${fromX},${fromY} -> $toX,$toY (${DRAG_HOME_MS}ms)")
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DRAG_HOME_MS
                interpolator = DecelerateInterpolator(2.2f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    lp.x = Math.round(fromX + (toX - fromX) * f)
                    lp.y = Math.round(fromY + (toY - fromY) * f)
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t: Throwable) {
                        Log.w("drag home frame failed", t)
                    }
                }
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (dragSettleAnimator === anim) dragSettleAnimator = null
                    dragReturning = false
                    lp.x = toX
                    lp.y = toY
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t: Throwable) {
                        Log.w("drag home end failed", t)
                    }
                    placeAfterDrag(view)
                }
            })
            dragSettleAnimator = anim
            anim.start()
        } catch (t: Throwable) {
            Log.w("drag home failed", t)
            dragReturning = false
            placeAfterDrag(view)
        }
    }

    /** Anything that asked to move or resize this surface during the drag was deferred until now. */
    private fun placeAfterDrag(view: PillView) {
        handler.post {
            try {
                when (view) {
                    lockPill -> applyLockTuning()
                    popupPill -> applyPopupTuning()
                }
            } catch (t: Throwable) {
                Log.w("post-drag placement failed", t)
            }
        }
    }

    /**
     * Follows the status bar itself: the pill is drawn in the same strip as the notification
     * icons, so a fullscreen app that hides the bar hides the pill too, and both come back
     * together. The state comes from the SystemUI hook, which can see the bar's own visibility.
     */
    private fun syncStatusBarVisibility() {
        try {
            val hidden = readStatusBarHidden()
            if (hidden == sbHidden) return
            sbHidden = hidden
            Log.i("status bar ${if (hidden) "hidden" else "visible"}: pill follows")
            if (hidden) {
                disarmAutoClose()
                removePopup()
                leaveSbPillWithBar()
            } else if (lastLocked == false) {
                sbLeavingWithBar = false
                addSbPill(slideIn = true)
            }
        } catch (t: Throwable) {
            Log.w("status bar visibility sync failed", t)
        }
    }

    /**
     * Leaves the way the bar does: the pill rises with it and fades. Removing the window outright
     * made it look like the pill had fallen off while the bar was still animating away - the strip
     * slid up and the pill was just gone.
     */
    private fun leaveSbPillWithBar() {
        val view = sbPill ?: return
        val lp = sbParams ?: return
        if (sbLeavingWithBar) return
        sbLeavingWithBar = true
        try {
            cancelAnimator(1)
            sbWidthAnimator?.cancel()
            sbWidthAnimator = null
            val fromY = lp.y
            val fromAlpha = lp.alpha.takeIf { it in 0.01f..1f } ?: 1f
            val travel = lp.height.toFloat()
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SB_BAR_MS
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    if (sbPill !== view || sbParams !== lp) return@addUpdateListener
                    val f = it.animatedValue as Float
                    lp.y = (fromY - travel * f).toInt()
                    lp.alpha = (fromAlpha * (1f - f)).coerceIn(0f, 1f)
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t: Throwable) {
                        Log.w("sb bar frame failed", t)
                    }
                }
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setAnimator(1, null)
                    sbLeavingWithBar = false
                    if (sbPill !== view || sbParams !== lp) return
                    // Parked, not removed: the window has to stay attached to keep receiving the
                    // insets that tell us when the bar is back.
                    try {
                        removeSbPill()
                        Log.i("sb pill left with the status bar")
                    } catch (t: Throwable) {
                        Log.w("sb bar leave end failed", t)
                        removeSbPill()
                    }
                }
            })
            setAnimator(1, anim)
            anim.start()
        } catch (t: Throwable) {
            sbLeavingWithBar = false
            Log.w("sb bar leave failed, removing outright", t)
            removeSbPill()
        }
    }

    /** Comes back down with the bar: the reverse of [leaveSbPillWithBar], from above the top edge. */
    private fun animateSbReturn(view: PillView, lp: WindowManager.LayoutParams) {
        cancelAnimator(1)
        val targetY = sbPillY()
        val fromY = (targetY - lp.height).coerceAtLeast(-lp.height)
        lp.y = fromY
        lp.alpha = 0f
        windowManager.updateViewLayout(view, lp)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SB_BAR_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                if (sbPill !== view || sbParams !== lp) return@addUpdateListener
                val f = it.animatedValue as Float
                lp.y = (fromY + (targetY - fromY) * f).toInt()
                lp.alpha = f.coerceIn(0f, 1f)
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (t: Throwable) {
                    Log.w("sb bar return frame failed", t)
                }
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                setAnimator(1, null)
                if (sbPill !== view || sbParams !== lp) return
                lp.y = targetY
                lp.alpha = 1f
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (t: Throwable) {
                    Log.w("sb bar return end failed", t)
                }
            }
        })
        setAnimator(1, anim)
        anim.start()
    }

    /**
     * Reads the hook's "status bar is hidden" flag. A stale hook (SystemUI restarted without it)
     * is ignored, so a leftover 1 can never leave the pill switched off for good.
     */
    /**
     * The other half of the push: SystemUI writes the bar's state the moment the platform tells it,
     * and this fires for that write. No polling anywhere on the fast path - the pill reacts in the
     * same frame budget as the bar itself.
     */
    private var statusBarObserver: ContentObserver? = null

    private fun registerStatusBarObserver() {
        if (statusBarObserver != null) return
        try {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    syncStatusBarVisibility()
                    // Shade open/closed, and the place the pill takes there: the same push, so
                    // this happens with the shade animation rather than after it.
                    syncShadeSurfaces()
                    // A new screen size means a rotation, and every window has to be placed again:
                    // the hook publishes it, so this works even with the shade open, where this
                    // process's own display metrics do not update.
                    replaceAllWindows()
                    // The hook also pushes the cellular level the inverse pill draws.
                    updateStatusGlyphs()
                }
            }
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(NowBarHook.KEY_SB_HIDDEN),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(NowBarHook.KEY_SHADE_OPEN),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(NowBarHook.KEY_SCREEN_W),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(NowBarHook.KEY_SCREEN_H),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(NowBarHook.KEY_CLOCK_RIGHT),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(NowBarHook.KEY_CELL_LEVEL),
                false,
                observer
            )
            statusBarObserver = observer
            // Catch up on anything that changed while this service was away.
            syncStatusBarVisibility()
            Log.i("watching the status bar state (push)")
        } catch (t: Throwable) {
            Log.w("status bar observer not registered", t)
        }
    }

    private fun unregisterStatusBarObserver() {
        val observer = statusBarObserver ?: return
        statusBarObserver = null
        try {
            contentResolver.unregisterContentObserver(observer)
        } catch (t: Throwable) {
            Log.w("status bar observer not removed", t)
        }
    }

    /**
     * Re-reads the connection when it changes: joining Wi-Fi has to swap the pill's bars for the
     * Wi-Fi fan at once, not on the next content change that happens to come along.
     */
    private fun registerNetwork() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    updateStatusGlyphs()
                }

                override fun onLost(network: android.net.Network) {
                    updateStatusGlyphs()
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    capabilities: android.net.NetworkCapabilities
                ) {
                    updateStatusGlyphs()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
            Log.i("watching the connection for the pill glyphs")
        } catch (t: Throwable) {
            Log.w("network callback not registered", t)
        }
    }

    private fun unregisterNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (t: Throwable) {
            Log.w("network callback not removed", t)
        }
    }

    /**
     * The inverse pill draws the battery Android's icon used to. The sticky battery broadcast is
     * the same number that icon reads, so no permission or hook is involved.
     */
    private fun registerBattery() {
        if (batteryReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    if (pct != batteryPct) {
                        batteryPct = pct
                        updateStatusGlyphs()
                    }
                } catch (t: Throwable) {
                    Log.w("battery read failed", t)
                }
            }
        }
        try {
            registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiver = r
        } catch (t: Throwable) {
            Log.w("battery receiver not registered", t)
        }
    }

    private fun unregisterBattery() {
        val r = batteryReceiver ?: return
        batteryReceiver = null
        try {
            unregisterReceiver(r)
        } catch (t: Throwable) {
            Log.w("battery receiver not removed", t)
        }
    }

    /**
     * Pushes the inverse glyphs into the status pill. Called on a battery change, a signal change
     * and every content apply, so the icons follow the device instead of freezing on add.
     */
    private fun updateStatusGlyphs() {
        try {
            val t = Tuning.sb(this)
            sbPill?.setStatusGlyphs(
                t.inverse,
                batteryPct,
                cellLevel(),
                t.signalFirst,
                Networks.kind(this)
            )
        } catch (t: Throwable) {
            Log.w("status glyph update failed", t)
        }
    }

    /**
     * The cellular level the hook read from SystemUI, or -1 while the hook is stale. A stale value
     * means the icons were restored by SystemUI's restart, so empty bars are the honest answer.
     */
    private fun cellLevel(): Int = try {
        val beat = Settings.Global.getLong(contentResolver, NowBarHook.KEY_HOOK_BEAT, 0L)
        val fresh = beat > 0L &&
            abs(SystemClock.elapsedRealtime() - beat) < HOOK_STALE_MS
        if (!fresh) {
            -1
        } else {
            Settings.Global.getInt(contentResolver, NowBarHook.KEY_CELL_LEVEL, -1)
                .coerceIn(-1, 4)
        }
    } catch (t: Throwable) {
        -1
    }

    private fun readStatusBarHidden(): Boolean {
        return try {
            // The hook's value, which the system pushes into SystemUI the moment the bar changes.
            // A stale value (SystemUI restarted without the hook) is ignored, so a leftover 1 can
            // never leave the pill switched off for good.
            val beat = Settings.Global.getLong(contentResolver, NowBarHook.KEY_HOOK_BEAT, 0L)
            val fresh = beat > 0L &&
                abs(SystemClock.elapsedRealtime() - beat) < HOOK_STALE_MS
            fresh && Settings.Global.getInt(contentResolver, NowBarHook.KEY_SB_HIDDEN, 0) == 1
        } catch (t: Throwable) {
            if (!sbHiddenWarned) {
                sbHiddenWarned = true
                Log.w("status bar state unreadable, assuming visible", t)
            }
            false
        }
    }

    private fun statusBarTopPx(): Int = try {
        windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            .top
    } catch (t: Throwable) {
        Log.w("status bar inset unreadable", t)
        dp(48)
    }

    private fun cutoutRightPx(): Int = try {
        windowManager.currentWindowMetrics.windowInsets.displayCutout
            ?.boundingRects
            ?.filter { it.top <= 0 }
            ?.maxOfOrNull { it.right } ?: -1
    } catch (t: Throwable) {
        Log.w("cutout bounds unreadable", t)
        -1
    }

    /**
     * The real display size, read from the display itself.
     *
     * `windowManager.currentWindowMetrics` was the obvious source, but for an accessibility
     * service that window manager is bound when the service starts and its metrics do not follow a
     * rotation - so after turning the phone, `isLandscape()` still answered "landscape" and the pill
     * was placed for the old orientation. A service also does not reliably get
     * `onConfigurationChanged`, so nothing corrected it. The display's own metrics do update.
     */
    private fun screenSizePx(): android.graphics.Point {
        // SystemUI's own numbers first: this process keeps its display metrics from when it started
        // and does not update them on rotation, which is what left the pill laid out for the
        // previous orientation.
        val hooked = hookedScreenSize()
        if (hooked != null && hookedOrientationMatches(hooked)) return hooked
        return try {
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                android.graphics.Point(metrics.widthPixels, metrics.heightPixels)
            } else {
                hooked ?: fallbackSize()
            }
        } catch (t: Throwable) {
            Log.w("screen size unreadable", t)
            hooked ?: fallbackSize()
        }
    }

    private fun hookedScreenSize(): android.graphics.Point? = try {
        val w = Settings.Global.getInt(contentResolver, NowBarHook.KEY_SCREEN_W, 0)
        val h = Settings.Global.getInt(contentResolver, NowBarHook.KEY_SCREEN_H, 0)
        if (w > 0 && h > 0) android.graphics.Point(w, h) else null
    } catch (t: Throwable) {
        Log.w("hooked screen size unreadable", t)
        null
    }

    /**
     * Whether the size SystemUI published belongs to the way the screen is actually turned.
     *
     * SystemUI starts before its configuration settles, so on boot it can publish a landscape size
     * while the phone is upright. Trusting that placed the pill centred for a 2392-wide screen on a
     * 1080-wide one - i.e. off the right edge, which is exactly how it "moved". The rotation is read
     * from the display itself, so a stale size is caught before it is used.
     */
    private fun hookedOrientationMatches(size: android.graphics.Point): Boolean {
        return try {
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return true
            if (!display.isValid) return true
            val landscape = when (display.rotation) {
                android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 -> true
                else -> false
            }
            if (landscape != (size.x > size.y)) {
                Log.w("hooked screen ${size.x}x${size.y} disagrees with rotation ${display.rotation}")
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            true
        }
    }

    private fun fallbackSize(): android.graphics.Point = try {
        val b = windowManager.currentWindowMetrics.bounds
        android.graphics.Point(b.width(), b.height())
    } catch (t: Throwable) {
        android.graphics.Point(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
    }

    private fun screenWidthPx(): Int = screenSizePx().x

    /** Window y for a lock pill of [heightPx] whose bottom edge sits [bottomDp] above the bottom. */
    private fun lockTopY(bottomDp: Int, heightPx: Int): Int =
        screenHeightPx() - dp(bottomDp) - heightPx

    private fun screenHeightPx(): Int = screenSizePx().y

    private fun applyAll() {
        // Before the master check: the watcher switch has to work with the pill switched off too.
        syncRootWatcher()
        syncHookInverse()
        syncStatusBarVisibility()
        if (!TuningStore.isOn(this, TuningStore.MASTER)) {
            removeAllSurfaces()
            return
        }
        if (shadeOpen()) {
            syncShadeSurfaces()
            return
        }
        // A surface switched off while it was on screen has to leave now, and one switched back
        // on has to appear without waiting for the next lock/unlock cycle. When the state already
        // matches, both branches are no-ops, so dragging a slider costs nothing.
        if (!enabled(TuningStore.SURFACE_LOCK)) {
            removeLockPill()
        } else if (lockPill == null && lastLocked == true) {
            addLockPill(fadeIn = true)
        }
        if (!enabled(TuningStore.SURFACE_STATUS)) {
            removeSbPill()
        } else if (sbPill == null && lastLocked == false) {
            addSbPill(fadeIn = true)
        }
        if (!enabled(TuningStore.SURFACE_POPUP)) removePopup()
        applyLockTuning()
        applySbTuning()
        applyPopupTuning()
        applyContent()
    }

    /**
     * Pushes the inverse layout to SystemUI. The placement itself comes from the prefs, but the
     * hook owns hiding Android's icon cluster (and the privacy dot), so after a prefs rewrite the
     * two have to agree without waiting for the settings toggle.
     */
    private fun syncHookInverse() {
        try {
            val want = if (TuningStore.sbInverse(this)) 1 else 0
            val have = Settings.Global.getInt(contentResolver, NowBarHook.KEY_INVERSE, -1)
            if (have == want) return
            Thread {
                val out = RootShell.run("settings put global ${NowBarHook.KEY_INVERSE} $want")
                if (out == null) Log.w("hook inverse sync: no root, flag left at $have")
            }.start()
            Log.i("hook: ${NowBarHook.KEY_INVERSE} -> $want (was $have)")
        } catch (t: Throwable) {
            Log.w("hook inverse sync failed", t)
        }
    }

    /** Master switch and per-surface switch, read fresh every time a window is about to appear. */
    private fun enabled(surface: String): Boolean =
        TuningStore.isOn(this, TuningStore.MASTER) && TuningStore.isOn(this, surface)

    /**
     * The lock screen and the popup only exist while something is active - except in measure
     * mode, where the empty shape is exactly what is being looked at.
     */
    private fun emptyForLock(): Boolean =
        NowBarState.items(this).isEmpty() && !TuningStore.outline(this)

    /** Pushes the current item into whichever pills exist; each surface decides what it shows. */
    private fun applyContent() {
        // addLockPill/addSbPill call back into here once the window exists, so the outer call has
        // to be the one that keeps going.
        if (applyingContent) return
        applyingContent = true
        try {
            val items = NowBarState.items(this)
            if (items.isEmpty()) {
                // Nothing active: the lock pill and the popup have nothing to draw and go away.
                // The status bar pill keeps the placeholder label, and only disappears when that
                // label is blank - there is no separate idle switch to leave in the wrong state.
                if (TuningStore.outline(this)) {
                    // Measure mode: the shapes stay up so every edge can be seen while it is
                    // dialled in. The lockscreen one only belongs while the keyguard is up.
                    if (lastLocked != true) removeLockPill()
                    lockPill?.setItems(emptyList(), false, 0)
                    popupPill?.setItems(emptyList(), false, 0)
                } else {
                    removeLockPill()
                    removePopup()
                }
                // The status pill stays on the strip for as long as the phone is unlocked: with
                // nothing active it is the idle line, or just its own battery / signal glyphs.
                val keepSb = enabled(TuningStore.SURFACE_STATUS) && lastLocked != true
                if (!keepSb) removeSbPill() else sbPill?.let { view ->
                    view.setItems(emptyList(), false, 0)
                    // Back to the idle line: the pill has to shrink with it, otherwise it keeps
                    // the width of the card that just went away.
                    fitSbWidth(view)
                }
                return
            }
            // An activity can start while a surface is not drawn yet - a timer on an idle status
            // bar, or after the lock pill was removed for having nothing to show. This is the only
            // path that runs on every item change, so it has to be able to create the window too,
            // not just fill one that already exists (that needed "Apply settings" before).
            ensureSurface()
            val peek = dp(TuningStore.stackPeekDp(this))

            lockPill?.let { view ->
                // The animator owns both the size and the moment the expanded layout flips back to
                // one line. Re-filling the card mid-animation (this runs every second for a running
                // timer) put the single-line layout inside a still-shrinking card - the stutter.
                if (lockAnimator?.isRunning != true) view.setItems(ordered(), lockExpanded, peek)
                // The item count decides the window height, so grow/shrink it when the stack
                // changes - but never mid-expansion, where the animator owns the size.
                val t = Tuning.current(this)
                val target = lockTargetSize(t, lockExpanded)
                val lp = lockParams
                if (lp != null && lockAnimator?.isRunning != true && !dragActive &&
                    (lp.height != target.h || lp.width != target.w)
                ) {
                    lp.width = target.w
                    lp.height = target.h
                    lp.y = lockTopY(t.bottomDp, lockCardHeight(t, lockExpanded))
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t2: Throwable) {
                        Log.w("lock stack resize failed", t2)
                    }
                }
            }

            // The status bar pill is one line, no control, no stack - but it follows the same
            // selected card, so swiping anywhere on the pill changes what the status bar shows.
            sbPill?.let { view ->
                updateStatusGlyphs()
                view.setItems(ordered().take(1), false, 0)
                // Shrink to the line instead of leaving empty card to the right of the text.
                fitSbWidth(view)
            }

            popupPill?.let { view ->
                view.setItems(ordered(), true, peek)
                val target = popupTargetRect(view)
                val lp = popupParams
                if (lp != null && popupAnimator?.isRunning != true && !dragActive &&
                    (lp.height != target.h || lp.width != target.w)
                ) {
                    // The next card needs a different height (two lines instead of one, or a
                    // scrubber): flow into it rather than snapping.
                    animatePopupRect(
                        view,
                        lp,
                        Rect4(lp.x, lp.y, lp.width, lp.height),
                        target,
                        DecelerateInterpolator(1.6f),
                        180L,
                        onEnd = null
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("applyContent failed", t)
        } finally {
            applyingContent = false
        }
    }

    /**
     * Adds the surface that belongs to the current lock state when it is missing. Keyed off
     * [lastLocked], which the trusted unlock/lock paths set, so this never fights a handover in
     * flight (while [transitioning] both surfaces are being moved on purpose).
     */
    private fun ensureSurface() {
        if (transitioning) return
        when (lastLocked) {
            true -> if (lockPill == null) addLockPill(fadeIn = true)
            false -> if (sbPill == null) addSbPill(fadeIn = true)
            null -> Unit
        }
    }

    /**
     * How many of this service's own windows are actually on screen. The settings screen runs in
     * the same process, so it reads this directly instead of guessing from a flag.
     */
    fun windowsUp(): Int = try {
        var n = 0
        if (lockPill?.isAttachedToWindow == true) n++
        if (sbPill?.isAttachedToWindow == true) n++
        if (popupPill?.isAttachedToWindow == true) n++
        n
    } catch (t: Throwable) {
        0
    }

    /** Every item in ranking order, rotated so the swiped-to card leads. */
    private fun ordered(): List<NowBarItem> {
        val items = NowBarState.items(this)
        val key = frontKey ?: return items
        val i = items.indexOfFirst { it.id == key }
        if (i <= 0) return items
        return items.drop(i) + items.take(i)
    }

    private fun itemsFor(): List<NowBarItem> = ordered()

    /** Runs the slide transition on every surface that is on screen. */
    private fun animateSwap(dy: Int) {
        try {
            lockPill?.animateSwap(dy)
            popupPill?.animateSwap(dy)
            sbPill?.animateSwap(dy)
        } catch (t: Throwable) {
            Log.w("swap animation failed", t)
        }
    }

    /**
     * Left/right skips in the player, but only when the card in front is the media card.
     * Up/down moves to the next or previous card and makes it the front card everywhere,
     * including the status bar pill.
     */
    private fun handleSwipe(lockSurface: Boolean, dx: Int, dy: Int) {
        try {
            val items = itemsFor()
            val front = items.firstOrNull() ?: return
            armAutoClose()
            if (dx != 0) {
                if (front.kind != ItemKind.MEDIA) return
                // Swiping right goes back, the same way the rest of the system reads a rightward
                // drag on media. It used to skip forward, which felt reversed.
                if (dx > 0) MediaActions.previous(this, front.token)
                else MediaActions.next(this, front.token)
                return
            }
            if (dy == 0 || items.size < 2) return
            val next = ((items.indexOf(front) + dy) % items.size + items.size) % items.size
            frontKey = items[next].id
            applyContent()
            animateSwap(dy)
            Log.i(
                "swipe on ${if (lockSurface) "lock" else "popup"}: front card is now " +
                    "'${items[next].title}'"
            )
        } catch (t: Throwable) {
            Log.w("handleSwipe failed", t)
        }
    }

    /** Seek slider released on an expanded media card: one seek, then the card stays open. */
    private fun seekFront(fraction: Float) {
        try {
            armAutoClose()
            val item = itemsFor().firstOrNull() ?: return
            if (item.kind != ItemKind.MEDIA || item.durationMs <= 0L) return
            val target = (item.durationMs * fraction.coerceIn(0f, 1f)).toLong()
            MediaActions.seek(this, item.token, target)
            // Hold the new position until the player reports it, otherwise the next tick restores
            // the old snapshot and the thumb jumps back to where it was.
            NowBarState.holdMedia(item.id, target, null, holdMs = 2_000L)
            applyContent()
            Log.i("seek '${item.title}' to ${target}ms of ${item.durationMs}ms")
        } catch (t: Throwable) {
            Log.w("seek failed", t)
        }
    }

    private fun handleControl(lockSurface: Boolean, action: String) {
        try {
            armAutoClose()
            val item = itemsFor().firstOrNull() ?: return
            // "action:n" is one of the source notification's own buttons: sending its
            // PendingIntent makes the app do the work, so the pill needs no permission.
            if (action.startsWith("action:")) {
                val index = action.removePrefix("action:").toIntOrNull() ?: return
                val pending = item.actions.getOrNull(index)?.pendingIntent
                if (pending == null) {
                    Log.w("control $action has no action on '${item.title}'")
                    return
                }
                pending.send()
                Log.i("sent '${item.actions[index].label}' for '${item.title}'")
                return
            }
            when (action) {
                "prev" -> MediaActions.previous(this, item.token)
                "next" -> MediaActions.next(this, item.token)
                else -> {
                    MediaActions.playPause(this, item.token)
                    // Flip the glyph now instead of waiting up to a second for the player's next
                    // state post, which made a working pause look like a dead button.
                    NowBarState.holdMedia(item.id, null, !item.playing)
                }
            }
            applyContent()
        } catch (t: Throwable) {
            Log.w("handleControl failed", t)
        }
    }

    /** Tapping an expanded card opens the app the item came from. */
    private fun openCurrentApp(lockSurface: Boolean) {
        try {
            val item = itemsFor().firstOrNull() ?: return
            val pkg = item.packageName ?: return
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            Log.i("opened $pkg from the pill")
        } catch (t: Throwable) {
            Log.w("open app failed", t)
        }
    }

    private fun removeAllSurfaces() {
        try {
            removePopup()
            removeLockPill()
            removeSbPill()
        } catch (t: Throwable) {
            Log.w("removeAllSurfaces failed", t)
        }
    }

    /**
     * The settings screen's "Apply settings" button: re-read every preference and re-apply it
     * immediately, without waiting for a card, a lock cycle or a service restart.
     */
    fun reloadNow() {
        try {
            handler.post {
                try {
                    applyAll()
                    Log.i("settings applied on request")
                } catch (t: Throwable) {
                    Log.w("reload failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.w("reloadNow failed", t)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
