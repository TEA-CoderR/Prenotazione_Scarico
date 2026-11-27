package it.casate.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestsTest {
    private fun req(id: Int, ean: String, status: String, answerKind: String = "", seen: Long = 0, at: Long = id.toLong(), handled: Long = 0) =
        ProductRequest(id, ean, "", "unknown", "customers", null, "", false, status, answerKind, "", "", "", null, false, "", seen, at, at, handled)

    private fun item(griglia: Int, wholesalers: List<String> = emptyList(), sub: String = "") =
        Item("1", "X", griglia, wholesalers, sub, "", "")

    @Test
    fun onlyUnknownOutOfGrigliaAndNoWholesalerCanAsk() {
        assertTrue(Requests.eligible(Catalog.judgeItem(null)))
        assertTrue(Requests.eligible(Catalog.judgeItem(item(1))))               // in Griglia, nobody has it
        assertTrue(Requests.eligible(Catalog.judgeItem(item(2, listOf("MELONI"))))) // taken out of the Griglia
        assertTrue(Requests.eligible(Catalog.judgeItem(item(2))))
        assertFalse(Requests.eligible(Catalog.judgeItem(item(0, listOf("MELONI"))))) // NON IN GRIGLIA: urgent reason as before
        assertFalse(Requests.eligible(Catalog.judgeItem(item(0))))
        assertFalse(Requests.eligible(Catalog.judgeItem(item(1, listOf("MELONI")))))
        assertEquals("noquote", Requests.itemKind(Catalog.judgeItem(item(1))))
        assertEquals("off", Requests.itemKind(Catalog.judgeItem(item(2))))
        assertEquals("unknown", Requests.itemKind(Catalog.judgeItem(null)))
    }

    @Test
    fun newestRequestPerItemAndToDoOrder() {
        val now = 100L * 86_400_000
        val reqs = listOf(
            req(1, "0123", "answered", "no", seen = 5, at = 1, handled = 2),              // old, read: hidden
            req(2, "123", "open", at = now - 10),
            req(3, "555", "answered", "use", at = now - 5, handled = now - 5),            // new answer
            req(4, "777", "info", "info", seen = now - 1, at = now - 20, handled = now - 20), // must reply
        )
        val l = StoreList(ServerView("w", 0, null, null, emptyList(), 0, requests = reqs))
        assertEquals(2, l.request("123")?.id) // leading zeros do not matter, newest wins
        assertNull(l.request("999"))
        assertEquals(listOf(4, 3, 2), l.requestsToShow(now).map { it.id })
        assertEquals(1, l.requestsToRead)
        assertEquals("Ordina un altro articolo", Requests.title(reqs[2]))
        assertEquals(2, Requests.tone(reqs[1]))
    }

    @Test
    fun requestsPageOneLinePerItemFiltersAndCounts() {
        val reqs = listOf(
            req(1, "0111", "answered", "no", seen = 5, at = 1, handled = 2),     // old answer of 111, replaced below
            req(2, "111", "open", at = 50),                                       // asked again: newest wins
            req(3, "222", "answered", "griglia", at = 40, handled = 40),         // new answer, not read
            req(4, "333", "info", at = 30, seen = 31),                            // office asks: always to look at
            req(5, "444", "answered", "no", seen = 9, at = 20, handled = 8),      // read answer
            req(6, "555", "cancelled", at = 60),                                  // cancelled: only in «Tutte»
        )
        assertEquals(listOf(4, 3), Requests.page(reqs, Requests.Filter.TODO).map { it.id })
        assertEquals(listOf(2), Requests.page(reqs, Requests.Filter.WAITING).map { it.id })
        assertEquals(listOf(3, 5), Requests.page(reqs, Requests.Filter.ANSWERED).map { it.id })
        assertEquals(listOf(4, 3, 2, 5, 6), Requests.page(reqs, Requests.Filter.ALL).map { it.id })
        val n = Requests.counts(reqs)
        assertEquals(2, n[Requests.Filter.TODO]); assertEquals(1, n[Requests.Filter.WAITING]); assertEquals(5, n[Requests.Filter.ALL])
        assertEquals(Requests.Filter.TODO, Requests.firstFilter(reqs))
        assertEquals(Requests.Filter.ALL, Requests.firstFilter(listOf(reqs[4])))
        assertEquals("RISPONDI", Requests.chip(reqs[3])); assertEquals("IN ATTESA", Requests.chip(reqs[1]))
        assertEquals("ANNULLATA", Requests.chip(reqs[5])); assertEquals("SÌ", Requests.chip(reqs[2]))
    }
}
