package it.casate.scanner

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Zebra DataWedge (the scanning service on the TC22): creates a profile for this app that sends each
 * scan as a broadcast intent (ACTION) instead of typing it, so the app reacts to the trigger directly.
 * On devices without DataWedge these broadcasts are simply ignored and the code can be typed.
 */
object DataWedge {
    const val ACTION = "it.casate.scanner.SCAN"
    private const val PROFILE = "CasaTeScanner"
    private const val DW = "com.symbol.datawedge.api.ACTION"

    fun configure(ctx: Context) {
        val app = Bundle().apply {
            putString("PACKAGE_NAME", ctx.packageName)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }
        send(ctx, profile("CREATE_IF_NOT_EXIST", app, plugin("BARCODE", Bundle().apply {
            putString("scanner_selection", "auto")
            putString("scanner_input_enabled", "true")
        })))
        send(ctx, profile("UPDATE", app, plugin("INTENT", Bundle().apply {
            putString("intent_output_enabled", "true")
            putString("intent_action", ACTION)
            putString("intent_delivery", "2") // broadcast
        })))
        send(ctx, profile("UPDATE", app, plugin("KEYSTROKE", Bundle().apply {
            putString("keystroke_output_enabled", "false")
        })))
    }

    private fun plugin(name: String, params: Bundle) = Bundle().apply {
        putString("PLUGIN_NAME", name)
        putString("RESET_CONFIG", "true")
        putBundle("PARAM_LIST", params)
    }

    private fun profile(mode: String, app: Bundle, plugin: Bundle) = Bundle().apply {
        putString("PROFILE_NAME", PROFILE)
        putString("PROFILE_ENABLED", "true")
        putString("CONFIG_MODE", mode)
        putParcelableArray("APP_LIST", arrayOf(app))
        putBundle("PLUGIN_CONFIG", plugin)
    }

    private fun send(ctx: Context, config: Bundle) {
        runCatching {
            ctx.sendBroadcast(Intent().apply {
                action = DW
                putExtra("com.symbol.datawedge.api.SET_CONFIG", config)
            })
        }
    }
}
