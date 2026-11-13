package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.WhisperProvider
import io.github.bakeron.tapedo.core.speech.RecordedAudio
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperTest {

    private val loud = RecordedAudio(ByteArray(32_000) { if (it % 2 == 1) 0x30 else 0 }, 16_000)

    private fun secrets(key: String?) = object : SecretStore {
        override suspend fun apiKey(provider: AiProvider) = key
        override suspend fun setApiKey(provider: AiProvider, key: String?) = Unit
    }

    @Test
    fun `the recording goes as a form with the model, the language and word times - the key only in the header`() = runTest {
        var url = ""
        var auth = ""
        var form = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            auth = request.headers[HttpHeaders.Authorization].orEmpty()
            form = String(request.body.toByteArray(), Charsets.ISO_8859_1)
            respond(
                """{"text":" Mleko marchewki","segments":[{"text":" Mleko marchewki","start":0.0,"end":4.0,"no_speech_prob":0.01}],
                   "words":[{"word":"Mleko","start":0.1,"end":0.5},{"word":"marchewki","start":3.0,"end":3.6}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val text = HttpWhisper(aiHttpClient(engine)).transcribe(WhisperProvider.GROQ, "gsk_secret", loud, "pl-PL", 2_000L)

        assertEquals("Mleko, marchewki", text)
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", url)
        assertEquals("Bearer gsk_secret", auth)
        assertTrue(form.take(800), form.contains("whisper-large-v3-turbo"))
        assertTrue(form.take(800), Regex("name=\"?language\"?\r\n(?:[^\r]+\r\n)*\r\npl\r\n").containsMatchIn(form))
        assertTrue(form.contains("verbose_json"))
        assertTrue(form.contains("RIFF"))
        assertTrue("the key never goes in the body", !form.contains("gsk_secret"))
    }

    @Test
    fun `an error answer is named like the chat errors`() = runTest {
        val engine = MockEngine {
            respond("""{"error":{"message":"Rate limit reached for model whisper-large-v3-turbo","type":"tokens","code":"rate_limit_exceeded"}}""", HttpStatusCode.TooManyRequests)
        }
        val retyper = WhisperRetyper(secrets("gsk_x"), HttpWhisper(aiHttpClient(engine)))
        val outcome = retyper.retype(AppSettings(whisper = WhisperProvider.GROQ), loud) as WhisperRetyper.Outcome.Failed
        assertEquals(Problem.LIMIT, outcome.problem.problem)
        assertEquals(AiProvider.GROQ, outcome.problem.provider)
    }

    @Test
    fun `off, no audio, too long or no key - nothing is sent and the reason is given`() = runTest {
        var calls = 0
        val stt = SpeechToText { _, _, _, _, _ -> calls++; "x" }
        val on = AppSettings(whisper = WhisperProvider.OPENAI)

        assertEquals(null, WhisperRetyper(secrets("k"), stt).retype(AppSettings(), loud))
        fun reason(o: WhisperRetyper.Outcome?) = (o as WhisperRetyper.Outcome.Failed).problem.problem
        assertEquals(Problem.NO_AUDIO, reason(WhisperRetyper(secrets("k"), stt).retype(on, null)))
        assertEquals(Problem.TOO_LONG, reason(WhisperRetyper(secrets("k"), stt).retype(on, RecordedAudio(ByteArray(0), 16_000, complete = false))))
        assertEquals(Problem.NO_KEY, reason(WhisperRetyper(secrets(null), stt).retype(on, loud)))
        assertEquals(0, calls)

        assertEquals(WhisperRetyper.Outcome.Text("x"), WhisperRetyper(secrets("k"), stt).retype(on, loud))
    }
}
