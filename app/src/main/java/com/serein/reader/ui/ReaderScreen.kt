package com.serein.reader.ui

import android.app.SearchManager
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.serein.reader.R
import com.serein.reader.data.BionicReading
import com.serein.reader.data.BookContent
import com.serein.reader.data.BookBlockKind
import com.serein.reader.data.BookInlineSpan
import com.serein.reader.data.BookSearchResult
import com.serein.reader.data.BookmarkRecord
import com.serein.reader.data.BookRecord
import com.serein.reader.data.DictionaryState
import com.serein.reader.data.HighlightRecord
import com.serein.reader.data.ReadingLocation
import com.serein.reader.data.ReadingMode
import com.serein.reader.data.ReaderPreferences
import com.serein.reader.data.ReaderOrientation
import com.serein.reader.data.VocabularyRecord
import com.serein.reader.data.progressAt
import com.serein.reader.data.readingText
import com.serein.reader.data.search
import com.serein.reader.data.totalCharacters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.yield
import kotlin.math.ceil
import java.io.File

private enum class ReaderPanel { CONTENTS, SEARCH, SAVED, VOCABULARY }

private data class PageTurnRequest(val direction: Int, val token: Long = System.nanoTime())

private data class ReaderTarget(
    val chapterIndex: Int,
    val characterOffset: Int,
    val token: Long = System.nanoTime(),
)

private data class VisiblePosition(
    val location: ReadingLocation,
    val excerpt: String,
    val page: Int,
    val pageCount: Int,
    val pageCountEstimated: Boolean = true,
)

/**
 * Tracks this session's own reading pace so the footer can show "N min left". Starts from a
 * plausible silent-reading default and blends toward the reader's live pace; a sample is ignored
 * when the gap is too large (a TOC/search jump) or too long (the reader put the phone down), so
 * neither corrupts the running average.
 */
private class ReadingSpeedEstimator {
    private var lastSampleAtMs: Long = -1L
    private var lastAbsoluteOffset: Int = -1
    var charactersPerMinute: Double = DEFAULT_CHARACTERS_PER_MINUTE
        private set

    fun sample(absoluteOffset: Int, nowMs: Long) {
        if (lastSampleAtMs >= 0L && lastAbsoluteOffset >= 0) {
            val elapsedMs = nowMs - lastSampleAtMs
            val advanced = absoluteOffset - lastAbsoluteOffset
            if (elapsedMs in MIN_SAMPLE_INTERVAL_MS..MAX_SAMPLE_INTERVAL_MS && advanced in 1..MAX_PLAUSIBLE_CHARACTERS) {
                val instantaneousRate = advanced / (elapsedMs / 60_000.0)
                charactersPerMinute = (charactersPerMinute * 0.7) + (instantaneousRate * 0.3)
            }
        }
        lastSampleAtMs = nowMs
        lastAbsoluteOffset = absoluteOffset
    }

    private companion object {
        const val DEFAULT_CHARACTERS_PER_MINUTE = 1_000.0
        const val MIN_SAMPLE_INTERVAL_MS = 1_500L
        const val MAX_SAMPLE_INTERVAL_MS = 120_000L
        const val MAX_PLAUSIBLE_CHARACTERS = 6_000
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: BookRecord,
    content: BookContent?,
    isLoading: Boolean,
    preferences: ReaderPreferences,
    bookmarks: List<BookmarkRecord> = emptyList(),
    highlights: List<HighlightRecord> = emptyList(),
    dictionaryState: DictionaryState = DictionaryState.Idle,
    vocabulary: List<VocabularyRecord> = emptyList(),
    onBack: () -> Unit,
    onPreferencesChange: ((ReaderPreferences) -> ReaderPreferences) -> Unit,
    onLocationChange: (ReadingLocation) -> Unit = {},
    onReadingHeartbeat: () -> Unit = {},
    onReadingActiveChange: (Boolean) -> Unit = {},
    onToggleBookmark: (ReadingLocation, String) -> Unit = { _, _ -> },
    onRemoveBookmark: (BookmarkRecord) -> Unit = {},
    onAddHighlight: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onRemoveHighlight: (HighlightRecord) -> Unit = {},
    onUpdateHighlightNote: (HighlightRecord, String) -> Unit = { _, _ -> },
    onExportAnnotations: (Boolean) -> String? = { null },
    onDictionaryLookup: (String) -> Unit = {},
    onDictionaryDismiss: () -> Unit = {},
    initialSettingsOpen: Boolean = false,
) {
    val palette = LocalSereinPalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val contentToWrite = pendingExport
        pendingExport = null
        if (uri != null && contentToWrite != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(contentToWrite) }
                    ?: error("The export file could not be opened.")
            }.onSuccess {
                Toast.makeText(context, "Notes exported.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Notes could not be exported.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    DisposableEffect(lifecycleOwner, book.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onReadingActiveChange(true)
                Lifecycle.Event.ON_PAUSE -> onReadingActiveChange(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            onReadingActiveChange(true)
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycleOwner, book.id) {
        while (true) {
            delay(READING_HEARTBEAT_MS)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                onReadingHeartbeat()
            }
        }
    }
    var pageTurnRequest by remember { mutableStateOf<PageTurnRequest?>(null) }
    ReaderSystemEffects(preferences, activity)
    DisposableEffect(activity, preferences.volumePageTurns) {
        val host = activity as? com.serein.reader.MainActivity
        host?.volumePageHandler = if (preferences.volumePageTurns) {
            { direction -> pageTurnRequest = PageTurnRequest(direction); true }
        } else null
        onDispose { if (host?.volumePageHandler != null) host.volumePageHandler = null }
    }
    var showSettings by rememberSaveable(book.id) { mutableStateOf(initialSettingsOpen) }
    var activePanel by remember { mutableStateOf<ReaderPanel?>(null) }
    var controlsVisible by rememberSaveable(book.id) { mutableStateOf(true) }
    var selection by remember { mutableStateOf<ReaderSelection?>(null) }
    var target by remember { mutableStateOf<ReaderTarget?>(null) }
    var visible by remember(book.id) {
        mutableStateOf(
            VisiblePosition(
                ReadingLocation(book.chapterIndex, book.characterOffset, book.scrollOffsetPx, book.progress),
                excerpt = "",
                page = 1,
                pageCount = 1,
                pageCountEstimated = true,
            )
        )
    }
    val isBookmarked = bookmarks.any {
        it.chapterIndex == visible.location.chapterIndex &&
            kotlin.math.abs(it.characterOffset - visible.location.characterOffset) < 12
    }

    val speedEstimator = remember(book.id) { ReadingSpeedEstimator() }
    var estimatedMinutesLeft by remember(book.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(visible, content) {
        val total = content?.totalCharacters() ?: return@LaunchedEffect
        val absoluteOffset = (total * visible.location.progress).toInt().coerceIn(0, total)
        speedEstimator.sample(absoluteOffset, System.currentTimeMillis())
        val remainingCharacters = total - absoluteOffset
        estimatedMinutesLeft = ceil(remainingCharacters / speedEstimator.charactersPerMinute).toInt().coerceAtLeast(0)
    }

    Scaffold(
        containerColor = palette.paper,
        topBar = {
            ReaderTopBar(
                title = book.title,
                bookmarked = isBookmarked,
                controlsVisible = controlsVisible,
                readingMode = preferences.readingMode,
                onBack = onBack,
                onBookmark = { onToggleBookmark(visible.location, visible.excerpt) },
                onContents = { activePanel = ReaderPanel.CONTENTS },
                onSearch = { activePanel = ReaderPanel.SEARCH },
                onSaved = { activePanel = ReaderPanel.SAVED },
                onVocabulary = { activePanel = ReaderPanel.VOCABULARY },
                onSettings = { showSettings = true },
                onModeChange = { mode -> onPreferencesChange { it.copy(readingMode = mode) } },
            )
        },
        bottomBar = {
            ReaderFooter(
                progress = visible.location.progress,
                page = visible.page,
                pageCount = visible.pageCount,
                paged = preferences.readingMode == ReadingMode.PAGED && !visible.pageCountEstimated,
                minutesLeft = estimatedMinutesLeft,
            )
        },
    ) { safePadding ->
        Box(Modifier.fillMaxSize().padding(safePadding)) {
            if (isLoading || content == null) {
                CircularProgressIndicator(color = palette.sage, modifier = Modifier.align(Alignment.Center))
            } else if (preferences.readingMode == ReadingMode.PAGED) {
                PagedReader(
                    book = book,
                    content = content,
                    resumeLocation = visible.location,
                    preferences = preferences,
                    highlights = highlights,
                    target = target,
                    pageTurnRequest = pageTurnRequest,
                    onVisiblePosition = {
                        visible = it
                        onLocationChange(it.location)
                        target?.takeIf { requested ->
                            requested.chapterIndex == it.location.chapterIndex &&
                                kotlin.math.abs(requested.characterOffset - it.location.characterOffset) < 4_096
                        }?.let { target = null }
                    },
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onSelection = { selection = it },
                )
            } else {
                ScrollingReader(
                    book = book,
                    content = content,
                    resumeLocation = visible.location,
                    preferences = preferences,
                    highlights = highlights,
                    target = target,
                    pageTurnRequest = pageTurnRequest,
                    onVisiblePosition = {
                        visible = it
                        onLocationChange(it.location)
                        target?.takeIf { requested -> requested.chapterIndex == it.location.chapterIndex }
                            ?.let { target = null }
                    },
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onSelection = { selection = it },
                )
            }

            selection?.let { selected ->
                SelectionBar(
                    selection = selected,
                    onDismiss = { selection = null },
                    onDefine = { onDictionaryLookup(selected.word); selection = null },
                    onHighlightWord = {
                        onAddHighlight(selected.chapterIndex, selected.wordStart, selected.wordEnd, selected.word)
                        selection = null
                    },
                    onHighlightSentence = {
                        onAddHighlight(selected.chapterIndex, selected.sentenceStart, selected.sentenceEnd, selected.sentence)
                        selection = null
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(preferences, { showSettings = false }, onPreferencesChange)
    }
    activePanel?.let { panel ->
        ReaderHubSheet(
            content = content,
            activePanel = panel,
            bookmarks = bookmarks,
            highlights = highlights,
            vocabulary = vocabulary,
            onPanelChange = { activePanel = it },
            onNavigate = { chapter, offset ->
                target = ReaderTarget(chapter, offset)
                activePanel = null
            },
            onRemoveBookmark = onRemoveBookmark,
            onRemoveHighlight = onRemoveHighlight,
            onUpdateHighlightNote = onUpdateHighlightNote,
            onExport = { markdown ->
                onExportAnnotations(markdown)?.let { exported ->
                    pendingExport = exported
                    val safeTitle = book.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
                        .ifBlank { "book" }
                    exportLauncher.launch("$safeTitle-notes.${if (markdown) "md" else "txt"}")
                }
            },
            onDismiss = { activePanel = null },
        )
    }
    if (dictionaryState !is DictionaryState.Idle) {
        DictionarySheet(
            state = dictionaryState,
            onDismiss = onDictionaryDismiss,
            onRetry = {
                (dictionaryState as? DictionaryState.Error)?.word?.let(onDictionaryLookup)
            },
        )
    }
}

@Composable
private fun ReaderSystemEffects(preferences: ReaderPreferences, activity: Activity?) {
    val view = LocalView.current
    DisposableEffect(activity, preferences.brightness, preferences.orientation, preferences.keepScreenAwake) {
        val previousBrightness = activity?.window?.attributes?.screenBrightness
        val previousKeepAwake = view.keepScreenOn
        activity?.window?.let { window ->
            val attributes = window.attributes
            attributes.screenBrightness = preferences.brightness
            window.attributes = attributes
        }
        activity?.requestedOrientation = when (preferences.orientation) {
            ReaderOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        view.keepScreenOn = preferences.keepScreenAwake
        onDispose {
            activity?.window?.let { window ->
                val attributes = window.attributes
                attributes.screenBrightness = previousBrightness ?: -1f
                window.attributes = attributes
            }
            if (activity != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            view.keepScreenOn = previousKeepAwake
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PagedReader(
    book: BookRecord,
    content: BookContent,
    resumeLocation: ReadingLocation,
    preferences: ReaderPreferences,
    highlights: List<HighlightRecord>,
    target: ReaderTarget?,
    pageTurnRequest: PageTurnRequest?,
    onVisiblePosition: (VisiblePosition) -> Unit,
    onToggleControls: () -> Unit,
    onSelection: (ReaderSelection) -> Unit,
) {
    val palette = LocalSereinPalette.current
    val textMeasurer = rememberTextMeasurer()
    val bodySize = (preferences.textSize - 2).coerceIn(13, 28)
    val style = TextStyle(
        color = palette.ink,
        fontFamily = preferences.font.toFontFamily(),
        fontSize = bodySize.sp,
        lineHeight = (bodySize * preferences.lineHeight).sp,
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val pageWidthPx = with(density) { (maxWidth - preferences.marginWidth.dp * 2).roundToPx() }
        val normalHeightPx = with(density) { (maxHeight - 38.dp).roundToPx() }
        val firstHeightPx = with(density) { (maxHeight - 154.dp).roundToPx() }
        val anchorChapter = target?.chapterIndex ?: resumeLocation.chapterIndex
        val anchorOffset = target?.characterOffset ?: resumeLocation.characterOffset
        key(
            content, pageWidthPx, normalHeightPx, firstHeightPx, preferences.font,
            preferences.textSize, preferences.lineHeight, target?.token,
        ) {
            val forwardIterator = remember {
                paginateBookFrom(
                    content, textMeasurer, style, pageWidthPx, normalHeightPx, firstHeightPx,
                    anchorChapter, anchorOffset,
                ).iterator()
            }
            val initialPrevious = remember {
                precedingPages(
                    content, textMeasurer, style, pageWidthPx, normalHeightPx, firstHeightPx,
                    anchorChapter, anchorOffset, INITIAL_PREVIOUS_PAGE_COUNT,
                )
            }
            val pages = remember {
                mutableStateListOf<ReadingPage>().apply {
                    addAll(initialPrevious)
                    repeat(INITIAL_FORWARD_PAGE_COUNT) {
                        if (forwardIterator.hasNext()) add(forwardIterator.next())
                    }
                }
            }
            if (pages.isEmpty()) {
                pages.add(
                    ReadingPage(
                        chapterIndex = 0,
                        startOffset = 0,
                        endOffset = 0,
                        text = "This chapter does not contain readable reflowable text.",
                        isChapterStart = true,
                    )
                )
            }
            val initialPage = initialPrevious.size.coerceAtMost(pages.lastIndex)
            val pagerState = rememberPagerState(
                initialPage = initialPage,
            ) { pages.size }
            LaunchedEffect(pageTurnRequest?.token) {
                pageTurnRequest?.let { request ->
                    val destination = (pagerState.currentPage + request.direction)
                        .coerceIn(0, pages.lastIndex)
                    if (destination != pagerState.currentPage) pagerState.animateScrollToPage(destination)
                }
            }
            LaunchedEffect(pagerState, pages) {
                snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { index ->
                    val safeIndex = index.coerceIn(pages.indices)
                    val page = pages[safeIndex]
                    if (safeIndex >= pages.lastIndex - PAGE_PREFETCH_DISTANCE) {
                        repeat(PAGE_LOAD_BATCH_SIZE) {
                            if (forwardIterator.hasNext()) pages.add(forwardIterator.next())
                            yield()
                        }
                    }
                    if (safeIndex <= PAGE_PREFETCH_DISTANCE) {
                        val first = pages.first()
                        val more = precedingPages(
                            content, textMeasurer, style, pageWidthPx, normalHeightPx,
                            firstHeightPx, first.chapterIndex, first.startOffset,
                            PAGE_LOAD_BATCH_SIZE,
                        ).filterNot(pages::contains)
                        if (more.isNotEmpty()) {
                            pages.addAll(0, more)
                            pagerState.requestScrollToPage(safeIndex + more.size)
                        }
                    }
                    val averageCharacters = pages.asSequence().map(ReadingPage::text)
                        .filter(String::isNotBlank).map(String::length)
                        .take(PAGINATION_SAMPLE_SIZE).toList()
                        .average().takeIf { !it.isNaN() && it > 0.0 } ?: 1_200.0
                    val estimatedPageCount = ceil(content.totalCharacters() / averageCharacters)
                        .toInt().coerceAtLeast(1)
                    val estimatedPage = (
                        content.progressAt(page.chapterIndex, page.startOffset) * (estimatedPageCount - 1)
                        ).toInt().coerceIn(0, estimatedPageCount - 1) + 1
                    onVisiblePosition(
                        VisiblePosition(
                            ReadingLocation(
                                page.chapterIndex,
                                page.startOffset,
                                progress = content.progressAt(page.chapterIndex, page.startOffset),
                            ),
                            page.text.take(150),
                            estimatedPage,
                            estimatedPageCount,
                        )
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { index ->
                val page = pages[index]
                val pageOffset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                PageContent(
                    book = book,
                    chapterTitle = content.chapters[page.chapterIndex].title,
                    page = page,
                    preferences = preferences,
                    highlights = highlights.filter { it.chapterIndex == page.chapterIndex },
                    textStyle = style,
                    onToggleControls = onToggleControls,
                    onSelection = onSelection,
                    onPrevious = { if (index > 0) pagerState.requestScrollToPage(index - 1) },
                    onNext = {
                        if (index < pages.lastIndex) pagerState.requestScrollToPage(index + 1)
                    },
                    modifier = Modifier.pageTurnEffect(pageOffset),
                )
            }
        }
    }
}

private fun precedingPages(
    content: BookContent,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    pageWidthPx: Int,
    normalHeightPx: Int,
    firstHeightPx: Int,
    chapterIndex: Int,
    characterOffset: Int,
    count: Int,
): List<ReadingPage> {
    if (chapterIndex <= 0 && characterOffset <= 0) return emptyList()
    val startChapter = if (characterOffset > 0) chapterIndex else (chapterIndex - 1).coerceAtLeast(0)
    val startTextLength = content.chapters[startChapter].readingText().length
    val startOffset = if (startChapter == chapterIndex) {
        (characterOffset - MAX_PAGE_PROBE_CHARACTERS).coerceAtLeast(0)
    } else {
        (startTextLength - MAX_PAGE_PROBE_CHARACTERS).coerceAtLeast(0)
    }
    val pages = mutableListOf<ReadingPage>()
    val iterator = paginateBookFrom(
        content, textMeasurer, style, pageWidthPx, normalHeightPx, firstHeightPx,
        startChapter, startOffset,
    ).iterator()
    while (iterator.hasNext()) {
        val page = iterator.next()
        if (page.chapterIndex < chapterIndex ||
            (page.chapterIndex == chapterIndex && page.endOffset <= characterOffset)
        ) {
            pages.add(page)
            continue
        }
        if (page.chapterIndex == chapterIndex && page.startOffset < characterOffset) {
            val chapterText = content.chapters[chapterIndex].readingText()
            val text = chapterText.substring(
                page.startOffset.coerceIn(0, chapterText.length),
                characterOffset.coerceIn(0, chapterText.length),
            ).trimEnd()
            if (text.isNotBlank()) {
                pages.add(
                    page.copy(
                        endOffset = characterOffset,
                        text = text,
                        imagePath = null,
                        imageAltText = "",
                    )
                )
            }
        }
        break
    }
    return pages.takeLast(count)
}

/**
 * A page lifts slightly and gains a soft shadow as it slides past an adjacent page, then settles
 * back to flat (scale 1, no shadow) once it's fully at rest — a lighter-weight stand-in for a
 * true page curl, using only [pageOffset]'s magnitude so it looks correct regardless of swipe
 * direction.
 */
private fun Modifier.pageTurnEffect(pageOffset: Float): Modifier = graphicsLayer {
    val magnitude = kotlin.math.abs(pageOffset.coerceIn(-1f, 1f))
    val scale = 1f - (0.04f * magnitude)
    scaleX = scale
    scaleY = scale
    alpha = 1f - (0.08f * magnitude)
    shadowElevation = 10f * magnitude
    shape = RectangleShape
    clip = false
}

@Composable
private fun PageContent(
    book: BookRecord,
    chapterTitle: String,
    page: ReadingPage,
    preferences: ReaderPreferences,
    highlights: List<HighlightRecord>,
    textStyle: TextStyle,
    onToggleControls: () -> Unit,
    onSelection: (ReaderSelection) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSereinPalette.current
    val tapZoneWidth = if (preferences.wideTapZones) 82.dp else 34.dp
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = preferences.marginWidth.dp, vertical = 18.dp)) {
            if (page.isChapterStart) {
                Row(Modifier.fillMaxWidth().height(116.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            chapterTitle.uppercase(), color = palette.apricot, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            book.title, color = palette.ink, fontFamily = LiterataFamily,
                            fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Image(
                        painterResource(R.drawable.botanical_sprig), null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.padding(start = 14.dp).size(width = 30.dp, height = 92.dp).alpha(0.7f),
                    )
                }
            }
            if (page.imagePath != null) {
                EpubImageBlock(
                    imagePath = page.imagePath,
                    altText = page.imageAltText,
                    onClick = onToggleControls,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            } else {
                InteractiveReaderText(
                    page.text, page.chapterIndex, page.startOffset, preferences, highlights,
                    page.inlineSpans, textStyle, onToggleControls, onSelection, Modifier.fillMaxWidth(),
                )
            }
        }
        Box(Modifier.align(Alignment.CenterStart).width(tapZoneWidth).fillMaxHeight().clickable(onClick = onPrevious))
        Box(Modifier.align(Alignment.CenterEnd).width(tapZoneWidth).fillMaxHeight().clickable(onClick = onNext))
    }
}

@Composable
private fun ScrollingReader(
    book: BookRecord,
    content: BookContent,
    resumeLocation: ReadingLocation,
    preferences: ReaderPreferences,
    highlights: List<HighlightRecord>,
    target: ReaderTarget?,
    pageTurnRequest: PageTurnRequest?,
    onVisiblePosition: (VisiblePosition) -> Unit,
    onToggleControls: () -> Unit,
    onSelection: (ReaderSelection) -> Unit,
) {
    val palette = LocalSereinPalette.current
    val blocks = remember(content) { buildScrollBlocks(content) }
    val highlightsByChapter = remember(highlights) { highlights.groupBy(HighlightRecord::chapterIndex) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = blocks.blockIndexFor(
            resumeLocation.chapterIndex,
            resumeLocation.characterOffset,
        ),
        initialFirstVisibleItemScrollOffset = resumeLocation.scrollOffsetPx,
    )
    val bodySize = (preferences.textSize - 2).coerceIn(13, 28)
    val style = TextStyle(
        color = palette.ink,
        fontFamily = preferences.font.toFontFamily(),
        fontSize = bodySize.sp,
        lineHeight = (bodySize * preferences.lineHeight).sp,
    )
    val estimatedPages = remember(content, preferences.textSize) {
        ceil(content.totalCharacters() / (1700.0 * 18.0 / preferences.textSize)).toInt().coerceAtLeast(1)
    }
    LaunchedEffect(target?.token) {
        target?.let { listState.animateScrollToItem(blocks.blockIndexFor(it.chapterIndex, it.characterOffset)) }
    }
    LaunchedEffect(pageTurnRequest?.token) {
        pageTurnRequest?.let { request ->
            val destination = (listState.firstVisibleItemIndex + request.direction)
                .coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
            if (blocks.isNotEmpty()) listState.animateScrollToItem(destination)
        }
    }
    LaunchedEffect(listState, blocks) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (index, pixelOffset) ->
                delay(350)
                val block = blocks.getOrNull(index) ?: return@collectLatest
                val progress = content.progressAt(block.chapterIndex, block.startOffset)
                val estimatedPage = (progress * estimatedPages).toInt().coerceIn(0, estimatedPages - 1) + 1
                onVisiblePosition(
                    VisiblePosition(
                        ReadingLocation(block.chapterIndex, block.startOffset, pixelOffset, progress),
                        block.text.take(150), estimatedPage, estimatedPages,
                    )
                )
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            preferences.marginWidth.dp, 30.dp, preferences.marginWidth.dp, 44.dp,
        ),
    ) {
        items(
            items = blocks,
            key = { "${it.chapterIndex}:${it.startOffset}:${it.kind}" },
            contentType = ScrollBlock::kind,
        ) { block ->
            if (block.isChapterStart) {
                Text(
                    content.chapters[block.chapterIndex].title.uppercase(), color = palette.apricot,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp,
                    modifier = Modifier.padding(top = 22.dp),
                )
                Text(
                    book.title, color = palette.ink, fontFamily = LiterataFamily,
                    fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 22.dp),
                )
            }
            RichScrollBlock(
                block = block,
                preferences = preferences,
                highlights = highlightsByChapter[block.chapterIndex].orEmpty().filter {
                    it.endOffset > block.startOffset && it.startOffset < block.endOffset
                },
                bodyStyle = style,
                onToggleControls = onToggleControls,
                onSelection = onSelection,
            )
        }
    }
}

@Composable
private fun RichScrollBlock(
    block: ScrollBlock,
    preferences: ReaderPreferences,
    highlights: List<HighlightRecord>,
    bodyStyle: TextStyle,
    onToggleControls: () -> Unit,
    onSelection: (ReaderSelection) -> Unit,
) {
    val palette = LocalSereinPalette.current
    when (block.kind) {
        BookBlockKind.IMAGE -> {
            EpubImageBlock(
                imagePath = block.imagePath,
                altText = block.altText,
                onClick = onToggleControls,
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            )
        }
        BookBlockKind.SEPARATOR -> Text(
            "•  •  •",
            color = palette.apricot,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        BookBlockKind.HEADING -> InteractiveReaderText(
            block.text, block.chapterIndex, block.startOffset, preferences, highlights,
            block.inlineSpans,
            bodyStyle.copy(
                fontFamily = LiterataFamily,
                fontSize = (preferences.textSize + 3).sp,
                lineHeight = ((preferences.textSize + 3) * 1.28f).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            onToggleControls, onSelection,
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
        )
        BookBlockKind.QUOTE -> Row(
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
        ) {
            Box(Modifier.width(3.dp).height(76.dp).clip(RoundedCornerShape(3.dp)).background(palette.apricot))
            InteractiveReaderText(
                block.text, block.chapterIndex, block.startOffset, preferences, highlights,
                block.inlineSpans,
                bodyStyle.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                onToggleControls, onSelection,
                Modifier.weight(1f).padding(start = 15.dp),
            )
        }
        else -> InteractiveReaderText(
            block.text, block.chapterIndex, block.startOffset, preferences, highlights,
            block.inlineSpans, bodyStyle, onToggleControls, onSelection,
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
        )
    }
}

@Composable
private fun EpubImageBlock(
    imagePath: String?,
    altText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSereinPalette.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, imagePath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            imagePath?.takeIf { File(it).isFile }?.let(::decodeReaderImage)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image != null) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = image,
                contentDescription = altText,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio((image.width.toFloat() / image.height.coerceAtLeast(1)).coerceIn(0.55f, 1.8f))
                    .clickable(onClick = onClick),
            )
            if (altText.isNotBlank() && altText != "Illustration") {
                Text(altText, color = palette.mutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    } else if (altText.isNotBlank()) {
        Text(
            altText,
            color = palette.mutedInk,
            fontSize = 12.sp,
            modifier = modifier,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun decodeReaderImage(path: String): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = boundedBitmapSampleSize(
        width = bounds.outWidth,
        height = bounds.outHeight,
        maximumWidth = 1_600,
        maximumHeight = 2_000,
    )
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@Composable
private fun InteractiveReaderText(
    text: String,
    chapterIndex: Int,
    baseOffset: Int,
    preferences: ReaderPreferences,
    highlights: List<HighlightRecord>,
    inlineSpans: List<BookInlineSpan>,
    style: TextStyle,
    onTap: () -> Unit,
    onSelection: (ReaderSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val rendered = remember(text, baseOffset, preferences.bionicReading, highlights, inlineSpans) {
        styledReaderText(text, baseOffset, preferences.bionicReading, highlights, inlineSpans)
    }
    Text(
        text = rendered,
        style = style,
        textAlign = if (preferences.justifyText) {
            androidx.compose.ui.text.style.TextAlign.Justify
        } else {
            androidx.compose.ui.text.style.TextAlign.Start
        },
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(text, baseOffset) {
            detectTapGestures(
                onTap = { onTap() },
                onLongPress = { position ->
                    val layout = layoutResult ?: return@detectTapGestures
                    if (text.isEmpty()) return@detectTapGestures
                    val offset = layout.getOffsetForPosition(position).coerceIn(0, text.lastIndex)
                    val boundary: TextRange = layout.getWordBoundary(offset)
                    selectionFor(text, boundary.start, boundary.end, chapterIndex, baseOffset)?.let(onSelection)
                },
            )
        },
    )
}

private fun styledReaderText(
    text: String,
    baseOffset: Int,
    bionic: Boolean,
    highlights: List<HighlightRecord>,
    inlineSpans: List<BookInlineSpan>,
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (bionic) {
        BionicReading.forEachEmphasisRange(text) { range ->
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
        }
    }
    inlineSpans.forEach { span ->
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(0, text.length)
        if (start < end) addStyle(span.style.toSpanStyle(), start, end)
    }
    highlights.forEach { highlight ->
        val localStart = (highlight.startOffset - baseOffset).coerceIn(0, text.length)
        val localEnd = (highlight.endOffset - baseOffset).coerceIn(0, text.length)
        if (localStart < localEnd) {
            addStyle(SpanStyle(background = Color(0x4DD88B61)), localStart, localEnd)
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    bookmarked: Boolean,
    controlsVisible: Boolean,
    readingMode: ReadingMode,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onContents: () -> Unit,
    onSearch: () -> Unit,
    onSaved: () -> Unit,
    onVocabulary: () -> Unit,
    onSettings: () -> Unit,
    onModeChange: (ReadingMode) -> Unit,
) {
    val palette = LocalSereinPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = palette.paper,
        tonalElevation = 0.dp,
        modifier = Modifier
            .statusBarsPadding()
            .alpha(if (controlsVisible) 1f else 0f),
    ) {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library", tint = palette.ink) }
            Text(
                title, Modifier.weight(1f).padding(horizontal = 8.dp), color = palette.ink,
                fontFamily = LiterataFamily, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onContents) { Icon(Icons.AutoMirrored.Rounded.MenuBook, "Contents", tint = palette.ink) }
            IconButton(onClick = onBookmark) {
                Icon(
                    if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    if (bookmarked) "Remove bookmark" else "Add bookmark",
                    tint = if (bookmarked) palette.sage else palette.ink,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More reading options", tint = palette.ink) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Search in book") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        onClick = { menuOpen = false; onSearch() },
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmarks & highlights") }, leadingIcon = { Icon(Icons.Rounded.Bookmarks, null) },
                        onClick = { menuOpen = false; onSaved() },
                    )
                    DropdownMenuItem(
                        text = { Text("Vocabulary history") }, leadingIcon = { Icon(Icons.Rounded.EditNote, null) },
                        onClick = { menuOpen = false; onVocabulary() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (readingMode == ReadingMode.PAGED) "Continuous scrolling" else "Paged reading") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null) },
                        onClick = {
                            menuOpen = false
                            onModeChange(if (readingMode == ReadingMode.PAGED) ReadingMode.SCROLL else ReadingMode.PAGED)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Reading settings") }, leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                        onClick = { menuOpen = false; onSettings() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderFooter(progress: Float, page: Int, pageCount: Int, paged: Boolean, minutesLeft: Int?) {
    val palette = LocalSereinPalette.current
    val pagesLeft = (pageCount - page).coerceAtLeast(0)
    Surface(
        color = palette.paper,
        tonalElevation = 0.dp,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp)),
                color = palette.sage, trackColor = palette.line,
            )
            Row(Modifier.padding(top = 7.dp)) {
                Text(readingTimeSummary(progress, minutesLeft), color = palette.mutedInk, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    pageSummary(pagesLeft, page, pageCount, paged),
                    color = palette.mutedInk, fontSize = 11.sp,
                )
            }
        }
    }
}

internal fun pageSummary(pagesLeft: Int, page: Int, pageCount: Int, paged: Boolean): String {
    val pageLabel = if (pagesLeft == 1) "page" else "pages"
    val prefix = if (paged) "" else "about "
    return "$prefix$pagesLeft $pageLabel left  ·  $page / $pageCount"
}

internal fun readingTimeSummary(progress: Float, minutesLeft: Int?): String {
    val percentLabel = "${(progress * 100).toInt()}% read"
    val timeLabel = when {
        minutesLeft == null -> null
        progress >= 0.995f -> null
        minutesLeft <= 0 -> "under a minute left"
        minutesLeft == 1 -> "1 min left"
        minutesLeft < 60 -> "$minutesLeft min left"
        else -> {
            val hours = minutesLeft / 60
            val remainderMinutes = minutesLeft % 60
            if (remainderMinutes == 0) "$hours hr left" else "${hours}h ${remainderMinutes}m left"
        }
    }
    return if (timeLabel != null) "$percentLabel  ·  $timeLabel" else percentLabel
}

@Composable
private fun SelectionBar(
    selection: ReaderSelection,
    onDismiss: () -> Unit,
    onDefine: () -> Unit,
    onHighlightWord: () -> Unit,
    onHighlightSentence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSereinPalette.current
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        color = palette.sheet, contentColor = palette.ink,
        shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "“${selection.word}”", Modifier.weight(1f), fontFamily = LiterataFamily,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, "Dismiss text actions", modifier = Modifier.size(18.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onDefine) { Text("Define") }
                TextButton(onClick = onHighlightWord) { Text("Highlight word") }
                TextButton(onClick = onHighlightSentence) { Text("Sentence") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderHubSheet(
    content: BookContent?,
    activePanel: ReaderPanel,
    bookmarks: List<BookmarkRecord>,
    highlights: List<HighlightRecord>,
    vocabulary: List<VocabularyRecord>,
    onPanelChange: (ReaderPanel) -> Unit,
    onNavigate: (Int, Int) -> Unit,
    onRemoveBookmark: (BookmarkRecord) -> Unit,
    onRemoveHighlight: (HighlightRecord) -> Unit,
    onUpdateHighlightNote: (HighlightRecord, String) -> Unit,
    onExport: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalSereinPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var editingHighlight by remember { mutableStateOf<HighlightRecord?>(null) }
    val results = remember(content, query) { content?.search(query).orEmpty() }
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = palette.sheet, contentColor = palette.ink,
        dragHandle = null, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = palette.ink.copy(alpha = 0.08f),
    ) {
        Column(Modifier.fillMaxWidth().height(570.dp).padding(top = 10.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(4.dp)).background(palette.mutedInk.copy(alpha = 0.35f))
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    ReaderPanel.CONTENTS to "Contents",
                    ReaderPanel.SEARCH to "Search",
                    ReaderPanel.SAVED to "Saved",
                    ReaderPanel.VOCABULARY to "Words",
                ).forEach { (panel, label) ->
                    Surface(
                        Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).clickable { onPanelChange(panel) },
                        color = if (activePanel == panel) palette.selectedControl else palette.control,
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Text(
                            label, color = if (activePanel == panel) palette.sage else palette.ink,
                            fontSize = 13.sp, fontWeight = if (activePanel == panel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 11.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            when (activePanel) {
                ReaderPanel.CONTENTS -> LazyColumn(Modifier.fillMaxSize()) {
                    items(content?.chapters.orEmpty().withIndex().toList()) { (index, chapter) ->
                        SheetRow(chapter.title, "Chapter ${index + 1}") { onNavigate(index, 0) }
                    }
                }
                ReaderPanel.SEARCH -> Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Search this book") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } }
                        } else null,
                        singleLine = true, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (query.length >= 2 && results.isEmpty()) item { EmptySheetMessage("No matches in this book.") }
                        items(results) { result -> SearchResultRow(result, onNavigate) }
                    }
                }
                ReaderPanel.SAVED -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                            TextButton(onClick = { onExport(true) }) {
                                Icon(Icons.Rounded.Share, null, modifier = Modifier.size(17.dp))
                                Text("Markdown", modifier = Modifier.padding(start = 5.dp))
                            }
                            TextButton(onClick = { onExport(false) }) { Text("Plain text") }
                        }
                    }
                    item { SheetSectionTitle("Bookmarks") }
                    if (bookmarks.isEmpty()) item { EmptySheetMessage("No bookmarks yet.") }
                    items(bookmarks, key = { it.id }) { bookmark ->
                        SavedRow(
                            content?.chapters?.getOrNull(bookmark.chapterIndex)?.title.orEmpty(), bookmark.excerpt,
                            { onNavigate(bookmark.chapterIndex, bookmark.characterOffset) }, { onRemoveBookmark(bookmark) },
                        )
                    }
                    item { SheetSectionTitle("Highlights") }
                    if (highlights.isEmpty()) item { EmptySheetMessage("Long-press a word to save a highlight.") }
                    items(highlights, key = { it.id }) { highlight ->
                        SavedRow(
                            content?.chapters?.getOrNull(highlight.chapterIndex)?.title.orEmpty(), highlight.text,
                            { onNavigate(highlight.chapterIndex, highlight.startOffset) },
                            { onRemoveHighlight(highlight) }, true,
                            note = highlight.note,
                            onEditNote = { editingHighlight = highlight },
                        )
                    }
                }
                ReaderPanel.VOCABULARY -> LazyColumn(Modifier.fillMaxSize()) {
                    if (vocabulary.isEmpty()) item {
                        EmptySheetMessage("Long-press a word and choose Define. Lookups are saved here for review.")
                    }
                    items(vocabulary, key = { "${it.word}:${it.lookedUpAt}" }) { item ->
                        SheetRow(
                            title = item.word + (item.phonetic?.let { "  $it" } ?: ""),
                            subtitle = item.definition,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
    editingHighlight?.let { highlight ->
        HighlightNoteDialog(
            highlight = highlight,
            onDismiss = { editingHighlight = null },
            onSave = { note -> onUpdateHighlightNote(highlight, note); editingHighlight = null },
        )
    }
}

@Composable
private fun SheetRow(title: String, subtitle: String, onClick: () -> Unit) {
    val palette = LocalSereinPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.ink, fontFamily = LiterataFamily, fontSize = 16.sp)
            Text(subtitle, color = palette.mutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = palette.mutedInk)
    }
    HorizontalDivider(color = palette.line, modifier = Modifier.padding(start = 22.dp))
}

@Composable
private fun SearchResultRow(result: BookSearchResult, onNavigate: (Int, Int) -> Unit) {
    val palette = LocalSereinPalette.current
    Column(
        Modifier.fillMaxWidth().clickable { onNavigate(result.chapterIndex, result.characterOffset) }
            .padding(horizontal = 22.dp, vertical = 13.dp),
    ) {
        Text(result.chapterTitle, color = palette.sage, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            result.excerpt, color = palette.ink, fontFamily = LiterataFamily, fontSize = 14.sp,
            lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
    HorizontalDivider(color = palette.line, modifier = Modifier.padding(start = 22.dp))
}

@Composable
private fun SavedRow(
    title: String,
    excerpt: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    highlighted: Boolean = false,
    note: String = "",
    onEditNote: (() -> Unit)? = null,
) {
    val palette = LocalSereinPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 22.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (highlighted) {
            Box(
                Modifier.padding(end = 12.dp).size(width = 4.dp, height = 46.dp)
                    .clip(RoundedCornerShape(3.dp)).background(palette.apricot.copy(alpha = 0.7f))
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.sage, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                excerpt, color = palette.ink, fontFamily = LiterataFamily, fontSize = 14.sp,
                lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (note.isNotBlank()) {
                Text(
                    note, color = palette.mutedInk, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        onEditNote?.let { edit ->
            IconButton(onClick = edit) {
                Icon(Icons.Rounded.EditNote, if (note.isBlank()) "Add note" else "Edit note", tint = palette.sage)
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = palette.mutedInk) }
    }
}

@Composable
private fun HighlightNoteDialog(
    highlight: HighlightRecord,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var note by remember(highlight.id) { mutableStateOf(highlight.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (highlight.note.isBlank()) "Add a note" else "Edit note") },
        text = {
            Column {
                Text(
                    "“${highlight.text}”", color = LocalSereinPalette.current.mutedInk,
                    fontFamily = LiterataFamily, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Your thought…") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SheetSectionTitle(text: String) {
    Text(
        text, color = LocalSereinPalette.current.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptySheetMessage(text: String) {
    Text(
        text, color = LocalSereinPalette.current.mutedInk, fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DictionarySheet(
    state: DictionaryState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {},
    inline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSereinPalette.current
    val context = LocalContext.current
    val sheetContent: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp)
        ) {
            when (state) {
                DictionaryState.Idle -> Unit
                is DictionaryState.Loading -> {
                    Text(state.word, fontFamily = LiterataFamily, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = palette.sage, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Looking up definition…", color = palette.mutedInk, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                is DictionaryState.Error -> {
                    Text(state.word, fontFamily = LiterataFamily, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.message, color = palette.mutedInk, lineHeight = 22.sp, modifier = Modifier.padding(top = 14.dp))
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onRetry) { Text("Try again") }
                        TextButton(
                            onClick = {
                                val search = Intent(Intent.ACTION_WEB_SEARCH)
                                    .putExtra(SearchManager.QUERY, "define ${state.word}")
                                runCatching { context.startActivity(search) }.onFailure {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://www.google.com/search?q=define+${Uri.encode(state.word)}"),
                                        )
                                    )
                                }
                            },
                        ) { Text("Search the web") }
                    }
                }
                is DictionaryState.Found -> {
                    Text(
                        state.entry.word,
                        fontFamily = LiterataFamily,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.entry.phonetic?.let {
                        Text(it, color = palette.apricot, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    state.entry.meanings.forEach { meaning ->
                        Text(
                            meaning.partOfSpeech.ifBlank { "meaning" }, color = palette.sage,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp),
                        )
                        meaning.definitions.forEachIndexed { index, definition ->
                            Text(
                                "${index + 1}.  $definition", color = palette.ink, fontFamily = LiterataFamily,
                                fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        meaning.examples.take(2).forEach { example ->
                            Text(
                                "“$example”", color = palette.mutedInk,
                                fontFamily = LiterataFamily, fontSize = 13.sp,
                                lineHeight = 19.sp, modifier = Modifier.padding(top = 7.dp),
                            )
                        }
                        if (meaning.synonyms.isNotEmpty()) {
                            Text(
                                "Synonyms  ${meaning.synonyms.take(6).joinToString(" · ")}",
                                color = palette.apricot, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    Text(
                        "Offline · ${state.entry.source}",
                        color = palette.mutedInk,
                        fontSize = 10.sp, modifier = Modifier.padding(top = 22.dp),
                    )
                }
            }
        }
    }
    if (inline) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = palette.sheet,
            contentColor = palette.ink,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 18.dp,
        ) { sheetContent() }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss, containerColor = palette.sheet, contentColor = palette.ink,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            scrimColor = palette.ink.copy(alpha = 0.08f),
        ) { sheetContent() }
    }
}

private const val READING_HEARTBEAT_MS = 30_000L
private const val INITIAL_PREVIOUS_PAGE_COUNT = 1
private const val INITIAL_FORWARD_PAGE_COUNT = 3
private const val PAGE_PREFETCH_DISTANCE = 1
private const val PAGE_LOAD_BATCH_SIZE = 3
private const val PAGINATION_SAMPLE_SIZE = 24
