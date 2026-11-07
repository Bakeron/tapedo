package io.github.bakeron.tapedo.core.speech

/**
 * "End on silence", counted by Tapedo instead of the recognizer: the recognizer ends a phrase after
 * about a second of quiet, far too soon for someone thinking of the next item. The recording ends only
 * after [silenceMillis] without new words (counted from the start while nothing was said yet).
 */
class SilenceCountdown(private val silenceMillis: Long) {
    private var lastText = ""
    private var lastWordsAt = 0L

    /** The text on screen at [nowMillis] (since the recording started); a change means new words. */
    fun heard(text: String, nowMillis: Long) {
        if (text != lastText) {
            lastText = text
            lastWordsAt = nowMillis
        }
    }

    fun isOver(nowMillis: Long): Boolean = nowMillis - lastWordsAt >= silenceMillis
}
