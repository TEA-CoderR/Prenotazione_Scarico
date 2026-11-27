package it.casate.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannersTest {
    @Test
    fun codeCleanedOfLineBreaksAndControlCharacters() {
        assertEquals("8001234567890", Scanners.clean("8001234567890\r\n"))
        assertEquals("ABC123", Scanners.clean("\u0002ABC123\t"))
        assertEquals("", Scanners.clean("\n"))
    }

    @Test
    fun commonBrandsListenedTo() {
        assertTrue(DataWedge.ACTION in Scanners.ACTIONS)
        assertTrue("android.intent.ACTION_DECODE_DATA" in Scanners.ACTIONS)      // Urovo
        assertTrue("nlscan.action.SCANNER_RESULT" in Scanners.ACTIONS)           // Newland
    }
}
