package it.casate.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun item(griglia: Int, w: List<String> = emptyList(), sub: String = "", subName: String = "", direct: String = "", ean: String = "8001090000001") =
        Item(ean, "SVELTO PIATTI 980", griglia, w, sub, subName, direct)

    @Test
    fun barcodeFoundWithOrWithoutLeadingZeros() {
        val c = Catalog(1, "", "", mapOf("8001090000001" to item(1, listOf("MELONI")), "12345" to item(0)))
        assertEquals("8001090000001", c.find(" 8001090000001 ")?.ean)
        assertEquals("8001090000001", c.find("08001090000001")?.ean)
        assertEquals(null, c.find("abc"))
    }

    @Test
    fun verdicts() {
        assertEquals(Kind.ORDER, Catalog.judgeItem(item(1, listOf("MELONI"))).kind)
        val s = Catalog.judgeItem(item(1, sub = "8001", subName = "SVELTO 1000"))
        assertEquals(Kind.SUBSTITUTE, s.kind)
        assertEquals("SVELTO 1000", s.substitute?.name)
        assertEquals(Kind.SUBSTITUTE, Catalog.judgeItem(item(2, sub = "8001", subName = "N")).kind)
        assertEquals(Kind.NOT_AVAILABLE, Catalog.judgeItem(item(1)).kind)
        // not in Griglia but a wholesaler has it: only in an emergency
        assertEquals(Kind.URGENT_ONLY, Catalog.judgeItem(item(0, listOf("MEGA CASH"), direct = "SAIS")).kind)
        assertEquals(Kind.URGENT_ONLY, Catalog.judgeItem(item(2, listOf("MELONI"))).kind)
        assertEquals(Kind.DIRECT_ONLY, Catalog.judgeItem(item(0, direct = "BAMA")).kind)
        assertEquals(Kind.UNKNOWN, Catalog.judgeItem(null).kind)
    }

    @Test
    fun substituteTakenFromTheCatalog() {
        val new = item(1, listOf("MELONI"), ean = "8001")
        val c = Catalog(1, "", "", mapOf("8001090000001" to item(2, sub = "8001", subName = "x"), "8001" to new))
        assertEquals(listOf("MELONI"), c.judge("8001090000001").substitute?.wholesalers)
    }

    @Test
    fun storeNumbers() {
        val info = StoreInfo(stock = 9.0, sold30 = 30.0, transitBoxes = 0.0, suggestBoxes = 2, pack = 6.0)
        assertEquals(9, info.coverDays())
        assertEquals(21, Catalog.coverAfter(info, 2))
        assertEquals(2, Catalog.startBoxes(null, info))
        assertEquals(3, Catalog.startBoxes(3, info))
        assertEquals(1, Catalog.startBoxes(null, StoreInfo(suggestBoxes = 0)))
        assertTrue(Catalog.overstocked(StoreInfo(stock = 46.0, sold30 = 10.0)))
        assertTrue(Catalog.overstocked(StoreInfo(stock = 5.0, sold30 = 0.0)))
        assertFalse(Catalog.overstocked(info))
        assertNull(StoreInfo(stock = 3.0).coverDays())
        assertEquals("4 mesi", Catalog.months(110))
        val c = Catalog(1, "", "", emptyMap(), mapOf("8001090000001" to info))
        assertEquals(9.0, c.info("08001090000001")?.stock)
    }

    @Test
    fun searchByNameWordsOrBarcodeGrigliaFirst() {
        val a = Item("8001", "SVELTO PIATTI LIMONE 1000", 0, listOf("MELONI"), "", "", "")
        val b = Item("8002", "SVELTO PIATTI LIMONE 980", 1, listOf("MELONI"), "", "", "", pack = 12.0, bestPrice = 1.1, bestSource = "MELONI")
        val c = Item("8003", "DIXAN 40", 1, emptyList(), "", "", "")
        val cat = Catalog(1, "", "", mapOf("8001" to a, "8002" to b, "8003" to c), imageUrl = "https://x/{ean}.webp")
        assertEquals(listOf("8002", "8001"), cat.search("svelto limone").map { it.ean })
        assertEquals(listOf("8003"), cat.search("8003").map { it.ean })
        assertEquals(emptyList<Item>(), cat.search("  "))
        assertEquals(12.0, cat.find("8002")!!.pack)
        assertEquals("https://x/{ean}.webp", cat.imageUrl)
    }

    @Test
    fun adviceAlwaysSaysSomething() {
        // the purchasing program's number
        val sys = Catalog.advise(StoreInfo(stock = 9.0, sold30 = 30.0, suggestBoxes = 2, pack = 6.0), null)
        assertEquals(2, sys.boxes)
        assertFalse(sys.estimated)
        assertTrue(sys.reason.contains("9 giorni"))
        // not computed: estimated until the next delivery (horizon days, 14 by default), not a whole month
        val est = Catalog.advise(StoreInfo(stock = 4.0, sold30 = 60.0, transitBoxes = 1.0, pack = 6.0), null)
        assertEquals(3, est.boxes)                         // (2/day × 14 − 4 − 6) / 6 → 3
        assertTrue(est.estimated)
        assertEquals(0, Catalog.advise(StoreInfo(stock = 4.0, sold30 = 20.0, transitBoxes = 1.0, pack = 6.0), null).boxes)
        // already enough: 0, and why
        val none = Catalog.advise(StoreInfo(stock = 3.0, sold30 = 1.0, transitBoxes = 2.0, suggestBoxes = 0, pack = 6.0), null)
        assertEquals(0, none.boxes)
        assertTrue(none.reason.contains("già in arrivo"))
        assertEquals(0, Catalog.advise(StoreInfo(stock = 5.0, sold30 = 0.0), 6.0).boxes)
        // nothing to go on
        assertNull(Catalog.advise(null, 6.0).boxes)
        assertNull(Catalog.advise(StoreInfo(stock = 0.0, sold30 = 0.0), 6.0).boxes)
        // the list starts from the suggestion (estimate too), else 1
        assertEquals(3, Catalog.startBoxes(null, StoreInfo(stock = 4.0, sold30 = 60.0, transitBoxes = 1.0, pack = 6.0)))
        assertEquals(1, Catalog.startBoxes(null, null))
    }

    @Test
    fun newerAppVersion() {
        val u = AppUpdate(42, "1.0.42", "ab", 10, "")
        assertTrue(AppUpdate.isNewer(u, 41))
        assertFalse(AppUpdate.isNewer(u, 42))
        assertFalse(AppUpdate.isNewer(null, 1))
    }

    @Test
    fun scannerTypingIsTreatedAsScanPersonIsNot() {
        var t = 0L
        val k = KeyScan { t }
        // scanner: 13 digits a few ms apart, then ENTER
        for (c in "8001280307621") { k.add(c); t += 4 }
        assertEquals("8001280307621", k.finish())
        // a person: 300 ms between keys → each pause starts over, never a scan
        for (c in "123456") { t += 300; k.add(c) }
        assertNull(k.finish())
        // too short
        for (c in "1234") { k.add(c); t += 3 }
        assertNull(k.finish())
        // search box: whole code in one burst
        assertTrue(KeyScan.looksScanned("8001280307621", 60))
        assertFalse(KeyScan.looksScanned("8001280307621", 4000))
        assertFalse(KeyScan.looksScanned("DIXAN", 10))
    }
}
