package dev.brl.nowbar.module

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.SystemClock

/**
 * Keeper for the accessibility service itself.
 *
 * The watchdog inside [NowBarA11yService] can repaint a window that went missing, but it cannot
 * do anything when the whole process is gone. On this build a killed (or repeatedly crashed) app
 * is also dropped from `enabled_accessibility_services`, and then Android never binds it again:
 * the pill stays away until it is switched back on by hand. That is a dead end no in-process code
 * can escape, so this alarm - which restarts the process - puts our own component back and lets
 * the platform bind the service again.
 *
 * It only acts on a drop it did not cause: the last thing the service does before a *graceful*
 * stop is clear [WAS_CONNECTED], so switching the service off in Settings stays off. A crash or a
 * kill never reaches onDestroy, so the flag is left set and the service is restored.
 */
object NowBarWatchdog {

    private const val INTERVAL_MS = 60_000L

    /** Written by the service: true while it is bound, false after a deliberate shutdown. */
    const val WAS_CONNECTED = "wasConnected"

    private const val ALARM_ACTION = "dev.brl.nowbar.module.WATCHDOG"

    /** Consecutive "listed but not running" checks before forcing a rebind. */
    private var misses = 0

    /** Arms the repeating check. Cheap and idempotent: safe to call on every start. */
    fun schedule(context: Context) {
        try {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                intent(context)
            )
        } catch (t: Throwable) {
            Log.w("watchdog schedule failed", t)
        }
    }

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WatchdogReceiver::class.java).setAction(ALARM_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** True when the last shutdown was not the owner's doing, i.e. the service died on us. */
    private fun droppedOnItsOwn(context: Context): Boolean = try {
        TuningStore.prefs(context).getBoolean(WAS_CONNECTED, false)
    } catch (t: Throwable) {
        false
    }

    fun check(context: Context) {
        try {
            if (!TuningStore.isOn(context, TuningStore.MASTER)) return
            val me = ComponentName(context, NowBarA11yService::class.java)
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val listed = raw.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it.packageName == me.packageName && it.className == me.className }
            val alive = NowBarBridge.service != null

            if (!listed) {
                if (!droppedOnItsOwn(context)) {
                    Log.i("watchdog: the service is off and it was switched off on purpose - leaving it")
                    return
                }
                val value = (raw.trim().trim(':') + ":" + me.flattenToString()).trim(':')
                Log.i("watchdog: the service was dropped from enabled accessibility services, restoring")
                RootShell.run("settings put secure enabled_accessibility_services '$value'")
                RootShell.run("settings put secure accessibility_enabled 1")
                misses = 0
                return
            }

            if (alive) {
                misses = 0
                return
            }
            // Listed yet not running: the bind did not take. Give it two more chances before
            // forcing it, because a normal start takes a moment.
            misses++
            Log.i("watchdog: enabled but not connected (miss $misses)")
            if (misses < 3) return
            misses = 0
            if (!droppedOnItsOwn(context)) return
            // Taking our entry out and putting it back is what makes the accessibility manager
            // bind the service again. Every other enabled service is left exactly as it was.
            val others = raw.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .filterNot { it.packageName == me.packageName && it.className == me.className }
                .joinToString(":") { it.flattenToString() }
            RootShell.run("settings put secure enabled_accessibility_services '$others'")
            RootShell.run(
                "settings put secure enabled_accessibility_services " +
                    "'${(others + ":" + me.flattenToString()).trim(':')}'"
            )
            RootShell.run("settings put secure accessibility_enabled 1")
            Log.i("watchdog: forced the accessibility manager to bind the service again")
        } catch (t: Throwable) {
            Log.w("watchdog check failed", t)
        }
    }
}
