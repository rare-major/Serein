package com.serein.reader

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.serein.reader.data.BookChapter
import com.serein.reader.data.BookContent
import com.serein.reader.data.BookRecord
import com.serein.reader.data.DictionaryEntry
import com.serein.reader.data.DictionaryMeaning
import com.serein.reader.data.DictionaryState
import com.serein.reader.data.ReadingMode
import com.serein.reader.data.ReaderPreferences
import com.serein.reader.data.ReaderTheme
import com.serein.reader.ui.ReaderScreen
import com.serein.reader.ui.SereinTheme
import com.serein.reader.ui.LibraryScreen
import com.serein.reader.ui.ReaderSettingsSheet
import com.serein.reader.ui.DictionarySheet

@PreviewTest
@Preview(
    name = "Serein reader",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
)
@Composable
fun ReaderPreviewScreenshot() {
    val preferences = ReaderPreferences(bionicReading = true)
    SereinTheme(preferences) {
        ReaderScreen(
            book = BookRecord(
                id = "preview",
                title = "A Room of One’s Own",
                author = "Virginia Woolf",
                progress = 0.37f,
                isDemo = true,
            ),
            content = BookContent(
                chapters = listOf(
                    BookChapter(
                        title = "Chapter Three",
                        paragraphs = listOf(
                            "If a woman is to write fiction, she must have money and a room of her own. If she is to write good fiction, these two things are absolutely essential.",
                            "We think back through our mothers if we are women. I do not wish to be tedious about my mother. I think of her only because she was for me typical of the average woman of my time.",
                        ),
                    ),
                    BookChapter("Chapter Four", listOf("Books continue each other.")),
                )
            ),
            isLoading = false,
            preferences = preferences,
            onBack = {},
            onPreferencesChange = {},
            initialSettingsOpen = false,
        )
    }
}

@PreviewTest
@Preview(
    name = "Serein library",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
)
@Composable
fun LibraryPreviewScreenshot() {
    val preferences = ReaderPreferences()
    SereinTheme(preferences) {
        LibraryScreen(
            books = listOf(
                BookRecord(
                    id = "preview",
                    title = "A Room of One’s Own",
                    author = "Virginia Woolf",
                    progress = 0.37f,
                    isDemo = true,
                ),
            ),
            searchQuery = "",
            isImporting = false,
            onSearchQueryChange = {},
            onAddEpub = {},
            onOpenBook = {},
            onRemoveBook = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Serein reader settings",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
)
@Composable
fun ReaderSettingsPreviewScreenshot() {
    val preferences = ReaderPreferences(bionicReading = true)
    SereinTheme(preferences) {
        Box(Modifier.fillMaxSize()) {
            ReaderScreen(
                book = BookRecord(
                    id = "settings-preview",
                    title = "A Room of One’s Own",
                    author = "Virginia Woolf",
                    progress = 0.37f,
                    isDemo = true,
                ),
                content = BookContent(
                    listOf(
                        BookChapter(
                            "Chapter Three",
                            listOf(
                                "If a woman is to write fiction, she must have money and a room of her own. If she is to write good fiction, these two things are absolutely essential.",
                                "A woman must have money and a room of her own if she is to write fiction; but, alas! how few women have either money or rooms of their own.",
                                "We think back through our mothers if we are women.",
                            ),
                        )
                    )
                ),
                isLoading = false,
                preferences = preferences,
                onBack = {},
                onPreferencesChange = {},
                initialSettingsOpen = false,
            )
            ReaderSettingsSheet(
                preferences = preferences,
                onDismiss = {},
                onChange = {},
                inline = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .height(430.dp),
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Serein dark reader",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
)
@Composable
fun DarkReaderPreviewScreenshot() {
    val preferences = ReaderPreferences(
        theme = ReaderTheme.DARK,
        bionicReading = true,
    )
    SereinTheme(preferences) {
        ReaderScreen(
            book = BookRecord(
                id = "dark-preview",
                title = "A Room of One’s Own",
                author = "Virginia Woolf",
                progress = 0.37f,
                isDemo = true,
            ),
            content = BookContent(
                chapters = listOf(
                    BookChapter(
                        title = "Chapter Three",
                        paragraphs = listOf(
                            "If a woman is to write fiction, she must have money and a room of her own. If she is to write good fiction, these two things are absolutely essential.",
                            "We think back through our mothers if we are women.",
                        ),
                    ),
                    BookChapter("Chapter Four", listOf("Books continue each other.")),
                )
            ),
            isLoading = false,
            preferences = preferences,
            onBack = {},
            onPreferencesChange = {},
            initialSettingsOpen = false,
        )
    }
}

@PreviewTest
@Preview(
    name = "Serein scrolling reader",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
)
@Composable
fun ScrollingReaderPreviewScreenshot() {
    val preferences = ReaderPreferences(readingMode = ReadingMode.SCROLL)
    SereinTheme(preferences) {
        ReaderScreen(
            book = BookRecord("scroll-preview", "A Room of One’s Own", "Virginia Woolf", isDemo = true),
            content = previewContent(),
            isLoading = false,
            preferences = preferences,
            onBack = {},
            onPreferencesChange = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Serein dictionary",
    widthDp = 390,
    heightDp = 844,
    showBackground = true,
)
@Composable
fun DictionaryPreviewScreenshot() {
    val preferences = ReaderPreferences()
    SereinTheme(preferences) {
        Box(Modifier.fillMaxSize()) {
            ReaderScreen(
                book = BookRecord("dictionary-preview", "A Room of One’s Own", "Virginia Woolf", isDemo = true),
                content = previewContent(),
                isLoading = false,
                preferences = preferences,
                onBack = {},
                onPreferencesChange = {},
            )
            DictionarySheet(
                state = DictionaryState.Found(
                    DictionaryEntry(
                        word = "serene",
                        phonetic = "/səˈriːn/",
                        meanings = listOf(
                            DictionaryMeaning(
                                partOfSpeech = "adjective",
                                definitions = listOf(
                                    "Calm, peaceful, and untroubled.",
                                    "Clear and free of storms or unpleasant change.",
                                ),
                            )
                        ),
                    )
                ),
                onDismiss = {},
                inline = true,
                modifier = Modifier.align(Alignment.BottomCenter).height(390.dp),
            )
        }
    }
}

private fun previewContent() = BookContent(
    listOf(
        BookChapter(
            "Chapter Three",
            listOf(
                "If a woman is to write fiction, she must have money and a room of her own. If she is to write good fiction, these two things are absolutely essential.",
                "We think back through our mothers if we are women. I do not wish to be tedious about my mother. I think of her only because she was for me typical of the average woman of my time.",
                "She had, in fact, nothing except a lively intelligence and a delight in words.",
            ),
        ),
        BookChapter("Chapter Four", listOf("Books continue each other, quietly.")),
    )
)
