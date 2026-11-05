package dev.brl.nowbar.module

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * TEMPORARY debug UI: a floating panel with sliders for both pills.
 *
 * Drag it by the title bar. Every slider writes the same prefs the pill reads, so the pill
 * changes as you drag. Numbers shown are dp; `auto` means "let the service work it out"
 * (left of the status bar pill defaults to just past the clock, top to centred in the bar).
 */
class TunerService : Service() {

    companion object {
        private const val CHANNEL_ID = "nowbar_tuner"
        private const val NOTIFICATION_ID = 42

        /** Upper bound for every slider: high enough that nothing is capped. */
        private const val MAX_DP = 1000

        @Volatile
        var running = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var panel: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val rows = ArrayList<Row>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(WindowManager::class.java)
        prefs = TuningStore.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        startForeground(NOTIFICATION_ID, buildNotification())
        showPanel()
    }

    override fun onDestroy() {
        running = false
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (t: Throwable) {
        }
        try {
            panel?.let { windowManager.removeView(it) }
        } catch (t: Throwable) {
            Log.w("tuner removeView failed", t)
        }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- panel

    private fun showPanel() {
        try {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(12))
            }

            val title = TextView(this).apply {
                text = "Now Bar debug  ⠿"
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(0, 0, 0, dp(6))
            }
            column.addView(title)

            column.addView(note("Sliders are dp. Changes apply live."))

            column.addView(section("Lock screen pill"))
            // Every slider runs 0..1000 dp so nothing is capped while dialling in.
            addRow(column, "widthDp", "Width", TuningStore.DEFAULT_WIDTH_DP, 0, MAX_DP)
            addRow(column, "heightDp", "Height", TuningStore.DEFAULT_HEIGHT_DP, 0, MAX_DP)
            addRow(column, "textDp", "Text", TuningStore.DEFAULT_TEXT_DP, 0, MAX_DP)
            addRow(column, "radiusDp", "Radius", TuningStore.DEFAULT_RADIUS_DP, 0, MAX_DP)
            addRow(column, "bottomDp", "From bottom", TuningStore.DEFAULT_BOTTOM_DP, 0, MAX_DP)

            column.addView(section("Status bar pill"))
            addRow(column, "sbWidthDp", "Width", TuningStore.DEFAULT_SB_WIDTH_DP, 0, MAX_DP)
            addRow(column, "sbHeightDp", "Height", TuningStore.DEFAULT_SB_HEIGHT_DP, 0, MAX_DP)
            addRow(column, "sbTextDp", "Text", TuningStore.DEFAULT_SB_TEXT_DP, 0, MAX_DP)
            addRow(column, "sbRadiusDp", "Radius", TuningStore.DEFAULT_SB_RADIUS_DP, 0, MAX_DP)
            addRow(column, "sbLeftDp", "From left", TuningStore.DEFAULT_SB_LEFT_DP, 0, MAX_DP)
            addRow(column, "sbTopDp", "From top", TuningStore.DEFAULT_SB_TOP_DP, 0, MAX_DP)

            column.addView(section("Expanded popup"))
            addRow(column, "popupWidthDp", "Width", TuningStore.DEFAULT_POPUP_WIDTH_DP, 0, MAX_DP)
            addRow(column, "popupHeightDp", "Height", TuningStore.DEFAULT_POPUP_HEIGHT_DP, 0, MAX_DP)
            addRow(column, "popupTextDp", "Text", TuningStore.DEFAULT_POPUP_TEXT_DP, 0, MAX_DP)
            addRow(column, "popupRadiusDp", "Radius", TuningStore.DEFAULT_POPUP_RADIUS_DP, 0, MAX_DP)
            addRow(column, "popupLeftDp", "From left", TuningStore.DEFAULT_POPUP_LEFT_DP, 0, MAX_DP)
            addRow(column, "popupTopDp", "From top", TuningStore.DEFAULT_POPUP_TOP_DP, 0, MAX_DP)

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            buttons.addView(Button(this).apply {
                text = "Reset"
                setOnClickListener { reset() }
            })
            buttons.addView(Button(this).apply {
                text = "Hide"
                setOnClickListener { stopSelf() }
            })
            column.addView(buttons)

            val scroll = ScrollView(this).apply {
                addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val lp = WindowManager.LayoutParams(
                dp(340),
                dp(600),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(60)
            }

            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.parseColor("#F01B1B20"))
                    setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                }
                addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, dp(600) - dp(22))
            }

            var dragX = 0
            var dragY = 0
            title.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragX = lp.x - event.rawX.toInt()
                        dragY = lp.y - event.rawY.toInt()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = event.rawX.toInt() + dragX
                        lp.y = event.rawY.toInt() + dragY
                        try {
                            windowManager.updateViewLayout(holder, lp)
                        } catch (t: Throwable) {
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(holder, lp)
            panel = holder
            params = lp
            refresh()
            Log.i("tuner panel added (${rows.size} sliders)")
        } catch (t: Throwable) {
            Log.e("tuner panel failed (overlay permission?)", t)
            stopSelf()
        }
    }

    private fun section(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(2))
        }

    private fun note(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
        }

    private fun addRow(parent: LinearLayout, key: String, label: String, default: Int, min: Int, max: Int) {
        val row = Row(key, label, default, min, max)
        rows.add(row)
        parent.addView(row.build())
    }

    private fun reset() {
        prefs.edit()
            .putInt("widthDp", TuningStore.DEFAULT_WIDTH_DP)
            .putInt("heightDp", TuningStore.DEFAULT_HEIGHT_DP)
            .putInt("textDp", TuningStore.DEFAULT_TEXT_DP)
            .putInt("radiusDp", TuningStore.DEFAULT_RADIUS_DP)
            .putInt("bottomDp", TuningStore.DEFAULT_BOTTOM_DP)
            .putInt("sbWidthDp", TuningStore.DEFAULT_SB_WIDTH_DP)
            .putInt("sbHeightDp", TuningStore.DEFAULT_SB_HEIGHT_DP)
            .putInt("sbTextDp", TuningStore.DEFAULT_SB_TEXT_DP)
            .putInt("sbRadiusDp", TuningStore.DEFAULT_SB_RADIUS_DP)
            .putInt("sbLeftDp", TuningStore.DEFAULT_SB_LEFT_DP)
            .putInt("sbTopDp", TuningStore.DEFAULT_SB_TOP_DP)
            .putInt("popupWidthDp", TuningStore.DEFAULT_POPUP_WIDTH_DP)
            .putInt("popupHeightDp", TuningStore.DEFAULT_POPUP_HEIGHT_DP)
            .putInt("popupTextDp", TuningStore.DEFAULT_POPUP_TEXT_DP)
            .putInt("popupRadiusDp", TuningStore.DEFAULT_POPUP_RADIUS_DP)
            .putInt("popupLeftDp", TuningStore.DEFAULT_POPUP_LEFT_DP)
            .putInt("popupTopDp", TuningStore.DEFAULT_POPUP_TOP_DP)
            .apply()
        refresh()
        Log.i("tuner: reset to defaults")
    }

    private fun refresh() {
        for (row in rows) {
            try {
                row.refresh()
            } catch (t: Throwable) {
            }
        }
    }

    private inner class Row(
        private val key: String,
        private val label: String,
        private val default: Int,
        private val min: Int,
        private val max: Int
    ) {
        private val valueView = TextView(this@TunerService)
        private var bar: SeekBar? = null

        fun build(): View {
            val row = LinearLayout(this@TunerService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this@TunerService).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                width = dp(70)
            }
            valueView.apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                // Wide enough for four digits (the sliders run to 1000).
                width = dp(64)
            }
            val seek = SeekBar(this@TunerService).apply {
                this.max = max - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        prefs.edit().putInt(key, progress + min).apply()
                        valueView.text = "${progress + min}"
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            bar = seek
            row.addView(name)
            row.addView(
                seek,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(valueView)
            refresh()
            return row
        }

        fun refresh() {
            val stored = prefs.getInt(key, default)
            valueView.text = if (stored < 0) "auto" else "$stored"
            bar?.progress = stored.coerceIn(min, max) - min
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Now Bar tuner", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Now Bar tuner")
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
