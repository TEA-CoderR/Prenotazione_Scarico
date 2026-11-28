package it.casate.scanner

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * «Richieste all'ufficio»: every item the store asked the purchasing office about (last 90 days, all the
 * store's scanners), one line per item, so nobody has to scan things again to see if the office answered.
 * Views: Da guardare (answers not read yet / the office asks something) · In attesa · Risposte · Tutte.
 * Tap a line = the conversation with the office (opening it marks the answer as read); the item on top of it opens the card.
 */
internal fun MainActivity.requestsTab(): View {
    val all = list.server?.requests.orEmpty()
    val counts = Requests.counts(all)
    val f = reqFilter ?: Requests.firstFilter(all).also { reqFilter = it }
    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)
    val toLook = counts[Requests.Filter.TODO] ?: 0
    val waiting = counts[Requests.Filter.WAITING] ?: 0
    val sub = listOfNotNull(
        if (toLook > 0) "$toLook da guardare" else null,
        if (waiting > 0) "$waiting in attesa" else null,
    ).joinToString(" · ").ifEmpty { if (all.isEmpty()) "Nessuna richiesta" else "Tutte lette" }
    root.addView(header("Richieste all'ufficio", sub, if (toLook > 0) C.YELLOW else C.ON_NAVY_MUTED) { reqFilter = null; go(MainActivity.Tab.SCAN) })

    // views: one segmented bar as wide as the screen (number on top, name below), nothing goes off screen
    val seg = ui.row()
    seg.background = ui.bg(0xFFE8ECF3.toInt(), 14)
    seg.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
    for (x in Requests.Filter.values()) {
        val n = counts[x] ?: 0
        val on = x == f
        val alert = x == Requests.Filter.TODO && n > 0
        val c = ui.col()
        c.gravity = Gravity.CENTER
        c.setPadding(ui.dp(2), ui.dp(7), ui.dp(2), ui.dp(7))
        if (on) c.background = ui.bg(C.CARD, 11)
        c.addView(ui.num(n.toString(), 16f, when { alert -> C.AMBER_INK; on -> C.BLUE_INK; n == 0 -> C.FAINT; else -> C.INK })
            .apply { gravity = Gravity.CENTER })
        c.addView(ui.text(x.label, 11f, if (on) C.INK else C.MUTED, if (on) ui.bold else ui.medium).oneLine()
            .apply { gravity = Gravity.CENTER }, ui.lp(top = 1))
        ui.tap(c, 11) { reqFilter = x; render() }
        seg.addView(c, ui.lp(w = 0, weight = 1f))
    }
    val segBox = ui.col()
    segBox.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(2))
    segBox.addView(seg)
    root.addView(segBox)

    val body = ui.col()
    body.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(20))
    val rows = Requests.page(all, f)
    if (rows.isEmpty()) {
        val e = ui.col(32)
        e.gravity = Gravity.CENTER_HORIZONTAL
        val (title, hint) = when (f) {
            Requests.Filter.TODO -> "Niente da guardare" to "Le risposte nuove dell'ufficio compaiono qui"
            Requests.Filter.WAITING -> "Nessuna richiesta in attesa" to "L'ufficio ha risposto a tutte"
            Requests.Filter.ANSWERED -> "Ancora nessuna risposta" to "Quando l'ufficio risponde la trovi qui"
            Requests.Filter.ALL -> "Nessuna richiesta" to "Scansiona un articolo che non si può ordinare e chiedi all'ufficio"
        }
        e.addView(ui.iconBadge(Icons.CHECK, C.GREEN_SOFT, C.GREEN_INK, 64, 32, 2.6f),
            LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        e.addView(ui.text(title, 18f, C.INK, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 12))
        e.addView(ui.text(hint, 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        body.addView(e, ui.lp(top = 30))
    } else {
        val card = ui.card(20)
        card.setPadding(ui.dp(12), ui.dp(2), ui.dp(10), ui.dp(2))
        for ((i, r) in rows.withIndex()) {
            if (i > 0) card.addView(ui.divider(56))
            card.addView(requestRow(r))
        }
        body.addView(card)
        body.addView(ui.text("Ultimi 90 giorni · richieste di tutti gli scanner del negozio", 12f, C.FAINT)
            .apply { gravity = Gravity.CENTER }, ui.lp(top = 12))
    }
    val sv = ScrollView(this)
    sv.isFillViewport = true
    sv.addView(body)
    root.addView(sv, ui.lp(h = 0, weight = 1f))
    return root
}

/** Colours of a request's state: text, soft fill. */
private fun requestTone(r: ProductRequest): Pair<Int, Int> = when {
    r.status == "cancelled" -> C.MUTED to 0xFFF1F4F8.toInt()
    else -> when (Requests.tone(r)) {
        0 -> C.GREEN_INK to C.GREEN_SOFT
        1 -> C.AMBER_INK to C.AMBER_SOFT
        3 -> C.RED_INK to C.RED_SOFT
        else -> C.BLUE_INK to C.BLUE_SOFT
    }
}

/** One request: photo · name · what the office said (or what we asked) · when · state. */
private fun MainActivity.requestRow(r: ProductRequest): View {
    val (fg, fill) = requestTone(r)
    val row = ui.row(Gravity.TOP)
    row.setPadding(ui.dp(2), ui.dp(12), ui.dp(2), ui.dp(12))
    row.addView(ui.photo(r.ean, r.name, 44, fg))
    val c = ui.col()
    val top = ui.row()
    if (r.unseen) top.addView(ui.dot(C.AMBER, 8), LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(6) })
    top.addView(ui.text(r.name.ifEmpty { r.ean }, 15f, C.INK, if (r.unseen) ui.bold else ui.medium).oneLine(), ui.lp(w = 0, weight = 1f))
    c.addView(top)
    c.addView(ui.text(if (r.status == "cancelled") "Annullata dal negozio" else Requests.title(r), 12f, fg, ui.bold).oneLine(), ui.lp(top = 3))
    val detail = when {
        r.status == "open" || r.answer.isEmpty() ->
            "Chiesto " + dayTime(r.createdAt) + (r.boxes?.let { " · $it ${colli(it)}" } ?: "") +
                (r.thread().lastOrNull { it.mine }?.text?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
        else -> "Ufficio " + dayTime(if (r.handledAt > 0) r.handledAt else r.updatedAt) + ": " + r.answer
    }
    c.addView(ui.text(detail, 12f, C.MUTED).lines(2), ui.lp(top = 2))
    if (r.addedToList && r.answerBoxes != null)
        c.addView(ui.text("Già in lista: ${r.answerBoxes} ${colli(r.answerBoxes)}", 12f, C.GREEN_INK, ui.bold).oneLine(), ui.lp(top = 2))
    row.addView(c, ui.lp(w = 0, weight = 1f, start = 12))
    row.addView(ui.chip(Requests.chip(r), fg, fill, 10f),
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(8) })
    ui.tap(row, 12) { openThread(r) }
    return row
}
