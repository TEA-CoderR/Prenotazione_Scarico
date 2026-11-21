package it.casate.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun item(griglia: Int, w: List<String> = emptyList(), sub: String = "", subName: String = "", direct: String = "") =
        Item("8001090000001", "SVELTO PIATTI 980", griglia, w, sub, subName, direct)

    @Test
    fun barcodeFoundWithOrWithoutLeadingZeros() {
        val c = Catalog(1, "", "", mapOf("8001090000001" to item(1, listOf("MELONI")), "12345" to item(0)))
        assertEquals("8001090000001", c.find(" 8001090000001 ")?.ean)
        assertEquals("8001090000001", c.find("08001090000001")?.ean)
        assertEquals("8001090000001", c.find("8001090000001")?.ean)
        assertEquals(null, c.find("abc"))
    }

    @Test
    fun verdicts() {
        assertEquals(Tone.OK, Catalog.judge(item(1, listOf("MELONI")), "x").tone)
        val s = Catalog.judge(item(1, sub = "8001", subName = "SVELTO 1000"), "x")
        assertEquals(Tone.SUBSTITUTE, s.tone)
        assertTrue(s.lines.contains("SVELTO 1000"))
        assertEquals(Tone.SUBSTITUTE, Catalog.judge(item(2, sub = "8001", subName = "N"), "x").tone)
        assertEquals(Tone.WARN, Catalog.judge(item(1), "x").tone)
        assertEquals(Tone.STOP, Catalog.judge(item(2, listOf("MELONI")), "x").tone)
        val notIn = Catalog.judge(item(0, listOf("MELONI"), direct = "BAMA"), "x")
        assertEquals(Tone.STOP, notIn.tone)
        assertTrue(notIn.lines.any { it.contains("BAMA") })
        assertEquals(Tone.UNKNOWN, Catalog.judge(null, "x").tone)
    }
}
