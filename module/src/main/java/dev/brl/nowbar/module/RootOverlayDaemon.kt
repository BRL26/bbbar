package dev.brl.nowbar.module

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONObject
import java.io.File

/**
 * Runs as root (started with `su -c app_process …`) and draws the pill as a *system*
 * window, which is the only way to sit above the keyguard.
 *
 * Window type attempts, best first:
 *   2010 TYPE_SYSTEM_ERROR    - above keyguard and everything else
 *   2006 TYPE_SYSTEM_OVERLAY  - deprecated, still above keyguard
 *   2003 TYPE_SYSTEM_ALERT    - deprecated, above keyguard
 *
 * Config is polled from a JSON file so the app can retune it live without restarting
 * the daemon.
 */
object RootOverlayDaemon {

    private const val TAG = "NowBarRoot"
    private const val POLL_MS = 400L

    private val TYPE_SYSTEM_ERROR = 2010
    private val TYPE_SYSTEM_OVERLAY = 2006
    private val TYPE_SYSTEM_ALERT = 2003

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Looper.prepareMainLooper()
            val configPath = args.firstOrNull() ?: "/data/local/tmp/nowbar_tuning.json"
            log("daemon starting, config=$configPath")

            val context = systemContext()
            if (context == null) {
                log("could not obtain a system context; aborting")
                return
            }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            log("step: windowManager=${windowManager != null}")
            if (windowManager == null) return

            val density = try {
                context.resources.displayMetrics.density
            } catch (t: Throwable) {
                logStack("displayMetrics failed", t)
                2.75f
            }
            log("step: density=$density")
            fun dp(v: Int) = (v * density + 0.5f).toInt()

            probeSharedMemory()

            var pill: PillView? = try {
                PillView(context)
            } catch (t: Throwable) {
                logStack("PillView #1 failed", t)
                null
            }
            if (pill == null) {
                tryInitSharedMemory()
                pill = try {
                    PillView(context)
                } catch (t: Throwable) {
                    logStack("PillView #2 failed", t)
                    null
                }
            }
            if (pill == null) {
                log("could not create the pill view; exiting")
                return
            }
            log("step: pill view created")
            var params: WindowManager.LayoutParams? = null

            for (type in intArrayOf(TYPE_SYSTEM_ERROR, TYPE_SYSTEM_OVERLAY, TYPE_SYSTEM_ALERT)) {
                try {
                    val lp = WindowManager.LayoutParams(
                        dp(148),
                        dp(44),
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        y = dp(186)
                    }
                    log("step: addView type=$type")
                    windowManager.addView(pill, lp)
                    params = lp
                    log("window added with type=$type")
                    break
                } catch (t: Throwable) {
                    logStack("type=$type rejected", t)
                }
            }

            if (params == null) {
                log("no system window type accepted; daemon exiting")
                return
            }

            val handler = Handler(Looper.getMainLooper())
            handler.post(object : Runnable {
                override fun run() {
                    try {
                        applyConfig(windowManager, pill, params!!, density, configPath)
                    } catch (t: Throwable) {
                        log("config apply failed: ${t.message}")
                    }
                    handler.postDelayed(this, POLL_MS)
                }
            })

            Looper.loop()
        } catch (t: Throwable) {
            log("daemon crashed: $t")
        }
    }

    private fun applyConfig(
        windowManager: WindowManager,
        pill: PillView,
        lp: WindowManager.LayoutParams,
        density: Float,
        path: String
    ) {
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val file = File(path)
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        val values = Tuning.Values(
            widthDp = json.optInt("widthDp", TuningStore.DEFAULT_WIDTH_DP),
            heightDp = json.optInt("heightDp", TuningStore.DEFAULT_HEIGHT_DP),
            textDp = json.optInt("textDp", TuningStore.DEFAULT_TEXT_DP),
            radiusDp = json.optInt("radiusDp", TuningStore.DEFAULT_RADIUS_DP),
            bottomDp = json.optInt("bottomDp", TuningStore.DEFAULT_BOTTOM_DP),
            label = json.optString("label", TuningStore.DEFAULT_LABEL)
        )
        val newWidth = dp(values.widthDp)
        val newHeight = dp(values.heightDp)
        val newY = dp(values.bottomDp)
        if (lp.width == newWidth && lp.height == newHeight && lp.y == newY) return
        lp.width = newWidth
        lp.height = newHeight
        lp.y = newY
        pill.setStyle(values)
        windowManager.updateViewLayout(pill, lp)
        log("applied ${values.widthDp}x${values.heightDp}dp y=${newY}px")
    }

    /**
     * A Context that can talk to WindowManager from a bare app_process (root) process.
     *
     * On Android 17 `ActivityThread.systemMain()` bails out with
     * "ApplicationSharedMemory not initialized", so we fall back to building the system
     * ContextImpl directly off a plain ActivityThread instance.
     */
    private fun systemContext(): Context? {
        val atClass = try {
            Class.forName("android.app.ActivityThread")
        } catch (t: Throwable) {
            log("ActivityThread missing: ${describe(t)}")
            return null
        }

        try {
            val thread = atClass.getMethod("systemMain").invoke(null)
            val ctx = atClass.getMethod("getSystemContext").invoke(thread) as? Context
            if (ctx != null) {
                log("context: systemMain()")
                return ctx
            }
        } catch (t: Throwable) {
            log("systemMain failed: ${describe(t)}")
        }

        try {
            val ctor = atClass.getDeclaredConstructor().apply { isAccessible = true }
            val thread = ctor.newInstance()
            val ctxImpl = Class.forName("android.app.ContextImpl")
            val method = ctxImpl
                .getDeclaredMethod("createSystemContext", atClass)
                .apply { isAccessible = true }
            val ctx = method.invoke(null, thread) as? Context
            if (ctx != null) {
                log("context: ContextImpl.createSystemContext()")
                return ctx
            }
        } catch (t: Throwable) {
            log("createSystemContext failed: ${describe(t)}")
        }

        return null
    }

    /** Android 17 requirement for WindowManagerGlobal; absent outside zygote-spawned processes. */
    private fun probeSharedMemory() {
        try {
            val cls = Class.forName("android.os.ApplicationSharedMemory")
            log("ASM ctors: " + cls.declaredConstructors.joinToString { c -> "(" + c.parameterTypes.joinToString { it.simpleName } + ")" })
            log("ASM methods: " + cls.declaredMethods.joinToString { m -> m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")" })
            log("ASM fields: " + cls.declaredFields.joinToString { f -> f.name + ":" + f.type.simpleName })
        } catch (t: Throwable) {
            log("ASM probe failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun tryInitSharedMemory() {
        try {
            val cls = Class.forName("android.os.ApplicationSharedMemory")
            for (name in listOf("setup", "initialize", "init", "getInstance")) {
                val method = cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: continue
                try {
                    method.isAccessible = true
                    log("ASM $name() -> ${method.invoke(null)}")
                } catch (t: Throwable) {
                    log("ASM $name() failed: ${describe(t)}")
                }
            }
        } catch (t: Throwable) {
            log("ASM init failed: ${describe(t)}")
        }
    }

    private fun logStack(prefix: String, t: Throwable) {
        var root = t
        while (root.cause != null) root = root.cause!!
        log("$prefix: ${root.javaClass.name}: ${root.message}")
        root.stackTrace.take(8).forEach { log("    at $it") }
    }

    private fun describe(t: Throwable): String {
        var root = t
        while (root.cause != null) root = root.cause!!
        val frame = root.stackTrace.firstOrNull()?.toString() ?: "?"
        return "${root.javaClass.name}: ${root.message} @ $frame"
    }

    private fun log(msg: String) {
        try {
            android.util.Log.i(TAG, msg)
        } catch (t: Throwable) {
        }
        println("$TAG: $msg")
    }
}
