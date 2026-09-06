package com.serein.reader.data

import kotlin.math.max

fun BookChapter.readingText(): String = paragraphs.joinToString("\n\n")

fun BookChapter.readingLength(): Int = when {
    paragraphs.isEmpty() -> 0
    else -> paragraphs.sumOf(String::length) + (paragraphs.size - 1) * 2
}

fun BookContent.totalCharacters(): Int = chapters.sumOf(BookChapter::readingLength).coerceAtLeast(1)

fun BookContent.progressAt(chapterIndex: Int, characterOffset: Int): Float {
    if (chapters.isEmpty()) return 0f
    val safeChapter = chapterIndex.coerceIn(0, chapters.lastIndex)
    val completed = chapters.take(safeChapter).sumOf(BookChapter::readingLength)
    val currentLength = chapters[safeChapter].readingLength()
    val offset = characterOffset.coerceIn(0, currentLength)
    return ((completed + offset).toFloat() / totalCharacters().toFloat()).coerceIn(0f, 1f)
}

fun BookContent.search(query: String, maxResults: Int = 60): List<BookSearchResult> {
    val needle = query.trim()
    if (needle.length < 2) return emptyList()
    return buildList {
        chapters.forEachIndexed { chapterIndex, chapter ->
            val text = chapter.readingText()
            var offset = 0
            while (size < maxResults) {
                val match = text.indexOf(needle, startIndex = offset, ignoreCase = true)
                if (match < 0) break
                val excerptStart = max(0, match - 54)
                val excerptEnd = (match + needle.length + 72).coerceAtMost(text.length)
                val prefix = if (excerptStart > 0) "…" else ""
                val suffix = if (excerptEnd < text.length) "…" else ""
                add(
                    BookSearchResult(
                        chapterIndex = chapterIndex,
                        characterOffset = match,
                        chapterTitle = chapter.title,
                        excerpt = prefix + text.substring(excerptStart, excerptEnd)
                            .replace(Regex("\\s+"), " ") + suffix,
                    )
                )
                offset = match + needle.length
            }
        }
    }
}
