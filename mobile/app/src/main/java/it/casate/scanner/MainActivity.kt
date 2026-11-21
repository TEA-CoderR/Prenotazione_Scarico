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

    private enum class Screen { PAIR, HOME, RESULT, REVIEW, SENT }
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
        list = api.loadList()
        window.statusBarColor = C.NAVY
        tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
        DataWedge.configure(this)
        if (api.paired) {
            catalog = api.loadCatalog()
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
            Screen.RESULT -> { pending = null; showHome() }
            Screen.REVIEW, Screen.SENT -> showHome()
            else -> super.onBackPressed()
        }
    }

    // ================================================================== common pieces
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
            val b = ui.text("‹", 30f, Color.WHITE, ui.bold).apply {
                gravity = Gravity.CENTER
                contentDescription = "Indietro"
                setOnClickListener { onBackPressed() }
            }
            r.addView(b, ui.fixed(40, 48))
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
    private fun showHome() {
        pending = null
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
        pill.setOnClickListener { menu() }
        top.addView(pill, ui.lp(w = ui.WRAP))
        head.addView(top)

        // this week's list
        val card = ui.col(14)
        card.background = ui.bg(C.NAVY2, 16, C.NAVY_LINE)
        val v = list.server
        val r1 = ui.row()
        r1.addView(ui.text("LISTA DETERSIVI" + (v?.week?.let { " · " + weekNo(it).uppercase() } ?: ""), 12f, C.ON_NAVY_MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
        if (v != null) r1.addView(ui.text("chiude " + dayTime(v.deadline), 12f, C.YELLOW, ui.medium))
        card.addView(r1)
        val stats = ui.row()
        fun stat(value: String, label: String, color: Int) {
            val c = ui.col()
            c.addView(ui.num(value, 26f, color))
            c.addView(ui.text(label, 12f, C.ON_NAVY_MUTED), ui.lp(top = 2))
            stats.addView(c, ui.lp(w = 0, weight = 1f))
        }
        stat("${list.ordered.size}", "articoli", Color.WHITE)
        stat("${list.boxes}", "colli", Color.WHITE)
        stat("${list.urgent}", if (list.urgent == 1) "urgenza" else "urgenze", if (list.urgent > 0) C.CORAL else Color.WHITE)
        card.addView(stats, ui.lp(top = 12))
        val line = when {
            v == null -> "Lista non ancora scaricata: serve il Wi-Fi"
            list.submitted && list.changedSinceSubmit -> "Inviata, poi modificata · inviala di nuovo"
            list.submitted -> "Inviata " + dayTime(v.list!!.submittedAt, false) + " · modificabile per " + countdown(v.deadline)
            else -> "Mancano " + countdown(v.deadline) + " · la lista si salva da sola"
        }
        val lt = ui.text(line, 12f, if (list.submitted && !list.changedSinceSubmit) C.MINT else C.ON_NAVY_MUTED)
        card.addView(lt, ui.lp(top = 12))
        if (list.submitted) card.setOnClickListener { showSent() }
        head.addView(card, ui.lp(top = 16))
        root.addView(head)

        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(12))

        // reminders that are back
        val back = list.server?.reminders.orEmpty().filter { r -> catalog?.judge(r.ean)?.kind == Kind.ORDER && (list.line(r.ean)?.boxes ?: 0) == 0 }
        if (back.isNotEmpty()) {
            val b = ui.row()
            b.background = ui.bg(C.GREEN_SOFT, 14)
            b.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            b.addView(ui.text("↻", 18f, C.GREEN_INK, ui.bold))
            b.addView(ui.text(if (back.size == 1) "${back[0].name.take(28)} è tornato disponibile" else "${back.size} articoli sono tornati disponibili", 14f, C.GREEN_INK, ui.bold), ui.lp(w = 0, weight = 1f, start = 10))
            b.addView(ui.text("›", 22f, C.GREEN_INK, ui.bold))
            b.setOnClickListener { openScan(back[0].ean) }
            body.addView(b, ui.lp())
        }

        // scan target with a moving beam
        val target = FrameLayout(this)
        target.background = ui.dashed(0xFF9DB8FF.toInt(), C.CARD, 20)
        val inner = ui.col()
        inner.gravity = Gravity.CENTER
        inner.addView(ui.text("▮▯▮▮▯▮▯▮", 26f, C.INK, ui.mono).apply { gravity = Gravity.CENTER })
        inner.addView(ui.text(if (catalog == null) "Catalogo non ancora scaricato" else "Premi il tasto giallo", 17f, C.INK, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 10))
        inner.addView(ui.text(if (catalog == null) "Collegati al Wi-Fi: si scarica da solo" else "e inquadra il codice a barre", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        target.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val laser = View(this)
        laser.setBackgroundColor(C.BLUE)
        laser.elevation = ui.dpf(4f)
        val lpL = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(2), Gravity.TOP)
        lpL.setMargins(ui.dp(30), ui.dp(26), ui.dp(30), 0)
        target.addView(laser, lpL)
        body.addView(target, ui.lp(h = ui.dp(178), top = if (back.isNotEmpty()) 12 else 0))

        // type a code
        val input = EditText(this).apply {
            hint = "Codice a barre"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 15f
            background = ui.bg(C.CARD, 14, C.LINE)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            setOnEditorActionListener { tv, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) {
                    val s = tv.text.toString()
                    tv.text = ""
                    hideKeyboard(tv)
                    if (s.isNotBlank()) onScan(s)
                }
                enter
            }
        }
        body.addView(input, ui.lp(h = ui.dp(48), top = 12))

        // last lines
        val last = list.lines().take(4)
        if (last.isNotEmpty()) {
            body.addView(ui.text("ULTIMI IN LISTA", 12f, C.MUTED, ui.bold), ui.lp(top = 16))
            for ((i, l) in last.withIndex()) {
                val r = ui.row()
                r.addView(ui.dot(kindColor(l.kind)))
                r.addView(ui.text(l.name.ifEmpty { l.ean }, 14f, C.INK, ui.medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.lp(w = 0, weight = 1f, start = 10))
                when (l.kind) {
                    "urgent" -> r.addView(ui.chip("URGENZA", C.RED_INK, C.RED_SOFT, 11f))
                    "remind" -> r.addView(ui.chip("DA RICORDARE", C.AMBER_INK, C.AMBER_SOFT, 11f))
                    else -> {
                        r.addView(ui.num("${l.boxes}", 14f))
                        r.addView(ui.text(" " + colli(l.boxes), 12f, C.MUTED))
                    }
                }
                r.setOnClickListener { openScan(l.origEan ?: l.ean) }
                body.addView(r, ui.lp(h = ui.dp(42), top = if (i == 0) 6 else 0))
            }
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))

        val bar = bottomBar()
        val label = if (list.submitted && !list.changedSinceSubmit) "Vedi la lista inviata" else "Rivedi e invia lista"
        bar.addView(ui.button("$label  →", C.NAVY) { showReview() }, ui.lp(h = ui.dp(52)))
        root.addView(bar)
        show(root, Screen.HOME)

        // the beam goes up and down while the screen waits for a scan
        target.post {
            val range = (target.height - ui.dp(56)).toFloat().coerceAtLeast(1f)
            beam = ObjectAnimator.ofFloat(laser, "translationY", 0f, range).apply {
                duration = 1300
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun kindColor(kind: String) = when (kind) {
        "sub" -> C.BLUE
        "urgent" -> C.RED
        "remind" -> C.AMBER
        else -> C.GREEN
    }

    private fun catalogDate(): String = runCatching {
        dayTime(Instant.parse(catalog!!.publishedAt).toEpochMilli(), false).substring(0, 5)
    }.getOrDefault("")

    // ================================================================== scanning
    private fun onScan(raw: String) {
        if (screen == Screen.PAIR) return
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
            // the same item again: one more box
            if (p.verdict.kind == Kind.ORDER || p.verdict.kind == Kind.SUBSTITUTE) {
                p.boxes += 1
                beep(Kind.ORDER)
                showResult()
            }
            return
        }
        if (screen == Screen.RESULT) commit()
        openScan(raw)
    }

    private fun openScan(raw: String) {
        val cat = catalog ?: return
        val v = cat.judge(raw)
        val target = targetEan(v, raw)
        val inList = list.line(target)
        val info = cat.info(target)
        pending = Pending(raw, v, Catalog.startBoxes(inList?.boxes, info), remind = inList?.kind != "griglia")
        beep(v.kind)
        showResult()
    }

    /** The ean that goes in the list for this verdict (the substitute for a replaced item). */
    private fun targetEan(v: Verdict, raw: String): String =
        (if (v.kind == Kind.SUBSTITUTE) v.substitute?.ean else v.item?.ean) ?: (Catalog.keys(raw).firstOrNull() ?: raw)

    /** Puts the looked-at item in the list (next scan or "Aggiungi"). */
    private fun commit() {
        val p = pending ?: return
        pending = null
        val v = p.verdict
        val now = System.currentTimeMillis()
        val me = api.deviceName
        val line = when (v.kind) {
            Kind.ORDER -> Line(v.item!!.ean, v.item.name.take(120), p.boxes, "griglia", updatedBy = me, updatedAt = now)
            Kind.SUBSTITUTE -> {
                val s = v.substitute!!
                Line(s.ean, s.name.take(120), p.boxes, "sub", origEan = v.item?.ean, origName = v.item?.name?.take(120), updatedBy = me, updatedAt = now)
            }
            Kind.NOT_AVAILABLE -> if (p.remind) Line(v.item!!.ean, v.item.name.take(120), 0, "remind", updatedBy = me, updatedAt = now) else null
            else -> null
        } ?: run {
            // "remind" switched off for an item that was in the list as a reminder
            val old = v.item?.let { list.line(it.ean) }
            if (old != null && old.kind == "remind") change(old.copy(boxes = 0, kind = "griglia", updatedAt = now))
            return
        }
        val old = list.line(line.ean)
        if (old != null && old.boxes == line.boxes && old.kind == line.kind) return
        change(line)
    }

    private fun change(line: Line) {
        list.set(line)
        api.savePending(list)
        sync(quiet = true)
    }

    // ================================================================== result of a scan
    private fun showResult() {
        val p = pending ?: return showHome()
        val v = p.verdict
        val cat = catalog
        val target = targetEan(v, p.scan)
        val info = cat?.info(target)
        val inList = list.line(target)
        val root = page()
        root.addView(topBar(back = true))

        data class Hero(val bg: Int, val fg: Int, val sub: Int, val symbol: String, val title: String, val line: String)
        val hero = when (v.kind) {
            Kind.ORDER -> Hero(C.GREEN, Color.WHITE, 0xFFD5F5E8.toInt(), "✓", "ORDINABILE",
                "In Griglia · " + v.item!!.wholesalers.size + (if (v.item.wholesalers.size == 1) " grossista lo ha" else " grossisti lo hanno"))
            Kind.SUBSTITUTE -> Hero(C.BLUE, Color.WHITE, 0xFFDCE6FF.toInt(), "⇄", "SOSTITUITO", "Questo codice non c'è più · ordina il nuovo")
            Kind.NOT_AVAILABLE -> Hero(C.AMBER, 0xFF2B1A00.toInt(), 0xFF4A3300.toInt(), "◷", "NON DISPONIBILE", "In Griglia, ma questa settimana nessun grossista lo ha")
            Kind.URGENT_ONLY -> Hero(C.RED, Color.WHITE, 0xFFFFD9D4.toInt(), "✕", "NON IN GRIGLIA",
                if (v.item!!.griglia == 2) "Tolto dalla Griglia" else "Non è un articolo della lista DETERSIVI")
            Kind.DIRECT_ONLY -> Hero(C.RED, Color.WHITE, 0xFFFFD9D4.toInt(), "✕", "NON IN GRIGLIA", "Nessun grossista DETERSIVI lo ha")
            Kind.UNKNOWN -> Hero(0xFF475569.toInt(), Color.WHITE, 0xFFE2E8F0.toInt(), "?", "SCONOSCIUTO", "Non è in Griglia né nei listini dei grossisti")
        }
        val h = ui.row()
        h.setBackgroundColor(hero.bg)
        h.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18))
        h.addView(ui.badge(hero.symbol, 0x33FFFFFF, hero.fg))
        val ht = ui.col()
        ht.addView(ui.text(hero.title, 25f, hero.fg, ui.bold))
        if (v.kind == Kind.URGENT_ONLY) ht.addView(ui.chip("SOLO PER URGENZE", C.RED, Color.WHITE), ui.lp(w = ui.WRAP, top = 6))
        ht.addView(ui.text(hero.line, 13f, hero.sub), ui.lp(top = 5))
        h.addView(ht, ui.lp(w = 0, weight = 1f, start = 14))
        root.addView(h)
        h.alpha = 0f
        h.translationY = -ui.dpf(12f)
        h.animate().alpha(1f).translationY(0f).setDuration(180).start()

        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))

        // the product (for a substitute: the old one crossed out, then the new one)
        if (v.kind == Kind.SUBSTITUTE) {
            val old = ui.row()
            old.background = ui.dashed(0xFFC4CCDA.toInt(), 0xFFEEF1F6.toInt(), 16)
            old.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            old.addView(ui.initials(v.item!!.name, C.FAINT, C.CARD, 44))
            val oc = ui.col()
            oc.addView(ui.text("HAI SCANSIONATO · NON PIÙ IN VENDITA", 10f, C.MUTED, ui.bold))
            oc.addView(ui.struck(ui.text(v.item.name, 14f, C.MUTED, ui.medium)), ui.lp(top = 3))
            oc.addView(ui.num(v.item.ean, 12f, C.FAINT), ui.lp(top = 2))
            old.addView(oc, ui.lp(w = 0, weight = 1f, start = 12))
            body.addView(old)
            body.addView(ui.text("↓", 22f, C.BLUE, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
            val s = v.substitute!!
            val nw = ui.col(14)
            nw.background = ui.bg(C.CARD, 18, C.BLUE, 2)
            val nr = ui.row()
            nr.addView(ui.initials(s.name, C.BLUE, C.BLUE_SOFT, 52))
            val nc = ui.col()
            nc.addView(ui.text("ORDINA QUESTO AL SUO POSTO", 10f, C.BLUE, ui.bold))
            nc.addView(ui.text(s.name, 15f, C.INK, ui.bold), ui.lp(top = 3))
            nc.addView(ui.num(s.ean + packText(info), 12f, C.MUTED), ui.lp(top = 2))
            nr.addView(nc, ui.lp(w = 0, weight = 1f, start = 12))
            nw.addView(nr)
            val tags = ui.row()
            if (s.wholesalers.isNotEmpty()) tags.addView(ui.chip("${s.wholesalers.size} grossisti", C.GREEN_INK, C.GREEN_SOFT))
            info?.stock?.let { tags.addView(ui.chip("In negozio ${n(it)} pz", C.MUTED, 0xFFEEF1F6.toInt()), ui.lp(w = ui.WRAP, start = if (tags.childCount > 0) 6 else 0)) }
            if (tags.childCount > 0) nw.addView(tags, ui.lp(top = 10))
            body.addView(nw, ui.lp(top = 4))
        } else {
            val pr = ui.row()
            val name = v.item?.name?.ifEmpty { null } ?: "Articolo non in catalogo"
            pr.addView(ui.initials(name, hero.bg.let { if (v.kind == Kind.NOT_AVAILABLE) C.AMBER_INK else it }))
            val pc = ui.col()
            pc.addView(ui.text(name, 16f, C.INK, ui.bold))
            pc.addView(ui.num((v.item?.ean ?: p.scan) + packText(info), 12f, C.MUTED), ui.lp(top = 3))
            pr.addView(pc, ui.lp(w = 0, weight = 1f, start = 12))
            body.addView(pr)
        }

        // this store's numbers
        if (info != null) {
            val tiles = ui.row()
            val cover = info.coverDays((info.transitBoxes ?: 0.0) * (info.pack ?: 0.0))
            val empty = (info.stock ?: 0.0) <= 0
            fun add(t: View, first: Boolean) = tiles.addView(t, ui.lp(w = 0, weight = 1f, start = if (first) 0 else 6))
            add(if (empty) ui.tile("In negozio", "0", "pz", C.RED_SOFT, 0xFFF7C6C0.toInt(), C.RED_INK, C.RED_INK) else ui.tile("In negozio", n(info.stock), "pz"), true)
            add(ui.tile("Venduti 30g", n(info.sold30), "pz"), false)
            add(ui.tile("In arrivo", n(info.transitBoxes ?: 0.0), colli((info.transitBoxes ?: 0.0).toInt()), fg = if ((info.transitBoxes ?: 0.0) > 0) C.BLUE else C.INK), false)
            add(when {
                cover == null -> ui.tile("Basta per", "–")
                cover < 14 -> ui.tile("Basta per", "$cover", "gg", C.AMBER_SOFT, C.AMBER_LINE, C.AMBER_INK, C.AMBER_INK)
                else -> ui.tile("Basta per", "$cover", "gg")
            }, false)
            body.addView(tiles, ui.lp(top = 14))
            // prices: purchase / sale / promotion
            if ((info.cost ?: 0.0) > 0 || (info.price ?: 0.0) > 0) {
                val pr = ui.row()
                pr.background = ui.bg(C.CARD, 12, C.LINE)
                pr.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
                pr.addView(ui.text("Acquisto ", 12f, C.MUTED))
                pr.addView(ui.num(eur(info.cost), 13f))
                pr.addView(ui.text("   Vendita ", 12f, C.MUTED))
                val sale = ui.num(eur(info.price), 13f)
                if ((info.promoPrice ?: 0.0) > 0) ui.struck(sale).setTextColor(C.FAINT)
                pr.addView(sale)
                pr.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
                if ((info.promoPrice ?: 0.0) > 0) {
                    pr.addView(ui.chip("PROMO " + eur(info.promoPrice) + (if (info.promoEnd.isNotEmpty()) " · " + info.promoEnd else ""), Color.WHITE, C.RED, 11f))
                }
                body.addView(pr, ui.lp(top = 8))
            }
        }

        // things worth knowing before ordering
        fun note(symbol: String, text: String, fg: Int, fill: Int, line: Int) {
            val r = ui.row()
            r.background = ui.bg(fill, 12, line)
            r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            r.addView(ui.text(symbol, 15f, fg, ui.bold))
            r.addView(ui.text(text, 13f, fg), ui.lp(w = 0, weight = 1f, start = 10))
            body.addView(r, ui.lp(top = 8))
        }
        if (inList != null && inList.boxes > 0 && (v.kind == Kind.ORDER || v.kind == Kind.SUBSTITUTE)) {
            val by = if (!inList.updatedBy.isNullOrEmpty() && inList.updatedBy != api.deviceName) " · da ${inList.updatedBy}" else ""
            note("≡", "Già in lista: ${inList.boxes} ${colli(inList.boxes)}$by", C.BLUE_INK, C.BLUE_SOFT, 0xFFC6D5FF.toInt())
        }
        if ((v.kind == Kind.ORDER || v.kind == Kind.SUBSTITUTE) && info != null) {
            if ((info.transitBoxes ?: 0.0) > 0) note("▸", "${n(info.transitBoxes)} ${colli((info.transitBoxes ?: 0.0).toInt())} già in arrivo: tienine conto", C.BLUE_INK, C.BLUE_SOFT, 0xFFC6D5FF.toInt())
            if (Catalog.overstocked(info)) {
                val c = info.coverDays((info.transitBoxes ?: 0.0) * (info.pack ?: 0.0))
                note("!", "Hai già ${n(info.stock)} pz" + (if (c != null) ": bastano ${Catalog.months(c)}" else " e non si vendono"), C.AMBER_INK, C.AMBER_SOFT, C.AMBER_LINE)
            }
        }

        when (v.kind) {
            Kind.ORDER, Kind.SUBSTITUTE -> body.addView(stepper(p, info), ui.lp(top = 14))
            Kind.NOT_AVAILABLE -> notAvailable(body, p, info)
            Kind.URGENT_ONLY -> urgentChoices(body, v)
            Kind.DIRECT_ONLY, Kind.UNKNOWN -> {
                val d = v.item?.direct.orEmpty()
                val c = ui.col(14)
                c.background = ui.bg(C.CARD, 16, C.LINE)
                c.addView(ui.text(if (d.isNotEmpty()) "Da ordinare al fornitore diretto" else "Non si ordina nella lista DETERSIVI", 15f, C.INK, ui.bold))
                c.addView(ui.text(if (d.isNotEmpty()) "$d · con l'ordine diretto del negozio" else "Se serve davvero, chiedi all'ufficio acquisti", 13f, C.MUTED), ui.lp(top = 4))
                body.addView(c, ui.lp(top = 14))
            }
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))

        // buttons
        val bar = bottomBar()
        when (v.kind) {
            Kind.ORDER, Kind.SUBSTITUTE -> {
                bar.addView(ui.text("▮▯▮  Scansiona il prossimo: questo si aggiunge da solo", 12f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp())
                val r = ui.row()
                val inIt = inList != null && inList.boxes > 0
                r.addView(ui.button(if (inIt) "Togli" else "Annulla", 0, if (inIt) C.RED_INK else C.INK, size = 15f) {
                    if (inIt) { list.remove(target); api.savePending(list); sync(quiet = true) }
                    pending = null
                    showHome()
                }, ui.fixed(100, 52))
                val label = when {
                    inIt && inList!!.boxes == p.boxes -> "Va bene così"
                    inIt -> "Cambia in ${p.boxes} ${colli(p.boxes)}"
                    v.kind == Kind.SUBSTITUTE -> "Ordina il sostituto · ${p.boxes}"
                    else -> "Aggiungi ${p.boxes} ${colli(p.boxes)}"
                }
                r.addView(ui.button(label, if (v.kind == Kind.SUBSTITUTE) C.BLUE else C.GREEN) { commit(); showHome() }, ui.lp(w = 0, h = ui.dp(52), weight = 1f, start = 10))
                bar.addView(r, ui.lp(top = 10))
            }
            Kind.NOT_AVAILABLE -> bar.addView(ui.button("OK · prossimo articolo", C.NAVY) { commit(); showHome() }, ui.lp(h = ui.dp(52)))
            Kind.URGENT_ONLY -> {
                val urgent = list.line(target)?.takeIf { it.kind == "urgent" && it.boxes > 0 }
                if (urgent != null) {
                    bar.addView(ui.button("Modifica l'urgenza", C.RED) { urgentSheet(v, info, urgent) }, ui.lp(h = ui.dp(52)))
                    bar.addView(ui.button("Togli dalla lista", 0, C.RED_INK, C.RED, 15f) {
                        list.remove(target); api.savePending(list); sync(quiet = true); showHome()
                    }, ui.lp(h = ui.dp(48), top = 10))
                } else {
                    bar.addView(ui.button("Ok, lo prendo dal diretto", C.GREEN) { pending = null; showHome() }, ui.lp(h = ui.dp(52)))
                    bar.addView(ui.button("È un'urgenza", 0, C.RED_INK, C.RED, 15f) { urgentSheet(v, info, null) }, ui.lp(h = ui.dp(48), top = 10))
                }
            }
            else -> bar.addView(ui.button("OK · prossimo articolo", C.NAVY) { pending = null; showHome() }, ui.lp(h = ui.dp(52)))
        }
        root.addView(bar)
        show(root, Screen.RESULT)
    }

    private fun packText(info: StoreInfo?): String = info?.pack?.takeIf { it > 0 }?.let { " · ${n(it)} pz/collo" } ?: ""

    /** − boxes + with the suggestion and how long it will last. */
    private fun stepper(p: Pending, info: StoreInfo?): View {
        val c = ui.col(16)
        c.background = ui.bg(C.CARD, 18, C.LINE)
        val head = ui.row()
        head.addView(ui.text("QUANTI COLLI?", 13f, C.MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
        val sugg = info?.suggestBoxes ?: 0
        if (sugg > 0) {
            val chip = ui.chip("✦ Consigliato $sugg", C.GREEN_INK, C.GREEN_SOFT)
            chip.setOnClickListener { p.boxes = sugg; showResult() }
            head.addView(chip)
        } else if (info != null && info.suggestBoxes == 0) {
            head.addView(ui.chip("Il sistema non lo ordinerebbe", C.AMBER_INK, C.AMBER_SOFT, 11f))
        }
        c.addView(head)
        val r = ui.row()
        val minus = ui.button("−", 0xFFF3F5F9.toInt(), C.INK, size = 30f) { if (p.boxes > 1) { p.boxes -= 1; showResult() } }
        minus.background = ui.bg(0xFFF3F5F9.toInt(), 16, C.LINE2)
        minus.contentDescription = "Togli un collo"
        r.addView(minus, ui.fixed(64, 64))
        val mid = ui.col()
        mid.gravity = Gravity.CENTER_HORIZONTAL
        mid.addView(ui.num("${p.boxes}", 44f).apply { gravity = Gravity.CENTER })
        val pcs = info?.pack?.takeIf { it > 0 }?.let { " = ${n(it * p.boxes)} pz" } ?: ""
        mid.addView(ui.text(colli(p.boxes) + pcs, 12f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 2))
        r.addView(mid, ui.lp(w = 0, weight = 1f))
        val plus = ui.button("+", C.NAVY, size = 30f) { p.boxes += 1; showResult() }
        plus.contentDescription = "Aggiungi un collo"
        r.addView(plus, ui.fixed(64, 64))
        c.addView(r, ui.lp(top = 14))
        Catalog.coverAfter(info, p.boxes)?.let {
            c.addView(ui.text("Con ${p.boxes} ${colli(p.boxes)} arrivi a ~${Catalog.months(it)} di vendita", 12f, if (it > 120) C.AMBER_INK else C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 12))
        }
        return c
    }

    private fun notAvailable(body: LinearLayout, p: Pending, info: StoreInfo?) {
        val c = ui.col(14)
        c.background = ui.bg(C.CARD, 16, C.LINE)
        val d = p.verdict.item?.direct.orEmpty()
        val cover = info?.coverDays()
        c.addView(ui.text(when {
            info == null -> "L'ufficio acquisti cerca un'alternativa"
            (info.stock ?: 0.0) <= 0 -> "Scaffale vuoto: avvisa l'ufficio acquisti"
            cover != null && cover >= 21 -> "Hai scorta per $cover giorni: nessun problema"
            cover != null -> "Hai scorta per $cover giorni"
            else -> "L'ufficio acquisti cerca un'alternativa"
        }, 14f, C.INK, ui.bold))
        if (d.isNotEmpty()) c.addView(ui.text("Si trova anche dal fornitore diretto: $d", 13f, C.MUTED), ui.lp(top = 4))
        body.addView(c, ui.lp(top = 14))
        val r = ui.row()
        r.background = ui.bg(C.CARD, 16, if (p.remind) C.NAVY else C.LINE, if (p.remind) 2 else 1)
        r.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        val box = ui.text(if (p.remind) "✓" else "", 16f, Color.WHITE, ui.bold).apply {
            gravity = Gravity.CENTER
            background = if (p.remind) ui.bg(C.NAVY, 6) else ui.bg(C.CARD, 6, C.LINE2, 2)
        }
        r.addView(box, ui.fixed(26, 26))
        val t = ui.col()
        t.addView(ui.text("Ricordamelo quando torna", 14f, C.INK, ui.bold))
        t.addView(ui.text("Lo ritrovi nella prossima lista appena c'è", 12f, C.MUTED), ui.lp(top = 2))
        r.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
        r.setOnClickListener { p.remind = !p.remind; showResult() }
        body.addView(r, ui.lp(top = 10))
    }

    private fun urgentChoices(body: LinearLayout, v: Verdict) {
        val d = v.item?.direct.orEmpty()
        val rec = ui.col(14)
        rec.background = ui.bg(C.CARD, 18, C.GREEN, 2)
        val h = ui.row()
        h.addView(ui.chip("CONSIGLIATO", C.GREEN_INK, C.GREEN_SOFT, 11f))
        h.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
        h.addView(ui.text("costa meno", 12f, C.MUTED))
        rec.addView(h)
        val r = ui.row()
        r.addView(ui.badge("▸", C.GREEN_SOFT, C.GREEN_INK, 40, 18f))
        val t = ui.col()
        t.addView(ui.text(if (d.isNotEmpty()) "Fornitore diretto: $d" else "Fornitore diretto", 15f, C.INK, ui.bold))
        t.addView(ui.text("Con il prossimo ordine diretto del negozio", 13f, C.MUTED), ui.lp(top = 2))
        r.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
        rec.addView(r, ui.lp(top = 10))
        body.addView(rec, ui.lp(top = 14))

        val urg = ui.col(14)
        urg.background = ui.bg(C.RED_BG, 18, 0xFFF7C6C0.toInt())
        val u = ui.row()
        u.addView(ui.badge("⚡", C.RED_SOFT, C.RED_INK, 40, 18f))
        val ut = ui.col()
        ut.addView(ui.text("Urgente? Dal grossista", 15f, C.INK, ui.bold))
        ut.addView(ui.text(v.item!!.wholesalers.joinToString(", ") + " ce l'ha questa settimana", 13f, C.MUTED), ui.lp(top = 2))
        u.addView(ut, ui.lp(w = 0, weight = 1f, start = 12))
        urg.addView(u)
        urg.addView(ui.text("Costa di più · serve un motivo · l'ufficio acquisti decide", 12f, C.RED_INK), ui.lp(top = 8))
        body.addView(urg, ui.lp(top = 10))
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
            q.addView(ui.button("−", C.CARD, C.INK, C.LINE2, 22f) { if (boxes > 1) { boxes -= 1; render() } }, ui.fixed(44, 44))
            q.addView(ui.num("$boxes", 26f).apply { gravity = Gravity.CENTER }, ui.fixed(44, 44))
            q.addView(ui.button("+", C.NAVY, size = 22f) { boxes += 1; render() }, ui.fixed(44, 44))
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
                showHome()
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
    private fun showReview() {
        pending = null
        val v = list.server
        val root = page()
        val head = ui.col()
        head.setBackgroundColor(C.NAVY)
        head.setPadding(ui.dp(12), ui.dp(10), ui.dp(18), ui.dp(16))
        val t = ui.row()
        t.addView(ui.text("‹", 30f, Color.WHITE, ui.bold).apply { gravity = Gravity.CENTER; setOnClickListener { showHome() }; contentDescription = "Indietro" }, ui.fixed(40, 44))
        val tc = ui.col()
        tc.addView(ui.text("Lista " + (v?.week?.let { weekNo(it) } ?: ""), 18f, Color.WHITE, ui.bold))
        if (v != null) tc.addView(ui.text("chiude tra " + countdown(v.deadline) + " · " + dayTime(v.deadline), 12f, C.YELLOW, ui.medium), ui.lp(top = 2))
        t.addView(tc, ui.lp(w = 0, weight = 1f))
        head.addView(t)
        val stats = ui.row()
        for ((i, s) in listOf(Triple("${list.ordered.size}", "articoli", Color.WHITE), Triple("${list.boxes}", "colli", Color.WHITE),
            Triple("${list.urgent}", if (list.urgent == 1) "urgenza" else "urgenze", if (list.urgent > 0) C.CORAL else Color.WHITE)).withIndex()) {
            val c = ui.col()
            c.background = ui.bg(C.NAVY2, 12)
            c.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            c.addView(ui.num(s.first, 20f, s.third))
            c.addView(ui.text(s.second, 11f, C.ON_NAVY_MUTED), ui.lp(top = 2))
            stats.addView(c, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 6))
        }
        head.addView(stats, ui.lp(top = 12))
        root.addView(head)

        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        val lines = list.lines()
        val cat = catalog

        // to check: much stock already, already coming, not orderable any more
        val checks = ArrayList<Triple<Line, String, Int>>()
        for (l in lines.filter { it.boxes > 0 }) {
            val info = cat?.info(l.ean)
            val kind = cat?.judge(l.ean)?.kind
            when {
                l.kind != "urgent" && kind != null && kind != Kind.ORDER && kind != Kind.SUBSTITUTE ->
                    checks.add(Triple(l, "non più ordinabile questa settimana", 2))
                Catalog.overstocked(info) -> {
                    val c = info!!.coverDays((info.transitBoxes ?: 0.0) * (info.pack ?: 0.0))
                    checks.add(Triple(l, "hai già ${n(info.stock)} pz" + (if (c != null) ", bastano ${Catalog.months(c)}" else ", non si vendono"), 0))
                }
                (info?.transitBoxes ?: 0.0) > 0 -> checks.add(Triple(l, "${n(info!!.transitBoxes)} ${colli(info.transitBoxes!!.toInt())} già in arrivo", 1))
            }
        }
        if (checks.isNotEmpty()) {
            body.addView(ui.text("DA CONTROLLARE · ${checks.size}", 12f, C.AMBER_INK, ui.bold))
            for (c in checks) {
                val (fg, fill, line) = when (c.third) {
                    1 -> listOf(C.BLUE_INK, C.BLUE_SOFT, 0xFFC6D5FF.toInt())
                    2 -> listOf(C.RED_INK, C.RED_BG, 0xFFF7C6C0.toInt())
                    else -> listOf(C.AMBER_INK, C.AMBER_SOFT, C.AMBER_LINE)
                }
                val r = ui.row()
                r.background = ui.bg(fill, 12, line)
                r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                r.addView(ui.text(if (c.third == 1) "▸" else "!", 15f, fg, ui.bold))
                val tx = ui.col()
                tx.addView(ui.text(c.first.name.ifEmpty { c.first.ean }, 13f, C.INK, ui.bold).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                tx.addView(ui.text(c.second, 12f, fg), ui.lp(top = 2))
                r.addView(tx, ui.lp(w = 0, weight = 1f, start = 10))
                r.addView(ui.num("${c.first.boxes}", 14f))
                r.setOnClickListener { openScan(c.first.origEan ?: c.first.ean) }
                body.addView(r, ui.lp(top = 6))
            }
        }

        // groups
        val groups = listOf(
            Triple("In Griglia", C.GREEN, lines.filter { it.boxes > 0 && (it.kind == "griglia" || it.kind == "other") }),
            Triple("Sostituiti", C.BLUE, lines.filter { it.boxes > 0 && it.kind == "sub" }),
            Triple("Urgenze", C.RED, lines.filter { it.boxes > 0 && it.kind == "urgent" }),
            Triple("Da ricordare · non disponibili", C.AMBER, lines.filter { it.kind == "remind" }),
        ).filter { it.third.isNotEmpty() }
        if (groups.isEmpty()) {
            val e = ui.col(24)
            e.gravity = Gravity.CENTER_HORIZONTAL
            e.background = ui.dashed(0xFFC4CCDA.toInt(), C.CARD, 16)
            e.addView(ui.text("La lista è vuota", 17f, C.INK, ui.bold).apply { gravity = Gravity.CENTER })
            e.addView(ui.text("Scansiona gli articoli da ordinare", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
            body.addView(e, ui.lp(top = if (checks.isEmpty()) 0 else 14))
        }
        for ((gi, g) in groups.withIndex()) {
            val gh = ui.row()
            gh.addView(ui.dot(g.second))
            gh.addView(ui.text(g.first, 14f, C.INK, ui.bold), ui.lp(w = 0, weight = 1f, start = 8))
            val boxes = g.third.sumOf { it.boxes }
            gh.addView(ui.text("${g.third.size} art" + (if (boxes > 0) " · $boxes colli" else ""), 12f, C.MUTED))
            body.addView(gh, ui.lp(top = if (gi == 0 && checks.isEmpty()) 0 else 16))
            val card = ui.col()
            card.background = ui.bg(C.CARD, 16, C.LINE)
            card.setPadding(ui.dp(14), ui.dp(2), ui.dp(8), ui.dp(2))
            for ((i, l) in g.third.withIndex()) {
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(0xFFE6EAF1.toInt()) }, ui.lp(h = 1))
                card.addView(lineRow(l))
            }
            body.addView(card, ui.lp(top = 6))
        }
        val last = list.fromLastWeek()
        if (last.isNotEmpty()) {
            val b = ui.button("↻  Aggiungi quelli della settimana scorsa (${last.size})", C.CARD, C.INK, size = 14f) {
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
            b.background = ui.dashed(0xFFC4CCDA.toInt(), C.CARD, 12)
            body.addView(b, ui.lp(h = ui.dp(46), top = 16))
        }
        root.addView(scroll(body), ui.lp(h = 0, weight = 1f))

        val bar = bottomBar()
        val sent = list.submitted && !list.changedSinceSubmit
        if (sent) {
            bar.addView(ui.button("✓  Inviata · vedi com'è andata", C.GREEN) { showSent() }, ui.lp(h = ui.dp(54)))
        } else {
            val label = if (list.submitted) "Invia di nuovo le modifiche" else "Invia all'ufficio acquisti"
            bar.addView(ui.button("➤  $label", if (list.ordered.isEmpty()) C.LINE2 else C.BLUE) { if (list.ordered.isNotEmpty()) confirmSubmit() }, ui.lp(h = ui.dp(54)))
        }
        if (v != null) bar.addView(ui.text("Puoi modificarla fino a " + dayTime(v.deadline), 12f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 8))
        root.addView(bar)
        show(root, Screen.REVIEW)
    }

    /** One line of the list: name, reason / original, − boxes +. */
    private fun lineRow(l: Line): View {
        val r = ui.row()
        r.setPadding(0, ui.dp(8), 0, ui.dp(8))
        val c = ui.col()
        c.addView(ui.text(l.name.ifEmpty { l.ean }, 14f, C.INK, ui.medium).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val sub = when (l.kind) {
            "sub" -> "al posto di " + (l.origName ?: l.origEan ?: "")
            "urgent" -> "Urgenza · " + (l.reason ?: "")
            "remind" -> "Te lo riproponiamo quando torna"
            else -> l.ean
        } + (if (!l.updatedBy.isNullOrEmpty() && l.updatedBy != api.deviceName) " · ${l.updatedBy}" else "")
        c.addView(ui.text(sub, 11f, if (l.kind == "urgent") C.RED_INK else C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.lp(top = 2))
        c.setOnClickListener { openScan(l.origEan ?: l.ean) }
        r.addView(c, ui.lp(w = 0, weight = 1f))
        if (l.kind == "remind") {
            r.addView(ui.button("✕", C.CARD, C.MUTED, C.LINE2, 14f) { list.remove(l.ean); api.savePending(list); sync(quiet = true); showReview() }, ui.fixed(40, 40))
            return r
        }
        val step = { d: Int ->
            val nb = l.boxes + d
            if (nb <= 0) {
                AlertDialog.Builder(this).setTitle("Togliere dalla lista?").setMessage(l.name)
                    .setPositiveButton("Togli") { _, _ -> list.remove(l.ean); api.savePending(list); sync(quiet = true); showReview() }
                    .setNegativeButton("Annulla", null).show()
            } else {
                change(l.copy(boxes = nb, updatedBy = api.deviceName, updatedAt = System.currentTimeMillis()))
                showReview()
            }
        }
        r.addView(ui.button("−", C.CARD, C.INK, C.LINE2, 18f) { step(-1) }, ui.fixed(40, 40, 6))
        r.addView(ui.num("${l.boxes}", 16f).apply { gravity = Gravity.CENTER }, ui.fixed(36, 40))
        r.addView(ui.button("+", C.NAVY, size = 18f) { step(1) }, ui.fixed(40, 40))
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
        val v = list.server
        val cur = v?.list
        val root = page()
        val head = ui.col()
        head.setBackgroundColor(C.NAVY)
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.setPadding(ui.dp(18), ui.dp(24), ui.dp(18), ui.dp(20))
        val b = ui.badge("✓", C.GREEN, Color.WHITE, 64, 32f)
        head.addView(b)
        b.scaleX = 0.6f; b.scaleY = 0.6f
        b.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
        head.addView(ui.text(if (cur?.status == "ordered") "Lista ordinata" else "Lista inviata", 24f, Color.WHITE, ui.bold), ui.lp(w = ui.WRAP, top = 10))
        if (cur != null) head.addView(ui.text(dayTime(cur.submittedAt) + " · ${list.ordered.size} articoli · ${list.boxes} colli", 13f, C.ON_NAVY_MUTED), ui.lp(w = ui.WRAP, top = 4))
        root.addView(head)

        val body = ui.col()
        body.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14))
        if (cur != null) body.addView(timeline(cur, v.deadline))
        val prev = v?.previous
        if (prev != null) {
            val c = ui.col(14)
            c.background = ui.bg(C.CARD, 16, C.LINE)
            c.addView(ui.text("SETTIMANA SCORSA · " + weekNo(prev.week).removePrefix("sett. "), 12f, C.MUTED, ui.bold))
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
                    r.addView(t, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 5))
                }
                c.addView(r, ui.lp(top = 10))
                // the lines that changed
                val fb = prev.feedback!!
                for (l in prev.lines.filter { it.boxes > 0 }) {
                    val got = fb.boxes[l.ean] ?: continue
                    if (got >= l.boxes && fb.notes[l.ean].isNullOrEmpty()) continue
                    val lr = ui.row()
                    lr.addView(ui.text(l.name.ifEmpty { l.ean }, 13f, C.INK).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.lp(w = 0, weight = 1f))
                    lr.addView(ui.num("${l.boxes} → $got", 13f, if (got <= 0) C.RED_INK else C.AMBER_INK), ui.lp(w = ui.WRAP, start = 8))
                    c.addView(lr, ui.lp(top = 8))
                    fb.notes[l.ean]?.let { c.addView(ui.text(it, 11f, C.MUTED), ui.lp(top = 1)) }
                }
                for (d in fb.deliveries) {
                    val dr = ui.row()
                    dr.addView(ui.text("▸", 14f, C.INK, ui.bold))
                    dr.addView(ui.text("${d.first} · scarico ${d.second}", 13f, C.INK), ui.lp(w = 0, weight = 1f, start = 8))
                    c.addView(dr, ui.lp(top = 8))
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
                if (screen == Screen.HOME && (e == null || was != offline)) showHome()
                else if (screen == Screen.REVIEW && e == null && !quiet) showReview()
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
