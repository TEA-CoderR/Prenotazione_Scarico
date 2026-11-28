package it.casate.scanner

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * «Novità e guida»: after an update the store sees, once, what changed and how to use it — a picture of the real
 * screen with numbered markers (drawn by mobile/guide/screens.py) and one short line per number.
 * Always reachable again from the menu («Novità e guida»).
 * New release: add a Release at the top of RELEASES (new id) → every scanner shows it once after updating.
 */
object Guide {
    data class Page(val image: String, val title: String, val points: List<String>)
    data class Release(val id: String, val title: String, val pages: List<Page>)

    val RELEASES = listOf(
        Release("2026-11-28", "Aggiornamento di novembre", listOf(
            Page("guide_search", "Cerca per nome", listOf(
                "Scrivi il nome (anche solo una parte) e premi «Cerca». Il codice a barre funziona come sempre.",
                "Ogni risultato mostra il nome intero e se si può ordinare. Tocca la riga per aprire l'articolo.")),
            Page("guide_list", "La lista è più comoda", listOf(
                "In alto ci sono sempre gli articoli da controllare (troppi colli, già in arrivo…).",
                "Gli articoli sono divisi in gruppi: tocca il titolo di un gruppo per chiuderlo o aprirlo.",
                "Un solo tasto in basso: dice anche se la lista è già inviata.")),
            Page("guide_ask", "Chiedi all'ufficio", listOf(
                "Frase pronta: toccala per scriverla, poi aggiungi quello che serve.",
                "Scrivi la domanda: niente più motivi da scegliere.",
                "Se servono, aggiungi i colli o una foto, poi «Invia».")),
            Page("guide_requests", "Le risposte dell'ufficio", listOf(
                "In «Richieste all'ufficio» tocca una riga: si apre la conversazione, rispondi da lì. Tocca l'articolo in alto per aprire la sua scheda.",
                "Dalla home, «Richieste all'ufficio» diventa gialla quando c'è una risposta da leggere.")),
            Page("guide_instore", "In negozio", listOf(
                "La giacenza di oggi. Ci arrivi toccando i riquadri della giacenza sulla scheda dell'articolo.",
                "Controlla la giacenza: arrivati, venduti da allora e quanti ne dice il sistema. Se non torna, contali e segnala.",
                "Tutti gli arrivi degli ultimi 12 mesi: data, da chi e quanti pezzi.",
                "Quanti pezzi si vendono ogni mese: utile per capire quanto ordinare.")),
            Page("guide_substitute", "Articolo sostituito", listOf(
                "Ora vedi anche codice, giacenza e vendite del vecchio articolo, senza cercarlo. Toccalo per i dettagli.",
                "Sotto c'è l'articolo da ordinare al suo posto.")),
            Page("guide_volantino", "Prossimo VOLANTINO", listOf(
                "Dalla home apri il volantino: «Da preparare» sono gli articoli per cui ti manca merce.",
                "Il sistema calcola i colli dai volantini passati. «Aggiungi» li mette nella lista DETERSIVI.",
                "Puoi aggiungere tutti i consigliati in una volta. Se l'ufficio non l'ha ancora caricato, la home lo dice.")),
        )),
    )

    val latest get() = RELEASES.first()

    /** The guide is on screen: a scan closes it (marked as seen) and is handled normally. */
    var open = false
}

/** Shows the guide of the newest release once (after an update or the first pairing). */
internal fun MainActivity.maybeShowGuide() {
    if (!api.paired || api.guideSeen == Guide.latest.id || fullScreen || sheet.isOpen) return
    showGuide(Guide.latest)
}

internal fun MainActivity.showGuide(r: Guide.Release, start: Int = 0) {
    countBack = { api.guideSeen = r.id; render() }
    val i = start.coerceIn(0, r.pages.size - 1)
    val pg = r.pages[i]
    val root = ui.col()
    root.setBackgroundColor(C.CANVAS)
    root.addView(header("Novità · " + r.title, "${i + 1} di ${r.pages.size} · ${pg.title}", C.YELLOW) {
        api.guideSeen = r.id; countBack = null; render()
    })

    val body = ui.col()
    body.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
    // the picture of the screen (as big as it fits)
    val img = ImageView(this).apply {
        val id = resources.getIdentifier(pg.image, "drawable", packageName)
        if (id != 0) setImageResource(id)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        background = ui.bg(C.CARD, 18, C.LINE)
        setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        contentDescription = pg.title
    }
    val h = (resources.displayMetrics.heightPixels * 0.48).toInt()
    body.addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h))
    body.addView(ui.text(pg.title, 19f, C.INK, ui.bold), ui.lp(top = 14))
    for ((n, t) in pg.points.withIndex()) {
        val row = ui.row(Gravity.TOP)
        val dot = ui.text("${n + 1}", 13f, C.INK, ui.bold).apply {
            gravity = Gravity.CENTER
            background = ui.bg(C.AMBER, 999)
        }
        row.addView(dot, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        row.addView(ui.text(t, 14f, C.INK), ui.lp(w = 0, weight = 1f, start = 10))
        body.addView(row, ui.lp(top = 10))
    }
    root.addView(ScrollView(this).apply { addView(body) }, ui.lp(h = 0, weight = 1f))

    // page dots + Back / Next
    val bar = ui.col()
    bar.setBackgroundColor(C.CARD)
    bar.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
    val dots = ui.row(Gravity.CENTER)
    for (k in r.pages.indices) dots.addView(View(this).apply { background = ui.bg(if (k == i) C.BLUE else C.LINE2, 999) },
        LinearLayout.LayoutParams(ui.dp(if (k == i) 18 else 7), ui.dp(7)).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
    bar.addView(dots, ui.lp(bottom = 10))
    val btns = ui.row()
    if (i > 0) btns.addView(ui.button("Indietro", 0, C.INK) { showGuide(r, i - 1) }, ui.lp(w = 0, weight = 1f, h = ui.dp(50)))
    val last = i == r.pages.size - 1
    btns.addView(ui.iconButton(if (last) "Ho capito" else "Avanti", if (last) Icons.CHECK else Icons.NEXT, if (last) C.GREEN else C.BLUE, after = !last) {
        if (last) { api.guideSeen = r.id; countBack = null; render(); toast("Trovi la guida nel menu: «Novità e guida»") }
        else showGuide(r, i + 1)
    }, ui.lp(w = 0, h = ui.dp(50), weight = if (i > 0) 1.4f else 1f, start = if (i > 0) 8 else 0))
    bar.addView(btns)
    root.addView(bar)
    showFull(root, dark = true)
    Guide.open = true
}
