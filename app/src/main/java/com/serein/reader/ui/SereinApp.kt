package com.serein.reader.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serein.reader.AppViewModel
import com.serein.reader.data.BookRecord
import kotlinx.coroutines.delay

internal fun readerTransitionKey(book: BookRecord?): String? = book?.id

@Composable
fun SereinApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1100)
        showSplash = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importBook)
    }
    val backupCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::createBackup) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restoreBackup)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    BackHandler(enabled = state.currentBook != null, onBack = viewModel::closeReader)

    SereinTheme(state.readerPreferences) {
        Crossfade(targetState = showSplash, label = "splash") { splashing ->
            if (splashing) {
                SplashScreen()
                return@Crossfade
            }
            Box(Modifier.fillMaxSize()) {
                Crossfade(
                    targetState = readerTransitionKey(state.currentBook),
                    label = "library-reader",
                ) { currentBookId ->
                    if (currentBookId == null) {
                        LibraryScreen(
                            books = state.books,
                            searchQuery = state.searchQuery,
                            preferences = state.libraryPreferences,
                            readingStats = state.readingStats,
                            isImporting = state.isLoading,
                            onSearchQueryChange = viewModel::setSearchQuery,
                            onAddEpub = {
                                picker.launch(
                                    arrayOf(
                                        "application/epub+zip",
                                        "application/pdf",
                                        "application/octet-stream",
                                    )
                                )
                            },
                            onOpenBook = viewModel::openBook,
                            onRemoveBook = viewModel::removeBook,
                            onPreferencesChange = viewModel::updateLibraryPreferences,
                            onOrganizeBook = viewModel::updateBookOrganization,
                            onBackup = { backupCreator.launch("Serein-library-backup.zip") },
                            onRestore = { backupPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                        )
                    } else {
                        val currentBook = state.currentBook?.takeIf { it.id == currentBookId }
                            ?: state.books.first { it.id == currentBookId }
                        ReaderScreen(
                            book = currentBook,
                            content = state.content,
                            isLoading = state.isLoading,
                            preferences = state.readerPreferences,
                            bookmarks = state.bookmarks,
                            highlights = state.highlights,
                            dictionaryState = state.dictionaryState,
                            vocabulary = state.vocabulary,
                            onBack = viewModel::closeReader,
                            onPreferencesChange = viewModel::updateReaderPreferences,
                            onLocationChange = viewModel::saveReadingLocation,
                            onReadingHeartbeat = viewModel::recordReadingHeartbeat,
                            onReadingActiveChange = viewModel::setReadingActive,
                            onToggleBookmark = viewModel::toggleBookmark,
                            onRemoveBookmark = viewModel::removeBookmark,
                            onAddHighlight = viewModel::addHighlight,
                            onRemoveHighlight = viewModel::removeHighlight,
                            onUpdateHighlightNote = viewModel::updateHighlightNote,
                            onExportAnnotations = viewModel::exportAnnotations,
                            onDictionaryLookup = viewModel::lookupDictionary,
                            onDictionaryDismiss = viewModel::clearDictionary,
                        )
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
