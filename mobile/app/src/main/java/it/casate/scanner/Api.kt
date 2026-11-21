package it.casate.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ApiError(val status: Int, val code: String, message: String) : Exception(message)

/** Talks to the booking platform server (/api/scanner/...) and keeps the device key and the catalog on the device. */
class Api(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("scanner", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString("url", v.trim().trimEnd('/')).apply()
    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()
    var deviceName: String
        get() = prefs.getString("device", "") ?: ""
        set(v) = prefs.edit().putString("device", v).apply()
    var store: Int
        get() = prefs.getInt("store", -1)
        set(v) = prefs.edit().putInt("store", v).apply()
    var lastCheck: Long
        get() = prefs.getLong("lastCheck", 0)
        set(v) = prefs.edit().putLong("lastCheck", v).apply()

    val paired get() = !token.isNullOrEmpty()

    fun forget() {
        prefs.edit().remove("token").remove("device").remove("store").apply()
        catalogFile.delete()
    }

    private fun request(method: String, path: String, body: JSONObject? = null, etag: String? = null): Triple<Int, String, String?> {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("Accept", "application/json")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            etag?.let { c.setRequestProperty("If-None-Match", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else if (status == 304) null else c.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status >= 400) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty().ifEmpty { "HTTP $status" }
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw ApiError(status, code, msg.ifEmpty { code })
            }
            return Triple(status, text, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    /** Exchange the one-time code for the device key. */
    fun pair(code: String) {
        token = null
        val (_, text, _) = request("POST", "/api/scanner/pair", JSONObject().put("code", code))
        val o = JSONObject(text)
        token = o.getString("token")
        val d = o.getJSONObject("device")
        deviceName = d.optString("name")
        store = if (d.isNull("entityCode")) -1 else d.optInt("entityCode", -1)
    }

    /** Downloads the catalog when the server has a newer one. Returns true if it changed. */
    fun refreshCatalog(): Boolean {
        val etag = prefs.getString("etag", null)?.takeIf { catalogFile.exists() }
        val (status, text, newTag) = request("GET", "/api/scanner/catalog", etag = etag)
        lastCheck = System.currentTimeMillis()
        if (status == 304) return false
        val tmp = File(ctx.filesDir, "catalog.json.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        tmp.renameTo(catalogFile)
        prefs.edit().putString("etag", newTag).apply()
        return true
    }

    private val catalogFile get() = File(ctx.filesDir, "catalog.json")

    fun loadCatalog(): Catalog? {
        if (!catalogFile.exists()) return null
        return runCatching { parse(JSONObject(catalogFile.readText(Charsets.UTF_8))) }.getOrNull()
    }

    companion object {
        const val DEFAULT_URL = "https://prenota.casa-te.top"
        val STORES = mapOf(0 to "BMS", 1 to "FOIANO", 2 to "MARLIA", 3 to "S.FILIPPO", 4 to "S.MARCO", 5 to "S.CONCORDIO")

        fun parse(o: JSONObject): Catalog {
            val sources = o.optJSONArray("sources") ?: JSONArray()
            val directs = o.optJSONArray("directs") ?: JSONArray()
            val arr = o.optJSONArray("items") ?: JSONArray()
            val map = HashMap<String, Item>(arr.length() * 2)
            for (i in 0 until arr.length()) {
                val r = arr.optJSONArray(i) ?: continue
                val ean = r.optString(0)
                if (ean.isEmpty()) continue
                val w = r.optJSONArray(3)
                val names = ArrayList<String>()
                if (w != null) for (k in 0 until w.length()) sources.optString(w.optInt(k), "").takeIf { it.isNotEmpty() }?.let(names::add)
                val d = r.optInt(6, -1)
                map[ean] = Item(
                    ean = ean,
                    name = r.optString(1),
                    griglia = r.optInt(2),
                    wholesalers = names,
                    substituteEan = r.optString(4),
                    substituteName = r.optString(5),
                    direct = if (d >= 0) directs.optString(d, "") else "",
                )
            }
            return Catalog(o.optInt("id"), o.optString("week"), o.optString("publishedAt"), map)
        }
    }
}
