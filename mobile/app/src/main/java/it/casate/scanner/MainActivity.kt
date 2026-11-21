package it.casate.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * One screen app for the Zebra TC22: pair once with the code from the purchasing program, then scan.
 * Each scan shows at once (offline, from the downloaded catalog) whether the item can go in the
 * DETERSIVO list: in Griglia and available this week / substituted / not available / not in Griglia.
 */
class MainActivity : Activity() {
    private lateinit var api: Api
    private var catalog: Catalog? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tones: ToneGenerator? = null

    private data class Scan(val ean: String, val name: String, val verdict: Verdict, var count: Int)
    private val scans = ArrayList<Scan>()

    // scan screen views
    private var resultBox: LinearLayout? = null
    private var listBox: LinearLayout? = null
    private var summary: TextView? = null
    private var status: TextView? = null
    private var input: EditText? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
            onScan(data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(this)
        tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
        DataWedge.configure(this)
        if (api.paired) showScanner() else showPairing(null)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(DataWedge.ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
        // Check for a new catalog at most every 30 minutes (and always when there is none).
        if (api.paired && (catalog == null || System.currentTimeMillis() - api.lastCheck > 30 * 60_000)) refresh(false)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
    }

    override fun onDestroy() {
        tones?.release()
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int = 14, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) Color.WHITE else ACCENT)
        background = rounded(if (primary) ACCENT else Color.WHITE, 10, if (primary) 0 else LINE)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                   top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    // ------------------------------------------------------------------ pairing
    private fun showPairing(message: String?) {
        resultBox = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(CANVAS)
        }
        root.addView(text("CASA&TE Scanner", 24f, INK, true))
        root.addView(text("Collega questo dispositivo con il codice ricevuto dall'ufficio acquisti.", 15f, MUTED), lp(top = 6))
        if (message != null) root.addView(text(message, 14f, ERR), lp(top = 12))
        root.addView(text("Codice di collegamento", 13f, MUTED), lp(top = 22))
        val code = EditText(this).apply {
            hint = "ABCD-2345"
            textSize = 26f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            typeface = Typeface.MONOSPACE
            background = rounded(Color.WHITE, 10, LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(code, lp(top = 6))
        root.addView(text("Indirizzo del server", 13f, MUTED), lp(top = 16))
        val url = EditText(this).apply {
            setText(api.baseUrl)
            textSize = 15f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            background = rounded(Color.WHITE, 10, LINE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(url, lp(top = 6))
        val err = text("", 14f, ERR)
        root.addView(err, lp(top = 12))
        lateinit var go: Button
        go = button("Collega", primary = true) {
            val c = code.text.toString()
            if (c.filter { it.isLetterOrDigit() }.length != 8) {
                err.text = "Il codice ha 8 caratteri (es. ABCD-2345)."
                return@button
            }
            go.isEnabled = false
            err.text = "Collegamento in corso…"
            err.setTextColor(MUTED)
            api.baseUrl = url.text.toString().ifBlank { Api.DEFAULT_URL }
            io.execute {
                val e = runCatching { api.pair(c) }.exceptionOrNull()
                main.post {
                    if (e == null) {
                        hideKeyboard(code)
                        showScanner()
                        refresh(true)
                    } else {
                        go.isEnabled = true
                        err.setTextColor(ERR)
                        err.text = when ((e as? ApiError)?.status) {
                            404 -> "Codice non valido o scaduto: chiedere un nuovo codice all'ufficio acquisti."
                            429 -> "Troppi tentativi: riprovare tra qualche minuto."
                            null -> "Server non raggiungibile. Controllare il Wi-Fi e l'indirizzo del server."
                            else -> e.message ?: "Errore"
                        }
                    }
                }
            }
        }
        root.addView(go, lp(h = dp(52), top = 8))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ------------------------------------------------------------------ scanning
    private fun showScanner() {
        catalog = api.loadCatalog()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CANVAS)
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val store = Api.STORES[api.store]
        titles.addView(text(listOfNotNull("Lista DETERSIVO", store).joinToString(" · "), 19f, INK, true))
        status = text("", 12f, MUTED)
        titles.addView(status)
        head.addView(titles, lp(w = 0, weight = 1f))
        head.addView(button("⋯") { menu() }, LinearLayout.LayoutParams(dp(52), dp(44)))
        root.addView(head)

        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(resultBox, lp(top = 12))

        input = EditText(this).apply {
            hint = "Scansiona o scrivi il codice a barre"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 16f
            background = rounded(Color.WHITE, 10, LINE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { v, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) {
                    val s = v.text.toString()
                    v.text = ""
                    if (s.isNotBlank()) onScan(s)
                }
                enter
            }
        }
        root.addView(input, lp(top = 10))

        val listHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        summary = text("", 13f, MUTED, true)
        listHead.addView(summary, lp(w = 0, weight = 1f))
        listHead.addView(button("Svuota") { confirmClear() }, LinearLayout.LayoutParams(dp(96), dp(40)))
        root.addView(listHead, lp(top = 14))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) }, lp(h = 0, top = 6, weight = 1f))
        setContentView(root)
        showIdle()
        renderList()
        updateStatus()
    }

    private fun showIdle() {
        val box = resultBox ?: return
        box.removeAllViews()
        box.background = rounded(Color.WHITE, 14, LINE)
        box.addView(text("Pronto", 26f, INK, true))
        box.addView(text(if (catalog == null) "Catalogo non ancora scaricato: serve il Wi-Fi." else "Premi il grilletto e scansiona un articolo.", 15f, MUTED), lp(top = 4))
    }

    private fun onScan(raw: String) {
        val cat = catalog
        val ean = raw.trim()
        if (resultBox == null) return
        if (cat == null) {
            beep(Tone.UNKNOWN)
            showResult(ean, "", Verdict(Tone.UNKNOWN, "CATALOGO MANCANTE", listOf("Collegarsi al Wi-Fi e premere ⋯ → Aggiorna catalogo.")))
            return
        }
        val item = cat.find(ean)
        val v = Catalog.judge(item, ean)
        beep(v.tone)
        val name = item?.name.orEmpty()
        showResult(ean, name, v)
        val key = item?.ean ?: Catalog.keys(ean).firstOrNull() ?: ean
        val old = scans.indexOfFirst { it.ean == key }
        if (old >= 0) {
            val s = scans.removeAt(old)
            s.count += 1
            scans.add(0, s)
        } else {
            scans.add(0, Scan(key, name, v, 1))
            if (scans.size > 300) scans.removeAt(scans.size - 1)
        }
        renderList()
    }

    private fun showResult(ean: String, name: String, v: Verdict) {
        val box = resultBox ?: return
        val (bg, fg) = colors(v.tone)
        box.removeAllViews()
        box.background = rounded(bg, 14)
        box.addView(text(v.title, 28f, fg, true))
        if (name.isNotEmpty()) box.addView(text(name, 17f, INK, true), lp(top = 6))
        box.addView(text("EAN $ean", 13f, MUTED).apply { typeface = Typeface.MONOSPACE }, lp(top = 2))
        v.lines.forEachIndexed { i, line ->
            box.addView(text(line, 15f, if (i == 0) fg else INK, v.tone == Tone.SUBSTITUTE && i == 1), lp(top = if (i == 0) 10 else 2))
        }
    }

    private fun renderList() {
        val box = listBox ?: return
        box.removeAllViews()
        val ok = scans.count { it.verdict.tone == Tone.OK || it.verdict.tone == Tone.SUBSTITUTE }
        summary?.text = if (scans.isEmpty()) "Nessun articolo scansionato" else
            "${scans.size} articoli · $ok da ordinare · ${scans.size - ok} da controllare"
        for (s in scans) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(Color.WHITE, 8, LINE)
            }
            val (bg, fg) = colors(s.verdict.tone)
            row.addView(View(this).apply { background = rounded(fg, 5) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            col.addView(text(s.name.ifEmpty { "EAN " + s.ean }, 14f, INK, true).apply { maxLines = 1 })
            col.addView(text(s.verdict.title + (if (s.count > 1) "  ·  ×${s.count}" else ""), 12f, fg))
            row.addView(col, lp(w = 0, weight = 1f))
            row.setOnClickListener { showResult(s.ean, s.name, s.verdict) }
            row.tag = bg
            box.addView(row, lp(top = 6))
        }
    }

    private fun confirmClear() {
        if (scans.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Svuotare l'elenco?")
            .setMessage("Vengono tolti ${scans.size} articoli scansionati da questo dispositivo.")
            .setPositiveButton("Svuota") { _, _ -> scans.clear(); renderList(); showIdle() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun menu() {
        val items = arrayOf("Aggiorna catalogo", "Scollega dispositivo")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> refresh(true)
                1 -> AlertDialog.Builder(this)
                    .setTitle("Scollegare il dispositivo?")
                    .setMessage("Per usarlo di nuovo servirà un nuovo codice dall'ufficio acquisti.")
                    .setPositiveButton("Scollega") { _, _ -> api.forget(); catalog = null; scans.clear(); showPairing(null) }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        }.show()
    }

    private fun refresh(manual: Boolean) {
        status?.text = "Aggiornamento catalogo…"
        io.execute {
            val r = runCatching { api.refreshCatalog() }
            val loaded = if (r.getOrNull() == true || catalog == null) api.loadCatalog() else catalog
            main.post {
                val e = r.exceptionOrNull()
                if (e is ApiError && e.status == 401) {
                    api.forget()
                    catalog = null
                    showPairing("Dispositivo scollegato dall'ufficio acquisti: inserire un nuovo codice.")
                    return@post
                }
                catalog = loaded
                if (resultBox != null && scans.isEmpty()) showIdle()
                updateStatus(if (e != null) (if (e is ApiError) "Catalogo non aggiornato (${e.code})" else "Offline: uso l'ultimo catalogo") else null)
                if (manual && e == null) beep(Tone.OK)
            }
        }
    }

    private fun updateStatus(extra: String? = null) {
        val c = catalog
        val parts = ArrayList<String>()
        if (c != null) {
            parts.add("Catalogo del " + shortDate(c.publishedAt))
            parts.add("${c.size} articoli")
        } else parts.add("Catalogo non scaricato")
        if (api.deviceName.isNotEmpty()) parts.add(api.deviceName)
        extra?.let { parts.add(it) }
        status?.text = parts.joinToString(" · ")
    }

    private fun shortDate(iso: String): String = runCatching {
        val p = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ITALY).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val d = p.parse(iso.take(19)) ?: return iso
        SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(d)
    }.getOrDefault(iso)

    private fun colors(t: Tone): Pair<Int, Int> = when (t) {
        Tone.OK -> Pair(0xFFDCFCE7.toInt(), 0xFF15803D.toInt())
        Tone.SUBSTITUTE -> Pair(0xFFDBEAFE.toInt(), 0xFF1D4ED8.toInt())
        Tone.WARN -> Pair(0xFFFEF3C7.toInt(), 0xFFB45309.toInt())
        Tone.STOP -> Pair(0xFFFEE2E2.toInt(), 0xFFB91C1C.toInt())
        Tone.UNKNOWN -> Pair(0xFFF1F5F9.toInt(), 0xFF475569.toInt())
    }

    private fun beep(t: Tone) {
        val g = tones ?: return
        when (t) {
            Tone.OK -> g.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            Tone.SUBSTITUTE, Tone.WARN -> g.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            Tone.STOP, Tone.UNKNOWN -> {
                g.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
                vibrate()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        val CANVAS = 0xFFF5F7FA.toInt()
        val INK = 0xFF0F172A.toInt()
        val MUTED = 0xFF64748B.toInt()
        val LINE = 0xFFE2E8F0.toInt()
        val ACCENT = 0xFF1D4ED8.toInt()
        val ERR = 0xFFB91C1C.toInt()
    }
}
