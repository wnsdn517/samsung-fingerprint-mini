package io.github.fpmini

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Saves options from adb (kept across reboots):
 *   adb shell am broadcast -a io.github.fpmini.SET -n io.github.fpmini/.ConfigReceiver --include-stopped-packages --es sensor side
 * `--ez reset true` restores the defaults. Senders need android.permission.DUMP, which adb shell has.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val (prefs, active) = Settings.open(ctx)
        val edit = prefs.edit()
        if (intent.getBooleanExtra("reset", false)) edit.clear()
        val unknown = mutableListOf<String>()
        intent.extras?.keySet()?.forEach { k ->
            if (k == "reset") return@forEach
            if (k in Settings.DEFAULTS) edit.putString(k, intent.extras?.get(k).toString()) else unknown += k
        }
        edit.commit()
        val now = Settings.DEFAULTS.keys.joinToString(" ") { "$it=${prefs.getString(it, null) ?: Settings.DEFAULTS[it]}" }
        resultData = buildString {
            if (!active) append("WARNING: module not active in LSPosed; ")
            if (unknown.isNotEmpty()) append("unknown keys: $unknown; ")
            append(now)
        }
    }
}
