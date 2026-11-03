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

    /** Keep listening ("stop button" mode, a pause or a busy recognizer). */
    data object Restart : RecognizerStep

    /** End normally with the phrases heard so far. */
    data object FinishWithText : RecognizerStep

    /** End with this error - nothing usable was heard. */
    data class Fail(val error: SpeechError) : RecognizerStep
}

/**
 * The privacy-relevant decision after a recognizer error, kept free of Android so it can be tested
 * as a table: the voice goes online only with [allowOnline], and phrases already heard are never lost.
 */
object RecognizerErrorPolicy {

    /**
     * @param offline the current session still uses the offline recognizer.
     * @param phrasesHeard at least one phrase was already recognised in this session.
     * @param untilStopped "stop button" mode - keep listening across pauses.
     * @param stopping the user pressed stop (or the time limit did).
     */
    fun decide(
        error: RecognizerErrorKind,
        offline: Boolean,
        phrasesHeard: Boolean,
        allowOnline: Boolean,
        untilStopped: Boolean,
        stopping: Boolean,
    ): RecognizerStep {
        val keepListening = untilStopped && !stopping
        return when {
            // No offline pack for this language: go online only when the user allowed it.
            offline && error == RecognizerErrorKind.LANGUAGE_MISSING && !phrasesHeard ->
                if (allowOnline) RecognizerStep.GoOnline else RecognizerStep.Fail(SpeechError.OFFLINE_UNAVAILABLE)
            // A pause with nothing said: keep listening in "stop button" mode.
            error == RecognizerErrorKind.SILENCE && keepListening -> RecognizerStep.Restart
            error == RecognizerErrorKind.BUSY && keepListening -> RecognizerStep.Restart
            // Something went wrong later in a long recording - keep what was already heard.
            phrasesHeard -> RecognizerStep.FinishWithText
            error == RecognizerErrorKind.SILENCE || stopping -> RecognizerStep.Fail(SpeechError.NO_SPEECH)
            else -> RecognizerStep.Fail(speechErrorOf(error))
        }
    }

    private fun speechErrorOf(error: RecognizerErrorKind): SpeechError = when (error) {
        RecognizerErrorKind.NO_PERMISSION -> SpeechError.NO_PERMISSION
        RecognizerErrorKind.NETWORK -> SpeechError.NETWORK
        RecognizerErrorKind.BUSY -> SpeechError.BUSY
        RecognizerErrorKind.LANGUAGE_MISSING -> SpeechError.NOT_AVAILABLE
        RecognizerErrorKind.SILENCE, RecognizerErrorKind.OTHER -> SpeechError.OTHER
    }
}
