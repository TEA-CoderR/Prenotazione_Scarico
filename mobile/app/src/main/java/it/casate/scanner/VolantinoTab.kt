package it.casate.scanner

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** The view open on the VOLANTINO page. */
internal var volFilter = Volantino.Filter.TODO

/** VOLANTINO card on the home page: the next promotion and how much is left to prepare, or «not uploaded yet». */
internal fun MainActivity.volantinoCard(): View {
    val v = catalog?.volantino
    val r = ui.row()
    r.setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(10))
    val t = ui.col()
    if (v == null) {
        r.background = ui.bg(C.CARD, 16, C.LINE)
        r.addView(ui.iconBadge(Icons.TAG, 0xFFF1F4F8.toInt(), C.MUTED, 34, 18, 2.2f))
        t.addView(ui.text("Prossimo VOLANTINO", 14f, C.INK, ui.bold).oneLine())
        t.addView(ui.text("L'ufficio non ha ancora caricato il volantino", 12f, C.MUTED).oneLine(), ui.lp(top = 1))
        r.addView(t, ui.lp(w = 0, weight = 1f, start = 10))
        return r
    }
    val rows = volRows()
    val c = Volantino.counts(rows)
    val todo = c[Volantino.Filter.TODO] ?: 0
    r.background = ui.bg(C.CARD, 16, if (todo > 0) 0xFFF7C6B5.toInt() else C.LINE)
    r.addView(ui.iconBadge(Icons.TAG, if (todo > 0) C.ORANGE_SOFT else C.GREEN_SOFT, if (todo > 0) C.ORANGE else C.GREEN_INK, 34, 18, 2.2f))
    t.addView(ui.text("VOLANTINO ${Volantino.dm(v.start)} – ${Volantino.dm(v.end)}", 14f, C.INK, ui.bold).oneLine())
    t.addView(ui.text(listOfNotNull(when2(v), if (todo > 0) "$todo da preparare" else "tutto pronto",
        (c[Volantino.Filter.DONE] ?: 0).takeIf { it > 0 }?.let { "$it in lista" }).joinToString(" · "),
        12f, if (todo > 0) C.ORANGE else C.GREEN_INK).oneLine(), ui.lp(top = 1))
    r.addView(t, ui.lp(w = 0, weight = 1f, start = 10))
    if (todo > 0) r.addView(ui.num("$todo", 15f, C.ORANGE))
    r.addView(ui.icon(Icons.NEXT, C.FAINT, 16, 2.2f), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginStart = ui.dp(6) })
    ui.tap(r, 16) { go(MainActivity.Tab.VOLANTINO) }
    return r
}

private fun when2(v: VolantinoInfo): String {
    val d = Volantino.daysTo(v.start, java.time.LocalDate.now()) ?: return ""
    val end = Volantino.daysTo(v.end, java.time.LocalDate.now()) ?: 0
    return when {
        d > 1 -> "inizia tra $d gg"
        d == 1L -> "inizia domani"
        d == 0L -> "inizia oggi"
        end >= 0 -> "in corso · finisce tra $end gg"
        else -> "finito"
    }
}

internal fun MainActivity.volRows(): List<Volantino.Row> {
    val cat = catalog ?: return emptyList()
    return Volantino.rows(cat.volantinoItems(), { e -> list.line(e) }, { e -> packOf(e) })
}

/** The VOLANTINO page: what is still to prepare (suggested boxes, «Aggiungi»), what is covered, what is in the list. */
internal fun MainActivity.volantinoTab(): View {
    val v = catalog?.volantino
    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)
    if (v == null) {
        root.addView(header("VOLANTINO", "L'ufficio non ha ancora caricato il volantino", C.ON_NAVY_MUTED) { go(MainActivity.Tab.SCAN) })
        return root
    }
    val rows = volRows()
    val counts = Volantino.counts(rows)
    root.addView(header("VOLANTINO ${Volantino.dm(v.start)} – ${Volantino.dm(v.end)}",
        listOf("${rows.size} articoli", "${v.days} giorni", when2(v)).filter { it.isNotEmpty() }.joinToString(" · "), C.YELLOW) { go(MainActivity.Tab.SCAN) })

    // three views (tabs under the header)
    val seg = ui.row()
    seg.setBackgroundColor(C.CARD)
    for (f in Volantino.Filter.values()) {
        val on = f == volFilter
        val c = ui.col()
        c.gravity = Gravity.CENTER
        c.setPadding(0, ui.dp(10), 0, ui.dp(8))
        val n = counts[f] ?: 0
        c.addView(ui.text("${f.label} $n", 13f, if (on) C.INK else C.MUTED, if (on) ui.bold else ui.medium).apply { gravity = Gravity.CENTER })
        c.addView(View(this).apply { setBackgroundColor(if (on) C.BLUE else 0) }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(3)).apply { topMargin = ui.dp(7); gravity = Gravity.CENTER_HORIZONTAL })
        ui.tap(c, 0) { volFilter = f; render() }
        seg.addView(c, ui.lp(w = 0, weight = 1f))
    }
    root.addView(seg)
    root.addView(ui.divider())

    val page = Volantino.page(rows, volFilter) { e -> catalog?.find(e)?.name ?: e }
    val body = ui.col()
    if (page.isEmpty()) {
        val (t, h) = when (volFilter) {
            Volantino.Filter.TODO -> "Niente da preparare" to "Con la giacenza e gli arrivi basta per tutto il volantino"
            Volantino.Filter.COVERED -> "Nessun articolo già coperto" to "Qui finiscono quelli per cui basta quello che hai"
            Volantino.Filter.DONE -> "Ancora niente in lista" to "Premi «Aggiungi» sugli articoli da preparare"
        }
        val e = ui.col(28)
        e.gravity = Gravity.CENTER_HORIZONTAL
        e.addView(ui.text(t, 17f, C.INK, ui.bold).apply { gravity = Gravity.CENTER })
        e.addView(ui.text(h, 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        body.addView(e, ui.lp(top = 30))
    }
    for (r in page) {
        body.addView(volRow(v, r))
        body.addView(ui.divider(68))
    }
    body.addView(ui.text("Solo gli articoli che si ordinano nella lista DETERSIVI · stima dai volantini passati", 11f, C.FAINT)
        .apply { gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16)) })
    root.addView(ScrollView(this).apply { addView(body) }, ui.lp(h = 0, weight = 1f))

    val bulk = page.filter { volFilter == Volantino.Filter.TODO && Volantino.bulk(it) && canAdd(it.ean) }
    if (bulk.isNotEmpty()) {
        val bar = ui.col()
        bar.setBackgroundColor(C.CARD)
        bar.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
        val boxes = bulk.sumOf { it.boxes ?: 0 }
        bar.addView(ui.primary("Aggiungi i ${bulk.size} consigliati · $boxes colli", C.BLUE, true, Icons.PLUS) {
            val left = page.size - bulk.size
            AlertDialog.Builder(this).setTitle("Aggiungere ${bulk.size} articoli alla lista?")
                .setMessage("$boxes colli in tutto, con il motivo «VOLANTINO»." +
                    (if (left > 0) "\n$left articoli da guardare uno per uno (quantità grandi o da controllare) restano qui." else ""))
                .setPositiveButton("Aggiungi") { _, _ -> for (r in bulk) addVol(v, r, r.boxes ?: 0, quiet = true); toast("In lista: ${bulk.size} articoli"); render() }
                .setNegativeButton("Annulla", null).show()
        })
        root.addView(bar)
    }
    return root
}

/** Only items the DETERSIVO list takes (Griglia, substitutes, not-in-Griglia from the wholesaler). */
private fun MainActivity.canAdd(ean: String): Boolean = when (catalog?.judge(ean)?.kind) {
    Kind.ORDER, Kind.SUBSTITUTE, Kind.URGENT_ONLY -> true
    else -> false
}

private fun MainActivity.volRow(v: VolantinoInfo, r: Volantino.Row): View {
    val item = catalog?.find(r.ean)
    val name = item?.name?.ifEmpty { null } ?: r.ean
    val row = ui.row()
    row.setBackgroundColor(C.CARD)
    row.setPadding(ui.dp(14), ui.dp(9), ui.dp(10), ui.dp(9))
    row.addView(ui.photo(r.ean, name, 42, if (r.vol.basis == 0) C.GREEN else C.ORANGE))
    val c = ui.col()
    c.addView(ui.text(name, 14f, C.INK, ui.medium).lines(2))
    val past = when (r.vol.basis) {
        0 -> (r.vol.lastName.ifEmpty { "ultimo volantino" }) + (r.vol.lastSold?.let { ": $it pz venduti" } ?: "")
        1 -> "Mai in volantino · stima"
        else -> "Mai venduto · stima"
    }
    c.addView(ui.text(listOfNotNull(r.vol.price?.let { "Offerta ${eur(it)}" }, past).joinToString(" · "), 12f,
        if (r.vol.basis == 0) C.MUTED else C.ORANGE).oneLine(), ui.lp(top = 2))
    val tb = r.info.transitBoxes ?: 0.0
    c.addView(ui.text("Servono ~${n(Math.round(r.vol.expected).toDouble())} · hai ${n((r.info.stock ?: 0.0).coerceAtLeast(0.0))}" +
        (if (tb > 0) " · in arrivo ${n(tb)} ${colli(tb.toInt())}" else "") + (if (r.vol.warn) " · da controllare" else ""),
        12f, if (r.vol.warn) C.AMBER_INK else C.MUTED).oneLine(), ui.lp(top = 1))
    row.addView(c, ui.lp(w = 0, weight = 1f, start = 12))
    val side = ui.col()
    side.gravity = Gravity.END
    val add = canAdd(r.ean)
    when {
        r.inList != null && r.inList.boxes > 0 -> {
            side.addView(ui.num("${r.inList.boxes} ${colli(r.inList.boxes)}", 13f, C.GREEN_INK))
            side.addView(ui.text("✓ In lista", 11f, C.GREEN_INK, ui.bold), ui.lp(top = 2))
        }
        (r.boxes ?: 0) > 0 && add -> {
            side.addView(ui.num("+${r.boxes} ${colli(r.boxes!!)}", 14f, C.BLUE).apply { gravity = Gravity.END })
            val b = ui.button("Aggiungi", C.BLUE, size = 12f) { askVol(v, r, name) }
            b.setPadding(ui.dp(10), 0, ui.dp(10), 0)
            side.addView(b, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, h = ui.dp(32), top = 3))
        }
        (r.boxes ?: 0) > 0 -> side.addView(ui.text("Non nella\nlista DETERSIVI", 11f, C.MUTED).apply { gravity = Gravity.END })
        r.boxes == null -> side.addView(ui.text("Senza pz/collo", 11f, C.MUTED))
        else -> side.addView(ui.text("Basta così", 12f, C.GREEN_INK, ui.bold))
    }
    row.addView(side, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
    ui.tap(row, 0) { openItem(r.ean) }
    return row
}

/** «Aggiungi»: the boxes (suggestion already in), changeable, then in the list with the reason «VOLANTINO». */
private fun MainActivity.askVol(v: VolantinoInfo, r: Volantino.Row, name: String) {
    var n = r.boxes ?: 1
    val c = ui.col(20)
    c.addView(ui.text(name, 16f, C.INK, ui.bold).lines(2))
    c.addView(ui.text("Consigliati ${r.boxes} colli per il volantino" + (if (r.vol.warn) " · quantità da controllare" else ""), 12f,
        if (r.vol.warn) C.AMBER_INK else C.MUTED).lines(2), ui.lp(top = 4))
    c.addView(Stepper(ui, { n }, { n = it }, 1, 52, 30f, "Quanti colli?", onChange = {}, onDone = {}).view, ui.lp(top = 14))
    AlertDialog.Builder(this).setView(c)
        .setPositiveButton("Aggiungi") { _, _ -> addVol(v, r, n, quiet = false); render() }
        .setNegativeButton("Annulla", null).show()
}

private fun MainActivity.addVol(v: VolantinoInfo, r: Volantino.Row, boxes: Int, quiet: Boolean) {
    if (boxes <= 0) return
    val verdict = catalog?.judge(r.ean) ?: return
    val now = System.currentTimeMillis()
    val reason = Volantino.reason(v)
    val line = when (verdict.kind) {
        Kind.ORDER -> Line(verdict.item!!.ean, verdict.item.name.take(120), boxes, "griglia", reason = reason, updatedBy = api.deviceName, updatedAt = now)
        Kind.SUBSTITUTE -> verdict.substitute!!.let { s ->
            Line(s.ean, s.name.take(120), boxes, "sub", reason = reason, origEan = verdict.item?.ean, origName = verdict.item?.name?.take(120),
                updatedBy = api.deviceName, updatedAt = now)
        }
        Kind.URGENT_ONLY -> Line(verdict.item!!.ean, verdict.item.name.take(120), boxes, "urgent", reason = reason, updatedBy = api.deviceName, updatedAt = now)
        else -> return
    }
    val old = list.line(line.ean)
    change(if (old != null && old.boxes > 0) line.copy(boxes = maxOf(old.boxes, boxes)) else line)
    if (!quiet) toast("In lista: ${line.name.take(30)} · ${line.boxes} ${colli(line.boxes)}")
}
