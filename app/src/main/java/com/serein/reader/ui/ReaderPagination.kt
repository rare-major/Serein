package com.serein.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.serein.reader.data.BookContent
import com.serein.reader.data.BookBlockKind
import com.serein.reader.data.BookInlineSpan
import com.serein.reader.data.BookInlineStyle
import com.serein.reader.data.readingText

internal data class ReadingPage(
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val isChapterStart: Boolean,
    val imagePath: String? = null,
    val imageAltText: String = "",
    val inlineSpans: List<BookInlineSpan> = emptyList(),
)

internal data class ScrollBlock(
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val isChapterStart: Boolean,
    val kind: BookBlockKind = BookBlockKind.PARAGRAPH,
    val imagePath: String? = null,
    val altText: String = "",
    val inlineSpans: List<BookInlineSpan> = emptyList(),
)

internal data class ReaderSelection(
    val chapterIndex: Int,
    val wordStart: Int,
    val wordEnd: Int,
    val word: String,
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val sentence: String,
)

internal const val MAX_PAGE_PROBE_CHARACTERS = 4_096

internal fun paginateBook(
    content: BookContent,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    pageWidthPx: Int,
    normalPageHeightPx: Int,
    firstPageHeightPx: Int,
): Sequence<ReadingPage> = paginateBookFrom(
    content = content,
    textMeasurer = textMeasurer,
    textStyle = textStyle,
    pageWidthPx = pageWidthPx,
    normalPageHeightPx = normalPageHeightPx,
    firstPageHeightPx = firstPageHeightPx,
    startChapterIndex = 0,
    startCharacterOffset = 0,
)

internal fun paginateBookFrom(
    content: BookContent,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    pageWidthPx: Int,
    normalPageHeightPx: Int,
    firstPageHeightPx: Int,
    startChapterIndex: Int,
    startCharacterOffset: Int,
): Sequence<ReadingPage> = sequence {
    content.chapters.forEachIndexed { chapterIndex, chapter ->
        if (chapterIndex < startChapterIndex) return@forEachIndexed
        val chapterText = chapter.readingText()
        if (chapterText.isBlank()) return@forEachIndexed
        var blockOffset = 0
        val chapterInlineSpans = mutableListOf<BookInlineSpan>()
        val chapterImages = buildList {
            chapter.blocks.forEach { block ->
                val representation = when (block.kind) {
                    BookBlockKind.IMAGE -> block.altText.takeIf(String::isNotBlank)?.let { "[$it]" }.orEmpty()
                    BookBlockKind.SEPARATOR -> "• • •"
                    else -> block.text
                }
                if (block.kind == BookBlockKind.IMAGE && block.imagePath != null) {
                    add(Triple(blockOffset, block.imagePath, block.altText))
                }
                block.inlineSpans.forEach { span ->
                    chapterInlineSpans.add(
                        span.copy(start = span.start + blockOffset, end = span.end + blockOffset)
                    )
                }
                val semanticStyle = when (block.kind) {
                    BookBlockKind.HEADING -> BookInlineStyle.BOLD
                    BookBlockKind.QUOTE -> BookInlineStyle.ITALIC
                    else -> null
                }
                if (semanticStyle != null && representation.isNotBlank()) {
                    chapterInlineSpans.add(
                        BookInlineSpan(
                            start = blockOffset,
                            end = blockOffset + representation.length,
                            style = semanticStyle,
                        )
                    )
                }
                blockOffset += representation.length + 2
            }
        }
        chapterInlineSpans.sortBy(BookInlineSpan::start)
        var inlineSpanCursor = 0
        var start = if (chapterIndex == startChapterIndex) {
            startCharacterOffset.coerceIn(0, chapterText.length)
        } else {
            0
        }
        while (start < chapterText.length && chapterText[start].isWhitespace()) start++
        var isFirstPage = start == 0
        while (start < chapterText.length) {
            val height = if (isFirstPage) firstPageHeightPx else normalPageHeightPx
            val probeEnd = (start + MAX_PAGE_PROBE_CHARACTERS).coerceAtMost(chapterText.length)
            val remainder = chapterText.substring(start, probeEnd)
            while (
                inlineSpanCursor < chapterInlineSpans.size &&
                chapterInlineSpans[inlineSpanCursor].end <= start
            ) {
                inlineSpanCursor++
            }
            val relevantInlineSpans = buildList {
                var spanIndex = inlineSpanCursor
                while (
                    spanIndex < chapterInlineSpans.size &&
                    chapterInlineSpans[spanIndex].start < probeEnd
                ) {
                    add(chapterInlineSpans[spanIndex])
                    spanIndex++
                }
            }
            val measuredRemainder = buildAnnotatedString {
                append(remainder)
                relevantInlineSpans.forEach { span ->
                    val localStart = (span.start - start).coerceIn(0, remainder.length)
                    val localEnd = (span.end - start).coerceIn(0, remainder.length)
                    if (localStart < localEnd) addStyle(span.style.toSpanStyle(), localStart, localEnd)
                }
            }
            val layout = textMeasurer.measure(
                text = measuredRemainder,
                style = textStyle,
                overflow = TextOverflow.Clip,
                softWrap = true,
                maxLines = Int.MAX_VALUE,
                constraints = Constraints(
                    maxWidth = pageWidthPx.coerceAtLeast(1),
                    maxHeight = height.coerceAtLeast(1),
                ),
            )
            var visibleCharacters = if (layout.didOverflowHeight && layout.lineCount > 0) {
                val lastVisibleLine = (0 until layout.lineCount)
                    .takeWhile { layout.getLineBottom(it) <= height }
                    .lastOrNull()
                    ?: 0
                layout.getLineEnd(lastVisibleLine, visibleEnd = true)
            } else {
                remainder.length
            }
            if (visibleCharacters <= 0) visibleCharacters = minOf(remainder.length, 180)
            if (visibleCharacters < remainder.length) {
                val whitespace = remainder.lastIndexOfAny(
                    charArrayOf(' ', '\n', '\t'),
                    startIndex = (visibleCharacters - 1).coerceAtLeast(0),
                )
                if (whitespace > visibleCharacters / 2) visibleCharacters = whitespace + 1
            }
            val rawEnd = (start + visibleCharacters).coerceAtMost(chapterText.length)
            val pageText = chapterText.substring(start, rawEnd).trimEnd()
            val end = start + pageText.length
            val pageInlineSpans = relevantInlineSpans.mapNotNull { span ->
                val localStart = (span.start - start).coerceIn(0, pageText.length)
                val localEnd = (span.end - start).coerceIn(0, pageText.length)
                span.copy(start = localStart, end = localEnd).takeIf { localStart < localEnd }
            }
            yield(
                ReadingPage(
                    chapterIndex = chapterIndex,
                    startOffset = start,
                    endOffset = end,
                    text = pageText,
                    isChapterStart = isFirstPage,
                    inlineSpans = pageInlineSpans,
                )
            )
            chapterImages.filter { (offset) -> offset in start until rawEnd }.forEach { (offset, path, alt) ->
                yield(
                    ReadingPage(
                        chapterIndex = chapterIndex,
                        startOffset = offset,
                        endOffset = offset,
                        text = "",
                        isChapterStart = false,
                        imagePath = path,
                        imageAltText = alt,
                    )
                )
            }
            start = rawEnd
            while (start < chapterText.length && chapterText[start].isWhitespace()) start++
            isFirstPage = false
        }
    }
}

internal fun buildScrollBlocks(content: BookContent): List<ScrollBlock> = buildList {
    content.chapters.forEachIndexed { chapterIndex, chapter ->
        var offset = 0
        val sourceBlocks = chapter.blocks.ifEmpty {
            chapter.paragraphs.map { com.serein.reader.data.BookBlock(it) }
        }
        sourceBlocks.forEachIndexed { blockIndex, source ->
            val paragraph = when (source.kind) {
                BookBlockKind.IMAGE -> source.altText.takeIf(String::isNotBlank)?.let { "[$it]" }.orEmpty()
                BookBlockKind.SEPARATOR -> "• • •"
                else -> source.text
            }
            val chunks = if (source.kind == BookBlockKind.IMAGE || source.kind == BookBlockKind.SEPARATOR) {
                listOf(0 to paragraph)
            } else {
                scrollChunks(paragraph)
            }
            chunks.forEachIndexed { chunkIndex, (localOffset, chunk) ->
                add(
                    ScrollBlock(
                        chapterIndex = chapterIndex,
                        startOffset = offset + localOffset,
                        endOffset = offset + localOffset + chunk.length,
                        text = chunk,
                        isChapterStart = blockIndex == 0 && chunkIndex == 0,
                        kind = source.kind,
                        imagePath = source.imagePath,
                        altText = source.altText,
                        inlineSpans = source.inlineSpans.mapNotNull { span ->
                            val chunkStart = localOffset
                            val chunkEnd = localOffset + chunk.length
                            val start = maxOf(span.start, chunkStart)
                            val end = minOf(span.end, chunkEnd)
                            span.copy(start = start - chunkStart, end = end - chunkStart)
                                .takeIf { it.start < it.end }
                        },
                    )
                )
            }
            offset += paragraph.length + 2
        }
    }
}

private fun scrollChunks(text: String): List<Pair<Int, String>> {
    if (text.length <= MAX_SCROLL_BLOCK_CHARACTERS) return listOf(0 to text)
    return buildList {
        var start = 0
        while (start < text.length) {
            var end = (start + MAX_SCROLL_BLOCK_CHARACTERS).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOfAny(
                    charArrayOf(' ', '\n', '\t'),
                    startIndex = end,
                )
                if (boundary > start + MAX_SCROLL_BLOCK_CHARACTERS / 2) end = boundary
            }
            val raw = text.substring(start, end)
            val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val chunk = raw.trimEnd()
            if (chunk.isNotBlank()) add((start + leading) to chunk.trimStart())
            start = end
            while (start < text.length && text[start].isWhitespace()) start++
        }
    }
}

private const val MAX_SCROLL_BLOCK_CHARACTERS = 1_600

internal fun BookInlineStyle.toSpanStyle(): SpanStyle = when (this) {
    BookInlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    BookInlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    BookInlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    BookInlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
    BookInlineStyle.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript)
}

internal fun List<ReadingPage>.pageIndexFor(chapterIndex: Int, characterOffset: Int): Int {
    val exact = indexOfFirst {
        it.chapterIndex == chapterIndex && characterOffset in it.startOffset..it.endOffset
    }
    if (exact >= 0) return exact
    val chapterStart = indexOfFirst { it.chapterIndex == chapterIndex }
    return if (chapterStart >= 0) chapterStart else 0
}

internal fun List<ScrollBlock>.blockIndexFor(chapterIndex: Int, characterOffset: Int): Int {
    val exact = indexOfFirst {
        it.chapterIndex == chapterIndex && characterOffset in it.startOffset..it.endOffset
    }
    if (exact >= 0) return exact
    val chapterStart = indexOfFirst { it.chapterIndex == chapterIndex }
    return if (chapterStart >= 0) chapterStart else 0
}

internal fun selectionFor(
    text: String,
    localWordStart: Int,
    localWordEnd: Int,
    chapterIndex: Int,
    baseOffset: Int,
): ReaderSelection? {
    if (text.isBlank()) return null
    var wordStart = localWordStart.coerceIn(0, text.length)
    var wordEnd = localWordEnd.coerceIn(wordStart, text.length)
    while (wordStart < wordEnd && !text[wordStart].isLetterOrDigit()) wordStart++
    while (wordEnd > wordStart && !text[wordEnd - 1].isLetterOrDigit()) wordEnd--
    if (wordStart >= wordEnd) return null

    var sentenceStart = wordStart
    while (sentenceStart > 0 && text[sentenceStart - 1] !in ".!?\n") sentenceStart--
    while (sentenceStart < wordStart && text[sentenceStart].isWhitespace()) sentenceStart++
    var sentenceEnd = wordEnd
    while (sentenceEnd < text.length && text[sentenceEnd] !in ".!?\n") sentenceEnd++
    if (sentenceEnd < text.length && text[sentenceEnd] in ".!?") sentenceEnd++

    return ReaderSelection(
        chapterIndex = chapterIndex,
        wordStart = baseOffset + wordStart,
        wordEnd = baseOffset + wordEnd,
        word = text.substring(wordStart, wordEnd),
        sentenceStart = baseOffset + sentenceStart,
        sentenceEnd = baseOffset + sentenceEnd,
        sentence = text.substring(sentenceStart, sentenceEnd).trim(),
    )
}
