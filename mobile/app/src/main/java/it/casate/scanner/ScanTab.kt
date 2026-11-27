package it.casate.scanner

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

private var beam: ValueAnimator? = null

/**
 * Home (v3 look): navy header with this week's list, what is left to do, the scan target, code / name
 * search, the last lines put in the list, and "Rivedi e invia lista" at the bottom.
 */
internal fun MainActivity.scanTab(): View {
    beam?.cancel()
    searchExit = null
    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)
    val head = ui.col()
    head.setBackgroundColor(C.NAVY)
    head.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18))
    val top = ui.row()
    top.addView(ui.text("C&T", 12f, Color.WHITE, ui.bold).apply { gravity = Gravity.CENTER; background = ui.bg(C.BLUE, 10) }, ui.fixed(34, 34))
    val who = ui.col()
    who.addView(ui.text(store, 16f, Color.WHITE, ui.bold).oneLine())
    who.addView(ui.text(api.deviceName, 12f, C.ON_NAVY_MUTED).oneLine(), ui.lp(top = 2))
    top.addView(who, ui.lp(w = 0, weight = 1f, start = 10))
    val pill = ui.row()
    pill.background = ui.bg(C.NAVY3, 999)
    pill.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
    val waiting = list.pending.size
    pill.addView(ui.dot(if (offline) C.AMBER else C.MINT, 8))
    pill.addView(ui.text(when {
        offline && waiting > 0 -> "Offline · $waiting da inviare"
        offline -> "Offline"
        catalog != null -> "Catalogo " + catalogDate()
        else -> "Senza catalogo"
    }, 12f, C.ON_NAVY), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 6))
    ui.tap(pill, 999, true) { menu() }
    pill.contentDescription = "Menu dello scanner"
    top.addView(pill, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT))
    head.addView(top)

    // this week's list
    val card = ui.col(14)
    card.background = ui.bg(C.NAVY2, 16, C.NAVY_LINE)
    val v = list.server
    val r1 = ui.row()
    r1.addView(ui.text("LISTA DETERSIVI" + (v?.week?.let { " · " + weekNo(it).uppercase() } ?: ""), 12f, C.ON_NAVY_MUTED, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f))
    if (v != null) r1.addView(ui.text("chiude " + shortDay(v.deadline), 12f, C.YELLOW, ui.medium))
    card.addView(r1)
    val stats = ui.row()
    fun stat(value: String, label: String, color: Int) {
        val c = ui.col()
        c.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        c.addView(ui.num(value, 26f, color))
        c.addView(ui.text(label, 12f, C.ON_NAVY_MUTED).oneLine(), ui.lp(top = 2))
        stats.addView(c, ui.lp(w = 0, weight = 1f))
    }
    stat("${list.ordered.size}", "articoli", Color.WHITE)
    stat("${list.boxes}", "colli", Color.WHITE)
    stat("${list.urgent}", "non in Griglia", if (list.urgent > 0) C.CORAL else Color.WHITE)
    card.addView(stats, ui.lp(top = 8))
    if (v != null) {
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
        list.submitted -> "Inviata " + dayTime(v.list!!.submittedAt) + " · modificabile per " + countdown(v.deadline)
        else -> "Mancano " + countdown(v.deadline) + " · la lista si salva da sola"
    }
    card.addView(ui.text(line, 12f, if (list.submitted && !list.changedSinceSubmit) C.MINT else C.ON_NAVY_MUTED).oneLine(), ui.lp(top = 8))
    ui.tap(card, 16, true) { go(MainActivity.Tab.LIST) }
    head.addView(card, ui.lp(top = 16))
    root.addView(head)

    val body = ui.col()
    body.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(12))
    val rest = ui.col()
    flash?.let { msg ->
        flash = null
        val b = ui.row()
        b.background = ui.bg(C.GREEN_SOFT, 14)
        b.setPadding(ui.dp(14), ui.dp(11), ui.dp(14), ui.dp(11))
        b.addView(ui.icon(Icons.CHECK, C.GREEN_INK, 18, 2.6f))
        b.addView(ui.text(msg, 14f, C.GREEN_INK, ui.bold).lines(2), ui.lp(w = 0, weight = 1f, start = 10))
        ui.tap(b, 14) { go(MainActivity.Tab.LIST) }
        rest.addView(b, ui.lp(bottom = 12))
        b.alpha = 0f
        b.animate().alpha(1f).setDuration(200).start()
    }
    update?.let { u ->
        val b = ui.row()
        b.background = ui.bg(C.BLUE_SOFT, 14, 0xFFC6D5FF.toInt())
        b.setPadding(ui.dp(14), ui.dp(8), ui.dp(8), ui.dp(8))
        b.addView(ui.icon(Icons.REFRESH, C.BLUE_INK, 18, 2.2f))
        val t = ui.col()
        t.addView(ui.text("Nuova versione dell'app · ${u.versionName}", 14f, C.BLUE_INK, ui.bold).oneLine())
        t.addView(ui.text("Un minuto · lista e collegamento restano", 12f, C.BLUE_INK).oneLine(), ui.lp(top = 1))
        b.addView(t, ui.lp(w = 0, weight = 1f, start = 10))
        b.addView(ui.button("Aggiorna", C.BLUE, size = 14f) { startUpdate(u) }.apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) },
            ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, h = ui.dp(40)))
        rest.addView(b, ui.lp(bottom = 12))
    }

    // what is left to do before sending
    val nCount = toCount.size
    val nCheck = checks().size
    val back = backInStock()
    if (nCount > 0 || nCheck > 0 || back.isNotEmpty()) {
        val c = ui.col()
        c.background = ui.bg(C.CARD, 16, C.LINE)
        c.setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(4))
        c.addView(ui.text("DA FARE PRIMA DI INVIARE", 12f, C.MUTED, ui.bold))
        fun todo(icon: Icons.Spec, fg: Int, fill: Int, title: String, sub: String, count: Int) {
            val r = ui.row()
            r.setPadding(0, ui.dp(8), 0, ui.dp(8))
            r.addView(ui.iconBadge(icon, fill, fg, 34, 18, 2.2f))
            val t = ui.col()
            t.addView(ui.text(title, 14f, C.INK, ui.bold).oneLine())
            t.addView(ui.text(sub, 12f, C.MUTED).oneLine(), ui.lp(top = 1))
            r.addView(t, ui.lp(w = 0, weight = 1f, start = 10))
            r.addView(ui.num("$count", 15f, fg))
            r.addView(ui.icon(Icons.NEXT, C.FAINT, 16, 2.2f), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginStart = ui.dp(6) })
            ui.tap(r, 10) { go(MainActivity.Tab.TASKS) }
            c.addView(r)
        }
        if (nCount > 0) todo(Icons.LIST, C.AMBER_INK, C.AMBER_SOFT, "Da contare", "Conta la giacenza: l'ufficio la corregge", nCount)
        if (nCheck > 0) todo(Icons.ALERT, C.AMBER_INK, C.AMBER_SOFT, "Da controllare", "Scorta alta, già in arrivo o non più ordinabile", nCheck)
        if (back.isNotEmpty()) todo(Icons.REFRESH, C.GREEN_INK, C.GREEN_SOFT, "Di nuovo disponibili", "Li avevi chiesti: ora si possono ordinare", back.size)
        rest.addView(c, ui.lp(bottom = 12))
    }

    // requests to the purchasing office: one tap to the list with the answers
    val reqs = list.server?.requests.orEmpty()
    val rc = Requests.counts(reqs)
    if ((rc[Requests.Filter.ALL] ?: 0) > 0) {
        val toLook = rc[Requests.Filter.TODO] ?: 0
        val waiting = rc[Requests.Filter.WAITING] ?: 0
        val r = ui.row()
        r.background = ui.bg(if (toLook > 0) C.AMBER_SOFT else C.CARD, 16, if (toLook > 0) C.AMBER_LINE else C.LINE)
        r.setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(10))
        r.addView(ui.iconBadge(Icons.SEND, if (toLook > 0) C.CARD else C.BLUE_SOFT, if (toLook > 0) C.AMBER_INK else C.BLUE_INK, 34, 18, 2.2f))
        val t = ui.col()
        t.addView(ui.text("Richieste all'ufficio", 14f, C.INK, ui.bold).oneLine())
        t.addView(ui.text(listOfNotNull(
            if (toLook > 0) (if (toLook == 1) "1 risposta da leggere" else "$toLook risposte da leggere") else null,
            if (waiting > 0) "$waiting in attesa" else null,
        ).joinToString(" · ").ifEmpty { "Tutte con risposta · vedi lo storico" }, 12f, if (toLook > 0) C.AMBER_INK else C.MUTED).oneLine(), ui.lp(top = 1))
        r.addView(t, ui.lp(w = 0, weight = 1f, start = 10))
        if (toLook > 0) r.addView(ui.num("$toLook", 15f, C.AMBER_INK))
        r.addView(ui.icon(Icons.NEXT, C.FAINT, 16, 2.2f), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginStart = ui.dp(6) })
        ui.tap(r, 16) { reqFilter = null; go(MainActivity.Tab.REQUESTS) }
        rest.addView(r, ui.lp(bottom = 12))
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
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x002F6BFF, 0x552F6BFF, 0x002F6BFF))
    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(16)))
    laser.addView(View(this).apply { setBackgroundColor(C.BLUE) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(2), Gravity.CENTER_VERTICAL))
    target.addView(laser, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(16), Gravity.TOP).apply { setMargins(ui.dp(30), ui.dp(18), ui.dp(30), 0) })
    if (catalog == null) ui.tap(target, 20) { refreshCatalog(true) }
    rest.addView(target, ui.lp(h = ui.dp(170)))

    // code or name
    val results = ui.col()
    val sr = ui.row()
    sr.background = ui.bg(C.CARD, 14, C.LINE)
    sr.setPadding(ui.dp(12), 0, ui.dp(6), 0)
    sr.addView(ui.icon(Icons.SEARCH, C.MUTED, 18, 2f))
    val input = EditText(this).apply {
        hint = "Codice o nome dell'articolo"
        setHintTextColor(C.FAINT)
        setTextColor(C.INK)
        setText(search)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        textSize = 14f
        background = null
        setPadding(ui.dp(8), 0, ui.dp(4), 0)
    }
    sr.addView(input, ui.lp(w = 0, h = ui.dp(46), weight = 1f))
    val clear = ui.icon(Icons.CLOSE, C.FAINT, 34, 2f).apply { setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9)); contentDescription = "Cancella" }
    clear.visibility = if (search.isEmpty()) View.GONE else View.VISIBLE
    sr.addView(clear, ui.fixed(34, 34))
    val go = ui.button("Cerca", C.BLUE, size = 14f) {}.apply { setPadding(ui.dp(14), 0, ui.dp(14), 0) }
    sr.addView(go, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, h = ui.dp(38), start = 4))

    // the last lines put in the list
    val lastBox = ui.col()
    val last = list.lines().take(4)
    if (last.isNotEmpty()) {
        val hr = ui.row()
        hr.addView(ui.text("ULTIMI IN LISTA", 12f, C.MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
        val all = ui.row()
        all.setPadding(ui.dp(8), ui.dp(6), ui.dp(4), ui.dp(6))
        all.addView(ui.text("Vedi tutti", 12f, C.BLUE, ui.bold))
        all.addView(ui.icon(Icons.NEXT, C.BLUE, 14, 2.4f))
        ui.tap(all, 8) { go(MainActivity.Tab.LIST) }
        hr.addView(all)
        lastBox.addView(hr, ui.lp(top = 14))
        for ((i, l) in last.withIndex()) {
            val r = ui.row()
            r.setPadding(ui.dp(2), 0, ui.dp(2), 0)
            r.addView(ui.dot(lineColor(l.kind)))
            r.addView(ui.text(l.name.ifEmpty { l.ean }, 14f, C.INK, ui.medium).oneLine(), ui.lp(w = 0, weight = 1f, start = 10))
            when (l.kind) {
                "urgent" -> r.addView(ui.chip("NON IN GRIGLIA", C.ORANGE, C.ORANGE_SOFT, 11f))
                "remind" -> r.addView(ui.chip("DA RICORDARE", C.AMBER_INK, C.AMBER_SOFT, 11f))
                else -> {
                    r.addView(ui.num("${l.boxes}", 14f))
                    r.addView(ui.text(" " + colli(l.boxes), 12f, C.MUTED))
                }
            }
            ui.tap(r, 8) { openItem(l.origEan ?: l.ean) }
            if (i > 0) lastBox.addView(ui.divider())
            lastBox.addView(r, ui.lp(h = ui.dp(44), top = if (i == 0) 4 else 0))
        }
    }
    body.addView(rest)
    body.addView(sr, ui.lp(h = ui.dp(48), top = 12))
    body.addView(results)
    body.addView(lastBox)

    val bar = ui.col()
    // «search» mode: while the field is in use the navy header and the bottom button go away, so the
    // results get the whole screen above the keyboard and nobody taps «Rivedi e invia lista» by mistake.
    fun mode(on: Boolean) {
        head.visibility = if (on) View.GONE else View.VISIBLE
        bar.visibility = if (on) View.GONE else View.VISIBLE
        searchExit = if (on) ({ input.setText(""); input.clearFocus(); hideKeyboard(input); mode(false) }) else null
    }
    fun searchNow(q: String) {
        search = q
        results.removeAllViews()
        val t = q.trim()
        val cat = catalog
        val searching = t.length >= 2
        clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        rest.visibility = if (searching) View.GONE else View.VISIBLE
        lastBox.visibility = if (searching) View.GONE else View.VISIBLE
        if (searching || input.hasFocus()) mode(true)
        if (cat == null || !searching) return
        if (t.all { it.isDigit() } && t.length < 4) {
            results.addView(ui.text("Scrivi almeno 4 cifre del codice", 13f, C.MUTED), ui.lp(top = 12))
            return
        }
        val found = cat.search(t, 40)
        val head2 = ui.row()
        head2.addView(ui.text(when (found.size) { 0 -> "Nessun articolo"; 1 -> "1 articolo"; 40 -> "Primi 40 articoli · scrivi di più"; else -> "${found.size} articoli" },
            12f, C.MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
        results.addView(head2, ui.lp(top = 12))
        val c = ui.col()
        c.background = ui.bg(C.CARD, 14, C.LINE)
        c.setPadding(ui.dp(10), ui.dp(2), ui.dp(6), ui.dp(2))
        if (found.isEmpty()) c.addView(ui.text("Prova con un'altra parola o con il codice a barre", 13f, C.MUTED).apply { setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(14)) })
        for ((i, it) in found.withIndex()) {
            if (i > 0) c.addView(ui.divider(60))
            c.addView(searchRow(it) { hideKeyboard(input); openItem(it.ean) })
        }
        results.addView(c, ui.lp(top = 6))
    }
    var run: Runnable? = null
    input.setOnEditorActionListener { tv, actionId, event ->
        val enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
            (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
        if (enter) {
            val s = tv.text.toString().trim()
            if (s.length >= 6 && s.all { it.isDigit() }) { tv.text = ""; search = ""; hideKeyboard(tv); onScan(s) } else { searchNow(s); hideKeyboard(tv) }
        }
        enter
    }
    go.setOnClickListener {
        val s = input.text.toString().trim()
        if (s.length >= 6 && s.all { it.isDigit() }) { input.setText(""); search = ""; hideKeyboard(input); onScan(s) }
        else { searchNow(s); if (s.length >= 2) hideKeyboard(input) else input.requestFocus() }
    }
    ui.tap(clear, 999) { input.setText(""); input.requestFocus() }
    input.setOnFocusChangeListener { _, has -> if (has) mode(true) else if (input.text.isNullOrBlank()) mode(false) }
    var burst = 0L
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(e: android.text.Editable?) {
            run?.let { main.removeCallbacks(it) }
            val q = e?.toString().orEmpty()
            val now = System.currentTimeMillis()
            if (q.length <= 1) burst = now
            // the scanner typed a whole code in a burst (keystroke mode, no ENTER): treat it as a scan
            run = if (KeyScan.looksScanned(q.trim(), now - burst)) Runnable {
                if (input.text.toString() == q) { input.setText(""); search = ""; hideKeyboard(input); onScan(q.trim()) }
            }.also { main.postDelayed(it, 150) }
            else Runnable { searchNow(q) }.also { main.postDelayed(it, 220) }
        }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    if (search.isNotEmpty()) searchNow(search)
    root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, ui.lp(h = 0, weight = 1f))

    bar.setBackgroundColor(C.CARD)
    bar.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(16))
    val sent = list.submitted && !list.changedSinceSubmit
    bar.addView(ui.iconButton(if (sent) "Vedi la lista inviata" else "Rivedi e invia lista", Icons.ARROW, C.NAVY, after = true) { go(MainActivity.Tab.LIST) }, ui.lp(h = ui.dp(52)))
    root.addView(bar)

    target.post {
        val range = (target.height - ui.dp(52)).toFloat().coerceAtLeast(1f)
        beam = ObjectAnimator.ofFloat(laser, "translationY", 0f, range).apply {
            duration = 1300; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }
    return root
}

/** What a search result is, in the words of the item card. */
private fun searchLabel(item: Item, k: Kind): String = when (k) {
    Kind.ORDER -> "Ordinabile"
    Kind.SUBSTITUTE -> "Sostituito" + (item.substituteName.takeIf { it.isNotEmpty() }?.let { " da $it" } ?: "")
    Kind.NOT_AVAILABLE -> "Non disponibile questa settimana"
    Kind.URGENT_ONLY -> "Non in Griglia · solo urgenze"
    Kind.DIRECT_ONLY -> if (item.griglia == 2) "Tolto dalla Griglia" else "Non in Griglia"
    Kind.UNKNOWN -> "Sconosciuto"
}

/** One search result: photo (tap = open, not zoom) · full name on two lines · what it is · barcode. */
private fun MainActivity.searchRow(item: Item, onClick: () -> Unit): View {
    val k = Catalog.judgeItem(item).kind
    val fg = kindColor(k)
    val r = ui.row()
    r.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(10))
    r.minimumHeight = ui.dp(68)
    r.addView(ui.photo(item.ean, item.name, 48, fg, zoomable = false))
    val c = ui.col()
    c.addView(ui.text(item.name.ifEmpty { item.ean }, 15f, C.INK, ui.medium).lines(2))
    val sub = ui.row()
    sub.addView(ui.dot(fg, 7), LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)).apply { marginEnd = ui.dp(6) })
    sub.addView(ui.text(searchLabel(item, k), 12f, fg, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f))
    c.addView(sub, ui.lp(top = 4))
    c.addView(ui.num(item.ean, 11f, C.FAINT), ui.lp(top = 2))
    r.addView(c, ui.lp(w = 0, weight = 1f, start = 12))
    r.addView(ui.icon(Icons.NEXT, C.FAINT, 18, 2.2f), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginStart = ui.dp(6) })
    ui.tap(r, 12) { onClick() }
    return r
}
