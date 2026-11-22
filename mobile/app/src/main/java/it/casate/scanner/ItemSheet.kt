package it.casate.scanner

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout

/** The item being decided on the sheet: nothing goes in the list until its main button is pressed. */
internal class Pending(val scan: String, val verdict: Verdict, var boxes: Int, var remind: Boolean) {
    /** Reason picked on the card (when one is needed); "Altro" = `other`. */
    var reason: String? = null
    var other = ""
    /** 0 = the item · 1 = "stock wrong?" choices */
    var mode = 0
    /** The stock choices were opened from the reason "Giacenza sbagliata" (then the item also goes in the list). */
    var stockForWhy = false
    val orderable get() = verdict.kind == Kind.ORDER || verdict.kind == Kind.SUBSTITUTE || verdict.kind == Kind.URGENT_ONLY
}

internal val WHY_REASONS = listOf("Promozione o volantino", "Più spazio a scaffale", "Richiesto da un cliente", "Giacenza sbagliata", "Altro")
internal val URGENT_REASONS = listOf("Scaffale vuoto", "Cliente in attesa", "Promozione in corso", "Il diretto arriva tardi", "Altro")

internal fun MainActivity.openItem(raw: String) {
    val cat = catalog ?: return
    val v = cat.judge(raw)
    val target = targetEan(v, raw)
    val inList = list.line(target)
    val info = cat.info(target)
    val pack = info?.pack?.takeIf { it > 0 } ?: (if (v.kind == Kind.SUBSTITUTE) v.substitute else v.item)?.pack
    val start = when (v.kind) {
        Kind.URGENT_ONLY -> inList?.boxes?.takeIf { it > 0 } ?: 1
        else -> Catalog.startBoxes(inList?.boxes, info, pack)
    }
    val p = Pending(raw, v, start, remind = inList?.kind != "griglia")
    if (v.kind == Kind.URGENT_ONLY) inList?.reason?.let { r -> if (r in URGENT_REASONS) p.reason = r else { p.reason = "Altro"; p.other = r } }
    else if (inList != null && !inList.reason.isNullOrEmpty()) p.reason = inList.reason.takeIf { it in WHY_REASONS } ?: run { p.other = inList.reason; "Altro" }
    pending = p
    beep(v.kind)
    showItem()
    sheet.top()
}

internal fun MainActivity.targetEan(v: Verdict, raw: String): String =
    (if (v.kind == Kind.SUBSTITUTE) v.substitute?.ean else v.item?.ean) ?: (Catalog.keys(raw).firstOrNull() ?: raw)

/** The card of the item: one look = can I order it, how many, why. */
internal fun MainActivity.showItem() {
    val p = pending ?: return sheet.close()
    val v = p.verdict
    val target = targetEan(v, p.scan)
    val item = if (v.kind == Kind.SUBSTITUTE) v.substitute else v.item
    val sys = catalog?.info(target)
    val rep = list.report(target)
    // a stock the store reported (not handled yet): everything is computed with what was counted
    val info = if (rep?.status == "open") sys?.copy(stock = rep.counted.toDouble(), suspect = false) else sys
    val pack = info?.pack?.takeIf { it > 0 } ?: item?.pack
    val inList = list.line(target)?.takeIf { it.boxes > 0 || it.kind == "remind" }
    val adv = Catalog.advise(info, pack)
    val name = item?.name?.ifEmpty { null } ?: "Articolo non in catalogo"

    sheet.onClose = { pending = null }
    sheet.onBack = { if (p.mode == 1) { p.mode = 0; showItem(); true } else false }
    if (p.mode == 1 && sys != null) return stockChoices(p, target, name, sys)

    val body = ui.col()
    body.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8))

    // status + close
    data class St(val label: String, val fg: Int, val fill: Int, val note: String)
    val st = when (v.kind) {
        Kind.ORDER -> St("ORDINABILE", C.GREEN_INK, C.GREEN_SOFT, "${v.item!!.wholesalers.size} " + if (v.item.wholesalers.size == 1) "grossista" else "grossisti")
        Kind.SUBSTITUTE -> St("SOSTITUITO", C.BLUE_INK, C.BLUE_SOFT, "Ordina il nuovo")
        Kind.NOT_AVAILABLE -> St("NON DISPONIBILE", C.AMBER_INK, C.AMBER_SOFT, "Nessun grossista questa settimana")
        Kind.URGENT_ONLY -> St("SOLO URGENZE", C.ORANGE, C.ORANGE_SOFT, if (v.item!!.griglia == 2) "Tolto dalla Griglia" else "Non in Griglia")
        Kind.DIRECT_ONLY -> St("NON IN GRIGLIA", C.RED_INK, C.RED_SOFT, "Nessun grossista")
        Kind.UNKNOWN -> St("SCONOSCIUTO", C.MUTED, 0xFFEEF1F6.toInt(), "Non in catalogo")
    }
    val head = ui.row()
    head.addView(ui.pill(st.label, st.fg, st.fill))
    head.addView(ui.text(st.note, 13f, C.MUTED).oneLine(), ui.lp(w = 0, weight = 1f, start = 10))
    head.addView(ui.iconTap(Icons.CLOSE, C.MUTED, "Chiudi") { sheet.close(); render() })
    body.addView(head)

    if (v.kind == Kind.SUBSTITUTE) {
        val o = ui.row()
        o.background = ui.bg(0xFFF1F4F8.toInt(), 12)
        o.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        o.addView(ui.icon(Icons.SWAP, C.BLUE_INK, 16, 2.2f))
        o.addView(ui.text("Al posto di ", 12f, C.MUTED), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
        o.addView(ui.struck(ui.text(v.item!!.name, 12f, C.MUTED, ui.medium)).oneLine(), ui.lp(w = 0, weight = 1f))
        body.addView(o, ui.lp(top = 12))
    }

    // the product
    val pr = ui.row(Gravity.TOP)
    pr.addView(ui.photo(item?.ean ?: p.scan, name, 72, kindColor(v.kind)))
    val pc = ui.col()
    pc.addView(ui.text(name, 18f, C.INK, ui.bold).lines(2))
    pc.addView(ui.num((item?.ean ?: p.scan) + (pack?.let { " · ${n(it)} pz/collo" } ?: ""), 12f, C.MUTED).oneLine(), ui.lp(top = 4))
    priceLine(info)?.let { pc.addView(it, ui.lp(top = 8)) }
    pr.addView(pc, ui.lp(w = 0, weight = 1f, start = 14))
    body.addView(pr, ui.lp(top = 14))

    // this store's numbers + is the stock right?
    if (info != null) {
        val cover = info.coverDays((info.transitBoxes ?: 0.0) * (pack ?: 0.0))
        val empty = (info.stock ?: 0.0) <= 0
        body.addView(ui.stats(listOf(
            Stat(if (rep?.status == "open") "Contati" else "In negozio", n(info.stock), "pz", if (empty) C.RED_INK else C.INK),
            Stat("Venduti 30g", n(info.sold30), "pz"),
            Stat("In arrivo", n(info.transitBoxes ?: 0.0), colli((info.transitBoxes ?: 0.0).toInt()), if ((info.transitBoxes ?: 0.0) > 0) C.BLUE_INK else C.INK),
            Stat("Basta per", cover?.toString() ?: "–", if (cover != null) "gg" else "", if (cover != null && cover < 14) C.AMBER_INK else C.INK),
        )), ui.lp(top = 16))
        if (sys != null) body.addView(stockRow(p, target, name, sys, rep), ui.lp(top = 8))
    } else if (v.kind != Kind.UNKNOWN) {
        body.addView(ui.text("Giacenza e vendite di $store non ancora pubblicate", 12f, C.MUTED), ui.lp(top = 14))
    }

    when (v.kind) {
        Kind.ORDER, Kind.SUBSTITUTE -> decision(body, p, info, pack, adv, inList)
        Kind.URGENT_ONLY -> urgentBlock(body, p, v, info, pack)
        Kind.NOT_AVAILABLE -> notAvailableBlock(body, p, v, info)
        Kind.DIRECT_ONLY, Kind.UNKNOWN -> {
            val d = v.item?.direct.orEmpty()
            body.addView(infoCard(if (d.isNotEmpty()) Icons.TRUCK else Icons.INFO, if (d.isNotEmpty()) C.GREEN_INK else C.MUTED,
                if (d.isNotEmpty()) C.GREEN_SOFT else 0xFFF1F4F8.toInt(),
                if (d.isNotEmpty()) "Dal fornitore diretto $d" else "Non si ordina nella lista DETERSIVI",
                if (d.isNotEmpty()) "Con l'ordine diretto del negozio" else "Se serve davvero, chiedi all'ufficio acquisti"), ui.lp(top = 16))
        }
    }
    lastWeekLine(target)?.let { body.addView(it, ui.lp(top = 14)) }

    sheet.show(body, actions(p, v, target, item, info, sys, adv, inList, rep))
}

/** Sale price, struck through when on offer, and the offer price (the purchase price is never shown). */
private fun MainActivity.priceLine(info: StoreInfo?): View? {
    val price = info?.price?.takeIf { it > 0 }
    val promo = info?.promoPrice?.takeIf { it > 0 }
    if (price == null && promo == null) return null
    val r = ui.row()
    if (price != null) {
        val t = ui.num(eur(price), if (promo == null) 16f else 13f, if (promo == null) C.INK else C.FAINT)
        if (promo != null) ui.struck(t)
        r.addView(t)
    }
    if (promo != null) {
        r.addView(ui.num(eur(promo), 16f, C.RED_INK), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = if (price != null) 8 else 0))
        r.addView(ui.chip("OFFERTA" + (if (info.promoEnd.isNotEmpty()) " · ${info.promoEnd}" else ""), Color.WHITE, C.RED, 10f),
            ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
    }
    return r
}

private fun MainActivity.infoCard(icon: Icons.Spec, fg: Int, fill: Int, title: String, sub: String): View {
    val c = ui.row()
    c.background = ui.bg(fill, 16)
    c.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
    c.addView(ui.iconBadge(icon, Color.WHITE, fg, 36, 20, 2.2f))
    val t = ui.col()
    t.addView(ui.text(title, 15f, C.INK, ui.bold))
    if (sub.isNotEmpty()) t.addView(ui.text(sub, 13f, C.MUTED), ui.lp(top = 2))
    c.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
    return c
}

/** One line under the numbers: is the stock right? (state of a report / "to count", or a button). */
private fun MainActivity.stockRow(p: Pending, ean: String, name: String, sys: StoreInfo, rep: StockReport?): View {
    val r = ui.row()
    r.setPadding(ui.dp(14), ui.dp(4), ui.dp(4), ui.dp(4))
    r.minimumHeight = ui.dp(52)
    fun set(fill: Int, line: Int, icon: Icons.Spec, fg: Int, title: String, sub: String?, action: View?) {
        r.background = ui.bg(fill, 14, line)
        r.addView(ui.icon(icon, fg, 18, 2.2f))
        val t = ui.col()
        t.addView(ui.text(title, 13f, fg, ui.bold).oneLine())
        if (!sub.isNullOrEmpty()) t.addView(ui.text(sub, 12f, fg).oneLine(), ui.lp(top = 2))
        r.addView(t, ui.lp(w = 0, weight = 1f, start = 10))
        if (action != null) r.addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(40)).apply { marginEnd = ui.dp(4) })
    }
    val recount = { countFromSheet(p, ean, name, sys, forWhy = false) }
    when {
        rep?.status == "open" -> set(C.BLUE_SOFT, 0xFFC6D5FF.toInt(), Icons.CLOCK, C.BLUE_INK, "Giacenza segnalata · ${rep.counted} pz",
            "In attesa dell'ufficio · conto con i pezzi contati", ui.outline("Riconta", C.BLUE_INK, 0xFFC6D5FF.toInt()) { recount() })
        rep?.status == "fixed" -> set(C.GREEN_SOFT, C.GREEN_SOFT, Icons.CHECK, C.GREEN_INK, "Giacenza corretta dall'ufficio",
            "Era ${n(rep.systemQty)} pz · contati ${rep.counted}", null)
        rep?.status == "rejected" -> set(C.RED_BG, 0xFFF7C6C0.toInt(), Icons.ALERT, C.RED_INK, "Segnalazione respinta",
            rep.answer.ifEmpty { "Chiedi all'ufficio acquisti" }, ui.outline("Riconta", C.RED_INK, 0xFFF7C6C0.toInt()) { recount() })
        toCount.has(ean) -> set(C.AMBER_SOFT, C.AMBER_LINE, Icons.LIST, C.AMBER_INK, "In «Da contare»", "Contalo prima di inviare la lista",
            ui.outline("Conta ora", C.AMBER_INK, C.AMBER) { recount() })
        sys.suspect && (sys.stock ?: 0.0) > 0 -> set(C.AMBER_SOFT, C.AMBER_LINE, Icons.ALERT, C.AMBER_INK, "Giacenza da verificare",
            sys.lastSaleDays?.let { "Nessuna vendita da $it giorni" } ?: "Non si vende da molto",
            ui.outline("Segnala", C.AMBER_INK, C.AMBER) { p.mode = 1; p.stockForWhy = false; showItem() })
        else -> set(0xFFF6F8FB.toInt(), C.LINE, Icons.BOX, C.MUTED, "La giacenza è giusta?", null,
            ui.outline("Segnala", C.INK) { p.mode = 1; p.stockForWhy = false; showItem() })
    }
    return r
}

/** Suggestion + quantity + (when needed) the reason, all on the card. */
private fun MainActivity.decision(body: LinearLayout, p: Pending, info: StoreInfo?, pack: Double?, adv: Advice, inList: Line?) {
    val box = ui.card(20)
    box.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16))
    // the suggestion
    val sr = ui.row()
    sr.addView(ui.iconBadge(Icons.SPARK, C.GREEN_SOFT, C.GREEN_INK, 36, 18, 2.4f))
    val sc = ui.col()
    val b = adv.boxes
    val big = ui.row(Gravity.BOTTOM)
    big.addView(ui.text("Consigliato  ", 13f, C.MUTED, ui.medium))
    if (b == null) big.addView(ui.text("—", 18f, C.MUTED, ui.bold))
    else {
        big.addView(ui.num("$b", 22f, if (b > 0) C.GREEN_INK else C.MUTED))
        big.addView(ui.text(" " + colli(b), 14f, if (b > 0) C.GREEN_INK else C.MUTED, ui.bold))
        if (adv.estimated) big.addView(ui.chip("STIMA", C.MUTED, 0xFFEEF1F6.toInt(), 10f), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
    }
    sc.addView(big)
    sc.addView(ui.text(if (b == 0) "Non serve · " + adv.reason else adv.reason, 12f, C.MUTED).lines(2), ui.lp(top = 3))
    sr.addView(sc, ui.lp(w = 0, weight = 1f, start = 12))
    if (b != null && b > 0 && b != p.boxes) {
        sr.addView(ui.outline("Usa $b", C.GREEN_INK, 0xFF9FDCC4.toInt()) { p.boxes = b; showItem() })
    }
    box.addView(sr)
    box.addView(ui.divider(), ui.lp(h = ui.dp(1), top = 14))

    // the quantity
    val qh = ui.row()
    qh.addView(ui.caption("Da ordinare"), ui.lp(w = 0, weight = 1f))
    if (inList != null && inList.boxes > 0) {
        val by = if (!inList.updatedBy.isNullOrEmpty() && inList.updatedBy != api.deviceName) " · ${inList.updatedBy}" else ""
        qh.addView(ui.chip("In lista $by".trim() + " · ${inList.boxes}", C.BLUE_INK, C.BLUE_SOFT, 11f))
    }
    box.addView(qh, ui.lp(top = 14))
    val note = ui.text("", 12f, C.MUTED).apply { gravity = Gravity.CENTER }
    fun noteText() {
        val pcs = pack?.let { " = ${n(it * p.boxes)} pz" } ?: ""
        val withPack = info?.copy(pack = pack) ?: pack?.let { StoreInfo(pack = it) }
        val cover = Catalog.coverAfter(withPack, p.boxes)?.let { " · basta ~${Catalog.months(it)}" } ?: ""
        note.text = colli(p.boxes) + pcs + cover
    }
    noteText()
    val st = Stepper(ui, { p.boxes }, { p.boxes = it }, 1, 60, 40f, "Quanti colli?",
        onChange = { noteText() },
        onDone = { showItem() })   // the button label and the reason block follow the number
    box.addView(st.view, ui.lp(top = 10))
    box.addView(note, ui.lp(top = 8))
    body.addView(box, ui.lp(top = 16))

    // the reason, only when the system would not order this many
    if (needsWhy(p, info, adv)) {
        val over = b != null && b > 0
        body.addView(ui.caption(if (over) "Perché più del consigliato?" else "Perché lo ordini?", C.AMBER_INK), ui.lp(top = 18))
        body.addView(ui.text("Lo vede l'ufficio acquisti", 12f, C.MUTED), ui.lp(top = 3))
        val flow = FlowLayout(this, ui.dp(8), ui.dp(8))
        for (r in Feedbacks.byUse(WHY_REASONS.dropLast(1), api.whyUse) + "Altro") {
            flow.addView(ui.choice(r, p.reason == r, C.NAVY) {
                if (r == "Giacenza sbagliata") { p.mode = 1; p.stockForWhy = true; showItem(); return@choice }
                p.reason = if (p.reason == r) null else r
                showItem()
            })
        }
        body.addView(flow, ui.lp(top = 10))
        if (p.reason == "Altro") body.addView(otherField(p), ui.lp(top = 10))
    }
}

private fun MainActivity.otherField(p: Pending): View = EditText(this).apply {
    hint = "Scrivi il motivo"
    setText(p.other)
    setSingleLine()
    textSize = 15f
    imeOptions = EditorInfo.IME_ACTION_DONE
    background = ui.bg(C.CARD, 12, C.LINE2)
    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
    addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { p.other = s?.toString().orEmpty() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
}

/** A reason is needed when the system would not order it, or far fewer boxes (stock being corrected: no). */
internal fun MainActivity.needsWhy(p: Pending, info: StoreInfo?, adv: Advice): Boolean {
    val k = p.verdict.kind
    if (k != Kind.ORDER && k != Kind.SUBSTITUTE) return false
    info ?: return false
    if (list.report(targetEan(p.verdict, p.scan))?.status == "open") return false
    val b = adv.boxes ?: return false
    return b == 0 || Feedbacks.overOrder(b, p.boxes)
}

private fun Pending.reasonText(): String? = when (reason) {
    null -> null
    "Altro" -> other.trim().takeIf { it.length >= 3 }
    else -> reason
}

private fun MainActivity.urgentBlock(body: LinearLayout, p: Pending, v: Verdict, info: StoreInfo?, pack: Double?) {
    val d = v.item!!.direct
    body.addView(infoCard(Icons.BOLT, C.ORANGE, C.ORANGE_SOFT, "Solo se serve subito",
        "Di norma dal fornitore diretto" + (if (d.isNotEmpty()) " $d" else "") + " · dal grossista solo come urgenza"), ui.lp(top = 16))
    val box = ui.card(20)
    box.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16))
    box.addView(ui.caption("Colli urgenti"))
    val note = ui.text("", 12f, C.MUTED).apply { gravity = Gravity.CENTER }
    val upd = { note.text = colli(p.boxes) + (pack?.let { " = ${n(it * p.boxes)} pz" } ?: "") }
    upd()
    box.addView(Stepper(ui, { p.boxes }, { p.boxes = it }, 1, 56, 36f, "Quanti colli?", onChange = upd, onDone = {}).view, ui.lp(top = 10))
    box.addView(note, ui.lp(top = 8))
    body.addView(box, ui.lp(top = 12))
    body.addView(ui.caption("Perché è urgente?", C.ORANGE), ui.lp(top = 18))
    val flow = FlowLayout(this, ui.dp(8), ui.dp(8))
    for (r in URGENT_REASONS) flow.addView(ui.choice(r, p.reason == r, C.ORANGE) { p.reason = if (p.reason == r) null else r; showItem() })
    body.addView(flow, ui.lp(top = 10))
    if (p.reason == "Altro") body.addView(otherField(p), ui.lp(top = 10))
    if (info != null && (info.stock ?: 0.0) <= 0 && p.reason == null) body.addView(ui.text("Lo scaffale è vuoto", 12f, C.RED_INK, ui.bold), ui.lp(top = 8))
}

private fun MainActivity.notAvailableBlock(body: LinearLayout, p: Pending, v: Verdict, info: StoreInfo?) {
    val cover = info?.coverDays()
    val d = v.item?.direct.orEmpty()
    val sub = when {
        info == null -> ""
        (info.stock ?: 0.0) <= 0 -> "Scaffale vuoto: l'ufficio cerca un'alternativa"
        cover != null -> "Hai scorta per $cover giorni"
        else -> ""
    } + (if (d.isNotEmpty()) (if (info != null) " · " else "") + "anche dal diretto $d" else "")
    body.addView(infoCard(Icons.CLOCK, C.AMBER_INK, C.AMBER_SOFT, "Questa settimana nessun grossista lo ha", sub), ui.lp(top = 16))
    val r = ui.row()
    r.background = ui.bg(C.CARD, 16, if (p.remind) C.NAVY else C.LINE, if (p.remind) 2 else 1)
    r.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
    r.addView(ui.checkbox(p.remind, 24))
    val t = ui.col()
    t.addView(ui.text("Avvisami quando torna", 15f, C.INK, ui.bold))
    t.addView(ui.text("Te lo riproponiamo in «Da fare»", 12f, C.MUTED), ui.lp(top = 2))
    r.addView(t, ui.lp(w = 0, weight = 1f, start = 12))
    ui.tap(r, 16) { p.remind = !p.remind; showItem() }
    body.addView(r, ui.lp(top = 10))
}

/** What the purchasing office did with this item last week, in one line. */
private fun MainActivity.lastWeekLine(ean: String): View? {
    val l = Feedbacks.last(list.server?.previous, ean) ?: return null
    val got = l.got
    val (fg, txt) = when {
        got == null -> C.MUTED to "chiesti ${l.asked} · in attesa"
        got >= l.asked -> C.GREEN_INK to "chiesti ${l.asked} · ordinati $got"
        got <= 0 -> C.RED_INK to "chiesti ${l.asked} → non ordinato"
        else -> C.AMBER_INK to "chiesti ${l.asked} → ordinati $got"
    }
    val c = ui.col()
    val r = ui.row()
    r.addView(ui.icon(Icons.REFRESH, fg, 14, 2.2f))
    r.addView(ui.text("Settimana scorsa: ", 12f, C.MUTED), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 6))
    r.addView(ui.text(txt, 12f, fg, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f))
    c.addView(r)
    if (l.note.isNotEmpty()) c.addView(ui.text("Ufficio: " + l.note, 12f, C.INK).lines(2), ui.lp(top = 3))
    return c
}

/** The fixed actions at the bottom of the card. */
private fun MainActivity.actions(p: Pending, v: Verdict, target: String, item: Item?, info: StoreInfo?, sys: StoreInfo?,
                                 adv: Advice, inList: Line?, rep: StockReport?): View {
    val c = ui.col()
    val name = item?.name ?: target
    when (v.kind) {
        Kind.ORDER, Kind.SUBSTITUTE -> {
            val inIt = inList != null && inList.boxes > 0
            val need = needsWhy(p, info, adv)
            val ready = !need || p.reasonText() != null
            val label = when {
                inIt && inList!!.boxes == p.boxes && !need -> "Va bene così"
                inIt -> "Cambia in ${p.boxes} ${colli(p.boxes)}"
                v.kind == Kind.SUBSTITUTE -> "Ordina il sostituto · ${p.boxes}"
                else -> "Aggiungi ${p.boxes} ${colli(p.boxes)}"
            }
            c.addView(ui.primary(label, if (v.kind == Kind.SUBSTITUTE) C.BLUE else C.GREEN, ready,
                if (inIt && inList!!.boxes == p.boxes) Icons.CHECK else Icons.PLUS,
                whyDisabled = { toast("Scegli il motivo") }) { done(commit(p, target, name)) })
            c.addView(if (inIt) ui.ghost("Togli dalla lista", C.RED_INK) { removeLine(target); done("Tolto: ${name.take(36)}") }
                else ui.ghost("Annulla") { done(null) }, ui.lp(top = 4))
        }
        Kind.URGENT_ONLY -> {
            val inIt = inList != null && inList.boxes > 0 && inList.kind == "urgent"
            val why = p.reasonText()
            c.addView(ui.primary(if (inIt) "Salva l'urgenza · ${p.boxes}" else "Ordina come urgenza · ${p.boxes}", C.ORANGE, why != null, Icons.BOLT,
                whyDisabled = { toast("Scegli perché è urgente") }) {
                val it = v.item!!
                change(Line(it.ean, it.name.take(120), p.boxes, "urgent", reason = why!!.take(120), updatedBy = api.deviceName, updatedAt = System.currentTimeMillis()))
                done("Urgenza in lista: ${it.name.take(36)} · ${p.boxes}")
            })
            c.addView(if (inIt) ui.ghost("Togli dalla lista", C.RED_INK) { removeLine(target); done("Urgenza tolta") }
                else ui.ghost("Non serve") { done(null) }, ui.lp(top = 4))
        }
        Kind.NOT_AVAILABLE -> c.addView(ui.primary("OK", C.NAVY, true, Icons.CHECK) { done(commit(p, target, name)) })
        else -> c.addView(ui.primary("OK", C.NAVY, true, Icons.CHECK) { done(null) })
    }
    return c
}

/** Puts the item in the list as decided on the card. Returns the line to flash, or null. */
private fun MainActivity.commit(p: Pending, target: String, name: String): String? {
    val v = p.verdict
    val now = System.currentTimeMillis()
    val me = api.deviceName
    val why = p.reasonText()
    why?.let { if (it in WHY_REASONS) api.whyUse = Feedbacks.countUse(api.whyUse, it) }
    val line = when (v.kind) {
        Kind.ORDER -> Line(v.item!!.ean, v.item.name.take(120), p.boxes, "griglia", reason = why?.take(120), updatedBy = me, updatedAt = now)
        Kind.SUBSTITUTE -> {
            val s = v.substitute!!
            Line(s.ean, s.name.take(120), p.boxes, "sub", reason = why?.take(120), origEan = v.item?.ean, origName = v.item?.name?.take(120), updatedBy = me, updatedAt = now)
        }
        Kind.NOT_AVAILABLE -> if (p.remind) Line(v.item!!.ean, v.item.name.take(120), 0, "remind", updatedBy = me, updatedAt = now) else {
            val old = list.line(v.item!!.ean)
            if (old != null && old.kind == "remind") change(old.copy(boxes = 0, kind = "griglia", updatedAt = now))
            return null
        }
        else -> return null
    }
    val old = list.line(line.ean)
    if (old != null && old.boxes == line.boxes && old.kind == line.kind && old.reason == line.reason) return null
    change(line)
    return if (line.kind == "remind") "Ti avvisiamo quando torna: ${name.take(32)}" else "In lista: ${name.take(32)} · ${line.boxes} ${colli(line.boxes)}"
}

/** Closes the card and goes back to where it was opened; `msg` flashes on the scan tab. */
internal fun MainActivity.done(msg: String?) {
    if (msg != null) { beep(Kind.ORDER); flash = msg }
    sheet.close()
    render()
}

/** "Is the stock wrong?" on the same card: two clear ways. */
private fun MainActivity.stockChoices(p: Pending, ean: String, name: String, sys: StoreInfo) {
    val body = ui.col()
    body.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8))
    val head = ui.row()
    head.addView(ui.iconTap(Icons.BACK, C.INK, "Indietro") { p.mode = 0; showItem() })
    head.addView(ui.text("La giacenza è sbagliata?", 20f, C.INK, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f, start = 4))
    body.addView(head)
    body.addView(ui.text("A sistema ${n(sys.stock)} pz · ${name.take(40)}", 13f, C.MUTED).oneLine(), ui.lp(top = 4))
    fun option(icon: Icons.Spec, fg: Int, fill: Int, line: Int, title: String, sub: String, onClick: () -> Unit) {
        val r = ui.row()
        r.background = ui.bg(fill, 18, line, 2)
        r.setPadding(ui.dp(16), ui.dp(16), ui.dp(14), ui.dp(16))
        r.addView(ui.iconBadge(icon, Color.WHITE, fg, 48, 24, 2.2f))
        val t = ui.col()
        t.addView(ui.text(title, 17f, C.INK, ui.bold))
        t.addView(ui.text(sub, 13f, C.MUTED).lines(2), ui.lp(top = 3))
        r.addView(t, ui.lp(w = 0, weight = 1f, start = 14))
        r.addView(ui.icon(Icons.NEXT, fg, 20, 2.4f))
        ui.tap(r, 18) { r.haptic(); onClick() }
        body.addView(r, ui.lp(top = 14))
    }
    if (p.stockForWhy) {
        option(Icons.BOX, C.RED_INK, C.RED_BG, 0xFFF7C6C0.toInt(), "Conta adesso e aggiungi", "Lo conti ora, lo segnali e va in lista") {
            countFromSheet(p, ean, name, sys, forWhy = true)
        }
        option(Icons.LIST, C.AMBER_INK, C.AMBER_SOFT, C.AMBER_LINE, "Aggiungi e contalo dopo", "Va in lista ora e in «Da contare»") {
            toCount.add(ean, name, System.currentTimeMillis()); saveToCount()
            p.reason = "Giacenza da verificare · da contare"
            p.mode = 0
            done(commitWithReason(p, ean, name, "Giacenza da verificare · da contare"))
        }
    } else {
        option(Icons.BOX, C.RED_INK, C.RED_BG, 0xFFF7C6C0.toInt(), "Conta adesso", "Scaffale e magazzino, poi lo segnali all'ufficio") {
            countFromSheet(p, ean, name, sys, forWhy = false)
        }
        option(Icons.LIST, C.AMBER_INK, C.AMBER_SOFT, C.AMBER_LINE, "Metti in «Da contare»", "Lo conti dopo con gli altri · intanto continui") {
            toCount.add(ean, name, System.currentTimeMillis()); saveToCount()
            p.mode = 0
            toast("In «Da contare» · ${toCount.size}")
            showItem()
        }
    }
    sheet.show(body, ui.ghost("Annulla") { p.mode = 0; showItem() })
}

private fun MainActivity.commitWithReason(p: Pending, ean: String, name: String, reason: String): String? {
    p.reason = "Altro"
    p.other = reason
    return commit(p, ean, name)
}

/** Opens the count screen from the card; afterwards back to the card (or straight in the list). */
private fun MainActivity.countFromSheet(p: Pending, ean: String, name: String, sys: StoreInfo, forWhy: Boolean) {
    val keep = p
    sheet.onClose = null
    sheet.close(false)
    showCount(ean, name, sys,
        after = { counted ->
            pending = keep
            keep.mode = 0
            if (forWhy) {
                done(commitWithReason(keep, ean, name, "Giacenza sbagliata · contati $counted pz"))
            } else {
                render()
                toast("Giacenza segnalata: $counted pz")
                showItem()
            }
        },
        back = { pending = keep; keep.mode = 0; render(); showItem() })
}
