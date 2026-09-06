package com.serein.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serein.reader.data.BookContent
import com.serein.reader.data.BookmarkRecord
import com.serein.reader.data.BookRecord
import com.serein.reader.data.BookRepository
import com.serein.reader.data.DictionaryRepository
import com.serein.reader.data.DictionaryState
import com.serein.reader.data.HighlightRecord
import com.serein.reader.data.LibraryPreferences
import com.serein.reader.data.ReadingLocation
import com.serein.reader.data.ReaderPreferences
import com.serein.reader.data.ReadingStatsSummary
import com.serein.reader.data.VocabularyRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class AppUiState(
    val books: List<BookRecord> = emptyList(),
    val currentBook: BookRecord? = null,
    val content: BookContent? = null,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val message: String? = null,
    val readerPreferences: ReaderPreferences = ReaderPreferences(),
    val bookmarks: List<BookmarkRecord> = emptyList(),
    val highlights: List<HighlightRecord> = emptyList(),
    val dictionaryState: DictionaryState = DictionaryState.Idle,
    val vocabulary: List<VocabularyRecord> = emptyList(),
    val libraryPreferences: LibraryPreferences = LibraryPreferences(),
    val readingStats: ReadingStatsSummary = ReadingStatsSummary(),
)

internal fun BookRecord.withReadingLocation(location: ReadingLocation): BookRecord = copy(
    chapterIndex = location.chapterIndex,
    characterOffset = location.characterOffset,
    scrollOffsetPx = location.scrollOffsetPx,
    progress = location.progress.coerceIn(0f, 1f),
    completedAt = if (location.progress >= 0.995f && completedAt == 0L) {
        System.currentTimeMillis()
    } else completedAt,
)

private data class PendingReadingLocation(
    val bookId: String,
    val location: ReadingLocation,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BookRepository(application)
    private var dictionaryRepository = DictionaryRepository(application)
    private val initialBooks = repository.loadBooks()
    private val _uiState = MutableStateFlow(
        AppUiState(
            books = initialBooks,
            readerPreferences = repository.loadReaderPreferences(),
            libraryPreferences = repository.loadLibraryPreferences(),
            readingStats = repository.readingStatsSummary(initialBooks),
            vocabulary = dictionaryRepository.loadVocabulary(),
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private var pendingReadingLocation: PendingReadingLocation? = null
    private var locationSaveJob: Job? = null
    private var lastReadingEventAt = 0L
    private var previousReadingLocation: ReadingLocation? = null
    private var pendingReadingMillis = 0L
    private var pendingPagesRead = 0

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateLibraryPreferences(transform: (LibraryPreferences) -> LibraryPreferences) {
        val updated = transform(_uiState.value.libraryPreferences)
        repository.saveLibraryPreferences(updated)
        _uiState.update { it.copy(libraryPreferences = updated) }
    }

    fun updateBookOrganization(book: BookRecord, collection: String, tags: List<String>) {
        val updatedBook = book.copy(
            collection = collection.trim(),
            tags = tags.map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase),
        )
        val books = _uiState.value.books.map { if (it.id == book.id) updatedBook else it }
        repository.saveBooks(books)
        _uiState.update { it.copy(books = books, message = "Updated “${book.title}”.") }
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }
            runCatching { withContext(Dispatchers.IO) { repository.importBook(uri) } }
                .onSuccess { book ->
                    val books = _uiState.value.books + book
                    withContext(Dispatchers.IO) { repository.saveBooks(books) }
                    _uiState.update {
                        it.copy(
                            books = books,
                            isLoading = false,
                            message = "Added “${book.title}” to your library.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = error.message ?: "That file could not be added.",
                        )
                    }
                }
        }
    }

    fun openBook(book: BookRecord) {
        locationSaveJob?.cancel()
        pendingReadingLocation = null
        lastReadingEventAt = System.currentTimeMillis()
        previousReadingLocation = null
        viewModelScope.launch {
            val openedBook = book.copy(lastOpenedAt = System.currentTimeMillis())
            val books = _uiState.value.books.map { if (it.id == book.id) openedBook else it }
            _uiState.update {
                it.copy(
                    books = books,
                    currentBook = openedBook,
                    content = null,
                    isLoading = true,
                    dictionaryState = DictionaryState.Idle,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.saveBooks(books)
                    repository.readContent(book)
                }
            }
                .onSuccess { content ->
                    _uiState.update {
                        it.copy(
                            content = content,
                            bookmarks = repository.loadBookmarks(book.id),
                            highlights = repository.loadHighlights(book.id),
                            isLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            currentBook = null,
                            content = null,
                            isLoading = false,
                            message = error.message ?: "This book could not be opened.",
                        )
                    }
                }
        }
    }

    fun closeReader() {
        val pending = pendingReadingLocation
        locationSaveJob?.cancel()
        locationSaveJob = null
        pendingReadingLocation = null
        captureReadingTime()
        val state = _uiState.value
        val updatedBook = pending
            ?.takeIf { it.bookId == state.currentBook?.id }
            ?.let { state.currentBook?.withReadingLocation(it.location) }
        val books = if (updatedBook == null) {
            state.books
        } else {
            state.books.map { if (it.id == updatedBook.id) updatedBook else it }
        }
        _uiState.update {
            it.copy(
                books = books,
                currentBook = null,
                content = null,
                bookmarks = emptyList(),
                highlights = emptyList(),
                dictionaryState = DictionaryState.Idle,
            )
        }
        if (updatedBook != null) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.saveBooks(books)
                flushReadingActivity(books)
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) { flushReadingActivity(books) }
        }
        previousReadingLocation = null
        lastReadingEventAt = 0L
    }

    fun updateReaderPreferences(transform: (ReaderPreferences) -> ReaderPreferences) {
        val updated = transform(_uiState.value.readerPreferences)
        repository.saveReaderPreferences(updated)
        _uiState.update { it.copy(readerPreferences = updated) }
    }

    fun saveReadingLocation(location: ReadingLocation) {
        val state = _uiState.value
        val book = state.currentBook ?: return
        captureReadingTime(location)
        val pending = PendingReadingLocation(book.id, location)
        pendingReadingLocation = pending
        locationSaveJob?.cancel()
        locationSaveJob = viewModelScope.launch {
            delay(READING_LOCATION_SAVE_DELAY_MS)
            val latestState = _uiState.value
            val sourceBook = latestState.books.firstOrNull { it.id == pending.bookId }
                ?: return@launch
            val updatedBook = sourceBook.withReadingLocation(pending.location)
            val books = latestState.books.map { if (it.id == pending.bookId) updatedBook else it }
            withContext(Dispatchers.IO) {
                repository.saveBooks(books)
                flushReadingActivity(books)
            }
        }
    }

    fun recordReadingHeartbeat() {
        if (_uiState.value.currentBook != null) captureReadingTime()
    }

    fun setReadingActive(active: Boolean) {
        if (_uiState.value.currentBook == null) return
        if (active) {
            lastReadingEventAt = System.currentTimeMillis()
        } else {
            captureReadingTime()
            lastReadingEventAt = 0L
            val books = _uiState.value.books
            viewModelScope.launch(Dispatchers.IO) { flushReadingActivity(books) }
        }
    }

    fun toggleBookmark(location: ReadingLocation, excerpt: String) {
        val book = _uiState.value.currentBook ?: return
        val existing = _uiState.value.bookmarks.firstOrNull {
            it.chapterIndex == location.chapterIndex &&
                kotlin.math.abs(it.characterOffset - location.characterOffset) < 12
        }
        if (existing != null) {
            repository.removeBookmark(existing.id)
            _uiState.update { state ->
                state.copy(bookmarks = state.bookmarks.filterNot { it.id == existing.id })
            }
        } else {
            val bookmark = BookmarkRecord(
                id = UUID.randomUUID().toString(),
                bookId = book.id,
                chapterIndex = location.chapterIndex,
                characterOffset = location.characterOffset,
                excerpt = excerpt.trim().take(150),
                createdAt = System.currentTimeMillis(),
            )
            repository.saveBookmark(bookmark)
            _uiState.update { it.copy(bookmarks = listOf(bookmark) + it.bookmarks) }
        }
    }

    fun removeBookmark(bookmark: BookmarkRecord) {
        repository.removeBookmark(bookmark.id)
        _uiState.update { state ->
            state.copy(bookmarks = state.bookmarks.filterNot { it.id == bookmark.id })
        }
    }

    fun addHighlight(chapterIndex: Int, startOffset: Int, endOffset: Int, text: String) {
        val book = _uiState.value.currentBook ?: return
        if (startOffset >= endOffset || text.isBlank()) return
        val highlight = HighlightRecord(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            chapterIndex = chapterIndex,
            startOffset = startOffset,
            endOffset = endOffset,
            text = text.trim().take(500),
            createdAt = System.currentTimeMillis(),
        )
        repository.saveHighlight(highlight)
        _uiState.update { it.copy(highlights = listOf(highlight) + it.highlights) }
    }

    fun removeHighlight(highlight: HighlightRecord) {
        repository.removeHighlight(highlight.id)
        _uiState.update { state ->
            state.copy(highlights = state.highlights.filterNot { it.id == highlight.id })
        }
    }

    fun updateHighlightNote(highlight: HighlightRecord, note: String) {
        repository.updateHighlightNote(highlight.id, note)
        _uiState.update { state ->
            state.copy(highlights = state.highlights.map {
                if (it.id == highlight.id) it.copy(note = note.trim()) else it
            })
        }
    }

    fun exportAnnotations(markdown: Boolean): String? {
        val book = _uiState.value.currentBook ?: return null
        return repository.exportAnnotations(book, markdown)
    }

    fun lookupDictionary(word: String) {
        val cleaned = word.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (cleaned.isBlank()) return
        _uiState.update { it.copy(dictionaryState = DictionaryState.Loading(cleaned)) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { dictionaryRepository.lookup(cleaned) } }
                .onSuccess { entry ->
                    _uiState.update {
                        it.copy(
                            dictionaryState = DictionaryState.Found(entry),
                            vocabulary = dictionaryRepository.loadVocabulary(),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            dictionaryState = DictionaryState.Error(
                                cleaned,
                                error.message ?: "This word could not be defined offline.",
                            )
                        )
                    }
                }
        }
    }

    fun clearDictionary() {
        _uiState.update { it.copy(dictionaryState = DictionaryState.Idle) }
    }

    fun removeBook(book: BookRecord) {
        repository.removeBook(book)
        val books = _uiState.value.books.filterNot { it.id == book.id }
        repository.saveBooks(books)
        _uiState.update {
            it.copy(
                books = books,
                readingStats = repository.readingStatsSummary(books),
                message = "Removed “${book.title}”.",
            )
        }
    }

    fun createBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    flushReadingActivity(_uiState.value.books)
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        repository.createBackup(it, _uiState.value.books)
                    } ?: error("The backup file could not be opened.")
                }
            }.onSuccess {
                _uiState.update { it.copy(isLoading = false, message = "Serein backup saved.") }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, message = error.message ?: "Backup failed.") }
            }
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(repository::restoreBackup)
                        ?: error("The backup file could not be opened.")
                }
            }.onSuccess { books ->
                dictionaryRepository = DictionaryRepository(getApplication())
                _uiState.update {
                    it.copy(
                        books = books,
                        readerPreferences = repository.loadReaderPreferences(),
                        libraryPreferences = repository.loadLibraryPreferences(),
                        readingStats = repository.readingStatsSummary(books),
                        vocabulary = dictionaryRepository.loadVocabulary(),
                        isLoading = false,
                        message = "Library restored from backup.",
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, message = error.message ?: "Restore failed.") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        locationSaveJob?.cancel()
        val pending = pendingReadingLocation
        if (pending != null) {
            val state = _uiState.value
            val sourceBook = state.books.firstOrNull { it.id == pending.bookId }
            if (sourceBook != null) {
                val updatedBook = sourceBook.withReadingLocation(pending.location)
                repository.saveBooks(
                    state.books.map { if (it.id == pending.bookId) updatedBook else it }
                )
            }
        }
        captureReadingTime()
        flushReadingActivity(_uiState.value.books)
        super.onCleared()
    }

    private fun captureReadingTime(location: ReadingLocation? = null) {
        val now = System.currentTimeMillis()
        if (lastReadingEventAt > 0L) {
            val elapsed = now - lastReadingEventAt
            if (elapsed > 0L) pendingReadingMillis += elapsed.coerceAtMost(MAX_ACTIVE_READING_GAP_MS)
        }
        if (location != null) {
            val previous = previousReadingLocation
            if (previous != null && (
                    previous.chapterIndex != location.chapterIndex ||
                        kotlin.math.abs(previous.characterOffset - location.characterOffset) >= 80
                    )) {
                pendingPagesRead++
            }
            previousReadingLocation = location
        }
        lastReadingEventAt = now
    }

    @Synchronized
    private fun flushReadingActivity(books: List<BookRecord>) {
        val millis = pendingReadingMillis
        val pages = pendingPagesRead
        pendingReadingMillis = 0L
        pendingPagesRead = 0
        repository.addReadingActivity(millis, pages)
        _uiState.update { it.copy(readingStats = repository.readingStatsSummary(books)) }
    }

    private companion object {
        const val READING_LOCATION_SAVE_DELAY_MS = 700L
        const val MAX_ACTIVE_READING_GAP_MS = 120_000L
    }
}
