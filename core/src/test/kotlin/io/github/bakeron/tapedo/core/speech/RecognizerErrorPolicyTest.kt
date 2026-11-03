package io.github.bakeron.tapedo.core.speech

import io.github.bakeron.tapedo.core.speech.RecognizerErrorKind.BUSY
import io.github.bakeron.tapedo.core.speech.RecognizerErrorKind.LANGUAGE_MISSING
import io.github.bakeron.tapedo.core.speech.RecognizerErrorKind.NETWORK
import io.github.bakeron.tapedo.core.speech.RecognizerErrorKind.NO_PERMISSION
import io.github.bakeron.tapedo.core.speech.RecognizerErrorKind.OTHER
import io.github.bakeron.tapedo.core.speech.RecognizerErrorKind.SILENCE
import io.github.bakeron.tapedo.core.speech.RecognizerStep.FinishWithText
import io.github.bakeron.tapedo.core.speech.RecognizerStep.GoOnline
import io.github.bakeron.tapedo.core.speech.RecognizerStep.Restart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecognizerErrorPolicyTest {

    private data class Case(
        val error: RecognizerErrorKind,
        val offline: Boolean = true,
        val phrases: Boolean = false,
        val allowOnline: Boolean = false,
        val untilStopped: Boolean = false,
        val stopping: Boolean = false,
    ) {
        fun decide() = RecognizerErrorPolicy.decide(error, offline, phrases, allowOnline, untilStopped, stopping)
    }

    private fun fail(error: SpeechError) = RecognizerStep.Fail(error)

    @Test
    fun `missing offline pack without consent never goes online`() {
        // Every combination of the other inputs: without allowOnline the answer is never GoOnline.
        for (offline in listOf(true, false)) for (phrases in listOf(true, false))
            for (untilStopped in listOf(true, false)) for (stopping in listOf(true, false)) {
                for (error in RecognizerErrorKind.entries) {
                    val step = Case(error, offline, phrases, false, untilStopped, stopping).decide()
                    assertNotEquals("$error offline=$offline phrases=$phrases", GoOnline, step)
                }
            }
        assertEquals(fail(SpeechError.OFFLINE_UNAVAILABLE), Case(LANGUAGE_MISSING).decide())
        assertEquals(fail(SpeechError.OFFLINE_UNAVAILABLE), Case(LANGUAGE_MISSING, untilStopped = true).decide())
        assertEquals(fail(SpeechError.OFFLINE_UNAVAILABLE), Case(LANGUAGE_MISSING, stopping = true).decide())
    }

    @Test
    fun `decision table`() {
        val table = listOf(
            // No offline pack, the user allowed online recognition.
            Case(LANGUAGE_MISSING, allowOnline = true) to GoOnline,
            Case(LANGUAGE_MISSING, allowOnline = true, untilStopped = true) to GoOnline,
            // Already online - a language error is not a reason to switch again.
            Case(LANGUAGE_MISSING, offline = false, allowOnline = true) to fail(SpeechError.NOT_AVAILABLE),
            // Phrases already heard offline - keep them, do not send the rest to the internet.
            Case(LANGUAGE_MISSING, phrases = true, allowOnline = true) to FinishWithText,
            // Pauses in "stop button" mode keep listening.
            Case(SILENCE, untilStopped = true) to Restart,
            Case(SILENCE, untilStopped = true, phrases = true) to Restart,
            Case(BUSY, untilStopped = true) to Restart,
            // "Stop" in a pause ends the recording with what was said.
            Case(SILENCE, untilStopped = true, stopping = true, phrases = true) to FinishWithText,
            Case(SILENCE, untilStopped = true, stopping = true) to fail(SpeechError.NO_SPEECH),
            Case(BUSY, untilStopped = true, stopping = true, phrases = true) to FinishWithText,
            // "Silence" mode: the first pause ends the recording.
            Case(SILENCE) to fail(SpeechError.NO_SPEECH),
            Case(SILENCE, phrases = true) to FinishWithText,
            Case(BUSY) to fail(SpeechError.BUSY),
            // A late error in a long recording keeps the phrases.
            Case(NETWORK, offline = false, phrases = true, untilStopped = true) to FinishWithText,
            Case(OTHER, phrases = true) to FinishWithText,
            // Nothing heard - the error is shown.
            Case(NETWORK, offline = false) to fail(SpeechError.NETWORK),
            Case(NO_PERMISSION) to fail(SpeechError.NO_PERMISSION),
            Case(NO_PERMISSION, untilStopped = true) to fail(SpeechError.NO_PERMISSION),
            Case(OTHER) to fail(SpeechError.OTHER),
            Case(OTHER, stopping = true) to fail(SpeechError.NO_SPEECH),
        )
        for ((case, expected) in table) assertEquals(case.toString(), expected, case.decide())
    }
}
