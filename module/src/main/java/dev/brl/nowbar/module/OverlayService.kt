package dev.brl.nowbar.module

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager

/**
 * Draws the pill as our own overlay window, with no LSPosed/SystemUI hooking at all.
 *
 * Window type is TYPE_APPLICATION_OVERLAY, so the only system permission needed is
 * "Display over other apps" (SYSTEM_ALERT_WINDOW), which the user grants normally.
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "nowbar_overlay"
        private const val NOTIFICATION_ID = 41

        @Volatile
        var running = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var pill: PillView? = null
    private var params: WindowManager.LayoutParams? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyTuning()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(WindowManager::class.java)
        prefs = TuningStore.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        startForeground(NOTIFICATION_ID, buildNotification())
        addPill()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (t: Throwable) {
        }
        try {
            pill?.let { windowManager.removeView(it) }
        } catch (t: Throwable) {
            Log.w("removeView failed", t)
        }
        pill = null
        params = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- overlay

    private fun addPill() {
        try {
            val view = PillView(this)
            val lp = WindowManager.LayoutParams(
                dp(TuningStore.DEFAULT_WIDTH_DP),
                dp(TuningStore.DEFAULT_HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(TuningStore.DEFAULT_BOTTOM_DP)
            }

            windowManager.addView(view, lp)
            pill = view
            params = lp
            Log.i("overlay window added (type=TYPE_APPLICATION_OVERLAY)")
            applyTuning()
        } catch (t: Throwable) {
            Log.e("addPill failed - is 'Display over other apps' granted?", t)
            stopSelf()
        }
    }

    private fun applyTuning() {
        val view = pill ?: return
        val lp = params ?: return
        try {
            val t = Tuning.current(this)
            lp.width = dp(t.widthDp)
            lp.height = dp(t.heightDp)
            lp.y = dp(t.bottomDp)
            view.setStyle(t)
            windowManager.updateViewLayout(view, lp)
            view.requestLayout()
            Log.i("overlay updated: ${t.widthDp}x${t.heightDp}dp text=${t.textDp}dp y=${lp.y}px label='${t.label}'")
        } catch (t: Throwable) {
            Log.w("applyTuning failed", t)
        }
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Now Bar overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Now Bar")
            .setContentText("Pill overlay running")
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
