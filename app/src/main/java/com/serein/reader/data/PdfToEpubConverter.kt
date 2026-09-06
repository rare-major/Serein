package com.serein.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts a PDF into a minimal, spec-valid EPUB3 package so it can flow through the same
 * storage, parsing, and reading pipeline as every other book ([EpubParser], [BookRepository]).
 *
 * Chapter boundaries come from the PDF's own outline (bookmarks) when present, falling back to
 * fixed-size page chunks. Only the text layer is recovered; scanned or image-only PDFs have no
 * extractable text and are rejected rather than silently imported as empty books.
 */
internal object PdfToEpubConverter {
    internal data class ChapterSpec(val title: String, val startPage: Int, val endPageExclusive: Int)

    @Volatile
    private var resourceLoaderReady = false

    fun convert(context: Context, sourcePdf: File, targetEpub: File, fallbackTitle: String) {
        if (!resourceLoaderReady) {
            synchronized(this) {
                if (!resourceLoaderReady) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    resourceLoaderReady = true
                }
            }
        }
        val coverBytes = renderCoverThumbnail(sourcePdf)
        PDDocument.load(sourcePdf).use { document ->
            val pageCount = document.numberOfPages
            require(pageCount in 1..MAX_PDF_PAGES) {
                if (pageCount == 0) "This PDF has no pages." else "This PDF has too many pages for Serein to convert."
            }
            val info = document.documentInformation
            val title = info?.title?.trim().orEmpty().ifBlank { fallbackTitle }
            val author = info?.author?.trim().orEmpty().ifBlank { "Unknown author" }

            val textBudget = ByteBudget(MAX_TOTAL_TEXT_CHARS, "This PDF's text content")
            val chapters = chapterSpecs(document, pageCount)
                .map { spec -> spec to extractParagraphs(document, spec, textBudget) }
                .filter { (_, paragraphs) -> paragraphs.isNotEmpty() }
            require(chapters.isNotEmpty()) {
                "This PDF appears to contain no extractable text. Scanned or image-only PDFs aren't supported yet."
            }

            writeEpub(targetEpub, title, author, chapters, coverBytes)
        }
    }

    private fun chapterSpecs(document: PDDocument, pageCount: Int): List<ChapterSpec> {
        val fromOutline = outlineChapterSpecs(document, pageCount)
        val base = if (fromOutline != null && fromOutline.size >= 2) fromOutline else pageChunkSpecs(pageCount)
        return base.flatMap { spec -> splitToMaxSpan(spec) }
    }

    private fun outlineChapterSpecs(document: PDDocument, pageCount: Int): List<ChapterSpec>? {
        val outline = runCatching { document.documentCatalog?.documentOutline }.getOrNull() ?: return null
        val entries = mutableListOf<Pair<String, Int>>()
        fun visit(node: PDOutlineNode, depth: Int) {
            if (depth > MAX_OUTLINE_DEPTH || entries.size >= MAX_OUTLINE_ENTRIES) return
            for (item: PDOutlineItem in node.children()) {
                if (entries.size >= MAX_OUTLINE_ENTRIES) return
                val page = runCatching { item.findDestinationPage(document) }.getOrNull()
                    ?.let(document.pages::indexOf)
                    ?.takeIf { it in 0 until pageCount }
                val title = item.title?.trim().orEmpty()
                if (page != null && title.isNotEmpty()) entries.add(title to page)
                visit(item, depth + 1)
            }
        }
        visit(outline, 0)
        val ordered = entries.sortedBy { it.second }.distinctBy { it.second }
        if (ordered.isEmpty()) return null

        return buildList {
            val firstStart = ordered.first().second
            if (firstStart > 0) add(ChapterSpec("Beginning", 0, firstStart))
            ordered.forEachIndexed { index, (title, start) ->
                val end = ordered.getOrNull(index + 1)?.second ?: pageCount
                if (start < end) add(ChapterSpec(title, start, end))
            }
        }
    }

    internal fun pageChunkSpecs(pageCount: Int): List<ChapterSpec> {
        val pagesPerChapter = maxOf(MAX_PAGES_PER_CHAPTER, (pageCount + MAX_CHUNKED_CHAPTERS - 1) / MAX_CHUNKED_CHAPTERS)
        return (0 until pageCount step pagesPerChapter).mapIndexed { index, start ->
            ChapterSpec("Chapter ${index + 1}", start, minOf(start + pagesPerChapter, pageCount))
        }
    }

    internal fun splitToMaxSpan(spec: ChapterSpec): List<ChapterSpec> {
        val span = spec.endPageExclusive - spec.startPage
        if (span <= MAX_PAGES_PER_CHAPTER) return listOf(spec)
        return (spec.startPage until spec.endPageExclusive step MAX_PAGES_PER_CHAPTER).mapIndexed { index, start ->
            val end = minOf(start + MAX_PAGES_PER_CHAPTER, spec.endPageExclusive)
            val title = if (index == 0) spec.title else "${spec.title} (continued)"
            ChapterSpec(title, start, end)
        }
    }

    private fun extractParagraphs(document: PDDocument, spec: ChapterSpec, budget: ByteBudget): List<String> {
        val stripper = PDFTextStripper().apply {
            startPage = spec.startPage + 1
            endPage = spec.endPageExclusive
            lineSeparator = "\n"
            paragraphEnd = "\n\n"
            sortByPosition = true
            spacingTolerance = 2.0f
        }
        val raw = stripper.getText(document)
        budget.consume(raw.length)
        return raw.split(PARAGRAPH_BREAK)
            .map(::cleanParagraph)
            .filter(String::isNotBlank)
    }

    internal fun cleanParagraph(raw: String): String {
        val dehyphenated = raw.replace(HYPHENATED_LINE_BREAK, "")
        val joined = dehyphenated.replace('\n', ' ')
        val stripped = joined.filterNot { it.code in 0x00..0x08 || it.code in 0x0E..0x1F }
        return stripped.replace(WHITESPACE_RUN, " ").trim()
    }

    private fun renderCoverThumbnail(sourcePdf: File): ByteArray? = runCatching {
        ParcelFileDescriptor.open(sourcePdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return@runCatching null
                renderer.openPage(0).use { page ->
                    val width = COVER_WIDTH_PX
                    val height = (page.height.toFloat() / page.width * width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        bitmap.recycle()
                        out.toByteArray()
                    }
                }
            }
        }
    }.getOrNull()

    private fun writeEpub(
        targetEpub: File,
        title: String,
        author: String,
        chapters: List<Pair<ChapterSpec, List<String>>>,
        coverBytes: ByteArray?,
    ) {
        ZipOutputStream(targetEpub.outputStream()).use { zip ->
            writeStoredEntry(zip, "mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            writeEntry(zip, "META-INF/container.xml", containerXml())
            writeEntry(zip, "OEBPS/nav.xhtml", navXhtml(title, chapters.map { it.first.title }))
            if (coverBytes != null) writeEntry(zip, "OEBPS/cover.jpg", coverBytes)
            chapters.forEachIndexed { index, (spec, paragraphs) ->
                writeEntry(zip, "OEBPS/chapter${index + 1}.xhtml", chapterXhtml(spec.title, paragraphs))
            }
            writeEntry(zip, "OEBPS/content.opf", packageOpf(title, author, chapters.size, coverBytes != null))
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) =
        writeEntry(zip, name, content.toByteArray(Charsets.UTF_8))

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeStoredEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun containerXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    // trimIndent() strips the *smallest* leading-whitespace among all lines. A multi-line
    // interpolated value (a joined list of items or paragraphs) contributes lines of its own,
    // and if those happen to be less indented than the template, trimIndent under-strips the
    // whole template — leaving stray whitespace before the XML declaration itself, which every
    // strict XML parser (including our own) rejects outright. Trimming the static skeleton by
    // itself first, then substituting multi-line content into a placeholder afterwards, keeps
    // the two indentation schemes from ever interfering with each other.
    internal fun packageOpf(title: String, author: String, chapterCount: Int, hasCover: Boolean): String {
        val modified = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val coverMeta = if (hasCover) "<meta name=\"cover\" content=\"cover-image\"/>" else ""
        val coverItem = if (hasCover) {
            "<item id=\"cover-image\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>"
        } else ""
        val chapterItems = (1..chapterCount).joinToString("\n") { index ->
            "<item id=\"chapter$index\" href=\"chapter$index.xhtml\" media-type=\"application/xhtml+xml\"/>"
        }
        val spineItems = (1..chapterCount).joinToString("\n") { index ->
            "<itemref idref=\"chapter$index\"/>"
        }
        val template = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">urn:uuid:${UUID.randomUUID()}</dc:identifier>
                <dc:title>${escapeXml(title)}</dc:title>
                <dc:creator>${escapeXml(author)}</dc:creator>
                <dc:language>en</dc:language>
                <meta property="dcterms:modified">$modified</meta>
                $coverMeta
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                $coverItem
                __CHAPTER_ITEMS__
              </manifest>
              <spine>
                __SPINE_ITEMS__
              </spine>
            </package>
        """.trimIndent()
        return template
            .replace("__CHAPTER_ITEMS__", chapterItems)
            .replace("__SPINE_ITEMS__", spineItems)
    }

    internal fun navXhtml(title: String, chapterTitles: List<String>): String {
        val items = chapterTitles.mapIndexed { index, chapterTitle ->
            "<li><a href=\"chapter${index + 1}.xhtml\">${escapeXml(chapterTitle)}</a></li>"
        }.joinToString("\n")
        val template = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>${escapeXml(title)}</title></head>
              <body>
                <nav epub:type="toc" id="toc">
                  <h1>Contents</h1>
                  <ol>
                    __ITEMS__
                  </ol>
                </nav>
              </body>
            </html>
        """.trimIndent()
        return template.replace("__ITEMS__", items)
    }

    internal fun chapterXhtml(title: String, paragraphs: List<String>): String {
        val body = paragraphs.joinToString("\n") { paragraph -> "<p>${escapeXml(paragraph)}</p>" }
        val template = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>${escapeXml(title)}</title></head>
              <body>
                <h1>${escapeXml(title)}</h1>
                __BODY__
              </body>
            </html>
        """.trimIndent()
        return template.replace("__BODY__", body)
    }

    internal fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val PARAGRAPH_BREAK = Regex("\n{2,}")
    private val HYPHENATED_LINE_BREAK = Regex("-\n(?=\\p{Ll})")
    private val WHITESPACE_RUN = Regex("[ \\t\\x0B\\f\\r]+")
    private const val MAX_PDF_PAGES = 5_000
    private const val MAX_PAGES_PER_CHAPTER = 40
    private const val MAX_CHUNKED_CHAPTERS = 400
    private const val MAX_OUTLINE_ENTRIES = 500
    private const val MAX_OUTLINE_DEPTH = 3
    private const val MAX_TOTAL_TEXT_CHARS = 15_000_000L
    private const val COVER_WIDTH_PX = 480
}
