package com.serein.reader

import com.serein.reader.data.BookChapter
import com.serein.reader.data.BookContent
import com.serein.reader.data.progressAt
import com.serein.reader.data.readingLength
import com.serein.reader.data.search
import com.serein.reader.ui.boundedBitmapSampleSize
import com.serein.reader.ui.coverSampleSize
import com.serein.reader.ui.pageSummary
import com.serein.reader.ui.readerTransitionKey
import com.serein.reader.ui.buildScrollBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingNavigationTest {
    private val content = BookContent(
        listOf(
            BookChapter("One", listOf("A quiet room.", "Light crossed the books.")),
            BookChapter("Two", listOf("The reader returned home.")),
        )
    )

    @Test
    fun progressUsesLogicalCharacterPosition() {
        val atStart = content.progressAt(0, 0)
        val atSecondChapter = content.progressAt(1, 0)
        val atEnd = content.progressAt(1, Int.MAX_VALUE)

        assertEquals(0f, atStart)
        assertTrue(atSecondChapter > 0.5f)
        assertEquals(1f, atEnd)
    }

    @Test
    fun readingLengthDoesNotNeedToJoinTheWholeChapter() {
        assertEquals("A quiet room.\n\nLight crossed the books.".length, content.chapters.first().readingLength())
    }

    @Test
    fun searchReturnsChapterAndStableOffset() {
        val result = content.search("returned").single()

        assertEquals(1, result.chapterIndex)
        assertEquals("Two", result.chapterTitle)
        assertEquals(11, result.characterOffset)
        assertTrue(result.excerpt.contains("returned"))
    }

    @Test
    fun readingProgressDoesNotRestartTheScreenTransition() {
        val original = com.serein.reader.data.BookRecord("book-id", "Title", "Author")
        val updated = original.copy(progress = 0.42f, characterOffset = 120)

        assertEquals(readerTransitionKey(original), readerTransitionKey(updated))
    }

    @Test
    fun footerUsesCorrectPageGrammar() {
        assertEquals("1 page left  ·  1 / 2", pageSummary(1, 1, 2, paged = true))
        assertEquals("about 3 pages left  ·  2 / 5", pageSummary(3, 2, 5, paged = false))
    }

    @Test
    fun readingLocationCanBePersistedWithoutMutatingUnrelatedBookState() {
        val book = com.serein.reader.data.BookRecord(
            id = "book-id",
            title = "Title",
            author = "Author",
            lastOpenedAt = 123L,
        )

        val updated = book.withReadingLocation(
            com.serein.reader.data.ReadingLocation(
                chapterIndex = 3,
                characterOffset = 420,
                scrollOffsetPx = 16,
                progress = 1.4f,
            )
        )

        assertEquals(3, updated.chapterIndex)
        assertEquals(420, updated.characterOffset)
        assertEquals(16, updated.scrollOffsetPx)
        assertEquals(1f, updated.progress)
        assertEquals(123L, updated.lastOpenedAt)
        assertTrue(updated.completedAt > 0L)
    }

    @Test
    fun largeCoversAreDownsampledBeforeDecoding() {
        assertEquals(1, coverSampleSize(600, 900))
        assertEquals(4, coverSampleSize(4_000, 6_000))
    }

    @Test
    fun bitmapSamplingBoundsBothDimensionsForExtremeAspectRatios() {
        val panoramic = boundedBitmapSampleSize(
            width = Int.MAX_VALUE,
            height = 1,
            maximumWidth = 1_600,
            maximumHeight = 2_000,
        )
        val towering = boundedBitmapSampleSize(
            width = 1,
            height = Int.MAX_VALUE,
            maximumWidth = 1_600,
            maximumHeight = 2_000,
        )

        assertTrue(Int.MAX_VALUE.toLong() / panoramic <= 1_600L)
        assertTrue(Int.MAX_VALUE.toLong() / towering <= 2_000L)
    }

    @Test
    fun longParagraphsAreChunkedForSmoothScrolling() {
        val longParagraph = (1..1_200).joinToString(" ") { "word$it" }
        val blocks = buildScrollBlocks(BookContent(listOf(BookChapter("Long", listOf(longParagraph)))))

        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.text.length <= 1_600 })
        assertTrue(blocks.zipWithNext().all { (first, second) -> first.startOffset < second.startOffset })
    }
}
