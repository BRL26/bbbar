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
        // A file written by an older build is replaced by this build's full defaults, so no
        // geometry from the old layout can leave a surface half-configured.
        Log.i("app: onCreate (config ${TuningStore.CONFIG_VERSION})")
        TuningStore.maybeRewrite(this)
        // Started from here so the check is armed whichever way the app comes up, including the
        // launch after the system restored the accessibility service.
        NowBarWatchdog.schedule(this)
        // The loop outlives this process, so a crash cannot leave the pill switched off.
        RootKeeper.startAsync()
    }
}
