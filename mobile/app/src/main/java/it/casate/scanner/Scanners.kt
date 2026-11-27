package it.casate.scanner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle

/**
 * Scanners that are not Zebra (2026-11-25: on Honeywell and other models the code was read only after
 * tapping the search box). Most rugged phones can send each scan as a broadcast intent; each brand uses
 * its own action and extra. Here:
 *  - Honeywell: the app claims the scanner and asks for a "data intent" on our own ACTION (released when
 *    the app goes to the background, so other apps get the scanner back);
 *  - Urovo, Newland, iData / Kaicom, Sunmi, Chainway, Seuic, Datalogic and generic devices: their default
 *    broadcast actions are listened to as they are, no setup needed;
 *  - anything else that "types" the code (keyboard wedge, or text written straight into the focused field)
 *    is caught by KeyScan / the invisible scan field in MainActivity.
 */
object Scanners {
    private const val HW_PKG = "com.intermec.datacollectionservice"
    private const val HW_CLAIM = "com.honeywell.aidc.action.ACTION_CLAIM_SCANNER"
    private const val HW_RELEASE = "com.honeywell.aidc.action.ACTION_RELEASE_SCANNER"

    /** Broadcast actions of the common brands (ours first: Zebra DataWedge and Honeywell send on it). */
    val ACTIONS = listOf(
        DataWedge.ACTION,
        "android.intent.ACTION_DECODE_DATA",                    // Urovo
        "nlscan.action.SCANNER_RESULT",                         // Newland
        "android.intent.action.SCANRESULT",                     // iData, Kaicom
        "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED",          // Sunmi
        "com.scanner.broadcast",                                // Chainway and generic
        "scan.rcv.message",                                     // Seuic / Chainway (bytes)
        "com.android.server.scannerservice.broadcast",          // Seuic
        "com.datalogic.decodewizard.decode_action",             // Datalogic intent wedge
        "com.honeywell.decode.intent.action.EDIT_DATA",         // Honeywell intent wedge set by hand
        "com.android.scanner.broadcast",
        "com.barcode.sendBroadcast",
    )

    /** String extras where the brands put the code. */
    private val STRING_KEYS = listOf(
        "com.symbol.datawedge.data_string", "data", "barcode_string", "SCAN_BARCODE1", "value", "scannerdata",
        "com.datalogic.decode.intentwedge.barcode_string", "barcodeData", "barcode", "decode_rslt", "BARCODE",
        "scan_data", "code",
    )

    /** Byte-array extras (with an optional length). */
    private val BYTE_KEYS = listOf("barocode", "barcode", "dataBytes", "decode_rslt_bytes")

    fun filter(): IntentFilter = IntentFilter().apply {
        ACTIONS.forEach { addAction(it) }
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    /** The code inside a scanner broadcast (null when it is not one). */
    fun extract(i: Intent): String? {
        val ex = i.extras ?: return null
        for (k in STRING_KEYS) {
            val v = runCatching { ex.getString(k) }.getOrNull()
            if (!v.isNullOrBlank()) return clean(v)
        }
        for (k in BYTE_KEYS) {
            val b = runCatching { ex.getByteArray(k) }.getOrNull() ?: continue
            val n = ex.getInt("length", b.size).coerceIn(0, b.size)
            val s = String(b, 0, n, Charsets.ISO_8859_1)
            if (s.isNotBlank()) return clean(s)
        }
        return null
    }

    /** Line breaks, tabs and control characters some scanners add at the end. */
    fun clean(s: String): String = s.filter { it >= ' ' }.trim()

    val brand: String get() = (Build.MANUFACTURER ?: "").trim()
    val isZebra get() = brand.contains("zebra", true) || brand.contains("symbol", true)
    val isHoneywell get() = brand.contains("honeywell", true) || brand.contains("intermec", true)

    /** Honeywell: take the scanner and ask for a data intent on our ACTION (no keystrokes). */
    fun claim(ctx: Context) {
        if (!isHoneywell) return
        val props = Bundle().apply {
            putBoolean("DPR_DATA_INTENT", true)
            putString("DPR_DATA_INTENT_ACTION", DataWedge.ACTION)
            putBoolean("DPR_WEDGE", false)
            putBoolean("TRIG_ENABLE", true)
        }
        runCatching {
            ctx.sendBroadcast(Intent(HW_CLAIM).setPackage(HW_PKG)
                .putExtra("com.honeywell.aidc.extra.EXTRA_SCANNER", "dcs.scanner.imager")
                .putExtra("com.honeywell.aidc.extra.EXTRA_PROFILE", "DEFAULT")
                .putExtra("com.honeywell.aidc.extra.EXTRA_PROPERTIES", props))
        }
    }

    /** Honeywell: give the scanner back when the app goes to the background. */
    fun release(ctx: Context) {
        if (!isHoneywell) return
        runCatching { ctx.sendBroadcast(Intent(HW_RELEASE).setPackage(HW_PKG)) }
    }
}
