package com.serein.reader

import com.serein.reader.data.ArchiveQuotaException
import com.serein.reader.data.DEMO_BOOK_ID
import com.serein.reader.data.LimitedInputStream
import com.serein.reader.data.copyWithLimit
import com.serein.reader.data.ownedDirectChild
import com.serein.reader.data.requireValidBookIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ArchiveSafetyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsOnlyCanonicalImportedIdsAndTheExactDemoIdentity() {
        val id = "01234567-89ab-cdef-8123-456789abcdef"
        assertEquals(id, requireValidBookIdentity(id, isDemo = false))
        assertEquals(DEMO_BOOK_ID, requireValidBookIdentity(DEMO_BOOK_ID, isDemo = true))

        listOf("..", ".", "../books", id.uppercase(), "1-1-1-1-1", DEMO_BOOK_ID).forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) {
                requireValidBookIdentity(unsafe, isDemo = false)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireValidBookIdentity("another-demo", isDemo = true)
        }
    }

    @Test
    fun ownedChildrenCannotEscapeTheirRoot() {
        val root = temporaryFolder.newFolder("owned")
        assertEquals(root.canonicalFile, ownedDirectChild(root, "book.epub").parentFile)
        listOf(".", "..", "../book.epub", "/tmp/book.epub", "folder/book.epub", "folder\\book.epub").forEach {
            assertThrows(IllegalArgumentException::class.java) { ownedDirectChild(root, it) }
        }
    }

    @Test
    fun countedCopiesStopBeforeWritingPastTheLimit() {
        val output = ByteArrayOutputStream()
        assertThrows(ArchiveQuotaException::class.java) {
            copyWithLimit(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), output, 4, "Test input")
        }
        assertEquals(0, output.size())

        val accepted = ByteArrayOutputStream()
        copyWithLimit(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), accepted, 4, "Test input")
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), accepted.toByteArray())
    }

    @Test
    fun compressedInputWrapperStopsAfterItsOwnBudget() {
        val stream = LimitedInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2, "Archive")
        assertEquals(1, stream.read())
        assertEquals(2, stream.read())
        assertThrows(ArchiveQuotaException::class.java) { stream.read() }
    }
}
