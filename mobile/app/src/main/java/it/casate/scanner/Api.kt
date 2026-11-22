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
        // the first builds had a wrong default (casa-te.top): a saved old address is corrected on read
        get() = (prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL).replace("casa-te.top", "casaete.top")
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
        prefs.edit().remove("token").remove("device").remove("store").remove("list_view").remove("pending").remove("etag").apply()
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
        val etag = prefs.getString("etag", null)?.takeIf { catalogFile.exists() && it.contains("-") } // old tags (no store data): full download once
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

    // ------------------------------------------------------------------ the store's list

    /** The server's list as last seen, plus this scanner's unsent changes (kept across restarts). */
    fun loadList(): StoreList {
        val view = prefs.getString("list_view", null)?.let { t -> runCatching { parseView(JSONObject(t)) }.getOrNull() }
        val pending = LinkedHashMap<String, Line>()
        prefs.getString("pending", null)?.let { t ->
            runCatching {
                val a = JSONArray(t)
                for (i in 0 until a.length()) parseLine(a.getJSONObject(i)).let { pending[it.ean] = it }
            }
        }
        return StoreList(view, pending)
    }

    fun savePending(list: StoreList) {
        val a = JSONArray()
        for (l in list.pending.values) a.put(lineJson(l))
        prefs.edit().putString("pending", a.toString()).apply()
    }

    private fun keepView(text: String): ServerView {
        val v = parseView(JSONObject(text))
        prefs.edit().putString("list_view", text).apply()
        return v
    }

    fun fetchList(): ServerView = keepView(request("GET", "/api/scanner/list").second)

    /** Sends the queued changes; returns the server's list after them. */
    fun pushLines(lines: Collection<Line>): ServerView {
        val a = JSONArray()
        for (l in lines) a.put(lineJson(l))
        return keepView(request("PUT", "/api/scanner/list", JSONObject().put("lines", a)).second)
    }

    fun submitList(): ServerView = keepView(request("POST", "/api/scanner/list/submit", JSONObject()).second)

    /** Name of who counts stock on this scanner (asked once, then suggested). */
    var counter: String
        get() = prefs.getString("counter", "") ?: ""
        set(v) = prefs.edit().putString("counter", v.trim()).apply()

    /** Sends a stock error: the store counted `counted` pieces (shelf + back room); the system says `system`. */
    fun reportStock(ean: String, name: String, system: Double?, counted: Int, by: String): StockReport {
        val body = JSONObject().put("ean", ean).put("name", name.take(120)).put("systemQty", system ?: JSONObject.NULL)
            .put("counted", counted).put("countedBy", by).put("shelf", true).put("backroom", true)
        return parseReport(JSONObject(request("POST", "/api/scanner/stock-report", body).second))
    }

    fun loadCatalog(): Catalog? {
        if (!catalogFile.exists()) return null
        return runCatching { parse(JSONObject(catalogFile.readText(Charsets.UTF_8))) }.getOrNull()
    }

    companion object {
        const val DEFAULT_URL = "https://prenota.casaete.top"
        val STORES = mapOf(0 to "BMS", 1 to "FOIANO", 2 to "MARLIA", 3 to "S.FILIPPO", 4 to "S.MARCO", 5 to "S.CONCORDIO")

        private fun time(s: String?): Long =
            if (s.isNullOrEmpty() || s == "null") 0 else runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0)

        private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }

        fun lineJson(l: Line): JSONObject = JSONObject()
            .put("ean", l.ean).put("name", l.name).put("boxes", l.boxes).put("kind", l.kind)
            .put("reason", l.reason ?: JSONObject.NULL).put("origEan", l.origEan ?: JSONObject.NULL)
            .put("origName", l.origName ?: JSONObject.NULL)
            .put("updatedBy", l.updatedBy ?: JSONObject.NULL).put("at", l.updatedAt)

        fun parseLine(o: JSONObject) = Line(
            ean = o.optString("ean"),
            name = o.optString("name"),
            boxes = o.optInt("boxes"),
            kind = o.optString("kind", "griglia"),
            reason = str(o, "reason"),
            origEan = str(o, "origEan"),
            origName = str(o, "origName"),
            updatedBy = str(o, "updatedBy"),
            updatedAt = if (o.has("at")) o.optLong("at") else time(str(o, "updatedAt")),
        )

        private fun parseList(o: JSONObject?): ListState? {
            o ?: return null
            val lines = ArrayList<Line>()
            o.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) lines.add(parseLine(a.getJSONObject(i))) }
            val fb = o.optJSONObject("feedback")?.let { f ->
                val boxes = HashMap<String, Int>()
                val notes = HashMap<String, String>()
                f.optJSONObject("lines")?.let { m ->
                    for (k in m.keys()) {
                        val x = m.optJSONObject(k) ?: continue
                        boxes[k] = x.optDouble("boxes", 0.0).toInt()
                        x.optString("note").takeIf { it.isNotEmpty() }?.let { notes[k] = it }
                    }
                }
                val del = ArrayList<Pair<String, String>>()
                f.optJSONArray("deliveries")?.let { a ->
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { del.add(Pair(it.optString("supplier"), it.optString("when"))) }
                }
                Feedback(boxes, notes, del)
            }
            return ListState(
                id = o.optInt("id"), week = o.optString("week"), status = o.optString("status"),
                submittedAt = time(str(o, "submittedAt")), submittedBy = o.optString("submittedBy"),
                receivedAt = time(str(o, "receivedAt")), updatedAt = time(str(o, "updatedAt")),
                lines = lines, feedback = fb,
            )
        }

        fun parseView(o: JSONObject): ServerView {
            val rem = ArrayList<Line>()
            o.optJSONArray("reminders")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { rem.add(Line(it.optString("ean"), it.optString("name"), 0, "remind")) }
            }
            val reps = ArrayList<StockReport>()
            o.optJSONArray("stockReports")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { reps.add(parseReport(it)) }
            }
            return ServerView(
                week = o.optString("week"), deadline = time(o.optString("deadline")),
                list = parseList(o.optJSONObject("list")), previous = parseList(o.optJSONObject("previous")),
                reminders = rem, fetchedAt = System.currentTimeMillis(), stockReports = reps,
            )
        }

        fun parseReport(o: JSONObject) = StockReport(
            id = o.optInt("id"), ean = o.optString("ean"), name = o.optString("name"),
            systemQty = if (o.isNull("systemQty")) null else o.optDouble("systemQty"),
            counted = o.optInt("counted"), countedBy = o.optString("countedBy"), status = o.optString("status"),
            answer = str(o, "answer") ?: "", createdAt = time(str(o, "createdAt")), handledAt = time(str(o, "handledAt")),
        )

        private fun num(a: JSONArray, i: Int): Double? = if (i >= a.length() || a.isNull(i)) null else a.optDouble(i).takeIf { !it.isNaN() }

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
                    pack = num(r, 7)?.takeIf { it > 0 },
                    bestPrice = num(r, 8)?.takeIf { it > 0 },
                    bestSource = r.optInt(9, -1).let { k -> if (k >= 0) sources.optString(k, "") else "" },
                )
            }
            // This store's numbers: [ean, stock, sold30, transitBoxes, suggestBoxes, cost, price, promoPrice, promoEnd, pack]
            val st = o.optJSONArray("store") ?: JSONArray()
            val info = HashMap<String, StoreInfo>(st.length() * 2)
            for (i in 0 until st.length()) {
                val r = st.optJSONArray(i) ?: continue
                val ean = r.optString(0)
                if (ean.isEmpty()) continue
                info[ean] = StoreInfo(
                    stock = num(r, 1), sold30 = num(r, 2), transitBoxes = num(r, 3), suggestBoxes = num(r, 4)?.toInt(),
                    cost = num(r, 5), price = num(r, 6), promoPrice = num(r, 7),
                    promoEnd = if (r.length() > 8 && !r.isNull(8)) r.optString(8) else "", pack = num(r, 9),
                    lastSaleDays = num(r, 10)?.toInt(), suspect = (num(r, 11) ?: 0.0) > 0,
                )
            }
            return Catalog(o.optInt("id"), o.optString("week"), o.optString("publishedAt"), map, info, o.optString("imageUrl"))
        }
    }
}
