package io.github.bakeron.tapedo.core.speech

/**
 * The transcript of one recording, built from what the recognizer sends: the words of the phrase being
 * spoken (partial results) and finished phrases.
 *
 * Where the recognizer ends its phrases has little to do with the speaker: "Lista zakupów" said in one
 * breath can come as two phrases, and four items said with pauses as one. So the separator comes from
 * the clock instead: a comma (a new list item) only where no new word appeared for [pauseMillis],
 * a space everywhere else. Words appear a moment after they are said, but that delay is about the same
 * for every word, so the gap between two words is close to the real pause.
 */
class Transcript(private val pauseMillis: Long = PAUSE_MILLIS) {

    /** Finished phrases, separators already in place. */
    private val done = StringBuilder()

    /** Words of the phrase being spoken. */
    private var current: List<String> = emptyList()

    /** Indexes in [current] that came after a long pause. */
    private val breaks = mutableSetOf<Int>()

    /** When the newest word appeared (any clock that only goes forward); null before the first word. */
    private var lastWordAt: Long? = null

    fun isEmpty(): Boolean = done.isEmpty() && current.isEmpty()

    /** The phrase being spoken as the recognizer sees it now - it may still rewrite earlier words. */
    fun partial(text: String, nowMillis: Long) {
        val words = words(text)
        if (words.isEmpty()) return
        // A different phrase after a long pause, with no "phrase ended" in between: some recognizers drop
        // the old phrase silently. It was on screen, so it is kept.
        if (current.isNotEmpty() && words.first() != current.first() && pausedBefore(nowMillis)) flush()
        // A corrected word also means the recognizer is still hearing speech - not a pause.
        take(words, nowMillis, anyChangeIsSpeech = true)
    }

    /** The phrase ended. [text] is its final form; null or blank keeps what was on screen. */
    fun phraseEnded(text: String?, nowMillis: Long) {
        val words = words(text.orEmpty())
        // The final form often arrives a second after the last word, with only letters tidied up -
        // that is not speech, so it does not move the clock.
        if (words.isNotEmpty()) take(words, nowMillis, anyChangeIsSpeech = false)
        flush()
    }

    /** Finished phrases plus the one being spoken - what the screen shows. */
    fun text(): String = StringBuilder(done).also { append(it, current, breaks) }.toString()

    private fun take(words: List<String>, nowMillis: Long, anyChangeIsSpeech: Boolean) {
        if (words == current) return
        val grew = words.size > current.size
        if (grew && pausedBefore(nowMillis)) breaks += current.size
        if (grew || anyChangeIsSpeech) lastWordAt = nowMillis
        current = words
        breaks.retainAll { it < words.size }
    }

    private fun pausedBefore(nowMillis: Long): Boolean = lastWordAt?.let { nowMillis - it >= pauseMillis } == true

    private fun flush() {
        append(done, current, breaks)
        current = emptyList()
        breaks.clear()
    }

    private fun append(to: StringBuilder, words: List<String>, breaks: Set<Int>) {
        words.forEachIndexed { i, word ->
            if (to.isNotEmpty()) {
                // The recognizer's own punctuation already separates - no second mark after it.
                if (i in breaks && to.last() !in PUNCTUATION) to.append(',')
                to.append(' ')
            }
            to.append(word)
        }
    }

    private fun words(text: String): List<String> = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    companion object {
        /** The author's choice (07.11.2026): a shorter pause is a breath, not the next item. */
        const val PAUSE_MILLIS = 2_000L

        private const val PUNCTUATION = ".,;:!?…"
        private val WHITESPACE = Regex("\\s+")
    }
}
