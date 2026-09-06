package com.serein.reader.data

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.file.Paths
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

object EpubParser {
    private class AssetBudget(val bytes: ByteBudget) {
        private val paths = mutableSetOf<String>()

        fun register(path: String): Boolean {
            if (!paths.add(path)) return false
            require(paths.size <= MAX_IMAGE_COUNT) { "This EPUB contains too many images." }
            return true
        }
    }

    private data class PackageData(
        val opfPath: String,
        val document: Document,
        val manifest: Map<String, ManifestItem>,
    )

    private data class ManifestItem(
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    fun parseMetadata(epubFile: File, coverDirectory: File): ParsedEpub {
        ZipFile(epubFile).use { zip ->
            validateArchive(zip)
            val xmlBudget = ByteBudget(MAX_TOTAL_XML_BYTES, "The EPUB's XML content")
            val packageData = readPackage(zip, xmlBudget)
            val document = packageData.document
            val title = firstText(document, "title").ifBlank {
                epubFile.nameWithoutExtension.replace('_', ' ').replace('-', ' ')
            }
            val author = firstText(document, "creator").ifBlank { "Unknown author" }
            val coverBudget = ByteBudget(
                storageBudget(coverDirectory, MAX_COVER_BYTES, MIN_FREE_SPACE_BYTES),
                "The EPUB cover",
            )
            val coverPath = extractCover(
                zip,
                packageData,
                coverDirectory,
                epubFile.nameWithoutExtension,
                coverBudget,
            )
            return ParsedEpub(title.trim(), author.trim(), coverPath)
        }
    }

    fun readContent(epubFile: File, assetDirectory: File? = null): BookContent {
        ZipFile(epubFile).use { zip ->
            validateArchive(zip)
            val xmlBudget = ByteBudget(MAX_TOTAL_XML_BYTES, "The EPUB's XML content")
            val assetBudget = assetDirectory?.let { directory ->
                require(directory.mkdirs() || directory.isDirectory) {
                    "Serein could not prepare storage for this EPUB's images."
                }
                AssetBudget(
                    bytes = ByteBudget(
                        storageBudget(directory, MAX_TOTAL_ASSET_BYTES, MIN_FREE_SPACE_BYTES),
                        "The EPUB's extracted images",
                    )
                )
            }
            val packageData = readPackage(zip, xmlBudget)
            val spineIds = elements(packageData.document, "itemref")
                .mapNotNull { it.getAttribute("idref").takeIf(String::isNotBlank) }
            require(spineIds.size <= MAX_SPINE_CHAPTERS) { "This EPUB contains too many chapters." }

            val parsedChapters = spineIds.mapNotNull { id ->
                val item = packageData.manifest[id] ?: return@mapNotNull null
                if (!item.mediaType.contains("html") && !item.mediaType.contains("xhtml")) {
                    return@mapNotNull null
                }
                val entryPath = resolvePath(packageData.opfPath, item.href)
                val entry = zip.getEntry(entryPath) ?: return@mapNotNull null
                entryPath to parseChapter(zip, entryPath, assetDirectory, xmlBudget, assetBudget)
            }.filter { (_, parsed) -> parsed.chapter.paragraphs.any(String::isNotBlank) }

            if (parsedChapters.isEmpty()) {
                return BookContent(
                    listOf(
                        BookChapter(
                            "Start reading",
                            listOf("Serein could not extract the text from this EPUB. The file may use an unsupported layout or encryption."),
                        )
                    )
                )
            }
            val pathToChapterIndex = parsedChapters.withIndex()
                .associate { (index, entry) -> entry.first to index }
            val chapters = parsedChapters.mapIndexed { chapterIndex, (chapterPath, parsed) ->
                resolveInternalLinks(parsed.chapter, chapterIndex, chapterPath, pathToChapterIndex) { targetIndex ->
                    parsedChapters[targetIndex].second.anchorOffsets
                }
            }
            return BookContent(chapters)
        }
    }

    private fun readPackage(zip: ZipFile, xmlBudget: ByteBudget): PackageData {
        val containerEntry = zip.getEntry("META-INF/container.xml")
            ?: error("This file is missing EPUB container metadata.")
        val container = zip.getInputStream(containerEntry).use { parseXml(it, xmlBudget) }
        val rootFile = elements(container, "rootfile").firstOrNull()
            ?: error("This EPUB does not declare a package document.")
        val opfPath = rootFile.getAttribute("full-path")
            .ifBlank { error("The EPUB package path is empty.") }
        val packageEntry = zip.getEntry(opfPath)
            ?: error("The EPUB package document could not be found.")
        val document = zip.getInputStream(packageEntry).use { parseXml(it, xmlBudget) }
        val manifest = elements(document, "item").associate { item ->
            item.getAttribute("id") to ManifestItem(
                href = item.getAttribute("href"),
                mediaType = item.getAttribute("media-type"),
                properties = item.getAttribute("properties"),
            )
        }
        return PackageData(opfPath, document, manifest)
    }

    private fun extractCover(
        zip: ZipFile,
        packageData: PackageData,
        coverDirectory: File,
        bookId: String,
        coverBudget: ByteBudget,
    ): String? {
        val metadataCoverId = elements(packageData.document, "meta")
            .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
            ?.getAttribute("content")
        val coverItem = metadataCoverId?.let(packageData.manifest::get)
            ?: packageData.manifest.values.firstOrNull {
                it.properties.split(' ').contains("cover-image")
            }
            ?: return null
        val path = resolvePath(packageData.opfPath, coverItem.href)
        val entry = zip.getEntry(path) ?: return null
        val extension = when {
            coverItem.mediaType.contains("png") -> "png"
            coverItem.mediaType.contains("webp") -> "webp"
            else -> "jpg"
        }
        coverDirectory.mkdirs()
        val safeName = bookId.filter(Char::isLetterOrDigit).ifBlank { "book" }
        val output = ownedDirectChild(coverDirectory, "$safeName.$extension")
        try {
            zip.getInputStream(entry).use { input ->
                output.outputStream().use { destination ->
                    copyWithLimit(input, destination, MAX_COVER_BYTES, "The EPUB cover", coverBudget)
                }
            }
        } catch (exception: Exception) {
            output.delete()
            throw exception
        }
        return output.absolutePath
    }

    /** A parsed chapter plus where its anchor ids land, so another chapter's link can resolve into it. */
    private data class ParsedChapter(val chapter: BookChapter, val anchorOffsets: Map<String, Int>)

    private fun parseChapter(
        zip: ZipFile,
        chapterPath: String,
        assetDirectory: File?,
        xmlBudget: ByteBudget,
        assetBudget: AssetBudget?,
    ): ParsedChapter {
        return try {
            val entry = zip.getEntry(chapterPath) ?: error("Chapter is missing")
            val document = zip.getInputStream(entry).use { parseXml(it, xmlBudget) }
            val title = listOf("h1", "h2", "h3", "title")
                .firstNotNullOfOrNull { tag -> firstText(document, tag).takeIf(String::isNotBlank) }
                ?: chapterPath.substringAfterLast('/').substringBeforeLast('.')
            val body = elements(document, "body").firstOrNull()
            val blocks = body?.let {
                extractBlocks(it, zip, chapterPath, assetDirectory, assetBudget)
            }.orEmpty().ifEmpty {
                val fallback = normalizeWhitespace(body?.textContent.orEmpty())
                if (fallback.isBlank()) emptyList() else listOf(BookBlock(fallback))
            }
            val contentBlocks = blocks.toMutableList().apply {
                val first = firstOrNull()
                if (first?.kind == BookBlockKind.HEADING && first.text.equals(title, ignoreCase = true)) {
                    removeAt(0)
                }
            }
            val paragraphs = mutableListOf<String>()
            val anchorOffsets = mutableMapOf<String, Int>()
            var runningOffset = 0
            contentBlocks.forEach { block ->
                val representation = when (block.kind) {
                    BookBlockKind.IMAGE -> block.altText.takeIf(String::isNotBlank)?.let { "[$it]" }
                    BookBlockKind.SEPARATOR -> "• • •"
                    else -> block.text.takeIf(String::isNotBlank)
                }
                if (representation != null) {
                    block.anchorId?.let { id -> anchorOffsets.putIfAbsent(id, runningOffset) }
                    paragraphs.add(representation)
                    runningOffset += representation.length + 2
                }
            }
            ParsedChapter(BookChapter(title, paragraphs, contentBlocks), anchorOffsets)
        } catch (exception: ArchiveQuotaException) {
            throw exception
        } catch (_: Exception) {
            ParsedChapter(
                BookChapter(
                    chapterPath.substringAfterLast('/').substringBeforeLast('.'),
                    listOf("This chapter could not be rendered because its markup is not valid XHTML."),
                ),
                emptyMap(),
            )
        }
    }

    /**
     * Resolves each `<a href>`-derived [BookInlineSpan] in this chapter to a concrete
     * (chapterIndex, characterOffset) when it points inside this same book — e.g. a real Index or
     * Contents page linking to a heading elsewhere. Links that don't resolve (external URLs,
     * hrefs pointing at a non-chapter manifest item, missing anchors) are left inert rather than
     * guessed at.
     */
    private fun resolveInternalLinks(
        chapter: BookChapter,
        chapterIndex: Int,
        chapterPath: String,
        pathToChapterIndex: Map<String, Int>,
        anchorOffsetsFor: (Int) -> Map<String, Int>,
    ): BookChapter {
        fun hasResolvableLink(spans: List<BookInlineSpan>) =
            spans.any { it.style == BookInlineStyle.UNDERLINE && !it.target.isNullOrBlank() }
        if (chapter.blocks.none { hasResolvableLink(it.inlineSpans) }) return chapter

        val resolvedBlocks = chapter.blocks.map { block ->
            if (!hasResolvableLink(block.inlineSpans)) return@map block
            block.copy(
                inlineSpans = block.inlineSpans.map { span ->
                    if (span.style != BookInlineStyle.UNDERLINE || span.target.isNullOrBlank()) return@map span
                    val resolved = resolveHref(span.target, chapterPath, chapterIndex, pathToChapterIndex, anchorOffsetsFor)
                    if (resolved == null) span else span.copy(
                        targetChapterIndex = resolved.first,
                        targetCharacterOffset = resolved.second,
                    )
                },
            )
        }
        return chapter.copy(blocks = resolvedBlocks)
    }

    private fun resolveHref(
        rawHref: String,
        currentChapterPath: String,
        currentChapterIndex: Int,
        pathToChapterIndex: Map<String, Int>,
        anchorOffsetsFor: (Int) -> Map<String, Int>,
    ): Pair<Int, Int>? {
        if (rawHref.contains("://") || rawHref.startsWith("mailto:")) return null
        val decoded = runCatching { URLDecoder.decode(rawHref, Charsets.UTF_8.name()) }.getOrDefault(rawHref)
        val hashIndex = decoded.indexOf('#')
        val pathPart = if (hashIndex >= 0) decoded.substring(0, hashIndex) else decoded
        val fragment = (if (hashIndex >= 0) decoded.substring(hashIndex + 1) else null)?.takeIf(String::isNotBlank)

        val targetChapterIndex = if (pathPart.isBlank()) {
            currentChapterIndex
        } else {
            pathToChapterIndex[resolvePath(currentChapterPath, pathPart)] ?: return null
        }
        val offset = fragment?.let { anchorOffsetsFor(targetChapterIndex)[it] ?: return null } ?: 0
        return targetChapterIndex to offset
    }

    private fun extractBlocks(
        root: Element,
        zip: ZipFile,
        chapterPath: String,
        assetDirectory: File?,
        assetBudget: AssetBudget?,
    ): List<BookBlock> = buildList {
        fun visit(node: Node) {
            if (node !is Element) return
            val tag = (node.localName ?: node.tagName).lowercase()
            when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> normalizedBlock(node, BookBlockKind.HEADING)?.let(::add)
                "p", "pre" -> {
                    normalizedBlock(node, BookBlockKind.PARAGRAPH)?.let(::add)
                    descendantImages(node).forEach { image ->
                        extractImage(image, zip, chapterPath, assetDirectory, assetBudget)?.let(::add)
                    }
                }
                "blockquote" -> normalizedBlock(node, BookBlockKind.QUOTE)?.let(::add)
                "li" -> normalizedBlock(node, BookBlockKind.LIST_ITEM)?.let(::add)
                "figcaption", "caption", "td", "th" -> normalizedBlock(node, BookBlockKind.PARAGRAPH)?.let(::add)
                "hr" -> add(BookBlock(kind = BookBlockKind.SEPARATOR))
                "img", "image" -> extractImage(node, zip, chapterPath, assetDirectory, assetBudget)?.let(::add)
                else -> {
                    val children = node.childNodes
                    for (index in 0 until children.length) visit(children.item(index))
                }
            }
        }
        val children = root.childNodes
        for (index in 0 until children.length) visit(children.item(index))
    }

    private fun descendantImages(element: Element): List<Element> {
        val images = element.getElementsByTagNameNS("*", "img")
        return (0 until images.length).mapNotNull { images.item(it) as? Element }
    }

    private fun normalizedBlock(element: Element, kind: BookBlockKind): BookBlock? {
        val extracted = extractInlineText(element)
        if (extracted.text.isBlank()) return null
        val prefix = if (kind == BookBlockKind.LIST_ITEM) "• " else ""
        return BookBlock(
            text = prefix + extracted.text,
            kind = kind,
            inlineSpans = extracted.spans.map { span ->
                span.copy(start = span.start + prefix.length, end = span.end + prefix.length)
            },
            anchorId = element.getAttribute("id").ifBlank { null },
        )
    }

    private data class InlineText(
        val text: String,
        val spans: List<BookInlineSpan>,
    )

    private fun extractInlineText(root: Element): InlineText {
        val text = StringBuilder()
        val spans = mutableListOf<BookInlineSpan>()

        fun appendText(raw: String) {
            raw.forEach { character ->
                if (character.isWhitespace() || character == '\u00A0') {
                    if (text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
                } else {
                    text.append(character)
                }
            }
        }

        fun visit(node: Node) {
            if (node.nodeType == Node.TEXT_NODE) {
                appendText(node.nodeValue.orEmpty())
                return
            }
            val element = node as? Element ?: return
            val tag = (element.localName ?: element.tagName).lowercase()
            if (tag == "br") {
                if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
                return
            }
            val start = text.length
            val children = element.childNodes
            for (index in 0 until children.length) visit(children.item(index))
            val end = text.length
            val style = when (tag) {
                "strong", "b" -> BookInlineStyle.BOLD
                "em", "i", "cite" -> BookInlineStyle.ITALIC
                "a" -> BookInlineStyle.UNDERLINE
                "code", "kbd", "samp" -> BookInlineStyle.CODE
                "sup" -> BookInlineStyle.SUPERSCRIPT
                else -> null
            }
            if (style != null && start < end) {
                spans.add(
                    BookInlineSpan(
                        start = start,
                        end = end,
                        style = style,
                        target = element.getAttribute("href").takeIf(String::isNotBlank),
                    )
                )
            }
        }

        val children = root.childNodes
        for (index in 0 until children.length) visit(children.item(index))
        val raw = text.toString()
        val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val trailing = raw.indexOfLast { !it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
        val normalized = if (leading < trailing) raw.substring(leading, trailing) else ""
        val adjusted = spans.mapNotNull { span ->
            val start = (span.start - leading).coerceIn(0, normalized.length)
            val end = (span.end - leading).coerceIn(0, normalized.length)
            span.copy(start = start, end = end).takeIf { start < end }
        }
        return InlineText(normalized, adjusted)
    }

    private fun extractImage(
        element: Element,
        zip: ZipFile,
        chapterPath: String,
        assetDirectory: File?,
        assetBudget: AssetBudget?,
    ): BookBlock? {
        val rawSource = sequenceOf(
            element.getAttribute("src"),
            element.getAttribute("href"),
            element.getAttributeNS("http://www.w3.org/1999/xlink", "href"),
        ).firstOrNull(String::isNotBlank) ?: return null
        val decoded = runCatching { URLDecoder.decode(rawSource, Charsets.UTF_8.name()) }.getOrDefault(rawSource)
        val path = resolvePath(chapterPath, decoded)
        val entry = zip.getEntry(path) ?: return null
        val outputPath = assetDirectory?.let { directory ->
            directory.mkdirs()
            val shouldExtract = assetBudget?.register(path) ?: true
            val extension = path.substringAfterLast('.', "img").filter(Char::isLetterOrDigit).take(5)
            val output = ownedDirectChild(directory, "${stableAssetId(path)}.$extension")
            if (shouldExtract && (!output.isFile || output.length() != entry.size)) {
                try {
                    zip.getInputStream(entry).use { input ->
                        output.outputStream().use { destination ->
                            copyWithLimit(
                                input,
                                destination,
                                MAX_IMAGE_BYTES,
                                "An EPUB image",
                                assetBudget?.bytes,
                            )
                        }
                    }
                } catch (exception: Exception) {
                    output.delete()
                    throw exception
                }
            }
            output.absolutePath
        }
        return BookBlock(
            kind = BookBlockKind.IMAGE,
            imagePath = outputPath,
            altText = element.getAttribute("alt").ifBlank { "Illustration" },
        )
    }

    private fun parseXml(input: InputStream, xmlBudget: ByteBudget): Document {
        val xml = readBytesWithLimit(input, MAX_XML_DOCUMENT_BYTES, "An EPUB XML document", xmlBudget)
        require(!containsInternalDoctypeSubset(xml)) {
            "This EPUB contains an unsafe XML document type declaration."
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            try {
                isXIncludeAware = false
            } catch (_: UnsupportedOperationException) {
                // Some Android XML implementations do not expose XInclude controls.
            }
            setFeatureIfSupported(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val builder = factory.newDocumentBuilder().apply {
            // Never allow an EPUB to resolve a network or local external entity.
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return builder.parse(ByteArrayInputStream(xml)).also(::validateXmlComplexity)
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: ParserConfigurationException) {
            // Feature support varies between the JDK and Android XML parsers.
        }
    }

    private fun validateArchive(zip: ZipFile) {
        val names = mutableSetOf<String>()
        var entryCount = 0
        var declaredBytes = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount++
            require(entryCount <= MAX_ZIP_ENTRIES) { "This EPUB contains too many files." }
            val name = entry.name
            require(
                name.isNotBlank() &&
                    !name.startsWith('/') &&
                    '\\' !in name &&
                    '\u0000' !in name &&
                    name.split('/').none { it == "." || it == ".." }
            ) { "This EPUB contains an unsafe file path." }
            require(names.add(name)) { "This EPUB contains a duplicate file entry." }
            if (entry.size >= 0L) {
                require(entry.size <= MAX_ZIP_ENTRY_BYTES) { "An EPUB file entry is too large." }
                require(declaredBytes <= MAX_DECLARED_EXPANDED_BYTES - entry.size) {
                    "The EPUB's extracted content is too large."
                }
                declaredBytes += entry.size
            }
        }
    }

    private fun validateXmlComplexity(document: Document) {
        val pending = ArrayDeque<Pair<Node, Int>>()
        pending.add(document to 0)
        var nodeCount = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            nodeCount++
            require(nodeCount <= MAX_XML_NODES) { "An EPUB XML document is too complex." }
            require(depth <= MAX_XML_DEPTH) { "An EPUB XML document is nested too deeply." }
            val children = node.childNodes
            for (index in 0 until children.length) {
                pending.add(children.item(index) to depth + 1)
            }
        }
    }

    private fun stableAssetId(path: String): String = MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun containsInternalDoctypeSubset(xml: ByteArray): Boolean =
        listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).any { charset ->
            val text = xml.toString(charset)
            val declarationStart = text.indexOf("<!DOCTYPE", ignoreCase = true)
            if (declarationStart < 0) {
                false
            } else {
                val declarationEnd = text.indexOf('>', startIndex = declarationStart)
                val subsetStart = text.indexOf('[', startIndex = declarationStart)
                subsetStart >= 0 && (declarationEnd < 0 || subsetStart < declarationEnd)
            }
        }

    private fun elements(document: Document, localName: String): List<Element> {
        val namespaced = document.getElementsByTagNameNS("*", localName)
        val nodes = if (namespaced.length > 0) namespaced else document.getElementsByTagName(localName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun firstText(document: Document, localName: String): String =
        elements(document, localName).firstOrNull()?.textContent?.let(::normalizeWhitespace).orEmpty()

    private fun normalizeWhitespace(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun resolvePath(opfPath: String, relativePath: String): String {
        val parent = Paths.get(opfPath).parent ?: Paths.get("")
        return parent.resolve(relativePath.substringBefore('#')).normalize().toString().replace('\\', '/')
    }

    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_SPINE_CHAPTERS = 2_000
    private const val MAX_IMAGE_COUNT = 5_000
    private const val MAX_XML_NODES = 200_000
    private const val MAX_XML_DEPTH = 128
    private const val MAX_XML_DOCUMENT_BYTES = 8L * 1024L * 1024L
    private const val MAX_TOTAL_XML_BYTES = 128L * 1024L * 1024L
    private const val MAX_COVER_BYTES = 32L * 1024L * 1024L
    private const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
    private const val MAX_ZIP_ENTRY_BYTES = 256L * 1024L * 1024L
    private const val MAX_DECLARED_EXPANDED_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_ASSET_BYTES = 512L * 1024L * 1024L
    private const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
}
