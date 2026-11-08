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
        val stopping: Boolean = false,
        val failures: Int = 1,
    ) {
        fun decide() = RecognizerErrorPolicy.decide(error, offline, phrases, allowOnline, stopping, failures)
    }

    private fun fail(error: SpeechError) = RecognizerStep.Fail(error)

    private val tooMany = RecognizerErrorPolicy.MAX_FAILURES_IN_ROW + 1

    @Test
    fun `missing offline pack without consent never goes online`() {
        // Every combination of the other inputs: without allowOnline the answer is never GoOnline.
        for (offline in listOf(true, false)) for (phrases in listOf(true, false))
            for (stopping in listOf(true, false)) for (failures in 1..tooMany) {
                for (error in RecognizerErrorKind.entries) {
                    val step = Case(error, offline, phrases, false, stopping, failures).decide()
                    assertNotEquals("$error offline=$offline phrases=$phrases", GoOnline, step)
                }
            }
        assertEquals(fail(SpeechError.OFFLINE_UNAVAILABLE), Case(LANGUAGE_MISSING).decide())
        assertEquals(fail(SpeechError.OFFLINE_UNAVAILABLE), Case(LANGUAGE_MISSING, stopping = true).decide())
    }

    @Test
    fun `words already heard are never dropped`() {
        // Whatever goes wrong after the first phrase, the recording either goes on or ends with the text.
        for (offline in listOf(true, false)) for (allowOnline in listOf(true, false))
            for (stopping in listOf(true, false)) for (failures in 1..tooMany) {
                for (error in RecognizerErrorKind.entries) {
                    val step = Case(error, offline, true, allowOnline, stopping, failures).decide()
                    assert(step == Restart || step == FinishWithText) { "$error -> $step" }
                }
            }
    }

    @Test
    fun `decision table`() {
        val table = listOf(
            // No offline pack, the user allowed online recognition.
            Case(LANGUAGE_MISSING, allowOnline = true) to GoOnline,
            // Already online - a language error is not a reason to switch again.
            Case(LANGUAGE_MISSING, offline = false, allowOnline = true) to fail(SpeechError.NOT_AVAILABLE),
            // Phrases already heard offline - listen again offline, never send the rest to the internet.
            Case(LANGUAGE_MISSING, phrases = true, allowOnline = true) to Restart,
            // Pauses keep listening, however many and whatever was said - like a dictaphone.
            Case(SILENCE) to Restart,
            Case(SILENCE, phrases = true) to Restart,
            Case(SILENCE, phrases = true, failures = tooMany) to Restart,
            Case(BUSY) to Restart,
            // The bug from 07.11.2026: an error after the first phrase used to end the recording silently.
            Case(OTHER, phrases = true) to Restart,
            Case(NETWORK, offline = false, phrases = true) to Restart,
            // ...but a recognizer that keeps failing ends it, with the words kept.
            Case(OTHER, phrases = true, failures = tooMany) to FinishWithText,
            Case(BUSY, failures = tooMany) to fail(SpeechError.BUSY),
            // Stop in a pause ends the recording with what was said.
            Case(SILENCE, stopping = true, phrases = true) to FinishWithText,
            Case(SILENCE, stopping = true) to fail(SpeechError.NO_SPEECH),
            Case(BUSY, stopping = true, phrases = true) to FinishWithText,
            Case(OTHER, stopping = true) to fail(SpeechError.NO_SPEECH),
            // No microphone access - listening again would not help.
            Case(NO_PERMISSION) to fail(SpeechError.NO_PERMISSION),
            Case(NO_PERMISSION, phrases = true) to FinishWithText,
            // Nothing heard yet - the error is shown at once.
            Case(NETWORK, offline = false) to fail(SpeechError.NETWORK),
            Case(OTHER) to fail(SpeechError.OTHER),
        )
        for ((case, expected) in table) assertEquals(case.toString(), expected, case.decide())
    }

    @Test
    fun `a phone that refuses Tapedo's own audio is told apart from ordinary errors`() {
        fun refused(error: RecognizerErrorKind, offline: Boolean = true, heard: Boolean = false) =
            RecognizerErrorPolicy.ownAudioRefused(error, offline, heard)

        // A recognizer that cannot take audio from an app fails at once with a generic error.
        assertEquals(true, refused(OTHER))
        assertEquals("offline there is no network to blame", true, refused(NETWORK))
        // Ordinary situations keep their own handling.
        assertEquals(false, refused(NETWORK, offline = false))
        assertEquals(false, refused(SILENCE))
        assertEquals(false, refused(BUSY))
        assertEquals(false, refused(LANGUAGE_MISSING))
        assertEquals(false, refused(NO_PERMISSION))
        // Once Tapedo's audio was understood, the phone does take it - a later error is a glitch.
        assertEquals(false, refused(OTHER, heard = true))
    }
}
