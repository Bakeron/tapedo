package io.github.bakeron.tapedo.core.speech

/** Recognizer errors grouped the way the decision needs them - the platform maps its own codes to these. */
enum class RecognizerErrorKind {
    /** The offline language pack for this language is missing or not supported. */
    LANGUAGE_MISSING,

    /** Nothing was understood, or nothing was said before the timeout. */
    SILENCE,
    BUSY,
    NO_PERMISSION,
    NETWORK,
    OTHER,
}

/** What the recognizer does next after an error. */
sealed interface RecognizerStep {
    /** Switch to the online recognizer and listen again - only ever after the user allowed it. */
    data object GoOnline : RecognizerStep

    /** Keep listening (a pause or a glitch in the middle of a recording). */
    data object Restart : RecognizerStep

    /** End normally with the phrases heard so far. */
    data object FinishWithText : RecognizerStep

    /** End with this error - nothing usable was heard. */
    data class Fail(val error: SpeechError) : RecognizerStep
}

/**
 * The privacy-relevant decision after a recognizer error, kept free of Android so it can be tested
 * as a table: the voice goes online only with [allowOnline], and phrases already heard are never lost.
 * A recording works like a dictaphone - an error in the middle means "listen again", not "the end".
 */
object RecognizerErrorPolicy {

    /** Glitches in a row (without new words in between) before the recording gives up. */
    const val MAX_FAILURES_IN_ROW = 3

    /**
     * @param offline the current session still uses the offline recognizer.
     * @param phrasesHeard at least one phrase was already recognised in this session.
     * @param stopping the user pressed stop (or the time limit or the silence countdown did).
     * @param failuresInRow errors other than silence since the last new words, this one included.
     */
    fun decide(
        error: RecognizerErrorKind,
        offline: Boolean,
        phrasesHeard: Boolean,
        allowOnline: Boolean,
        stopping: Boolean,
        failuresInRow: Int,
    ): RecognizerStep = when {
        // No offline pack for this language: go online only when the user allowed it.
        offline && error == RecognizerErrorKind.LANGUAGE_MISSING && !phrasesHeard ->
            if (allowOnline) RecognizerStep.GoOnline else RecognizerStep.Fail(SpeechError.OFFLINE_UNAVAILABLE)
        // Stopped during a pause or a restart: end with what was said.
        stopping -> if (phrasesHeard) RecognizerStep.FinishWithText else RecognizerStep.Fail(SpeechError.NO_SPEECH)
        // Listening again would not help.
        error == RecognizerErrorKind.NO_PERMISSION ->
            if (phrasesHeard) RecognizerStep.FinishWithText else RecognizerStep.Fail(SpeechError.NO_PERMISSION)
        // A pause to think - the recognizer gave up on it, the recording goes on.
        error == RecognizerErrorKind.SILENCE -> RecognizerStep.Restart
        // A glitch in a long recording ("busy", "client" after a quick restart, a network blip) - listen again,
        // but not forever: a recognizer that keeps failing ends the recording with what was heard.
        (phrasesHeard || error == RecognizerErrorKind.BUSY) && failuresInRow <= MAX_FAILURES_IN_ROW ->
            RecognizerStep.Restart
        phrasesHeard -> RecognizerStep.FinishWithText
        else -> RecognizerStep.Fail(speechErrorOf(error))
    }

    private fun speechErrorOf(error: RecognizerErrorKind): SpeechError = when (error) {
        RecognizerErrorKind.NO_PERMISSION -> SpeechError.NO_PERMISSION
        RecognizerErrorKind.NETWORK -> SpeechError.NETWORK
        RecognizerErrorKind.BUSY -> SpeechError.BUSY
        RecognizerErrorKind.LANGUAGE_MISSING -> SpeechError.NOT_AVAILABLE
        RecognizerErrorKind.SILENCE, RecognizerErrorKind.OTHER -> SpeechError.OTHER
    }
}
