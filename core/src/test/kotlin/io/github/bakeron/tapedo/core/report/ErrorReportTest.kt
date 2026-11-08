package io.github.bakeron.tapedo.core.report

import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorReportTest {

    private val device = DeviceInfo(
        appVersion = "1.1",
        manufacturer = "Samsung",
        model = "SM-S911B",
        androidVersion = "14",
        sdk = 34,
        language = "pl-PL",
    )

    @Test
    fun `the subject says what broke and on which phone`() {
        val subject = ErrorReport.subject(Problem.OwnAudioRefused(code = 5), device)
        assertTrue(subject, subject.startsWith("Tapedo 1.1 - błąd: "))
        assertTrue(subject, subject.contains("Samsung SM-S911B, Android 14"))
    }

    @Test
    fun `the body has the facts and a ready prompt for Claude with the same facts`() {
        val body = ErrorReport.body(Problem.OwnAudioRefused(code = 5), device, time = "2026-11-08 14:05")
        val prompt = body.substringAfter("----- Prompt dla Claude Code -----")
        for (fact in listOf("Kod błędu rozpoznawania mowy: 5", "Samsung SM-S911B", "API 34", "pl-PL", "2026-11-08 14:05")) {
            assertTrue(fact, body.substringBefore("-----").contains(fact))
            assertTrue("prompt: $fact", prompt.contains(fact))
        }
        assertTrue(prompt.contains("EXTRA_AUDIO_SOURCE"))
    }

    @Test
    fun `a failed recording names the error, and a missing code is left out`() {
        val body = ErrorReport.body(Problem.RecordingFailed(error = "OTHER", code = null), device, time = "t")
        assertTrue(body.contains("SpeechError.OTHER"))
        assertTrue(!body.contains("Kod błędu rozpoznawania mowy"))
    }

    @Test
    fun `no local paths of the author's computer get into the mail`() {
        val body = ErrorReport.body(Problem.OwnAudioRefused(code = 5), device, time = "t")
        assertTrue(!body.contains(":\\"))
    }
}
