package com.serein.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BookRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("serein_library", Context.MODE_PRIVATE)
    private val booksDirectory = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDirectory = File(context.filesDir, "covers").apply { mkdirs() }
    private val assetsDirectory = File(context.filesDir, "epub_assets").apply { mkdirs() }
    private val contentCache = object : LinkedHashMap<String, BookContent>(3, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookContent>?): Boolean =
            size > 3
    }

    fun loadBooks(): List<BookRecord> {
        val stored = preferences.getString(KEY_BOOKS, null) ?: return listOf(demoBook())
        return runCatching {
            val json = JSONArray(stored)
            buildList {
                for (index in 0 until json.length()) {
                    runCatching { normalizeStoredBook(json.getJSONObject(index).toBookRecord()) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }.getOrElse { listOf(demoBook()) }.ifEmpty { listOf(demoBook()) }
    }

    fun importBook(uri: Uri): BookRecord {
        val displayName = queryDisplayName(uri) ?: "Imported book"
        val id = UUID.randomUUID().toString()
        val target = bookFile(id)
        val staging = File(context.cacheDir, "import-$id")

        return try {
            val stagingLimit = storageBudget(context.cacheDir, MAX_IMPORT_FILE_BYTES, MIN_FREE_SPACE_BYTES)
            require(stagingLimit > 0L) { "There is not enough free space to add this file." }
            context.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output ->
                    copyWithLimit(input, output, stagingLimit, "The selected file")
                }
            } ?: error("The selected file could not be opened.")

            val limit = storageBudget(booksDirectory, MAX_IMPORT_FILE_BYTES, MIN_FREE_SPACE_BYTES)
            require(limit > 0L) { "There is not enough free space to add this book." }
            when (detectFileKind(staging)) {
                FileKind.EPUB -> {
                    require(staging.length() <= limit) { "This EPUB exceeds Serein's safety limit." }
                    moveIntoPlace(staging, target)
                }
                FileKind.PDF -> {
                    PdfToEpubConverter.convert(context, staging, target, displayName.substringBeforeLast('.'))
                }
                FileKind.UNKNOWN -> error("This does not appear to be a valid EPUB or PDF file.")
            }
            val parsed = EpubParser.parseMetadata(target, coversDirectory)
            BookRecord(
                id = id,
                title = parsed.title.ifBlank { displayName.substringBeforeLast('.') },
                author = parsed.author,
                filePath = target.absolutePath,
                coverPath = parsed.coverPath,
            )
        } catch (exception: Exception) {
            target.delete()
            deleteBookCovers(id)
            throw IllegalArgumentException(
                exception.message ?: "This does not appear to be a valid EPUB or PDF file."
            )
        } finally {
            staging.delete()
        }
    }

    private enum class FileKind { EPUB, PDF, UNKNOWN }

    private fun detectFileKind(file: File): FileKind {
        val header = ByteArray(5)
        val read = file.inputStream().use { it.read(header) }
        if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
        ) {
            return FileKind.EPUB
        }
        if (read >= 5 && String(header, 0, 5, Charsets.US_ASCII) == "%PDF-") {
            return FileKind.PDF
        }
        return FileKind.UNKNOWN
    }

    fun readContent(book: BookRecord): BookContent {
        synchronized(contentCache) { contentCache[book.id] }?.let { return it }
        val content = if (book.isDemo) {
            demoContent()
        } else {
            requireValidBookIdentity(book.id, false)
            val file = bookFile(book.id)
            require(file.exists()) { "The EPUB file is no longer available." }
            EpubParser.readContent(file, assetDirectory(book.id))
        }
        synchronized(contentCache) { contentCache[book.id] = content }
        return content
    }

    fun saveBooks(books: List<BookRecord>) {
        val json = JSONArray()
        books.forEach { book -> json.put(book.toJson()) }
        preferences.edit().putString(KEY_BOOKS, json.toString()).apply()
    }

    fun loadLibraryPreferences(): LibraryPreferences = LibraryPreferences(
        sort = enumPreference(KEY_LIBRARY_SORT, LibrarySort.RECENT),
        filter = enumPreference(KEY_LIBRARY_FILTER, LibraryFilter.ALL),
        collection = preferences.getString(KEY_LIBRARY_COLLECTION, "").orEmpty(),
    )

    fun saveLibraryPreferences(value: LibraryPreferences) {
        preferences.edit()
            .putString(KEY_LIBRARY_SORT, value.sort.name)
            .putString(KEY_LIBRARY_FILTER, value.filter.name)
            .putString(KEY_LIBRARY_COLLECTION, value.collection)
            .apply()
    }

    fun loadBookmarks(bookId: String): List<BookmarkRecord> = loadJsonArray(KEY_BOOKMARKS) { json ->
        BookmarkRecord(
            id = json.getString("id"),
            bookId = json.getString("bookId"),
            chapterIndex = json.optInt("chapterIndex"),
            characterOffset = json.optInt("characterOffset"),
            excerpt = json.optString("excerpt"),
            createdAt = json.optLong("createdAt"),
        )
    }.filter { it.bookId == bookId }.sortedByDescending { it.createdAt }

    fun saveBookmark(bookmark: BookmarkRecord) {
        val all = loadJsonArray(KEY_BOOKMARKS, ::bookmarkFromJson)
            .filterNot { it.id == bookmark.id } + bookmark
        saveJsonArray(KEY_BOOKMARKS, all.map(::bookmarkToJson))
    }

    fun removeBookmark(bookmarkId: String) {
        val all = loadJsonArray(KEY_BOOKMARKS, ::bookmarkFromJson).filterNot { it.id == bookmarkId }
        saveJsonArray(KEY_BOOKMARKS, all.map(::bookmarkToJson))
    }

    fun loadHighlights(bookId: String): List<HighlightRecord> = loadJsonArray(
        KEY_HIGHLIGHTS,
        ::highlightFromJson,
    ).filter { it.bookId == bookId }.sortedByDescending { it.createdAt }

    fun saveHighlight(highlight: HighlightRecord) {
        val all = loadJsonArray(KEY_HIGHLIGHTS, ::highlightFromJson)
            .filterNot { it.id == highlight.id } + highlight
        saveJsonArray(KEY_HIGHLIGHTS, all.map(::highlightToJson))
    }

    fun updateHighlightNote(highlightId: String, note: String) {
        val all = loadJsonArray(KEY_HIGHLIGHTS, ::highlightFromJson).map {
            if (it.id == highlightId) it.copy(note = note.trim()) else it
        }
        saveJsonArray(KEY_HIGHLIGHTS, all.map(::highlightToJson))
    }

    fun exportAnnotations(book: BookRecord, markdown: Boolean): String {
        val bookmarks = loadBookmarks(book.id).sortedBy { it.createdAt }
        val highlights = loadHighlights(book.id).sortedBy { it.createdAt }
        val heading = if (markdown) "# ${book.title}\n\n*${book.author}*" else "${book.title}\n${book.author}"
        val body = buildString {
            append(heading)
            append("\n\n")
            if (highlights.isNotEmpty()) {
                append(if (markdown) "## Highlights\n\n" else "HIGHLIGHTS\n\n")
                highlights.forEach { item ->
                    append(if (markdown) "> ${item.text.replace("\n", " ")}\n" else "“${item.text.replace("\n", " ")}”\n")
                    append(if (markdown) "\n_Chapter ${item.chapterIndex + 1}_\n" else "Chapter ${item.chapterIndex + 1}\n")
                    if (item.note.isNotBlank()) append(if (markdown) "\nNote: ${item.note}\n" else "Note: ${item.note}\n")
                    append("\n")
                }
            }
            if (bookmarks.isNotEmpty()) {
                append(if (markdown) "## Bookmarks\n\n" else "BOOKMARKS\n\n")
                bookmarks.forEach { append(if (markdown) "- ${it.excerpt}\n" else "• ${it.excerpt}\n") }
            }
        }
        return body.trimEnd() + "\n"
    }

    fun removeHighlight(highlightId: String) {
        val all = loadJsonArray(KEY_HIGHLIGHTS, ::highlightFromJson)
            .filterNot { it.id == highlightId }
        saveJsonArray(KEY_HIGHLIGHTS, all.map(::highlightToJson))
    }

    fun removeBook(book: BookRecord) {
        synchronized(contentCache) { contentCache.remove(book.id) }
        if (!book.isDemo) {
            requireValidBookIdentity(book.id, false)
            bookFile(book.id).delete()
            deleteBookCovers(book.id)
            assetDirectory(book.id).deleteRecursively()
        }
        saveJsonArray(
            KEY_BOOKMARKS,
            loadJsonArray(KEY_BOOKMARKS, ::bookmarkFromJson)
                .filterNot { it.bookId == book.id }
                .map(::bookmarkToJson),
        )
        saveJsonArray(
            KEY_HIGHLIGHTS,
            loadJsonArray(KEY_HIGHLIGHTS, ::highlightFromJson)
                .filterNot { it.bookId == book.id }
                .map(::highlightToJson),
        )
    }

    fun loadReaderPreferences(): ReaderPreferences = ReaderPreferences(
        theme = runCatching {
            ReaderTheme.valueOf(
                preferences.getString(KEY_THEME, ReaderTheme.LIGHT.name).orEmpty()
            )
        }.getOrDefault(ReaderTheme.LIGHT),
        font = runCatching {
            ReaderFont.valueOf(
                preferences.getString(KEY_FONT, ReaderFont.LORA.name).orEmpty()
            )
        }.getOrDefault(ReaderFont.LORA),
        textSize = preferences.getInt(KEY_TEXT_SIZE, 18).coerceIn(14, 30),
        lineHeight = preferences.getFloat(KEY_LINE_HEIGHT, 1.65f).coerceIn(1.3f, 2.0f),
        bionicReading = preferences.getBoolean(KEY_BIONIC, false),
        readingMode = runCatching {
            ReadingMode.valueOf(
                preferences.getString(KEY_READING_MODE, ReadingMode.PAGED.name).orEmpty()
            )
        }.getOrDefault(ReadingMode.PAGED),
        brightness = preferences.getFloat(KEY_BRIGHTNESS, -1f).let {
            if (it < 0f) -1f else it.coerceIn(0.05f, 1f)
        },
        orientation = enumPreference(KEY_ORIENTATION, ReaderOrientation.SYSTEM),
        volumePageTurns = preferences.getBoolean(KEY_VOLUME_PAGE_TURNS, false),
        wideTapZones = preferences.getBoolean(KEY_WIDE_TAP_ZONES, true),
        keepScreenAwake = preferences.getBoolean(KEY_KEEP_SCREEN_AWAKE, true),
    )

    fun saveReaderPreferences(readerPreferences: ReaderPreferences) {
        preferences.edit()
            .putString(KEY_THEME, readerPreferences.theme.name)
            .putString(KEY_FONT, readerPreferences.font.name)
            .putInt(KEY_TEXT_SIZE, readerPreferences.textSize)
            .putFloat(KEY_LINE_HEIGHT, readerPreferences.lineHeight)
            .putBoolean(KEY_BIONIC, readerPreferences.bionicReading)
            .putString(KEY_READING_MODE, readerPreferences.readingMode.name)
            .putFloat(KEY_BRIGHTNESS, readerPreferences.brightness)
            .putString(KEY_ORIENTATION, readerPreferences.orientation.name)
            .putBoolean(KEY_VOLUME_PAGE_TURNS, readerPreferences.volumePageTurns)
            .putBoolean(KEY_WIDE_TAP_ZONES, readerPreferences.wideTapZones)
            .putBoolean(KEY_KEEP_SCREEN_AWAKE, readerPreferences.keepScreenAwake)
            .apply()
    }

    fun addReadingActivity(durationMillis: Long, pagesRead: Int) {
        if (durationMillis <= 0L && pagesRead <= 0) return
        val today = LocalDate.now().toString()
        val all = loadReadingStats().associateBy { it.date }.toMutableMap()
        val current = all[today] ?: DailyReadingStats(today)
        all[today] = current.copy(
            readingMillis = current.readingMillis + durationMillis.coerceIn(0L, 120_000L),
            pagesRead = current.pagesRead + pagesRead.coerceAtLeast(0),
        )
        val cutoff = LocalDate.now().minusDays(364)
        saveJsonArray(
            KEY_READING_STATS,
            all.values.filter { runCatching { LocalDate.parse(it.date) >= cutoff }.getOrDefault(false) }
                .sortedBy { it.date }
                .map(::readingStatsToJson),
        )
    }

    fun loadReadingStats(): List<DailyReadingStats> = loadJsonArray(
        KEY_READING_STATS,
        ::readingStatsFromJson,
    ).sortedByDescending { it.date }

    fun readingStatsSummary(books: List<BookRecord>): ReadingStatsSummary {
        val stats = loadReadingStats()
        val today = LocalDate.now()
        val byDate = stats.associateBy { it.date }
        var streak = 0
        var cursor = today
        while ((byDate[cursor.toString()]?.readingMillis ?: 0L) > 0L) {
            streak++
            cursor = cursor.minusDays(1)
        }
        val todayStats = byDate[today.toString()] ?: DailyReadingStats(today.toString())
        return ReadingStatsSummary(
            todayMinutes = (todayStats.readingMillis / 60_000L).toInt(),
            todayPages = todayStats.pagesRead,
            currentStreak = streak,
            totalMinutes = (stats.sumOf { it.readingMillis } / 60_000L).toInt(),
            completedBooks = books.count { it.progress >= 0.995f },
            recentDays = (6 downTo 0).map { days ->
                val date = today.minusDays(days.toLong()).toString()
                byDate[date] ?: DailyReadingStats(date)
            },
        )
    }

    fun createBackup(output: OutputStream, books: List<BookRecord>) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val manifest = JSONObject()
                .put("schemaVersion", BACKUP_SCHEMA_VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put("books", JSONArray().apply { books.forEach { put(it.toBackupJson()) } })
                .put("bookmarks", jsonArrayValue(KEY_BOOKMARKS))
                .put("highlights", jsonArrayValue(KEY_HIGHLIGHTS))
                .put("readingStats", jsonArrayValue(KEY_READING_STATS))
                .put("readerPreferences", readerPreferencesToJson(loadReaderPreferences()))
                .put("libraryPreferences", libraryPreferencesToJson(loadLibraryPreferences()))
                .put("vocabulary", dictionaryPreferenceArray("vocabulary_history"))
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            books.filterNot { it.isDemo }.forEach { book ->
                requireValidBookIdentity(book.id, false)
                bookFile(book.id).takeIf(File::isFile)?.let { file ->
                    zipFile(zip, file, "books/${book.id}.epub")
                }
                safeStoredCover(book)?.takeIf(File::isFile)?.let { file ->
                    zipFile(zip, file, "covers/${book.id}.${file.extension.ifBlank { "jpg" }}")
                }
            }
        }
    }

    fun restoreBackup(input: InputStream): List<BookRecord> {
        synchronized(contentCache) { contentCache.clear() }
        val temporary = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val stagedFiles = mutableListOf<File>()
        try {
            val expandedLimit = storageBudget(
                context.cacheDir,
                MAX_BACKUP_EXPANDED_BYTES,
                MIN_FREE_SPACE_BYTES,
            )
            require(expandedLimit > 0L) { "There is not enough free space to restore this backup." }
            val expandedBudget = ByteBudget(expandedLimit, "The backup's extracted content")
            val compressed = LimitedInputStream(input, MAX_BACKUP_FILE_BYTES, "The backup file")
            val entryNames = mutableSetOf<String>()
            var entryCount = 0
            ZipInputStream(BufferedInputStream(compressed)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    require(entryCount <= MAX_BACKUP_ENTRIES) { "The backup contains too many files." }
                    require(entryNames.add(entry.name)) { "The backup contains a duplicate file entry." }
                    val entryLimit = backupEntryLimit(entry.name, entry.isDirectory)
                    val output = safeRestoreFile(temporary, entry.name)
                    if (!entry.isDirectory) {
                        output.parentFile?.mkdirs()
                        try {
                            output.outputStream().use { destination ->
                                copyWithLimit(
                                    zip,
                                    destination,
                                    entryLimit,
                                    "Backup entry ${entry.name}",
                                    expandedBudget,
                                )
                            }
                        } catch (exception: Exception) {
                            output.delete()
                            throw exception
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val manifestFile = safeRestoreFile(temporary, BACKUP_MANIFEST)
            require(manifestFile.isFile) { "This is not a Serein backup." }
            val manifestBytes = manifestFile.inputStream().use {
                readBytesWithLimit(it, MAX_BACKUP_MANIFEST_BYTES, "The backup manifest")
            }
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            require(manifest.optInt("schemaVersion") in 1..BACKUP_SCHEMA_VERSION) {
                "This backup was created by a newer version of Serein."
            }
            val bookmarks = validatedManifestArray(manifest, "bookmarks", MAX_BACKUP_BOOKMARKS)
            val highlights = validatedManifestArray(manifest, "highlights", MAX_BACKUP_HIGHLIGHTS)
            val readingStats = validatedManifestArray(manifest, "readingStats", MAX_BACKUP_READING_DAYS)
            val vocabulary = validatedManifestArray(manifest, "vocabulary", MAX_BACKUP_VOCABULARY)
            val seenBookIds = mutableSetOf<String>()
            val stagedMoves = mutableListOf<Pair<File, File>>()
            val restored = buildList {
                val jsonBooks = manifest.optJSONArray("books") ?: JSONArray()
                require(jsonBooks.length() <= MAX_BACKUP_BOOKS) { "The backup contains too many books." }
                for (index in 0 until jsonBooks.length()) {
                    val raw = jsonBooks.getJSONObject(index)
                    val record = raw.toBookRecord()
                    requireValidBookIdentity(record.id, record.isDemo)
                    require(seenBookIds.add(record.id)) { "The backup contains a duplicate book identity." }
                    if (record.isDemo) {
                        add(record.copy(filePath = null, coverPath = null))
                    } else {
                        val sourceBook = safeRestoreFile(temporary, "books/${record.id}.epub")
                        if (!sourceBook.isFile) continue
                        val targetBook = bookFile(record.id)
                        val stagedBook = ownedDirectChild(
                            booksDirectory,
                            "${record.id}.epub.restore-${UUID.randomUUID()}",
                        )
                        stagedFiles.add(stagedBook)
                        moveIntoPlace(sourceBook, stagedBook)
                        stagedMoves.add(stagedBook to targetBook)
                        val coverDirectory = safeRestoreFile(temporary, "covers")
                        val matchingCovers = coverDirectory.listFiles()?.filter {
                            it.nameWithoutExtension == record.id
                        }.orEmpty()
                        require(matchingCovers.size <= 1) { "The backup contains duplicate covers for a book." }
                        val targetCover = matchingCovers.singleOrNull()?.let { sourceCover ->
                            val extension = sourceCover.extension.lowercase()
                            require(extension in COVER_EXTENSIONS) { "The backup contains an unsupported cover type." }
                            val target = ownedDirectChild(coversDirectory, "${record.id}.$extension")
                            val staged = ownedDirectChild(
                                coversDirectory,
                                "${record.id}.$extension.restore-${UUID.randomUUID()}",
                            )
                            stagedFiles.add(staged)
                            moveIntoPlace(sourceCover, staged)
                            stagedMoves.add(staged to target)
                            target
                        }
                        add(record.copy(filePath = targetBook.absolutePath, coverPath = targetCover?.absolutePath))
                    }
                }
            }.ifEmpty { listOf(demoBook()) }
            stagedMoves.forEach { (source, target) -> moveIntoPlace(source, target) }
            saveBooks(restored)
            saveJsonArrayValue(KEY_BOOKMARKS, bookmarks)
            saveJsonArrayValue(KEY_HIGHLIGHTS, highlights)
            saveJsonArrayValue(KEY_READING_STATS, readingStats)
            context.getSharedPreferences("serein_dictionary", Context.MODE_PRIVATE).edit()
                .putString("dictionary_cache", "[]")
                .putString("vocabulary_history", vocabulary.toString())
                .apply()
            manifest.optJSONObject("readerPreferences")?.let { saveReaderPreferences(it.toReaderPreferences()) }
            manifest.optJSONObject("libraryPreferences")?.let { saveLibraryPreferences(it.toLibraryPreferences()) }
            return restored
        } finally {
            stagedFiles.forEach { it.delete() }
            temporary.deleteRecursively()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }

    private fun normalizeStoredBook(book: BookRecord): BookRecord {
        requireValidBookIdentity(book.id, book.isDemo)
        if (book.isDemo) return book.copy(filePath = null, coverPath = null)
        return book.copy(
            filePath = bookFile(book.id).absolutePath,
            coverPath = safeStoredCover(book)?.absolutePath,
        )
    }

    private fun bookFile(bookId: String): File {
        requireValidBookIdentity(bookId, false)
        return ownedDirectChild(booksDirectory, "$bookId.epub")
    }

    private fun assetDirectory(bookId: String): File {
        requireValidBookIdentity(bookId, false)
        return ownedDirectChild(assetsDirectory, bookId)
    }

    private fun safeStoredCover(book: BookRecord): File? {
        requireValidBookIdentity(book.id, false)
        val path = book.coverPath ?: return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val canonicalRoot = coversDirectory.canonicalFile
        val expectedNames = setOf(book.id, book.id.filter(Char::isLetterOrDigit))
        return candidate.takeIf {
            it.parentFile == canonicalRoot &&
                it.nameWithoutExtension in expectedNames &&
                it.extension.lowercase() in COVER_EXTENSIONS
        }
    }

    private fun deleteBookCovers(bookId: String) {
        requireValidBookIdentity(bookId, false)
        val expectedNames = setOf(bookId, bookId.filter(Char::isLetterOrDigit))
        coversDirectory.listFiles()?.forEach { candidate ->
            if (
                candidate.parentFile?.canonicalFile == coversDirectory.canonicalFile &&
                candidate.nameWithoutExtension in expectedNames &&
                candidate.extension.lowercase() in COVER_EXTENSIONS
            ) {
                candidate.delete()
            }
        }
    }

    private fun backupEntryLimit(name: String, isDirectory: Boolean): Long {
        if (isDirectory) {
            require(name == "books/" || name == "covers/") {
                "The backup contains an unexpected directory."
            }
            return 0L
        }
        if (name == BACKUP_MANIFEST) return MAX_BACKUP_MANIFEST_BYTES
        BACKUP_BOOK_ENTRY.matchEntire(name)?.groupValues?.get(1)?.let { id ->
            requireValidBookIdentity(id, false)
            return MAX_BACKUP_BOOK_BYTES
        }
        BACKUP_COVER_ENTRY.matchEntire(name)?.let { match ->
            requireValidBookIdentity(match.groupValues[1], false)
            return MAX_BACKUP_COVER_BYTES
        }
        throw IllegalArgumentException("The backup contains an unexpected file entry.")
    }

    private fun validatedManifestArray(manifest: JSONObject, key: String, maximumItems: Int): JSONArray {
        val array = manifest.optJSONArray(key) ?: JSONArray()
        require(array.length() <= maximumItems) { "The backup contains too many $key records." }
        return array
    }

    private fun saveJsonArrayValue(key: String, value: JSONArray) {
        preferences.edit().putString(key, value.toString()).apply()
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun BookRecord.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author)
        .put("filePath", filePath)
        .put("coverPath", coverPath)
        .put("progress", progress.toDouble())
        .put("chapterIndex", chapterIndex)
        .put("characterOffset", characterOffset)
        .put("scrollOffsetPx", scrollOffsetPx)
        .put("lastOpenedAt", lastOpenedAt)
        .put("isDemo", isDemo)
        .put("collection", collection)
        .put("tags", JSONArray(tags))
        .put("completedAt", completedAt)

    private fun JSONObject.toBookRecord(): BookRecord = BookRecord(
        id = getString("id"),
        title = getString("title"),
        author = getString("author"),
        filePath = optString("filePath").takeUnless { it.isBlank() || it == "null" },
        coverPath = optString("coverPath").takeUnless { it.isBlank() || it == "null" },
        progress = optDouble("progress", 0.0).toFloat(),
        chapterIndex = optInt("chapterIndex", 0),
        characterOffset = optInt("characterOffset", 0),
        scrollOffsetPx = optInt("scrollOffsetPx", 0),
        lastOpenedAt = optLong("lastOpenedAt", 0L),
        isDemo = optBoolean("isDemo", false),
        collection = optString("collection"),
        tags = optJSONArray("tags")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty(),
        completedAt = optLong("completedAt", 0L),
    )

    private fun bookmarkToJson(bookmark: BookmarkRecord) = JSONObject()
        .put("id", bookmark.id)
        .put("bookId", bookmark.bookId)
        .put("chapterIndex", bookmark.chapterIndex)
        .put("characterOffset", bookmark.characterOffset)
        .put("excerpt", bookmark.excerpt)
        .put("createdAt", bookmark.createdAt)

    private fun bookmarkFromJson(json: JSONObject) = BookmarkRecord(
        id = json.getString("id"),
        bookId = json.getString("bookId"),
        chapterIndex = json.optInt("chapterIndex"),
        characterOffset = json.optInt("characterOffset"),
        excerpt = json.optString("excerpt"),
        createdAt = json.optLong("createdAt"),
    )

    private fun highlightToJson(highlight: HighlightRecord) = JSONObject()
        .put("id", highlight.id)
        .put("bookId", highlight.bookId)
        .put("chapterIndex", highlight.chapterIndex)
        .put("startOffset", highlight.startOffset)
        .put("endOffset", highlight.endOffset)
        .put("text", highlight.text)
        .put("createdAt", highlight.createdAt)
        .put("note", highlight.note)

    private fun highlightFromJson(json: JSONObject) = HighlightRecord(
        id = json.getString("id"),
        bookId = json.getString("bookId"),
        chapterIndex = json.optInt("chapterIndex"),
        startOffset = json.optInt("startOffset"),
        endOffset = json.optInt("endOffset"),
        text = json.optString("text"),
        createdAt = json.optLong("createdAt"),
        note = json.optString("note"),
    )

    private fun <T> loadJsonArray(key: String, transform: (JSONObject) -> T): List<T> {
        val stored = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(stored)
            buildList {
                for (index in 0 until json.length()) add(transform(json.getJSONObject(index)))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveJsonArray(key: String, values: List<JSONObject>) {
        preferences.edit().putString(key, JSONArray(values).toString()).apply()
    }

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, fallback.name).orEmpty()) }
            .getOrDefault(fallback)

    private fun readingStatsToJson(value: DailyReadingStats) = JSONObject()
        .put("date", value.date)
        .put("readingMillis", value.readingMillis)
        .put("pagesRead", value.pagesRead)

    private fun readingStatsFromJson(json: JSONObject) = DailyReadingStats(
        date = json.optString("date"),
        readingMillis = json.optLong("readingMillis"),
        pagesRead = json.optInt("pagesRead"),
    )

    private fun jsonArrayValue(key: String): JSONArray = runCatching {
        JSONArray(preferences.getString(key, "[]"))
    }.getOrDefault(JSONArray())

    private fun readerPreferencesToJson(value: ReaderPreferences) = JSONObject()
        .put("theme", value.theme.name)
        .put("font", value.font.name)
        .put("textSize", value.textSize)
        .put("lineHeight", value.lineHeight.toDouble())
        .put("bionicReading", value.bionicReading)
        .put("readingMode", value.readingMode.name)
        .put("brightness", value.brightness.toDouble())
        .put("orientation", value.orientation.name)
        .put("volumePageTurns", value.volumePageTurns)
        .put("wideTapZones", value.wideTapZones)
        .put("keepScreenAwake", value.keepScreenAwake)

    private fun JSONObject.toReaderPreferences() = ReaderPreferences(
        theme = runCatching { ReaderTheme.valueOf(optString("theme")) }.getOrDefault(ReaderTheme.LIGHT),
        font = runCatching { ReaderFont.valueOf(optString("font")) }.getOrDefault(ReaderFont.LORA),
        textSize = optInt("textSize", 18).coerceIn(14, 30),
        lineHeight = optDouble("lineHeight", 1.65).toFloat().coerceIn(1.3f, 2f),
        bionicReading = optBoolean("bionicReading"),
        readingMode = runCatching { ReadingMode.valueOf(optString("readingMode")) }.getOrDefault(ReadingMode.PAGED),
        brightness = optDouble("brightness", -1.0).toFloat().let { if (it < 0f) -1f else it.coerceIn(0.05f, 1f) },
        orientation = runCatching { ReaderOrientation.valueOf(optString("orientation")) }.getOrDefault(ReaderOrientation.SYSTEM),
        volumePageTurns = optBoolean("volumePageTurns"),
        wideTapZones = optBoolean("wideTapZones", true),
        keepScreenAwake = optBoolean("keepScreenAwake", true),
    )

    private fun libraryPreferencesToJson(value: LibraryPreferences) = JSONObject()
        .put("sort", value.sort.name)
        .put("filter", value.filter.name)
        .put("collection", value.collection)

    private fun JSONObject.toLibraryPreferences() = LibraryPreferences(
        sort = runCatching { LibrarySort.valueOf(optString("sort")) }.getOrDefault(LibrarySort.RECENT),
        filter = runCatching { LibraryFilter.valueOf(optString("filter")) }.getOrDefault(LibraryFilter.ALL),
        collection = optString("collection"),
    )

    private fun BookRecord.toBackupJson(): JSONObject = toJson()
        .put("filePath", JSONObject.NULL)
        .put("coverPath", JSONObject.NULL)

    private fun zipFile(zip: ZipOutputStream, source: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun safeRestoreFile(root: File, name: String): File {
        require(name.isNotBlank() && '\\' !in name && '\u0000' !in name) {
            "The backup contains an unsafe file path."
        }
        val output = File(root, name)
        val rootPath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(outputPath != rootPath && outputPath.startsWith(rootPath)) {
            "The backup contains an unsafe file path."
        }
        return output
    }

    private fun dictionaryPreferenceArray(key: String): JSONArray {
        val store = context.getSharedPreferences("serein_dictionary", Context.MODE_PRIVATE)
        return runCatching { JSONArray(store.getString(key, "[]")) }.getOrDefault(JSONArray())
    }

    private fun demoContent() = BookContent(
        chapters = listOf(
            BookChapter(
                title = "Chapter Three",
                paragraphs = listOf(
                    "If a woman is to write fiction, she must have money and a room of her own. If she is to write good fiction, these two things are absolutely essential.",
                    "A woman must have money and a room of her own if she is to write fiction; but, alas! how few women have either money or rooms of their own.",
                    "We think back through our mothers if we are women. I do not wish to be tedious about my mother. I think of her only because she was for me typical of the average woman of my time.",
                    "She was a woman of no education, no money, and no room of her own. She had, in fact, nothing except a lively intelligence and a delight in words.",
                ),
            ),
            BookChapter(
                title = "Chapter Four",
                paragraphs = listOf(
                    "The mind is certainly a very mysterious organ. It is always altering its focus, and bringing the world into different perspectives.",
                    "Books continue each other, in spite of our habit of judging them separately. A reader carries one voice quietly into the next.",
                ),
            ),
        )
    )

    private fun demoBook() = BookRecord(
        id = DEMO_BOOK_ID,
        title = "A Room of One’s Own",
        author = "Virginia Woolf",
        isDemo = true,
    )

    private companion object {
        const val KEY_BOOKS = "books"
        const val KEY_THEME = "reader_theme"
        const val KEY_FONT = "reader_font"
        const val KEY_TEXT_SIZE = "reader_text_size"
        const val KEY_LINE_HEIGHT = "reader_line_height"
        const val KEY_BIONIC = "reader_bionic"
        const val KEY_READING_MODE = "reader_reading_mode"
        const val KEY_BRIGHTNESS = "reader_brightness"
        const val KEY_ORIENTATION = "reader_orientation"
        const val KEY_VOLUME_PAGE_TURNS = "reader_volume_page_turns"
        const val KEY_WIDE_TAP_ZONES = "reader_wide_tap_zones"
        const val KEY_KEEP_SCREEN_AWAKE = "reader_keep_screen_awake"
        const val KEY_LIBRARY_SORT = "library_sort"
        const val KEY_LIBRARY_FILTER = "library_filter"
        const val KEY_LIBRARY_COLLECTION = "library_collection"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_HIGHLIGHTS = "highlights"
        const val KEY_READING_STATS = "reading_stats"
        const val BACKUP_MANIFEST = "serein-backup.json"
        const val BACKUP_SCHEMA_VERSION = 2
        const val MAX_IMPORT_FILE_BYTES = 256L * 1024L * 1024L
        const val MAX_BACKUP_FILE_BYTES = 1024L * 1024L * 1024L
        const val MAX_BACKUP_EXPANDED_BYTES = 1024L * 1024L * 1024L
        const val MAX_BACKUP_MANIFEST_BYTES = 4L * 1024L * 1024L
        const val MAX_BACKUP_BOOK_BYTES = 256L * 1024L * 1024L
        const val MAX_BACKUP_COVER_BYTES = 32L * 1024L * 1024L
        const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
        const val MAX_BACKUP_ENTRIES = 2_501
        const val MAX_BACKUP_BOOKS = 1_000
        const val MAX_BACKUP_BOOKMARKS = 20_000
        const val MAX_BACKUP_HIGHLIGHTS = 20_000
        const val MAX_BACKUP_READING_DAYS = 3_660
        const val MAX_BACKUP_VOCABULARY = 100
        val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val BACKUP_BOOK_ENTRY = Regex(
            "^books/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.epub$"
        )
        val BACKUP_COVER_ENTRY = Regex(
            "^covers/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(jpg|jpeg|png|webp)$"
        )
    }
}
