package dev.brl.nowbar.module

import android.util.Log

/** All module output goes here, tag "NowBar". Never throws. */
object Log {
    const val TAG = "NowBar"

    fun i(msg: String) {
        try {
            Log.i(TAG, msg)
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
