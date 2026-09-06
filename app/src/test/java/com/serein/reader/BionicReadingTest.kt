package com.serein.reader

import com.serein.reader.data.BionicReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BionicReadingTest {
    @Test
    fun emphasizesOnlyTheBeginningOfEachWord() {
        val text = "Reading is gentle."
        val emphasized = BionicReading.emphasisRanges(text).map { text.substring(it) }

        assertEquals(listOf("Read", "i", "gen"), emphasized)
    }

    @Test
    fun preservesApostrophesInsideWords() {
        val text = "O’Connor’s own"
        val emphasized = BionicReading.emphasisRanges(text).map { text.substring(it) }

        assertEquals(listOf("O’Con", "ow"), emphasized)
    }

    @Test
    fun emphasizesExactlyHalfRoundedUpForOddLengthWords() {
        val text = "comfortable"
        val emphasized = BionicReading.emphasisRanges(text).map { text.substring(it) }

        assertEquals(listOf("comfor"), emphasized)
    }

    @Test
    fun handlesPunctuationUnicodeAndNumbers() {
        val text = "Hello, naïve reader 2026!"
        val emphasized = BionicReading.emphasisRanges(text).map { text.substring(it) }

        assertEquals(listOf("Hel", "naï", "rea", "20"), emphasized)
    }

    @Test
    fun handlesSupplementaryUnicodeWithoutSplittingCodePoints() {
        val text = "𐐀𐐨𐐯𐐻 reader"
        val ranges = BionicReading.emphasisRanges(text)

        assertEquals("𐐀𐐨", text.substring(ranges.first()))
        assertTrue(ranges.all { it.first >= 0 && it.last < text.length })
    }

    @Test
    fun streamsRangesForLargeChaptersWithoutAnIntermediateList() {
        val text = "comfortable reading ".repeat(10_000)
        var rangeCount = 0

        BionicReading.forEachEmphasisRange(text) { range ->
            assertTrue(range.first >= 0 && range.last < text.length)
            rangeCount += 1
        }

        assertEquals(20_000, rangeCount)
    }
}
