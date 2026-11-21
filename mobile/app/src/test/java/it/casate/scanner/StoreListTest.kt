package it.casate.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreListTest {
    private fun view(lines: List<Line>, status: String = "draft", submittedAt: Long = 0, updatedAt: Long = 0, prev: List<Line> = emptyList()) =
        ServerView("2026-11-22", 0, ListState(1, "2026-11-22", status, submittedAt, "", 0, updatedAt, lines),
            ListState(0, "2026-11-15", "ordered", 1, "", 1, 1, prev), emptyList(), 0)

    @Test
    fun pendingChangesOnTopOfTheServer() {
        val l = StoreList(view(listOf(Line("1", "A", 2, updatedAt = 1), Line("2", "B", 1, updatedAt = 2))))
        l.set(Line("1", "A", 5, updatedAt = 10))
        l.set(Line("3", "C", 1, "urgent", reason = "Scaffale vuoto", updatedAt = 11))
        assertEquals(listOf("3", "1", "2"), l.lines().map { it.ean })
        assertEquals(5 + 1 + 1, l.boxes)
        assertEquals(1, l.urgent)
        l.remove("2")
        assertEquals(listOf("3", "1"), l.ordered.map { it.ean })
        // the server took the changes
        val sent = l.pending.values.toList()
        l.applied(view(listOf(Line("1", "A", 5), Line("3", "C", 1, "urgent"))), sent)
        assertTrue(l.pending.isEmpty())
        assertEquals(6, l.boxes)
    }

    @Test
    fun aChangeMadeWhileSendingStaysQueued() {
        val l = StoreList(view(emptyList()))
        l.set(Line("1", "A", 1, updatedAt = 1))
        val sent = l.pending.values.toList()
        l.set(Line("1", "A", 2, updatedAt = 2))
        l.applied(view(listOf(Line("1", "A", 1))), sent)
        assertEquals(2, l.line("1")?.boxes)
    }

    @Test
    fun remindersAndChangesAfterSending() {
        val l = StoreList(view(listOf(Line("1", "A", 2), Line("9", "R", 0, "remind")), "submitted", 1000, 1000))
        assertEquals(1, l.ordered.size)
        assertEquals(2, l.lines().size)
        assertTrue(l.submitted)
        assertFalse(l.changedSinceSubmit)
        l.set(Line("1", "A", 3))
        assertTrue(l.changedSinceSubmit)
    }

    @Test
    fun lastWeekWithoutUrgenciesOrDuplicates() {
        val l = StoreList(view(listOf(Line("1", "A", 2)), prev = listOf(Line("1", "A", 1), Line("2", "B", 3), Line("3", "C", 1, "urgent", "x"))))
        assertEquals(listOf("2"), l.fromLastWeek().map { it.ean })
    }

    @Test
    fun feedbackSummary() {
        val s = ListState(lines = listOf(Line("1", "A", 2), Line("2", "B", 3), Line("3", "C", 1), Line("4", "D", 1, "sub")),
            feedback = Feedback(mapOf("1" to 2, "2" to 1, "3" to 0, "4" to 1), emptyMap(), emptyList()))
        assertEquals(Feedbacks.Summary(1, 1, 1, 1), Feedbacks.summary(s))
    }

    @Test
    fun stockReportsNewestOpenOrRecentlyHandled() {
        val day = 86_400_000L
        val now = 100 * day
        fun rep(id: Int, ean: String, status: String, at: Long, handled: Long = 0) =
            StockReport(id, ean, "X", 12.0, 0, "Marco", status, "", at, handled)
        val v = ServerView("w", 0, null, null, emptyList(), 0, listOf(
            rep(1, "1", "fixed", now - 30 * day, now - 29 * day), rep(2, "1", "open", now - day),
            rep(3, "2", "fixed", now - 20 * day, now - 20 * day), rep(4, "3", "rejected", now - 2 * day, now - day)))
        val l = StoreList(v)
        assertEquals(2, l.report("1", now)?.id)
        assertEquals(null, l.report("2", now))          // corrected long ago: nothing to show
        assertEquals("rejected", l.report("3", now)?.status)
        assertEquals(1, l.openReports)
    }
}
