package dev.brl.nowbar.module

import android.util.Log

/**
 * All module output goes here, tag "NowBar". Never throws.
 *
 * The debug level decides how much is written, because the loud lines are the ones that matter
 * when something is wrong and are pure noise when nothing is:
 *
 *  - 0 off:     only warnings and errors.
 *  - 1 basic:   what each surface placed and when a card changed. The default.
 *  - 2 verbose: every notification read, every placement decision, every drag frame.
 *
 * The level is read from the shared preferences, so the settings screen can change it without a
 * restart. The Xposed hook runs inside SystemUI and cannot read those preferences; it keeps the
 * default and logs its own startup lines.
 */
object Log {
    const val TAG = "NowBar"

    @Volatile
    private var level = 1

    /** Re-reads the debug level. Cheap: one preference read. */
    fun sync(context: android.content.Context) {
        try {
            level = TuningStore.debugLevel(context)
        } catch (t: Throwable) {
        }
    }

    fun i(msg: String) {
        if (level < 1) return
        try {
            Log.i(TAG, msg)
        } catch (t: Throwable) {
        }
    }

    /** Only at verbose: for lines that would otherwise drown the tag. */
    fun v(msg: String) {
        if (level < 2) return
        try {
            Log.v(TAG, msg)
        } catch (t: Throwable) {
        }
    }

    fun w(msg: String, t: Throwable? = null) {
        try {
            Log.w(TAG, msg, t)
        } catch (ignored: Throwable) {
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        try {
            Log.e(TAG, msg, t)
        } catch (ignored: Throwable) {
        }
    }
}
