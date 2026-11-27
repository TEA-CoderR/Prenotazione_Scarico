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
}
