package dev.brl.nowbar.module

import android.content.Context

/**
 * The all-off switch: the bar and every helper bbbar has running, but not the accessibility
 * permission.
 *
 * The grant belongs to the app, not to a session, so it is deliberately left alone: switching
 * everything back on should not need a second trip to system settings. What is stopped is what
 * bbbar started - the root keeper loop, the keyguard logcat stream, the watchdog alarm and the
 * service's own timers and windows.
 */
object AllOff {

    /** Stops the bar, the root keeper, the keyguard log stream and the watchdog. */
    fun off(context: Context) {
        try {
            RootKeeper.stop()
            RootKeyguardWatcher.stop()
            NowBarWatchdog.cancel(context)
            NowBarBridge.service?.shutDownEverything()
            Log.i("everything off: bar and background helpers stopped")
        } catch (t: Throwable) {
            Log.w("everything off failed", t)
        }
    }

    /** Puts the helpers back and asks the bound service to draw again. */
    fun on(context: Context) {
        try {
            RootKeeper.startAsync()
            NowBarWatchdog.schedule(context)
            NowBarBridge.service?.startUpAgain()
            Log.i("everything on: bar and background helpers started")
        } catch (t: Throwable) {
            Log.w("everything on failed", t)
        }
    }
}
