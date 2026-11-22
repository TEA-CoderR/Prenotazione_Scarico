package it.casate.scanner

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale
import java.util.concurrent.Executors

/**
 * CASA&TE Scanner for the Zebra TC22. The store scans the DETERSIVO items it wants; each scan shows at
 * once (offline, from the downloaded catalog) whether it can be ordered, the store's stock / sales /
 * prices, and puts it in this week's list of the store (shared by the store's scanners, sent to the
 * purchasing office before the weekly deadline).
 *
 * Screens: pairing → home (scan) → result of a scan → review & send → sent / what happened.
 */
class MainActivity : Activity() {
    private lateinit var api: Api
    private lateinit var ui: Ui
    private var catalog: Catalog? = null
    private lateinit var list: StoreList
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tones: ToneGenerator? = null

    private enum class Screen { PAIR, HOME, RESULT, REVIEW, LINES, SENT, COUNT }
    /** Where "back" goes from a scan result; what to redraw after a change. */
    private var returnTo: (() -> Unit)? = null
    private var redraw: () -> Unit = {}
    private var screen = Screen.PAIR
    private var offline = false
    private var syncing = false

    /** The scan being looked at: committed to the list by the next scan or "Aggiungi". */
    private class Pending(val scan: String, val verdict: Verdict, var boxes: Int, var remind: Boolean = true)
    private var pending: Pending? = null
    private var beam: ValueAnimator? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
            onScan(data)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (screen == Screen.HOME || screen == Screen.REVIEW || screen == Screen.SENT) sync(quiet = true)
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(this)
        ui = Ui(this)
        ui.images = Images(this)
        list = api.loadList()
        window.statusBarColor = C.NAVY
        tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
        DataWedge.configure(this)
        if (api.paired) {
            catalog = api.loadCatalog()
            useImageUrl()
            showHome()
        } else showPairing(null)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(DataWedge.ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
        if (api.paired) {
            if (catalog == null || System.currentTimeMillis() - api.lastCheck > 30 * 60_000) refreshCatalog(false)
            sync(quiet = true)
        }
        main.removeCallbacks(tick)
        main.postDelayed(tick, 60_000)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
        main.removeCallbacks(tick)
    }

    override fun onDestroy() {
        tones?.release()
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("Back key")
    override fun onBackPressed() {
        when (screen) {
            Screen.RESULT -> goBack()
            Screen.LINES -> linesBack()
            Screen.REVIEW, Screen.SENT -> showHome()
            Screen.COUNT -> { afterStock = null; val r = countReturn; countReturn = null; if (r != null) r() else showResult() }
            else -> super.onBackPressed()
        }
    }

    // ================================================================== common pieces
    private fun useImageUrl() {
        catalog?.imageUrl?.takeIf { it.contains("{ean}") }?.let { ui.images?.template = it }
    }

    /** "mer 12:00" */
    private fun shortDay(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(rome).format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ITALIAN))

    private val store get() = Api.STORES[api.store] ?: "—"

    private fun weekNo(week: String): String = runCatching {
        "sett. " + LocalDate.parse(week).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    }.getOrDefault("")

    private val rome = ZoneId.of("Europe/Rome")

    private fun dayTime(ms: Long, withDay: Boolean = true): String {
        val z = Instant.ofEpochMilli(ms).atZone(rome)
        val f = DateTimeFormatter.ofPattern(if (withDay) "EEE dd/MM · HH:mm" else "dd/MM HH:mm", Locale.ITALIAN)
        return z.format(f)
    }

    private fun countdown(deadline: Long): String {
        val left = (deadline - System.currentTimeMillis()) / 60_000
        if (left <= 0) return "chiusa"
        val d = left / (60 * 24)
        val h = (left / 60) % 24
        val m = left % 60
        return when {
            d > 0 -> "${d} g ${h} h"
            h > 0 -> "${h} h ${m} min"
            else -> "${m} min"
        }
    }

    private fun eur(v: Double?): String = if (v == null || v <= 0) "–" else "€ " + String.format(Locale.ITALY, "%.2f", v)
    private fun n(v: Double?): String = when {
        v == null -> "–"
        v == Math.floor(v) -> v.toLong().toString()
        else -> String.format(Locale.ITALY, "%.1f", v)
    }

    private fun colli(k: Int) = if (k == 1) "collo" else "colli"

    /** Navy top bar used by the scan screens. */
    private fun topBar(back: Boolean): View {
        val r = ui.row()
        r.setBackgroundColor(C.NAVY)
        r.setPadding(ui.dp(if (back) 8 else 16), 0, ui.dp(16), 0)
        if (back) {
            val b = ui.icon(Icons.BACK, Color.WHITE, 40, 2.4f).apply {
                setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                contentDescription = "Indietro"
            }
            ui.tap(b, 10, true) { onBackPressed() }
            r.addView(b, ui.fixed(40, 40))
        }
        val t = ui.text("", 15f, Color.WHITE, ui.bold)
        t.text = store
        val wk = list.server?.week?.let { weekNo(it) }.orEmpty()
        r.addView(t)
        if (wk.isNotEmpty()) r.addView(ui.text("  · $wk", 14f, C.ON_NAVY_MUTED))
        r.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
        r.addView(ui.num("${list.ordered.size} art · ${list.boxes} colli", 13f, C.ON_NAVY))
        r.layoutParams = ui.lp(h = ui.dp(52))
        return r
    }

    private fun page(): LinearLayout = ui.col().apply { setBackgroundColor(C.CANVAS) }

    private fun bottomBar(): LinearLayout = ui.col().apply {
        setBackgroundColor(C.CARD)
        setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(16))
    }

    private fun scroll(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }

    private fun show(root: View, s: Screen) {
        beam?.cancel()
        beam = null
        screen = s
        setContentView(root)
    }

    // ================================================================== pairing
    private fun showPairing(message: String?) {
        pending = null
        val root = ui.col()
        root.setBackgroundColor(C.NAVY)
        root.setPadding(ui.dp(24), ui.dp(40), ui.dp(24), ui.dp(24))
        val logo = ui.text("C&T", 20f, Color.WHITE, ui.bold).apply { gravity = Gravity.CENTER; background = ui.bg(C.BLUE, 18) }
        root.addView(logo, ui.fixed(64, 64))
        root.addView(ui.text("Collega lo scanner", 28f, Color.WHITE, ui.bold), ui.lp(top = 28))
        root.addView(ui.text("Inserisci il codice di 8 caratteri che ti ha dato l'ufficio acquisti.", 15f, C.ON_NAVY_MUTED), ui.lp(top = 8))
        val code = EditText(this).apply {
            hint = "ABCD-2345"
            setHintTextColor(0xFF3A4A6B.toInt())
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = ui.mono
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            background = ui.bg(C.NAVY2, 14, C.BLUE)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        }
        root.addView(code, ui.lp(top = 26))
        val err = ui.text(message ?: "", 14f, C.CORAL)
        root.addView(err, ui.lp(top = 12))
        val steps = ui.col(14)
        steps.background = ui.bg(C.NAVY2, 14)
        for ((i, s) in listOf("Ogni scanner ha il suo codice", "Vale 7 giorni, si usa una volta sola", "Dopo, catalogo e lista si aggiornano da soli").withIndex()) {
            val r = ui.row()
            r.addView(ui.num("${i + 1}", 14f, 0xFF8FB0FF.toInt()))
            r.addView(ui.text(s, 13f, C.ON_NAVY), ui.lp(w = ui.WRAP, start = 10))
            steps.addView(r, ui.lp(top = if (i == 0) 0 else 10))
        }
        root.addView(steps, ui.lp(top = 18))
        val url = EditText(this).apply {
            setText(api.baseUrl)
            setTextColor(C.ON_NAVY_MUTED)
            textSize = 13f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            background = ui.bg(C.NAVY, 10, C.NAVY_LINE)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        root.addView(View(this), ui.lp(h = 0, weight = 1f))
        root.addView(ui.text("Server", 12f, C.ON_NAVY_MUTED), ui.lp(top = 16))
        root.addView(url, ui.lp(top = 4))
        lateinit var go: TextView
        go = ui.button("Collega", C.BLUE) {
            val c = code.text.toString()
            if (c.count { it.isLetterOrDigit() } != 8) {
                err.text = "Il codice ha 8 caratteri (es. ABCD-2345)."
                return@button
            }
            go.isEnabled = false
            go.alpha = 0.6f
            err.setTextColor(C.ON_NAVY_MUTED)
            err.text = "Collegamento in corso…"
            api.baseUrl = url.text.toString().ifBlank { Api.DEFAULT_URL }
            io.execute {
                val e = runCatching { api.pair(c) }.exceptionOrNull()
                main.post {
                    if (e == null) {
                        hideKeyboard(code)
                        list = api.loadList()
                        showHome()
                        refreshCatalog(true)
                        sync(quiet = true)
                    } else {
                        go.isEnabled = true
                        go.alpha = 1f
                        err.setTextColor(C.CORAL)
                        err.text = when ((e as? ApiError)?.status) {
                            404 -> "Codice non valido o scaduto: chiedi un nuovo codice all'ufficio acquisti."
                            429 -> "Troppi tentativi: riprova tra qualche minuto."
                            null -> "Server non raggiungibile: controlla il Wi-Fi."
                            else -> e.message ?: "Errore"
                        }
                    }
                }
            }
        }
        root.addView(go, ui.lp(h = ui.dp(54), top = 14))
        show(scroll(root), Screen.PAIR)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ================================================================== home
    /** Shown once on the home screen after something was put in the list. */
    private var flash: String? = null
    private var searchBox: EditText? = null
    private var searchRun: Runnable? = null

    private fun showHome() {
        pending = null
        returnTo = null
        redraw = { showHome() }
        val root = page()
        val head = ui.col()
        head.setBackgroundColor(C.NAVY)
        head.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18))
        val top = ui.row()
        top.addView(ui.text("C&T", 12f, Color.WHITE, ui.bold).apply { gravity = Gravity.CENTER; background = ui.bg(C.BLUE, 10) }, ui.fixed(34, 34))
        val who = ui.col()
        who.addView(ui.text(store, 16f, Color.WHITE, ui.bold))
        who.addView(ui.text(api.deviceName, 12f, C.ON_NAVY_MUTED), ui.lp(top = 2))
        top.addView(who, ui.lp(w = 0, weight = 1f, start = 10))
        val pill = ui.row()
        pill.background = ui.bg(C.NAVY3, 999)
        pill.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        val waiting = list.pending.size
        pill.addView(ui.dot(if (offline) C.AMBER else C.MINT, 8))
        val st = when {
            offline && waiting > 0 -> "Offline · $waiting da inviare"
            offline -> "Offline"
            catalog != null -> "Catalogo " + catalogDate()
            else -> "Senza catalogo"
        }
        pill.addView(ui.text(st, 12f, C.ON_NAVY), ui.lp(w = ui.WRAP, start = 6))
        ui.tap(pill, 999, true) { menu() }
        pill.contentDescription = "Menu dello scanner"
        top.addView(pill, ui.lp(w = ui.WRAP))
        head.addView(top)

        // this week's list (tap = review; the numbers open the list)
        val card = ui.col(14)
        card.background = ui.bg(C.NAVY2, 16, C.NAVY_LINE)
        val v = list.server
        val r1 = ui.row()
        r1.addView(ui.text("LISTA DETERSIVI" + (v?.week?.let { " · " + weekNo(it).uppercase() } ?: ""), 12f, C.ON_NAVY_MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
        if (v != null) r1.addView(ui.text("chiude " + shortDay(v.deadline), 12f, C.YELLOW, ui.medium))
        card.addView(r1)
        val stats = ui.row()
        fun stat(value: String, label: String, color: Int, f: Filter) {
            val c = ui.col()
            c.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            c.addView(ui.num(value, 26f, color))
            c.addView(ui.text(label, 12f, C.ON_NAVY_MUTED), ui.lp(top = 2))
            ui.tap(c, 10, true) { showLines(f) { showHome() } }
            stats.addView(c, ui.lp(w = 0, weight = 1f))
        }
        stat("${list.ordered.size}", "articoli", Color.WHITE, Filter.ALL)
        stat("${list.boxes}", "colli", Color.WHITE, Filter.BOXES)
        stat("${list.urgent}", if (list.urgent == 1) "urgenza" else "urgenze", if (list.urgent > 0) C.CORAL else Color.WHITE, Filter.URGENT)
        card.addView(stats, ui.lp(top = 8))
        if (v != null) {
            // how much of the week is gone
            val f = ((System.currentTimeMillis() - (v.deadline - 7 * 86_400_000L)).toFloat() / (7 * 86_400_000L)).coerceIn(0.02f, 1f)
            val bar = ui.row()
            bar.background = ui.bg(C.NAVY_LINE, 3)
            bar.addView(View(this).apply { background = ui.bg(C.YELLOW, 3) }, ui.lp(w = 0, h = ui.dp(6), weight = f))
            bar.addView(View(this), ui.lp(w = 0, h = ui.dp(6), weight = 1f - f + 0.0001f))
            card.addView(bar, ui.lp(h = ui.dp(6), top = 10))
        }
        val line = when {
            v == null -> "Lista non ancora scaricata: serve il Wi-Fi"
            list.submitted && list.changedSinceSubmit -> "Inviata, poi modificata · inviala di nuovo"
            list.submitted -> "Inviata " + dayTime(v.list!!.submittedAt, false) + " · modificabile per " + countdown(v.deadline)
            else -> "Mancano " + countdown(v.deadline) + " · la lista si salva da sola"
        }
        card.addView(ui.text(line, 12f, if (list.submitted && !list.changedSinceSubmit) C.MINT else C.ON_NAVY_MUTED), ui.lp(top = 8))
        ui.tap(card, 16, true) { if (list.submitted && !list.changedSinceSubmit) showSent() else showReview() }
        head.addView(card, ui.lp(top = 16))
        root.addView(head)

        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(12))

        flash?.let { msg ->
            flash = null
            val b = ui.row()
            b.background = ui.bg(C.GREEN_SOFT, 14)
            b.setPadding(ui.dp(14), ui.dp(11), ui.dp(14), ui.dp(11))
            b.addView(ui.icon(Icons.CHECK, C.GREEN_INK, 18, 2.6f))
            b.addView(ui.text(msg, 14f, C.GREEN_INK, ui.bold).apply { maxLines = 2 }, ui.lp(w = 0, weight = 1f, start = 10))
            ui.tap(b, 14) { showLines(Filter.ALL) { showHome() } }
            body.addView(b, ui.lp(top = 0))
            b.alpha = 0f
            b.animate().alpha(1f).setDuration(200).start()
            body.addView(ui.spacer(12))
        }

        // reminders that are back
        val back = list.server?.reminders.orEmpty().filter { r -> catalog?.judge(r.ean)?.kind == Kind.ORDER && (list.line(r.ean)?.boxes ?: 0) == 0 }
        if (back.isNotEmpty()) {
            val b = ui.row()
            b.background = ui.bg(C.GREEN_SOFT, 14)
            b.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            b.addView(ui.icon(Icons.REFRESH, C.GREEN_INK, 18, 2.2f))
            b.addView(ui.text(if (back.size == 1) "${back[0].name.take(28)} è tornato disponibile" else "${back.size} articoli sono tornati disponibili", 14f, C.GREEN_INK, ui.bold), ui.lp(w = 0, weight = 1f, start = 10))
            b.addView(ui.icon(Icons.NEXT, C.GREEN_INK, 18, 2.2f))
            ui.tap(b, 14) { if (back.size == 1) openScan(back[0].ean, null) else showLines(Filter.REMIND) { showHome() } }
            body.addView(b)
            body.addView(ui.spacer(12))
        }

        // items to count later
        if (toCount.size > 0) {
            val b = ui.row()
            b.background = ui.bg(C.AMBER_SOFT, 14, C.AMBER_LINE)
            b.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            b.addView(ui.icon(Icons.LIST, C.AMBER_INK, 18, 2.2f))
            b.addView(ui.text("Da contare: ${toCount.size} " + (if (toCount.size == 1) "articolo" else "articoli"), 14f, C.AMBER_INK, ui.bold), ui.lp(w = 0, weight = 1f, start = 10))
            b.addView(ui.icon(Icons.NEXT, C.AMBER_INK, 18, 2.2f))
            ui.tap(b, 14) { showToCount { showHome() } }
            body.addView(b)
            body.addView(ui.spacer(12))
        }

        // scan target: corner marks + moving beam
        val target = FrameLayout(this)
        target.background = android.graphics.drawable.LayerDrawable(arrayOf(ui.bg(C.CARD, 20, C.LINE), Corners(C.BLUE, ui.dpf(3f), ui.dpf(26f), ui.dpf(18f), ui.dpf(6f))))
        val inner = ui.col()
        inner.gravity = Gravity.CENTER
        inner.addView(ui.icon(Icons.BARCODE, C.INK, 46, 1.6f), LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        inner.addView(ui.text(if (catalog == null) "Catalogo non ancora scaricato" else "Premi il tasto giallo", 17f, C.INK, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 8))
        inner.addView(ui.text(if (catalog == null) "Collegati al Wi-Fi: si scarica da solo" else "e inquadra il codice a barre", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        target.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val laser = FrameLayout(this)
        laser.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x002F6BFF, 0x552F6BFF, 0x002F6BFF))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(16)))
        laser.addView(View(this).apply { setBackgroundColor(C.BLUE) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(2), Gravity.CENTER_VERTICAL))
        val lpL = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(16), Gravity.TOP)
        lpL.setMargins(ui.dp(30), ui.dp(18), ui.dp(30), 0)
        target.addView(laser, lpL)
        if (catalog == null) ui.tap(target, 20) { refreshCatalog(true) }
        body.addView(target, ui.lp(h = ui.dp(178)))

        // code or name
        val sr = ui.row()
        sr.background = ui.bg(C.CARD, 14, C.LINE)
        sr.setPadding(ui.dp(12), 0, ui.dp(6), 0)
        sr.addView(ui.icon(Icons.SEARCH, C.MUTED, 18, 2f))
        val results = ui.col()
        val input = EditText(this).apply {
            hint = "Codice o nome dell'articolo"
            setHintTextColor(C.FAINT)
            setTextColor(C.INK)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 14f
            background = null
            setPadding(ui.dp(8), 0, ui.dp(4), 0)
            setOnEditorActionListener { tv, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) {
                    val s = tv.text.toString().trim()
                    if (s.isNotEmpty() && s.all { it.isDigit() } && s.length >= 6) {
                        tv.text = ""
                        hideKeyboard(tv)
                        onScan(s)
                    } else renderSearch(results, s)
                }
                enter
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) {
                    searchRun?.let { main.removeCallbacks(it) }
                    val q = e?.toString().orEmpty()
                    searchRun = Runnable { renderSearch(results, q) }.also { main.postDelayed(it, 220) }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        searchBox = input
        sr.addView(input, ui.lp(w = 0, h = ui.dp(46), weight = 1f))
        val clear = ui.icon(Icons.CLOSE, C.FAINT, 34, 2f).apply { setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9)); contentDescription = "Cancella" }
        ui.tap(clear, 999) { input.setText(""); hideKeyboard(input) }
        sr.addView(clear, ui.fixed(34, 34))
        body.addView(sr, ui.lp(h = ui.dp(48), top = 12))
        body.addView(results, ui.lp())

        // last lines put in the list
        val last = list.lines().take(4)
        if (last.isNotEmpty()) {
            val hr = ui.row()
            hr.addView(ui.text("ULTIMI IN LISTA", 12f, C.MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
            val all = ui.row()
            all.setPadding(ui.dp(8), ui.dp(6), ui.dp(4), ui.dp(6))
            all.addView(ui.text("Vedi tutti", 12f, C.BLUE, ui.bold))
            all.addView(ui.icon(Icons.NEXT, C.BLUE, 14, 2.4f))
            ui.tap(all, 8) { showLines(Filter.ALL) { showHome() } }
            hr.addView(all)
            body.addView(hr, ui.lp(top = 14))
            for ((i, l) in last.withIndex()) {
                val r = ui.row()
                r.setPadding(ui.dp(2), 0, ui.dp(2), 0)
                r.addView(ui.dot(kindColor(l.kind)))
                r.addView(ui.text(l.name.ifEmpty { l.ean }, 14f, C.INK, ui.medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.lp(w = 0, weight = 1f, start = 10))
                when (l.kind) {
                    "urgent" -> r.addView(ui.chip("URGENZA", C.RED_INK, C.RED_SOFT, 11f))
                    "remind" -> r.addView(ui.chip("DA RICORDARE", C.AMBER_INK, C.AMBER_SOFT, 11f))
                    else -> {
                        r.addView(ui.num("${l.boxes}", 14f))
                        r.addView(ui.text(" " + colli(l.boxes), 12f, C.MUTED))
                        packOf(l.ean)?.let { r.addView(ui.text(" · ${n(it * l.boxes)} pz", 12f, C.MUTED)) }
                    }
                }
                ui.tap(r, 8) { openScan(l.origEan ?: l.ean, null) }
                if (i > 0) body.addView(divider())
                body.addView(r, ui.lp(h = ui.dp(44), top = if (i == 0) 4 else 0))
            }
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))

        val bar = bottomBar()
        val label = if (list.submitted && !list.changedSinceSubmit) "Vedi la lista inviata" else "Rivedi e invia lista"
        bar.addView(ui.iconButton(label, Icons.ARROW, C.NAVY, after = true) { if (list.submitted && !list.changedSinceSubmit) showSent() else showReview() }, ui.lp(h = ui.dp(52)))
        root.addView(bar)
        show(root, Screen.HOME)

        // the beam goes up and down while the screen waits for a scan
        target.post {
            val range = (target.height - ui.dp(52)).toFloat().coerceAtLeast(1f)
            beam = ObjectAnimator.ofFloat(laser, "translationY", 0f, range).apply {
                duration = 1300
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    /** Search by name / code on the home screen: results under the box, tap = open it. */
    private fun renderSearch(box: LinearLayout, q: String) {
        box.removeAllViews()
        val t = q.trim()
        val cat = catalog ?: return
        if (t.length < 2 || (t.all { it.isDigit() } && t.length < 4)) return
        val found = cat.search(t, 25)
        val card = ui.col()
        card.background = ui.bg(C.CARD, 14, C.LINE)
        card.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
        if (found.isEmpty()) {
            card.addView(ui.text("Nessun articolo trovato", 13f, C.MUTED).apply { setPadding(ui.dp(4), ui.dp(12), 0, ui.dp(12)) })
        }
        for ((i, it) in found.withIndex()) {
            if (i > 0) card.addView(divider())
            val r = ui.row()
            r.setPadding(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(6))
            val k = Catalog.judgeItem(it).kind
            r.addView(ui.photo(it.ean, it.name, 40, kindFg(k)))
            val c = ui.col()
            c.addView(ui.text(it.name, 13f, C.INK, ui.bold).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            val sub = ui.row()
            sub.addView(ui.dot(kindFg(k), 8))
            sub.addView(ui.text(kindLabel(k) + " · " + it.ean, 11f, C.MUTED), ui.lp(w = ui.WRAP, start = 6))
            c.addView(sub, ui.lp(top = 3))
            r.addView(c, ui.lp(w = 0, weight = 1f, start = 10))
            r.addView(ui.icon(Icons.NEXT, C.FAINT, 16, 2.2f))
            ui.tap(r, 8) {
                searchBox?.let { s -> hideKeyboard(s) }
                openScan(it.ean, null)
            }
            card.addView(r)
        }
        if (found.size >= 25) card.addView(ui.text("Mostrati i primi 25: scrivi di più per restringere", 11f, C.FAINT).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(8)) })
        box.addView(card, ui.lp(top = 8))
    }

    private fun divider() = View(this).apply { setBackgroundColor(0xFFE6EAF1.toInt()) }.also { it.layoutParams = ui.lp(h = 1) }

    private fun kindColor(kind: String) = when (kind) {
        "sub" -> C.BLUE
        "urgent" -> C.RED
        "remind" -> C.AMBER
        else -> C.GREEN
    }

    private fun kindFg(k: Kind) = when (k) {
        Kind.ORDER -> C.GREEN
        Kind.SUBSTITUTE -> C.BLUE
        Kind.NOT_AVAILABLE -> C.AMBER
        Kind.URGENT_ONLY -> C.ORANGE
        Kind.DIRECT_ONLY -> C.RED
        Kind.UNKNOWN -> C.FAINT
    }

    private fun kindLabel(k: Kind) = when (k) {
        Kind.ORDER -> "Ordinabile"
        Kind.SUBSTITUTE -> "Sostituito"
        Kind.NOT_AVAILABLE -> "Non disponibile"
        Kind.URGENT_ONLY -> "Solo per urgenze"
        Kind.DIRECT_ONLY -> "Non in Griglia"
        Kind.UNKNOWN -> "Sconosciuto"
    }

    private fun catalogDate(): String = runCatching {
        dayTime(Instant.parse(catalog!!.publishedAt).toEpochMilli(), false).substring(0, 5)
    }.getOrDefault("")

    // ================================================================== scanning
    private fun onScan(raw: String) {
        if (screen == Screen.PAIR || screen == Screen.COUNT) return
        val cat = catalog
        if (cat == null) {
            beep(Kind.UNKNOWN)
            AlertDialog.Builder(this).setTitle("Catalogo mancante")
                .setMessage("Collega lo scanner al Wi-Fi: il catalogo si scarica da solo.")
                .setPositiveButton("Aggiorna ora") { _, _ -> refreshCatalog(true) }.setNegativeButton("Chiudi", null).show()
            return
        }
        val p = pending
        val key = Catalog.keys(raw).firstOrNull() ?: return
        if (screen == Screen.RESULT && p != null && Catalog.keys(p.scan).firstOrNull() == key) {
            // the same item again: one more box (still to be confirmed with the button)
            if (p.verdict.kind == Kind.ORDER || p.verdict.kind == Kind.SUBSTITUTE) {
                p.boxes += 1
                beep(Kind.ORDER)
                showResult()
            }
            return
        }
        // a new scan only looks: nothing goes in the list without "Aggiungi"
        val back = when (screen) {
            Screen.RESULT -> returnTo
            Screen.HOME -> null
            else -> redraw
        }
        openScan(raw, back)
    }

    private fun openScan(raw: String, back: (() -> Unit)?) {
        val cat = catalog ?: return
        val v = cat.judge(raw)
        val target = targetEan(v, raw)
        val inList = list.line(target)
        val info = cat.info(target)
        returnTo = back
        pending = Pending(raw, v, Catalog.startBoxes(inList?.boxes, info), remind = inList?.kind != "griglia")
        beep(v.kind)
        showResult()
    }

    /** Back to where the scan was opened from (home, the list…). */
    private fun goBack() {
        pending = null
        val r = returnTo
        returnTo = null
        if (r != null) r() else showHome()
    }

    /** The ean that goes in the list for this verdict (the substitute for a replaced item). */
    private fun targetEan(v: Verdict, raw: String): String =
        (if (v.kind == Kind.SUBSTITUTE) v.substitute?.ean else v.item?.ean) ?: (Catalog.keys(raw).firstOrNull() ?: raw)

    /** Puts the looked-at item in the list ("Aggiungi"); returns what to tell the store. */
    private fun commit(reason: String? = null): String? {
        val p = pending ?: return null
        pending = null
        val v = p.verdict
        val now = System.currentTimeMillis()
        val me = api.deviceName
        val line = when (v.kind) {
            Kind.ORDER -> Line(v.item!!.ean, v.item.name.take(120), p.boxes, "griglia", reason = reason?.take(120), updatedBy = me, updatedAt = now)
            Kind.SUBSTITUTE -> {
                val s = v.substitute!!
                Line(s.ean, s.name.take(120), p.boxes, "sub", reason = reason?.take(120), origEan = v.item?.ean, origName = v.item?.name?.take(120), updatedBy = me, updatedAt = now)
            }
            Kind.NOT_AVAILABLE -> if (p.remind) Line(v.item!!.ean, v.item.name.take(120), 0, "remind", updatedBy = me, updatedAt = now) else null
            else -> null
        } ?: run {
            // "remind" switched off for an item that was in the list as a reminder
            val old = v.item?.let { list.line(it.ean) }
            if (old != null && old.kind == "remind") change(old.copy(boxes = 0, kind = "griglia", updatedAt = now))
            return null
        }
        val old = list.line(line.ean)
        if (old != null && old.boxes == line.boxes && old.kind == line.kind) return null
        change(line)
        return if (line.kind == "remind") "Te lo ricordiamo: ${line.name.take(40)}"
        else "In lista: ${line.name.take(40)} · ${line.boxes} ${colli(line.boxes)}"
    }

    private fun change(line: Line) {
        list.set(line)
        api.savePending(list)
        sync(quiet = true)
    }

    // ================================================================== scan result
    private fun showResult() {
        val p = pending ?: return goBack()
        val v = p.verdict
        val cat = catalog
        val target = targetEan(v, p.scan)
        val item = if (v.kind == Kind.SUBSTITUTE) v.substitute else v.item
        val sysInfo = cat?.info(target)
        val rep = list.report(target)
        // the store reported a different stock: show and count with what it counted until the office corrects it
        val info = if (rep?.status == "open") sysInfo?.copy(stock = rep.counted.toDouble(), suspect = false) else sysInfo
        val pack = info?.pack?.takeIf { it > 0 } ?: item?.pack
        val inList = list.line(target)
        redraw = { showResult() }
        val root = page()
        root.addView(topBar(back = true))

        data class Hero(val bg: Int, val fg: Int, val sub: Int, val icon: Icons.Spec, val title: String, val line: String)
        val hero = when (v.kind) {
            Kind.ORDER -> Hero(C.GREEN, Color.WHITE, 0xFFD5F5E8.toInt(), Icons.CHECK, "ORDINABILE",
                "In Griglia · " + v.item!!.wholesalers.size + (if (v.item.wholesalers.size == 1) " grossista lo ha" else " grossisti lo hanno"))
            Kind.SUBSTITUTE -> Hero(C.BLUE, Color.WHITE, 0xFFDCE6FF.toInt(), Icons.SWAP, "SOSTITUITO", "Questo codice non c'è più · ordina il nuovo")
            Kind.NOT_AVAILABLE -> Hero(C.AMBER, 0xFF2B1A00.toInt(), 0xFF4A3300.toInt(), Icons.CLOCK, "NON DISPONIBILE", "In Griglia, ma questa settimana nessun grossista lo ha")
            Kind.URGENT_ONLY -> Hero(C.ORANGE, Color.WHITE, 0xFFFFE2CC.toInt(), Icons.BOLT, "SOLO PER URGENZE",
                (if (v.item!!.griglia == 2) "Tolto dalla Griglia" else "Non è in Griglia") + " · si può ordinare dal grossista solo se serve subito")
            Kind.DIRECT_ONLY -> Hero(C.RED, Color.WHITE, 0xFFFFD9D4.toInt(), Icons.BAN, "NON IN GRIGLIA", "Nessun grossista DETERSIVI lo ha")
            Kind.UNKNOWN -> Hero(0xFF475569.toInt(), Color.WHITE, 0xFFE2E8F0.toInt(), Icons.INFO, "SCONOSCIUTO", "Non è in Griglia né nei listini dei grossisti")
        }
        val h = ui.row()
        h.setBackgroundColor(hero.bg)
        h.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18))
        h.addView(ui.iconBadge(hero.icon, if (hero.fg == Color.WHITE) 0x2EFFFFFF else 0x59FFFFFF, hero.fg, 52, 28, 2.6f))
        val ht = ui.col()
        ht.addView(ui.text(hero.title, 24f, hero.fg, ui.bold))
        ht.addView(ui.text(hero.line, 13f, hero.sub), ui.lp(top = 4))
        h.addView(ht, ui.lp(w = 0, weight = 1f, start = 14))
        root.addView(h)
        h.alpha = 0f
        h.translationY = -ui.dpf(12f)
        h.animate().alpha(1f).translationY(0f).setDuration(180).start()

        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))

        // the product (for a substitute: the old one crossed out, then the new one with its own − +)
        if (v.kind == Kind.SUBSTITUTE) {
            val old = ui.row()
            old.background = ui.dashed(0xFFC4CCDA.toInt(), 0xFFEEF1F6.toInt(), 16)
            old.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            old.addView(ui.photo(v.item!!.ean, v.item.name, 48, C.FAINT, faded = true))
            val oc = ui.col()
            oc.addView(ui.text("HAI SCANSIONATO · NON PIÙ IN VENDITA", 10f, C.MUTED, ui.bold))
            oc.addView(ui.struck(ui.text(v.item.name, 14f, C.MUTED, ui.medium)), ui.lp(top = 3))
            oc.addView(ui.num(v.item.ean, 12f, C.FAINT), ui.lp(top = 2))
            old.addView(oc, ui.lp(w = 0, weight = 1f, start = 12))
            body.addView(old)
            body.addView(ui.icon(Icons.DOWN, C.BLUE, 22, 2.4f), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(6); bottomMargin = ui.dp(6) })
            val s = v.substitute!!
            val nw = ui.col(14)
            nw.background = ui.bg(C.CARD, 18, C.BLUE, 2)
            nw.elevation = ui.dpf(3f)
            val nr = ui.row()
            nr.addView(ui.photo(s.ean, s.name, 56, C.BLUE, C.BLUE_SOFT))
            val nc = ui.col()
            nc.addView(ui.text("ORDINA QUESTO AL SUO POSTO", 10f, C.BLUE, ui.bold))
            nc.addView(ui.text(s.name, 15f, C.INK, ui.bold), ui.lp(top = 3))
            nc.addView(ui.num(s.ean + packText(pack), 12f, C.MUTED), ui.lp(top = 2))
            nr.addView(nc, ui.lp(w = 0, weight = 1f, start = 12))
            nw.addView(nr)
            val tags = ui.row()
            fun tag(t: String, fg: Int, fill: Int) = tags.addView(ui.chip(t, fg, fill), ui.lp(w = ui.WRAP, start = if (tags.childCount > 0) 6 else 0))
            if (s.wholesalers.isNotEmpty()) tag("${s.wholesalers.size} " + (if (s.wholesalers.size == 1) "grossista" else "grossisti"), C.GREEN_INK, C.GREEN_SOFT)
            info?.stock?.let { tag("In negozio ${n(it)} pz", C.MUTED, 0xFFEEF1F6.toInt()) }
            if (tags.childCount > 0) nw.addView(tags, ui.lp(top = 10))
            nw.addView(divider(), ui.lp(h = 1, top = 12))
            nw.addView(stepRow(p, pack, 52, 36f), ui.lp(top = 12))
            body.addView(nw)
        } else {
            val pr = ui.row()
            val name = v.item?.name?.ifEmpty { null } ?: "Articolo non in catalogo"
            pr.addView(ui.photo(v.item?.ean ?: p.scan, name, 64, if (v.kind == Kind.NOT_AVAILABLE) C.AMBER_INK else hero.bg))
            val pc = ui.col()
            pc.addView(ui.text(name, 16f, C.INK, ui.bold))
            val sub = ui.num((v.item?.ean ?: p.scan) + packText(pack), 12f, C.MUTED)
            pc.addView(sub, ui.lp(top = 3))
            if ((v.kind == Kind.URGENT_ONLY || v.kind == Kind.DIRECT_ONLY) && info != null && (info.stock ?: 0.0) <= 0) {
                pc.addView(ui.text("0 pz in negozio", 12f, C.RED_INK, ui.bold), ui.lp(top = 2))
            }
            pr.addView(pc, ui.lp(w = 0, weight = 1f, start = 12))
            body.addView(pr)
        }

        // this store's numbers
        if (info != null) {
            val tiles = ui.row()
            val cover = info.coverDays((info.transitBoxes ?: 0.0) * (pack ?: 0.0))
            val empty = (info.stock ?: 0.0) <= 0
            val four = v.kind == Kind.ORDER || v.kind == Kind.SUBSTITUTE
            fun add(t: View) = tiles.addView(t, ui.lp(w = 0, weight = 1f, start = if (tiles.childCount == 0) 0 else 6))
            val stockLabel = if (rep?.status == "open") "Contati" else "In negozio"
            add(if (empty) ui.tile(stockLabel, "0", "pz", C.RED_SOFT, 0xFFF7C6C0.toInt(), C.RED_INK, C.RED_INK) else ui.tile(stockLabel, n(info.stock), "pz"))
            add(ui.tile("Venduti 30g", n(info.sold30), "pz"))
            if (four) add(ui.tile("In arrivo", n(info.transitBoxes ?: 0.0), colli((info.transitBoxes ?: 0.0).toInt()), fg = if ((info.transitBoxes ?: 0.0) > 0) C.BLUE else C.INK))
            add(when {
                cover == null -> ui.tile("Basta per", "–")
                cover < 14 -> ui.tile("Basta per", "$cover", "gg", C.AMBER_SOFT, C.AMBER_LINE, C.AMBER_INK, C.AMBER_INK)
                else -> ui.tile("Basta per", "$cover", "gg")
            })
            body.addView(tiles, ui.lp(top = 14))
        } else if (v.kind != Kind.UNKNOWN) {
            val r = ui.row()
            r.background = ui.bg(0xFFEEF1F6.toInt(), 12)
            r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            r.addView(ui.icon(Icons.INFO, C.MUTED, 16, 2f))
            r.addView(ui.text("Giacenza e vendite di $store non ancora pubblicate dall'ufficio acquisti", 12f, C.MUTED), ui.lp(w = 0, weight = 1f, start = 8))
            body.addView(r, ui.lp(top = 14))
        }
        // prices: purchase (this week's cheapest wholesaler if the store has none) / sale / promotion
        val cost = info?.cost?.takeIf { it > 0 } ?: item?.bestPrice
        val costFrom = if (info?.cost?.takeIf { it > 0 } == null) item?.bestSource.orEmpty() else ""
        if ((cost ?: 0.0) > 0 || (info?.price ?: 0.0) > 0) {
            val pr = ui.row()
            pr.background = ui.bg(C.CARD, 12, C.LINE)
            pr.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
            pr.addView(ui.icon(Icons.TAG, C.MUTED, 16, 2f))
            pr.addView(ui.text("Acquisto ", 12f, C.MUTED), ui.lp(w = ui.WRAP, start = 8))
            pr.addView(ui.num(eur(cost), 13f))
            if (costFrom.isNotEmpty()) pr.addView(ui.text(" $costFrom", 11f, C.FAINT))
            if ((info?.price ?: 0.0) > 0) {
                pr.addView(ui.text("   Vendita ", 12f, C.MUTED))
                val sale = ui.num(eur(info!!.price), 13f)
                if ((info.promoPrice ?: 0.0) > 0) ui.struck(sale).setTextColor(C.FAINT)
                pr.addView(sale)
            }
            pr.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
            if ((info?.promoPrice ?: 0.0) > 0) {
                pr.addView(ui.chip("PROMO " + eur(info!!.promoPrice) + (if (info.promoEnd.isNotEmpty()) " · " + info.promoEnd else ""), Color.WHITE, C.RED, 11f))
            }
            body.addView(pr, ui.lp(top = 8))
        }
        if (sysInfo != null) stockCheck(body, target, item?.name ?: "", sysInfo, rep)

        // things worth knowing before ordering (tap: the list / the reason)
        fun note(icon: Icons.Spec, text: String, fg: Int, fill: Int, line: Int, onTap: (() -> Unit)? = null) {
            val r = ui.row()
            r.background = ui.bg(fill, 12, line)
            r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            r.addView(ui.icon(icon, fg, 18, 2.2f))
            r.addView(ui.text(text, 13f, fg), ui.lp(w = 0, weight = 1f, start = 10))
            if (onTap != null) {
                r.addView(ui.icon(Icons.NEXT, fg, 16, 2.2f))
                ui.tap(r, 12) { onTap() }
            }
            body.addView(r, ui.lp(top = 8))
        }
        if (inList != null && inList.boxes > 0 && (v.kind == Kind.ORDER || v.kind == Kind.SUBSTITUTE)) {
            val by = if (!inList.updatedBy.isNullOrEmpty() && inList.updatedBy != api.deviceName) " · da ${inList.updatedBy}" else ""
            note(Icons.LIST, "Già in lista: ${inList.boxes} ${colli(inList.boxes)}$by", C.BLUE_INK, C.BLUE_SOFT, 0xFFC6D5FF.toInt()) {
                val back = redraw
                showLines(Filter.ALL) { back() }
            }
        }
        if ((v.kind == Kind.ORDER || v.kind == Kind.SUBSTITUTE) && info != null) {
            if ((info.transitBoxes ?: 0.0) > 0) note(Icons.TRUCK, "${n(info.transitBoxes)} ${colli((info.transitBoxes ?: 0.0).toInt())} già in arrivo: tienine conto", C.BLUE_INK, C.BLUE_SOFT, 0xFFC6D5FF.toInt())
            if (Catalog.overstocked(info)) {
                val c = info.coverDays((info.transitBoxes ?: 0.0) * (pack ?: 0.0))
                note(Icons.ALERT, "Hai già ${n(info.stock)} pz" + (if (c != null) ": bastano ${Catalog.months(c)}" else " e non si vendono"), C.AMBER_INK, C.AMBER_SOFT, C.AMBER_LINE)
            }
        }

        if (v.kind == Kind.ORDER || v.kind == Kind.SUBSTITUTE || v.kind == Kind.URGENT_ONLY) lastWeek(body, target)
        when (v.kind) {
            Kind.ORDER -> body.addView(stepper(p, info, pack), ui.lp(top = 14))
            Kind.SUBSTITUTE -> {}
            Kind.NOT_AVAILABLE -> notAvailable(body, p, info)
            Kind.URGENT_ONLY -> howToOrder(body, v, info)
            Kind.DIRECT_ONLY, Kind.UNKNOWN -> {
                val d = v.item?.direct.orEmpty()
                val c = ui.row()
                c.background = ui.bg(C.CARD, 16, C.LINE)
                c.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                c.addView(ui.iconBadge(if (d.isNotEmpty()) Icons.TRUCK else Icons.INFO, if (d.isNotEmpty()) C.GREEN_SOFT else 0xFFEEF1F6.toInt(),
                    if (d.isNotEmpty()) C.GREEN_INK else C.MUTED, 40, 22, 2f))
                val t = ui.col()
                t.addView(ui.text(if (d.isNotEmpty()) "Da ordinare al fornitore diretto" else "Non si ordina nella lista DETERSIVI", 15f, C.INK, ui.bold))
                t.addView(ui.text(if (d.isNotEmpty()) "$d · con l'ordine diretto del negozio" else "Se serve davvero, chiedi all'ufficio acquisti", 13f, C.MUTED), ui.lp(top = 3))
                c.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
                body.addView(c, ui.lp(top = 14))
            }
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))

        // buttons: nothing goes in the list until one of them is pressed
        val bar = bottomBar()
        val done = { msg: String? -> flash = msg; goBack() }
        when (v.kind) {
            Kind.ORDER, Kind.SUBSTITUTE -> {
                val hint = ui.row(Gravity.CENTER)
                hint.addView(ui.icon(Icons.BARCODE, C.MUTED, 14, 2f))
                hint.addView(ui.text("Riscansiona lo stesso articolo: +1 collo", 12f, C.MUTED), ui.lp(w = ui.WRAP, start = 6))
                bar.addView(hint)
                val r = ui.row()
                val inIt = inList != null && inList.boxes > 0
                r.addView(ui.button(if (inIt) "Togli" else "Annulla", 0, if (inIt) C.RED_INK else C.INK, size = 15f) {
                    if (inIt) {
                        AlertDialog.Builder(this).setTitle("Togliere dalla lista?").setMessage(item?.name ?: target)
                            .setPositiveButton("Togli") { _, _ -> list.remove(target); api.savePending(list); sync(quiet = true); done("Tolto dalla lista: " + (item?.name ?: target).take(40)) }
                            .setNegativeButton("Annulla", null).show()
                    } else done(null)
                }, ui.fixed(100, 52))
                val label = when {
                    inIt && inList!!.boxes == p.boxes -> "Va bene così"
                    inIt -> "Cambia in ${p.boxes} ${colli(p.boxes)}"
                    v.kind == Kind.SUBSTITUTE -> "Ordina il sostituto · ${p.boxes}"
                    else -> "Aggiungi ${p.boxes} ${colli(p.boxes)}"
                }
                val icon = if (inIt && inList!!.boxes == p.boxes) Icons.CHECK else Icons.PLUS
                val ask = needsWhy(v, info, rep, p.boxes) && (!inIt || (Feedbacks.overOrder(info?.suggestBoxes, p.boxes) && !Feedbacks.overOrder(info?.suggestBoxes, inList!!.boxes)))
                r.addView(ui.iconButton(label, icon, if (v.kind == Kind.SUBSTITUTE) C.BLUE else C.GREEN) {
                    if (ask) return@iconButton whySheet(target, item?.name ?: target, info!!, sysInfo, p.boxes)
                    val msg = commit()
                    if (msg != null) beep(Kind.ORDER)
                    done(msg)
                }, ui.lp(w = 0, h = ui.dp(52), weight = 1f, start = 10))
                bar.addView(r, ui.lp(top = 10))
            }
            Kind.NOT_AVAILABLE -> bar.addView(ui.iconButton("OK · prossimo articolo", Icons.CHECK, C.NAVY) { done(commit()) }, ui.lp(h = ui.dp(52)))
            Kind.URGENT_ONLY -> {
                val urgent = list.line(target)?.takeIf { it.kind == "urgent" && it.boxes > 0 }
                if (urgent != null) {
                    bar.addView(ui.iconButton("Modifica l'urgenza", Icons.BOLT, C.ORANGE) { urgentSheet(v, info, urgent) }, ui.lp(h = ui.dp(52)))
                    bar.addView(ui.button("Togli dalla lista", 0, C.RED_INK, size = 15f) {
                        list.remove(target); api.savePending(list); sync(quiet = true); done("Urgenza tolta: " + (item?.name ?: target).take(40))
                    }, ui.lp(h = ui.dp(46), top = 8))
                } else {
                    bar.addView(ui.iconButton("Ordina come urgenza", Icons.BOLT, C.ORANGE) { urgentSheet(v, info, null) }, ui.lp(h = ui.dp(52)))
                    bar.addView(ui.button("Non serve · prossimo articolo", 0, C.INK, size = 15f) { done(null) }, ui.lp(h = ui.dp(46), top = 8))
                }
            }
            else -> bar.addView(ui.iconButton("OK · prossimo articolo", Icons.CHECK, C.NAVY) { done(null) }, ui.lp(h = ui.dp(52)))
        }
        root.addView(bar)
        show(root, Screen.RESULT)
    }

    // ================================================================== stock errors
    /** Under the store's numbers: the state of a stock report, or "the stock looks wrong", or a quiet way to report it. */
    private fun stockCheck(body: LinearLayout, ean: String, name: String, sys: StoreInfo?, rep: StockReport?) {
        sys ?: return
        val c = ui.col(14)
        val recount = { showCount(ean, name, sys) }
        val later = toCount.has(ean)
        fun twoButtons(fg: Int, line: Int) {
            val r = ui.row()
            r.addView(ui.button("Conta adesso", 0, fg, line, 14f) { recount() }, ui.lp(w = 0, h = ui.dp(42), weight = 1f))
            r.addView(ui.button(if (later) "Da contare ✓" else "Conta dopo", 0, fg, line, 14f) {
                if (later) { toCount.remove(ean); saveToCount() } else countLater(ean, name)
                redraw()
            }, ui.lp(w = 0, h = ui.dp(42), weight = 1f, start = 8))
            c.addView(r, ui.lp(top = 10))
        }
        when {
            rep?.status == "open" -> {
                c.background = ui.bg(C.BLUE_SOFT, 14, 0xFFC6D5FF.toInt())
                c.addView(ui.text("Giacenza segnalata · in attesa dell'ufficio", 14f, C.BLUE_INK, ui.bold))
                c.addView(ui.text("Contati ${rep.counted} pz da ${rep.countedBy} · a sistema ${n(rep.systemQty)} pz · " + dayTime(rep.createdAt, false), 12f, C.BLUE_INK), ui.lp(top = 4))
            }
            rep?.status == "fixed" -> {
                c.background = ui.bg(C.GREEN_SOFT, 14)
                c.addView(ui.text("✓ Giacenza corretta dall'ufficio acquisti", 14f, C.GREEN_INK, ui.bold))
                c.addView(ui.text("Era ${n(rep.systemQty)} pz, contati ${rep.counted} pz" + (if (rep.answer.isNotEmpty()) " · ${rep.answer}" else ""), 12f, C.GREEN_INK), ui.lp(top = 4))
            }
            rep?.status == "rejected" -> {
                c.background = ui.bg(C.RED_BG, 14, 0xFFF7C6C0.toInt())
                c.addView(ui.text("Segnalazione respinta", 14f, C.RED_INK, ui.bold))
                c.addView(ui.text(rep.answer.ifEmpty { "Chiedi all'ufficio acquisti" }, 12f, C.RED_INK), ui.lp(top = 4))
                twoButtons(C.RED_INK, C.RED)
            }
            sys.suspect && (sys.stock ?: 0.0) > 0 -> {
                c.background = ui.bg(C.AMBER_SOFT, 14, C.AMBER_LINE)
                c.addView(ui.text("! Giacenza da verificare", 14f, C.AMBER_INK, ui.bold))
                val d = sys.lastSaleDays
                c.addView(ui.text("A sistema ${n(sys.stock)} pz, ma " + (if (d != null) "nessuna vendita da $d giorni" else "non si vende da molto") +
                    ". Guarda sullo scaffale e in magazzino.", 12f, C.AMBER_INK), ui.lp(top = 4))
                twoButtons(C.AMBER_INK, C.AMBER)
            }
            else -> {
                val r = ui.row()
                if (later) {
                    r.addView(ui.chip("Da contare dopo", C.AMBER_INK, C.AMBER_SOFT, 12f), ui.lp(w = ui.WRAP))
                    r.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
                    r.addView(ui.button("Conta adesso", 0, C.AMBER_INK, size = 13f) { recount() }.apply { setPadding(ui.dp(14), 0, ui.dp(14), 0) },
                        ui.lp(w = ui.WRAP, h = ui.dp(36)))
                } else {
                    r.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
                    r.addView(ui.button("Giacenza sbagliata?", 0, C.MUTED, size = 13f) { countChoice(ean, name, sys) }.apply { setPadding(ui.dp(14), 0, ui.dp(14), 0) },
                        ui.lp(w = ui.WRAP, h = ui.dp(36)))
                }
                body.addView(r, ui.lp(top = 8))
                return
            }
        }
        if (rep?.status == "open" || rep?.status == "fixed") {
            val r = ui.row()
            r.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
            r.addView(ui.text("Conta di nuovo", 12f, if (rep.status == "open") C.BLUE_INK else C.GREEN_INK, ui.bold).apply {
                setPadding(0, ui.dp(8), 0, 0)
                setOnClickListener { recount() }
            })
            c.addView(r)
        }
        body.addView(c, ui.lp(top = 8))
    }

    /**
     * Count the real stock: pieces on the shelf + in the back room, who counted, both places ticked; then a
     * warning that the purchasing office will correct the stock, confirmed by holding the button.
     */
    private fun showCount(ean: String, name: String, sys: StoreInfo) {
        var counted = 0
        var shelf = false
        var backroom = false
        val pack = sys.pack?.takeIf { it > 0 }?.toInt()
        val root = page()
        root.addView(topBar(back = true))
        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
        body.addView(ui.text("CONTA LA GIACENZA", 12f, C.MUTED, ui.bold))
        body.addView(ui.text(name.ifEmpty { ean }, 17f, C.INK, ui.bold), ui.lp(top = 4))
        body.addView(ui.num("$ean · a sistema ${n(sys.stock)} pz", 12f, C.MUTED), ui.lp(top = 3))

        val numView = ui.num("0", 52f)
        val diff = ui.text("", 13f, C.MUTED)
        val nameEdit = EditText(this)
        lateinit var send: TextView
        lateinit var shelfBox: TextView
        lateinit var backBox: TextView
        fun ready() = shelf && backroom && nameEdit.text.toString().trim().length >= 2 && counted.toDouble() != Math.round(sys.stock ?: -1.0).toDouble()
        var putNum: () -> Unit = {}
        fun refresh() {
            numView.text = "$counted"
            putNum()
            val d = counted - (sys.stock ?: 0.0)
            diff.text = when {
                d == 0.0 -> "Uguale al sistema: niente da segnalare"
                d < 0 -> "${n(-d)} pz in meno del sistema"
                else -> "${n(d)} pz in più del sistema"
            }
            diff.setTextColor(if (d == 0.0) C.MUTED else if (d < 0) C.RED_INK else C.BLUE_INK)
            for ((box, on) in listOf(shelfBox to shelf, backBox to backroom)) {
                box.text = if (on) "✓" else ""
                box.background = if (on) ui.bg(C.NAVY, 6) else ui.bg(C.CARD, 6, C.LINE2, 2)
            }
            send.alpha = if (ready()) 1f else 0.45f
        }

        val card = ui.col(16)
        card.background = ui.bg(C.CARD, 18, C.LINE)
        card.addView(ui.text("Quanti pezzi ci sono davvero?", 14f, C.INK, ui.bold))
        card.addView(ui.text("Pezzi, non colli" + (if (pack != null) " · 1 collo = $pack pz" else ""), 12f, C.MUTED), ui.lp(top = 2))
        // hold = faster, tap the number = type it (long shelves: 37 pz is quicker to type than to tap)
        val numBox = FrameLayout(this)
        fun putCounter() {
            numBox.removeAllViews()
            numBox.addView(counter({ counted }, { counted = it }, 0, 64, 52f, "Quanti pezzi?") { refresh() })
        }
        putCounter()
        putNum = { putCounter() }
        card.addView(numBox, ui.lp(top = 12))
        val quick = ui.row()
        quick.addView(ui.button("Nessuno · 0", C.RED_SOFT, C.RED_INK, size = 14f) { counted = 0; refresh() }, ui.lp(w = 0, h = ui.dp(40), weight = 1f))
        if (pack != null && pack > 1) quick.addView(ui.button("+1 collo ($pack)", C.CANVAS, C.INK, C.LINE2, 14f) { counted += pack; refresh() }, ui.lp(w = 0, h = ui.dp(40), weight = 1f, start = 8))
        card.addView(quick, ui.lp(top = 12))
        card.addView(diff.apply { gravity = Gravity.CENTER }, ui.lp(top = 10))
        body.addView(card, ui.lp(top = 14))

        fun check(label: String, sub: String, onClick: () -> Unit): Pair<View, TextView> {
            val row = ui.row()
            row.background = ui.bg(C.CARD, 14, C.LINE)
            row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            val box = ui.text("", 16f, Color.WHITE, ui.bold).apply { gravity = Gravity.CENTER }
            row.addView(box, ui.fixed(26, 26))
            val t = ui.col()
            t.addView(ui.text(label, 14f, C.INK, ui.bold))
            t.addView(ui.text(sub, 12f, C.MUTED), ui.lp(top = 2))
            row.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
            row.setOnClickListener { onClick() }
            return row to box
        }
        val (s1, b1) = check("Ho contato lo scaffale", "Anche dietro e in alto") { shelf = !shelf; refresh() }
        val (s2, b2) = check("Ho controllato il magazzino", "Retro, scatoloni aperti, esposizioni") { backroom = !backroom; refresh() }
        shelfBox = b1
        backBox = b2
        body.addView(s1, ui.lp(top = 12))
        body.addView(s2, ui.lp(top = 8))

        body.addView(ui.text("Chi ha contato", 12f, C.MUTED, ui.bold), ui.lp(top = 14))
        nameEdit.apply {
            setText(api.counter)
            hint = "Nome e cognome"
            setSingleLine()
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            background = ui.bg(C.CARD, 12, C.LINE2)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        body.addView(nameEdit, ui.lp(top = 4))
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))

        val bar = bottomBar()
        send = ui.button("Segnala la giacenza", C.RED) {
            if (!ready()) {
                val why = when {
                    counted.toDouble() == Math.round(sys.stock ?: -1.0).toDouble() -> "Il conteggio è uguale al sistema: niente da segnalare."
                    !shelf || !backroom -> "Prima controlla scaffale e magazzino, poi spunta tutte e due."
                    else -> "Scrivi chi ha contato."
                }
                AlertDialog.Builder(this).setMessage(why).setPositiveButton("OK", null).show()
                return@button
            }
            hideKeyboard(nameEdit)
            confirmStock(ean, name, sys, counted, nameEdit.text.toString().trim())
        }
        bar.addView(send, ui.lp(h = ui.dp(54)))
        bar.addView(ui.text("L'ufficio acquisti corregge la giacenza nel gestionale", 12f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 8))
        root.addView(bar)
        refresh()
        show(root, Screen.COUNT)
    }

    /** Last warning: what will change, confirmed by holding the button for 2 seconds (no accidental taps). */
    private fun confirmStock(ean: String, name: String, sys: StoreInfo, counted: Int, by: String) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val c = ui.col()
        c.background = ui.bg(C.CARD, 22)
        c.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(18))
        c.addView(ui.badge("!", C.RED_SOFT, C.RED_INK, 52, 26f))
        c.addView(ui.text("Sei sicuro?", 22f, C.INK, ui.bold), ui.lp(top = 12))
        c.addView(ui.text("L'ufficio acquisti correggerà la giacenza di $store", 14f, C.MUTED), ui.lp(top = 6))
        val box = ui.row()
        box.background = ui.bg(C.CANVAS, 14)
        box.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        box.addView(ui.num(n(sys.stock), 28f, C.FAINT).apply { ui.struck(this) })
        box.addView(ui.text("  →  ", 20f, C.MUTED, ui.bold))
        box.addView(ui.num("$counted", 28f, C.RED_INK))
        box.addView(ui.text(" pz", 14f, C.MUTED), ui.lp(w = ui.WRAP))
        c.addView(box, ui.lp(top = 12))
        c.addView(ui.text(name.ifEmpty { ean }, 13f, C.INK, ui.medium), ui.lp(top = 8))
        c.addView(ui.text("Contato da $by · scaffale e magazzino", 12f, C.MUTED), ui.lp(top = 2))
        c.addView(ui.text("Se il conteggio è sbagliato, gli ordini del negozio saranno sbagliati.", 13f, C.RED_INK, ui.medium), ui.lp(top = 12))

        // hold to confirm
        val hold = FrameLayout(this)
        hold.background = ui.bg(C.RED, 14)
        val fill = View(this)
        fill.background = ui.bg(0xFF8F1D14.toInt(), 14)
        fill.pivotX = 0f
        fill.scaleX = 0f
        hold.addView(fill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val label = ui.text("Tieni premuto per confermare", 16f, Color.WHITE, ui.bold).apply { gravity = Gravity.CENTER }
        hold.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        hold.contentDescription = "Tieni premuto per confermare la segnalazione"
        var anim: ValueAnimator? = null
        var sent = false
        @Suppress("ClickableViewAccessibility")
        hold.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (sent) return@setOnTouchListener true
                    anim = ValueAnimator.ofFloat(fill.scaleX, 1f).apply {
                        duration = (2000 * (1f - fill.scaleX)).toLong().coerceAtLeast(1)
                        addUpdateListener { fill.scaleX = it.animatedValue as Float }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) {
                                if (fill.scaleX >= 0.999f && !sent) {
                                    sent = true
                                    label.text = "Invio in corso…"
                                    vibrate()
                                    sendStock(dlg, ean, name, sys, counted, by)
                                }
                            }
                        })
                        start()
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!sent) {
                        anim?.cancel()
                        fill.animate().scaleX(0f).setDuration(200).start()
                    }
                }
            }
            true
        }
        c.addView(hold, ui.lp(h = ui.dp(56), top = 16))
        c.addView(ui.button("Annulla", 0, C.INK, size = 15f) { dlg.dismiss() }, ui.lp(h = ui.dp(48), top = 10))
        dlg.setContentView(c)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.65f)
        }
        dlg.show()
    }

    private fun sendStock(dlg: Dialog, ean: String, name: String, sys: StoreInfo, counted: Int, by: String) {
        api.counter = by
        io.execute {
            val r = runCatching {
                api.reportStock(ean, name, sys.stock, counted, by)
                api.fetchList()
            }
            main.post {
                dlg.dismiss()
                val e = r.exceptionOrNull()
                if (e == null && toCount.remove(ean)) saveToCount()
                when {
                    e == null && afterStock != null -> {
                        list.applied(r.getOrThrow(), emptyList())
                        val cb = afterStock!!
                        afterStock = null
                        beep(Kind.ORDER)
                        cb(counted)
                    }
                    e == null -> {
                        list.applied(r.getOrThrow(), emptyList())
                        beep(Kind.ORDER)
                        AlertDialog.Builder(this).setTitle("Segnalazione inviata")
                            .setMessage("Grazie. L'ufficio acquisti correggerà la giacenza; vedrai qui quando è fatto.")
                            .setPositiveButton("OK", null).show()
                        if (pending != null) showResult() else showHome()
                    }
                    e is ApiError && e.status == 401 -> unpaired()
                    else -> AlertDialog.Builder(this).setTitle("Non inviata")
                        .setMessage(if (e is ApiError) (e.message ?: e.code) else "Serve la connessione: riprova quando c'è il Wi-Fi.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun packText(pack: Double?): String = pack?.takeIf { it > 0 }?.let { " · ${n(it)} pz/collo" } ?: ""

    /** − n colli + (big, the design's), used in the order card and in the substitute card. */
    /**
     * − n +  for any number: tap = ±1, hold = faster and faster (1 → 2 → 5 → 10 a step), tap the number =
     * type it. `live` runs on every step (only text changes), `done` once the finger is lifted / typed.
     */
    private fun counter(get: () -> Int, set: (Int) -> Unit, min: Int = 0, size: Int = 64, numSize: Float = 44f,
                        title: String = "Quanti colli?", sub: ((Int) -> String)? = null, done: () -> Unit): View {
        val r = ui.row()
        val num = ui.num("${get()}", numSize).apply { gravity = Gravity.CENTER }
        val subTv = sub?.let { ui.text(it(get()), 12f, C.MUTED).apply { gravity = Gravity.CENTER } }
        fun show() {
            num.text = "${get()}"
            subTv?.text = sub!!(get())
        }
        fun step(d: Int): Boolean {
            val nv = (get() + d).coerceAtLeast(min)
            if (nv == get()) return false
            set(nv)
            show()
            return true
        }
        fun key(spec: Icons.Spec, dir: Int, fill: Int, fg: Int, desc: String): View {
            val v = ui.icon(spec, fg, size, 2.2f).apply {
                val pd = ui.dp(size / 3)
                setPadding(pd, pd, pd, pd)
                background = ui.ripple(if (fill == C.NAVY) ui.bg(C.NAVY, 16) else ui.bg(fill, 16, C.LINE2), 16, fill == C.NAVY)
                contentDescription = desc
                isClickable = true
            }
            var n = 0
            val repeat = object : Runnable {
                override fun run() {
                    n++
                    val s = when { n > 24 -> 10; n > 14 -> 5; n > 6 -> 2; else -> 1 }
                    if (step(dir * s)) main.postDelayed(this, 110)
                }
            }
            @Suppress("ClickableViewAccessibility")
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.drawableHotspotChanged(e.x, e.y)
                        view.isPressed = true
                        n = 0
                        step(dir)
                        main.postDelayed(repeat, 450)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        main.removeCallbacks(repeat)
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) view.performClick()
                        done()
                    }
                }
                true
            }
            return v
        }
        r.addView(key(Icons.MINUS, -1, 0xFFF3F5F9.toInt(), C.INK, "Togli"), ui.fixed(size, size))
        val mid = ui.col()
        mid.gravity = Gravity.CENTER_HORIZONTAL
        mid.addView(num)
        subTv?.let { mid.addView(it, ui.lp(top = 2)) }
        mid.contentDescription = "Scrivi il numero"
        ui.tap(mid, 12) {
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText("${get()}")
                selectAll()
                textSize = 22f
                gravity = Gravity.CENTER
            }
            val d = AlertDialog.Builder(this).setTitle(title).setView(e)
                .setPositiveButton("OK") { _, _ ->
                    e.text.toString().toIntOrNull()?.let { set(it.coerceIn(min, 9999)); show(); done() }
                }.setNegativeButton("Annulla", null).create()
            d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            d.show()
            e.requestFocus()
        }
        r.addView(mid, ui.lp(w = 0, weight = 1f))
        r.addView(key(Icons.PLUS, 1, C.NAVY, Color.WHITE, "Aggiungi"), ui.fixed(size, size))
        return r
    }

    /** − n colli + of the scan result (the screen is redrawn when the finger is lifted). */
    private fun stepRow(p: Pending, pack: Double?, size: Int, numSize: Float): View =
        counter({ p.boxes }, { p.boxes = it }, 1, size, numSize,
            sub = { b -> colli(b) + (pack?.takeIf { it > 0 }?.let { " = ${n(it * b)} pz" } ?: "") }) { showResult() }

    /** QUANTI COLLI? card: the suggestion (tap = use it), − n +, how long it will last. */
    private fun stepper(p: Pending, info: StoreInfo?, pack: Double?): View {
        val c = ui.col(16)
        c.background = ui.bg(C.CARD, 18, C.LINE)
        val head = ui.row()
        head.addView(ui.text("QUANTI COLLI?", 13f, C.MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
        val sugg = info?.suggestBoxes ?: 0
        if (sugg > 0) {
            val chip = ui.row()
            chip.background = ui.bg(C.GREEN_SOFT, 999)
            chip.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
            chip.addView(ui.icon(Icons.SPARK, C.GREEN_INK, 13, 2.4f))
            chip.addView(ui.text("Consigliato $sugg", 12f, C.GREEN_INK, ui.bold), ui.lp(w = ui.WRAP, start = 5))
            chip.contentDescription = "Usa il consigliato: $sugg"
            ui.tap(chip, 999) { p.boxes = sugg; showResult() }
            head.addView(chip)
        } else if (info != null && info.suggestBoxes == 0) {
            head.addView(ui.chip("Il sistema non lo ordinerebbe", C.AMBER_INK, C.AMBER_SOFT, 11f))
        }
        val askWhy = needsWhy(p.verdict, info, list.report(targetEan(p.verdict, p.scan)), p.boxes) &&
            ((list.line(targetEan(p.verdict, p.scan))?.boxes ?: 0).let { had -> had == 0 || (Feedbacks.overOrder(info?.suggestBoxes, p.boxes) && !Feedbacks.overOrder(info?.suggestBoxes, had)) })
        c.addView(head)
        c.addView(stepRow(p, pack, 64, 44f), ui.lp(top = 14))
        if (askWhy) c.addView(ui.text("Se lo aggiungi ti chiediamo il motivo (un tocco)", 12f, C.AMBER_INK).apply { gravity = Gravity.CENTER }, ui.lp(top = 10))
        val withPack = info?.copy(pack = pack) ?: if (pack != null) StoreInfo(pack = pack) else null
        Catalog.coverAfter(withPack, p.boxes)?.let {
            c.addView(ui.text("Con ${p.boxes} ${colli(p.boxes)} arrivi a ~${Catalog.months(it)} di vendita", 12f, if (it > 120) C.AMBER_INK else C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 12))
        }
        return c
    }

    private fun notAvailable(body: LinearLayout, p: Pending, info: StoreInfo?) {
        val c = ui.col(14)
        c.background = ui.bg(C.CARD, 16, C.LINE)
        val d = p.verdict.item?.direct.orEmpty()
        val cover = info?.coverDays()
        fun line(icon: Icons.Spec, color: Int, text: CharSequence) {
            val r = ui.row(Gravity.TOP)
            r.addView(ui.icon(icon, color, 18, 2.2f))
            r.addView(ui.text(text, 14f, C.INK), ui.lp(w = 0, weight = 1f, start = 10))
            c.addView(r, ui.lp(top = if (c.childCount == 0) 0 else 10))
        }
        line(Icons.LIST, C.MUTED, "Nessuno dei grossisti DETERSIVI lo ha questa settimana")
        when {
            info == null -> {}
            (info.stock ?: 0.0) <= 0 -> line(Icons.ALERT, C.RED_INK, "Scaffale vuoto: l'ufficio acquisti cerca un'alternativa")
            cover != null && cover >= 21 -> line(Icons.CHECK, C.GREEN, "Hai scorta per $cover giorni: nessun problema")
            cover != null -> line(Icons.ALERT, C.AMBER_INK, "Hai scorta per $cover giorni")
        }
        if (d.isNotEmpty()) line(Icons.TRUCK, C.MUTED, "Si trova anche dal fornitore diretto $d")
        body.addView(c, ui.lp(top = 14))
        val r = ui.row()
        r.background = ui.bg(C.CARD, 16, if (p.remind) C.NAVY else C.LINE, if (p.remind) 2 else 1)
        r.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        r.addView(ui.checkbox(p.remind, 24))
        val t = ui.col()
        t.addView(ui.text("Ricordamelo quando torna", 14f, C.INK, ui.bold))
        t.addView(ui.text("Te lo riproponiamo nella prossima lista", 12f, C.MUTED), ui.lp(top = 2))
        r.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
        ui.tap(r, 16) { p.remind = !p.remind; showResult() }
        body.addView(r, ui.lp(top = 10))
    }

    /**
     * Not in Griglia, a wholesaler has it: one information card (no fake options) — normally from the
     * direct supplier, if it is urgent from the wholesaler with a reason. The action is the button below.
     */
    private fun howToOrder(body: LinearLayout, v: Verdict, info: StoreInfo?) {
        val it = v.item!!
        val d = it.direct
        val c = ui.col()
        c.background = ui.bg(C.CARD, 16, C.LINE)
        c.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        c.addView(ui.text("COME SI ORDINA", 12f, C.MUTED, ui.bold))
        fun step(icon: Icons.Spec, fg: Int, fill: Int, title: String, sub: String, top: Int) {
            val r = ui.row()
            r.addView(ui.iconBadge(icon, fill, fg, 40, 22, 2.2f))
            val t = ui.col()
            t.addView(ui.text(title, 15f, C.INK, ui.bold))
            t.addView(ui.text(sub, 13f, C.MUTED), ui.lp(top = 2))
            r.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
            c.addView(r, ui.lp(top = top))
        }
        step(Icons.TRUCK, C.GREEN_INK, C.GREEN_SOFT, "Di norma: " + (if (d.isNotEmpty()) "fornitore diretto $d" else "fornitore diretto"),
            "Con l'ordine diretto del negozio · costa meno", 10)
        c.addView(divider(), ui.lp(h = 1, top = 12))
        val price = it.bestPrice?.let { p -> " · " + eur(p) + "/pz" } ?: ""
        step(Icons.BOLT, C.ORANGE, C.ORANGE_SOFT, "Se serve subito: dal grossista",
            it.wholesalers.joinToString(", ") + price + " · serve un motivo", 12)
        if (info != null && (info.stock ?: 0.0) <= 0) {
            c.addView(ui.text("Lo scaffale è vuoto: puoi ordinarlo come urgenza", 12f, C.ORANGE, ui.bold), ui.lp(top = 10))
        }
        body.addView(c, ui.lp(top = 14))
    }

    /** Set while a stock count was opened from the "why" sheet: after the report the item goes in the list. */
    private var afterStock: ((Int) -> Unit)? = null

    /** Where Back goes from the count screen (the "to count" list), when not the product. */
    private var countReturn: (() -> Unit)? = null

    /** Items marked "count later" (kept on this scanner). */
    private val toCount: ToCount by lazy { ToCount(api.toCount) }
    private fun saveToCount() { api.toCount = toCount.save() }

    private fun countLater(ean: String, name: String) {
        toCount.add(ean, name, System.currentTimeMillis())
        saveToCount()
    }

    /** Pieces per box: this store's number, else the catalog's (Griglia, wholesaler or direct supplier). */
    private fun packOf(ean: String): Double? =
        catalog?.info(ean)?.pack?.takeIf { it > 0 } ?: catalog?.find(ean)?.pack?.takeIf { it > 0 }

    private fun piecesText(ean: String, boxes: Int): String =
        packOf(ean)?.let { "${n(it)} pz/collo · ${n(it * boxes)} pz" } ?: ""

    /** Count now (stops the order for a moment) or put it in the "to count" list and go on ordering. */
    private fun countChoice(ean: String, name: String, sys: StoreInfo) {
        AlertDialog.Builder(this).setTitle("Giacenza sbagliata?")
            .setItems(arrayOf("Conta adesso", "Conta dopo · continuo a ordinare")) { _, w ->
                if (w == 0) showCount(ean, name, sys) else {
                    countLater(ean, name)
                    android.widget.Toast.makeText(this, "Da contare dopo · ${toCount.size}", android.widget.Toast.LENGTH_SHORT).show()
                    redraw()
                }
            }.setNegativeButton("Annulla", null).show()
    }

    // ================================================================== to count later
    private fun showToCount(back: () -> Unit) {
        pending = null
        linesBack = back
        redraw = { showToCount(back) }
        val items = toCount.all()
        val root = page()
        root.addView(header("Da contare", "${items.size} articoli · contali tutti insieme", C.ON_NAVY_MUTED) { back() })
        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        if (items.isEmpty()) {
            val e = ui.col(24)
            e.gravity = Gravity.CENTER_HORIZONTAL
            e.background = ui.dashed(0xFFC4CCDA.toInt(), C.CARD, 16)
            e.addView(ui.text("Niente da contare", 16f, C.INK, ui.bold).apply { gravity = Gravity.CENTER })
            e.addView(ui.text("Durante l'ordine tocca «Giacenza sbagliata?» → Conta dopo", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
            body.addView(e)
        } else {
            val card = ui.col()
            card.background = ui.bg(C.CARD, 16, C.LINE)
            card.setPadding(ui.dp(10), ui.dp(2), ui.dp(8), ui.dp(2))
            for ((i, it) in items.withIndex()) {
                if (i > 0) card.addView(divider())
                val sys = catalog?.info(it.ean)
                val r = ui.row()
                r.setPadding(0, ui.dp(8), 0, ui.dp(8))
                r.addView(ui.photo(it.ean, it.name, 44, C.AMBER_INK))
                val c = ui.col()
                c.addView(ui.text(it.name.ifEmpty { it.ean }, 14f, C.INK, ui.medium).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                c.addView(ui.text((if (sys != null) "A sistema ${n(sys.stock)} pz" else "Dati del negozio non ancora pubblicati") +
                    " · segnato " + dayTime(it.at, false), 11f, C.MUTED), ui.lp(top = 2))
                r.addView(c, ui.lp(w = 0, weight = 1f, start = 10))
                val x = ui.icon(Icons.CLOSE, C.MUTED, 38, 2.2f).apply {
                    setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
                    background = ui.bg(C.CARD, 10, C.LINE2)
                    contentDescription = "Togli dalla lista da contare"
                }
                ui.tap(x, 10) { toCount.remove(it.ean); saveToCount(); redraw() }
                r.addView(x, ui.fixed(38, 38, 6))
                if (sys != null) {
                    r.addView(ui.button("Conta", C.AMBER, 0xFF2B1A00.toInt(), size = 14f) {
                        countReturn = { showToCount(back) }
                        afterStock = { counted ->
                            countReturn = null
                            android.widget.Toast.makeText(this, "Segnalato: $counted pz", android.widget.Toast.LENGTH_SHORT).show()
                            showToCount(back)
                        }
                        showCount(it.ean, it.name, sys)
                    }, ui.lp(w = ui.dp(84), h = ui.dp(38), start = 6))
                }
                card.addView(r)
            }
            body.addView(card)
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))
        show(root, Screen.LINES)
    }

    /**
     * The purchasing program would not order it (enough stock, already coming): the store knows better what
     * is on the shelf, so instead of the office checking it later the store says why — with one tap.
     */
    private fun needsWhy(v: Verdict, info: StoreInfo?, rep: StockReport?, boxes: Int): Boolean {
        if (v.kind != Kind.ORDER && v.kind != Kind.SUBSTITUTE) return false
        info ?: return false
        if (rep?.status == "open") return false          // stock already being corrected: the count is used
        return info.suggestBoxes == 0 || Catalog.overstocked(info) || Feedbacks.overOrder(info.suggestBoxes, boxes)
    }

    private fun whySheet(ean: String, name: String, info: StoreInfo, sys: StoreInfo?, boxes: Int) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = ui.col()
        sheet.background = ui.bg(C.CARD, 24)
        sheet.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(18))
        sheet.addView(View(this).apply { background = ui.bg(C.LINE2, 3) }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        sheet.addView(ui.text("Perché lo ordini?", 22f, C.INK, ui.bold), ui.lp(top = 14))
        val cover = info.coverDays((info.transitBoxes ?: 0.0) * (info.pack ?: 0.0))
        val why = when {
            (info.transitBoxes ?: 0.0) > 0 && (info.suggestBoxes ?: 1) == 0 -> "${n(info.transitBoxes)} ${colli((info.transitBoxes ?: 0.0).toInt())} già in arrivo"
            cover != null -> "A sistema ${n(info.stock)} pz: bastano ${Catalog.months(cover)}"
            else -> "A sistema ${n(info.stock)} pz e si vende poco"
        }
        val more = Feedbacks.overOrder(info.suggestBoxes, boxes)
        sheet.addView(ui.text(if (more) "Il sistema ne consiglia ${info.suggestBoxes}, tu ne metti $boxes. Lo vede l'ufficio acquisti."
            else "Il sistema non lo ordinerebbe · $why. Lo vede l'ufficio acquisti.", 13f, C.MUTED), ui.lp(top = 4))
        fun add(reason: String, before: String = "") {
            dlg.dismiss()
            api.whyUse = Feedbacks.countUse(api.whyUse, reason.substringBefore(" · "))
            val msg = commit(reason)
            beep(Kind.ORDER)
            flash = msg?.let { before + it }
            goBack()
        }
        fun option(icon: Icons.Spec, fg: Int, fill: Int, title: String, sub: String, onClick: () -> Unit) {
            val r = ui.row()
            r.background = ui.bg(C.CARD, 14, C.LINE2)
            r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            r.addView(ui.iconBadge(icon, fill, fg, 38, 20, 2.2f))
            val t = ui.col()
            t.addView(ui.text(title, 15f, C.INK, ui.bold))
            t.addView(ui.text(sub, 12f, C.MUTED), ui.lp(top = 1))
            r.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
            r.addView(ui.icon(Icons.NEXT, C.FAINT, 16, 2.2f))
            ui.tap(r, 14) { onClick() }
            sheet.addView(r, ui.lp(top = 8))
        }
        // the reasons this scanner uses most come first (in promotion weeks "Promozione" is on top)
        val opts = LinkedHashMap<String, () -> Unit>()
        if (sys != null) opts["Giacenza sbagliata"] = {
            option(Icons.ALERT, C.RED_INK, C.RED_SOFT, "La giacenza è sbagliata", "Ne ho meno: conta adesso o dopo") {
                AlertDialog.Builder(this).setTitle("La giacenza è sbagliata")
                    .setItems(arrayOf("Conta adesso e aggiungi", "Aggiungi ora · conta dopo")) { _, w ->
                        if (w == 0) {
                            dlg.dismiss()
                            afterStock = { counted -> add("Giacenza sbagliata · contati $counted pz", "Giacenza segnalata · ") }
                            showCount(ean, name, sys)
                        } else {
                            countLater(ean, name)
                            add("Giacenza da verificare · da contare", "Da contare dopo · ")
                        }
                    }.setNegativeButton("Annulla", null).show()
            }
        }
        opts["Promozione o volantino"] = { option(Icons.TAG, C.ORANGE, C.ORANGE_SOFT, "Promozione o volantino", "Ne servono di più per l'offerta") { add("Promozione o volantino") } }
        opts["Più spazio a scaffale"] = { option(Icons.BOX, C.BLUE_INK, C.BLUE_SOFT, "Più spazio a scaffale", "Nuova esposizione, testata, ripiano in più") { add("Più spazio a scaffale") } }
        opts["Richiesto da un cliente"] = { option(Icons.INFO, C.GREEN_INK, C.GREEN_SOFT, "L'ha chiesto un cliente", "Ordine o prenotazione di un cliente") { add("Richiesto da un cliente") } }
        for (k in Feedbacks.byUse(opts.keys.toList(), api.whyUse)) opts[k]!!()
        val other = EditText(this).apply {
            hint = "Altro motivo"
            setSingleLine()
            textSize = 15f
            background = ui.bg(C.CARD, 12, C.LINE2)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val orow = ui.row()
        orow.addView(other, ui.lp(w = 0, h = ui.dp(46), weight = 1f))
        orow.addView(ui.button("Aggiungi", C.NAVY, size = 14f) {
            val t = other.text.toString().trim()
            if (t.length >= 3) { hideKeyboard(other); add(t) } else other.error = "Scrivi il motivo"
        }, ui.lp(w = ui.dp(96), h = ui.dp(46), start = 8))
        sheet.addView(orow, ui.lp(top = 12))
        sheet.addView(ui.button("Annulla · non lo aggiungo", 0, C.INK, size = 15f) { dlg.dismiss() }, ui.lp(h = ui.dp(48), top = 12))
        val wrap = FrameLayout(this)
        wrap.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        dlg.setContentView(ScrollView(this).apply { addView(wrap) })
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.6f)
        }
        dlg.show()
    }

    /** Bottom sheet: why it is urgent and how many boxes. */
    private fun urgentSheet(v: Verdict, info: StoreInfo?, old: Line?) {
        val reasons = listOf("Scaffale vuoto", "Cliente in attesa", "Promozione in corso", "Il diretto arriva troppo tardi", "Altro")
        var reason = old?.reason ?: if ((info?.stock ?: 1.0) <= 0) "Scaffale vuoto" else ""
        var boxes = old?.boxes ?: 1
        var other: String = old?.reason?.takeIf { it !in reasons } ?: ""
        if (other.isNotEmpty()) reason = "Altro"
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = ui.col()
        sheet.background = ui.bg(C.CARD, 24)
        sheet.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(18))

        fun render() {
            sheet.removeAllViews()
            sheet.addView(View(this).apply { background = ui.bg(C.LINE2, 3) }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            sheet.addView(ui.text("Perché è urgente?", 22f, C.INK, ui.bold), ui.lp(top = 14))
            sheet.addView(ui.text("Lo vede l'ufficio acquisti", 13f, C.MUTED), ui.lp(top = 4))
            for (r in reasons) {
                val on = r == reason
                val row = ui.row()
                row.background = ui.bg(if (on) C.RED_BG else C.CARD, 14, if (on) C.RED else C.LINE2, if (on) 2 else 1)
                row.setPadding(ui.dp(14), 0, ui.dp(14), 0)
                row.addView(ui.text(if (on) "✓" else "", 13f, Color.WHITE, ui.bold).apply {
                    gravity = Gravity.CENTER
                    background = if (on) ui.circle(C.RED) else ui.bg(C.CARD, 999, 0xFFC4CCDA.toInt(), 2)
                }, ui.fixed(22, 22))
                row.addView(ui.text(r, 15f, C.INK, if (on) ui.bold else ui.medium), ui.lp(w = 0, weight = 1f, start = 12))
                if (r == "Scaffale vuoto" && (info?.stock ?: 1.0) <= 0) row.addView(ui.text("0 pz in negozio", 11f, C.RED_INK, ui.bold))
                row.setOnClickListener { reason = r; render() }
                sheet.addView(row, ui.lp(h = ui.dp(48), top = 8))
            }
            if (reason == "Altro") {
                val e = EditText(this).apply {
                    hint = "Scrivi il motivo"
                    setText(other)
                    setSingleLine()
                    textSize = 15f
                    background = ui.bg(C.CARD, 12, C.LINE2)
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) { other = s?.toString().orEmpty() }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                }
                sheet.addView(e, ui.lp(top = 8))
            }
            val q = ui.row()
            q.background = ui.bg(C.CANVAS, 14)
            q.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            q.addView(ui.text("Quanti colli", 14f, C.INK, ui.bold), ui.lp(w = 0, weight = 1f))
            q.addView(counter({ boxes }, { boxes = it }, 1, 44, 26f) { render() }, ui.lp(w = ui.dp(150)))
            sheet.addView(q, ui.lp(top = 14))
            val r = ui.row()
            r.addView(ui.button("Indietro", 0, C.INK, size = 15f) { dlg.dismiss() }, ui.fixed(100, 52))
            r.addView(ui.button(if (old != null) "Salva l'urgenza" else "Aggiungi come urgenza", C.RED) {
                val why = if (reason == "Altro") other.trim() else reason
                if (why.isEmpty()) return@button
                val item = v.item!!
                change(Line(item.ean, item.name.take(120), boxes, "urgent", reason = why.take(120), updatedBy = api.deviceName, updatedAt = System.currentTimeMillis()))
                dlg.dismiss()
                pending = null
                beep(Kind.ORDER)
                flash = "Urgenza in lista: ${item.name.take(40)} · $boxes ${colli(boxes)}"
                goBack()
            }.apply { alpha = if (reason.isEmpty()) 0.5f else 1f }, ui.lp(w = 0, h = ui.dp(52), weight = 1f, start = 10))
            sheet.addView(r, ui.lp(top = 16))
        }
        render()
        val wrap = FrameLayout(this)
        wrap.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        dlg.setContentView(ScrollView(this).apply { addView(wrap) })
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.6f)
        }
        dlg.show()
    }

    // ================================================================== review & send
    private class Check(val line: Line, val text: String, val level: Int) // 0 much stock · 1 coming · 2 not orderable

    /** Lines worth a second look before sending: much stock already, already coming, not orderable any more. */
    private fun checks(): List<Check> {
        val cat = catalog
        val out = ArrayList<Check>()
        for (l in list.lines().filter { it.boxes > 0 }) {
            val info = cat?.info(l.ean)
            val kind = cat?.judge(l.ean)?.kind
            when {
                l.kind != "urgent" && kind != null && kind != Kind.ORDER && kind != Kind.SUBSTITUTE ->
                    out.add(Check(l, "non più ordinabile questa settimana", 2))
                Catalog.overstocked(info) -> {
                    val c = info!!.coverDays((info.transitBoxes ?: 0.0) * (info.pack ?: 0.0))
                    out.add(Check(l, "hai già ${n(info.stock)} pz" + (if (c != null) ", bastano ${Catalog.months(c)}" else ", non si vendono") +
                        (l.reason?.let { " · motivo: $it" } ?: ""), 0))
                }
                (info?.transitBoxes ?: 0.0) > 0 -> out.add(Check(l, "${n(info!!.transitBoxes)} ${colli(info.transitBoxes!!.toInt())} già in arrivo", 1))
            }
        }
        return out
    }

    private enum class Filter(val title: String) {
        ALL("Articoli in lista"), BOXES("Colli in lista"), URGENT("Urgenze"), GRIGLIA("In Griglia"),
        SUB("Sostituiti"), REMIND("Da ricordare"), CHECK("Da controllare"), PREV("Settimana scorsa"),
    }

    private fun linesOf(f: Filter): List<Line> {
        val all = list.lines()
        return when (f) {
            Filter.ALL -> all.filter { it.boxes > 0 }
            Filter.BOXES -> all.filter { it.boxes > 0 }.sortedByDescending { it.boxes }
            Filter.URGENT -> all.filter { it.boxes > 0 && it.kind == "urgent" }
            Filter.GRIGLIA -> all.filter { it.boxes > 0 && (it.kind == "griglia" || it.kind == "other") }
            Filter.SUB -> all.filter { it.boxes > 0 && it.kind == "sub" }
            Filter.REMIND -> all.filter { it.kind == "remind" }
            Filter.CHECK -> checks().map { it.line }
            Filter.PREV -> list.server?.previous?.lines.orEmpty().filter { it.boxes > 0 }
        }
    }

    /** Navy header with a back arrow, a title and a line under it. */
    private fun header(title: String, sub: String?, subColor: Int, onBack: () -> Unit): LinearLayout {
        val head = ui.col()
        head.setBackgroundColor(C.NAVY)
        head.setPadding(ui.dp(14), ui.dp(12), ui.dp(18), ui.dp(14))
        val t = ui.row()
        val b = ui.icon(Icons.BACK, Color.WHITE, 36, 2.2f).apply {
            setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
            background = ui.bg(C.NAVY3, 10)
            contentDescription = "Indietro"
        }
        ui.tap(b, 10, true) { onBack() }
        t.addView(b, ui.fixed(36, 36))
        val tc = ui.col()
        tc.addView(ui.text(title, 18f, Color.WHITE, ui.bold))
        if (!sub.isNullOrEmpty()) tc.addView(ui.text(sub, 12f, subColor, ui.medium), ui.lp(top = 2))
        t.addView(tc, ui.lp(w = 0, weight = 1f, start = 10))
        head.addView(t)
        return head
    }

    private fun showReview() {
        pending = null
        returnTo = null
        redraw = { showReview() }
        val v = list.server
        val root = page()
        val head = header("Lista " + (v?.week?.let { weekNo(it) } ?: ""),
            v?.let { "chiude tra " + countdown(it.deadline) + " · " + shortDay(it.deadline) }, C.YELLOW) { showHome() }
        val stats = ui.row()
        for ((i, s) in listOf(
            Triple("${list.ordered.size}" to "articoli", Color.WHITE, Filter.ALL),
            Triple("${list.boxes}" to "colli", Color.WHITE, Filter.BOXES),
            Triple("${list.urgent}" to (if (list.urgent == 1) "urgenza" else "urgenze"), if (list.urgent > 0) C.CORAL else Color.WHITE, Filter.URGENT),
        ).withIndex()) {
            val c = ui.col()
            c.background = ui.bg(C.NAVY2, 12)
            c.setPadding(ui.dp(10), ui.dp(8), ui.dp(8), ui.dp(8))
            val top = ui.row()
            top.addView(ui.num(s.first.first, 20f, s.second), ui.lp(w = 0, weight = 1f))
            top.addView(ui.icon(Icons.NEXT, C.ON_NAVY_MUTED, 14, 2.2f))
            c.addView(top)
            c.addView(ui.text(s.first.second, 11f, C.ON_NAVY_MUTED), ui.lp(top = 2))
            c.contentDescription = "Vedi ${s.first.second}"
            ui.tap(c, 12, true) { showLines(s.third) { showReview() } }
            stats.addView(c, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 6))
        }
        head.addView(stats, ui.lp(top = 12))
        root.addView(head)

        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        val lines = list.lines()
        val chk = checks()
        if (toCount.size > 0) {
            val r = ui.row()
            r.background = ui.bg(C.AMBER_SOFT, 12, C.AMBER_LINE)
            r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            r.addView(ui.icon(Icons.LIST, C.AMBER_INK, 18, 2.2f))
            r.addView(ui.text("${toCount.size} da contare: conta prima di inviare, così l'ufficio usa la giacenza giusta", 13f, C.AMBER_INK, ui.bold),
                ui.lp(w = 0, weight = 1f, start = 10))
            r.addView(ui.icon(Icons.NEXT, C.AMBER_INK, 16, 2.2f))
            ui.tap(r, 12) { showToCount { showReview() } }
            body.addView(r)
            body.addView(ui.spacer(12))
        }
        if (chk.isNotEmpty()) {
            body.addView(ui.text("DA CONTROLLARE · ${chk.size}", 12f, C.AMBER_INK, ui.bold))
            for (c in chk) {
                val (fg, fill, line) = when (c.level) {
                    1 -> Triple(C.BLUE_INK, C.BLUE_SOFT, 0xFFC6D5FF.toInt())
                    2 -> Triple(C.RED_INK, C.RED_BG, 0xFFF7C6C0.toInt())
                    else -> Triple(C.AMBER_INK, C.AMBER_SOFT, C.AMBER_LINE)
                }
                val r = ui.row()
                r.background = ui.bg(fill, 12, line)
                r.setPadding(ui.dp(10), ui.dp(8), ui.dp(12), ui.dp(8))
                r.addView(ui.photo(c.line.ean, c.line.name, 36, fg))
                val tx = ui.col()
                tx.addView(ui.text(c.line.name.ifEmpty { c.line.ean }, 13f, C.INK, ui.bold).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                val sub = ui.row()
                sub.addView(ui.icon(if (c.level == 1) Icons.TRUCK else Icons.ALERT, fg, 13, 2.2f))
                sub.addView(ui.text(c.text, 12f, fg), ui.lp(w = 0, weight = 1f, start = 5))
                tx.addView(sub, ui.lp(top = 2))
                r.addView(tx, ui.lp(w = 0, weight = 1f, start = 10))
                r.addView(ui.num("${c.line.boxes}", 14f), ui.lp(w = ui.WRAP, start = 6))
                r.addView(ui.icon(Icons.NEXT, fg, 16, 2.2f), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginStart = ui.dp(4) })
                ui.tap(r, 12) { openScan(c.line.origEan ?: c.line.ean) { showReview() } }
                body.addView(r, ui.lp(top = 6))
            }
        }

        // groups: one row each, tap = the lines of that group
        data class G(val title: String, val note: String, val color: Int, val f: Filter, val lines: List<Line>)
        val urgentWhy = lines.filter { it.boxes > 0 && it.kind == "urgent" }.mapNotNull { it.reason }.distinct().take(2).joinToString(", ")
        val groups = listOf(
            G("In Griglia", "", C.GREEN, Filter.GRIGLIA, lines.filter { it.boxes > 0 && (it.kind == "griglia" || it.kind == "other") }),
            G("Sostituiti", "", C.BLUE, Filter.SUB, lines.filter { it.boxes > 0 && it.kind == "sub" }),
            G("Urgenze", urgentWhy, C.RED, Filter.URGENT, lines.filter { it.boxes > 0 && it.kind == "urgent" }),
            G("Da ricordare", "non disponibili", C.AMBER, Filter.REMIND, lines.filter { it.kind == "remind" }),
        ).filter { it.lines.isNotEmpty() }
        if (groups.isEmpty()) {
            val e = ui.col(24)
            e.gravity = Gravity.CENTER_HORIZONTAL
            e.background = ui.dashed(0xFFC4CCDA.toInt(), C.CARD, 16)
            e.addView(ui.icon(Icons.BARCODE, C.FAINT, 36, 1.6f), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            e.addView(ui.text("La lista è vuota", 17f, C.INK, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 8))
            e.addView(ui.text("Scansiona gli articoli da ordinare", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
            ui.tap(e, 16) { showHome() }
            body.addView(e, ui.lp(top = if (chk.isEmpty()) 0 else 14))
        } else {
            val card = ui.col()
            card.background = ui.bg(C.CARD, 16, C.LINE)
            card.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
            for ((i, g) in groups.withIndex()) {
                if (i > 0) card.addView(divider())
                val r = ui.row()
                r.setPadding(ui.dp(10), 0, ui.dp(6), 0)
                r.addView(ui.dot(g.color))
                val t = ui.row()
                t.addView(ui.text(g.title, 14f, if (g.f == Filter.REMIND) C.MUTED else C.INK, ui.bold))
                if (g.note.isNotEmpty()) t.addView(ui.text(" · " + g.note, 12f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                r.addView(t, ui.lp(w = 0, weight = 1f, start = 8))
                val boxes = g.lines.sumOf { it.boxes }
                r.addView(ui.num("${g.lines.size}", 13f))
                r.addView(ui.text(" art" + (if (boxes > 0) " · " else ""), 13f, C.MUTED))
                if (boxes > 0) { r.addView(ui.num("$boxes", 13f)); r.addView(ui.text(" " + colli(boxes), 13f, C.MUTED)) }
                r.addView(ui.icon(Icons.NEXT, C.FAINT, 16, 2.2f), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginStart = ui.dp(6) })
                ui.tap(r, 12) { showLines(g.f) { showReview() } }
                card.addView(r, ui.lp(h = ui.dp(48)))
            }
            body.addView(card, ui.lp(top = if (chk.isEmpty()) 0 else 14))
        }
        val last = list.fromLastWeek()
        if (last.isNotEmpty()) {
            val b = ui.row(Gravity.CENTER)
            b.background = ui.dashed(0xFFC4CCDA.toInt(), C.CARD, 12)
            b.addView(ui.icon(Icons.REFRESH, C.INK, 16, 2f))
            b.addView(ui.text("Aggiungi quelli della settimana scorsa (${last.size})", 14f, C.INK, ui.medium), ui.lp(w = ui.WRAP, start = 8))
            ui.tap(b, 12) {
                AlertDialog.Builder(this).setTitle("Aggiungere ${last.size} articoli?")
                    .setMessage("Gli articoli della lista della settimana scorsa, con gli stessi colli (le urgenze no). Poi puoi cambiarli.")
                    .setPositiveButton("Aggiungi") { _, _ ->
                        val now = System.currentTimeMillis()
                        for (l in last) list.set(l.copy(updatedBy = api.deviceName, updatedAt = now))
                        api.savePending(list)
                        sync(quiet = true)
                        showReview()
                    }.setNegativeButton("Annulla", null).show()
            }
            body.addView(b, ui.lp(h = ui.dp(46), top = 14))
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))

        val bar = bottomBar()
        val sent = list.submitted && !list.changedSinceSubmit
        if (sent) {
            bar.addView(ui.iconButton("Inviata · vedi com'è andata", Icons.CHECK, C.GREEN) { showSent() }, ui.lp(h = ui.dp(54)))
        } else {
            val label = if (list.submitted) "Invia di nuovo le modifiche" else "Invia all'ufficio acquisti"
            bar.addView(ui.iconButton(label, Icons.SEND, if (list.ordered.isEmpty()) C.LINE2 else C.BLUE) { if (list.ordered.isNotEmpty()) confirmSubmit() }, ui.lp(h = ui.dp(54)))
        }
        if (v != null) bar.addView(ui.text("Dopo l'invio puoi ancora modificarla fino a " + shortDay(v.deadline), 12f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 8))
        root.addView(bar)
        show(root, Screen.REVIEW)
    }

    private var linesBack: () -> Unit = { showHome() }

    /** The lines of one group / filter, each with its photo and − n +; tap = open the item. */
    private fun showLines(f: Filter, back: () -> Unit) {
        pending = null
        linesBack = back
        redraw = { showLines(f, back) }
        val rows = linesOf(f)
        val root = page()
        val prev = f == Filter.PREV
        val boxes = rows.sumOf { it.boxes }
        val sub = when {
            prev -> list.server?.previous?.week?.let { weekNo(it) }.orEmpty()
            else -> "${rows.size} articoli" + (if (boxes > 0) " · $boxes ${colli(boxes)}" else "")
        }
        root.addView(header(f.title, sub, C.ON_NAVY_MUTED) { back() })
        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        if (rows.isEmpty()) {
            val e = ui.col(24)
            e.gravity = Gravity.CENTER_HORIZONTAL
            e.background = ui.dashed(0xFFC4CCDA.toInt(), C.CARD, 16)
            e.addView(ui.text(if (f == Filter.URGENT) "Nessuna urgenza in lista" else "Nessun articolo", 16f, C.INK, ui.bold).apply { gravity = Gravity.CENTER })
            e.addView(ui.text("Scansiona un articolo per aggiungerlo", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
            body.addView(e)
        } else {
            val card = ui.col()
            card.background = ui.bg(C.CARD, 16, C.LINE)
            card.setPadding(ui.dp(10), ui.dp(2), ui.dp(8), ui.dp(2))
            val fb = list.server?.previous?.feedback
            for ((i, l) in rows.withIndex()) {
                if (i > 0) card.addView(divider())
                card.addView(if (prev) prevRow(l, fb) else lineRow(l))
            }
            body.addView(card)
            if (prev && fb != null && fb.deliveries.isNotEmpty()) {
                for (d in fb.deliveries) {
                    val dr = ui.row()
                    dr.addView(ui.icon(Icons.TRUCK, C.INK, 18, 2f))
                    dr.addView(ui.text("${d.first} · scarico ${d.second}", 13f, C.INK), ui.lp(w = 0, weight = 1f, start = 8))
                    body.addView(dr, ui.lp(top = 10))
                }
            }
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))
        if (!prev) {
            val bar = bottomBar()
            val r = ui.row()
            r.addView(ui.iconButton("Scansiona", Icons.BARCODE, 0, C.INK, size = 15f) { showHome() }, ui.lp(w = 0, h = ui.dp(50), weight = 1f))
            r.addView(ui.iconButton("Rivedi e invia", Icons.ARROW, C.NAVY, size = 15f, after = true) { showReview() }, ui.lp(w = 0, h = ui.dp(50), weight = 1f, start = 10))
            bar.addView(r)
            root.addView(bar)
        }
        show(root, Screen.LINES)
    }

    /** One line of the list: photo, name, reason / original, − boxes +. Tap = open it. */
    private fun lineRow(l: Line): View {
        val r = ui.row()
        r.setPadding(0, ui.dp(8), 0, ui.dp(8))
        val left = ui.row()
        left.addView(ui.photo(l.ean, l.name, 44, kindColor(l.kind)))
        val c = ui.col()
        c.addView(ui.text(l.name.ifEmpty { l.ean }, 14f, C.INK, ui.medium).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val sub = when (l.kind) {
            "sub" -> "al posto di " + (l.origName ?: l.origEan ?: "")
            "urgent" -> "Urgenza · " + (l.reason ?: "")
            "remind" -> "Te lo riproponiamo quando torna"
            else -> l.reason?.let { "Motivo · $it" } ?: l.ean
        } + (if (!l.updatedBy.isNullOrEmpty() && l.updatedBy != api.deviceName) " · ${l.updatedBy}" else "")
        c.addView(ui.text(sub, 11f, if (l.kind == "urgent") C.RED_INK else C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.lp(top = 2))
        if (l.kind != "remind") piecesText(l.ean, l.boxes).takeIf { it.isNotEmpty() }?.let { c.addView(ui.num(it, 11f, C.INK), ui.lp(top = 2)) }
        left.addView(c, ui.lp(w = 0, weight = 1f, start = 10))
        ui.tap(left, 10) { openScan(l.origEan ?: l.ean, redraw) }
        r.addView(left, ui.lp(w = 0, weight = 1f))
        fun small(icon: Icons.Spec, fill: Int, fg: Int, desc: String, onClick: () -> Unit): View {
            val b = ui.icon(icon, fg, 38, 2.2f).apply {
                setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
                background = if (fill == C.CARD) ui.bg(C.CARD, 10, C.LINE2) else ui.bg(fill, 10)
                contentDescription = desc
            }
            return ui.tap(b, 10, fill == C.NAVY) { onClick() }
        }
        if (l.kind == "remind") {
            r.addView(small(Icons.CLOSE, C.CARD, C.MUTED, "Togli") { list.remove(l.ean); api.savePending(list); sync(quiet = true); redraw() }, ui.fixed(38, 38, 6))
            return r
        }
        var nb = l.boxes
        val st = counter({ nb }, { nb = it }, 0, 38, 16f, l.name.ifEmpty { l.ean }) {
            if (nb == l.boxes) return@counter
            if (nb <= 0) {
                AlertDialog.Builder(this).setTitle("Togliere dalla lista?").setMessage(l.name)
                    .setPositiveButton("Togli") { _, _ -> list.remove(l.ean); api.savePending(list); sync(quiet = true); redraw() }
                    .setNegativeButton("Annulla") { _, _ -> redraw() }.show()
            } else {
                change(l.copy(boxes = nb, updatedBy = api.deviceName, updatedAt = System.currentTimeMillis()))
                redraw()
            }
        }
        r.addView(st, ui.lp(w = ui.dp(124), start = 6))
        return r
    }

    /** Last week: what the store asked and what the office ordered. */
    /** What the purchasing office did with this item last week (asked → ordered, and why). */
    private fun lastWeek(body: LinearLayout, ean: String) {
        val prev = list.server?.previous
        val l = Feedbacks.last(prev, ean) ?: return
        val got = l.got
        val (fg, fill) = when {
            got == null -> C.MUTED to 0xFFEEF1F6.toInt()
            got >= l.asked -> C.GREEN_INK to C.GREEN_SOFT
            got <= 0 -> C.RED_INK to C.RED_SOFT
            else -> C.AMBER_INK to C.AMBER_SOFT
        }
        val c = ui.row(Gravity.TOP)
        c.background = ui.bg(C.CARD, 16, C.LINE)
        c.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        c.addView(ui.iconBadge(if (got != null && got < l.asked) Icons.ALERT else Icons.CHECK, fill, fg, 34, 18, 2.2f))
        val t = ui.col()
        t.addView(ui.text("SETTIMANA SCORSA", 11f, C.MUTED, ui.bold))
        val what = when {
            got == null -> "Chiesti ${l.asked} ${colli(l.asked)} · in attesa dell'ufficio acquisti"
            got == l.asked -> "Chiesti ${l.asked} · ordinati ${got}"
            else -> "Chiesti ${l.asked} → ordinati $got"
        }
        t.addView(ui.text(what, 14f, fg, ui.bold), ui.lp(top = 2))
        if (l.note.isNotEmpty()) t.addView(ui.text("Ufficio acquisti: " + l.note, 13f, C.INK), ui.lp(top = 2))
        else if (l.reason.isNotEmpty() && got != null) t.addView(ui.text("Motivo · " + l.reason, 12f, C.MUTED), ui.lp(top = 2))
        c.addView(t, ui.lp(w = 0, weight = 1f, start = 10))
        ui.tap(c, 16) { val back = redraw; showLines(Filter.PREV) { back() } }
        body.addView(c, ui.lp(top = 14))
    }

    private fun prevRow(l: Line, fb: Feedback?): View {
        val r = ui.row()
        r.setPadding(0, ui.dp(8), 0, ui.dp(8))
        r.addView(ui.photo(l.ean, l.name, 40, kindColor(l.kind)))
        val c = ui.col()
        c.addView(ui.text(l.name.ifEmpty { l.ean }, 13f, C.INK, ui.medium).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        fb?.notes?.get(l.ean)?.let { c.addView(ui.text(it, 11f, C.MUTED), ui.lp(top = 2)) }
        r.addView(c, ui.lp(w = 0, weight = 1f, start = 10))
        val got = fb?.boxes?.get(l.ean)
        val (txt, color) = when {
            fb == null || got == null -> "${l.boxes}" to C.INK
            got >= l.boxes -> "${l.boxes}" to C.GREEN_INK
            got <= 0 -> "${l.boxes} → 0" to C.RED_INK
            else -> "${l.boxes} → $got" to C.AMBER_INK
        }
        r.addView(ui.num(txt, 14f, color), ui.lp(w = ui.WRAP, start = 8))
        ui.tap(r, 10) { openScan(l.ean, redraw) }
        return r
    }

    private fun confirmSubmit() {
        val v = list.server
        AlertDialog.Builder(this)
            .setTitle("Inviare la lista?")
            .setMessage("${list.ordered.size} articoli · ${list.boxes} colli" + (if (list.urgent > 0) " · ${list.urgent} urgenze" else "") +
                " all'ufficio acquisti." + (if (v != null) "\nPuoi ancora modificarla fino a " + dayTime(v.deadline) + "." else ""))
            .setPositiveButton("Invia") { _, _ -> submit() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun submit() {
        val wait = AlertDialog.Builder(this).setMessage("Invio in corso…").setCancelable(false).show()
        io.execute {
            val r = runCatching {
                val sent = list.pending.values.toList()
                if (sent.isNotEmpty()) {
                    val v = api.pushLines(sent)
                    main.post { list.applied(v, sent); api.savePending(list) }
                }
                api.submitList()
            }
            main.post {
                wait.dismiss()
                val e = r.exceptionOrNull()
                if (e == null) {
                    list.server = r.getOrNull()
                    offline = false
                    beep(Kind.ORDER)
                    showSent()
                } else if (e is ApiError && e.status == 401) unpaired()
                else AlertDialog.Builder(this).setTitle("Non inviata")
                    .setMessage(if (e is ApiError) (e.message ?: e.code) else "Nessuna connessione: la lista resta salvata, riprova quando c'è il Wi-Fi.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    // ================================================================== sent / what happened
    private fun showSent() {
        returnTo = null
        redraw = { showSent() }
        val v = list.server
        val cur = v?.list
        val root = page()
        val head = ui.col()
        head.setBackgroundColor(C.NAVY)
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.setPadding(ui.dp(18), ui.dp(24), ui.dp(18), ui.dp(20))
        val b = ui.iconBadge(Icons.CHECK, C.GREEN, Color.WHITE, 64, 34, 2.6f)
        head.addView(b)
        b.scaleX = 0.6f; b.scaleY = 0.6f
        b.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
        head.addView(ui.text(if (cur?.status == "ordered") "Lista ordinata" else "Lista inviata", 24f, Color.WHITE, ui.bold), ui.lp(w = ui.WRAP, top = 10))
        if (cur != null) head.addView(ui.text(dayTime(cur.submittedAt) + " · ${list.ordered.size} articoli · ${list.boxes} colli", 13f, C.ON_NAVY_MUTED), ui.lp(w = ui.WRAP, top = 4))
        ui.tap(head, 0, true) { showLines(Filter.ALL) { showSent() } }
        root.addView(head)

        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14))
        if (cur != null) body.addView(timeline(cur, v.deadline))
        val prev = v?.previous
        if (prev != null) {
            val c = ui.col(14)
            c.background = ui.bg(C.CARD, 16, C.LINE)
            val hr = ui.row()
            hr.addView(ui.text("SETTIMANA SCORSA · " + weekNo(prev.week).removePrefix("sett. "), 12f, C.MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
            val det = ui.row()
            det.setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4))
            det.addView(ui.text("Dettagli", 12f, C.BLUE, ui.bold))
            det.addView(ui.icon(Icons.NEXT, C.BLUE, 14, 2.4f))
            ui.tap(det, 8) { showLines(Filter.PREV) { showSent() } }
            hr.addView(det)
            c.addView(hr)
            val s = Feedbacks.summary(prev)
            if (s == null) {
                c.addView(ui.text(if (prev.receivedAt > 0) "L'ufficio acquisti la sta ordinando" else "Inviata, in attesa dell'ufficio acquisti", 14f, C.INK), ui.lp(top = 8))
            } else {
                val r = ui.row()
                for ((i, x) in listOf(Triple(s.ordered, "ordinati", C.GREEN_INK to C.GREEN_SOFT), Triple(s.reduced, "ridotti", C.AMBER_INK to C.AMBER_SOFT),
                    Triple(s.notOrdered, "non ordinati", C.RED_INK to C.RED_SOFT), Triple(s.substituted, "sostituiti", C.BLUE_INK to C.BLUE_SOFT)).withIndex()) {
                    val t = ui.col()
                    t.background = ui.bg(x.third.second, 10)
                    t.setPadding(ui.dp(8), ui.dp(7), ui.dp(6), ui.dp(7))
                    t.addView(ui.num("${x.first}", 18f, x.third.first))
                    t.addView(ui.text(x.second, 10f, x.third.first), ui.lp(top = 2))
                    ui.tap(t, 10) { showLines(Filter.PREV) { showSent() } }
                    r.addView(t, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 5))
                }
                c.addView(r, ui.lp(top = 10))
                for (d in prev.feedback!!.deliveries) {
                    val dr = ui.row()
                    dr.addView(ui.icon(Icons.TRUCK, C.INK, 18, 2f))
                    dr.addView(ui.text("${d.first} · scarico ${d.second}", 13f, C.INK), ui.lp(w = 0, weight = 1f, start = 8))
                    c.addView(dr, ui.lp(top = 10))
                }
            }
            body.addView(c, ui.lp(top = 14))
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))
        val bar = bottomBar()
        val r = ui.row()
        r.addView(ui.button("Modifica lista", 0, C.INK, size = 15f) { showReview() }, ui.lp(w = 0, h = ui.dp(52), weight = 1f))
        r.addView(ui.button("Fatto", C.NAVY, size = 15f) { showHome() }, ui.lp(w = 0, h = ui.dp(52), weight = 1f, start = 10))
        bar.addView(r)
        root.addView(bar)
        show(root, Screen.SENT)
    }

    private fun timeline(cur: ListState, deadline: Long): View {
        val c = ui.col()
        c.background = ui.bg(C.CARD, 16, C.LINE)
        c.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        data class Step(val title: String, val sub: String, val state: Int) // 2 done, 1 now, 0 later
        val ordered = cur.status == "ordered"
        val got = cur.receivedAt > 0 || ordered
        val steps = listOf(
            Step("Inviata", dayTime(cur.submittedAt, false) + (if (cur.submittedBy.isNotEmpty()) " · " + cur.submittedBy else ""), 2),
            Step("L'ufficio acquisti la controlla", if (got) "ricevuta" else "dopo " + dayTime(deadline), if (got) 2 else 1),
            Step("Ordinata ai grossisti", if (ordered) "vedi cosa è cambiato la prossima settimana" else "vedrai cosa è cambiato e perché", if (ordered) 2 else if (got) 1 else 0),
            Step("In consegna", "giorno e ora di scarico", 0),
        )
        for ((i, s) in steps.withIndex()) {
            val r = ui.row(Gravity.TOP)
            val rail = ui.col()
            rail.gravity = Gravity.CENTER_HORIZONTAL
            val d = View(this)
            d.background = when (s.state) {
                2 -> ui.circle(C.GREEN)
                1 -> ui.bg(C.CARD, 999, C.BLUE, 3)
                else -> ui.bg(C.CARD, 999, 0xFFC4CCDA.toInt(), 2)
            }
            rail.addView(d, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { topMargin = ui.dp(2) })
            if (i < steps.size - 1) rail.addView(View(this).apply { setBackgroundColor(if (s.state == 2) C.GREEN else C.LINE2) }, LinearLayout.LayoutParams(ui.dp(2), ui.dp(30)))
            r.addView(rail, ui.lp(w = ui.dp(14)))
            val t = ui.col()
            t.addView(ui.text(s.title, 14f, when (s.state) { 2 -> C.INK; 1 -> C.BLUE; else -> C.MUTED }, ui.bold))
            t.addView(ui.text(s.sub, 12f, if (s.state == 0) C.FAINT else C.MUTED), ui.lp(top = 2))
            r.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
            c.addView(r)
        }
        return c
    }

    // ================================================================== server
    /** Sends this scanner's changes and picks up the other scanners' ones. */
    private fun sync(quiet: Boolean) {
        if (!api.paired || syncing) return
        syncing = true
        val sent = list.pending.values.toList()
        io.execute {
            val r = runCatching { if (sent.isEmpty()) api.fetchList() else api.pushLines(sent) }
            main.post {
                syncing = false
                val e = r.exceptionOrNull()
                if (e is ApiError && e.status == 401) return@post unpaired()
                val was = offline
                if (e == null) {
                    list.applied(r.getOrThrow(), sent)
                    api.savePending(list)
                    offline = false
                    if (list.pending.isNotEmpty()) sync(true)
                } else {
                    offline = e !is ApiError
                    if (e is ApiError && e.status == 400) {
                        // the server refused these lines (shouldn't happen): drop them so the queue does not block
                        for (l in sent) list.pending.remove(l.ean)
                        api.savePending(list)
                    }
                }
                // redraw screens that show the list (never under the user's thumb on a scan result)
                val typing = searchBox?.text?.isNotEmpty() == true
                if (screen == Screen.HOME && !typing && (e == null || was != offline)) showHome()
                else if (screen == Screen.REVIEW && e == null && !quiet) showReview()
                else if (screen == Screen.LINES && e == null && !quiet) redraw()
                else if (screen == Screen.SENT && e == null) showSent()
            }
        }
    }

    private fun unpaired() {
        api.forget()
        catalog = null
        list = StoreList()
        showPairing("Scanner scollegato dall'ufficio acquisti: inserisci un nuovo codice.")
    }

    private fun refreshCatalog(manual: Boolean) {
        io.execute {
            val r = runCatching { api.refreshCatalog() }
            val loaded = if (r.getOrNull() == true || catalog == null) api.loadCatalog() else catalog
            main.post {
                val e = r.exceptionOrNull()
                if (e is ApiError && e.status == 401) return@post unpaired()
                val changed = loaded !== catalog
                catalog = loaded
                useImageUrl()
                if (screen == Screen.HOME && changed) showHome()
                if (manual) {
                    if (e == null) beep(Kind.ORDER)
                    AlertDialog.Builder(this).setMessage(when {
                        e == null && loaded != null -> "Catalogo aggiornato: ${loaded.size} articoli"
                        e is ApiError && e.status == 404 -> "L'ufficio acquisti non ha ancora pubblicato il catalogo."
                        else -> "Nessuna connessione: uso l'ultimo catalogo scaricato."
                    }).setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun menu() {
        val items = arrayOf("Aggiorna catalogo e lista", "Scollega questo scanner")
        AlertDialog.Builder(this).setTitle(api.deviceName.ifEmpty { "Scanner" }).setItems(items) { _, which ->
            when (which) {
                0 -> { refreshCatalog(true); sync(false) }
                1 -> AlertDialog.Builder(this)
                    .setTitle("Scollegare lo scanner?")
                    .setMessage("Per usarlo di nuovo servirà un nuovo codice dall'ufficio acquisti." +
                        (if (list.pending.isNotEmpty()) "\n${list.pending.size} modifiche non ancora inviate andranno perse." else ""))
                    .setPositiveButton("Scollega") { _, _ -> unpaired() }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        }.show()
    }

    // ================================================================== sound
    private fun beep(k: Kind) {
        val g = tones ?: return
        when (k) {
            Kind.ORDER -> g.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            Kind.SUBSTITUTE, Kind.NOT_AVAILABLE -> g.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
            else -> {
                g.startTone(ToneGenerator.TONE_SUP_ERROR, 380)
                vibrate()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
