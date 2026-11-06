package dev.brl.nowbar.module

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.IdentityHashMap

/**
 * SystemUI hook. One job only: hide the icons Android draws in the status bar, because bbbar's
 * own pill lives in that same strip and the two fight for it.
 *
 * It never lays anything out and never draws. It finds the status bar view that SystemUI inflates,
 * walks to the notification icon area, and sets `alpha` on the children it is asked to hide. That
 * keeps the strip's geometry untouched, so nothing shifts when a switch is flipped; and because it
 * only ever writes `alpha`, a failed lookup leaves the stock status bar exactly as it was.
 *
 * Which children are which is decided by class name at runtime, never by assumption:
 *   - notification icons  -> a child whose class contains `NotificationIcon`
 *   - live update chip    -> a child whose class contains `Chip` (or a Compose host)
 * The first time a child class shows up it is logged, so the log always says what was matched.
 *
 * Switches come from [Settings.Global], which SystemUI (system uid) can read and the app writes
 * through root:
 *
 *   settings put global bbbar_hide_notif_icons 1
 *   settings put global bbbar_hide_live_chips 1
 *
 * A file kill switch stays available for a bad day: `touch /data/local/tmp/bbbar_hook_off`.
 */
class NowBarHook : IXposedHookLoadPackage {

    companion object {
        const val KILL_SWITCH = "/data/local/tmp/bbbar_hook_off"
        private const val PACKAGE = "com.android.systemui"

        /** Hidden only while these are 1. Written by the settings app through root. */
        const val KEY_HIDE_ICONS = "bbbar_hide_notif_icons"
        const val KEY_HIDE_CHIPS = "bbbar_hide_live_chips"

        /** Verified on Nothing OS 5.0: res/layout/status_bar.xml, id 0x7f0b0718. */
        private const val ID_NAME_ICON_AREA = "notification_icon_area"
    }

    /** Views this hook has hidden, so it only ever puts back what it took away. */
    private val hidden = IdentityHashMap<View, Boolean>()

    /** Child classes already reported, so the log says each one once. */
    private val reported = HashSet<String>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (lpparam.packageName != PACKAGE) return
            if (File(KILL_SWITCH).exists()) {
                Log.i("hook: kill switch $KILL_SWITCH present, not attaching")
                return
            }
            // Found by name, then verified: the constructor only hooks if the class really is a
            // View, so a renamed class means this quietly does nothing instead of crashing.
            val cls = Class.forName(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                false,
                lpparam.classLoader
            )
            if (!View::class.java.isAssignableFrom(cls)) {
                Log.w("hook: PhoneStatusBarView is not a View on this build, not attaching")
                return
            }
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        view.post { attach(view) }
                    } catch (t: Throwable) {
                        Log.e("hook: status bar post threw", t)
                    }
                }
            })
            Log.i(
                "hook: attached in SystemUI (${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT})"
            )
        } catch (t: Throwable) {
            Log.e("hook: handleLoadPackage threw", t)
        }
    }

    /** Wires the status bar view up: find the icon area, then keep it in sync with the switches. */
    private fun attach(statusBar: View) {
        try {
            val context = statusBar.context ?: return
            val area = findIconArea(statusBar)
            if (area == null) {
                Log.w("hook: notification icon area not found, icons left alone")
                return
            }
            Log.i("hook: icon area found (${area.javaClass.name})")
            val apply = { applySwitches(context, area) }
            apply()

            // Children come and go as notifications do, and the area re-lays out when they do.
            // Alpha only, so this cannot cause another layout pass and cannot loop.
            area.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }

            try {
                val resolver = context.contentResolver
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        apply()
                    }
                }
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(KEY_HIDE_ICONS), false, observer
                )
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(KEY_HIDE_CHIPS), false, observer
                )
            } catch (t: Throwable) {
                Log.w("hook: switch observer not registered", t)
            }
        } catch (t: Throwable) {
            Log.e("hook: attach threw", t)
        }
    }

    /** Depth-first search by resource id name, so a changed class name does not matter. */
    private fun findIconArea(view: View): ViewGroup? {
        try {
            if (view is ViewGroup) {
                if (view.id != View.NO_ID) {
                    val name = try {
                        view.resources.getResourceEntryName(view.id)
                    } catch (t: Throwable) {
                        null
                    }
                    if (name == ID_NAME_ICON_AREA) return view
                }
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i) ?: continue
                    findIconArea(child)?.let { return it }
                }
            }
        } catch (t: Throwable) {
            Log.w("hook: tree walk threw", t)
        }
        return null
    }

    /** Hides or restores the children the switches ask for. */
    private fun applySwitches(context: Context, area: ViewGroup) {
        try {
            val hideIcons = flag(context, KEY_HIDE_ICONS)
            val hideChips = flag(context, KEY_HIDE_CHIPS)
            for (i in 0 until area.childCount) {
                val child = area.getChildAt(i) ?: continue
                val name = child.javaClass.name
                if (reported.add(name)) Log.i("hook: icon area child $name")
                val isIcons = name.contains("NotificationIcon")
                // Live updates are chips; SystemUI hosts them in a Compose view, so both shapes
                // count. Anything that is neither is left exactly as it is.
                val isChip = name.contains("Chip") ||
                    name.contains("compose", ignoreCase = true) ||
                    name.contains("Compose")
                if (isIcons && hideIcons) hide(child)
                else if (isChip && hideChips) hide(child)
                else if (hidden.remove(child) != null) child.alpha = 1f
            }
        } catch (t: Throwable) {
            Log.e("hook: applySwitches threw", t)
        }
    }

    private fun hide(view: View) {
        try {
            view.alpha = 0f
            hidden[view] = true
        } catch (t: Throwable) {
            Log.w("hook: alpha write threw", t)
        }
    }

    private fun flag(context: Context, key: String): Boolean = try {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    } catch (t: Throwable) {
        false
    }
}
