package com.serein.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DictionaryRepository(context: Context? = null) {
    private val cache = mutableMapOf<String, DictionaryEntry>()
    private val preferences = context?.getSharedPreferences("serein_dictionary", Context.MODE_PRIVATE)
    private val bundledDictionary = context?.let(::BundledDictionary)

    init {
        val stored = preferences?.getString(KEY_CACHE, null)
        if (stored != null) runCatching {
            val array = JSONArray(stored)
            for (index in 0 until minOf(array.length(), MAX_CACHE_ENTRIES)) {
                val entry = entryFromJson(array.getJSONObject(index))
                if (entry.word.isNotBlank() && entry.meanings.isNotEmpty()) {
                    cache[entry.word.lowercase()] = entry
                }
            }
        }
    }

    fun lookup(rawWord: String): DictionaryEntry {
        val word = normalizeDictionaryWord(rawWord)
        require(word.isNotBlank()) { "Select a word to define." }
        cache[word]?.let { return it.also(::recordLookup) }
        val candidates = dictionaryCandidates(word)
        candidates.forEach { candidate ->
            cache[candidate]?.let { return it.also(::recordLookup) }
            OfflineDictionary.find(candidate)?.let { return it.also(::recordLookup) }
            bundledDictionary?.find(candidate)?.let { entry ->
                cache[word] = entry
                persistCache()
                recordLookup(entry)
                return entry
            }
        }
        throw IllegalArgumentException("No offline English definition was found for “$word”.")
    }

    fun loadVocabulary(): List<VocabularyRecord> {
        val stored = preferences?.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until minOf(array.length(), MAX_VOCABULARY_ENTRIES)).map { index ->
                val json = array.getJSONObject(index)
                VocabularyRecord(
                    word = json.optString("word").take(MAX_WORD_LENGTH),
                    phonetic = json.optString("phonetic").take(MAX_PHONETIC_LENGTH)
                        .takeIf(String::isNotBlank),
                    definition = json.optString("definition").take(MAX_DEFINITION_LENGTH),
                    lookedUpAt = json.optLong("lookedUpAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun recordLookup(entry: DictionaryEntry) {
        val preferenceStore = preferences ?: return
        val record = VocabularyRecord(
            word = entry.word,
            phonetic = entry.phonetic,
            definition = entry.meanings.firstOrNull()?.definitions?.firstOrNull().orEmpty(),
            lookedUpAt = System.currentTimeMillis(),
        )
        val updated = (listOf(record) + loadVocabulary().filterNot {
            it.word.equals(record.word, ignoreCase = true)
        }).take(MAX_VOCABULARY_ENTRIES)
        preferenceStore.edit().putString(KEY_HISTORY, JSONArray().apply {
            updated.forEach {
                put(JSONObject().put("word", it.word).put("phonetic", it.phonetic)
                    .put("definition", it.definition).put("lookedUpAt", it.lookedUpAt))
            }
        }.toString()).apply()
    }

    private fun persistCache() {
        preferences?.edit()?.putString(
            KEY_CACHE,
            JSONArray().apply {
                cache.values.toList().takeLast(MAX_CACHE_ENTRIES).forEach { put(entryToJson(it)) }
            }.toString(),
        )?.apply()
    }

    private fun entryToJson(entry: DictionaryEntry) = JSONObject()
        .put("word", entry.word)
        .put("phonetic", entry.phonetic)
        .put("source", entry.source)
        .put("meanings", JSONArray().apply {
            entry.meanings.forEach { meaning ->
                put(JSONObject()
                    .put("partOfSpeech", meaning.partOfSpeech)
                    .put("definitions", JSONArray(meaning.definitions))
                    .put("examples", JSONArray(meaning.examples))
                    .put("synonyms", JSONArray(meaning.synonyms)))
            }
        })

    private fun entryFromJson(json: JSONObject): DictionaryEntry {
        val meanings = json.optJSONArray("meanings") ?: JSONArray()
        return DictionaryEntry(
            word = json.optString("word").take(MAX_WORD_LENGTH),
            phonetic = json.optString("phonetic").take(MAX_PHONETIC_LENGTH)
                .takeIf(String::isNotBlank),
            source = json.optString("source", "Saved dictionary entry").take(MAX_SOURCE_LENGTH),
            meanings = (0 until minOf(meanings.length(), MAX_MEANINGS)).map { index ->
                val value = meanings.getJSONObject(index)
                DictionaryMeaning(
                    partOfSpeech = value.optString("partOfSpeech").take(MAX_PART_OF_SPEECH_LENGTH),
                    definitions = value.optJSONArray("definitions")
                        .strings(MAX_DEFINITIONS, MAX_DEFINITION_LENGTH),
                    examples = value.optJSONArray("examples")
                        .strings(MAX_EXAMPLES, MAX_EXAMPLE_LENGTH),
                    synonyms = value.optJSONArray("synonyms")
                        .strings(MAX_SYNONYMS, MAX_WORD_LENGTH),
                )
            },
        )
    }

    private fun JSONArray?.strings(maxItems: Int, maxLength: Int): List<String> {
        val array = this ?: return emptyList()
        return (0 until minOf(array.length(), maxItems)).mapNotNull {
            array.optString(it).take(maxLength).takeIf(String::isNotBlank)
        }
    }

    private companion object {
        const val KEY_CACHE = "dictionary_cache"
        const val KEY_HISTORY = "vocabulary_history"
        const val MAX_CACHE_ENTRIES = 250
        const val MAX_VOCABULARY_ENTRIES = 100
        const val MAX_MEANINGS = 8
        const val MAX_DEFINITIONS = 8
        const val MAX_EXAMPLES = 8
        const val MAX_SYNONYMS = 24
        const val MAX_WORD_LENGTH = 128
        const val MAX_PHONETIC_LENGTH = 256
        const val MAX_SOURCE_LENGTH = 256
        const val MAX_PART_OF_SPEECH_LENGTH = 64
        const val MAX_DEFINITION_LENGTH = 4_096
        const val MAX_EXAMPLE_LENGTH = 4_096
    }
}

internal fun normalizeDictionaryWord(rawWord: String): String = rawWord
    .trim()
    .takeWhile { !it.isWhitespace() }
    .trim { !it.isLetter() && it != '\u2019' && it != '\'' }
    .lowercase()
    .removeSuffix("\u2019s")
    .removeSuffix("'s")

internal fun dictionaryCandidates(word: String): List<String> {
    val irregular = mapOf(
        "women" to "woman", "men" to "man", "children" to "child",
        "people" to "person", "mice" to "mouse", "geese" to "goose",
        "feet" to "foot", "teeth" to "tooth", "went" to "go", "gone" to "go",
    )[word]
    return buildList {
        irregular?.let(::add)
        add(word)
        when {
            word.length > 4 && word.endsWith("ies") -> add(word.dropLast(3) + "y")
            word.length > 4 && word.endsWith("es") -> add(word.dropLast(2))
            word.length > 3 && word.endsWith("s") -> add(word.dropLast(1))
        }
    }.distinct()
}
