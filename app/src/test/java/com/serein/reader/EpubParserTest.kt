package com.serein.reader

import com.serein.reader.data.EpubParser
import com.serein.reader.data.BookBlockKind
import com.serein.reader.data.BookInlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsMetadataCoverAndSpineContent() {
        val epub = temporaryFolder.newFile("sample.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.add(
                "META-INF/container.xml",
                """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            )
            zip.add(
                "OEBPS/content.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>The Garden Room</dc:title>
                    <dc:creator>Sample Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>
                    <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
                """.trimIndent(),
            )
            zip.add("OEBPS/images/cover.png", "cover-bytes")
            zip.add(
                "OEBPS/text/chapter.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter One</title></head>
                  <body>
                    <h1>Chapter One</h1>
                    <p>A quiet room waited at the end of the hall.</p>
                    <p>Light moved gently across the books.</p>
                  </body>
                </html>
                """.trimIndent(),
            )
        }

        val metadata = EpubParser.parseMetadata(epub, temporaryFolder.newFolder("covers"))
        val content = EpubParser.readContent(epub)

        assertEquals("The Garden Room", metadata.title)
        assertEquals("Sample Author", metadata.author)
        assertTrue(metadata.coverPath?.endsWith(".png") == true)
        assertEquals("Chapter One", content.chapters.single().title)
        assertEquals(2, content.chapters.single().paragraphs.size)
    }

    @Test
    fun readsAnEpubWithAnExternalXhtmlDoctypeWithoutResolvingIt() {
        val epub = temporaryFolder.newFile("epub2-doctype.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.add("META-INF/container.xml", containerXml)
            zip.add("OEBPS/content.opf", packageXml)
            zip.add(
                "OEBPS/text/chapter.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html SYSTEM "https://example.invalid/xhtml.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter One</title></head>
                  <body><p>The chapter imports without fetching its external DTD.</p></body>
                </html>
                """.trimIndent(),
            )
        }

        val content = EpubParser.readContent(epub)

        assertEquals("The chapter imports without fetching its external DTD.", content.chapters.single().paragraphs.single())
    }

    @Test
    fun rejectsAnInternalDoctypeSubset() {
        val epub = temporaryFolder.newFile("unsafe-doctype.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.add("META-INF/container.xml", containerXml)
            zip.add(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE package [<!ENTITY author "Unsafe Author">]>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Unsafe book</dc:title>
                    <dc:creator>&author;</dc:creator>
                  </metadata>
                  <manifest/>
                  <spine/>
                </package>
                """.trimIndent(),
            )
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            EpubParser.parseMetadata(epub, temporaryFolder.newFolder("unsafe-covers"))
        }

        assertEquals("This EPUB contains an unsafe XML document type declaration.", error.message)
    }

    @Test
    fun preservesSemanticBlocksAndExtractsInlineImages() {
        val epub = temporaryFolder.newFile("semantic.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.add("META-INF/container.xml", containerXml)
            zip.add("OEBPS/content.opf", packageXml)
            zip.add("OEBPS/images/plate.png", "image-bytes")
            zip.add(
                "OEBPS/text/chapter.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter One</title></head>
                  <body>
                    <h1>Chapter One</h1>
                    <h2>A smaller heading</h2>
                    <p>Opening <em>paragraph</em> with <strong>weight</strong>.</p>
                    <blockquote>A remembered line.</blockquote>
                    <ul><li>First item</li></ul>
                    <img src="../images/plate.png" alt="Botanical plate"/>
                  </body>
                </html>
                """.trimIndent(),
            )
        }

        val assets = temporaryFolder.newFolder("assets")
        val chapter = EpubParser.readContent(epub, assets).chapters.single()

        assertEquals(
            listOf(
                BookBlockKind.HEADING,
                BookBlockKind.PARAGRAPH,
                BookBlockKind.QUOTE,
                BookBlockKind.LIST_ITEM,
                BookBlockKind.IMAGE,
            ),
            chapter.blocks.map { it.kind },
        )
        assertEquals("• First item", chapter.paragraphs[3])
        assertEquals(
            setOf(BookInlineStyle.ITALIC, BookInlineStyle.BOLD),
            chapter.blocks[1].inlineSpans.map { it.style }.toSet(),
        )
        assertTrue(chapter.blocks.last().imagePath?.let { java.io.File(it).isFile } == true)
    }

    @Test
    fun resolvesIndexPageLinksToTheChapterAndHeadingTheyPointAt() {
        val epub = temporaryFolder.newFile("index-links.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.add("META-INF/container.xml", containerXml)
            zip.add(
                "OEBPS/content.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Linked book</dc:title>
                    <dc:creator>Sample Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="index" href="text/index.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter3" href="text/chapter3.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="index"/>
                    <itemref idref="chapter2"/>
                    <itemref idref="chapter3"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            zip.add(
                "OEBPS/text/index.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Index</title></head>
                  <body>
                    <h1>Index</h1>
                    <p><a href="chapter2.xhtml#sec2">Jump to chapter two</a></p>
                    <p><a href="chapter3.xhtml">Jump to chapter three</a></p>
                    <p><a href="https://example.com/">An external link</a></p>
                  </body>
                </html>
                """.trimIndent(),
            )
            zip.add(
                "OEBPS/text/chapter2.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter Two</title></head>
                  <body>
                    <h1>Chapter Two</h1>
                    <p>Filler so the heading is not the only paragraph.</p>
                    <h2 id="sec2">Section two</h2>
                    <p>The section the index points at.</p>
                  </body>
                </html>
                """.trimIndent(),
            )
            zip.add(
                "OEBPS/text/chapter3.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter Three</title></head>
                  <body><h1>Chapter Three</h1><p>Its own opening line.</p></body>
                </html>
                """.trimIndent(),
            )
        }

        val chapters = EpubParser.readContent(epub).chapters
        val indexLinks = chapters[0].blocks.flatMap { it.inlineSpans }
            .filter { it.style == BookInlineStyle.UNDERLINE }

        val toSectionTwo = indexLinks.first { it.target == "chapter2.xhtml#sec2" }
        assertEquals(1, toSectionTwo.targetChapterIndex)
        val sectionTwoOffset = chapters[1].readingLengthUpTo("Section two")
        assertEquals(sectionTwoOffset, toSectionTwo.targetCharacterOffset)

        val toChapterThree = indexLinks.first { it.target == "chapter3.xhtml" }
        assertEquals(2, toChapterThree.targetChapterIndex)
        assertEquals(0, toChapterThree.targetCharacterOffset)

        val toExternal = indexLinks.first { it.target == "https://example.com/" }
        assertEquals(null, toExternal.targetChapterIndex)
    }

    private fun com.serein.reader.data.BookChapter.readingLengthUpTo(paragraphStartingWith: String): Int {
        val index = paragraphs.indexOfFirst { it.startsWith(paragraphStartingWith) }
        return paragraphs.take(index).sumOf { it.length + 2 }
    }

    @Test
    fun createsANewAssetDirectoryBeforeApplyingItsStorageBudget() {
        val epub = temporaryFolder.newFile("new-assets.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.add("META-INF/container.xml", containerXml)
            zip.add("OEBPS/content.opf", packageXml)
            zip.add("OEBPS/images/plate.png", "image-bytes")
            zip.add(
                "OEBPS/text/chapter.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter One</title></head>
                  <body><img src="../images/plate.png" alt="Botanical plate"/></body>
                </html>
                """.trimIndent(),
            )
        }

        val assets = java.io.File(temporaryFolder.root, "not-created-yet/assets")
        val image = EpubParser.readContent(epub, assets).chapters.single().blocks.single()

        assertTrue(assets.isDirectory)
        assertTrue(image.imagePath?.let { java.io.File(it).isFile } == true)
    }

    private val containerXml =
        """
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent()

    private val packageXml =
        """
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>EPUB 2 book</dc:title>
            <dc:creator>Sample Author</dc:creator>
          </metadata>
          <manifest>
            <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine><itemref idref="chapter"/></spine>
        </package>
        """.trimIndent()

    private fun ZipOutputStream.add(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }
}
