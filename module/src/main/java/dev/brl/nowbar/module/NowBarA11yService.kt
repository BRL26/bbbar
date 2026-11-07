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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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

    private var sbPill: PillView? = null
    private var sbParams: WindowManager.LayoutParams? = null
    private var sbAnimator: ValueAnimator? = null

    /** The width morph, kept apart from [sbAnimator] (which slides/fades the whole window). */
    private var sbWidthAnimator: ValueAnimator? = null

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
                    val unlockedConfirmed = !locked && unlockStreak >= 8
                    if (lastLocked != locked && (locked || unlockedConfirmed)) {
                        Log.i("poll caught the lock change (locked=$locked, unlockStreak=$unlockStreak)")
                        updateSurfaces()
                    }
                }
                // The pill shares its strip with the notification icons: when SystemUI hides the
                // status bar for a fullscreen app, the pill leaves with it and comes back after.
                if (!locked && isInteractive()) syncStatusBarVisibility()
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
        applyAll()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            windowManager = getSystemService(WindowManager::class.java)
            keyguard = getSystemService(KeyguardManager::class.java)
            prefs = TuningStore.prefs(this)
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            registerKeyguardReceiver()
            registerLockStateListener()
            startRootWatcher()
            lastLocked = isKeyguardLocked()
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
            val locked = isKeyguardLocked()
            if (lastLocked != locked) {
                Log.i("a11y event caught the lock change (locked=$locked)")
                lastLocked = locked
                updateSurfaces()
            }
        } catch (t: Throwable) {
            Log.w("a11y event handling failed", t)
        }
    }

    override fun onInterrupt() {}

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
                                // Off the AOD and back to a live screen: colour returns quickly.
                                lockPill?.setGray(0f, WAKE_FADE_MS)
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
            if (lockPill == null) addLockPill(fadeIn = true)
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
        val wantLock = locked && enabled(TuningStore.SURFACE_LOCK) && items.isNotEmpty()
        // The status bar pill also stands in for the idle line, which only a blank label removes.
        val wantSb = !locked && enabled(TuningStore.SURFACE_STATUS) &&
            (items.isNotEmpty() || TuningStore.showIdle(this))
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
            if (!enabled(TuningStore.SURFACE_LOCK)) return
            if (emptyForLock()) return
            val tuning = Tuning.current(this)
            val view = PillView(this).apply {
                // Collapsed: a tap opens the expansion. Expanded: a tap opens the source app.
                onTap = { if (lockExpanded) openCurrentApp(true) else toggleLockExpand() }
                onSwipe = { dx, dy -> handleSwipe(true, dx, dy) }
                onControl = { action -> handleControl(true, action) }
                onSeek = { fraction -> seekFront(fraction) }
            }
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
            lockPill = view
            lockParams = lp
            lockExpanded = false
            disarmAutoClose()
            // A pill that appears while the display is off is part of the AOD, so it starts grey.
            view.setGray(
                if (!isInteractive() && TuningStore.isOn(this, TuningStore.AOD_GRAY)) 1f else 0f,
                0L
            )
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

    private fun applyLockTuning() {
        val view = lockPill ?: return
        val lp = lockParams ?: return
        try {
            val t = Tuning.current(this)
            val target = lockTargetSize(t, lockExpanded)
            lp.width = target.w
            lp.height = target.h
            lp.y = lockTopY(t.bottomDp, lockCardHeight(t, lockExpanded))
            view.setStyle(t)
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
            if (sbPill != null) return
            // Read fresh, not cached: during an unlock the cached value can still describe the
            // AOD, where the bar is hidden, and that would swallow the handover.
            sbHidden = readStatusBarHidden()
            if (sbHidden) {
                Log.i("sb pill skipped: the status bar is hidden")
                return
            }
            if (!enabled(TuningStore.SURFACE_STATUS)) return
            if (emptyForStatus()) return
            val t = Tuning.sb(this)
            val view = PillView(this).apply {
                // The mini pill has no controls of its own: a tap is what opens the expansion.
                onTap = { expandFromSbPill() }
                onSwipe = { _, _ -> }
                onControl = { }
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
            val targetY = lp.y
            if (slideIn) lp.y = -lp.height
            // Alpha 0 goes into the LayoutParams *before* addView. Setting it afterwards makes the
            // window draw once at full alpha, disappear, then fade back in - the flicker.
            if (fadeIn) lp.alpha = 0f
            view.setStyle(t.label, t.textDp, t.radiusDp)
            // Content before the add, so the first frame is already the real item.
            view.setItems(NowBarState.items(this).take(1), false, 0)
            // A short line gets a short pill: the tuned width is the maximum, not a fixed size.
            lp.width = view.preferredWidth(dp(t.widthDp), lp.height)
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
            val wanted = view.preferredWidth(dp(Tuning.sb(this).widthDp), lp.height)
            if (lp.width == wanted && sbWidthAnimator?.isRunning != true) return
            // Several preferences can land in the same applyAll: an animation already heading for
            // this width is left alone instead of being restarted from its current frame.
            if (wanted == sbWidthTarget && sbWidthAnimator?.isRunning == true) return
            // A different line arrived mid-morph: retarget from the width currently on screen.
            sbWidthAnimator?.cancel()
            val from = lp.width
            sbWidthTarget = wanted
            Log.i("sb width morph: ${from}px -> ${wanted}px (max ${dp(Tuning.sb(this).widthDp)}px)")
            val anim = ValueAnimator.ofInt(from, wanted).apply {
                duration = WIDTH_MS
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    if (sbPill !== view || sbParams !== lp) return@addUpdateListener
                    lp.width = it.animatedValue as Int
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
        try {
            val t = Tuning.sb(this)
            // Style first, so the width is measured from the new label and text size, not the old.
            view.setStyle(t.label, t.textDp, t.radiusDp)
            val onScreen = lp.width
            placeSb(lp, t)
            // Position and height change at once, the width flows to its new value below. Keeping
            // the current width for that first frame is what stops the pill flashing wide when a
            // preference lands.
            lp.width = onScreen
            windowManager.updateViewLayout(view, lp)
            fitSbWidth(view)
            Log.i("sb pill updated: ${t.widthDp}x${t.heightDp}dp at x=${lp.x} y=${lp.y}px")
        } catch (t: Throwable) {
            Log.w("sb applyTuning failed", t)
        }
    }

    private fun placeSb(lp: WindowManager.LayoutParams, t: Tuning.Sb) {
        lp.width = dp(t.widthDp)
        lp.height = dp(t.heightDp)
        lp.x = if (t.leftDp >= 0) dp(t.leftDp) else defaultSbLeft(lp.width)
        lp.y = if (t.topDp >= 0) dp(t.topDp) else ((statusBarTopPx() - lp.height) / 2).coerceAtLeast(0)
    }

    private fun sbPillX(): Int {
        val t = Tuning.sb(this)
        return if (t.leftDp >= 0) dp(t.leftDp) else defaultSbLeft(dp(t.widthDp))
    }

    private fun sbPillY(): Int {
        val t = Tuning.sb(this)
        return if (t.topDp >= 0) dp(t.topDp) else ((statusBarTopPx() - dp(t.heightDp)) / 2).coerceAtLeast(0)
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
        return Rect4(sbPillX(), sbPillY(), dp(t.widthDp), dp(t.heightDp))
    }

    private fun popupTargetRect(view: PillView? = popupPill): Rect4 {
        val p = Tuning.popup(this)
        val w = dp(p.widthDp)
        val tuned = dp(p.heightDp)
        // The tuned height is a ceiling, not a target: a one-line card with no scrubber gets a
        // shorter popup instead of a band of empty card under it.
        val front = view?.preferredHeight(tuned) ?: tuned
        val h = front + peekExtra()
        // Portrait centres the popup. Landscape keeps it on the left, where the pill is, so the
        // expand/collapse animation grows out of the pill instead of flying across the screen.
        val landscape = screenWidthPx() > screenHeightPx()
        val x = when {
            p.leftDp >= 0 -> dp(p.leftDp)
            landscape -> dp(16)
            else -> ((screenWidthPx() - w) / 2).coerceAtLeast(0)
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
            popupPill = view
            popupParams = lp
            armAutoClose()
            animatePopupRect(view, lp, start, target, OvershootInterpolator(1.08f), EXPAND_MS, onEnd = null)
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
        try {
            val pop = Tuning.popup(this)
            val target = popupTargetRect()
            lp.x = target.x
            lp.y = target.y
            lp.width = target.w
            lp.height = target.h
            view.setStyle(pop.label, pop.textDp, pop.radiusDp)
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
                // clipped card would still show a sliver of itself.
                if (fadeTo != null) lp.alpha = 1f - f * (1f - fadeTo)
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
                removeSbPill()
            } else if (lastLocked == false) {
                addSbPill(fadeIn = true)
            }
        } catch (t: Throwable) {
            Log.w("status bar visibility sync failed", t)
        }
    }

    /**
     * Reads the hook's "status bar is hidden" flag. A stale hook (SystemUI restarted without it)
     * is ignored, so a leftover 1 can never leave the pill switched off for good.
     */
    private fun readStatusBarHidden(): Boolean {
        return try {
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

    private fun screenWidthPx(): Int = try {
        windowManager.currentWindowMetrics.bounds.width()
    } catch (t: Throwable) {
        Log.w("screen width unreadable", t)
        resources.displayMetrics.widthPixels
    }

    /** Window y for a lock pill of [heightPx] whose bottom edge sits [bottomDp] above the bottom. */
    private fun lockTopY(bottomDp: Int, heightPx: Int): Int =
        screenHeightPx() - dp(bottomDp) - heightPx

    private fun screenHeightPx(): Int = try {
        windowManager.currentWindowMetrics.bounds.height()
    } catch (t: Throwable) {
        Log.w("screen height unreadable", t)
        resources.displayMetrics.heightPixels
    }

    private fun applyAll() {
        // Before the master check: the watcher switch has to work with the pill switched off too.
        syncRootWatcher()
        syncStatusBarVisibility()
        if (!TuningStore.isOn(this, TuningStore.MASTER)) {
            removeAllSurfaces()
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

    /** Master switch and per-surface switch, read fresh every time a window is about to appear. */
    private fun enabled(surface: String): Boolean =
        TuningStore.isOn(this, TuningStore.MASTER) && TuningStore.isOn(this, surface)

    /** The lock screen and the popup only exist while something is active. */
    private fun emptyForLock(): Boolean = NowBarState.items(this).isEmpty()

    /**
     * Only the status bar pill has an idle case, and it is the label itself: an idle pill with a
     * blank label is not drawn. The lock pill and the popup are never placeholder surfaces.
     */
    private fun emptyForStatus(): Boolean =
        NowBarState.items(this).isEmpty() && !TuningStore.showIdle(this)

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
                removeLockPill()
                removePopup()
                if (!TuningStore.showIdle(this)) removeSbPill() else sbPill?.let { view ->
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
                if (lp != null && lockAnimator?.isRunning != true &&
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
                view.setItems(ordered().take(1), false, 0)
                // Shrink to the line instead of leaving empty card to the right of the text.
                fitSbWidth(view)
            }

            popupPill?.let { view ->
                view.setItems(ordered(), true, peek)
                val target = popupTargetRect(view)
                val lp = popupParams
                if (lp != null && popupAnimator?.isRunning != true &&
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
                else -> MediaActions.playPause(this, item.token)
            }
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
