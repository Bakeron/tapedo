package io.github.bakeron.tapedo.core.speech

/** Joins what the recognizer heard between pauses into one transcript. */
object Phrases {

    /**
     * A pause becomes a comma: the recognizer gives no punctuation, and a shopping list said item by item
     * ("marchewki" ... "mleko" ... "jajka") must split into separate items, not one long line.
     */
    fun join(phrases: List<String>): String =
        phrases.map { it.trim() }.filter { it.isNotEmpty() }.fold("") { acc, phrase ->
            when {
                acc.isEmpty() -> phrase
                acc.last() in PUNCTUATION -> "$acc $phrase"
                else -> "$acc, $phrase"
            }
        }

    private const val PUNCTUATION = ".,;:!?…"
}
