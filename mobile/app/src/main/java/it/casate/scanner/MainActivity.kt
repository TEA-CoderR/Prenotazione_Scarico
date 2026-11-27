package it.casate.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale
import java.util.concurrent.Executors

/**
 * CASA&TE Scanner (v4) — the store's weekly DETERSIVO order on the Zebra TC22.
 *
 * The whole flow is three steps, always the same:
 *   1. SCAN an item (yellow key, or type a code / name)
 *   2. DECIDE on one card that slides up: the system's suggestion is already filled in; adjust, give a
 *      reason only when asked, one button puts it in the list (stock wrong? two choices on the same card)
 *   3. SEND the list before the deadline (List tab); what is left to do is in the To-do tab
 *
 * Three tabs at the bottom (Scansiona · Lista · Da fare) and one bottom sheet; full screens only for
 * pairing and counting the stock. Screens are in ScanTab / ItemSheet / ListTab / TasksTab / CountScreen.
 */
class MainActivity : Activity() {
    internal lateinit var api: Api
    internal lateinit var ui: Ui
    internal var catalog: Catalog? = null
    internal lateinit var list: StoreList
    internal val io = Executors.newSingleThreadExecutor()
    internal val main = Handler(Looper.getMainLooper())
    private var tones: ToneGenerator? = null

    internal enum class Tab { SCAN, LIST, TASKS }
    internal var tab = Tab.SCAN
    /** A full-screen page over the tabs (pairing, counting): scans are ignored there. */
    internal var fullScreen = false
    internal lateinit var root: FrameLayout
    internal lateinit var page: FrameLayout
    internal lateinit var sheet: SheetHost
    internal var offline = false
    private var syncing = false
    /** One line shown once (green toast-like bar on the scan tab) after something was put in the list. */
    internal var flash: String? = null
    internal var search = ""
    /** The item on the card (see ItemSheet). */
    internal var pending: Pending? = null

    internal val toCount: ToCount by lazy { ToCount(api.toCount) }
    internal fun saveToCount() { api.toCount = toCount.save() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = Scanners.extract(intent) ?: return      // Zebra, Honeywell, Urovo, Newland… (Scanners.kt)
            scanVia = "intent"; scansIntent++
            onScan(data.trim())
        }
    }

    // ---- scanner (DataWedge) state, shown in the menu «Stato dello scanner»
    private var dwResult: String? = null
    private var dwActive: String? = null
    private var dwTries = 0
    private var scanVia = ""
    private var scansIntent = 0
    private var scansKeys = 0
    private val keyScan = KeyScan()
    private val keyFlush = Runnable { keyScan.finish()?.let { scannedByKeys(it) } }

    private val dwReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val (res, active) = DataWedge.readResult(intent)
            if (res != null) {
                dwResult = res
                api.dwOk = res == "SUCCESS"
                if (res == "SUCCESS") main.postDelayed({ DataWedge.askActiveProfile(this@MainActivity) }, 800)
            }
            if (active != null) {
                dwActive = active
                // the app is in front but another profile is active: write ours again (a few times at most)
                if (active != DataWedge.PROFILE && dwTries < 3) { dwTries++; DataWedge.configure(this@MainActivity) }
            }
        }
    }

    private fun scannedByKeys(code: String) {
        scanVia = "tastiera"; scansKeys++
        if (dwTries < 3 && Scanners.isZebra) { dwTries++; DataWedge.configure(this) }
        onScan(code)
    }

    // ---- invisible scan field: scanners that write the code straight into the focused text field
    // (Honeywell / Urovo «keyboard wedge»: no key events) used to need a tap on the search box first.
    // This field keeps the focus when no other field has it, so the code lands here and is read as a scan.
    private lateinit var sink: android.widget.EditText
    private var sinkFirst = 0L
    private var sinkBusy = false
    private var scansField = 0
    private val sinkFlush = Runnable { flushSink() }

    private fun makeSink() {
        sink = android.widget.EditText(this).apply {
            alpha = 0f
            isCursorVisible = false
            showSoftInputOnFocus = false
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setOnEditorActionListener { _, _, _ -> main.removeCallbacks(sinkFlush); flushSink(); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    if (sinkBusy || e.isNullOrEmpty()) return
                    if (sinkFirst == 0L) sinkFirst = System.currentTimeMillis()
                    if (e.contains('\n')) { main.removeCallbacks(sinkFlush); flushSink(); return }
                    main.removeCallbacks(sinkFlush)
                    main.postDelayed(sinkFlush, KeyScan.GAP_MS + 30)
                }
            })
        }
        root.addView(sink, FrameLayout.LayoutParams(2, 2))
    }

    private fun flushSink() {
        val raw = sink.text?.toString() ?: ""
        val ms = System.currentTimeMillis() - sinkFirst
        sinkBusy = true
        sink.setText("")
        sinkBusy = false
        sinkFirst = 0L
        val code = Scanners.clean(raw)
        if (code.length >= KeyScan.MIN_LEN && ms <= code.length * KeyScan.PER_CHAR_MS + 300) {
            scanVia = "campo"; scansField++
            onScan(code)
        }
    }

    /** The invisible field takes the focus when no other field has it (never on pairing / full screens). */
    internal fun focusSink() {
        if (!::sink.isInitialized || !api.paired || fullScreen) return
        val f = currentFocus
        if (f === sink || (f is android.widget.EditText && f.isShown)) return
        sink.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) main.post { focusSink() }
    }

    /** Scanner in keystroke mode (default Profile0): catch the typed code even when no field has focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus !is android.widget.EditText || (::sink.isInitialized && currentFocus === sink)) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_TAB) {
                if (keyScan.pending()) {
                    if (event.action == KeyEvent.ACTION_UP) { main.removeCallbacks(keyFlush); keyFlush.run() }
                    return true
                }
            } else {
                val ch = event.unicodeChar.toChar()
                if (event.unicodeChar != 0 && (ch.isLetterOrDigit() || ch == '-') && !event.isCtrlPressed) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        keyScan.add(ch)
                        main.removeCallbacks(keyFlush)
                        main.postDelayed(keyFlush, KeyScan.GAP_MS + 30)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!fullScreen && !sheet.isOpen) sync(quiet = true)
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catchCrashes()
        api = Api(this)
        ui = Ui(this)
        ui.images = Images(this)
        list = api.loadList()
        tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
        root = FrameLayout(this)
        page = FrameLayout(this)
        root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sheet = SheetHost(this)
        root.addView(sheet.layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        makeSink()
        setContentView(root)
        window.navigationBarColor = Color.WHITE
        if (api.paired) {
            catalog = api.loadCatalog()
            useImageUrl()
            render()
        } else showPairing(null)
        showLastCrash()
    }

    // ================================================================== crashes
    private val crashFile get() = java.io.File(filesDir, "last_crash.txt")

    /** If the app ever closes because of an error, keep what happened (shown at the next start, to send us). */
    private fun catchCrashes() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                crashFile.writeText("app ${Updater.installedName(this)} · ${java.time.LocalDateTime.now()} · ${t.name}\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun showLastCrash() {
        val f = crashFile
        if (!f.exists()) return
        val txt = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (txt.isBlank()) return
        val lines = txt.lines()
        // the error and where it happened in this app's code (what is worth a photo)
        val ours = lines.filter { it.contains("it.casate.scanner") }.take(6)
        val short = (lines.take(2) + ours).joinToString("\n").take(1200)
        AlertDialog.Builder(this).setTitle("L'app si era chiusa per un errore")
            .setMessage("Fai una foto a questa schermata e mandala all'ufficio acquisti:\n\n$short")
            .setPositiveButton("OK", null).show()
    }

    override fun onResume() {
        super.onResume()
        val filter = Scanners.filter()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
        Scanners.claim(this)
        val rf = IntentFilter(DataWedge.RESULT_ACTION).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dwReceiver, rf, Context.RECEIVER_EXPORTED) else registerReceiver(dwReceiver, rf)
        // not confirmed yet (or written by an older version): write the profile again; otherwise just check it
        if (Scanners.isZebra || !Scanners.isHoneywell) {
            if (!api.dwOk) DataWedge.configure(this) else main.postDelayed({ DataWedge.askActiveProfile(this) }, 800)
        }
        main.post { focusSink() }
        if (api.paired) {
            if (catalog == null || System.currentTimeMillis() - api.lastCheck > 30 * 60_000) refreshCatalog(false)
            sync(quiet = true)
        }
        if (System.currentTimeMillis() - updateAt > 3 * 3600_000L) checkUpdate(false)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 60_000)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(dwReceiver) }
        Scanners.release(this)
        main.removeCallbacks(tick)
    }

    override fun onDestroy() {
        tones?.release()
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("Back key")
    override fun onBackPressed() {
        when {
            sheet.isOpen -> sheet.back()
            countBack != null -> { val b = countBack!!; countBack = null; b() }
            !api.paired -> super.onBackPressed()
            tab != Tab.SCAN -> go(Tab.SCAN)
            else -> super.onBackPressed()
        }
    }

    // ================================================================== frame: tabs + bottom navigation
    internal fun go(t: Tab) {
        tab = t
        render()
    }

    /** Redraws the current tab (cheap: the screens are small). */
    internal fun render() {
        if (!api.paired) return
        fullScreen = false
        countBack = null
        val col = ui.col()
        col.setBackgroundColor(C.CANVAS)
        val content = when (tab) {
            Tab.SCAN -> scanTab()
            Tab.LIST -> listTab()
            Tab.TASKS -> tasksTab()
        }
        col.addView(content, ui.lp(h = 0, weight = 1f))
        statusBar(dark = true)
        page.removeAllViews()
        page.addView(col)
        afterRender?.invoke()
        afterRender = null
        main.post { focusSink() }
    }

    internal var afterRender: (() -> Unit)? = null

    /** Full-screen page without tabs (pairing, counting). */
    internal fun showFull(v: View, dark: Boolean) {
        fullScreen = true
        statusBar(dark)
        page.removeAllViews()
        page.addView(v)
    }

    internal fun statusBar(dark: Boolean) {
        window.statusBarColor = if (dark) C.NAVY else C.CANVAS
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    /** Things waiting in the To-do tab (badge). */
    internal fun todoCount(): Int = toCount.size + checks().size + backInStock().size + (if (update != null) 1 else 0)

    /** Navy bar of the inner pages: Back · store · week · what is in the list. */
    internal fun topBar(back: Boolean, onBack: () -> Unit = { onBackPressed() }): View {
        val r = ui.row()
        r.setBackgroundColor(C.NAVY)
        r.setPadding(ui.dp(if (back) 8 else 16), 0, ui.dp(16), 0)
        if (back) {
            val b = ui.icon(Icons.BACK, Color.WHITE, 40, 2.4f).apply {
                setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                contentDescription = "Indietro"
            }
            ui.tap(b, 10, true) { onBack() }
            r.addView(b, ui.fixed(40, 40))
        }
        r.addView(ui.text(store, 15f, Color.WHITE, ui.bold))
        val wk = list.server?.week?.let { weekNo(it).lowercase() }.orEmpty()
        if (wk.isNotEmpty()) r.addView(ui.text("  · $wk", 14f, C.ON_NAVY_MUTED))
        r.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
        r.addView(ui.num("${list.ordered.size} art · ${list.boxes} colli", 13f, C.ON_NAVY))
        r.layoutParams = ui.lp(h = ui.dp(52))
        return r
    }

    /** Navy header with a back arrow, a title and a line under it (List, To do). */
    internal fun header(title: String, sub: String?, subColor: Int, onBack: () -> Unit): LinearLayout {
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
        tc.addView(ui.text(title, 18f, Color.WHITE, ui.bold).oneLine())
        if (!sub.isNullOrEmpty()) tc.addView(ui.text(sub, 12f, subColor, ui.medium).oneLine(), ui.lp(top = 2))
        t.addView(tc, ui.lp(w = 0, weight = 1f, start = 10))
        head.addView(t)
        return head
    }

    // ================================================================== scanning
    internal fun onScan(raw: String) {
        if (fullScreen) return
        val cat = catalog
        if (cat == null) {
            beep(Kind.UNKNOWN)
            AlertDialog.Builder(this).setTitle("Catalogo mancante")
                .setMessage("Collega lo scanner al Wi-Fi: il catalogo si scarica da solo.")
                .setPositiveButton("Aggiorna ora") { _, _ -> refreshCatalog(true) }.setNegativeButton("Chiudi", null).show()
            return
        }
        val key = Catalog.keys(raw).firstOrNull() ?: return
        val p = pending
        if (sheet.isOpen && p != null && Catalog.keys(p.scan).firstOrNull() == key) {
            // the same item again: one more box (still confirmed with the button)
            if (p.orderable) { p.boxes += 1; beep(Kind.ORDER); showItem() }
            return
        }
        openItem(raw)
    }

    // ================================================================== list changes
    internal fun change(line: Line) {
        list.set(line)
        api.savePending(list)
        sync(quiet = true)
    }

    internal fun removeLine(ean: String) {
        list.remove(ean)
        api.savePending(list)
        sync(quiet = true)
    }

    /** Lines worth a second look before sending. level: 0 much stock · 1 already coming · 2 not orderable. */
    internal class Check(val line: Line, val text: String, val level: Int)

    internal fun checks(): List<Check> {
        val cat = catalog
        val out = ArrayList<Check>()
        for (l in list.ordered) {
            val info = cat?.info(l.ean)
            val kind = cat?.judge(l.ean)?.kind
            when {
                l.kind != "urgent" && kind != null && kind != Kind.ORDER && kind != Kind.SUBSTITUTE -> out.add(Check(l, "Non più ordinabile", 2))
                l.reason.isNullOrEmpty() && Catalog.overstocked(info) -> {
                    val c = info!!.coverDays((info.transitBoxes ?: 0.0) * (info.pack ?: 0.0))
                    out.add(Check(l, "Hai già ${n(info.stock)} pz" + (if (c != null) " · bastano ${Catalog.months(c)}" else ""), 0))
                }
                (info?.transitBoxes ?: 0.0) > 0 && l.reason.isNullOrEmpty() -> out.add(Check(l, "${n(info!!.transitBoxes)} ${colli(info.transitBoxes!!.toInt())} già in arrivo", 1))
            }
        }
        return out
    }

    /** Reminders that are orderable again and not in the list yet. */
    internal fun backInStock(): List<Line> = list.server?.reminders.orEmpty()
        .filter { r -> catalog?.judge(r.ean)?.kind == Kind.ORDER && (list.line(r.ean)?.boxes ?: 0) == 0 }

    // ================================================================== formatting
    internal val rome: ZoneId = ZoneId.of("Europe/Rome")
    internal val store get() = Api.STORES[api.store] ?: "—"

    internal fun weekNo(week: String): String = runCatching { "Sett. " + LocalDate.parse(week).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }.getOrDefault("")

    internal fun shortDay(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(rome).format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ITALIAN))

    internal fun dayTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(rome).format(DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.ITALIAN))

    internal fun countdown(deadline: Long): String {
        val left = (deadline - System.currentTimeMillis()) / 60_000
        if (left <= 0) return "chiusa"
        val d = left / (60 * 24)
        val h = (left / 60) % 24
        val m = left % 60
        return when { d > 0 -> "$d g $h h"; h > 0 -> "$h h $m min"; else -> "$m min" }
    }

    internal fun eur(v: Double?): String = if (v == null || v <= 0) "–" else "€ " + String.format(Locale.ITALY, "%.2f", v)
    internal fun n(v: Double?): String = when {
        v == null -> "–"
        v == Math.floor(v) -> v.toLong().toString()
        else -> String.format(Locale.ITALY, "%.1f", v)
    }
    internal fun colli(k: Int) = if (k == 1) "collo" else "colli"

    internal fun packOf(ean: String): Double? =
        catalog?.info(ean)?.pack?.takeIf { it > 0 } ?: catalog?.find(ean)?.pack?.takeIf { it > 0 }

    internal fun kindColor(k: Kind) = when (k) {
        Kind.ORDER -> C.GREEN
        Kind.SUBSTITUTE -> C.BLUE
        Kind.NOT_AVAILABLE -> C.AMBER
        Kind.URGENT_ONLY -> C.ORANGE
        Kind.DIRECT_ONLY -> C.RED
        Kind.UNKNOWN -> C.FAINT
    }

    internal fun lineColor(kind: String) = when (kind) {
        "sub" -> C.BLUE
        "urgent" -> C.ORANGE
        "remind" -> C.AMBER
        else -> C.GREEN
    }

    internal fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    internal fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun useImageUrl() {
        catalog?.imageUrl?.takeIf { it.contains("{ean}") }?.let { ui.images?.template = it }
    }

    internal fun catalogDate(): String = runCatching { dayTime(Instant.parse(catalog!!.publishedAt).toEpochMilli()).substring(0, 5) }.getOrDefault("")

    // ================================================================== server
    /** Sends this scanner's changes and picks up the other scanners' ones. */
    internal fun sync(quiet: Boolean) {
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
                        for (l in sent) list.pending.remove(l.ean)
                        api.savePending(list)
                    }
                }
                // never redraw under the user's thumb (open card, count, typing)
                if (!fullScreen && !sheet.isOpen && search.isEmpty() && (e == null || was != offline)) render()
            }
        }
    }

    internal fun unpaired() {
        api.forget()
        catalog = null
        list = StoreList()
        sheet.close(false)
        showPairing("Scanner scollegato dall'ufficio acquisti: inserisci un nuovo codice.")
    }

    internal fun refreshCatalog(manual: Boolean) {
        io.execute {
            val r = runCatching { api.refreshCatalog() }
            val loaded = if (r.getOrNull() == true || catalog == null) api.loadCatalog() else catalog
            main.post {
                val e = r.exceptionOrNull()
                if (e is ApiError && e.status == 401) return@post unpaired()
                val changed = loaded !== catalog
                catalog = loaded
                useImageUrl()
                if (changed && !fullScreen && !sheet.isOpen) render()
                if (manual) toast(when {
                    e == null && loaded != null -> "Catalogo aggiornato · ${loaded.size} articoli"
                    e is ApiError && e.status == 404 -> "Catalogo non ancora pubblicato"
                    else -> "Nessuna connessione: uso l'ultimo catalogo"
                })
            }
        }
    }

    internal fun menu() {
        val items = arrayOf("Aggiorna catalogo e lista", "Controlla aggiornamenti dell'app", "Stato dello scanner", "Scollega questo scanner")
        AlertDialog.Builder(this).setTitle(api.deviceName.ifEmpty { "Scanner" } + " · app " + Updater.installedName(this)).setItems(items) { _, w ->
            when (w) {
                0 -> { refreshCatalog(true); sync(false) }
                1 -> checkUpdate(true)
                2 -> scannerStatus()
                3 -> AlertDialog.Builder(this).setTitle("Scollegare lo scanner?")
                    .setMessage("Servirà un nuovo codice dall'ufficio acquisti." +
                        (if (list.pending.isNotEmpty()) "\n${list.pending.size} modifiche non ancora inviate andranno perse." else ""))
                    .setPositiveButton("Scollega") { _, _ -> unpaired() }.setNegativeButton("Annulla", null).show()
            }
        }.show()
    }

    /** What DataWedge says and how the last scans arrived: tells a device problem from an app problem. */
    private fun scannerStatus() {
        val ok = dwActive == DataWedge.PROFILE
        val msg = buildString {
            if (!Scanners.isZebra) {
                append(if (Scanners.isHoneywell) "Scanner Honeywell: l'app chiede le letture direttamente al lettore." else
                    "Scanner ${Scanners.brand.ifBlank { "non Zebra" }}: l'app ascolta i lettori più diffusi " +
                        "(Honeywell, Urovo, Newland, iData, Sunmi, Chainway, Seuic, Datalogic) e i codici scritti come tastiera.")
                append("\nSe il codice non arriva: nelle impostazioni dello scanner scegli l'uscita «Intent / Broadcast» " +
                    "oppure «Tastiera», senza toccare la casella di ricerca.")
            } else append(if (ok) "✓ Il tasto giallo funziona in tutta l'app." else if (dwActive == null && dwResult == null)
                "DataWedge non risponde: lo scanner potrebbe non essere uno Zebra o DataWedge è disattivato." else
                "⚠ Lo scanner usa il profilo «${dwActive ?: "?"}»: i codici arrivano come tastiera. L'app li legge lo stesso, ma è meglio ripristinare.")
            append("\n\nModello: ").append(Build.MANUFACTURER ?: "").append(" ").append(Build.MODEL ?: "")
            if (Scanners.isZebra) {
                append("\nProfilo attivo: ").append(dwActive ?: "—")
                append("\nImpostazione: ").append(dwResult ?: "nessuna risposta")
            }
            append("\nLetture: ").append(scansIntent).append(" dirette · ").append(scansKeys).append(" da tastiera · ")
                .append(scansField).append(" scritte nel campo")
        }
        AlertDialog.Builder(this).setTitle("Stato dello scanner").setMessage(msg)
            .setPositiveButton("Ripristina lo scanner") { _, _ ->
                dwTries = 0; dwResult = null; dwActive = null; api.dwOk = false
                if (Scanners.isHoneywell) { Scanners.release(this); main.postDelayed({ Scanners.claim(this) }, 500) }
                else DataWedge.configure(this)
                focusSink()
                main.postDelayed({ scannerStatus() }, 2500)
            }.setNegativeButton("Chiudi", null).show()
    }

    // ================================================================== app update
    internal var update: AppUpdate? = null
    private var updateAt = 0L

    internal fun checkUpdate(manual: Boolean) {
        updateAt = System.currentTimeMillis()
        Thread {
            val u = Updater.latest(api.baseUrl)
            val mine = Updater.installed(this)
            main.post {
                val had = update
                update = if (AppUpdate.isNewer(u, mine)) u else null
                if (had?.versionCode != update?.versionCode && api.paired && !fullScreen && !sheet.isOpen) render()
                if (manual) {
                    val nu = update
                    if (nu != null) startUpdate(nu)
                    else toast(if (u == null) "Nessuna connessione: riprova più tardi" else "App aggiornata · ${Updater.installedName(this)}")
                }
            }
        }.start()
    }

    internal fun startUpdate(u: AppUpdate) {
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this).setTitle("Permetti gli aggiornamenti")
                .setMessage("Solo la prima volta: attiva «Consenti da questa fonte», poi torna qui e tocca di nuovo «Aggiorna».")
                .setPositiveButton("Apri impostazioni") { _, _ -> Updater.openInstallPermission(this) }
                .setNegativeButton("Annulla", null).show()
            return
        }
        api.savePending(list)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val pct = ui.text("0%", 13f, C.MUTED)
        val c = ui.col(20)
        c.addView(ui.text("Versione ${Updater.installedName(this)} → ${u.versionName}", 14f, C.INK, ui.medium))
        c.addView(bar, ui.lp(top = 14))
        c.addView(pct, ui.lp(top = 6))
        val dlg = AlertDialog.Builder(this).setTitle("Aggiornamento").setView(c).setCancelable(false).show()
        Thread {
            val r = runCatching {
                val f = Updater.download(this, api.baseUrl, u) { p -> main.post { bar.progress = p; pct.text = "$p%" } }
                main.post { pct.text = "Installazione…" }
                Updater.install(this, f)
            }
            main.post {
                dlg.dismiss()
                r.exceptionOrNull()?.let { e ->
                    AlertDialog.Builder(this).setTitle("Aggiornamento non riuscito")
                        .setMessage((e.message ?: "Errore") + "\nRiprova con il Wi-Fi.").setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    // ================================================================== sound
    internal fun beep(k: Kind) {
        val g = tones ?: return
        when (k) {
            Kind.ORDER -> g.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            Kind.SUBSTITUTE, Kind.NOT_AVAILABLE, Kind.URGENT_ONLY -> g.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
            else -> { g.startTone(ToneGenerator.TONE_SUP_ERROR, 380); vibrate() }
        }
    }

    @Suppress("DEPRECATION")
    internal fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ================================================================== count screen state
    /** Where Back goes from the count screen. */
    internal var countBack: (() -> Unit)? = null
}
