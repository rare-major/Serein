package com.serein.reader.data

data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val filePath: String? = null,
    val coverPath: String? = null,
    val progress: Float = 0f,
    val chapterIndex: Int = 0,
    val characterOffset: Int = 0,
    val scrollOffsetPx: Int = 0,
    val lastOpenedAt: Long = 0L,
    val isDemo: Boolean = false,
    val collection: String = "",
    val tags: List<String> = emptyList(),
    val completedAt: Long = 0L,
)

enum class BookBlockKind { PARAGRAPH, HEADING, QUOTE, LIST_ITEM, IMAGE, SEPARATOR }

enum class BookInlineStyle { BOLD, ITALIC, UNDERLINE, CODE, SUPERSCRIPT }

data class BookInlineSpan(
    val start: Int,
    val end: Int,
    val style: BookInlineStyle,
    val target: String? = null,
)

data class BookBlock(
    val text: String = "",
    val kind: BookBlockKind = BookBlockKind.PARAGRAPH,
    val imagePath: String? = null,
    val altText: String = "",
    val inlineSpans: List<BookInlineSpan> = emptyList(),
)

data class BookChapter(
    val title: String,
    val paragraphs: List<String>,
    val blocks: List<BookBlock> = paragraphs.map { BookBlock(it) },
)

data class BookContent(
    val chapters: List<BookChapter>,
)

enum class ReaderTheme { LIGHT, SEPIA, DARK }

enum class ReadingMode { PAGED, SCROLL }

enum class ReaderOrientation(val label: String) {
    SYSTEM("Auto-rotate"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}

enum class ReaderFont(val label: String) {
    LORA("Lora"),
    LITERATA("Literata"),
    ATKINSON("Atkinson"),
}

data class ReaderPreferences(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val font: ReaderFont = ReaderFont.LORA,
    val textSize: Int = 18,
    val lineHeight: Float = 1.65f,
    val bionicReading: Boolean = false,
    val readingMode: ReadingMode = ReadingMode.PAGED,
    /** -1 follows the system brightness; values from 0.05 to 1 override it. */
    val brightness: Float = -1f,
    val orientation: ReaderOrientation = ReaderOrientation.SYSTEM,
    val volumePageTurns: Boolean = false,
    val wideTapZones: Boolean = true,
    val keepScreenAwake: Boolean = true,
)

enum class LibrarySort(val label: String) {
    RECENT("Recent"),
    TITLE("Title"),
    AUTHOR("Author"),
    PROGRESS("Progress"),
}

enum class LibraryFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    READING("Reading"),
    FINISHED("Finished"),
}

data class LibraryPreferences(
    val sort: LibrarySort = LibrarySort.RECENT,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val collection: String = "",
)

data class BookmarkRecord(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val characterOffset: Int,
    val excerpt: String,
    val createdAt: Long,
)

data class HighlightRecord(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val createdAt: Long,
    val note: String = "",
)

data class ReadingLocation(
    val chapterIndex: Int,
    val characterOffset: Int,
    val scrollOffsetPx: Int = 0,
    val progress: Float,
)

data class BookSearchResult(
    val chapterIndex: Int,
    val characterOffset: Int,
    val chapterTitle: String,
    val excerpt: String,
)

data class DictionaryEntry(
    val word: String,
    val phonetic: String?,
    val meanings: List<DictionaryMeaning>,
    val isOffline: Boolean = false,
    val source: String = "Open English WordNet 2025",
)

data class DictionaryMeaning(
    val partOfSpeech: String,
    val definitions: List<String>,
    val examples: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
)

data class VocabularyRecord(
    val word: String,
    val phonetic: String?,
    val definition: String,
    val lookedUpAt: Long,
)

data class DailyReadingStats(
    val date: String,
    val readingMillis: Long = 0L,
    val pagesRead: Int = 0,
)

data class ReadingStatsSummary(
    val todayMinutes: Int = 0,
    val todayPages: Int = 0,
    val currentStreak: Int = 0,
    val totalMinutes: Int = 0,
    val completedBooks: Int = 0,
    val recentDays: List<DailyReadingStats> = emptyList(),
)

sealed interface DictionaryState {
    data object Idle : DictionaryState
    data class Loading(val word: String) : DictionaryState
    data class Found(val entry: DictionaryEntry) : DictionaryState
    data class Error(val word: String, val message: String) : DictionaryState
}

data class ParsedEpub(
    val title: String,
    val author: String,
    val coverPath: String?,
)
