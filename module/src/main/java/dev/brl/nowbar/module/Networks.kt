package dev.brl.nowbar.module

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Which connection the status pill should draw in place of Android's own icons.
 *
 * The rule the owner asked for: Wi-Fi wins when it is up, mobile data is the fallback, and with
 * neither there is no glyph at all rather than an empty set of bars. The pill draws the battery
 * separately, so "nothing" only means the network slot.
 */
object Networks {

    const val NONE = 0
    const val WIFI = 1
    const val MOBILE = 2

    /**
     * Reads every registered network, not just the default one: a phone with Wi-Fi joined and
     * mobile data enabled has both up, and the default can be either while the user is looking at
     * the strip. Wi-Fi still wins, because that is the connection carrying the traffic.
     */
    fun kind(context: Context): Int {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NONE
            var wifi = false
            var mobile = false
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mobile = true
                if (wifi) break
            }
            when {
                wifi -> WIFI
                mobile -> MOBILE
                else -> NONE
            }
        } catch (t: Throwable) {
            Log.w("network state unreadable", t)
            NONE
        }
    }
}
