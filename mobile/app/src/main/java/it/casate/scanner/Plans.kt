package it.casate.scanner

import kotlin.math.ceil

/*
 * Pure logic of the store tools (no Android classes, unit-tested):
 *  · Phrases   — quick sentences to ask the office, learned from what this scanner writes most
 *  · Volantino — the next VOLANTINO: what the store still needs, ready to add to the DETERSIVO list
 *  · Sections  — the list page grouped by what each line is
 *  · InStore   — the «In negozio» page: stock check from the last delivery
 */

object Phrases {
    /** Shown when nothing better is known yet: what the stores ask about the items they cannot order. */
    val DEFAULTS = listOf("C'è un articolo che lo sostituisce o tolgo questo prodotto dallo scaffale?")
    const val SHOWN = 6
    private val SPLIT = Regex("[.;!?\\n]+|,\\s+")

    /** The sentences of a message worth remembering (3–60 characters). */
    fun pieces(text: String): List<String> =
        text.split(SPLIT).map { it.trim().trimEnd(',', ':').replace(Regex("\\s+"), " ") }.filter { it.length in 3..60 }

    private fun key(s: String) = s.lowercase()

    /** Remembers a sent message: each sentence counts one more use. `stored` = sentence → uses. */
    fun learn(stored: Map<String, Int>, text: String): Map<String, Int> {
        val out = LinkedHashMap(stored)
        for (p in pieces(text).distinctBy(::key)) {
            val k = out.keys.firstOrNull { key(it) == key(p) } ?: p
            out[k] = (out[k] ?: 0) + 1
        }
        // keep it small: the 60 most used
        return out.entries.sortedByDescending { it.value }.take(60).associate { it.key to it.value }
    }

    /**
     * What to show: what this scanner wrote at least twice (most used first), then what all the stores write most
     * to the office (`common`, published with the catalog), then the defaults.
     */
    fun shown(stored: Map<String, Int>, common: List<String> = emptyList()): List<String> {
        // 28/11: only the one sentence for now (what this scanner / the stores write is not shown)
        if (ONLY_DEFAULTS) return DEFAULTS
        val mine = stored.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.map { it.key }
        val out = ArrayList<String>()
        for (p in mine + common + DEFAULTS) if (out.none { key(it) == key(p) }) out.add(p)
        return out.take(SHOWN)
    }

    /** true = show just DEFAULTS (the office asked to keep a single quick sentence). */
    var ONLY_DEFAULTS = true

    /** Tapping a sentence: added at the end of what is written. */
    fun append(text: String, phrase: String): String {
        val t = text.trimEnd()
        if (t.isEmpty()) return phrase
        if (key(t).contains(key(phrase))) return text
        return if (t.endsWith(".") || t.endsWith(",")) "$t $phrase" else "$t, ${phrase.replaceFirstChar { it.lowercase() }}"
    }
}

object Volantino {
    enum class Filter(val label: String) { TODO("Da preparare"), COVERED("Coperti"), DONE("In lista") }

    /** Boxes still to order: (pieces it should sell − stock − on the way) ÷ pieces per box, rounded up; null = no pack. */
    fun boxes(v: VolItem, info: StoreInfo, pack: Double?): Int? {
        val pk = (info.pack ?: pack)?.takeIf { it > 0 } ?: return null
        val have = (info.stock ?: 0.0).coerceAtLeast(0.0) + (info.transitBoxes ?: 0.0) * pk
        val need = v.expected - have
        return if (need <= 0.0) 0 else ceil(need / pk - 1e-9).toInt()
    }

    data class Row(val ean: String, val info: StoreInfo, val vol: VolItem, val boxes: Int?, val inList: Line?) {
        val filter get() = when {
            inList != null && inList.boxes > 0 -> Filter.DONE
            (boxes ?: 0) > 0 -> Filter.TODO
            else -> Filter.COVERED
        }
    }

    fun rows(items: List<Pair<String, StoreInfo>>, line: (String) -> Line?, pack: (String) -> Double?): List<Row> =
        items.mapNotNull { (e, i) -> i.vol?.let { Row(e, i, it, boxes(it, i, pack(e)), line(e)) } }

    fun page(rows: List<Row>, f: Filter, name: (String) -> String): List<Row> =
        rows.filter { it.filter == f }.sortedWith(compareBy<Row>({ -(it.boxes ?: 0) }, { -it.vol.expected }, { name(it.ean) }))

    fun counts(rows: List<Row>): Map<Filter, Int> = Filter.values().associateWith { f -> rows.count { it.filter == f } }

    /** Reason written on the list line: the purchasing program recognises it (no "too many", transit already counted). */
    fun reason(v: VolantinoInfo): String = "VOLANTINO ${dm(v.start)}-${dm(v.end)} · IN ARRIVO DEDOTTO"

    fun dm(iso: String) = if (iso.length >= 10) "${iso.substring(8, 10)}/${iso.substring(5, 7)}" else iso

    /** Days from `today` (yyyy-mm-dd) to the start (negative = already started). */
    fun daysTo(start: String, today: java.time.LocalDate): Long? =
        runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(start.take(10))) }.getOrNull()

    /** Can «Aggiungi tutti» add this row without someone looking at it? (flagged by the office's checks: no) */
    fun bulk(r: Row) = r.filter == Filter.TODO && !r.vol.warn && (r.boxes ?: 0) in 1..20
}

object Sections {
    /** The list page, top to bottom: what needs a look first, then the Griglia, the rest last. */
    enum class Kind(val title: String, val folded: Boolean) {
        CHECK("Da controllare", false), GRIGLIA("Griglia", false), URGENT("Non in Griglia", false),
        SUB("Sostituti", false), REMIND("Avvisi", true)
    }

    fun kindOf(l: Line, check: Boolean): Kind = when {
        check -> Kind.CHECK
        l.kind == "remind" -> Kind.REMIND
        l.kind == "urgent" -> Kind.URGENT
        l.kind == "sub" -> Kind.SUB
        else -> Kind.GRIGLIA
    }

    /** Lines grouped (sections without lines left out), each section A→Z. */
    fun group(lines: List<Line>, checked: Set<String>): List<Pair<Kind, List<Line>>> {
        val by = lines.filter { it.boxes > 0 || it.kind == "remind" }.groupBy { kindOf(it, it.ean in checked) }
        return Kind.values().mapNotNull { k -> by[k]?.let { k to it.sortedBy { l -> l.name.ifEmpty { l.ean } } } }
    }
}

object InStore {
    /** «Since the last delivery»: arrived n, sold m since then, the system says s. null = no delivery known. */
    data class Since(val day: String, val arrived: Double, val sold: Double, val system: Double, val from: String)

    fun since(info: StoreInfo): Since? {
        val a = info.arrivals.firstOrNull() ?: return null
        return Since(a.dayText, a.qty, a.soldSince ?: 0.0, info.stock ?: 0.0, a.from)
    }

    /** Average pieces a month over the months with data (this month left out: not finished). */
    fun monthAvg(months: List<Double>?): Double? {
        val m = months?.dropLast(1)?.takeIf { it.isNotEmpty() } ?: return null
        return m.sum() / m.size
    }
}
