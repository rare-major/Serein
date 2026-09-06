package com.serein.reader

import com.serein.reader.data.DictionaryRepository
import com.serein.reader.data.bundledDictionaryEntry
import com.serein.reader.data.dictionaryCandidates
import com.serein.reader.data.normalizeDictionaryWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryRepositoryTest {
    @Test
    fun resolvesTheSmallFallbackDictionaryWithoutANetwork() {
        val entry = DictionaryRepository().lookup("serene")
        assertEquals("serene", entry.word)
        assertEquals("adjective", entry.meanings.single().partOfSpeech)
        assertEquals("Calm, peaceful, and untroubled.", entry.meanings.single().definitions.single())
        assertTrue(entry.isOffline)
    }

    @Test
    fun reportsAnOfflineMissWithoutRequestingConnectivity() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DictionaryRepository().lookup("notawordnetentry")
        }
        assertEquals("No offline English definition was found for “notawordnetentry”.", error.message)
    }

    @Test
    fun normalizesPossessivesAndTriesIrregularLemmasFirst() {
        assertEquals("mother", normalizeDictionaryWord("  Mother’s  "))
        val candidates = dictionaryCandidates("women")
        assertEquals("woman", candidates.first())
        assertFalse(candidates.contains("wom"))
    }

    @Test
    fun readsACompleteOfflineWordNetEntryFromSortedAssets() {
        val lines = listOf(
            "liberal\t{\"p\":\"adjective\",\"d\":\"showing tolerance\"}",
            "library\t{\"p\":\"noun\",\"d\":\"a room where books are kept\",\"e\":[\"They read in the library.\"],\"r\":\"ˈlaɪbɹi\"}",
            "library\t{\"p\":\"noun\",\"d\":\"a collection of books\",\"s\":[\"collection\"]}",
            "livid\t{\"p\":\"adjective\",\"d\":\"furiously angry\"}",
        )

        val entry = bundledDictionaryEntry("library", lines.iterator())

        requireNotNull(entry)
        assertEquals("Open English WordNet 2025", entry.source)
        assertTrue(entry.isOffline)
        assertEquals("ˈlaɪbɹi", entry.phonetic)
        assertEquals(listOf("a room where books are kept", "a collection of books"), entry.meanings.single().definitions)
        assertEquals(listOf("collection"), entry.meanings.single().synonyms)
    }
}
