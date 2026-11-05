package dev.brl.nowbar.module

/**
 * Lets the manifest-registered USER_PRESENT receiver poke the bound accessibility service,
 * which cannot be started or messaged the usual way.
 */
object NowBarBridge {

    @Volatile
    var service: NowBarA11yService? = null

    fun userPresent() {
        try {
            service?.onUserPresent()
        } catch (t: Throwable) {
            Log.w("userPresent dispatch failed", t)
        }
    }
}
