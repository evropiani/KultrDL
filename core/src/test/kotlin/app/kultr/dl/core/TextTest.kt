package app.kultr.dl.core

import app.kultr.dl.core.util.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTest {
    @Test
    fun coreTitleDropsFeaturesAndRemasters() {
        assertEquals("paper boats", Text.coreTitle("Paper Boats (feat. Somebody)"))
        assertEquals("paper boats", Text.coreTitle("Paper Boats - 2011 Remastered Version"))
        assertEquals("paper boats", Text.coreTitle("Paper Boats [Remastered 2009]"))
        assertEquals("cafe", Text.normalize("Café"))
    }

    @Test
    fun youtubeTitles() {
        assertEquals("Some Band" to "Paper Boats", Text.artistAndTitle("Some Band - Paper Boats (Official Music Video)", "SomeBandVEVO"))
        assertEquals("Some Band" to "Paper Boats", Text.artistAndTitle("Paper Boats", "Some Band - Topic"))
    }

    @Test
    fun clocksAndDates() {
        assertEquals(225_000L, Text.parseClock("3:45"))
        assertEquals(3_723_000L, Text.parseClock("1:02:03"))
        assertEquals(null, Text.parseClock("1.2M views"))
        assertEquals(225_000L, Text.parseIsoDuration("PT3M45S"))
        assertEquals(2019, Text.year("2019-05-17T00:00:00Z"))
    }

    @Test
    fun similarityAndArtists() {
        assertTrue(Text.similarity("Paper Boats", "paper boats") == 1.0)
        assertTrue(Text.similarity("Paper Boats", "Glass Houses") < 0.4)
        assertEquals(listOf("A", "B", "C"), Text.splitArtists("A, B & C"))
        assertEquals("A_B", Text.fileName("A/B"))
    }
}
