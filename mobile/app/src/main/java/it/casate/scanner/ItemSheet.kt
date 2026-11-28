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
    /** First draw after the scan: the coloured header pops in. */
    var fresh = true
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
    body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16))
    val hero = heroBand(v, adv)

    // the product (for a substitute: the old one crossed out, then the new one)
    if (v.kind == Kind.SUBSTITUTE) {
        val old = ui.row()
        old.background = ui.dashed(0xFFC4CCDA.toInt(), 0xFFEEF1F6.toInt(), 16)
        old.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        old.addView(ui.photo(v.item!!.ean, v.item.name, 44, C.FAINT, faded = true))
        val oc = ui.col()
        oc.addView(ui.text("HAI SCANSIONATO · NON PIÙ IN VENDITA", 10f, C.MUTED, ui.bold).oneLine())
        oc.addView(ui.struck(ui.text(v.item.name, 13f, C.MUTED, ui.medium)).oneLine(), ui.lp(top = 3))
        oc.addView(ui.num(v.item.ean + (v.item.pack?.takeIf { it > 0 }?.let { " · ${n(it)} pz/collo" } ?: ""), 12f, C.MUTED).oneLine(), ui.lp(top = 2))
        // the old item's numbers too (stock still on the shelf, what it sold): no need to look the old code up
        val oi = catalog?.info(v.item.ean)
        if (oi != null) {
            val parts = listOfNotNull("In negozio ${n(oi.stock ?: 0.0)} pz", "venduti 30g ${n(oi.sold30 ?: 0.0)}",
                oi.lastSaleDays?.let { when (it) { 0 -> "ultima vendita oggi"; 1 -> "ultima vendita ieri"; else -> "ultima vendita $it gg fa" } },
                (oi.transitBoxes ?: 0.0).takeIf { it > 0 }?.let { "in arrivo ${n(it)} ${colli(it.toInt())}" })
            oc.addView(ui.text(parts.joinToString(" · "), 12f, if ((oi.stock ?: 0.0) > 0) C.INK else C.MUTED, ui.medium).lines(2), ui.lp(top = 4))
        }
        old.addView(oc, ui.lp(w = 0, weight = 1f, start = 12))
        if (oi != null) {
            old.addView(ui.icon(Icons.NEXT, C.MUTED, 16, 2.2f), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginStart = ui.dp(6) })
            val oldPack = oi.pack?.takeIf { it > 0 } ?: v.item.pack
            ui.tap(old, 16) { inStoreFromSheet(p, v.item.ean, v.item.name, oi, oldPack) }
        }
        body.addView(old)
        body.addView(ui.icon(Icons.DOWN, C.BLUE, 20, 2.4f), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(6); bottomMargin = ui.dp(6) })
    }
    val pr = ui.row()
    pr.addView(ui.photo(item?.ean ?: p.scan, name, 64, if (v.kind == Kind.NOT_AVAILABLE) C.AMBER_INK else kindColor(v.kind)))
    val pc = ui.col()
    if (v.kind == Kind.SUBSTITUTE) pc.addView(ui.text("ORDINA QUESTO AL SUO POSTO", 10f, C.BLUE, ui.bold))
    pc.addView(ui.text(name, 16f, C.INK, ui.bold).lines(2))
    pc.addView(ui.num((item?.ean ?: p.scan) + (pack?.let { " · ${n(it)} pz/collo" } ?: ""), 12f, C.MUTED).oneLine(), ui.lp(top = 3))
    pr.addView(pc, ui.lp(w = 0, weight = 1f, start = 12))
    body.addView(pr)

    // this store's numbers
    if (info != null) {
        val cover = info.coverDays((info.transitBoxes ?: 0.0) * (pack ?: 0.0))
        val empty = (info.stock ?: 0.0) <= 0
        val tiles = ui.row()
        fun add(t: View) = tiles.addView(t, ui.lp(w = 0, weight = 1f, start = if (tiles.childCount == 0) 0 else 6))
        val stockLabel = if (rep?.status == "open") "Contati" else "In negozio"
        add(if (empty) ui.tile(stockLabel, "0", "pz", C.RED_SOFT, 0xFFF7C6C0.toInt(), C.RED_INK, C.RED_INK) else ui.tile(stockLabel, n(info.stock), "pz"))
        add(ui.tile("Venduti 30g", n(info.sold30), "pz"))
        add(ui.tile("In arrivo", n(info.transitBoxes ?: 0.0), colli((info.transitBoxes ?: 0.0).toInt()), fg = if ((info.transitBoxes ?: 0.0) > 0) C.BLUE else C.INK))
        add(when {
            cover == null -> ui.tile("Basta per", "–")
            cover < 14 -> ui.tile("Basta per", "$cover", "gg", C.AMBER_SOFT, C.AMBER_LINE, C.AMBER_INK, C.AMBER_INK)
            else -> ui.tile("Basta per", "$cover", "gg")
        })
        // tap = «In negozio»: deliveries, sales per month, other stores, stock check
        val open = { inStoreFromSheet(p, target, name, info, pack) }
        ui.tap(tiles, 12) { open() }
        body.addView(tiles, ui.lp(top = 14))
        val more = ui.row()
        more.setPadding(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(2))
        more.addView(ui.icon(Icons.STORE, C.BLUE, 15, 2f))
        val what = listOfNotNull(info.arrivals.firstOrNull()?.let { "ultimo arrivo ${it.dayText}" }, "vendite al mese", "altri negozi")
        more.addView(ui.text("In negozio: " + what.joinToString(" · "), 12f, C.BLUE, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f, start = 6))
        more.addView(ui.icon(Icons.NEXT, C.BLUE, 14, 2.4f))
        ui.tap(more, 8) { open() }
        body.addView(more)
    } else if (v.kind != Kind.UNKNOWN) {
        val r = ui.row()
        r.background = ui.bg(0xFFEEF1F6.toInt(), 12)
        r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        r.addView(ui.icon(Icons.INFO, C.MUTED, 16, 2f))
        r.addView(ui.text("Giacenza e vendite di $store non ancora pubblicate", 12f, C.MUTED), ui.lp(w = 0, weight = 1f, start = 8))
        body.addView(r, ui.lp(top = 14))
    }
    priceLine(info)?.let { body.addView(it, ui.lp(top = 8)) }
    if (info != null && sys != null) body.addView(stockRow(p, target, name, sys, rep), ui.lp(top = 8))

    when (v.kind) {
        Kind.ORDER, Kind.SUBSTITUTE -> decision(body, p, info, pack, adv, inList)
        Kind.URGENT_ONLY -> urgentBlock(body, p, v, info, pack)
        Kind.NOT_AVAILABLE -> notAvailableBlock(body, p, v, info)
        Kind.DIRECT_ONLY, Kind.UNKNOWN -> {
            val d = v.item?.direct.orEmpty()
            body.addView(infoCard(if (d.isNotEmpty()) Icons.TRUCK else Icons.INFO, if (d.isNotEmpty()) C.GREEN_INK else C.MUTED,
                if (d.isNotEmpty()) C.GREEN_SOFT else 0xFFF1F4F8.toInt(),
                if (d.isNotEmpty()) "Dal fornitore diretto $d" else "Non si ordina nella lista DETERSIVI",
                if (d.isNotEmpty()) "Con l'ordine diretto del negozio"
                else if (Requests.eligible(v)) "Se serve, chiedilo all'ufficio da qui: ti rispondono sullo scanner"
                else "Se serve davvero, chiedi all'ufficio acquisti"), ui.lp(top = 16))
        }
    }
    if (Requests.eligible(v)) requestBlock(body, target)
    lastWeekLine(target)?.let { body.addView(it, ui.lp(top = 14)) }

    sheet.show(hero, body, actions(p, v, target, item, info, sys, adv, inList, rep), animate = p.fresh)
    p.fresh = false
}

/** Sale price (struck through when on offer) and the offer price; the purchase price is never shown. */
private fun MainActivity.priceLine(info: StoreInfo?): View? {
    val price = info?.price?.takeIf { it > 0 }
    val promo = info?.promoPrice?.takeIf { it > 0 }
    if (price == null && promo == null) return null
    val pr = ui.row()
    pr.background = ui.bg(C.CARD, 12, C.LINE)
    pr.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
    pr.addView(ui.icon(Icons.TAG, C.MUTED, 16, 2f))
    pr.addView(ui.text("Prezzo ", 12f, C.MUTED), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
    if (price != null) {
        val sale = ui.num(eur(price), 15f)
        if (promo != null) ui.struck(sale).setTextColor(C.FAINT)
        pr.addView(sale)
    }
    pr.addView(View(this), ui.lp(w = 0, h = 1, weight = 1f))
    if (promo != null) pr.addView(ui.chip("OFFERTA " + eur(promo) + (if (info.promoEnd.isNotEmpty()) " · fino " + info.promoEnd else ""), Color.WHITE, C.RED, 12f))
    return pr
}

/** The coloured band of the verdict (v3 look). */
private fun MainActivity.heroBand(v: Verdict, adv: Advice?): View {
    data class Hero(val bg: Int, val fg: Int, val sub: Int, val icon: Icons.Spec, val title: String, val line: String)
    val h = when (v.kind) {
        // green = can be ordered (a fact about the item); whether the system advises it is said in the card below
        Kind.ORDER -> Hero(C.GREEN, Color.WHITE, 0xFFD5F5E8.toInt(), Icons.CHECK, "ORDINABILE",
            if (adv?.boxes == 0) "In Griglia · per ora il sistema non lo consiglia"
            else "In Griglia · " + v.item!!.wholesalers.size + (if (v.item.wholesalers.size == 1) " grossista lo ha" else " grossisti lo hanno"))
        Kind.SUBSTITUTE -> Hero(C.BLUE, Color.WHITE, 0xFFDCE6FF.toInt(), Icons.SWAP, "SOSTITUITO", "Questo codice non c'è più · ordina il nuovo")
        Kind.NOT_AVAILABLE -> Hero(C.AMBER, 0xFF2B1A00.toInt(), 0xFF4A3300.toInt(), Icons.CLOCK, "NON DISPONIBILE", "In Griglia, ma questa settimana nessun grossista lo ha")
        Kind.URGENT_ONLY -> Hero(C.ORANGE, Color.WHITE, 0xFFFFE2CC.toInt(), Icons.INFO,
            if (v.item!!.griglia == 2) "TOLTO DALLA GRIGLIA" else "NON IN GRIGLIA",
            "Si ordina dal fornitore diretto · dai grossisti DETERSIVI solo se urgente")
        Kind.DIRECT_ONLY -> Hero(C.RED, Color.WHITE, 0xFFFFD9D4.toInt(), Icons.BAN, "NON IN GRIGLIA", "Nessun grossista DETERSIVI lo ha")
        Kind.UNKNOWN -> Hero(0xFF475569.toInt(), Color.WHITE, 0xFFE2E8F0.toInt(), Icons.INFO, "SCONOSCIUTO", "Non è in Griglia né nei listini dei grossisti")
    }
    val r = ui.row()
    r.setBackgroundColor(h.bg)
    r.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16))
    r.addView(ui.iconBadge(h.icon, if (h.fg == Color.WHITE) 0x2EFFFFFF else 0x59FFFFFF, h.fg, 48, 26, 2.6f))
    val t = ui.col()
    t.addView(ui.text(h.title, 23f, h.fg, ui.bold).oneLine())
    t.addView(ui.text(h.line, 13f, h.sub).lines(2), ui.lp(top = 3))
    r.addView(t, ui.lp(w = 0, weight = 1f, start = 14))
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

/** QUANTI COLLI? card: the suggestion (tap = use it), − n +, what it means; the reason when needed. */
private fun MainActivity.decision(body: LinearLayout, p: Pending, info: StoreInfo?, pack: Double?, adv: Advice, inList: Line?) {
    val c = ui.col(16)
    val b = adv.boxes
    val warn = needsWhy(p, info, adv)
    // amber = the system would not order this (or not this many): you can, with a reason
    c.background = if (warn) ui.bg(0xFFFFFBF0.toInt(), 18, 0xFFF5D78E.toInt()) else ui.bg(C.CARD, 18, C.LINE)
    val head = ui.row()
    head.addView(ui.text("QUANTI COLLI?", 13f, C.MUTED, ui.bold), ui.lp(w = 0, weight = 1f))
    if (b != null) {
        val chip = ui.row()
        chip.background = ui.bg(if (b > 0) C.GREEN_SOFT else C.AMBER_SOFT, 999)
        chip.setPadding(ui.dp(10), ui.dp(6), ui.dp(12), ui.dp(6))
        chip.addView(ui.icon(Icons.SPARK, if (b > 0) C.GREEN_INK else C.AMBER_INK, 13, 2.4f))
        chip.addView(ui.text((if (adv.estimated) "Stima " else "Consigliato ") + b, 12f, if (b > 0) C.GREEN_INK else C.AMBER_INK, ui.bold), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 5))
        chip.contentDescription = "Usa il consigliato: $b"
        if (b > 0) ui.tap(chip, 999) { p.boxes = b; showItem() }
        head.addView(chip)
    }
    c.addView(head)
    c.addView(ui.text(if (b == 0) "Il sistema non lo ordinerebbe · " + adv.reason else adv.reason, 12f, if (b == 0) C.AMBER_INK else C.MUTED).lines(2), ui.lp(top = 6))
    dataAge()?.let { c.addView(ui.text(it, 11f, C.AMBER_INK), ui.lp(top = 2)) }
    if (inList != null && inList.boxes > 0) {
        val by = if (!inList.updatedBy.isNullOrEmpty() && inList.updatedBy != api.deviceName) " · ${inList.updatedBy}" else ""
        c.addView(ui.text("Già in lista: ${inList.boxes} ${colli(inList.boxes)}$by", 12f, C.BLUE_INK, ui.bold), ui.lp(top = 4))
    }
    val note = ui.text("", 12f, C.MUTED).apply { gravity = Gravity.CENTER }
    fun noteText() {
        val pcs = pack?.let { " = ${n(it * p.boxes)} pz" } ?: ""
        val withPack = info?.copy(pack = pack) ?: pack?.let { StoreInfo(pack = it) }
        val cover = Catalog.coverAfter(withPack, p.boxes)?.let { " · arrivi a ~${Catalog.months(it)}" } ?: ""
        note.text = colli(p.boxes) + pcs + cover
    }
    noteText()
    val st = Stepper(ui, { p.boxes }, { p.boxes = it }, 1, 64, 44f, "Quanti colli?", onChange = { noteText() }, onDone = { showItem() })
    c.addView(st.view, ui.lp(top = 14))
    c.addView(note, ui.lp(top = 8))
    body.addView(c, ui.lp(top = 14))

    // the reason is asked in a pop-up when adding (see askReason)
    if (warn) {
        val h = ui.row()
        h.addView(ui.icon(Icons.ALERT, C.AMBER_INK, 14, 2.2f))
        h.addView(ui.text(if (b == 0) "Il sistema non lo consiglia · aggiungendolo ti chiediamo il motivo"
            else "Più del consigliato · aggiungendolo ti chiediamo il motivo", 12f, C.AMBER_INK, ui.bold).lines(2), ui.lp(w = 0, weight = 1f, start = 6))
        c.addView(h, ui.lp(top = 10))
    }
}

/**
 * Pop-up that comes up after pressing Aggiungi / Ordina urgenza: says why the system would not
 * order it, then one tap on a reason submits. «Altro» asks for a sentence; «Giacenza sbagliata»
 * goes to the stock choices instead.
 */
private fun MainActivity.askReason(title: String, why: String, sub: String, accent: Int, soft: Int, ink: Int,
                                   reasons: List<String>, onStock: (() -> Unit)?, onPick: (String) -> Unit) {
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val box = ui.col(20)
    box.background = ui.bg(C.CARD, 24)
    val bar = View(this).apply { background = ui.bg(C.LINE2, 3) }
    box.addView(bar, LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    box.addView(ui.text(title, 21f, C.INK, ui.bold), ui.lp(top = 14))
    val info = ui.row()
    info.background = ui.bg(soft, 14)
    info.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
    info.addView(ui.icon(Icons.ALERT, ink, 20, 2.2f))
    val t = ui.col()
    t.addView(ui.text(why, 15f, ink, ui.bold).lines(2))
    if (sub.isNotEmpty()) t.addView(ui.text(sub, 13f, ink).lines(3), ui.lp(top = 2))
    info.addView(t, ui.lp(w = 0, weight = 1f, start = 10))
    box.addView(info, ui.lp(top = 12))
    box.addView(ui.text("Scegli il motivo · lo vede l'ufficio acquisti", 12f, C.MUTED), ui.lp(top = 14))
    val list = ui.col()
    fun row(label: String, icon: Icons.Spec, onClick: () -> Unit): View {
        val r = ui.row()
        r.minimumHeight = ui.dp(54)
        r.background = ui.bg(C.CARD, 14, C.LINE)
        r.setPadding(ui.dp(14), 0, ui.dp(12), 0)
        r.addView(ui.icon(icon, accent, 18, 2.2f))
        r.addView(ui.text(label, 16f, C.INK, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f, start = 12))
        r.addView(ui.icon(Icons.NEXT, C.MUTED, 16, 2.2f))
        ui.tap(r, 14) { r.haptic(); onClick() }
        return r
    }
    for (r in reasons) {
        when {
            r == "Giacenza sbagliata" && onStock != null -> list.addView(row("La giacenza è sbagliata", Icons.BOX) { d.dismiss(); onStock() }, ui.lp(top = 8))
            r == "Giacenza sbagliata" -> {}
            r == "Altro" -> list.addView(row("Altro · scrivo io", Icons.MORE) {
                list.removeAllViews()
                val e = EditText(this).apply {
                    hint = "Scrivi il motivo"
                    setSingleLine(); textSize = 16f
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    background = ui.bg(C.CARD, 12, C.LINE2)
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                }
                list.addView(e, ui.lp(top = 8))
                val ok = ui.primary("Aggiungi", C.GREEN, true, Icons.CHECK) {
                    val txt = e.text.toString().trim()
                    if (txt.length < 3) toast("Scrivi almeno 3 lettere") else { d.dismiss(); onPick(txt) }
                }
                list.addView(ok, ui.lp(top = 10, h = ui.dp(52)))
                e.requestFocus()
                d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            }, ui.lp(top = 8))
            else -> list.addView(row(r, Icons.CHECK) { d.dismiss(); onPick(r) }, ui.lp(top = 8))
        }
    }
    box.addView(list)
    box.addView(ui.button("Annulla", 0, C.INK, size = 15f) { d.dismiss() }, ui.lp(top = 12, h = ui.dp(48)))
    val sc = android.widget.ScrollView(this).apply { addView(box) }
    d.setContentView(sc)
    d.window?.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        attributes = attributes.apply { y = ui.dp(8); horizontalMargin = 0f }
        decorView.setPadding(ui.dp(8), 0, ui.dp(8), 0)
    }
    d.show()
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
    body.addView(infoCard(Icons.TRUCK, C.ORANGE, C.ORANGE_SOFT, "Ordinalo dal fornitore diretto" + (if (d.isNotEmpty()) " $d" else ""),
        "Dai grossisti DETERSIVI solo in caso di urgenza"), ui.lp(top = 16))
    val box = ui.card(20)
    box.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16))
    box.addView(ui.caption("Colli urgenti"))
    val note = ui.text("", 12f, C.MUTED).apply { gravity = Gravity.CENTER }
    val upd = { note.text = colli(p.boxes) + (pack?.let { " = ${n(it * p.boxes)} pz" } ?: "") }
    upd()
    box.addView(Stepper(ui, { p.boxes }, { p.boxes = it }, 1, 56, 36f, "Quanti colli?", onChange = upd, onDone = {}).view, ui.lp(top = 10))
    box.addView(note, ui.lp(top = 8))
    body.addView(box, ui.lp(top = 12))
    if (info != null && (info.stock ?: 0.0) <= 0) body.addView(ui.text("Lo scaffale è vuoto", 12f, C.RED_INK, ui.bold), ui.lp(top = 8))
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
    c.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(16))
    val name = item?.name ?: target
    fun pair(second: View, main: View) {
        val r = ui.row()
        r.addView(second, ui.fixed(100, 52))
        r.addView(main, ui.lp(w = 0, h = ui.dp(52), weight = 1f, start = 10))
        c.addView(r)
    }
    when (v.kind) {
        Kind.ORDER, Kind.SUBSTITUTE -> {
            val inIt = inList != null && inList.boxes > 0
            val need = needsWhy(p, info, adv)
            val label = when {
                inIt && inList!!.boxes == p.boxes && !need -> "Va bene così"
                inIt -> "Cambia in ${p.boxes} ${colli(p.boxes)}"
                v.kind == Kind.SUBSTITUTE -> "Ordina il sostituto · ${p.boxes}"
                else -> "Aggiungi ${p.boxes} ${colli(p.boxes)}"
            }
            val hint = ui.row(Gravity.CENTER)
            hint.addView(ui.icon(Icons.BARCODE, C.MUTED, 14, 2f))
            hint.addView(ui.text("Riscansiona lo stesso articolo: +1 collo", 12f, C.MUTED), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 6))
            c.addView(hint, ui.lp(bottom = 10))
            pair(if (inIt) ui.button("Togli", 0, C.RED_INK, size = 15f) { removeLine(target); done("Tolto dalla lista: ${name.take(36)}") }
                else ui.button("Annulla", 0, C.INK, size = 15f) { done(null) },
                ui.primary(label, if (need) 0xFFB45309.toInt() else if (v.kind == Kind.SUBSTITUTE) C.BLUE else C.GREEN, true,
                    if (inIt && inList!!.boxes == p.boxes) Icons.CHECK else Icons.PLUS) {
                    // ask again only if the decision changed since the saved reason
                    val saved = inList?.reason
                    val askAgain = need && (saved.isNullOrEmpty() || !inIt || (adv.boxes ?: 0) > 0 && Feedbacks.overOrder(adv.boxes!!, p.boxes) && !Feedbacks.overOrder(adv.boxes, inList!!.boxes))
                    if (!askAgain) { if (need && p.reason == null) p.reason = saved; done(commit(p, target, name)) }
                    else {
                        val b = adv.boxes ?: 0
                        val why = if (b == 0) "Il sistema non lo ordinerebbe" else "Ne metti ${p.boxes}, il sistema ne consiglia $b"
                        val sub = if (b == 0) adv.reason else "Scorta già sufficiente per il consigliato"
                        askReason("Perché lo ordini?", why, sub, C.AMBER, C.AMBER_SOFT, C.AMBER_INK,
                            Feedbacks.byUse(WHY_REASONS.dropLast(1), api.whyUse) + "Altro",
                            onStock = { p.mode = 1; p.stockForWhy = true; showItem() }) { r ->
                            p.reason = r; p.other = ""
                            done(commit(p, target, name))
                        }
                    }
                })
        }
        Kind.URGENT_ONLY -> {
            if (Requests.eligible(v)) {
                val r = list.request(target)
                val b = when (r?.status) {
                    "info" -> ui.outline("Rispondi all'ufficio", C.AMBER_INK, C.AMBER) { replyOffice(r, name) }
                    "open" -> requestEdit(target, name, v)
                    else -> ui.outline(if (r == null) "Chiedi all'ufficio di rimetterlo in Griglia" else "Chiedi di nuovo all'ufficio", C.BLUE_INK, 0xFFC6D5FF.toInt()) {
                        askOffice(target, name, v, null)
                    }
                }
                b?.let { c.addView(it, ui.lp(bottom = 10)) }
            }
            val inIt = inList != null && inList.boxes > 0 && inList.kind == "urgent"
            pair(if (inIt) ui.button("Togli", 0, C.RED_INK, size = 15f) { removeLine(target); done("Tolto dalla lista") }
                else ui.button("Non serve", 0, C.INK, size = 15f) { done(null) },
                ui.primary(if (inIt) "Salva · ${p.boxes}" else "Ordina urgenza · ${p.boxes}", C.ORANGE, true, Icons.BOLT) {
                    val it = v.item!!
                    fun save(why: String) {
                        change(Line(it.ean, it.name.take(120), p.boxes, "urgent", reason = why.take(120), updatedBy = api.deviceName, updatedAt = System.currentTimeMillis()))
                        done("In lista · non in Griglia: ${it.name.take(32)} · ${p.boxes}")
                    }
                    val saved = inList?.reason
                    if (inIt && !saved.isNullOrEmpty()) save(saved)
                    else {
                        val d = it.direct
                        askReason("Perché è urgente?", if (it.griglia == 2) "Tolto dalla Griglia" else "Non è in Griglia",
                            "Si ordina dal fornitore diretto" + (if (d.isNotEmpty()) " $d" else "") + " · dai grossisti DETERSIVI solo se urgente",
                            C.ORANGE, C.ORANGE_SOFT, C.ORANGE, URGENT_REASONS, onStock = null) { r -> save(r) }
                    }
                })
        }
        Kind.NOT_AVAILABLE, Kind.UNKNOWN, Kind.DIRECT_ONLY -> {
            val ok = { done(if (v.kind == Kind.NOT_AVAILABLE) commit(p, target, name) else null) }
            requestEdit(target, name, v)?.let { c.addView(it, ui.lp(bottom = 10)) }
            val ask = requestButton(target, name, v)
            if (ask != null) pair(ui.button("OK", 0, C.INK, size = 15f) { ok() }, ask)
            else c.addView(ui.primary("OK · prossimo articolo", C.NAVY, true, Icons.CHECK) { ok() })
        }
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
    body.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(16))
    body.addView(ui.text("La giacenza è sbagliata?", 22f, C.INK, ui.bold).oneLine())
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
    val foot = ui.col()
    foot.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(16))
    foot.addView(ui.button("Annulla", 0, C.INK, size = 15f) { p.mode = 0; showItem() }, ui.lp(h = ui.dp(52)))
    sheet.show(heroBand(p.verdict, null), body, foot)
}

private fun MainActivity.commitWithReason(p: Pending, ean: String, name: String, reason: String): String? {
    p.reason = "Altro"
    p.other = reason
    return commit(p, ean, name)
}

/** Opens «In negozio» from the card; Back = the card again. */
private fun MainActivity.inStoreFromSheet(p: Pending, ean: String, name: String, info: StoreInfo, pack: Double?) {
    val keep = p
    sheet.onClose = null
    sheet.close(false)
    showInStore(ean, name, info, pack) { pending = keep; render(); showItem() }
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

/** "Dati del 20/11" when the store numbers are more than 2 days old (the advice is computed from them). */
internal fun MainActivity.dataAge(): String? {
    val d = catalog?.dataDate?.takeIf { it.length == 10 } ?: return null
    val day = runCatching { java.time.LocalDate.parse(d) }.getOrNull() ?: return null
    if (java.time.temporal.ChronoUnit.DAYS.between(day, java.time.LocalDate.now()) <= 2) return null
    return "Dati del ${d.substring(8, 10)}/${d.substring(5, 7)}: il consiglio può non essere aggiornato"
}
