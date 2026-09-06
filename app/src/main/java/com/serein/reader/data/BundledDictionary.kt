package com.serein.reader.data

import android.content.Context
import org.json.JSONObject

/** Lazy, exact-word reader for the Open English WordNet assets bundled with Serein. */
internal class BundledDictionary(context: Context) {
    private val assets = context.applicationContext.assets

    fun find(word: String): DictionaryEntry? = runCatching {
        val group = word.firstOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' }?.toString() ?: "other"
        // Android's asset packager expands source .gz files and removes that suffix in the APK.
        assets.open("dictionary/$group.jsonl").bufferedReader().useLines { lines ->
            bundledDictionaryEntry(word, lines.iterator())
        }
    }.getOrNull()
}

internal fun bundledDictionaryEntry(word: String, lines: Iterator<String>): DictionaryEntry? {
    val meanings = linkedMapOf<String, MutableList<BundledSense>>()
    var phonetic: String? = null
    var found = false
    while (lines.hasNext()) {
        val line = lines.next()
        val separator = line.indexOf('\t')
        if (separator <= 0) continue
        val entryWord = line.substring(0, separator)
        val comparison = entryWord.compareTo(word)
        if (comparison < 0) continue
        if (comparison > 0) break
        found = true
        val value = JSONObject(line.substring(separator + 1))
        val part = value.optString("p", "meaning")
        val definitionsForPart = meanings.getOrPut(part) { mutableListOf() }
        if (definitionsForPart.size < MAX_DEFINITIONS_PER_PART) {
            definitionsForPart.add(
                BundledSense(
                    definition = value.optString("d"),
                    examples = value.optJSONArray("e").strings(),
                    synonyms = value.optJSONArray("s").strings(),
                )
            )
        }
        if (phonetic == null) phonetic = value.optString("r").takeIf(String::isNotBlank)
    }
    if (!found || meanings.isEmpty()) return null
    return DictionaryEntry(
        word = word,
        phonetic = phonetic,
        meanings = meanings.entries.take(MAX_PARTS_OF_SPEECH).map { (part, senses) ->
            DictionaryMeaning(
                partOfSpeech = part,
                definitions = senses.map(BundledSense::definition),
                examples = senses.flatMap(BundledSense::examples).distinct().take(2),
                synonyms = senses.flatMap(BundledSense::synonyms).distinct().take(8),
            )
        },
        isOffline = true,
        source = "Open English WordNet 2025",
    )
}

private fun org.json.JSONArray?.strings(): List<String> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).takeIf(String::isNotBlank)
    }
}

private data class BundledSense(
    val definition: String,
    val examples: List<String>,
    val synonyms: List<String>,
)

private const val MAX_PARTS_OF_SPEECH = 4
private const val MAX_DEFINITIONS_PER_PART = 3
