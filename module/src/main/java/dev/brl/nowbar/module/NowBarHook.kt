package dev.brl.nowbar.module

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.IdentityHashMap

/**
 * SystemUI hook. One job only: hide the icons Android draws in the status bar, because bbbar's
 * own pill lives in that same strip and the two fight for it.
 *
 * It never lays anything out and never draws. It finds the status bar view that SystemUI inflates,
 * walks to the notification icon area, and sets `alpha` on the children it is asked to hide. That
 * keeps the strip's geometry untouched, so nothing shifts when a switch is flipped; and because it
 * only ever writes `alpha`, a failed lookup leaves the stock status bar exactly as it was.
 *
 * Which children are which is decided by class name at runtime, never by assumption:
 *   - notification icons  -> a child whose class contains `NotificationIcon`
 *   - live update chip    -> a child whose class contains `Chip` (or a Compose host)
 * The first time a child class shows up it is logged, so the log always says what was matched.
 *
 * Switches come from [Settings.Global], which SystemUI (system uid) can read and the app writes
 * through root:
 *
 *   settings put global bbbar_hide_notif_icons 1
 *   settings put global bbbar_hide_live_chips 1
 *
 * A file kill switch stays available for a bad day: `touch /data/local/tmp/bbbar_hook_off`.
 */
class NowBarHook : IXposedHookLoadPackage {

    companion object {
        const val KILL_SWITCH = "/data/local/tmp/bbbar_hook_off"
        private const val PACKAGE = "com.android.systemui"

        /** Hidden only while these are 1. Written by the settings app through root. */
        const val KEY_HIDE_ICONS = "bbbar_hide_notif_icons"
        const val KEY_HIDE_CHIPS = "bbbar_hide_live_chips"

        /**
         * 1 while the pill owns the right end of the strip (the inverse layout, issue #10). The
         * hook then hides Android's battery / network icon cluster and puts the notification icons
         * back, since the pill no longer sits on top of them.
         */
        const val KEY_INVERSE = "bbbar_inverse"

        /**
         * 1 while the pill takes the left end of the strip with the clock switched off, so the
         * clock is drawn in the pill instead. Portrait only: the landscape pill stays at the right
         * end, where the bar's own clock is not in its way.
         */
        const val KEY_HIDE_CLOCK = "bbbar_hide_clock"

        /**
         * Cellular signal level (0..4) as SystemUI reads it, or -1 when there is no reading. The
         * inverse pill draws the bars itself, so it needs the number the hidden icons used.
         */
        const val KEY_CELL_LEVEL = "bbbar_cell_level"

        /**
         * Heartbeat: `SystemClock.elapsedRealtime()` of the last proof that the hook is live in
         * SystemUI. The settings screen reads it, so "is the hook loaded" no longer depends on a
         * logcat line - a rotated or cleared log used to make a working hook look missing. Stale by
         * more than a couple of minutes means SystemUI restarted without the hook.
         */
        const val KEY_HOOK_BEAT = "bbbar_hook_beat"
        private const val BEAT_MS = 30_000L

        /** How often the Bluetooth audio state is read. Short, so a headset lands promptly. */
        private const val BT_POLL_MS = 4_000L

        /**
         * 1 while SystemUI has the status bar hidden - a fullscreen or immersive app. bbbar's pill
         * is drawn in that same strip, so the service hides it with the bar and brings it back
         * when the bar returns.
         */
        const val KEY_SB_HIDDEN = "bbbar_sb_hidden"

        /**
         * Right edge of the status bar's clock, in screen pixels, or 0 when the clock is not on
         * screen (the shade draws its own instead). The pill lives in the same strip, so it needs
         * to know where the clock actually ends - that is the difference between a pill that clears
         * the time in landscape and one that clips it.
         */
        const val KEY_CLOCK_RIGHT = "bbbar_clock_right_px"

        /**
         * The screen size as SystemUI sees it. An accessibility-service process keeps its own display
         * metrics from when it started, so after a rotation it still reports the old orientation -
         * SystemUI's configuration does update, so its answer is the one to trust.
         */
        const val KEY_SCREEN_W = "bbbar_screen_w"
        const val KEY_SCREEN_H = "bbbar_screen_h"

        /** How often the clock's edge is sampled. Writes only when it changes. */
        private const val CLOCK_VISIT_MS = 500L

        /** Candidate ids for the clock in the status bar, newest first. */
        private val CLOCK_ID_NAMES = listOf("clock", "status_bar_clock")

        /**
         * 1 while the shade or Quick Settings is open. Pushed the same way as the bar's visibility,
         * from the shade window's own state: that screen lays its header out differently, so the
         * pill can be given its own position there.
         */
        const val KEY_SHADE_OPEN = "bbbar_shade_open"

        /**
         * 1 while a Bluetooth audio device - earbuds, a headset - is connected. The pill draws its
         * own earbuds glyph, and the app cannot ask Bluetooth itself without a runtime permission,
         * so SystemUI answers here instead.
         */
        const val KEY_BT_AUDIO = "bbbar_bt_audio"

        /**
         * Verified on Nothing OS 5.0 (classes7.dex): `shade/NotificationShadeWindowControllerImpl`
         * has `apply(NotificationShadeWindowState)`, called on every state change, and that state
         * carries `shadeOrQsExpanded`.
         */
        private const val SHADE_CONTROLLER =
            "com.android.systemui.shade.NotificationShadeWindowControllerImpl"
        private const val SHADE_STATE = "com.android.systemui.shade.NotificationShadeWindowState"
        private const val SHADE_EXPANDED_FIELD = "shadeOrQsExpanded"

        /**
         * Fallback only: how often the bar view is sampled when the window-state push could not be
         * hooked. Slow, because this signal cannot see a bar faded out by the window manager.
         */
        private const val VIS_VISIT_MS = 500L

        /** `StatusBarManager.WINDOW_STATUS_BAR`: the window id this state is about. */
        private const val WINDOW_STATUS_BAR = 1

        /** `StatusBarManager.WINDOW_STATE_SHOWING`: everything else means on its way out. */
        private const val WINDOW_STATE_SHOWING = 0

        /**
         * The class that receives `CommandQueue.Callbacks.setWindowState` for the status bar
         * window - the push the platform gives SystemUI. Verified on Nothing OS 5.0 (SystemUI
         * classes8.dex): `statusbar/window/StatusBarWindowStateController`, private
         * `setWindowState(int displayId, int window, int state)`.
         */
        private const val STATUS_BAR_WINDOW_STATE =
            "com.android.systemui.statusbar.window.StatusBarWindowStateController"

        /** Verified on Nothing OS 5.0: res/layout/status_bar.xml, id 0x7f0b0718. */
        private const val ID_NAME_ICON_AREA = "notification_icon_area"

        /** The right-hand cluster: battery, wifi, mobile signal - the icons the inverse pill takes.
         *  AOSP id name, and the fallback is the container around it. */
        private val SYSTEM_AREA_ID_NAMES = listOf("system_icon_area", "system_icons")

        /**
         * The views that hold the phone's own battery / Wi-Fi / signal icons. Their draw calls are
         * hooked, because there is no race to lose that way: when the pill owns the corner these
         * never paint, whatever SystemUI does to their alpha or visibility.
         */
        private const val KIND_ICONS = 1
        private const val KIND_PRIVACY = 2

        /** Names the keyguard's status bar may have on this build. */
        private val KEYGUARD_BAR_CLASSES = listOf(
            "com.android.systemui.statusbar.phone.KeyguardStatusBarView",
            "com.nothing.systemui.statusbar.phone.NTKeyguardStatusBarView",
            "com.android.systemui.keyguard.ui.view.KeyguardStatusBarView"
        )

        /** Name fragments of the classes that draw the phone's own status icons. */
        private val ICON_CLASS_HINTS = listOf(
            "StatusBarIconView",
            "StatusIconContainer",
            "WifiWrapper",
            "WifiView",
            "MobileIcon",
            "SignalIcon",
            "RiveWifi",
            "RiveBattery",
            "BatteryIcon"
        )

        private val SYSTEM_ICON_CLASSES = listOf(
            "com.android.systemui.statusbar.phone.StatusIconContainer",
            "com.android.systemui.statusbar.StatusBarIconView"
        )

        /**
         * The camera / microphone privacy indicator. It is drawn in the same right-hand corner as
         * the inverse pill and its own window sits above the overlay, so it is hidden with the
         * system icon cluster. AOSP's container id, plus the dot's own id for builds that put it
         * somewhere else.
         */
        private val PRIVACY_ID_NAMES = listOf(
            "privacy_chip_container",
            "privacy_dot",
            "privacy_chip"
        )

        /** How often the cellular level is sampled. The icon does not need a faster cadence. */
        private const val CELL_VISIT_MS = 5_000L
    }

    /** Views this hook has hidden, so it only ever puts back what it took away. */
    private val hidden = IdentityHashMap<View, Boolean>()

    /** Child classes already reported, so the log says each one once. */
    private val reported = HashSet<String>()

    /** Only one heartbeat loop per SystemUI process. */
    private var beating = false

    /** Only one window-state hook per SystemUI process, however many bars are attached. */
    private var hookedPush = false

    /** Only one shade-state hook per SystemUI process. */
    private var hookedShade = false

    /** Last shade value pushed, so the log and the provider only see real changes. */
    private var lastShadeOpen: Boolean? = null

    /** Set once the state field is missing on a changed build, so it is not re-read every frame. */
    private var shadeFieldMissing = false

    /** Only one clock sampler per SystemUI process. */
    private var clockWatching = false

    /** Only one cellular sampler per SystemUI process. */
    private var cellularWatching = false

    /** Last signal level published, so a steady signal costs no provider writes. */
    private var lastCellLevel = Int.MIN_VALUE

    /** The status bar clock, once found; the tree is only walked until it is. */
    private var clockView: View? = null

    /** True while the strip's clock must not paint (clock-less left layout, portrait). */
    @Volatile
    private var clockMasked = false

    /** Set once the clock instance's draw methods are hooked, so that happens only once. */
    private var clockDrawHooked = false

    /** Last clock edge published, so a steady bar costs no provider writes. */
    private var lastClockRight = -1

    /** Last screen size published, so a steady screen costs no provider writes. */
    private var lastScreenW = -1
    private var lastScreenH = -1

    /** Context of the status bar view, so the window-state push can publish. */
    private var barContext: Context? = null

    /** Last value pushed, so a burst of identical states costs no provider writes. */
    private var lastPublishedHidden: Boolean? = null

    /** Last Bluetooth audio state published, -1 until the first read. */
    private var lastBtAudio = -1

    /** Set once the Bluetooth read turns out to be refused, so the log is written once. */
    private var btRefused = false

    /** Last Bluetooth read, so the profile query does not run on every tick. */
    private var lastBtPoll = 0L

    /** True while the pill owns the icon corner, which is when those views must not paint. */
    @Volatile
    private var systemIconsHidden = false

    /** True while the privacy dot would sit on top of the pill. */
    @Volatile
    private var privacyHidden = false

    /** The view that holds the camera / microphone indicator. */
    private var privacyAreaRef: java.lang.ref.WeakReference<View>? = null

    /** The icon draw hooks are installed once per SystemUI process. */
    private var iconsHooked = false

    /**
     * The views that hold the phone's own status icons. There is one on the strip and one on the
     * keyguard, and both have to stay blank.
     */
    private val systemAreas = mutableListOf<java.lang.ref.WeakReference<View>>()

    /** Classes already hooked, so a re-attach does not stack another hook on the same method. */
    private val hookedClasses = HashSet<String>()

    /**
     * Publishes "the hook is running" into Settings.Global and keeps it fresh. SystemUI holds
     * WRITE_SECURE_SETTINGS on this build, so no extra permission or root is involved.
     */
    private fun startHeartbeat(context: Context) {
        if (beating) return
        beating = true
        try {
            val main = Handler(Looper.getMainLooper())
            var lastBeat = 0L
            val tick = object : Runnable {
                override fun run() {
                    val now = android.os.SystemClock.elapsedRealtime()
                    try {
                        if (now - lastBeat >= BEAT_MS) {
                            lastBeat = now
                            Settings.Global.putLong(context.contentResolver, KEY_HOOK_BEAT, now)
                        }
                        // Kept off the heartbeat's slow rhythm: a headset is put on in seconds,
                        // and the glyph should be there when the lock screen is looked at.
                        if (now - lastBtPoll >= BT_POLL_MS) {
                            lastBtPoll = now
                            publishBluetoothAudio(context)
                        }
                    } catch (t: Throwable) {
                        Log.w("hook: heartbeat write failed", t)
                    }
                    main.postDelayed(this, BEAT_MS / 3)
                }
            }
            main.post(tick)
        } catch (t: Throwable) {
            beating = false
            Log.w("hook: heartbeat start failed", t)
        }
    }

    /**
     * The push the system gives SystemUI: `CommandQueue.Callbacks.setWindowState` delivers the
     * status bar window's show/hide state, and [STATUS_BAR_WINDOW_STATE] is where that lands. Hooking
     * that method is the equivalent of a webhook - the platform calls us the moment the bar changes,
     * instead of us asking over and over. It is also the only signal that catches a bar hidden by
     * the window manager: those hides scale the window's own surface, which no view property shows.
     *
     * Returns false when the class or method is not on this build, so the caller can fall back to
     * sampling the view.
     */
    private fun hookWindowStatePush(loader: ClassLoader): Boolean {
        return try {
            val cls = Class.forName(STATUS_BAR_WINDOW_STATE, false, loader)
            XposedHelpers.findAndHookMethod(
                cls,
                "setWindowState",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val window = param.args[1] as? Int ?: return
                            val state = param.args[2] as? Int ?: return
                            if (window != WINDOW_STATUS_BAR) return
                            publishHidden(state != WINDOW_STATE_SHOWING)
                        } catch (t: Throwable) {
                            Log.w("hook: window state push failed", t)
                        }
                    }
                }
            )
            Log.i("hook: status bar window state is pushed to us ($STATUS_BAR_WINDOW_STATE)")
            true
        } catch (t: Throwable) {
            Log.w("hook: window state push unavailable, sampling the bar instead", t)
            false
        }
    }

    /**
     * The shade's open/closed state, pushed the same way as the bar's: the controller applies a new
     * state object on every change, and that object says whether the shade is expanded.
     */
    private fun hookShadePush(loader: ClassLoader): Boolean {
        return try {
            val controller = Class.forName(SHADE_CONTROLLER, false, loader)
            val state = Class.forName(SHADE_STATE, false, loader)
            XposedHelpers.findAndHookMethod(
                controller,
                "apply",
                state,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (shadeFieldMissing) return
                            val value = param.args[0] ?: return
                            val open = XposedHelpers.getBooleanField(value, SHADE_EXPANDED_FIELD)
                            publishShade(open)
                        } catch (t: Throwable) {
                            shadeFieldMissing = true
                            Log.w("hook: shade state field not readable, not watching it", t)
                        }
                    }
                }
            )
            Log.i("hook: shade state is pushed to us ($SHADE_CONTROLLER#apply)")
            true
        } catch (t: Throwable) {
            Log.w("hook: shade state push unavailable", t)
            false
        }
    }

    /**
     * Whether an audio device is connected, written for the app to observe.
     *
     * A2DP and the headset profile cover older gear, LE Audio the newer kind, and any of them
     * connected means the earbuds glyph belongs on the strip. A refused read (no
     * BLUETOOTH_CONNECT) leaves the value alone, so a missing permission means no glyph instead
     * of a wrong one, and the app's own permission list stays untouched.
     */
    private fun publishBluetoothAudio(context: Context) {
        try {
            val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                ?.adapter ?: return
            val on = if (!adapter.isEnabled) {
                0
            } else {
                val profiles = mutableListOf(
                    android.bluetooth.BluetoothProfile.A2DP,
                    android.bluetooth.BluetoothProfile.HEADSET
                )
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    profiles.add(android.bluetooth.BluetoothProfile.LE_AUDIO)
                }
                val connected = profiles.any { profile ->
                    adapter.getProfileConnectionState(profile) ==
                        android.bluetooth.BluetoothProfile.STATE_CONNECTED
                }
                if (connected) 1 else 0
            }
            if (on == lastBtAudio) return
            lastBtAudio = on
            Settings.Global.putInt(context.contentResolver, KEY_BT_AUDIO, on)
            Log.i("hook: bluetooth audio ${if (on == 1) "connected" else "off"}")
        } catch (t: Throwable) {
            if (!btRefused) {
                btRefused = true
                Log.w("hook: bluetooth state unreadable, no earbuds glyph", t)
            }
        }
    }

    /** Writes the shade's state for the app to observe. */
    private fun publishShade(open: Boolean) {
        if (open == lastShadeOpen) return
        val context = barContext ?: return
        lastShadeOpen = open
        try {
            Settings.Global.putInt(context.contentResolver, KEY_SHADE_OPEN, if (open) 1 else 0)
            Log.i("hook: shade ${if (open) "open" else "closed"} (pushed)")
        } catch (t: Throwable) {
            Log.w("hook: shade write failed", t)
        }
    }

    /** Writes the bar's state for the app to observe, and beats so it is known to be live. */
    private fun publishHidden(hidden: Boolean) {
        if (hidden == lastPublishedHidden) return
        val context = barContext ?: return
        lastPublishedHidden = hidden
        try {
            Settings.Global.putInt(context.contentResolver, KEY_SB_HIDDEN, if (hidden) 1 else 0)
            Settings.Global.putLong(
                context.contentResolver,
                KEY_HOOK_BEAT,
                android.os.SystemClock.elapsedRealtime()
            )
            Log.i("hook: status bar ${if (hidden) "hidden" else "visible"} (pushed)")
        } catch (t: Throwable) {
            Log.w("hook: visibility write failed", t)
        }
    }

    /**
     * The clock's right edge, so the pill can clear the time instead of guessing a margin. On this
     * build the clock is in the status bar's start side, which is exactly where a left-aligned pill
     * wants to be - and in landscape it is a different width, which is why a fixed dp offset clipped
     * it. Zero means "no clock on screen", which is the shade's case.
     */
    private fun sampleClock(statusBar: View) {
        try {
            val main = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    try {
                        val clock = clockView ?: findByIdNames(statusBar, CLOCK_ID_NAMES)?.also {
                            clockView = it
                            Log.i("hook: clock found (${it.javaClass.simpleName}, id name matched)")
                        }
                        if (clock != null) {
                            publishClock(clock)
                            maskClock(statusBar.context, clock)
                        }
                        publishScreen(statusBar)
                        // The privacy dot appears and disappears between layouts, so it is swept
                        // here as well: a dot SystemUI re-shows is gone again within half a second.
                        hidePrivacy(statusBar, flag(statusBar.context, KEY_INVERSE))
                    } catch (t: Throwable) {
                        Log.w("hook: clock sample failed", t)
                    }
                    main.postDelayed(this, CLOCK_VISIT_MS)
                }
            }
            main.post(tick)
        } catch (t: Throwable) {
            Log.w("hook: clock sampler failed to start", t)
        }
    }

    /** Writes the screen size SystemUI currently sees, so a rotation reaches the app at once. */
    private fun publishScreen(statusBar: View) {
        val context = barContext ?: return
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (w <= 0 || h <= 0) return
        if (w == lastScreenW && h == lastScreenH) return
        lastScreenW = w
        lastScreenH = h
        try {
            Settings.Global.putInt(context.contentResolver, KEY_SCREEN_W, w)
            Settings.Global.putInt(context.contentResolver, KEY_SCREEN_H, h)
            Log.i("hook: screen ${w}x${h} (statusBar ${statusBar.width}x${statusBar.height})")
        } catch (t: Throwable) {
            Log.w("hook: screen write failed", t)
        }
    }

    /**
     * Hides the strip's clock while the pill owns the left end and draws the time itself
     * ([KEY_HIDE_CLOCK]). INVISIBLE rather than alpha alone, for the same reason as the icon
     * cluster: SystemUI rewrites alpha around the shade, and a frame of the old clock would show
     * through the pill.
     */
    private fun maskClock(context: Context, clock: View) {
        try {
            val portrait = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            clockMasked = flag(context, KEY_HIDE_CLOCK) && portrait
            if (clockMasked) {
                hookClockDraw(clock)
                hide(clock)
            } else {
                restore(clock)
            }
        } catch (t: Throwable) {
            Log.w("hook: clock mask threw", t)
        }
    }

    /**
     * Hooks the clock instance's own draw calls, for the frame alpha cannot cover.
     *
     * INVISIBLE is enough most of the time, but SystemUI re-shows the clock around the shade and
     * screen-on, and that pass is exactly the split second the old clock appeared before the next
     * mask tick. The hook tests the instance, so a plain `TextView` clock does not affect other
     * views of the same class.
     */
    private fun hookClockDraw(clock: View) {
        if (clockDrawHooked) return
        clockDrawHooked = true
        var hooked = 0
        val skip = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!clockMasked) return
                if (param.thisObject !== clock) return
                param.result = null
            }
        }
        try {
            var cls: Class<*>? = clock.javaClass
            var own = true
            while (cls != null && cls != Any::class.java) {
                for (m in cls.declaredMethods) {
                    // `Clock` is a TextView: it declares no draw of its own, and the painting that
                    // matters is `onDraw`. Only the clock's own class gets draw/dispatchDraw, so
                    // the process-wide hook is on the one method TextView already calls, not on
                    // every View draw in SystemUI.
                    val wanted = if (own) {
                        m.name == "draw" || m.name == "dispatchDraw" || m.name == "onDraw"
                    } else {
                        m.name == "onDraw"
                    }
                    if (!wanted) continue
                    // Only void overloads: a boolean `draw` is read by drawChild, and cancelling
                    // it leaves it unboxing null - the crash loop that put LSPosed in safe mode.
                    if (m.returnType != java.lang.Void.TYPE) continue
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                    XposedBridge.hookMethod(m, skip)
                    hooked++
                }
                own = false
                cls = cls.superclass
            }
        } catch (t: Throwable) {
            Log.w("hook: clock draw hook failed", t)
        }
        Log.i("hook: clock draw hooked ($hooked methods, ${clock.javaClass.name})")
    }

    /** Writes the clock's right edge, or 0 when it is faded out or gone. */
    private fun publishClock(clock: View) {
        val context = barContext ?: return
        val visible = clock.isShown && clock.alpha > 0.5f && clock.width > 0
        val right = if (!visible) {
            0
        } else {
            val loc = IntArray(2)
            clock.getLocationOnScreen(loc)
            loc[0] + clock.width
        }
        if (right == lastClockRight) return
        lastClockRight = right
        try {
            Settings.Global.putInt(context.contentResolver, KEY_CLOCK_RIGHT, right)
            Log.i("hook: clock right edge = ${right}px")
        } catch (t: Throwable) {
            Log.w("hook: clock write failed", t)
        }
    }

    /** Depth-first search for a view whose resource id name is one of [names]. */
    private fun findByIdNames(view: View, names: List<String>): View? {
        try {
            if (view.id != View.NO_ID) {
                val name = try {
                    view.resources.getResourceEntryName(view.id)
                } catch (t: Throwable) {
                    null
                }
                if (name != null && names.any { it == name }) return view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i) ?: continue
                    findByIdNames(child, names)?.let { return it }
                }
            }
        } catch (t: Throwable) {
            Log.w("hook: id walk threw", t)
        }
        return null
    }

    /**
     * Fallback when the push is unavailable: sample the bar view. Slower and blind to a bar that is
     * faded out by the window manager, but better than nothing on a changed build.
     */
    private fun startVisibilitySampling(statusBar: View) {
        try {
            val main = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    try {
                        publishHidden(barHidden(statusBar))
                    } catch (t: Throwable) {
                        Log.w("hook: visibility write failed", t)
                    }
                    main.postDelayed(this, VIS_VISIT_MS)
                }
            }
            main.post(tick)
            Log.i("hook: sampling the bar every ${VIS_VISIT_MS}ms")
        } catch (t: Throwable) {
            Log.w("hook: visibility sampling failed to start", t)
        }
    }

    /** Reused by [barHidden]; the hook runs on the main thread only. */
    private val barRect = Rect()

    /**
     * Whether the status bar has really left the screen.
     *
     * `isShown` on its own was not enough: a bar that slides up for an immersive app, or one that
     * fades out under the shade, is still attached and still counts as shown, so the pill stayed
     * floating in a strip the bar had already vacated. This weighs three things instead - detached,
     * faded, or mostly scrolled off the top of the display.
     */
    private fun barHidden(bar: View): Boolean {
        return try {
            if (!bar.isShown) return true
            if (bar.alpha < 0.5f) return true
            // Nothing OS fades the bar out on the home screen instead of moving it, and that fade
            // can sit on the window's own root view rather than on the bar itself.
            val root = bar.rootView
            if (root !== bar && root.alpha < 0.5f) return true
            // False when nothing of the view is on screen at all.
            if (!bar.getGlobalVisibleRect(barRect)) return true
            val h = bar.height
            if (h <= 0) return false
            // A bar sliding up is still partly visible for a few frames; it only counts as gone
            // once most of it is off the top.
            barRect.height().toFloat() / h < 0.5f
        } catch (t: Throwable) {
            Log.w("hook: visibility read failed", t)
            false
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (lpparam.packageName != PACKAGE) return
            if (File(KILL_SWITCH).exists()) {
                Log.i("hook: kill switch $KILL_SWITCH present, not attaching")
                return
            }
            // Found by name, then verified: the constructor only hooks if the class really is a
            // View, so a renamed class means this quietly does nothing instead of crashing.
            val cls = Class.forName(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                false,
                lpparam.classLoader
            )
            if (!View::class.java.isAssignableFrom(cls)) {
                Log.w("hook: PhoneStatusBarView is not a View on this build, not attaching")
                return
            }
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        view.post { attach(view) }
                    } catch (t: Throwable) {
                        Log.e("hook: status bar post threw", t)
                    }
                }
            })
            // The keyguard draws its own status bar with its own icon views, which is why the
            // phone's battery and Wi-Fi only came back on the lock screen: nothing was hiding that
            // copy. Hooked here, by every name this build could use.
            hookKeyguardBar(lpparam.classLoader)
            Log.i(
                "hook: attached in SystemUI (${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT})"
            )
        } catch (t: Throwable) {
            Log.e("hook: handleLoadPackage threw", t)
        }
    }

    /** Wires the status bar view up: find the icon area, then keep it in sync with the switches. */
    private fun attach(statusBar: View) {
        try {
            val context = statusBar.context ?: return
            barContext = context
            // Before the icon-area search on purpose: the hook is loaded either way, and the row
            // should say so even if the strip could not be identified on a changed build.
            startHeartbeat(context)
            // The bar's visibility is pushed by the system; sampling is only the fallback.
            if (!hookedPush) {
                hookedPush = hookWindowStatePush(statusBar.javaClass.classLoader)
                if (!hookedPush) startVisibilitySampling(statusBar)
            }
            if (!hookedShade) hookedShade = hookShadePush(statusBar.javaClass.classLoader)
            // Killing the draw call, not just the alpha: SystemUI rewrites alpha and visibility
            // around screen-off, which is the frame where its own icons used to show through.
            hookSystemIcons(statusBar.javaClass.classLoader)
            if (!clockWatching) {
                clockWatching = true
                sampleClock(statusBar)
            }
            // The inverse pill needs the cellular level its hidden icons used, so SystemUI reads
            // it here while it still holds the phone permission.
            startCellular(context)
            val notifArea = findArea(statusBar, listOf(ID_NAME_ICON_AREA))
            val systemArea = findArea(statusBar, SYSTEM_AREA_ID_NAMES)
            val privacyArea = findArea(statusBar, PRIVACY_ID_NAMES)
            if (privacyArea == null) {
                Log.i("hook: privacy chip container not found by id, watching class names instead")
            } else {
                Log.i("hook: privacy chip container found (${privacyArea.javaClass.name})")
            }
            if (notifArea == null) {
                Log.w("hook: notification icon area not found, icons left alone")
            } else {
                Log.i("hook: icon area found (${notifArea.javaClass.name})")
            }
            if (systemArea == null) {
                Log.w("hook: system icon area not found, battery/network icons left alone")
            } else {
                Log.i("hook: system icon area found (${systemArea.javaClass.name})")
                // Hook whatever is actually in there, by class, gated on the instance: this
                // build draws its icons with its own views, so class names alone miss them.
                registerSystemArea(systemArea)
            }
            if (privacyArea != null) {
                privacyAreaRef = java.lang.ref.WeakReference(privacyArea)
                hookAreaDraws(privacyArea, KIND_PRIVACY)
            }
            // Found here as well as in the sampler: the first mask has to land before the bar's
            // first frame, not on the next 500 ms tick.
            if (clockView == null) {
                clockView = findByIdNames(statusBar, CLOCK_ID_NAMES)?.also {
                    Log.i("hook: clock found (${it.javaClass.simpleName}, id name matched)")
                }
            }
            clockView?.let { maskClock(context, it) }
            if (notifArea == null && systemArea == null) return
            val apply = { applySwitches(context, notifArea, systemArea, statusBar) }
            apply()
            // The privacy chip is created lazily and re-shown whenever the camera or microphone
            // changes, which is a layout; this keeps it hidden without polling the whole tree.
            try {
                statusBar.viewTreeObserver.addOnGlobalLayoutListener { apply() }
            } catch (t: Throwable) {
                Log.w("hook: privacy layout listener not registered", t)
            }

            // Children come and go as notifications do, and the areas re-lay out when they do.
            // Alpha only, so this cannot cause another layout pass and cannot loop.
            notifArea?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
            systemArea?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }

            // A reconciliation pass, because a re-applied alpha is not a layout: nothing tells the
            // hook that SystemUI just turned an icon back on. Once a second is enough to make a
            // stray Wi-Fi / signal icon blink out instead of sitting over the bar.
            try {
                val main = Handler(Looper.getMainLooper())
                val reconcile = object : Runnable {
                    override fun run() {
                        try {
                            apply()
                        } catch (t: Throwable) {
                            Log.w("hook: reconcile failed", t)
                        }
                        main.postDelayed(this, 1000L)
                    }
                }
                main.postDelayed(reconcile, 1000L)
            } catch (t: Throwable) {
                Log.w("hook: reconciler not started", t)
            }

            try {
                val resolver = context.contentResolver
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        apply()
                    }
                }
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(KEY_HIDE_ICONS), false, observer
                )
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(KEY_HIDE_CHIPS), false, observer
                )
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(KEY_INVERSE), false, observer
                )
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(KEY_HIDE_CLOCK), false, observer
                )
            } catch (t: Throwable) {
                Log.w("hook: switch observer not registered", t)
            }
        } catch (t: Throwable) {
            Log.e("hook: attach threw", t)
        }
    }

    /** Depth-first search by resource id name, so a changed class name does not matter. */
    private fun findArea(view: View, names: List<String>): ViewGroup? {
        try {
            if (view is ViewGroup) {
                if (view.id != View.NO_ID) {
                    val name = try {
                        view.resources.getResourceEntryName(view.id)
                    } catch (t: Throwable) {
                        null
                    }
                    if (name != null && names.any { it == name }) return view
                }
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i) ?: continue
                    findArea(child, names)?.let { return it }
                }
            }
        } catch (t: Throwable) {
            Log.w("hook: tree walk threw", t)
        }
        return null
    }

    /**
     * Hides or restores the children the switches ask for.
     *
     * Inverse is authoritative for the notification icons: the pill has moved off them, so they
     * come back even if "Hide notification icons" is still on. Its own right-hand cluster goes the
     * other way - the hook hides it and the pill draws the battery and signal glyphs instead.
     */
    private fun applySwitches(
        context: Context,
        notifArea: ViewGroup?,
        systemArea: ViewGroup?,
        root: View?
    ) {
        try {
            val inverse = flag(context, KEY_INVERSE)
            systemIconsHidden = inverse
            privacyHidden = inverse
            // The privacy chip is created lazily and the icon views are rebuilt when the
            // configuration changes, so their classes are picked up on every pass.
            systemArea?.let { area -> hookAreaDraws(area, KIND_ICONS) }
            if (root != null) {
                findArea(root, PRIVACY_ID_NAMES)?.let { area ->
                    privacyAreaRef = java.lang.ref.WeakReference(area)
                    hookAreaDraws(area, KIND_PRIVACY)
                }
            }
            val hideIcons = flag(context, KEY_HIDE_ICONS) && !inverse
            val hideChips = flag(context, KEY_HIDE_CHIPS)

            notifArea?.let { area ->
                for (i in 0 until area.childCount) {
                    val child = area.getChildAt(i) ?: continue
                    val name = child.javaClass.name
                    if (reported.add(name)) Log.i("hook: icon area child $name")
                    val isIcons = name.contains("NotificationIcon")
                    // Live updates are chips; SystemUI hosts them in a Compose view, so both shapes
                    // count. Anything that is neither is left exactly as it is.
                    val isChip = name.contains("Chip") ||
                        name.contains("compose", ignoreCase = true) ||
                        name.contains("Compose")
                    if (isIcons && hideIcons) hide(child)
                    else if (isChip && hideChips) hide(child)
                    else restore(child)
                }
            }

            systemArea?.let { area ->
                // The container itself is hidden, not only its children: SystemUI re-applies alpha
                // to the individual icons whenever a Wi-Fi / signal state changes, and that is how
                // one of them (usually the Wi-Fi icon) kept flickering back in for a moment while
                // the pill's own glyphs sat next to it. A zero on the group covers whatever it
                // holds, including children that were not there when this pass started.
                if (inverse) hide(area) else restore(area)
                for (i in 0 until area.childCount) {
                    val child = area.getChildAt(i) ?: continue
                    val name = child.javaClass.name
                    if (reported.add(name)) Log.i("hook: system icon area child $name")
                    if (inverse) hide(child) else restore(child)
                }
            }

            // Inverse layout only: the pill owns that corner, so the privacy indicator cannot sit
            // on top of it. Both the AOSP container and any view whose class says Privacy are
            // covered, because the dot is created by its own controller.
            hidePrivacy(root, inverse)

            // The clock mask follows the same layout switch, and this pass runs on every layout,
            // so it is also where the flag is read back after a settings change.
            clockView?.let { maskClock(context, it) }
        } catch (t: Throwable) {
            Log.e("hook: applySwitches threw", t)
        }
    }

    /**
     * Hides the camera / microphone privacy indicator while the inverse pill owns the corner it is
     * drawn in. Only views this hook hid are put back, and only when the layout is not inverse, so
     * the indicator is untouched in the normal layout.
     */
    private fun hidePrivacy(root: View?, inverse: Boolean) {
        try {
            if (root == null) return
            val container = findArea(root, PRIVACY_ID_NAMES)
            if (!inverse) {
                container?.let { restore(it) }
                val restored = hidden.keys.filter { it.javaClass.name.contains("Privacy") }
                for (view in restored) restore(view)
                return
            }
            container?.let {
                if (hidden[it] == null) Log.i("hook: privacy indicator hidden (${it.javaClass.name})")
                hide(it)
            }
            walkPrivacy(root) { view ->
                if (hidden[view] == null) {
                    Log.i("hook: privacy indicator hidden (${view.javaClass.name})")
                }
                hide(view)
            }
        } catch (t: Throwable) {
            Log.w("hook: privacy hide threw", t)
        }
    }

    /** Depth-first walk over every view whose class name is the privacy indicator. */
    private fun walkPrivacy(view: View, visit: (View) -> Unit) {
        try {
            if (view.javaClass.name.contains("Privacy")) visit(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i) ?: continue
                    walkPrivacy(child, visit)
                }
            }
        } catch (t: Throwable) {
            Log.w("hook: privacy walk threw", t)
        }
    }

    /** Puts back only a view this hook hid, so nothing legitimate is overwritten. */
    private fun restore(view: View) {
        if (hidden.remove(view) != null) {
            view.alpha = 1f
            view.visibility = View.VISIBLE
        }
    }

    /**
     * Samples the cellular level SystemUI's own status icon shows. The inverse pill replaces that
     * icon, and a plain app cannot read `signalStrength` without the phone permission - SystemUI
     * has it, so it reads the level here and publishes the number.
     */
    private fun startCellular(context: Context) {
        if (cellularWatching) return
        cellularWatching = true
        try {
            val main = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    try {
                        publishCellular(context)
                    } catch (t: Throwable) {
                        Log.w("hook: cellular sample failed", t)
                    }
                    main.postDelayed(this, CELL_VISIT_MS)
                }
            }
            main.post(tick)
        } catch (t: Throwable) {
            cellularWatching = false
            Log.w("hook: cellular sampler failed to start", t)
        }
    }

    private fun publishCellular(context: Context) {
        val level = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.signalStrength?.level ?: -1
        } catch (t: Throwable) {
            // No SIM, no permission, or the API changed: the pill draws empty bars.
            -1
        }
        if (level == lastCellLevel) return
        lastCellLevel = level
        try {
            Settings.Global.putInt(context.contentResolver, KEY_CELL_LEVEL, level)
            Log.i("hook: cellular level = $level")
        } catch (t: Throwable) {
            Log.w("hook: cellular write failed", t)
        }
    }

    /**
     * Hooks the lock screen's own status bar and registers its icon area.
     *
     * The keyguard has a separate view tree from the strip, so hiding the strip's icons left the
     * lock screen drawing the phone's battery and Wi-Fi from a second set of views.
     */
    private fun hookKeyguardBar(loader: ClassLoader) {
        var hooked = 0
        for (name in KEYGUARD_BAR_CLASSES) {
            try {
                val cls = Class.forName(name, false, loader)
                if (!View::class.java.isAssignableFrom(cls)) continue
                XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            view.post { registerKeyguardBar(view, name) }
                        } catch (t: Throwable) {
                            Log.w("hook: keyguard bar post threw", t)
                        }
                    }
                })
                hooked++
                Log.i("hook: keyguard status bar hooked ($name)")
            } catch (t: Throwable) {
                Log.w("hook: $name not on this build", t)
            }
        }
        Log.i("hook: keyguard status bar hooks on $hooked of ${KEYGUARD_BAR_CLASSES.size}")
    }

    /** Registers a keyguard bar's icon area and hides it right away if the pill owns the corner. */
    private fun registerKeyguardBar(bar: View, source: String) {
        try {
            val area = findArea(bar, SYSTEM_AREA_ID_NAMES)
            if (area == null) {
                Log.i("hook: keyguard bar $source has no system icon area")
                return
            }
            registerSystemArea(area)
            if (systemIconsHidden) hideTree(area)
            Log.i(
                "hook: keyguard icon area ready (${area.javaClass.name}, " +
                    "${if (area is ViewGroup) area.childCount else 0} children)"
            )
        } catch (t: Throwable) {
            Log.w("hook: keyguard bar registration failed", t)
        }
    }

    /** Adds an icon area to the set the draw guard tests against, hooking its view classes. */
    private fun registerSystemArea(area: View) {
        if (systemAreas.any { it.get() === area }) return
        systemAreas.add(java.lang.ref.WeakReference(area))
        hookAreaDraws(area, KIND_ICONS)
    }

    /** Hides a view and the children it holds right now. */
    private fun hideTree(view: View) {
        hide(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                hideTree(child)
            }
        }
    }

    /** Hooks [area] and every view already inside it, so none of them can paint. */
    private fun hookAreaDraws(area: View, kind: Int) {
        hookNoDraw(area.javaClass, kind)
        if (area is ViewGroup) {
            for (i in 0 until area.childCount) {
                val child = area.getChildAt(i) ?: continue
                hookAreaDraws(child, kind)
            }
        }
    }

    /**
     * Hooks `draw` / `dispatchDraw` of [cls], skipping only instances that live under the area
     * this kind belongs to - so hooking a common class like FrameLayout cannot blank the rest of
     * SystemUI.
     */
    private fun hookNoDraw(cls: Class<*>, kind: Int) {
        try {
            if (!View::class.java.isAssignableFrom(cls)) return
            if (!hookedClasses.add("${cls.name}#$kind")) return
            val n = hookVoidDraws(cls, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        if (!skipDraw(view, kind)) return
                        // A null result on a void method is "do not run this".
                        param.result = null
                    } catch (t: Throwable) {
                        Log.w("hook: draw guard threw", t)
                    }
                }
            })
            if (n == 0) return
            Log.i("hook: ${cls.name} draw hooked (kind $kind)")
        } catch (t: Throwable) {
            Log.w("hook: could not hook ${cls.name}", t)
        }
    }

    /**
     * Hooks only the draw entry points that return nothing.
     *
     * `View` has a second `draw(Canvas, ViewGroup, long)` overload that returns a boolean, and it
     * is what `ViewGroup.drawChild` reads to know whether the child painted itself. Cancelling
     * that one leaves drawChild unboxing a null into a boolean, which is a hard NullPointerException
     * in the middle of a frame - SystemUI died on every boot because of it, and LSPosed answers a
     * boot crash loop with safe mode. The void overloads are the only ones that can be skipped.
     */
    private fun hookVoidDraws(cls: Class<*>, hook: XC_MethodHook): Int {
        var n = 0
        try {
            for (m in cls.declaredMethods) {
                if (m.name != "draw" && m.name != "dispatchDraw") continue
                if (m.returnType != java.lang.Void.TYPE) continue
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                XposedBridge.hookMethod(m, hook)
                n++
            }
        } catch (t: Throwable) {
            Log.w("hook: void draw scan failed for ${cls.name}", t)
        }
        return n
    }

    /** Whether this view must not paint: the right area is hidden and the view is inside it. */
    private fun skipDraw(view: View, kind: Int): Boolean {
        val on = if (kind == KIND_ICONS) systemIconsHidden else privacyHidden
        if (!on) return false
        // The icon classes are icon-only: the shade's header draws the same ones from a second
        // set of instances, which are not under the strip's area, so the class decides as well.
        if (kind == KIND_ICONS && isIconClass(view.javaClass.name)) return true
        if (kind == KIND_ICONS) {
            // With no area left to compare against there is no way to tell an icon from the rest
            // of SystemUI, so it draws: blanking every instance of a common class would take
            // half the shade with it.
            val areas = systemAreas.mapNotNull { it.get() }
            if (areas.isEmpty()) return false
            return areas.any { isUnder(view, it) }
        }
        val area = privacyAreaRef?.get() ?: return false
        return isUnder(view, area)
    }

    /**
     * Classes that exist only to draw the phone's battery / network / signal icons. A name test,
     * so an updated build's reshuffled class still matches as long as it keeps the meaning.
     */
    private fun isIconClass(name: String): Boolean =
        ICON_CLASS_HINTS.any { name.contains(it) }

    private fun isUnder(view: View, area: View): Boolean {
        var current: View? = view
        var hops = 0
        while (current != null && hops < 12) {
            if (current === area) return true
            current = current.parent as? View
            hops++
        }
        return false
    }

    /**
     * Hooks the draw path of the views that carry the phone's own status icons.
     *
     * Hiding them with alpha and visibility from the outside is a race: SystemUI rewrites both
     * while the screen turns off, and the frame in between is exactly where the original battery
     * and Wi-Fi icons flashed. A skipped draw call cannot be undone by any of that.
     */
    private fun hookSystemIcons(loader: ClassLoader) {
        if (iconsHooked) return
        iconsHooked = true
        var hooked = 0
        for (name in SYSTEM_ICON_CLASSES) {
            try {
                val cls = Class.forName(name, false, loader)
                if (hookVoidDraws(cls, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!systemIconsHidden) return
                            // A null result on a void method is "do not run this".
                            param.result = null
                        }
                    }) == 0
                ) continue
                hooked++
                Log.i("hook: $name no longer draws while the pill owns the corner")
            } catch (t: Throwable) {
                Log.w("hook: $name not hooked, leaving it to alpha", t)
            }
        }
        Log.i("hook: system icon draw hooks on $hooked of ${SYSTEM_ICON_CLASSES.size} classes")
    }

    private fun hide(view: View) {
        try {
            // Already ours and already invisible: skip the invalidate the reconcile loop would
            // otherwise cause every second on every icon.
            if (hidden[view] == true && view.alpha == 0f &&
                view.visibility == View.INVISIBLE
            ) return
            view.alpha = 0f
            // Alpha alone was not enough: SystemUI re-applies it to individual icons when the
            // screen turns off, which is the frame where the phone's own battery and Wi-Fi icons
            // showed through. INVISIBLE is not touched by those passes, so nothing can be drawn.
            view.visibility = View.INVISIBLE
            hidden[view] = true
        } catch (t: Throwable) {
            Log.w("hook: hide threw", t)
        }
    }

    private fun flag(context: Context, key: String): Boolean = try {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    } catch (t: Throwable) {
        false
    }
}
