package com.serein.reader

import com.serein.reader.data.BookRecord
import com.serein.reader.data.LibraryFilter
import com.serein.reader.data.LibraryPreferences
import com.serein.reader.data.LibrarySort
import com.serein.reader.ui.filterAndSortBooks
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrganizationTest {
    private val books = listOf(
        BookRecord("1", "Zed", "Anne", progress = 0f, collection = "Essays", tags = listOf("classic")),
        BookRecord("2", "Alpha", "Zora", progress = 0.5f, collection = "Fiction", lastOpenedAt = 10L),
        BookRecord("3", "Middle", "Ben", progress = 1f, collection = "Essays", lastOpenedAt = 20L),
    )

    @Test
    fun filtersByProgressAndCollection() {
        val visible = filterAndSortBooks(
            books,
            query = "",
            preferences = LibraryPreferences(filter = LibraryFilter.FINISHED, collection = "Essays"),
        )

        assertEquals(listOf("Middle"), visible.map(BookRecord::title))
    }

    @Test
    fun searchesTagsAndSortsByTitle() {
        val visible = filterAndSortBooks(
            books,
            query = "classic",
            preferences = LibraryPreferences(sort = LibrarySort.TITLE),
        )

        assertEquals(listOf("Zed"), visible.map(BookRecord::title))
    }
}
