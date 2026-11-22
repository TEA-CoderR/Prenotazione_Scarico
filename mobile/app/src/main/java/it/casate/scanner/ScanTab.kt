package it.casate.scanner

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Faint grid behind the scan window (the "scanner" look). */
private class Grid(ctx: android.content.Context, private val step: Float) : View(ctx) {
    private val p = Paint().apply { color = 0x142F6BFF; strokeWidth = 1f }
    override fun onDraw(c: Canvas) {
        var x = step
        while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), p); x += step }
        var y = step
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, p); y += step }
    }
}

private var beam: ValueAnimator? = null

/** Tab 1 · Scansiona: the scan window, code / name search, what was just added. Nothing else. */
internal fun MainActivity.scanTab(): View {
    beam?.cancel()
    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)

    // ---------------------------------------------------------------- dark header
    val head = ui.col()
    head.background = vertical(C.NAVY, 0xFF101B33.toInt())
    head.setPadding(ui.dp(18), ui.dp(12), ui.dp(10), ui.dp(18))
    val top = ui.row()
    val who = ui.col()
    who.addView(ui.text(store, 20f, Color.WHITE, ui.bold).oneLine())
    val v = list.server
    val sub = listOfNotNull(v?.week?.let { weekNo(it) }, api.deviceName.ifEmpty { null }).joinToString(" · ")
    who.addView(ui.text(sub, 12f, C.ON_NAVY_MUTED).oneLine(), ui.lp(top = 2))
    top.addView(who, ui.lp(w = 0, weight = 1f))
    // connection state: a dot (green online, amber offline / waiting)
    val waiting = list.pending.size
    val net = ui.row()
    net.background = ui.bg(0x1FFFFFFF, 999)
    net.setPadding(ui.dp(10), ui.dp(6), ui.dp(12), ui.dp(6))
    net.addView(ui.dot(if (offline) C.AMBER else C.MINT, 8), LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(6) })
    net.addView(ui.text(when { offline && waiting > 0 -> "Offline · $waiting"; offline -> "Offline"; catalog == null -> "Senza catalogo"; else -> "Online" }, 12f, C.ON_NAVY, ui.medium))
    top.addView(net)
    top.addView(ui.iconTap(Icons.MORE, Color.WHITE, "Menu", onDark = true) { menu() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginStart = ui.dp(2) })
    head.addView(top)

    // the scan window
    val win = FrameLayout(this)
    win.background = ui.bg(0xFF0E1830.toInt(), 22, 0xFF263556.toInt())
    win.clipToOutline = true
    win.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    win.addView(Grid(this, ui.dpf(18f)), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val corners = View(this).apply { background = Corners(C.BLUE, ui.dpf(3f), ui.dpf(26f), ui.dpf(16f), ui.dpf(6f)) }
    win.addView(corners, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val inner = ui.col()
    inner.gravity = Gravity.CENTER
    inner.addView(ui.icon(Icons.BARCODE, Color.WHITE, 42, 1.6f), LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    inner.addView(ui.text(if (catalog == null) "Catalogo non ancora scaricato" else "Premi il tasto giallo", 18f, Color.WHITE, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 10))
    inner.addView(ui.text(if (catalog == null) "Tocca qui per scaricarlo" else "e inquadra il codice a barre", 13f, C.ON_NAVY_MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
    win.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val laser = FrameLayout(this)
    laser.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x002F6BFF, 0x662F6BFF, 0x002F6BFF)) },
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(18)))
    laser.addView(View(this).apply { setBackgroundColor(0xFF6E97FF.toInt()) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(2), Gravity.CENTER_VERTICAL))
    win.addView(laser, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(18), Gravity.TOP).apply { setMargins(ui.dp(24), ui.dp(14), ui.dp(24), 0) })
    if (catalog == null) ui.tap(win, 22, true) { refreshCatalog(true) }
    head.addView(win, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(176)).apply { topMargin = ui.dp(14); marginEnd = ui.dp(8) })

    // code or name
    val sr = ui.row()
    sr.background = ui.bg(0x1AFFFFFF, 14, 0x26FFFFFF)
    sr.setPadding(ui.dp(14), 0, ui.dp(4), 0)
    sr.addView(ui.icon(Icons.SEARCH, C.ON_NAVY_MUTED, 18, 2f))
    val results = ui.col()
    val input = EditText(this).apply {
        hint = "Codice o nome"
        setHintTextColor(0xFF7F8BA6.toInt())
        setTextColor(Color.WHITE)
        setText(search)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        textSize = 15f
        background = null
        setPadding(ui.dp(10), 0, ui.dp(4), 0)
    }
    sr.addView(input, ui.lp(w = 0, h = ui.dp(48), weight = 1f))
    sr.addView(ui.iconTap(Icons.CLOSE, C.ON_NAVY_MUTED, "Cancella", onDark = true) { input.setText(""); hideKeyboard(input) })
    head.addView(sr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)).apply { topMargin = ui.dp(12); marginEnd = ui.dp(8) })
    root.addView(head)

    // ---------------------------------------------------------------- light body
    val body = ui.col()
    body.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16))
    val rest = ui.col()   // everything below the search results (hidden while searching)
    flash?.let { msg ->
        flash = null
        val b = ui.row()
        b.background = ui.bg(C.GREEN_SOFT, 14)
        b.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        b.addView(ui.icon(Icons.CHECK, C.GREEN_INK, 18, 2.6f))
        b.addView(ui.text(msg, 14f, C.GREEN_INK, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f, start = 10))
        rest.addView(b, ui.lp(bottom = 12))
        b.alpha = 0f
        b.animate().alpha(1f).setDuration(220).start()
        main.postDelayed({ b.animate().alpha(0f).setDuration(300).withEndAction { b.visibility = View.GONE }.start() }, 3500)
    }
    update?.let { u ->
        val b = ui.row()
        b.background = ui.bg(C.BLUE_SOFT, 14)
        b.setPadding(ui.dp(14), ui.dp(4), ui.dp(4), ui.dp(4))
        b.addView(ui.icon(Icons.REFRESH, C.BLUE_INK, 18, 2.2f))
        b.addView(ui.text("Nuova versione ${u.versionName}", 14f, C.BLUE_INK, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f, start = 10))
        b.addView(ui.outline("Aggiorna", C.BLUE_INK, 0xFFC6D5FF.toInt()) { startUpdate(u) })
        rest.addView(b, ui.lp(bottom = 12))
    }
    // this week's list in one card: tap = the List tab
    val lc = ui.card(18)
    lc.setPadding(ui.dp(16), ui.dp(14), ui.dp(12), ui.dp(14))
    val lr = ui.row()
    val lt = ui.col()
    lt.addView(ui.caption("Lista della settimana"))
    val nums = ui.row(Gravity.BOTTOM)
    nums.addView(ui.num("${list.ordered.size}", 24f))
    nums.addView(ui.text(" articoli   ", 13f, C.MUTED))
    nums.addView(ui.num("${list.boxes}", 24f))
    nums.addView(ui.text(" colli", 13f, C.MUTED))
    lt.addView(nums, ui.lp(top = 6))
    lr.addView(lt, ui.lp(w = 0, weight = 1f))
    val state = when {
        v == null -> ui.pill("NON SCARICATA", C.MUTED, 0xFFEEF1F6.toInt())
        list.submitted && !list.changedSinceSubmit -> ui.pill("INVIATA", C.GREEN_INK, C.GREEN_SOFT)
        list.submitted -> ui.pill("DA REINVIARE", C.AMBER_INK, C.AMBER_SOFT)
        else -> ui.pill("CHIUDE " + shortDay(v.deadline).uppercase(), C.BLUE_INK, C.BLUE_SOFT)
    }
    lr.addView(state)
    lr.addView(ui.icon(Icons.NEXT, C.FAINT, 18, 2.2f), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginStart = ui.dp(6) })
    lc.addView(lr)
    if (v != null && !list.submitted) {
        val f = ((System.currentTimeMillis() - (v.deadline - 7 * 86_400_000L)).toFloat() / (7 * 86_400_000L)).coerceIn(0.02f, 1f)
        val bar = ui.row()
        bar.background = ui.bg(0xFFE9EDF3.toInt(), 3)
        bar.addView(View(this).apply { background = ui.bg(if (f > 0.85f) C.RED else C.BLUE, 3) }, ui.lp(w = 0, h = ui.dp(5), weight = f))
        bar.addView(View(this), ui.lp(w = 0, h = ui.dp(5), weight = 1f - f + 0.0001f))
        lc.addView(bar, ui.lp(h = ui.dp(5), top = 12))
        lc.addView(ui.text("Mancano ${countdown(v.deadline)}", 12f, if (f > 0.85f) C.RED_INK else C.MUTED, ui.medium), ui.lp(top = 6))
    }
    ui.tap(lc, 18) { go(MainActivity.Tab.LIST) }
    rest.addView(lc)

    // the last items put in the list
    val last = list.lines().take(4)
    if (last.isNotEmpty()) {
        rest.addView(ui.caption("Ultimi aggiunti"), ui.lp(top = 18))
        val card = ui.card(18)
        card.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2))
        for ((i, l) in last.withIndex()) {
            if (i > 0) card.addView(ui.divider(52))
            val trail = if (l.kind == "remind") ui.chip("AVVISO", C.AMBER_INK, C.AMBER_SOFT, 10f) else ui.num("${l.boxes}", 16f)
            card.addView(ui.listRow(ui.photo(l.ean, l.name, 40, lineColor(l.kind)), l.name.ifEmpty { l.ean },
                when (l.kind) { "urgent" -> "Urgenza"; "sub" -> "Sostituto"; "remind" -> "Avvisami quando torna"; else -> colli(l.boxes) + (packOf(l.ean)?.let { " · ${n(it * l.boxes)} pz" } ?: "") },
                if (l.kind == "urgent") C.ORANGE else C.MUTED, trail) { openItem(l.origEan ?: l.ean) })
        }
        rest.addView(card, ui.lp(top = 8))
    }
    body.addView(results)
    body.addView(rest)

    fun searchNow(q: String) {
        search = q
        results.removeAllViews()
        val t = q.trim()
        val cat = catalog
        rest.visibility = if (t.length >= 2) View.GONE else View.VISIBLE
        if (cat == null || t.length < 2 || (t.all { it.isDigit() } && t.length < 4)) return
        val found = cat.search(t, 30)
        val card = ui.card(18)
        card.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2))
        if (found.isEmpty()) card.addView(ui.text("Nessun articolo", 14f, C.MUTED).apply { setPadding(ui.dp(4), ui.dp(16), 0, ui.dp(16)) })
        for ((i, it) in found.withIndex()) {
            if (i > 0) card.addView(ui.divider(52))
            val k = Catalog.judgeItem(it).kind
            card.addView(ui.listRow(ui.photo(it.ean, it.name, 40, kindColor(k)), it.name, it.ean, C.MUTED, ui.dot(kindColor(k), 10)) {
                hideKeyboard(input); openItem(it.ean)
            })
        }
        results.addView(ui.caption("Risultati · ${found.size}"))
        results.addView(card, ui.lp(top = 8))
    }
    var run: Runnable? = null
    input.setOnEditorActionListener { tv, actionId, event ->
        val enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
            (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
        if (enter) {
            val s = tv.text.toString().trim()
            if (s.length >= 6 && s.all { it.isDigit() }) { tv.text = ""; search = ""; hideKeyboard(tv); onScan(s) } else searchNow(s)
        }
        enter
    }
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(e: android.text.Editable?) {
            run?.let { main.removeCallbacks(it) }
            val q = e?.toString().orEmpty()
            run = Runnable { searchNow(q) }.also { main.postDelayed(it, 200) }
        }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    if (search.isNotEmpty()) searchNow(search)
    root.addView(ScrollView(this).apply { addView(body) }, ui.lp(h = 0, weight = 1f))

    // the beam sweeps while the screen waits for a scan
    win.post {
        val range = (win.height - ui.dp(46)).toFloat().coerceAtLeast(1f)
        beam = ObjectAnimator.ofFloat(laser, "translationY", 0f, range).apply {
            duration = 1500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }
    return root
}
