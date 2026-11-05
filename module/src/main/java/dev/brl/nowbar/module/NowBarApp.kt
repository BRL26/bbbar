package dev.brl.nowbar.module

import android.app.Application

/**
 * Applies Material You to every activity: the palette is derived from the wallpaper, so the
 * settings screen follows the system accent and light/dark mode without a hardcoded colour.
 */
class NowBarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        } catch (t: Throwable) {
            Log.w("dynamic colours unavailable, falling back to the baseline theme", t)
        }
    }
}
