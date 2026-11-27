package it.casate.scanner

/**
 * This week's DETERSIVO list of the store, shared by all the store's scanners and kept on the server
 * (/api/scanner/list). Changes made here are applied at once (offline too) and queued until the
 * server has them. Pure Kotlin so it can be unit-tested.
 */
data class Line(
    val ean: String,
    val name: String,
    val boxes: Int,
    /** griglia · sub (substitute of origEan) · urgent (not in Griglia, from the wholesaler, with reason) · remind (0 boxes) · other */
    val kind: String = "griglia",
    val reason: String? = null,
    val origEan: String? = null,
    val origName: String? = null,
    val updatedBy: String? = null,
    val updatedAt: Long = 0,
)

data class Feedback(val boxes: Map<String, Int>, val notes: Map<String, String>, val deliveries: List<Pair<String, String>>)

data class ListState(
    val id: Int = 0,
    val week: String = "",
    val status: String = "",
    val submittedAt: Long = 0,
    val submittedBy: String = "",
    val receivedAt: Long = 0,
    val updatedAt: Long = 0,
    val lines: List<Line> = emptyList(),
    val feedback: Feedback? = null,
)

/** A stock error the store reported (counted on the shelf + back room vs what the system says). */
data class StockReport(
    val id: Int,
    val ean: String,
    val name: String,
    val systemQty: Double?,
    val counted: Int,
    val countedBy: String,
    /** open (waiting for the purchasing office) · fixed (stock corrected) · rejected (see answer) */
    val status: String,
    val answer: String,
    val createdAt: Long,
    val handledAt: Long,
)

/**
 * A question to the purchasing office about an item the store cannot order (unknown barcode, out of the
 * Griglia, no wholesaler this week). status: open (waiting) · info (the office asks for more information)
 * · answered. answerKind: use (order answerEan instead) · griglia (added to the Griglia) · once (ordered this
 * week only) · direct (from the direct supplier answerName) · no (we do not sell it) · info.
 */
data class ProductRequest(
    val id: Int,
    val ean: String,
    val name: String,
    val kind: String,
    val reason: String,
    val boxes: Int?,
    val note: String,
    val hasPhoto: Boolean,
    val status: String,
    val answerKind: String,
    val answer: String,
    val answerEan: String,
    val answerName: String,
    val answerBoxes: Int?,
    val addedToList: Boolean,
    val storeReply: String,
    val seenAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val handledAt: Long,
) {
    /** The office answered (or asked something) and nobody on the store's scanners has looked at it yet. */
    val unseen get() = (status == "answered" || status == "info") && seenAt == 0L
}

data class ServerView(
    val week: String,
    val deadline: Long,
    val list: ListState?,
    val previous: ListState?,
    val reminders: List<Line>,
    val fetchedAt: Long,
    val stockReports: List<StockReport> = emptyList(),
    val requests: List<ProductRequest> = emptyList(),
)

class StoreList(var server: ServerView? = null, val pending: LinkedHashMap<String, Line> = LinkedHashMap()) {

    /** Lines as the store sees them now: the server's, with this scanner's unsent changes on top (newest first). */
    fun lines(): List<Line> {
        val map = LinkedHashMap<String, Line>()
        for (l in server?.list?.lines.orEmpty()) map[l.ean] = l
        for ((k, l) in pending) map[k] = l
        return map.values.filter { it.boxes > 0 || it.kind == "remind" }.sortedByDescending { it.updatedAt }
    }

    fun line(ean: String): Line? = lines().firstOrNull { it.ean == ean }

    /** The newest stock report of this item (open, or handled in the last 14 days); null = none. */
    fun report(ean: String, now: Long = System.currentTimeMillis()): StockReport? =
        server?.stockReports.orEmpty().filter { it.ean == ean }.maxByOrNull { it.createdAt }
            ?.takeIf { it.status == "open" || now - maxOf(it.handledAt, it.createdAt) < 14L * 86_400_000 }

    val openReports get() = server?.stockReports.orEmpty().count { it.status == "open" }

    /** The newest request about this item (any scanner of the store), or null. */
    fun request(ean: String): ProductRequest? {
        val e = ean.trimStart('0')
        return server?.requests.orEmpty().filter { it.ean.trimStart('0') == e }.maxByOrNull { it.updatedAt }
    }

    /** Requests to show in «Da fare»: what the store must answer, new answers, then the ones waiting. */
    fun requestsToShow(now: Long = System.currentTimeMillis()): List<ProductRequest> =
        server?.requests.orEmpty()
            .filter { it.status != "answered" || it.unseen || now - it.handledAt < 7L * 86_400_000 }
            .sortedWith(compareBy<ProductRequest>({ Requests.rank(it) }, { -it.updatedAt }))

    /** Things the store has to look at: the office asked for information, or answered and nobody read it. */
    val requestsToRead get() = server?.requests.orEmpty().count { it.unseen }

    fun set(line: Line) {
        pending[line.ean] = line
    }

    fun remove(ean: String) {
        val old = line(ean) ?: return
        pending[ean] = old.copy(boxes = 0, kind = if (old.kind == "remind") "griglia" else old.kind, updatedAt = System.currentTimeMillis())
    }

    /** The server answered with its state after taking these changes. */
    fun applied(view: ServerView, sent: Collection<Line>) {
        server = view
        for (l in sent) if (pending[l.ean] == l) pending.remove(l.ean)
    }

    val ordered get() = lines().filter { it.boxes > 0 }
    val boxes get() = ordered.sumOf { it.boxes }
    val urgent get() = ordered.count { it.kind == "urgent" }
    val submitted get() = server?.list?.status == "submitted" || server?.list?.status == "ordered"

    /** Sent, and changed afterwards (by anyone): it has to be sent again. */
    val changedSinceSubmit: Boolean
        get() {
            val l = server?.list ?: return false
            if (!submitted) return false
            return pending.isNotEmpty() || l.updatedAt > l.submittedAt + 1000
        }

    /** Last week's lines that can be put back in (not urgencies, not already in this list). */
    fun fromLastWeek(): List<Line> {
        val now = lines().map { it.ean }.toSet()
        return server?.previous?.lines.orEmpty().filter { it.boxes > 0 && it.kind != "urgent" && it.ean !in now }
    }
}

object Feedbacks {
    data class Summary(val ordered: Int, val reduced: Int, val notOrdered: Int, val substituted: Int)

    /** Last week's line for this item and what the purchasing office did with it (asked, ordered or null, note). */
    data class Last(val asked: Int, val got: Int?, val note: String, val reason: String)

    fun last(list: ListState?, ean: String): Last? {
        list ?: return null
        val e = ean.trimStart('0')
        val l = list.lines.firstOrNull { it.ean.trimStart('0') == e && it.boxes > 0 } ?: return null
        val f = list.feedback
        val key = f?.boxes?.keys?.firstOrNull { it.trimStart('0') == e }
        return Last(l.boxes, key?.let { f.boxes[it] }, key?.let { f.notes[it] }.orEmpty(), l.reason.orEmpty())
    }

    /** The store asks for much more than the purchasing program suggests (e.g. 1 suggested, 6 wanted). */
    fun overOrder(suggested: Int?, boxes: Int): Boolean {
        val s = suggested ?: return false
        return s > 0 && boxes >= maxOf(s + 2, s * 2)
    }

    /** Reasons this scanner uses most first (stable for ties), from "reason=count;..." saved on the device. */
    fun byUse(reasons: List<String>, saved: String): List<String> {
        val n = HashMap<String, Int>()
        for (p in saved.split(';')) {
            val i = p.lastIndexOf('=')
            if (i > 0) n[p.substring(0, i)] = p.substring(i + 1).toIntOrNull() ?: 0
        }
        return reasons.withIndex().sortedWith(compareBy({ -(n[it.value] ?: 0) }, { it.index })).map { it.value }
    }

    fun countUse(saved: String, reason: String): String {
        val n = LinkedHashMap<String, Int>()
        for (p in saved.split(';')) {
            val i = p.lastIndexOf('=')
            if (i > 0) n[p.substring(0, i)] = p.substring(i + 1).toIntOrNull() ?: 0
        }
        n[reason] = (n[reason] ?: 0) + 1
        return n.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    /** What happened to a sent list, line by line, once the purchasing office has ordered. */
    fun summary(list: ListState): Summary? {
        val f = list.feedback ?: return null
        var ordered = 0
        var reduced = 0
        var none = 0
        var sub = 0
        for (l in list.lines.filter { it.boxes > 0 }) {
            val got = f.boxes[l.ean]
            when {
                l.kind == "sub" -> sub++
                got == null || got >= l.boxes -> ordered++
                got <= 0 -> none++
                else -> reduced++
            }
        }
        return Summary(ordered, reduced, none, sub)
    }
}

/**
 * Items to count later, kept on this scanner: while ordering the store marks "the stock looks wrong" without
 * stopping, and counts them all together afterwards (each count is then reported as usual).
 */
class ToCount(saved: String = "") {
    data class Item(val ean: String, val name: String, val at: Long)

    private val items = LinkedHashMap<String, Item>()

    init {
        for (l in saved.split('\n')) {
            val p = l.split('\t')
            if (p.size >= 3 && p[0].isNotEmpty()) items[key(p[0])] = Item(p[0], p[1], p[2].toLongOrNull() ?: 0)
        }
    }

    private fun key(ean: String) = ean.trimStart('0')

    fun add(ean: String, name: String, at: Long) { items[key(ean)] = Item(ean, name.replace('\t', ' ').replace('\n', ' '), at) }
    fun remove(ean: String): Boolean = items.remove(key(ean)) != null
    fun has(ean: String) = key(ean) in items
    val size get() = items.size
    /** newest first */
    fun all(): List<Item> = items.values.sortedByDescending { it.at }
    fun save(): String = items.values.joinToString("\n") { "${it.ean}\t${it.name}\t${it.at}" }
}

/** Product requests to the purchasing office: which scans can ask, and the words shown for each answer. */
object Requests {
    /** Why the store wants the item (code → what the scanner shows). */
    val REASONS = linkedMapOf(
        "customers" to "I clienti lo chiedono",
        "replaces" to "Sostituisce un articolo finito",
        "new" to "Novità del fornitore",
        "other" to "Altro",
    )

    /** Unknown barcodes, items taken out of the Griglia and Griglia items no wholesaler has this week. */
    fun eligible(v: Verdict): Boolean = when (v.kind) {
        Kind.UNKNOWN, Kind.NOT_AVAILABLE -> true
        Kind.URGENT_ONLY, Kind.DIRECT_ONLY -> v.item?.griglia == 2
        else -> false
    }

    /** What the scanner said about the item (sent with the request). */
    fun itemKind(v: Verdict): String = when (v.kind) {
        Kind.UNKNOWN -> "unknown"
        Kind.NOT_AVAILABLE -> "noquote"
        else -> "off"
    }

    /** Title of the state (one line on the card). */
    fun title(r: ProductRequest): String = when {
        r.status == "open" -> "Richiesta inviata · in attesa dell'ufficio"
        r.status == "info" -> "L'ufficio chiede più informazioni"
        else -> when (r.answerKind) {
            "use" -> "Ordina un altro articolo"
            "griglia" -> "Aggiunto in Griglia"
            "once" -> "Ordinato per questa settimana"
            "direct" -> "Si ordina dal fornitore diretto"
            "no" -> "Non lo trattiamo"
            else -> "Risposta dell'ufficio"
        }
    }

    /** 0 = good news (green) · 1 = to do / info (amber) · 2 = waiting (blue) · 3 = no (red) */
    fun tone(r: ProductRequest): Int = when {
        r.status == "open" -> 2
        r.status == "info" -> 1
        r.answerKind == "no" -> 3
        r.answerKind == "use" -> 1
        else -> 0
    }

    /** The views of the «Richieste all'ufficio» page. */
    enum class Filter(val label: String) { TODO("Da guardare"), WAITING("In attesa"), ANSWERED("Risposte"), ALL("Tutte") }

    /** To do for the store: the office asked something, or answered and nobody read it yet. */
    fun toLook(r: ProductRequest) = r.status == "info" || r.unseen

    fun matches(r: ProductRequest, f: Filter): Boolean = when (f) {
        Filter.TODO -> toLook(r)
        Filter.WAITING -> r.status == "open"
        Filter.ANSWERED -> r.status == "answered"
        Filter.ALL -> true
    }

    /** One line per item (its newest request), the ones to look at first, then newest first. */
    fun page(all: List<ProductRequest>, f: Filter): List<ProductRequest> =
        all.groupBy { it.ean.trimStart('0') }.values.map { g -> g.maxByOrNull { it.updatedAt }!! }
            .filter { f == Filter.ALL || it.status != "cancelled" }
            .filter { matches(it, f) }
            .sortedWith(compareBy<ProductRequest>({ rank(it) }, { -it.updatedAt }))

    fun counts(all: List<ProductRequest>): Map<Filter, Int> = Filter.values().associateWith { page(all, it).size }

    /** Opening the page: what to look at if there is something, otherwise everything. */
    fun firstFilter(all: List<ProductRequest>): Filter = if (page(all, Filter.TODO).isNotEmpty()) Filter.TODO else Filter.ALL

    /** Short state on the right of a row. */
    fun chip(r: ProductRequest): String = when {
        r.status == "cancelled" -> "ANNULLATA"
        r.status == "info" -> "RISPONDI"
        r.status == "open" -> "IN ATTESA"
        r.answerKind == "no" -> "NO"
        r.answerKind == "use" -> "USA ALTRO"
        else -> "SÌ"
    }

    fun rank(r: ProductRequest): Int = when {
        r.status == "info" -> 0
        r.unseen -> 1
        r.status == "open" -> 2
        r.status == "cancelled" -> 4
        else -> 3
    }
}
