package it.casate.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreToolsTest {
    @Test
    fun phrasesLearnedFromWhatTheStoreWritesReplaceTheDefaults() {
        // for now (28/11) only the one sentence, whatever this scanner or the stores write
        assertEquals(listOf("C'è un articolo che lo sostituisce o tolgo questo prodotto dallo scaffale?"),
            Phrases.shown(mapOf("Finito da 2 settimane" to 5), listOf("Mandate il sostituto")))
        Phrases.ONLY_DEFAULTS = false
        assertEquals(Phrases.DEFAULTS, Phrases.shown(emptyMap()))
        var m = Phrases.learn(emptyMap(), "Finito da 2 settimane. Lo chiedono i clienti, ne vendevamo tanti")
        assertEquals(Phrases.DEFAULTS, Phrases.shown(m))                       // once: not yet
        m = Phrases.learn(m, "finito da 2 settimane, ne servono 2 colli")
        val common = listOf("Mandate il sostituto", "lo chiedono i clienti, ne vendevamo tanti")
        val shown = Phrases.shown(m, common)
        assertEquals("Finito da 2 settimane", shown[0])                         // this scanner, written twice → first
        assertEquals("Mandate il sostituto", shown[1])                          // then what all the stores write
        assertEquals(4, shown.size)                                             // + the default
        assertEquals(1, shown.count { it.equals("Lo chiedono i clienti, ne vendevamo tanti", ignoreCase = true) })
        Phrases.ONLY_DEFAULTS = true
        assertEquals("Finito", Phrases.append("", "Finito"))
        assertEquals("Finito, i clienti lo chiedono", Phrases.append("Finito", "I clienti lo chiedono"))
        assertEquals("Finito, i clienti lo chiedono", Phrases.append("Finito, i clienti lo chiedono", "I clienti lo chiedono"))
    }

    @Test
    fun volantinoBoxesFromExpectedMinusStockAndTransit() {
        val v = VolItem(1.49, 54.0, 0, "OTT 26", 48, false)
        assertEquals(2, Volantino.boxes(v, StoreInfo(stock = 6.0, pack = 24.0), null))         // 48 → 2 boxes
        assertEquals(1, Volantino.boxes(v, StoreInfo(stock = 6.0, transitBoxes = 1.0, pack = 24.0), null))
        assertEquals(0, Volantino.boxes(v, StoreInfo(stock = 60.0, pack = 24.0), null))
        assertEquals(3, Volantino.boxes(v, StoreInfo(stock = -10.0), 24.0))                     // negative stock = 0
        assertNull(Volantino.boxes(v, StoreInfo(stock = 1.0), null))
        val items = listOf("1" to StoreInfo(stock = 0.0, pack = 6.0, vol = v), "2" to StoreInfo(stock = 99.0, pack = 6.0, vol = v),
            "3" to StoreInfo(stock = 0.0, pack = 6.0, vol = v.copy(warn = true)), "4" to StoreInfo(stock = 1.0))
        val rows = Volantino.rows(items, { e -> if (e == "1") Line("1", "A", 3) else null }, { null })
        assertEquals(3, rows.size)
        val c = Volantino.counts(rows)
        assertEquals(1, c[Volantino.Filter.DONE]); assertEquals(1, c[Volantino.Filter.COVERED]); assertEquals(1, c[Volantino.Filter.TODO])
        assertFalse(Volantino.bulk(rows.first { it.ean == "3" }))                                // flagged: one by one
        assertEquals("VOLANTINO 03/12-20/12 · IN ARRIVO DEDOTTO", Volantino.reason(VolantinoInfo("DIC", "2026-12-03", "2026-12-20", 18, 3)))
        assertEquals(6L, Volantino.daysTo("2026-12-03", java.time.LocalDate.of(2026, 11, 27)))
    }

    @Test
    fun listSectionsCheckFirstThenGrigliaRestLast() {
        val lines = listOf(Line("1", "Zeta", 2), Line("2", "Alfa", 1), Line("3", "Nicky", 1, "urgent"),
            Line("4", "Sost", 1, "sub"), Line("5", "Avviso", 0, "remind"), Line("6", "Tolto", 0))
        val g = Sections.group(lines, setOf("1"))
        assertEquals(listOf(Sections.Kind.CHECK, Sections.Kind.GRIGLIA, Sections.Kind.URGENT, Sections.Kind.SUB, Sections.Kind.REMIND), g.map { it.first })
        assertEquals(listOf("1"), g[0].second.map { it.ean })
        assertEquals(listOf("2"), g[1].second.map { it.ean })                                     // 0 boxes not shown
    }

    @Test
    fun inStoreSinceLastDeliveryAndMonthAverage() {
        val info = StoreInfo(stock = 6.0, arrivals = listOf(Arrival(20261112, 24.0, false, "MELONI", 18.0), Arrival(20261015, 6.0, true, "S.MARCO")))
        val s = InStore.since(info)!!
        assertEquals("12/11/26", s.day); assertEquals(24.0, s.arrived, 1e-9); assertEquals(18.0, s.sold, 1e-9); assertEquals("MELONI", s.from)
        assertNull(InStore.since(StoreInfo(stock = 1.0)))
        assertEquals(20.0, InStore.monthAvg(listOf(10.0, 30.0, 99.0))!!, 1e-9)                    // this month left out
    }

    @Test
    fun oldRequestsStillShowAConversation() {
        val r = ProductRequest(1, "8001", "X", "unknown", "customers", 2, "Finito", false, "answered", "no", "Non lo trattiamo", "", "",
            null, false, "È nuovo", 0, 1000, 3000, 2000)
        val t = r.thread()
        assertEquals(listOf(true, true, false), t.map { it.mine })
        assertEquals("Finito · I clienti lo chiedono", t[0].text)
        assertEquals("no", t[2].kind)
        val m = r.copy(messages = listOf(RequestMessage(true, "Ciao", 1, null, false, "")))
        assertEquals(1, m.thread().size)
    }
}
