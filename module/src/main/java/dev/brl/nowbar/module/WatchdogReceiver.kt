package dev.brl.nowbar.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs the [NowBarWatchdog] check from a broadcast, so it also works when the process that draws
 * the pill is not running. Re-armed here after a reboot or an app update, because the repeating
 * alarm does not survive either.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        try {
            NowBarWatchdog.schedule(ctx)
            NowBarWatchdog.check(ctx)
        } catch (t: Throwable) {
            Log.w("watchdog receiver failed", t)
        }
    }
}
