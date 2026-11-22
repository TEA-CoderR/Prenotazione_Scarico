package it.casate.scanner

import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Count the real stock (full screen, nothing else on it): pieces, shelf + back room ticked, who counted,
 * then HOLD the button to send (no extra confirmation dialog: holding is the confirmation).
 */
internal fun MainActivity.showCount(ean: String, name: String, sys: StoreInfo, after: (Int) -> Unit, back: () -> Unit) {
    var counted = 0
    var shelf = false
    var backroom = false
    val pack = sys.pack?.takeIf { it > 0 }?.toInt()
    countBack = back

    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)
    root.addView(topBar(back = true) { countBack = null; back() })

    val body = ui.col()
    body.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
    body.addView(ui.text("CONTA LA GIACENZA", 12f, C.MUTED, ui.bold), ui.lp(bottom = 8))
    val pr = ui.row()
    pr.addView(ui.photo(ean, name, 48, C.AMBER_INK))
    val pc = ui.col()
    pc.addView(ui.text(name.ifEmpty { ean }, 15f, C.INK, ui.bold).lines(2))
    pc.addView(ui.num("A sistema ${n(sys.stock)} pz", 12f, C.MUTED), ui.lp(top = 3))
    pr.addView(pc, ui.lp(w = 0, weight = 1f, start = 12))
    body.addView(pr)

    val card = ui.card(20)
    card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
    card.addView(ui.caption("Pezzi contati (non colli)"))
    val diff = ui.text("", 13f, C.MUTED, ui.medium).apply { gravity = Gravity.CENTER }
    val holdBox = FrameLayout(this)
    lateinit var refresh: () -> Unit
    val st = Stepper(ui, { counted }, { counted = it }, 0, 64, 48f, "Quanti pezzi?", onChange = { refresh() }, onDone = { refresh() })
    card.addView(st.view, ui.lp(top = 12))
    val quick = ui.row()
    quick.addView(ui.choice("Nessuno · 0", false, C.RED) { counted = 0; st.refresh(); refresh() }, ui.lp(w = 0, weight = 1f))
    if (pack != null && pack > 1) quick.addView(ui.choice("+1 collo · $pack pz", false) { counted += pack; st.refresh(); refresh() }, ui.lp(w = 0, weight = 1f, start = 8))
    card.addView(quick, ui.lp(top = 12))
    card.addView(diff, ui.lp(top = 12))
    body.addView(card, ui.lp(top = 16))

    fun check(label: String, sub: String, get: () -> Boolean, toggle: () -> Unit): View {
        val r = ui.row()
        r.background = ui.bg(C.CARD, 16, C.LINE)
        r.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        val box = FrameLayout(this)
        r.addView(box, ui.fixed(26, 26))
        val t = ui.col()
        t.addView(ui.text(label, 15f, C.INK, ui.bold))
        t.addView(ui.text(sub, 12f, C.MUTED), ui.lp(top = 2))
        r.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
        fun paint() { box.removeAllViews(); box.addView(ui.checkbox(get(), 26)) }
        paint()
        ui.tap(r, 16) { toggle(); paint(); r.haptic(); refresh() }
        return r
    }
    body.addView(check("Scaffale contato", "Anche dietro e in alto", { shelf }) { shelf = !shelf }, ui.lp(top = 12))
    body.addView(check("Magazzino controllato", "Retro, scatoloni aperti, esposizioni", { backroom }) { backroom = !backroom }, ui.lp(top = 8))

    body.addView(ui.caption("Chi ha contato"), ui.lp(top = 18))
    val who = EditText(this).apply {
        setText(api.counter)
        hint = "Nome e cognome"
        setSingleLine()
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        background = ui.bg(C.CARD, 14, C.LINE2)
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
    }
    who.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { refresh() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    body.addView(who, ui.lp(top = 6))
    val scroll = ScrollView(this).apply { addView(body) }
    root.addView(scroll, ui.lp(h = 0, weight = 1f))

    val bar = ui.col()
    bar.setBackgroundColor(Color.WHITE)
    bar.elevation = ui.dpf(8f)
    bar.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(16))
    bar.addView(holdBox)
    bar.addView(ui.text("L'ufficio acquisti corregge la giacenza nel gestionale", 12f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 8))
    root.addView(bar)

    var shown = ""
    refresh = {
        val d = counted - (sys.stock ?: 0.0)
        diff.text = when {
            d == 0.0 -> "Uguale al sistema"
            d < 0 -> "${n(-d)} pz in meno del sistema"
            else -> "${n(d)} pz in più del sistema"
        }
        diff.setTextColor(if (d == 0.0) C.MUTED else if (d < 0) C.RED_INK else C.BLUE_INK)
        val missing = when {
            counted.toDouble() == Math.round(sys.stock ?: -1.0).toDouble() -> "Uguale al sistema: niente da segnalare"
            !shelf || !backroom -> "Spunta scaffale e magazzino"
            who.text.toString().trim().length < 2 -> "Scrivi chi ha contato"
            else -> ""
        }
        val key = "$missing|$counted"
        if (key != shown) {
            shown = key
            holdBox.removeAllViews()
            if (missing.isNotEmpty()) holdBox.addView(ui.primary(missing, C.RED, false) {})
            else holdBox.addView(ui.holdButton("Tieni premuto · ${n(sys.stock)} → $counted pz", C.RED, 0xFF8F1D14.toInt()) {
                hideKeyboard(who)
                sendCount(ean, name, sys, counted, who.text.toString().trim(), after)
            })
        }
    }
    refresh()
    showFull(root, dark = true)
}

private fun MainActivity.sendCount(ean: String, name: String, sys: StoreInfo, counted: Int, by: String, after: (Int) -> Unit) {
    api.counter = by
    io.execute {
        val r = runCatching { api.reportStock(ean, name, sys.stock, counted, by); api.fetchList() }
        main.post {
            val e = r.exceptionOrNull()
            when {
                e == null -> {
                    list.applied(r.getOrThrow(), emptyList())
                    if (toCount.remove(ean)) saveToCount()
                    beep(Kind.ORDER)
                    countBack = null
                    after(counted)
                }
                e is ApiError && e.status == 401 -> unpaired()
                else -> AlertDialog.Builder(this).setTitle("Non inviata")
                    .setMessage(if (e is ApiError) (e.message ?: e.code) else "Serve la connessione: riprova con il Wi-Fi.")
                    .setPositiveButton("OK") { _, _ -> back() }.show()
            }
        }
    }
}

private fun MainActivity.back() { countBack?.let { countBack = null; it() } }

// ================================================================== pairing
internal fun MainActivity.showPairing(message: String?) {
    pending = null
    val root = ui.col()
    root.background = vertical(C.NAVY, 0xFF0F1C38.toInt())
    root.setPadding(ui.dp(24), ui.dp(48), ui.dp(24), ui.dp(24))
    val logo = ui.text("C&T", 20f, Color.WHITE, ui.bold).apply { gravity = Gravity.CENTER; background = ui.bg(C.BLUE, 18) }
    root.addView(logo, ui.fixed(64, 64))
    root.addView(ui.text("Collega lo scanner", 30f, Color.WHITE, ui.bold), ui.lp(top = 28))
    root.addView(ui.text("Codice di 8 caratteri dall'ufficio acquisti", 15f, C.ON_NAVY_MUTED), ui.lp(top = 8))
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
        background = ui.bg(C.NAVY2, 16, C.BLUE, 2)
        setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(16))
    }
    root.addView(code, ui.lp(top = 28))
    val err = ui.text(message ?: "", 14f, C.CORAL)
    root.addView(err, ui.lp(top = 12))
    root.addView(View(this), ui.lp(h = 0, weight = 1f))
    val url = EditText(this).apply {
        setText(api.baseUrl)
        setTextColor(C.ON_NAVY_MUTED)
        textSize = 13f
        inputType = InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine()
        background = ui.bg(C.NAVY, 12, C.NAVY_LINE)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        visibility = View.GONE
    }
    val srv = ui.text("Server", 13f, C.ON_NAVY_MUTED, ui.medium).apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) }
    srv.setOnClickListener { url.visibility = if (url.visibility == View.GONE) View.VISIBLE else View.GONE }
    root.addView(srv)
    root.addView(url)
    val go = FrameLayout(this)
    fun button(busy: Boolean) {
        go.removeAllViews()
        go.addView(ui.primary(if (busy) "Collegamento…" else "Collega", C.BLUE, !busy) {
            val c = code.text.toString()
            if (c.count { it.isLetterOrDigit() } != 8) { err.text = "Il codice ha 8 caratteri"; return@primary }
            button(true)
            err.text = ""
            api.baseUrl = url.text.toString().ifBlank { Api.DEFAULT_URL }
            io.execute {
                val e = runCatching { api.pair(c) }.exceptionOrNull()
                main.post {
                    if (e == null) {
                        hideKeyboard(code)
                        list = api.loadList()
                        tab = MainActivity.Tab.SCAN
                        render()
                        refreshCatalog(true)
                        sync(quiet = true)
                    } else {
                        button(false)
                        err.text = when ((e as? ApiError)?.status) {
                            404 -> "Codice non valido o scaduto: chiedine uno nuovo"
                            429 -> "Troppi tentativi: riprova tra qualche minuto"
                            null -> "Server non raggiungibile: controlla il Wi-Fi"
                            else -> e.message ?: "Errore"
                        }
                    }
                }
            }
        })
    }
    button(false)
    root.addView(go, ui.lp(top = 12))
    showFull(ScrollView(this).apply { isFillViewport = true; addView(root) }, dark = true)
}
