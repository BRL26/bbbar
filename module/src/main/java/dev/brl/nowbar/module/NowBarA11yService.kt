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
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

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

        const val SLIDE_MS = 180L
        const val EXPAND_MS = 280L
        const val COLLAPSE_MS = 220L

        /** Failsafe poll while the phone is unlocked. */
        const val POLL_MS = 1000L

        /** While the keyguard is showing, poll fast so an unlock is caught within ~150 ms. */
        const val FAST_POLL_MS = 150L

        /** After the screen comes on, check hard for this long (fingerprint unlocks land here). */
        const val BURST_MS = 5000L

        /** Delay used during a burst: effectively "as fast as is sane". */
        const val BURST_POLL_MS = 60L

        /** While a root keyguard signal is pending confirmation, check this often (one frame). */
        const val CONFIRM_POLL_MS = 8L

        /** Give up on a root signal that never turns into a keyguard change after this long. */
        const val CONFIRM_MS = 1500L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var keyguard: KeyguardManager

    private var lockPill: PillView? = null
    private var lockParams: WindowManager.LayoutParams? = null
    private var lockExpanded = false
    private var lockAnimator: ValueAnimator? = null

    private var sbPill: PillView? = null
    private var sbParams: WindowManager.LayoutParams? = null

    private var popupPill: PillView? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var popupAnimator: ValueAnimator? = null

    private var receiver: BroadcastReceiver? = null
    private var keyguardListener: KeyguardManager.KeyguardLockedStateListener? = null
    private var transitioning = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastLocked: Boolean? = null

    /** uptimeMillis until which the poll runs at BURST_POLL_MS. */
    private var burstUntil = 0L

    /** uptimeMillis until which a root keyguard signal is being confirmed at CONFIRM_POLL_MS. */
    private var confirmUntil = 0L

    /** uptimeMillis of the first root signal in the current burst, for latency reporting. */
    private var signalBurstStart = 0L

    /** uptimeMillis of the last root line, so a burst is logged once instead of thirty times. */
    private var lastSignalAt = 0L

    private val keyguardPoll = object : Runnable {
        override fun run() {
            var delay = POLL_MS
            try {
                val locked = isKeyguardLocked()
                if (lastLocked != locked) {
                    Log.i("poll caught the lock change (locked=$locked)")
                    updateSurfaces()
                }
                val now = SystemClock.uptimeMillis()
                delay = when {
                    now < burstUntil -> BURST_POLL_MS
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
            updateSurfaces()
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
        } catch (t: Throwable) {
        }
        if (NowBarBridge.service === this) NowBarBridge.service = null
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
    private fun startRootWatcher() {
        try {
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
    fun onUserPresent() {
        try {
            if (isKeyguardLocked()) {
                Log.i("USER_PRESENT but keyguard still locked, ignoring")
                return
            }
            if (lastLocked == false && sbPill != null) return
            lastLocked = false
            updateSurfaces()
        } catch (t: Throwable) {
            Log.w("onUserPresent failed", t)
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
            val locked = isKeyguardLocked()
            lastLocked = locked
            Log.i("surfaces: keyguardLocked=$locked")
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

    private fun isKeyguardLocked(): Boolean = try {
        keyguard.isKeyguardLocked
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
                        else -> {
                            if (action == Intent.ACTION_SCREEN_ON) armBurst()
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
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
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
        try {
            removePopup()
            lastLocked = true
            // The lock pill goes in first, so it is already there while the status pill leaves.
            if (lockPill == null) addLockPill(slideIn = false)
            val pill = sbPill
            val lp = sbParams
            if (pill == null || lp == null) return
            transitioning = true
            animateY(pill, lp, lp.y, -lp.height, SLIDE_MS, PathInterpolator(0.2f, 0f, 0f, 1f)) {
                removeSbPill()
                transitioning = false
                Log.i("screen off: status pill slid away, lock pill already in place")
            }
            Log.i("screen off: status pill sliding up (y ${lp.y} -> ${-lp.height})")
        } catch (t: Throwable) {
            Log.w("onScreenOff failed", t)
            transitioning = false
        }
    }

    private fun slideLockPillOut() {
        val lock = lockPill
        val lp = lockParams
        if (lock == null || lp == null) {
            if (sbPill == null) addSbPill(slideIn = true)
            return
        }
        try {
            transitioning = true
            // No gravity change here: the window has been TOP gravity since it was added, so the
            // slide is a pure y animation and nothing relayouts mid-flight.
            val topY = lp.y
            animateY(lock, lp, topY, -lp.height, SLIDE_MS, PathInterpolator(0.2f, 0f, 0f, 1f)) {
                removeLockPill()
                transitioning = false
                addSbPill(slideIn = true)
                Log.i("keyguard pill slid out, status pill sliding in")
            }
            Log.i("lock pill sliding up (y $topY -> ${-lp.height})")
        } catch (t: Throwable) {
            Log.w("transitionToUnlocked failed", t)
            transitioning = false
            removeLockPill()
            addSbPill(slideIn = true)
        }
    }

    /** Lock: the status pill slides up off the top, the keyguard pill slides in from the bottom. */
    private fun transitionToLocked() {
        val pill = sbPill
        val lp = sbParams
        if (pill == null || lp == null) {
            if (lockPill == null) addLockPill(slideIn = true)
            return
        }
        try {
            transitioning = true
            val startY = lp.y
            animateY(pill, lp, startY, -lp.height, SLIDE_MS, PathInterpolator(0.2f, 0f, 0f, 1f)) {
                removeSbPill()
                transitioning = false
                addLockPill(slideIn = true)
                Log.i("status pill slid out, keyguard pill sliding in")
            }
            Log.i("status pill sliding up (y $startY -> ${-lp.height})")
        } catch (t: Throwable) {
            Log.w("transitionToLocked failed", t)
            transitioning = false
            removeSbPill()
            addLockPill(slideIn = true)
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
        lockAnimator?.cancel()
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
                    onEnd?.invoke()
                }
            })
        }
        lockAnimator = anim
        anim.start()
    }

    // ----------------------------------------------------------- lock screen

    private fun addLockPill(slideIn: Boolean = false) {
        try {
            if (lockPill != null) return
            val tuning = Tuning.current(this)
            val view = PillView(this).apply {
                // Collapsed: a tap opens the expansion. Expanded: taps belong to the controls.
                setOnClickListener { if (!lockExpanded) toggleLockExpand() }
            }
            val w = dp(tuning.widthDp)
            val h = dp(tuning.heightDp)
            val targetY = lockTopY(tuning.bottomDp, h)
            // TOP gravity from the very first frame. Flipping gravity mid-animation makes
            // WindowManager recompute the window frame, which is what tore the pill in half
            // when unlocking out of the AOD.
            val lp = WindowManager.LayoutParams(
                w,
                h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                overlayFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = if (slideIn) screenHeightPx() else targetY
            }
            windowManager.addView(view, lp)
            lockPill = view
            lockParams = lp
            lockExpanded = false
            view.setStyle(tuning)
            if (slideIn) {
                animateY(view, lp, lp.y, targetY, SLIDE_MS, DecelerateInterpolator(1.6f), null)
                Log.i("lock pill sliding in from the bottom")
            } else {
                lp.y = targetY
                windowManager.updateViewLayout(view, lp)
            }
            Log.i("lock pill added: ${w}x${h}px at y=$targetY (top gravity)")
        } catch (t: Throwable) {
            Log.e("a11y addLockPill failed", t)
        }
    }

    private fun removeLockPill() {
        lockAnimator?.cancel()
        lockAnimator = null
        lockExpanded = false
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
            lp.y = lockTopY(t.bottomDp, target.h)
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
    private fun lockTargetSize(t: Tuning.Values, expanded: Boolean): Size {
        val w = dp(t.widthDp)
        val h = dp(t.heightDp)
        if (!expanded) return Size(w, h)
        val maxW = (screenWidthPx() - dp(16)).coerceAtLeast(w)
        return Size(minOf((w * SCALE).toInt(), maxW), (h * SCALE).toInt())
    }

    /** Tap: grow to 2.5x, tap again (or tap anywhere else) to settle back. */
    private fun toggleLockExpand() {
        val view = lockPill ?: return
        val lp = lockParams ?: return
        try {
            val t = Tuning.current(this)
            val expanding = !lockExpanded
            lockExpanded = expanding
            val target = lockTargetSize(t, expanding)
            lockAnimator?.cancel()
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                val fromW = lp.width
                val fromH = lp.height
                duration = EXPAND_MS
                interpolator = if (expanding) {
                    OvershootInterpolator(1.2f)
                } else {
                    AccelerateDecelerateInterpolator()
                }
                addUpdateListener {
                    val f = it.animatedValue as Float
                    val w = (fromW + (target.w - fromW) * f).toInt()
                    val h = (fromH + (target.h - fromH) * f).toInt()
                    lp.width = w
                    lp.height = h
                    // With TOP gravity the bottom edge has to be held manually while it grows.
                    lp.y = lockTopY(t.bottomDp, h)
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (t2: Throwable) {
                    }
                }
            }
            lockAnimator = anim
            anim.start()
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

    private fun addSbPill(slideIn: Boolean = false) {
        try {
            if (sbPill != null) return
            val t = Tuning.sb(this)
            val view = PillView(this).apply {
                setOnClickListener { expandFromSbPill() }
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
            view.setStyle(t.label, t.textDp, t.radiusDp)
            windowManager.addView(view, lp)
            sbPill = view
            sbParams = lp
            if (slideIn) {
                animateY(view, lp, -lp.height, targetY, SLIDE_MS, DecelerateInterpolator(1.6f), null)
            }
            Log.i(
                "sb pill added: ${t.widthDp}x${t.heightDp}dp at x=${lp.x} y=$targetY " +
                    "(slideIn=$slideIn statusBarTop=${statusBarTopPx()}px cutoutRight=${cutoutRightPx()}px)"
            )
        } catch (t: Throwable) {
            Log.e("sb pill add failed", t)
        }
    }

    private fun removeSbPill() {
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
            placeSb(lp, t)
            view.setStyle(t.label, t.textDp, t.radiusDp)
            windowManager.updateViewLayout(view, lp)
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

    private fun popupTargetRect(): Rect4 {
        val p = Tuning.popup(this)
        val w = dp(p.widthDp)
        val h = dp(p.heightDp)
        val x = if (p.leftDp >= 0) dp(p.leftDp) else ((screenWidthPx() - w) / 2).coerceAtLeast(0)
        val y = if (p.topDp >= 0) dp(p.topDp) else statusBarTopPx() + dp(POPUP_GAP_DP)
        return Rect4(x, y, w, h)
    }

    /**
     * Opens the popup from the mini pill's own rect and animates it to its full size, so it
     * looks like the pill itself sized up; the mini pill stays visible underneath.
     */
    private fun expandFromSbPill() {
        try {
            if (popupPill != null) return
            val pop = Tuning.popup(this)
            val start = pillRect()
            val target = popupTargetRect()

            val view = PillView(this).apply {
                setStyle(pop.label, pop.textDp, pop.radiusDp)
                // Taps inside the popup are for its controls; an outside tap closes it.
                onOutsideTouch = { collapseToSbPill() }
            }
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
            animatePopupRect(view, lp, start, target, OvershootInterpolator(1.08f), EXPAND_MS, null)
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
        try {
            val start = Rect4(lp.x, lp.y, lp.width, lp.height)
            val pill = pillRect()
            animatePopupRect(view, lp, start, pill, AccelerateDecelerateInterpolator(), COLLAPSE_MS) {
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
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (t: Throwable) {
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
        }
        popupAnimator = anim
        anim.start()
    }

    // ----------------------------------------------------------------- misc

    private fun overlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

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
        applyLockTuning()
        applySbTuning()
        applyPopupTuning()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
