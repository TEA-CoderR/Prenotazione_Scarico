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

data class ServerView(
    val week: String,
    val deadline: Long,
    val list: ListState?,
    val previous: ListState?,
    val reminders: List<Line>,
    val fetchedAt: Long,
    val stockReports: List<StockReport> = emptyList(),
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
