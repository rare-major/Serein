package com.serein.reader

import com.serein.reader.data.PdfToEpubConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FORM_FEED = ''
private const val VERTICAL_TAB = ''
private const val UNIT_SEPARATOR = ''

class PdfToEpubConverterTest {
    @Test
    fun rejoinsHyphenatedWordsAcrossLineBreaks() {
        val raw = "This is an exam-\nple of hyphenation."

        assertEquals("This is an example of hyphenation.", PdfToEpubConverter.cleanParagraph(raw))
    }

    @Test
    fun doesNotJoinAcrossADeliberateLineBreakWithoutAHyphen() {
        val raw = "First line\nsecond line"

        assertEquals("First line second line", PdfToEpubConverter.cleanParagraph(raw))
    }

    @Test
    fun collapsesRunsOfWhitespaceIncludingFormFeedsAndVerticalTabs() {
        val raw = "  Padded   text${VERTICAL_TAB}with${FORM_FEED}tabs  "

        assertEquals("Padded text with tabs", PdfToEpubConverter.cleanParagraph(raw))
    }

    @Test
    fun dropsStrayControlBytesWithoutLeavingASpace() {
        val raw = "Clean${UNIT_SEPARATOR}text"

        assertEquals("Cleantext", PdfToEpubConverter.cleanParagraph(raw))
    }

    @Test
    fun chunksPagesIntoChaptersOfTheDefaultSize() {
        val specs = PdfToEpubConverter.pageChunkSpecs(pageCount = 45)

        assertEquals(2, specs.size)
        assertEquals(0, specs[0].startPage)
        assertEquals(40, specs[0].endPageExclusive)
        assertEquals("Chapter 1", specs[0].title)
        assertEquals(40, specs[1].startPage)
        assertEquals(45, specs[1].endPageExclusive)
        assertEquals("Chapter 2", specs[1].title)
    }

    @Test
    fun boundsChapterCountForVeryLongPdfsByGrowingChapterSize() {
        val specs = PdfToEpubConverter.pageChunkSpecs(pageCount = 100_000)

        assertTrue(specs.size <= 400)
        assertEquals(0, specs.first().startPage)
        assertEquals(100_000, specs.last().endPageExclusive)
    }

    @Test
    fun leavesShortSpansUntouched() {
        val spec = PdfToEpubConverter.ChapterSpec("Preface", startPage = 0, endPageExclusive = 10)

        assertEquals(listOf(spec), PdfToEpubConverter.splitToMaxSpan(spec))
    }

    @Test
    fun splitsAnOverlongOutlineChapterIntoContinuationParts() {
        val spec = PdfToEpubConverter.ChapterSpec("Notes", startPage = 0, endPageExclusive = 90)

        val parts = PdfToEpubConverter.splitToMaxSpan(spec)

        assertEquals(3, parts.size)
        assertEquals("Notes", parts[0].title)
        assertEquals(0 to 40, parts[0].startPage to parts[0].endPageExclusive)
        assertEquals("Notes (continued)", parts[1].title)
        assertEquals(40 to 80, parts[1].startPage to parts[1].endPageExclusive)
        assertEquals("Notes (continued)", parts[2].title)
        assertEquals(80 to 90, parts[2].startPage to parts[2].endPageExclusive)
    }

    @Test
    fun escapesXmlSpecialCharactersForSafeEmbedding() {
        val escaped = PdfToEpubConverter.escapeXml("Tom & Jerry <says> \"hi\"")

        assertEquals("Tom &amp; Jerry &lt;says&gt; &quot;hi&quot;", escaped)
    }
}
