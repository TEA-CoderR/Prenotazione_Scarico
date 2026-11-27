package it.casate.scanner

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/*
 * «Chiedi all'ufficio»: instead of phoning, the store asks the purchasing office about an item it cannot
 * order (unknown barcode, out of the Griglia, no wholesaler this week). The office answers from the
 * purchasing program; the answer shows on the item's card and in «Da fare».
 */

/** The state of the store's request on the item's card (or nothing if there is none). */
internal fun MainActivity.requestBlock(body: LinearLayout, target: String) {
    val r = list.request(target) ?: return
    if (r.status == "cancelled") return
    val (fg, fill, line, icon) = when (Requests.tone(r)) {
        0 -> Quad(C.GREEN_INK, C.GREEN_SOFT, C.GREEN_SOFT, Icons.CHECK)
        1 -> Quad(C.AMBER_INK, C.AMBER_SOFT, C.AMBER_LINE, if (r.status == "info") Icons.INFO else Icons.SWAP)
        3 -> Quad(C.RED_INK, C.RED_BG, 0xFFF7C6C0.toInt(), Icons.BAN)
        else -> Quad(C.BLUE_INK, C.BLUE_SOFT, 0xFFC6D5FF.toInt(), Icons.CLOCK)
    }
    val c = ui.col(16)
    c.background = ui.bg(fill, 16, line)
    val head = ui.row()
    head.addView(ui.icon(icon, fg, 18, 2.2f))
    head.addView(ui.text(Requests.title(r), 14f, fg, ui.bold).lines(2), ui.lp(w = 0, weight = 1f, start = 8))
    head.addView(ui.text(shortDay(if (r.handledAt > 0 && r.status != "open") r.handledAt else r.updatedAt), 11f, fg))
    c.addView(head)
    val last = r.thread().lastOrNull()
    if (last != null && last.text.isNotEmpty())
        c.addView(ui.text((if (last.mine) "Tu: " else "Ufficio: ") + last.text, 13f, C.INK).lines(3), ui.lp(top = 6))
    val n = r.thread().size
    c.addView(ui.text(if (n > 1) "Apri la conversazione · $n messaggi ›" else "Apri la conversazione ›", 12f, fg, ui.bold), ui.lp(top = 6))
    if (r.addedToList && r.answerBoxes != null) {
        c.addView(ui.text("Già messo in lista: ${r.answerBoxes} ${colli(r.answerBoxes)}", 12f, C.GREEN_INK, ui.bold), ui.lp(top = 4))
    }
    if (r.status == "answered" && r.answerKind == "use" && r.answerEan.isNotEmpty()) {
        val b = ui.outline("Apri " + r.answerName.ifEmpty { r.answerEan }.take(28), C.AMBER_INK, C.AMBER) { openItem(r.answerEan) }
        c.addView(b, ui.lp(top = 10, w = LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    ui.tap(c, 16) { officeThread(target, r.name.ifEmpty { target }, catalog?.judge(target) ?: Verdict(Kind.UNKNOWN, null, null), r) }
    body.addView(c, ui.lp(top = 12))
    if (r.unseen) markRequestSeen(r)
}

private data class Quad(val a: Int, val b: Int, val c: Int, val d: Icons.Spec)

private fun MainActivity.markRequestSeen(r: ProductRequest) {
    io.execute {
        val ok = runCatching { api.requestAction(r.id, "seen") }.isSuccess
        if (ok) runCatching { api.fetchList() }.getOrNull()?.let { v -> main.post { list.applied(v, emptyList()); if (!sheet.isOpen && !fullScreen) render() } }
    }
}

/** The main button on the card of an item the store cannot order: ask, change the request or reply. */
internal fun MainActivity.requestButton(target: String, name: String, v: Verdict): View? {
    if (!Requests.eligible(v)) return null
    val r = list.request(target)
    return when (r?.status) {
        "info" -> ui.primary("Rispondi all'ufficio", C.AMBER, true, Icons.SEND) { replyOffice(r, name) }
        "open" -> null
        else -> ui.primary(if (r == null) "Chiedi all'ufficio" else "Chiedi di nuovo", C.BLUE, true, Icons.SEND) {
            askOffice(target, name, v, null)
        }
    }
}

/** Secondary button: change an open request. */
internal fun MainActivity.requestEdit(target: String, name: String, v: Verdict): View? {
    val r = list.request(target)?.takeIf { it.status == "open" && Requests.eligible(v) } ?: return null
    return ui.outline("Scrivi all'ufficio", C.BLUE_INK, 0xFFC6D5FF.toInt()) { askOffice(target, name, v, r) }
}

private fun MainActivity.bottomDialog(box: LinearLayout): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    box.background = ui.bg(C.CARD, 24)
    d.setContentView(ScrollView(this).apply { addView(box) })
    d.window?.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        attributes = attributes.apply { y = ui.dp(8) }
        decorView.setPadding(ui.dp(8), 0, ui.dp(8), 0)
        setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    return d
}

private var onShoot: ((ByteArray?) -> Unit)? = null

private fun MainActivity.shoot() {
    val cb = onShoot
    onPhoto = { b -> cb?.invoke(b) }
    if (!Photos.take(this)) { onPhoto = null; toast("Fotocamera non disponibile su questo scanner") }
}

/** Asking the office = the conversation of this item (new or going on). */
internal fun MainActivity.askOffice(target: String, name: String, v: Verdict, old: ProductRequest?) = officeThread(target, name, v, old)

/** The office asked something: same conversation, the answer goes as a reply. */
internal fun MainActivity.replyOffice(r: ProductRequest, name: String) =
    officeThread(r.ean, name, catalog?.judge(r.ean) ?: Verdict(Kind.UNKNOWN, null, null), r)

/** The quick sentences of this scanner (learned from what it sends). */
private fun MainActivity.phraseStore(): Map<String, Int> = runCatching {
    val o = org.json.JSONObject(api.phrases.ifEmpty { "{}" })
    o.keys().asSequence().associateWith { o.optInt(it) }
}.getOrDefault(emptyMap())

private fun MainActivity.learnPhrases(text: String) {
    api.phrases = org.json.JSONObject(Phrases.learn(phraseStore(), text) as Map<*, *>).toString()
}

/**
 * One window per item: the messages so far (store on the right, office on the left), quick sentences,
 * the text box (ready to type), boxes and photo next to «Invia». New request / change of an open one /
 * reply to the office's question all go through here.
 */
private fun MainActivity.officeThread(target: String, name: String, v: Verdict, r: ProductRequest?) {
    val status = r?.status
    val replying = status == "info"
    val going = status == "open" || replying
    var boxes = if (status == "open") r?.boxes ?: 0 else 0
    var photo: ByteArray? = null
    val box = ui.col()
    box.setPadding(0, ui.dp(10), 0, 0)

    // head: item · state · close
    val head = ui.row()
    head.setPadding(ui.dp(16), ui.dp(4), ui.dp(10), ui.dp(10))
    head.addView(ui.photo(target, name, 44, C.MUTED))
    val hc = ui.col()
    hc.addView(ui.text(name.ifEmpty { target }, 14f, C.INK, ui.bold).lines(2))
    val st = when {
        r == null || status == "cancelled" -> "Chiedi all'ufficio acquisti" to C.MUTED
        replying -> "L'ufficio ti ha chiesto qualcosa" to C.AMBER_INK
        status == "open" -> "In attesa di risposta" to C.BLUE_INK
        else -> Requests.title(r) to requestFg(r)
    }
    hc.addView(ui.text(st.first, 12f, st.second, ui.bold).oneLine(), ui.lp(top = 2))
    head.addView(hc, ui.lp(w = 0, weight = 1f, start = 10))
    lateinit var d: android.app.Dialog
    head.addView(ui.iconTap(Icons.CLOSE, C.MUTED, "Chiudi") { d.dismiss() })
    box.addView(head)
    box.addView(ui.divider())

    // the conversation so far
    val thread = if (r != null && status != "cancelled") r.thread() else emptyList()
    if (thread.isNotEmpty()) {
        val msgs = ui.col()
        msgs.setBackgroundColor(C.CANVAS)
        msgs.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        for (m in thread) msgs.addView(bubble(m))
        val sv = ScrollView(this).apply { addView(msgs); isVerticalScrollBarEnabled = false }
        val maxH = (resources.displayMetrics.heightPixels * 0.38).toInt()
        box.addView(sv, ui.lp(h = if (thread.size > 3) maxH else LinearLayout.LayoutParams.WRAP_CONTENT))
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    val open = r == null || status == "cancelled" || going || status == "answered"
    if (!open) return
    val form = ui.col()
    form.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(14))

    // quick sentences (tap = add to the text)
    val note = EditText(this).apply {
        textSize = 15f
        minLines = 2
        maxLines = 5
        hint = if (replying) "La tua risposta" else "Scrivi all'ufficio"
        setHintTextColor(C.FAINT)
        setTextColor(C.INK)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        background = ui.bg(C.CARD, 14, C.BLUE, 2)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
    }
    if (!replying) {
        val chips = ui.row()
        for ((i, ph) in Phrases.shown(phraseStore()).withIndex()) {
            val c = ui.text(ph, 13f, C.INK, ui.medium).apply {
                background = ui.bg(C.CARD, 999, C.LINE2)
                setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
            }
            ui.tap(c, 999) { c.haptic(); note.setText(Phrases.append(note.text.toString(), ph)); note.setSelection(note.text.length) }
            chips.addView(c, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = if (i == 0) 0 else 6))
        }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        form.addView(hs, ui.lp(bottom = 8))
    }
    form.addView(note)

    // boxes · photo · send
    val tools = ui.row()
    val boxBtn = ui.row()
    boxBtn.background = ui.bg(C.CARD, 12, C.LINE2)
    boxBtn.setPadding(ui.dp(10), 0, ui.dp(12), 0)
    boxBtn.addView(ui.icon(Icons.BOX, C.INK, 16, 2f))
    val boxTxt = ui.text("", 13f, C.INK, ui.bold)
    boxBtn.addView(boxTxt, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 6))
    fun drawBoxes() { boxTxt.text = if (boxes > 0) "$boxes ${colli(boxes)}" else "Colli" }
    drawBoxes()
    ui.tap(boxBtn, 12) { askBoxes(boxes) { boxes = it; drawBoxes() } }
    if (!replying) tools.addView(boxBtn, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, h = ui.dp(42)))
    val camBtn = ui.row()
    camBtn.background = ui.bg(C.CARD, 12, C.LINE2)
    camBtn.setPadding(ui.dp(10), 0, ui.dp(12), 0)
    camBtn.addView(ui.icon(Icons.CAMERA, C.INK, 16, 2f))
    val camTxt = ui.text("Foto", 13f, C.INK, ui.bold)
    camBtn.addView(camTxt, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 6))
    onShoot = { b -> if (b != null) { photo = b; camTxt.text = "Foto ✓"; camTxt.setTextColor(C.GREEN_INK) } else toast("Nessuna foto") }
    ui.tap(camBtn, 12) { shoot() }
    tools.addView(camBtn, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, h = ui.dp(42), start = if (replying) 0 else 8))
    tools.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
    val send = ui.iconButton("Invia", Icons.SEND, C.BLUE, size = 15f) {
        val t = note.text.toString().trim()
        if (t.length < 3 && !(replying && photo != null)) { toast(if (replying) "Scrivi la risposta o fai una foto" else "Scrivi cosa ti serve"); return@iconButton }
        val pic = photo
        hideKeyboard(note)
        d.dismiss()
        if (t.length >= 3) learnPhrases(t)
        if (replying) sendRequest({ api.requestAction(r!!.id, "reply", t, pic) }, "Risposta inviata all'ufficio")
        else sendRequest({ api.askOffice(target, if (v.item != null) name else "", Requests.itemKind(v), "other", boxes, t, pic) },
            "Inviata all'ufficio: ${name.take(30)}")
    }
    send.setPadding(ui.dp(18), 0, ui.dp(18), 0)
    tools.addView(send, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, h = ui.dp(42)))
    form.addView(tools, ui.lp(top = 10))
    if (status == "open" && r != null) {
        val w = ui.text("Non serve più · ritira la richiesta", 13f, C.RED_INK, ui.medium).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(2)) }
        ui.tap(w, 8) {
            AlertDialog.Builder(this).setTitle("Ritirare la richiesta?").setMessage("L'ufficio non la vedrà più.")
                .setPositiveButton("Ritira") { _, _ -> d.dismiss(); sendRequest({ api.requestAction(r.id, "cancel") }, "Richiesta ritirata") }
                .setNegativeButton("No", null).show()
        }
        form.addView(w, ui.lp(top = 6))
    }
    box.addView(form)
    d = bottomDialog(box)
    d.setOnDismissListener { onShoot = null }
    d.show()
    note.requestFocus()
    d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
}

private fun requestFg(r: ProductRequest) = when (Requests.tone(r)) { 0 -> C.GREEN_INK; 1 -> C.AMBER_INK; 3 -> C.RED_INK; else -> C.BLUE_INK }

/** One message: the store's on the right (blue), the office's on the left (white, green / red when it decides). */
private fun MainActivity.bubble(m: RequestMessage): View {
    val wrap = ui.row(if (m.mine) Gravity.END else Gravity.START)
    val b = ui.col()
    val (fill, fg, line) = when {
        m.mine -> Triple(C.BLUE, Color.WHITE, 0)
        m.kind in setOf("use", "griglia", "once") -> Triple(C.GREEN_SOFT, C.INK, 0)
        m.kind == "no" -> Triple(C.RED_BG, C.INK, 0)
        m.kind == "info" -> Triple(C.AMBER_SOFT, C.INK, 0)
        else -> Triple(C.CARD, C.INK, C.LINE)
    }
    b.background = ui.bg(fill, 16, line)
    b.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
    val text = m.text.ifEmpty { if (m.photo) "📷 Foto" else "—" }
    b.addView(ui.text(text, 14f, fg))
    val meta = listOfNotNull(if (m.mine) "Tu" else "Ufficio acquisti", dayTime(m.at),
        m.boxes?.let { "$it ${colli(it)}" }, if (m.photo && m.text.isNotEmpty()) "📷" else null).joinToString(" · ")
    b.addView(ui.text(meta, 11f, if (m.mine) 0xCCFFFFFF.toInt() else C.MUTED), ui.lp(top = 3))
    val w = (resources.displayMetrics.widthPixels * 0.78).toInt()
    b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    b.minimumWidth = 0
    (b.getChildAt(0) as TextView).maxWidth = w - ui.dp(24)
    wrap.addView(b)
    wrap.setPadding(0, ui.dp(4), 0, ui.dp(4))
    return wrap
}

/** «Colli»: small window with − n + (0 = just asking). */
private fun MainActivity.askBoxes(now: Int, done: (Int) -> Unit) {
    var n = now
    val c = ui.col(20)
    c.addView(ui.text("Quanti colli ti servirebbero?", 17f, C.INK, ui.bold))
    c.addView(ui.text("0 = chiedi solo un'informazione · se l'ufficio dice sì, vanno in lista da soli", 12f, C.MUTED).lines(3), ui.lp(top = 4))
    c.addView(Stepper(ui, { n }, { n = it }, 0, 52, 30f, "Quanti colli?", onChange = {}, onDone = {}).view, ui.lp(top = 14))
    AlertDialog.Builder(this).setView(c).setPositiveButton("OK") { _, _ -> done(n) }.setNegativeButton("Annulla", null).show()
}

/** Sends, then reloads the list (the requests come with it) and redraws the card. */
private fun MainActivity.sendRequest(call: () -> ProductRequest, ok: String) {
    toast("Invio…")
    io.execute {
        val res = runCatching { call(); api.fetchList() }
        main.post {
            val e = res.exceptionOrNull()
            when {
                e == null -> {
                    list.applied(res.getOrThrow(), emptyList())
                    beep(Kind.ORDER)
                    if (sheet.isOpen && pending != null) showItem() else render()
                    toast(ok)
                }
                e is ApiError && e.status == 401 -> unpaired()
                else -> AlertDialog.Builder(this).setTitle("Non inviata")
                    .setMessage(if (e is ApiError) (e.message ?: e.code) else "Serve la connessione: riprova con il Wi-Fi.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }
}
