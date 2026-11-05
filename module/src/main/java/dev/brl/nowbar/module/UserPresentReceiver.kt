package dev.brl.nowbar.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Instant unlock trigger.
 *
 * ACTION_USER_PRESENT is a protected system broadcast sent the moment the keyguard actually
 * goes away (PIN, pattern or fingerprint). Accessibility window events do not fire reliably for
 * that transition, and polling costs latency, so this is the primary signal; the service still
 * re-checks KeyguardManager before doing anything.
 */
class UserPresentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            Log.i("USER_PRESENT received")
            NowBarBridge.userPresent()
        } catch (t: Throwable) {
            Log.w("UserPresentReceiver failed", t)
        }
    }
}
