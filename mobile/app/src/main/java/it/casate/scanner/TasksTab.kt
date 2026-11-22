package it.casate.scanner

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Tab 3 · Da fare: only what needs the store's hands, most urgent first — stock to count, lines to check,
 * items back in stock, the stock reports and what the office did last week. Empty = "Tutto fatto".
 */
internal fun MainActivity.tasksTab(): View {
    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)
    val n = todoCount()
    root.addView(header("Da fare", if (n == 0) "Niente in sospeso" else "$n da fare", C.ON_NAVY_MUTED) { go(MainActivity.Tab.SCAN) })
    val body = ui.col()
    body.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(20))

    fun section(title: String, count: Int, color: Int, fill: (LinearLayout) -> Unit) {
        val h = ui.row()
        h.addView(ui.caption(title, color), ui.lp(w = 0, weight = 1f))
        if (count > 0) h.addView(ui.num("$count", 13f, color))
        body.addView(h, ui.lp(top = if (body.childCount == 0) 4 else 20))
        val card = ui.card(20)
        card.setPadding(ui.dp(12), ui.dp(2), ui.dp(10), ui.dp(2))
        fill(card)
        body.addView(card, ui.lp(top = 8))
    }

    update?.let { u ->
        section("Aggiornamento app", 0, C.BLUE_INK) { c ->
            c.addView(ui.listRow(ui.iconBadge(Icons.REFRESH, C.BLUE_SOFT, C.BLUE_INK, 40, 20, 2.2f), "Versione ${u.versionName}",
                "Un minuto · lista e collegamento restano", C.MUTED, ui.outline("Aggiorna", C.BLUE_INK, 0xFFC6D5FF.toInt()) { startUpdate(u) }))
        }
    }

    val items = toCount.all()
    if (items.isNotEmpty()) section("Da contare", items.size, C.AMBER_INK) { c ->
        for ((i, it) in items.withIndex()) {
            if (i > 0) c.addView(ui.divider(52))
            val sys = catalog?.info(it.ean)
            val trail = ui.row()
            trail.addView(ui.iconTap(Icons.CLOSE, C.FAINT, "Togli") { toCount.remove(it.ean); saveToCount(); render() })
            if (sys != null) trail.addView(ui.outline("Conta", C.AMBER_INK, C.AMBER) {
                showCount(it.ean, it.name, sys, after = { counted -> render(); toast("Segnalato: $counted pz") }, back = { render() })
            })
            c.addView(ui.listRow(ui.photo(it.ean, it.name, 40, C.AMBER_INK), it.name.ifEmpty { it.ean },
                if (sys != null) "A sistema ${n(sys.stock)} pz" else "Dati del negozio non pubblicati", C.MUTED, trail))
        }
    }

    val chk = checks()
    if (chk.isNotEmpty()) section("Da controllare prima di inviare", chk.size, C.AMBER_INK) { c ->
        for ((i, k) in chk.withIndex()) {
            if (i > 0) c.addView(ui.divider(52))
            val fg = when (k.level) { 1 -> C.BLUE_INK; 2 -> C.RED_INK; else -> C.AMBER_INK }
            c.addView(ui.listRow(ui.photo(k.line.ean, k.line.name, 40, fg), k.line.name, k.text, fg,
                ui.num("${k.line.boxes}", 15f)) { openItem(k.line.origEan ?: k.line.ean) })
        }
    }

    val back = backInStock()
    if (back.isNotEmpty()) section("Di nuovo disponibili", back.size, C.GREEN_INK) { c ->
        for ((i, l) in back.withIndex()) {
            if (i > 0) c.addView(ui.divider(52))
            c.addView(ui.listRow(ui.photo(l.ean, l.name, 40, C.GREEN), l.name, "L'avevi chiesto: ora si può ordinare", C.GREEN_INK,
                ui.icon(Icons.NEXT, C.FAINT, 18, 2.2f)) { openItem(l.ean) })
        }
    }

    val reps = list.server?.stockReports.orEmpty().sortedByDescending { it.createdAt }.take(12)
    if (reps.isNotEmpty()) section("Giacenze segnalate", reps.count { it.status == "open" }, C.BLUE_INK) { c ->
        for ((i, r) in reps.withIndex()) {
            if (i > 0) c.addView(ui.divider(52))
            val (lbl, fg, fill) = when (r.status) {
                "fixed" -> Triple("CORRETTA", C.GREEN_INK, C.GREEN_SOFT)
                "rejected" -> Triple("RESPINTA", C.RED_INK, C.RED_SOFT)
                else -> Triple("IN ATTESA", C.BLUE_INK, C.BLUE_SOFT)
            }
            c.addView(ui.listRow(ui.photo(r.ean, r.name, 40, fg), r.name.ifEmpty { r.ean },
                "${n(r.systemQty)} → ${r.counted} pz · " + dayTime(r.createdAt) + (if (r.answer.isNotEmpty()) " · ${r.answer}" else ""),
                C.MUTED, ui.chip(lbl, fg, fill, 10f)) { openItem(r.ean) })
        }
    }

    val prev = list.server?.previous
    if (prev != null && prev.lines.any { it.boxes > 0 }) section("Settimana scorsa · " + weekNo(prev.week), 0, C.MUTED) { c ->
        val s = Feedbacks.summary(prev)
        if (s == null) {
            c.addView(ui.text(if (prev.receivedAt > 0) "L'ufficio acquisti la sta ordinando" else "In attesa dell'ufficio acquisti", 14f, C.INK)
                .apply { setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(14)) })
        } else {
            val r = ui.row()
            r.setPadding(0, ui.dp(12), 0, ui.dp(8))
            for ((i, x) in listOf(Triple(s.ordered, "ordinati", C.GREEN_INK), Triple(s.reduced, "ridotti", C.AMBER_INK),
                Triple(s.notOrdered, "non ordinati", C.RED_INK), Triple(s.substituted, "sostituiti", C.BLUE_INK)).withIndex()) {
                val t = ui.col()
                t.gravity = Gravity.CENTER_HORIZONTAL
                t.addView(ui.num("${x.first}", 20f, x.third))
                t.addView(ui.text(x.second, 11f, C.MUTED).oneLine(), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, top = 2))
                r.addView(t, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 4))
            }
            c.addView(r)
            val fb = prev.feedback!!
            val changed = prev.lines.filter { it.boxes > 0 }.filter { l -> (fb.boxes[l.ean] ?: l.boxes) < l.boxes || fb.notes.containsKey(l.ean) }.take(15)
            for (l in changed) {
                c.addView(ui.divider(52))
                val got = fb.boxes[l.ean]
                val fg = if (got != null && got <= 0) C.RED_INK else C.AMBER_INK
                c.addView(ui.listRow(ui.photo(l.ean, l.name, 40, fg), l.name, fb.notes[l.ean] ?: "Chiesti ${l.boxes}", C.MUTED,
                    ui.num("${l.boxes} → ${got ?: l.boxes}", 14f, fg)) { openItem(l.ean) })
            }
            for (d in fb.deliveries) {
                c.addView(ui.divider())
                c.addView(ui.listRow(ui.iconBadge(Icons.TRUCK, 0xFFF1F4F8.toInt(), C.INK, 40, 20, 2f), d.first, "Scarico ${d.second}"))
            }
        }
    }

    if (body.childCount == 0) {
        val e = ui.col(32)
        e.gravity = Gravity.CENTER_HORIZONTAL
        e.addView(ui.iconBadge(Icons.CHECK, C.GREEN_SOFT, C.GREEN_INK, 72, 36, 2.6f), LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        e.addView(ui.text("Tutto fatto", 20f, C.INK, ui.bold).apply { gravity = Gravity.CENTER }, ui.lp(top = 14))
        e.addView(ui.text("Qui compare quello che serve contare o controllare", 13f, C.MUTED).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        body.addView(e, ui.lp(top = 40))
    }
    root.addView(ScrollView(this).apply { addView(body) }, ui.lp(h = 0, weight = 1f))
    return root
}
