package it.casate.scanner

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Filter of the List tab (chips under the title). */
internal enum class ListFilter(val label: String) { ALL("Tutti"), CHECK("Da controllare"), URGENT("Urgenze"), SUB("Sostituti"), REMIND("Avvisi") }

private var filter = ListFilter.ALL

/**
 * Tab 2 · Lista: everything that will be sent, changeable in place (− n +), the few lines worth a second
 * look marked in amber, and one button to send. After sending: the state of the list in the same place.
 */
internal fun MainActivity.listTab(): View {
    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)
    val v = list.server
    val sent = list.submitted && !list.changedSinceSubmit
    val head = header("Lista " + (v?.week?.let { weekNo(it).lowercase() } ?: ""),
        v?.let { "chiude tra " + countdown(it.deadline) + " · " + shortDay(it.deadline) }, C.YELLOW) { go(MainActivity.Tab.SCAN) }
    val stats = ui.row()
    for ((i, s3) in listOf(
        Triple("${list.ordered.size}", "articoli", android.graphics.Color.WHITE),
        Triple("${list.boxes}", "colli", android.graphics.Color.WHITE),
        Triple("${list.urgent}", if (list.urgent == 1) "urgenza" else "urgenze", if (list.urgent > 0) C.CORAL else android.graphics.Color.WHITE),
    ).withIndex()) {
        val c = ui.col()
        c.background = ui.bg(C.NAVY2, 12)
        c.setPadding(ui.dp(10), ui.dp(8), ui.dp(8), ui.dp(8))
        c.addView(ui.num(s3.first, 20f, s3.third))
        c.addView(ui.text(s3.second, 11f, C.ON_NAVY_MUTED), ui.lp(top = 2))
        stats.addView(c, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 6))
    }
    head.addView(stats, ui.lp(top = 12))
    root.addView(head)

    val body = ui.col()
    body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
    val stateLine = when {
        v == null -> Triple("Lista non ancora scaricata · serve il Wi-Fi", C.MUTED, null)
        sent && v.list?.status == "ordered" -> Triple("Ordinata dall'ufficio acquisti", C.GREEN_INK, Icons.CHECK)
        sent -> Triple("Inviata " + dayTime(v.list!!.submittedAt) + " · modificabile fino a " + shortDay(v.deadline), C.GREEN_INK, Icons.CHECK)
        list.submitted -> Triple("Modificata dopo l'invio: inviala di nuovo", C.AMBER_INK, Icons.ALERT)
        else -> null
    }
    if (stateLine != null) {
        val sl = ui.row()
        sl.background = ui.bg(if (stateLine.second == C.GREEN_INK) C.GREEN_SOFT else if (stateLine.second == C.AMBER_INK) C.AMBER_SOFT else 0xFFEEF1F6.toInt(), 12)
        sl.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        stateLine.third?.let { sl.addView(ui.icon(it, stateLine.second, 16, 2.4f)) }
        sl.addView(ui.text(stateLine.first, 13f, stateLine.second, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f, start = if (stateLine.third != null) 8 else 0))
        body.addView(sl, ui.lp(bottom = 12))
    }

    // filters
    val chk = checks()
    val all = list.lines()
    val counts = mapOf(
        ListFilter.ALL to list.ordered.size,
        ListFilter.CHECK to chk.size,
        ListFilter.URGENT to all.count { it.boxes > 0 && it.kind == "urgent" },
        ListFilter.SUB to all.count { it.boxes > 0 && it.kind == "sub" },
        ListFilter.REMIND to all.count { it.kind == "remind" },
    )
    if (counts[filter] == 0) filter = ListFilter.ALL
    val chips = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    val cr = ui.row()
    for (f in ListFilter.values()) {
        val n = counts[f] ?: 0
        if (f != ListFilter.ALL && n == 0) continue
        cr.addView(ui.choice("${f.label} $n", f == filter, if (f == ListFilter.CHECK) C.AMBER_INK else C.NAVY) { filter = f; render() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
    }
    chips.addView(cr)
    body.addView(chips)

    val warn = chk.associateBy { it.line.ean }
    val rows = when (filter) {
        ListFilter.ALL -> all.filter { it.boxes > 0 } + all.filter { it.kind == "remind" }
        ListFilter.CHECK -> chk.map { it.line }
        ListFilter.URGENT -> all.filter { it.boxes > 0 && it.kind == "urgent" }
        ListFilter.SUB -> all.filter { it.boxes > 0 && it.kind == "sub" }
        ListFilter.REMIND -> all.filter { it.kind == "remind" }
    }
    if (rows.isEmpty()) {
        val e = ui.col(28)
        e.gravity = Gravity.CENTER_HORIZONTAL
        e.background = ui.dashed(0xFFC4CCDA.toInt(), C.CARD, 18)
        e.addView(ui.icon(Icons.BARCODE, C.FAINT, 36, 1.6f), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        e.addView(ui.text("La lista è vuota", 17f, C.INK, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 10))
        e.addView(ui.text("Scansiona gli articoli da ordinare", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        ui.tap(e, 18) { go(MainActivity.Tab.SCAN) }
        body.addView(e, ui.lp(top = 12))
    } else {
        val card = ui.card(20)
        card.setPadding(ui.dp(12), ui.dp(2), ui.dp(8), ui.dp(2))
        for ((i, l) in rows.withIndex()) {
            if (i > 0) card.addView(ui.divider(52))
            card.addView(lineRow(l, warn[l.ean]))
        }
        body.addView(card, ui.lp(top = 12))
    }
    val last = list.fromLastWeek()
    if (last.isNotEmpty() && filter == ListFilter.ALL && !sent) {
        val b = ui.row(Gravity.CENTER)
        b.background = ui.dashed(0xFFC4CCDA.toInt(), C.CARD, 14)
        b.addView(ui.icon(Icons.REFRESH, C.INK, 16, 2f))
        b.addView(ui.text("Riprendi la settimana scorsa · ${last.size}", 14f, C.INK, ui.medium), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
        ui.tap(b, 14) {
            AlertDialog.Builder(this).setTitle("Aggiungere ${last.size} articoli?")
                .setMessage("Con gli stessi colli della settimana scorsa (urgenze escluse).")
                .setPositiveButton("Aggiungi") { _, _ ->
                    val now = System.currentTimeMillis()
                    for (l in last) list.set(l.copy(updatedBy = api.deviceName, updatedAt = now))
                    api.savePending(list); sync(quiet = true); render()
                }.setNegativeButton("Annulla", null).show()
        }
        body.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)).apply { topMargin = ui.dp(12) })
    }
    root.addView(ScrollView(this).apply { addView(body) }, ui.lp(h = 0, weight = 1f))

    // send
    val bar = ui.col()
    bar.setBackgroundColor(C.CARD)
    bar.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(16))
    if (toCount.size > 0 && !sent) {
        val w = ui.row()
        w.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8))
        w.addView(ui.icon(Icons.ALERT, C.AMBER_INK, 14, 2.4f))
        w.addView(ui.text("${toCount.size} da contare prima di inviare", 12f, C.AMBER_INK, ui.bold).oneLine(), ui.lp(w = 0, weight = 1f, start = 6))
        ui.tap(w, 8) { go(MainActivity.Tab.TASKS) }
        bar.addView(w)
    }
    when {
        sent -> bar.addView(ui.primary("Inviata", C.GREEN, false, Icons.CHECK) {})
        else -> bar.addView(ui.primary(if (list.submitted) "Invia le modifiche" else "Invia la lista · ${list.boxes} colli", C.BLUE, list.ordered.isNotEmpty(), Icons.SEND,
            whyDisabled = { toast("La lista è vuota") }) { confirmSend() })
    }
    if (v != null && !sent) bar.addView(ui.text("Dopo l'invio puoi ancora modificarla fino a " + shortDay(v.deadline), 12f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 8))
    root.addView(bar)
    return root
}

/** One line: photo, name, what it is (or why to check it), − n + in place. Tap = its card. */
private fun MainActivity.lineRow(l: Line, warn: MainActivity.Check?): View {
    val r = ui.row()
    r.setPadding(0, ui.dp(10), 0, ui.dp(10))
    val left = ui.row()
    left.addView(ui.photo(l.ean, l.name, 44, lineColor(l.kind)))
    val c = ui.col()
    c.addView(ui.text(l.name.ifEmpty { l.ean }, 14f, C.INK, ui.medium).lines(2))
    val adv = if (l.kind == "griglia" || l.kind == "sub") Catalog.advise(catalog?.info(l.ean), packOf(l.ean)).boxes else null
    val (sub, color) = when {
        warn != null -> warn.text to C.AMBER_INK
        l.kind == "sub" -> ("Al posto di " + (l.origName ?: l.origEan ?: "")) to C.BLUE_INK
        l.kind == "urgent" -> ("Urgenza · " + (l.reason ?: "")) to C.ORANGE
        l.kind == "remind" -> "Avvisami quando torna" to C.AMBER_INK
        !l.reason.isNullOrEmpty() -> ("Motivo · " + l.reason) to C.MUTED
        adv != null -> "Consigliato $adv" to (if (Feedbacks.overOrder(adv, l.boxes)) C.AMBER_INK else C.MUTED)
        else -> colli(l.boxes) to C.MUTED
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
    val st = Stepper(ui, { nb }, { nb = it }, 0, 40, 17f, l.name.ifEmpty { l.ean }, onChange = {}, onDone = {
        if (nb == l.boxes) return@Stepper
        if (nb <= 0) {
            AlertDialog.Builder(this).setTitle("Togliere dalla lista?").setMessage(l.name)
                .setPositiveButton("Togli") { _, _ -> removeLine(l.ean); render() }
                .setNegativeButton("Annulla") { _, _ -> render() }.show()
        } else { change(l.copy(boxes = nb, updatedBy = api.deviceName, updatedAt = System.currentTimeMillis())) }
    })
    r.addView(st.view, LinearLayout.LayoutParams(ui.dp(124), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(6) })
    return r
}

private fun MainActivity.confirmSend() {
    val v = list.server
    val chk = checks().size
    val msg = "${list.ordered.size} articoli · ${list.boxes} colli" + (if (list.urgent > 0) " · ${list.urgent} urgenze" else "") +
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
