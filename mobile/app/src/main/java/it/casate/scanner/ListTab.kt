package it.casate.scanner

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Sections folded on this scanner (tap a section title to fold / unfold it). */
private val folded = HashSet<Sections.Kind>()
private var foldLoaded = false

/**
 * Tab 2 · Lista (plan A, 2026-11-27): one long list, edge to edge, grouped by what each line is —
 * «Da controllare» always on top, then Griglia, Non in Griglia, Sostituti, Avvisi (each foldable).
 * One line of header, one button at the bottom: as many lines on the screen as possible.
 */
internal fun MainActivity.listTab(): View {
    if (!foldLoaded) {
        foldLoaded = true
        api.listFolded.split(',').mapNotNull { k -> Sections.Kind.values().firstOrNull { it.name == k } }.let(folded::addAll)
        if (api.listFolded.isEmpty()) folded.add(Sections.Kind.REMIND)
    }
    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)
    val v = list.server
    val sent = list.submitted && !list.changedSinceSubmit

    // one line of header: back · week · articles / boxes / deadline · «n da contare»
    val head = ui.row()
    head.setBackgroundColor(C.NAVY)
    head.setPadding(ui.dp(12), ui.dp(10), ui.dp(14), ui.dp(10))
    val b = ui.icon(Icons.BACK, Color.WHITE, 34, 2.2f).apply {
        setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); background = ui.bg(C.NAVY3, 10); contentDescription = "Indietro"
    }
    ui.tap(b, 10, true) { go(MainActivity.Tab.SCAN) }
    head.addView(b, ui.fixed(34, 34))
    val tc = ui.col()
    tc.addView(ui.text("Lista " + (v?.week?.let { weekNo(it).lowercase() } ?: ""), 17f, Color.WHITE, ui.bold).oneLine())
    val sub = listOfNotNull("${list.ordered.size} articoli", "${list.boxes} colli",
        if (list.urgent > 0) "${list.urgent} non in Griglia" else null,
        v?.let { "chiude " + shortDay(it.deadline) }).joinToString(" · ")
    tc.addView(ui.text(sub, 12f, C.ON_NAVY_MUTED).oneLine(), ui.lp(top = 1))
    head.addView(tc, ui.lp(w = 0, weight = 1f, start = 10))
    if (toCount.size > 0 && !sent) {
        val pill = ui.text("${toCount.size} da contare", 12f, C.YELLOW, ui.bold).apply {
            background = ui.bg(0xFF4A3A10.toInt(), 999); setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
        }
        ui.tap(pill, 999, true) { go(MainActivity.Tab.TASKS) }
        head.addView(pill, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
    }
    root.addView(head)

    val body = ui.col()
    val chk = checks()
    val warn = chk.associateBy { it.line.ean }
    val groups = Sections.group(list.lines(), warn.keys)
    if (groups.isEmpty()) {
        val e = ui.col(28)
        e.gravity = Gravity.CENTER_HORIZONTAL
        e.addView(ui.icon(Icons.BARCODE, C.FAINT, 36, 1.6f), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        e.addView(ui.text("La lista è vuota", 17f, C.INK, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 10))
        e.addView(ui.text(if (v == null) "Lista non ancora scaricata · serve il Wi-Fi" else "Scansiona gli articoli da ordinare", 13f, C.MUTED)
            .apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        ui.tap(e, 18) { go(MainActivity.Tab.SCAN) }
        body.addView(e, ui.lp(top = 30))
    }
    for ((k, lines) in groups) {
        val check = k == Sections.Kind.CHECK
        val h = ui.row()
        h.setBackgroundColor(if (check) 0xFFFFF8E6.toInt() else C.CANVAS)
        h.setPadding(ui.dp(16), ui.dp(9), ui.dp(14), ui.dp(6))
        val boxes = lines.sumOf { it.boxes }
        h.addView(ui.text(k.title.uppercase() + " · ${lines.size}", 12f, if (check) C.AMBER_INK else C.MUTED, ui.bold)
            .apply { letterSpacing = 0.06f }, ui.lp(w = 0, weight = 1f))
        if (boxes > 0) h.addView(ui.text("$boxes ${colli(boxes)}", 12f, C.MUTED))
        val closed = k in folded
        h.addView(ui.icon(if (closed) Icons.NEXT else Icons.DOWN, C.MUTED, 16, 2.2f), ui.fixed(16, 16, 8))
        ui.tap(h, 0) {
            if (!folded.remove(k)) folded.add(k)
            api.listFolded = folded.joinToString(",") { it.name }.ifEmpty { "-" }
            render()
        }
        body.addView(h)
        if (closed) continue
        for (l in lines) {
            body.addView(lineRow(l, warn[l.ean]))
            body.addView(ui.divider(68))
        }
    }
    val last = list.fromLastWeek()
    if (last.isNotEmpty() && !sent) {
        val r = ui.row(Gravity.CENTER)
        r.setPadding(0, ui.dp(14), 0, ui.dp(14))
        r.addView(ui.icon(Icons.REFRESH, C.BLUE, 16, 2f))
        r.addView(ui.text("Riprendi la settimana scorsa · ${last.size}", 14f, C.BLUE, ui.bold), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
        ui.tap(r, 0) {
            AlertDialog.Builder(this).setTitle("Aggiungere ${last.size} articoli?")
                .setMessage("Con gli stessi colli della settimana scorsa (esclusi i non in Griglia).")
                .setPositiveButton("Aggiungi") { _, _ ->
                    val now = System.currentTimeMillis()
                    for (l in last) list.set(l.copy(updatedBy = api.deviceName, updatedAt = now))
                    api.savePending(list); sync(quiet = true); render()
                }.setNegativeButton("Annulla", null).show()
        }
        body.addView(r)
    }
    root.addView(ScrollView(this).apply { addView(body) }, ui.lp(h = 0, weight = 1f))

    // one button: its text says the state of the list
    val bar = ui.col()
    bar.setBackgroundColor(C.CARD)
    bar.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
    when {
        sent && v?.list?.status == "ordered" -> bar.addView(ui.primary("Ordinata dall'ufficio acquisti", C.GREEN, false, Icons.CHECK) {})
        sent -> bar.addView(ui.primary("Inviata " + dayTime(v!!.list!!.submittedAt) + " · modificabile fino a " + shortDay(v.deadline),
            C.GREEN, false, Icons.CHECK) {})
        else -> bar.addView(ui.primary(if (list.submitted) "Invia le modifiche · ${list.boxes} colli" else "Invia la lista · ${list.boxes} colli",
            if (list.submitted) C.AMBER_INK else C.BLUE, list.ordered.isNotEmpty(), Icons.SEND,
            whyDisabled = { toast("La lista è vuota") }) { confirmSend() })
    }
    root.addView(bar)
    return root
}

/** One line: photo, name, what it is (or why to check it), − n + in place. Tap = its card. */
private fun MainActivity.lineRow(l: Line, warn: MainActivity.Check?): View {
    val r = ui.row()
    r.setBackgroundColor(C.CARD)
    r.setPadding(ui.dp(14), ui.dp(8), ui.dp(10), ui.dp(8))
    val left = ui.row()
    left.addView(ui.photo(l.ean, l.name, 42, lineColor(l.kind)))
    val c = ui.col()
    c.addView(ui.text(l.name.ifEmpty { l.ean }, 14f, C.INK, ui.medium).lines(2))
    val adv = if (l.kind == "griglia" || l.kind == "sub") Catalog.advise(catalog?.info(l.ean), packOf(l.ean)).boxes else null
    val (sub, color) = when {
        warn != null -> warn.text to C.AMBER_INK
        l.kind == "sub" -> ("Al posto di " + (l.origName ?: l.origEan ?: "")) to C.BLUE_INK
        l.kind == "urgent" -> ("Non in Griglia · " + (l.reason ?: "")) to C.ORANGE
        l.kind == "remind" -> "Avvisami quando torna" to C.AMBER_INK
        !l.reason.isNullOrEmpty() -> ("Motivo · " + l.reason) to C.MUTED
        adv != null && Feedbacks.overOrder(adv, l.boxes) -> "Consigliati $adv · ne metti ${l.boxes}" to C.AMBER_INK
        else -> (packOf(l.ean)?.let { pk -> "${n(pk)} pz/collo · ${n(pk * l.boxes)} pz" } ?: "${l.boxes} ${colli(l.boxes)}") to C.MUTED
    }
    c.addView(ui.text(sub, 12f, color).oneLine(), ui.lp(top = 3))
    left.addView(c, ui.lp(w = 0, weight = 1f, start = 12))
    ui.tap(left, 12) { openItem(l.origEan ?: l.ean) }
    r.addView(left, ui.lp(w = 0, weight = 1f))
    if (l.kind == "remind") {
        r.addView(ui.iconTap(Icons.CLOSE, C.MUTED, "Togli") { removeLine(l.ean); render() })
        return r
    }
    var nb = l.boxes
    val st = Stepper(ui, { nb }, { nb = it }, 0, 36, 16f, l.name.ifEmpty { l.ean }, onChange = {}, onDone = {
        if (nb == l.boxes) return@Stepper
        if (nb <= 0) {
            AlertDialog.Builder(this).setTitle("Togliere dalla lista?").setMessage(l.name)
                .setPositiveButton("Togli") { _, _ -> removeLine(l.ean); render() }
                .setNegativeButton("Annulla") { _, _ -> render() }.show()
        } else { change(l.copy(boxes = nb, updatedBy = api.deviceName, updatedAt = System.currentTimeMillis())) }
    })
    r.addView(st.view, LinearLayout.LayoutParams(ui.dp(112), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(6) })
    return r
}

private fun MainActivity.confirmSend() {
    val v = list.server
    val chk = checks().size
    val msg = "${list.ordered.size} articoli · ${list.boxes} colli" + (if (list.urgent > 0) " · ${list.urgent} non in Griglia" else "") +
        (if (v != null) "\nModificabile fino a ${shortDay(v.deadline)}." else "") +
        (if (chk > 0) "\n\n$chk da controllare." else "") + (if (toCount.size > 0) "\n${toCount.size} ancora da contare." else "")
    AlertDialog.Builder(this).setTitle("Inviare all'ufficio acquisti?").setMessage(msg)
        .setPositiveButton("Invia") { _, _ -> submit() }.setNegativeButton("Annulla", null).show()
}

private fun MainActivity.submit() {
    val wait = AlertDialog.Builder(this).setMessage("Invio in corso…").setCancelable(false).show()
    io.execute {
        val r = runCatching {
            val sent = list.pending.values.toList()
            if (sent.isNotEmpty()) {
                val v = api.pushLines(sent)
                main.post { list.applied(v, sent); api.savePending(list) }
            }
            api.submitList()
        }
        main.post {
            wait.dismiss()
            val e = r.exceptionOrNull()
            if (e == null) {
                list.server = r.getOrNull()
                offline = false
                beep(Kind.ORDER)
                toast("Lista inviata")
                render()
            } else if (e is ApiError && e.status == 401) unpaired()
            else AlertDialog.Builder(this).setTitle("Non inviata")
                .setMessage(if (e is ApiError) (e.message ?: e.code) else "Nessuna connessione: la lista resta salvata, riprova con il Wi-Fi.")
                .setPositiveButton("OK", null).show()
        }
    }
}
