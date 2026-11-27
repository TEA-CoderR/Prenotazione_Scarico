package it.casate.scanner

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
    if (r.status == "open") {
        val asked = Requests.REASONS[r.reason].orEmpty() + (r.boxes?.let { " · $it ${colli(it)}" } ?: "") +
            (if (r.note.isNotEmpty()) " · " + r.note else "")
        c.addView(ui.text("Hai chiesto: $asked", 12f, C.INK).lines(3), ui.lp(top = 6))
    } else if (r.answer.isNotEmpty()) {
        c.addView(ui.text("Ufficio: " + r.answer, 13f, C.INK).lines(4), ui.lp(top = 6))
    }
    if (r.addedToList && r.answerBoxes != null) {
        c.addView(ui.text("Già messo in lista: ${r.answerBoxes} ${colli(r.answerBoxes)}", 12f, C.GREEN_INK, ui.bold), ui.lp(top = 4))
    }
    if (r.status == "answered" && r.answerKind == "use" && r.answerEan.isNotEmpty()) {
        val b = ui.outline("Apri " + r.answerName.ifEmpty { r.answerEan }.take(28), C.AMBER_INK, C.AMBER) { openItem(r.answerEan) }
        c.addView(b, ui.lp(top = 10, w = LinearLayout.LayoutParams.WRAP_CONTENT))
    }
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
    return ui.outline("Modifica richiesta", C.BLUE_INK, 0xFFC6D5FF.toInt()) { askOffice(target, name, v, r) }
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

private fun MainActivity.noteField(hint: String, text: String): EditText = EditText(this).apply {
    this.hint = hint
    setText(text)
    textSize = 15f
    minLines = 2
    maxLines = 4
    imeOptions = EditorInfo.IME_ACTION_DONE
    background = ui.bg(C.CARD, 12, C.LINE2)
    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
}

/** "Fai una foto" / thumbnail + "Rifai": the photo is optional. */
private fun MainActivity.photoRow(get: () -> ByteArray?, set: (ByteArray?) -> Unit): LinearLayout {
    val r = ui.row()
    fun draw() {
        r.removeAllViews()
        val b = get()
        if (b != null) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                background = ui.bg(C.CARD, 10, C.LINE)
                clipToOutline = true
            }
            r.addView(iv, ui.fixed(56, 56))
            r.addView(ui.text("Foto pronta", 14f, C.GREEN_INK, ui.bold), ui.lp(w = 0, weight = 1f, start = 12))
            r.addView(ui.outline("Rifai", C.INK) { shoot() })
            r.addView(ui.iconTap(Icons.CLOSE, C.FAINT, "Togli la foto") { set(null); draw() })
        } else {
            r.addView(ui.outline("Fai una foto", C.INK) { shoot() })
            r.addView(ui.text("facoltativa · utile per un articolo nuovo", 12f, C.MUTED).lines(2), ui.lp(w = 0, weight = 1f, start = 12))
        }
    }
    draw()
    onShoot = { b -> if (b != null) { set(b); draw() } else toast("Nessuna foto") }
    return r
}

private var onShoot: ((ByteArray?) -> Unit)? = null

private fun MainActivity.shoot() {
    val cb = onShoot
    onPhoto = { b -> cb?.invoke(b) }
    if (!Photos.take(this)) { onPhoto = null; toast("Fotocamera non disponibile su questo scanner") }
}

/** The form: why (one tap) · boxes (optional) · note · photo → «Invia all'ufficio». */
internal fun MainActivity.askOffice(target: String, name: String, v: Verdict, old: ProductRequest?) {
    var reason: String? = old?.reason
    var boxes = old?.boxes ?: 0
    var photo: ByteArray? = null
    val box = ui.col(20)
    box.addView(View(this).apply { background = ui.bg(C.LINE2, 3) }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    box.addView(ui.text("Chiedi all'ufficio", 21f, C.INK, ui.bold), ui.lp(top = 14))
    val item = ui.row()
    item.addView(ui.photo(target, name, 44, C.MUTED))
    val ic = ui.col()
    ic.addView(ui.text(name, 14f, C.INK, ui.bold).lines(2))
    ic.addView(ui.num(target, 12f, C.MUTED).oneLine(), ui.lp(top = 2))
    item.addView(ic, ui.lp(w = 0, weight = 1f, start = 10))
    box.addView(item, ui.lp(top = 10))
    box.addView(ui.text("L'ufficio acquisti risponde qui sullo scanner: niente telefonate", 12f, C.MUTED).lines(2), ui.lp(top = 8))

    box.addView(ui.caption("Perché ti serve?"), ui.lp(top = 16))
    val reasons = ui.col()
    val note = noteField(if (v.kind == Kind.UNKNOWN) "Cos'è e cosa serve (es. «SVELTO piatti 1 L, nuovo formato»)" else "Nota per l'ufficio (facoltativa)", old?.note.orEmpty())
    lateinit var send: () -> Unit
    val sendBox = ui.col()
    fun drawSend() {
        sendBox.removeAllViews()
        val okReason = reason != null && (reason != "other" || note.text.toString().trim().length >= 3)
        sendBox.addView(ui.primary(if (old != null) "Aggiorna la richiesta" else "Invia all'ufficio", C.BLUE, okReason, Icons.SEND,
            whyDisabled = { toast(if (reason == null) "Scegli il motivo" else "Scrivi in una riga perché serve") }) { send() }, ui.lp(h = ui.dp(54)))
    }
    fun drawReasons() {
        reasons.removeAllViews()
        for ((code, label) in Requests.REASONS) {
            val on = reason == code
            val r = ui.row()
            r.minimumHeight = ui.dp(50)
            r.background = ui.bg(if (on) C.BLUE_SOFT else C.CARD, 14, if (on) C.BLUE else C.LINE, if (on) 2 else 1)
            r.setPadding(ui.dp(14), 0, ui.dp(12), 0)
            r.addView(ui.checkbox(on, 22))
            r.addView(ui.text(label, 15f, C.INK, if (on) ui.bold else ui.medium).oneLine(), ui.lp(w = 0, weight = 1f, start = 12))
            ui.tap(r, 14) { r.haptic(); reason = code; drawReasons(); drawSend() }
            reasons.addView(r, ui.lp(top = 8))
        }
    }
    drawReasons()
    box.addView(reasons)

    box.addView(ui.caption("Colli che vorresti"), ui.lp(top = 16))
    val bnote = ui.text("", 12f, C.MUTED).apply { gravity = Gravity.CENTER }
    val upd = { bnote.text = if (boxes == 0) "0 = chiedo solo un'informazione" else "Se l'ufficio dice sì, vanno in lista da soli" }
    upd()
    box.addView(Stepper(ui, { boxes }, { boxes = it }, 0, 52, 30f, "Quanti colli?", onChange = upd, onDone = {}).view, ui.lp(top = 8))
    box.addView(bnote, ui.lp(top = 6))

    box.addView(ui.caption("Nota"), ui.lp(top = 16))
    note.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: android.text.Editable?) { drawSend() }
    })
    box.addView(note, ui.lp(top = 8))
    box.addView(photoRow({ photo }, { photo = it }), ui.lp(top = 12))
    if (old?.hasPhoto == true) box.addView(ui.text("La foto inviata prima resta, se non ne fai una nuova", 11f, C.MUTED), ui.lp(top = 4))

    box.addView(sendBox, ui.lp(top = 16))
    drawSend()
    val d = bottomDialog(box)
    box.addView(ui.button("Annulla", 0, C.INK, size = 15f) { d.dismiss() }, ui.lp(top = 10, h = ui.dp(48)))
    if (old != null) box.addView(ui.ghost("Non serve più · ritira la richiesta", C.RED_INK) {
        AlertDialog.Builder(this).setTitle("Ritirare la richiesta?").setMessage("L'ufficio non la vedrà più.")
            .setPositiveButton("Ritira") { _, _ -> d.dismiss(); sendRequest({ api.requestAction(old.id, "cancel") }, "Richiesta ritirata") }
            .setNegativeButton("No", null).show()
    })
    send = {
        val why = reason!!
        val n = note.text.toString()
        val pic = photo
        d.dismiss()
        hideKeyboard(note)
        sendRequest({ api.askOffice(target, if (v.item != null) name else "", Requests.itemKind(v), why, boxes, n, pic) }, "Inviata all'ufficio: ${name.take(30)}")
    }
    d.setOnDismissListener { onShoot = null }
    d.show()
}

/** The office asked for more information: a note and/or a photo, then the request is open again. */
internal fun MainActivity.replyOffice(r: ProductRequest, name: String) {
    var photo: ByteArray? = null
    val box = ui.col(20)
    box.addView(ui.text("Rispondi all'ufficio", 21f, C.INK, ui.bold))
    val q = ui.row()
    q.background = ui.bg(C.AMBER_SOFT, 14)
    q.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
    q.addView(ui.icon(Icons.INFO, C.AMBER_INK, 18, 2.2f))
    q.addView(ui.text(r.answer.ifEmpty { "Servono più informazioni" }, 14f, C.AMBER_INK, ui.bold).lines(4), ui.lp(w = 0, weight = 1f, start = 10))
    box.addView(q, ui.lp(top = 12))
    box.addView(ui.text(name.take(60), 12f, C.MUTED).oneLine(), ui.lp(top = 8))
    val note = noteField("La tua risposta", "")
    box.addView(note, ui.lp(top = 12))
    box.addView(photoRow({ photo }, { photo = it }), ui.lp(top = 12))
    val d = bottomDialog(box)
    box.addView(ui.primary("Invia la risposta", C.BLUE, true, Icons.SEND) {
        val n = note.text.toString().trim()
        if (n.length < 2 && photo == null) { toast("Scrivi la risposta o fai una foto"); return@primary }
        val pic = photo
        d.dismiss()
        hideKeyboard(note)
        sendRequest({ api.requestAction(r.id, "reply", n, pic) }, "Risposta inviata all'ufficio")
    }, ui.lp(top = 16, h = ui.dp(54)))
    box.addView(ui.button("Annulla", 0, C.INK, size = 15f) { d.dismiss() }, ui.lp(top = 10, h = ui.dp(48)))
    d.setOnDismissListener { onShoot = null }
    d.show()
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
