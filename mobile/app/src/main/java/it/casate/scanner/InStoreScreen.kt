package it.casate.scanner

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * «In negozio» (tap the stock tile on the item's card): this item in this store, to order and to check the stock.
 *  · Giacenza: pieces, how long they last, sold in 30 days, last sale, on the way
 *  · Controlla la giacenza: since the last delivery (arrived · sold since · the system says) → count and report
 *  · Arrivi: deliveries of the last 12 months (date · from whom · pieces; only from 08/01/2025)
 *  · Venduti al mese: the last 12 months
 *  · Altri negozi: how many the other stores have
 */
internal fun MainActivity.showInStore(ean: String, name: String, info: StoreInfo, pack: Double?, back: () -> Unit) {
    countBack = back
    var allArrivals = false
    lateinit var draw: () -> Unit
    draw = {
        val root = ui.col()
        root.setBackgroundColor(C.CANVAS)
        root.addView(header("In negozio · $store", name.ifEmpty { ean }, C.ON_NAVY_MUTED) { countBack = null; back() })
        val body = ui.col()
        body.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(20))

        // stock
        val st = card("GIACENZA", catalog?.dataDate?.takeIf { it.length == 10 }?.let { "dati del ${it.substring(8, 10)}/${it.substring(5, 7)}" })
        val big = ui.row(Gravity.BOTTOM)
        val stock = info.stock ?: 0.0
        big.addView(ui.num(n(stock), 32f, if (stock <= 0) C.RED_INK else C.INK))
        val pk = (info.pack ?: pack)?.takeIf { it > 0 }
        val cover = info.coverDays((info.transitBoxes ?: 0.0) * (pk ?: 0.0))
        big.addView(ui.text(" pz" + (cover?.let { " · bastano ${Catalog.months(it)}" } ?: ""), 14f, C.MUTED), ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, bottom = 4))
        st.addView(big)
        val kv = ui.row()
        fun cell(label: String, value: String, fg: Int = C.INK) {
            val c = ui.col()
            c.background = ui.bg(C.CANVAS, 10)
            c.setPadding(ui.dp(8), ui.dp(6), ui.dp(6), ui.dp(6))
            c.addView(ui.text(label, 11f, C.MUTED).oneLine())
            c.addView(ui.num(value, 15f, fg).oneLine(), ui.lp(top = 2))
            kv.addView(c, ui.lp(w = 0, weight = 1f, start = if (kv.childCount == 0) 0 else 6))
        }
        cell("Venduti 30 gg", n(info.sold30 ?: 0.0))
        cell("Ultima vendita", when (val d = info.lastSaleDays) { null -> "–"; 0 -> "oggi"; 1 -> "ieri"; else -> "$d gg fa" })
        val tb = info.transitBoxes ?: 0.0
        cell("In arrivo", if (tb > 0) "${n(tb)} ${colli(tb.toInt())}" else "–", if (tb > 0) C.BLUE else C.INK)
        st.addView(kv, ui.lp(top = 10))
        body.addView(st)

        // stock check from the last delivery
        val chk = card("CONTROLLA LA GIACENZA", null)
        val since = InStore.since(info)
        val rep = list.report(ean)
        if (since != null) {
            fun line(l: String, v: String, bold: Boolean = false, fg: Int = C.INK) {
                val r = ui.row()
                r.setPadding(0, ui.dp(3), 0, ui.dp(3))
                r.addView(ui.text(l, 14f, if (bold) C.INK else C.MUTED, if (bold) ui.bold else ui.medium), ui.lp(w = 0, weight = 1f))
                r.addView(ui.num(v, 15f, fg))
                chk.addView(r)
            }
            line("Arrivati il ${since.day} · ${since.from}", "+" + n(since.arrived), fg = C.GREEN_INK)
            line("Venduti da allora", "−" + n(since.sold))
            chk.addView(ui.divider(), ui.lp(top = 4))
            line("A sistema adesso", n(since.system) + " pz", bold = true)
            val hint = when {
                since.system > since.arrived - since.sold + 0.5 -> "Prima dell'ultimo arrivo ne avevi già: a sistema sono ${n(since.system)}. Sullo scaffale ne vedi di meno? Contali."
                else -> "Sullo scaffale ne vedi di meno (o di più)? Contali e segnalalo all'ufficio."
            }
            chk.addView(ui.text(hint, 12f, C.MUTED).lines(3), ui.lp(top = 6))
        } else {
            chk.addView(ui.text("Nessun arrivo registrato negli ultimi 12 mesi: a sistema ${n(stock)} pz.", 13f, C.MUTED).lines(2))
        }
        if (rep != null) {
            val t = when (rep.status) {
                "open" -> "Hai segnalato ${rep.counted} pz il ${dayTime(rep.createdAt)} · l'ufficio deve correggere"
                "fixed" -> "Corretto dall'ufficio: ${rep.counted} pz (contati il ${dayTime(rep.createdAt)})"
                else -> "Segnalazione del ${dayTime(rep.createdAt)} non accettata" + (if (rep.answer.isNotEmpty()) ": ${rep.answer}" else "")
            }
            chk.addView(ui.text(t, 12f, if (rep.status == "rejected") C.RED_INK else C.BLUE_INK, ui.bold).lines(3), ui.lp(top = 6))
        }
        if (rep?.status != "open") {
            val b = ui.outline("Conta e segnala la giacenza", C.BLUE_INK, 0xFFC6D5FF.toInt()) {
                showCount(ean, name, info, after = { c -> toast("Giacenza segnalata: $c pz"); showInStore(ean, name, info, pack, back) },
                    back = { showInStore(ean, name, info, pack, back) })
            }
            chk.addView(b, ui.lp(top = 10, h = ui.dp(44)))
        }
        body.addView(chk, ui.lp(top = 10))

        // deliveries
        val arr = card("ARRIVI", "ultimi 12 mesi")
        if (info.arrivals.isEmpty()) arr.addView(ui.text("Nessun arrivo registrato", 13f, C.MUTED))
        val shown = if (allArrivals) info.arrivals else info.arrivals.take(3)
        for ((i, a) in shown.withIndex()) {
            if (i > 0) arr.addView(ui.divider())
            val r = ui.row()
            r.setPadding(0, ui.dp(8), 0, ui.dp(8))
            r.addView(ui.num(a.dayText, 13f), ui.lp(w = ui.dp(70)))
            val c = ui.col()
            c.addView(ui.text(a.from.ifEmpty { "–" }, 13f, C.INK, ui.medium).oneLine())
            c.addView(ui.text(if (a.transfer) "Trasferimento" else "Fornitore", 11f, C.MUTED).oneLine())
            r.addView(c, ui.lp(w = 0, weight = 1f))
            val boxes = pk?.let { if (a.qty >= it && a.qty % it == 0.0) " · ${n(a.qty / it)} ${colli((a.qty / it).toInt())}" else null } ?: ""
            r.addView(ui.num("+" + n(a.qty) + " pz", 14f, C.GREEN_INK))
            if (boxes.isNotEmpty()) r.addView(ui.text(boxes, 11f, C.MUTED))
            arr.addView(r)
        }
        if (info.arrivals.size > 3) {
            val more = ui.text(if (allArrivals) "Mostra meno" else "Tutti gli arrivi (${info.arrivals.size}) ›", 13f, C.BLUE, ui.bold)
            more.setPadding(0, ui.dp(8), 0, ui.dp(2))
            ui.tap(more, 8) { allArrivals = !allArrivals; draw() }
            arr.addView(more)
        }
        arr.addView(ui.text("Registro arrivi dal 08/01/2025", 11f, C.FAINT), ui.lp(top = 6))
        body.addView(arr, ui.lp(top = 10))

        // monthly sales
        val months = info.months
        if (months != null && months.size == 12) {
            val avg = InStore.monthAvg(months)
            val mc = card("VENDUTI AL MESE", avg?.let { "media ${n(Math.round(it).toDouble())} pz" })
            mc.addView(monthBars(months), ui.lp(top = 4))
            body.addView(mc, ui.lp(top = 10))
        }

        // other stores
        if (info.others.isNotEmpty()) {
            val oc = card("ALTRI NEGOZI", null)
            for (o in info.others.sortedByDescending { it.second }) {
                val r = ui.row()
                r.setPadding(0, ui.dp(5), 0, ui.dp(5))
                r.addView(ui.text(o.first, 14f, C.INK, ui.medium), ui.lp(w = 0, weight = 1f))
                r.addView(ui.num(n(o.second) + " pz", 14f, if (o.second > 0) C.INK else C.FAINT))
                oc.addView(r)
            }
            body.addView(oc, ui.lp(top = 10))
        }
        root.addView(ScrollView(this).apply { addView(body) }, ui.lp(h = 0, weight = 1f))
        showFull(root, dark = true)
    }
    draw()
}

private fun MainActivity.card(title: String, right: String?): LinearLayout {
    val c = ui.card(16)
    c.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
    val h = ui.row()
    h.addView(ui.caption(title), ui.lp(w = 0, weight = 1f))
    if (right != null) h.addView(ui.text(right, 11f, C.MUTED))
    c.addView(h, ui.lp(bottom = 8))
    return c
}

/** 12 columns, the height = pieces sold; the number on top; this month in blue (not finished). */
private fun MainActivity.monthBars(months: List<Double>): View {
    val max = months.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val letters = "GFMAMGLASOND"
    val now = java.time.LocalDate.now().monthValue
    val r = ui.row(Gravity.BOTTOM)
    for ((i, v) in months.withIndex()) {
        val col = ui.col()
        col.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        col.addView(ui.num(if (v > 0) n(v) else "", 9f, C.MUTED).apply { gravity = Gravity.CENTER })
        val h = (v / max * 64).toInt().coerceAtLeast(if (v > 0) 3 else 1)
        val bar = View(this).apply { background = ui.bg(if (i == months.size - 1) C.BLUE else 0xFFC9D7FF.toInt(), 3) }
        col.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(h)).apply { topMargin = ui.dp(2) })
        val m = ((now - 1 - (months.size - 1 - i)) % 12 + 12) % 12
        col.addView(ui.text(letters[m].toString(), 10f, C.FAINT).apply { gravity = Gravity.CENTER }, ui.lp(top = 3))
        r.addView(col, LinearLayout.LayoutParams(0, ui.dp(96), 1f).apply { marginStart = ui.dp(if (i == 0) 0 else 3) })
    }
    return FrameLayout(this).apply { addView(r) }
}
