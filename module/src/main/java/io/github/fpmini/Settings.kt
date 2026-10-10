package io.github.fpmini

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences

/**
 * User options. Stored as strings in the module's world-readable prefs (kept across reboots) and
 * read by the hook through XSharedPreferences. `setprop debug.fpmini.<key> <value>` overrides any
 * of them until the next reboot.
 */
object Settings {
    const val MODULE = "io.github.fpmini"
    const val FILE = "settings"

    /** auto | udfps | side | rear */
    const val SENSOR = "sensor"
    /** minimal (fingerprint only) | message (message + fingerprint) | compact (simplified card) | off */
    const val LAYOUT = "layout"
    /** 1 = custom glow animation, 0 = Samsung's default */
    const val ANIM = "anim"
    /** 1 = touches pass through the prompt to the app behind it */
    const val TOUCH = "touch"
    /** side sensor centre, % of screen height; 0 = auto */
    const val SIDE_Y = "side_y"
    /** side sensor length, % of screen height; 0 = auto */
    const val SIDE_LEN = "side_len"
    /** 1 = keep the prompt up briefly so the success / fail animation is seen */
    const val HOLD = "hold"
    /** seconds until the glow shows the prompt timing out */
    const val TIMEOUT = "timeout"

    val DEFAULTS = linkedMapOf(
        SENSOR to "auto",
        LAYOUT to "minimal",
        ANIM to "1",
        TOUCH to "1",
        SIDE_Y to "0",
        SIDE_LEN to "0",
        HOLD to "1",
        TIMEOUT to "30",
    )

    /** Module side: the prefs file the hook reads. Falls back to private prefs if LSPosed isn't active. */
    fun open(ctx: Context): Pair<SharedPreferences, Boolean> = try {
        @Suppress("DEPRECATION")
        ctx.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE) to true
    } catch (_: SecurityException) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE) to false
    }
}

/** Hook side: current options, re-read at most once a second. */
object Config {
    private val xsp by lazy { try { XSharedPreferences(Settings.MODULE, Settings.FILE) } catch (_: Throwable) { null } }
    private var values: Map<String, String> = Settings.DEFAULTS
    private var readAt = 0L

    fun refresh(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - readAt < 1000) return
        readAt = now
        try {
            xsp?.let { if (it.hasFileChanged()) it.reload() }
        } catch (_: Throwable) {}
        values = Settings.DEFAULTS.mapValues { (k, def) ->
            prop("debug.fpmini.$k").ifEmpty { legacy(k) }.ifEmpty {
                try { xsp?.getString(k, def) } catch (_: Throwable) { null } ?: def
            }
        }
    }

    private fun legacy(k: String) = when (k) {
        Settings.SENSOR -> prop("debug.fpmini.force")
        Settings.SIDE_Y -> prop("debug.fpmini.y")
        else -> ""
    }

    fun str(k: String): String = values[k] ?: Settings.DEFAULTS.getValue(k)
    fun bool(k: String): Boolean = str(k) == "1" || str(k) == "true"
    fun int(k: String): Int = str(k).toIntOrNull() ?: Settings.DEFAULTS.getValue(k).toInt()

    val sensor get() = str(Settings.SENSOR)
    val layout get() = str(Settings.LAYOUT)

    fun prop(key: String): String = try {
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
    } catch (_: Throwable) { "" }
}
