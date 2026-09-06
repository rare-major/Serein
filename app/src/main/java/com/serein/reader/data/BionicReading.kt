package com.serein.reader.data

object BionicReading {
    private val wordPattern = Regex("[\\p{L}\\p{N}]+(?:[’'][\\p{L}\\p{N}]+)*")

    fun emphasisRanges(text: String): List<IntRange> = buildList {
        forEachEmphasisRange(text, ::add)
    }

    fun forEachEmphasisRange(text: String, action: (IntRange) -> Unit) {
        wordPattern.findAll(text).forEach { match ->
            val visibleLength = match.value.letterOrDigitCount()
            val emphasizedCharacters = (visibleLength + 1) / 2
            var countedCharacters = 0
            var index = 0

            while (index < match.value.length) {
                val codePoint = match.value.codePointAt(index)
                index += Character.charCount(codePoint)
                if (Character.isLetterOrDigit(codePoint)) countedCharacters += 1
                if (countedCharacters >= emphasizedCharacters) break
            }

            action(match.range.first until (match.range.first + index))
        }
    }

    private fun String.letterOrDigitCount(): Int {
        var count = 0
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) count += 1
            index += Character.charCount(codePoint)
        }
        return count
    }
}
