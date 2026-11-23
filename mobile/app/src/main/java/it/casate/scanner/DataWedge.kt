package it.casate.scanner

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Zebra DataWedge (the scanning service on the TC22): a profile for this app that sends each scan as a
 * broadcast intent (ACTION) instead of typing it, so the app reacts to the trigger on any screen.
 *
 * The whole profile is written in ONE call (OVERWRITE: barcode on, intent on, keystrokes off) and
 * DataWedge is asked to answer (RESULT_ACTION). If it is not confirmed, or another profile is active
 * when the app is in front, the app writes it again. If the scanner still types (keystrokes, e.g. the
 * default Profile0), MainActivity catches the typed code anyway (see dispatchKeyEvent).
 */
object DataWedge {
    const val ACTION = "it.casate.scanner.SCAN"
    const val RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION"
    const val PROFILE = "CasaTeScanner"
    const val CMD_CONFIG = "casate_config"
    private const val DW = "com.symbol.datawedge.api.ACTION"

    fun configure(ctx: Context) {
        val app = Bundle().apply {
            putString("PACKAGE_NAME", ctx.packageName)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }
        val plugins = arrayListOf(
            plugin("BARCODE", Bundle().apply {
                putString("scanner_selection", "auto")
                putString("scanner_input_enabled", "true")
            }),
            plugin("INTENT", Bundle().apply {
                putString("intent_output_enabled", "true")
                putString("intent_action", ACTION)
                putString("intent_delivery", "2") // broadcast
            }),
            plugin("KEYSTROKE", Bundle().apply {
                putString("keystroke_output_enabled", "false")
            }),
        )
        val config = Bundle().apply {
            putString("PROFILE_NAME", PROFILE)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "OVERWRITE")
            putParcelableArray("APP_LIST", arrayOf(app))
            putParcelableArrayList("PLUGIN_CONFIG", plugins)
        }
        send(ctx, Intent().apply {
            action = DW
            putExtra("com.symbol.datawedge.api.SET_CONFIG", config)
            putExtra("SEND_RESULT", "COMPLETE_RESULT")
            putExtra("COMMAND_IDENTIFIER", CMD_CONFIG)
        })
    }

    /** Asks which profile DataWedge is using now (answer on RESULT_ACTION). */
    fun askActiveProfile(ctx: Context) {
        send(ctx, Intent().apply { action = DW; putExtra("com.symbol.datawedge.api.GET_ACTIVE_PROFILE", "") })
    }

    /** Reads a DataWedge answer: (config result or null, active profile or null). */
    fun readResult(i: Intent): Pair<String?, String?> {
        val active = i.getStringExtra("com.symbol.datawedge.api.RESULT_GET_ACTIVE_PROFILE")
        if (i.getStringExtra("COMMAND_IDENTIFIER") != CMD_CONFIG) return null to active
        val res = i.getStringExtra("RESULT") ?: return null to active
        if (res == "SUCCESS") return "SUCCESS" to active
        val codes = runCatching {
            @Suppress("DEPRECATION")
            val list = i.getParcelableArrayListExtra<Bundle>("RESULT_LIST")
            list?.mapNotNull { b -> b.getString("RESULT_CODE")?.let { (b.getString("PLUGIN_NAME") ?: "") + " " + it } }
        }.getOrNull().orEmpty()
        @Suppress("DEPRECATION")
        val info = i.getBundleExtra("RESULT_INFO")?.getString("RESULT_CODE")
        return (listOfNotNull(res, info).joinToString(" · ") + if (codes.isEmpty()) "" else " · " + codes.joinToString(", ")) to active
    }

    private fun plugin(name: String, params: Bundle) = Bundle().apply {
        putString("PLUGIN_NAME", name)
        putString("RESET_CONFIG", "true")
        putBundle("PARAM_LIST", params)
    }

    private fun send(ctx: Context, i: Intent) {
        runCatching { ctx.sendBroadcast(i) }
    }
}

/**
 * Codes typed by the scanner (keystroke output) instead of sent as intents: the characters arrive a few
 * milliseconds apart and usually end with ENTER. Collected here so a scan works even when nothing is
 * focused; a person typing is much slower and is never mistaken for a scan.
 */
class KeyScan(private val now: () -> Long = System::currentTimeMillis) {
    private val buf = StringBuilder()
    private var first = 0L
    private var last = 0L

    /** Adds a typed character; false if this does not look like a scanner. */
    fun add(c: Char): Boolean {
        val t = now()
        if (buf.isNotEmpty() && t - last > GAP_MS) buf.setLength(0)
        if (buf.isEmpty()) first = t
        buf.append(c)
        last = t
        return true
    }

    /** ENTER (or the pause after the last character): the code, when it came at scanner speed. */
    fun finish(): String? {
        val s = buf.toString().trim()
        val fast = s.length < 2 || (last - first) <= s.length * PER_CHAR_MS
        buf.setLength(0)
        return if (s.length >= MIN_LEN && fast) s else null
    }

    fun pending() = buf.isNotEmpty()

    companion object {
        const val GAP_MS = 120L
        const val PER_CHAR_MS = 40L
        const val MIN_LEN = 6
        /** Typed text (search box) at scanner speed: a whole code in a burst. */
        fun looksScanned(text: String, ms: Long) =
            text.length >= 8 && text.all { it.isDigit() } && ms <= text.length * PER_CHAR_MS
    }
}
