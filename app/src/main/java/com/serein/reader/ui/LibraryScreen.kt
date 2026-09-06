package com.serein.reader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.serein.reader.R
import com.serein.reader.data.BookRecord
import com.serein.reader.data.LibraryFilter
import com.serein.reader.data.LibraryPreferences
import com.serein.reader.data.LibrarySort
import com.serein.reader.data.ReadingStatsSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun filterAndSortBooks(
    books: List<BookRecord>,
    query: String,
    preferences: LibraryPreferences,
): List<BookRecord> {
    val filtered = books.filter { book ->
        val matchesQuery = query.isBlank() ||
            book.title.contains(query, ignoreCase = true) ||
            book.author.contains(query, ignoreCase = true) ||
            book.collection.contains(query, ignoreCase = true) ||
            book.tags.any { it.contains(query, ignoreCase = true) }
        val matchesCollection = preferences.collection.isBlank() ||
            book.collection.equals(preferences.collection, ignoreCase = true)
        val matchesProgress = when (preferences.filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.UNREAD -> book.progress <= 0.001f
            LibraryFilter.READING -> book.progress > 0.001f && book.progress < 0.995f
            LibraryFilter.FINISHED -> book.progress >= 0.995f
        }
        matchesQuery && matchesCollection && matchesProgress
    }
    return when (preferences.sort) {
        LibrarySort.RECENT -> filtered.sortedWith(compareByDescending<BookRecord> { it.lastOpenedAt }.thenBy { it.title.lowercase() })
        LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        LibrarySort.AUTHOR -> filtered.sortedWith(compareBy<BookRecord> { it.author.lowercase() }.thenBy { it.title.lowercase() })
        LibrarySort.PROGRESS -> filtered.sortedByDescending { it.progress }
    }
}

@Composable
fun LibraryScreen(
    books: List<BookRecord>,
    searchQuery: String,
    preferences: LibraryPreferences = LibraryPreferences(),
    readingStats: ReadingStatsSummary = ReadingStatsSummary(),
    isImporting: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onAddEpub: () -> Unit,
    onOpenBook: (BookRecord) -> Unit,
    onRemoveBook: (BookRecord) -> Unit,
    onPreferencesChange: ((LibraryPreferences) -> LibraryPreferences) -> Unit = {},
    onOrganizeBook: (BookRecord, String, List<String>) -> Unit = { _, _, _ -> },
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    val palette = LocalSereinPalette.current
    var organizingBook by remember { mutableStateOf<BookRecord?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val visibleBooks = remember(books, searchQuery, preferences) {
        filterAndSortBooks(books, searchQuery, preferences)
    }

    Scaffold(containerColor = palette.paper) { safePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(safePadding)
                .padding(horizontal = 20.dp),
        ) {
            LibraryHeader(
                onAddEpub = onAddEpub,
                onShowStats = { showStats = true },
                onBackup = onBackup,
                onRestore = { confirmRestore = true },
            )
            SearchField(searchQuery, onSearchQueryChange)
            LibraryControls(
                books = books,
                preferences = preferences,
                onChange = onPreferencesChange,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (searchQuery.isBlank()) "Books" else "Search results",
                    color = palette.ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${visibleBooks.size} ${if (visibleBooks.size == 1) "book" else "books"}",
                    color = palette.mutedInk,
                    fontSize = 13.sp,
                )
            }

            if (visibleBooks.isEmpty()) {
                EmptyLibrary(onAddEpub, Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    items(visibleBooks, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            onOpen = { onOpenBook(book) },
                            onOrganize = { organizingBook = book },
                            onRemove = { onRemoveBook(book) },
                        )
                    }
                }
            }
        }

        if (isImporting) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.paper.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = palette.sage)
            }
        }
    }

    organizingBook?.let { book ->
        BookOrganizationDialog(
            book = book,
            onDismiss = { organizingBook = null },
            onSave = { collection, tags ->
                onOrganizeBook(book, collection, tags)
                organizingBook = null
            },
        )
    }
    if (showStats) {
        ReadingStatsSheet(readingStats, onDismiss = { showStats = false })
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore library?") },
            text = { Text("Your current library index, progress, annotations, settings, and statistics will be replaced by the selected backup.") },
            confirmButton = {
                TextButton(onClick = { confirmRestore = false; onRestore() }) { Text("Choose backup") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LibraryControls(
    books: List<BookRecord>,
    preferences: LibraryPreferences,
    onChange: ((LibraryPreferences) -> LibraryPreferences) -> Unit,
) {
    val palette = LocalSereinPalette.current
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var collectionOpen by remember { mutableStateOf(false) }
    val collections = remember(books) { books.map(BookRecord::collection).filter(String::isNotBlank).distinct().sorted() }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            TextButton(onClick = { sortOpen = true }) { Text(preferences.sort.label, color = palette.sage, fontSize = 12.sp) }
            DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                LibrarySort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(sort.label) },
                        onClick = { sortOpen = false; onChange { it.copy(sort = sort) } },
                    )
                }
            }
        }
        Box {
            TextButton(onClick = { filterOpen = true }) { Text(preferences.filter.label, color = palette.sage, fontSize = 12.sp) }
            DropdownMenu(filterOpen, onDismissRequest = { filterOpen = false }) {
                LibraryFilter.entries.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(filter.label) },
                        onClick = { filterOpen = false; onChange { it.copy(filter = filter) } },
                    )
                }
            }
        }
        if (collections.isNotEmpty()) {
            Box {
                TextButton(onClick = { collectionOpen = true }) {
                    Text(preferences.collection.ifBlank { "Collections" }, color = palette.sage, fontSize = 12.sp)
                }
                DropdownMenu(collectionOpen, onDismissRequest = { collectionOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("All collections") },
                        onClick = { collectionOpen = false; onChange { it.copy(collection = "") } },
                    )
                    collections.forEach { collection ->
                        DropdownMenuItem(
                            text = { Text(collection) },
                            onClick = { collectionOpen = false; onChange { it.copy(collection = collection) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookOrganizationDialog(
    book: BookRecord,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    var collection by remember(book.id) { mutableStateOf(book.collection) }
    var tags by remember(book.id) { mutableStateOf(book.tags.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Organize book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(book.title, color = LocalSereinPalette.current.mutedInk, maxLines = 2)
                OutlinedTextField(
                    value = collection,
                    onValueChange = { collection = it },
                    label = { Text("Collection") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags, separated by commas") },
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(collection, tags.split(',')) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingStatsSheet(stats: ReadingStatsSummary, onDismiss: () -> Unit) {
    val palette = LocalSereinPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.sheet,
        contentColor = palette.ink,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp)) {
            Text("Reading activity", fontFamily = LiterataFamily, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatValue(stats.todayMinutes.toString(), "minutes today")
                StatValue(stats.todayPages.toString(), "pages today")
                StatValue(stats.currentStreak.toString(), "day streak")
            }
            HorizontalDivider(Modifier.padding(vertical = 22.dp), color = palette.line)
            Text("Last seven days", color = palette.sage, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            stats.recentDays.forEach { day ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(day.date.takeLast(5), color = palette.mutedInk, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${day.readingMillis / 60_000L} min  ·  ${day.pagesRead} pages", fontSize = 12.sp)
                }
            }
            Text(
                "${stats.totalMinutes} minutes total  ·  ${stats.completedBooks} books finished",
                color = palette.mutedInk,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 22.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun StatValue(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = LiterataFamily, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = LocalSereinPalette.current.mutedInk, fontSize = 10.sp)
    }
}

@Composable
private fun LibraryHeader(
    onAddEpub: () -> Unit,
    onShowStats: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val palette = LocalSereinPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 22.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "SEREIN",
                color = palette.sage,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.8.sp,
            )
            Text(
                text = "Library",
                color = palette.ink,
                fontSize = 38.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Library options", tint = palette.ink)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Reading statistics") },
                    leadingIcon = { Icon(Icons.Rounded.Insights, null) },
                    onClick = { menuOpen = false; onShowStats() },
                )
                DropdownMenuItem(
                    text = { Text("Back up library") },
                    leadingIcon = { Icon(Icons.Rounded.Save, null) },
                    onClick = { menuOpen = false; onBackup() },
                )
                DropdownMenuItem(
                    text = { Text("Restore backup") },
                    leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                    onClick = { menuOpen = false; onRestore() },
                )
            }
        }
        IconButton(
            onClick = onAddEpub,
            modifier = Modifier
                .size(48.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(palette.sheet)
                .border(1.dp, palette.line.copy(alpha = 0.75f), CircleShape),
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Add book",
                tint = palette.sage,
                modifier = Modifier.size(25.dp),
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val palette = LocalSereinPalette.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
        placeholder = { Text("Search library", color = palette.mutedInk, fontSize = 16.sp) },
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = palette.mutedInk,
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = palette.mutedInk,
                    )
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(17.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.control,
            unfocusedContainerColor = palette.control,
            focusedTextColor = palette.ink,
            unfocusedTextColor = palette.ink,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = palette.sage,
        ),
    )
}

@Composable
private fun BookCard(
    book: BookRecord,
    onOpen: () -> Unit,
    onOrganize: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalSereinPalette.current
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        BookCover(
            book = book,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .shadow(7.dp, RoundedCornerShape(13.dp))
                .clip(RoundedCornerShape(13.dp)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    color = palette.ink,
                    fontFamily = LiterataFamily,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.author,
                    color = palette.mutedInk,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = "Book options",
                        tint = palette.mutedInk,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Collection & tags") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null) },
                        onClick = { menuOpen = false; onOrganize() },
                    )
                    DropdownMenuItem(
                        enabled = !book.isDemo,
                        text = { Text(if (book.isDemo) "Built-in sample" else "Remove") },
                        leadingIcon = {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = { book.progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = palette.sage,
            trackColor = palette.line,
        )
        Text(
            text = "${(book.progress * 100).toInt()}% read",
            color = palette.mutedInk,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
private fun BookCover(
    book: BookRecord,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSereinPalette.current
    if (book.isDemo) {
        Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.a_room_of_ones_own_cover),
            contentDescription = "${book.title} cover",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        return
    }

    val bitmap by produceState<ImageBitmap?>(initialValue = null, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            decodeCover(book.coverPath)?.asImageBitmap()
        }
    }
    val coverBitmap = bitmap
    if (coverBitmap != null) {
        Image(
            bitmap = coverBitmap,
            contentDescription = "${book.title} cover",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = modifier,
            color = palette.coverFallback,
            shape = RoundedCornerShape(13.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.AutoStories,
                    contentDescription = null,
                    tint = palette.sage,
                    modifier = Modifier.size(34.dp),
                )
                Text(
                    text = book.title,
                    color = palette.ink,
                    fontFamily = LiterataFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

private fun decodeCover(path: String?): android.graphics.Bitmap? {
    val coverPath = path?.takeIf { File(it).exists() } ?: return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(coverPath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight)
        }
        BitmapFactory.decodeFile(coverPath, options)
    }.getOrNull()
}

internal fun coverSampleSize(
    width: Int,
    height: Int,
    targetWidth: Int = 720,
    targetHeight: Int = 1_080,
): Int = boundedBitmapSampleSize(
    width = width,
    height = height,
    maximumWidth = targetWidth.toLong() * 2L,
    maximumHeight = targetHeight.toLong() * 2L,
)

internal fun boundedBitmapSampleSize(
    width: Int,
    height: Int,
    maximumWidth: Long,
    maximumHeight: Long,
): Int {
    if (width <= 0 || height <= 0) return 1
    require(maximumWidth > 0L && maximumHeight > 0L)
    var sampleSize = 1
    while (
        (width.toLong() + sampleSize - 1L) / sampleSize > maximumWidth ||
        (height.toLong() + sampleSize - 1L) / sampleSize > maximumHeight
    ) {
        if (sampleSize > Int.MAX_VALUE / 2) return Int.MAX_VALUE
        sampleSize *= 2
    }
    return sampleSize
}

@Composable
private fun EmptyLibrary(
    onAddEpub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSereinPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.AutoStories,
            contentDescription = null,
            tint = palette.sage,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "No books found",
            color = palette.ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = "Add an EPUB or PDF from your device to begin your library.",
            color = palette.mutedInk,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )
        Button(onClick = onAddEpub) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Choose a book")
        }
    }
}

