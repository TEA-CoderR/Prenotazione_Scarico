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

data class ServerView(
    val week: String,
    val deadline: Long,
    val list: ListState?,
    val previous: ListState?,
    val reminders: List<Line>,
    val fetchedAt: Long,
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
