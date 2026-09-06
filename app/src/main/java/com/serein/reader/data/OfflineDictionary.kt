package com.serein.reader.data

/** A deliberately small, bundled lexicon for instant lookups without a connection. */
internal object OfflineDictionary {
    private fun entry(word: String, part: String, definition: String, example: String = "") =
        DictionaryEntry(
            word = word,
            phonetic = null,
            meanings = listOf(
                DictionaryMeaning(
                    partOfSpeech = part,
                    definitions = listOf(definition),
                    examples = listOfNotNull(example.takeIf(String::isNotBlank)),
                )
            ),
            isOffline = true,
            source = "Serein mini-dictionary",
        )

    private val entries = listOf(
        entry("alas", "interjection", "An expression of sadness, regret, or concern."),
        entry("amiable", "adjective", "Friendly, pleasant, and easy to like."),
        entry("ardent", "adjective", "Showing intense feeling or enthusiasm."),
        entry("benevolent", "adjective", "Kind and generous toward others."),
        entry("candor", "noun", "The quality of being open and honest."),
        entry("contemplate", "verb", "To think deeply or carefully about something."),
        entry("diligent", "adjective", "Careful and persistent in one’s work."),
        entry("eloquent", "adjective", "Fluent and persuasive in speaking or writing."),
        entry("ephemeral", "adjective", "Lasting for only a short time."),
        entry("fortitude", "noun", "Courage and strength during pain or difficulty."),
        entry("futile", "adjective", "Unable to produce a useful result."),
        entry("gracious", "adjective", "Courteous, kind, and pleasant."),
        entry("hasten", "verb", "To move or act quickly."),
        entry("impartial", "adjective", "Fair and not favoring one side."),
        entry("ineffable", "adjective", "Too great or unusual to be expressed in words."),
        entry("lively", "adjective", "Full of energy, activity, or interest."),
        entry("melancholy", "noun", "A thoughtful or persistent sadness."),
        entry("notion", "noun", "An idea, belief, or understanding of something."),
        entry("obscure", "adjective", "Not clearly understood or not well known."),
        entry("peculiar", "adjective", "Unusual or distinctive in a noticeable way."),
        entry("persevere", "verb", "To continue despite difficulty or delay."),
        entry("profound", "adjective", "Very great, intense, or intellectually deep."),
        entry("reluctant", "adjective", "Unwilling and hesitant to do something."),
        entry("serene", "adjective", "Calm, peaceful, and untroubled."),
        entry("solitude", "noun", "The state of being alone, often peacefully."),
        entry("subtle", "adjective", "Delicate or not immediately obvious."),
        entry("tedious", "adjective", "Too long, slow, or dull."),
        entry("tranquil", "adjective", "Free from disturbance; calm."),
        entry("vivid", "adjective", "Producing strong, clear images in the mind."),
        entry("wistful", "adjective", "Quietly longing for something unlikely or past."),
    ).associateBy { it.word }

    fun find(word: String): DictionaryEntry? = entries[word]
}
