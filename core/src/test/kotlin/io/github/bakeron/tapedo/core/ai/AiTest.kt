package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.time.EpochDays
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiTest {

    private val saturday = EpochDays.fromCivil(2026, 10, 31)
    private val request = StructureRequest("kup mleko i jutro zadzwoń do mamy", saturday, 0L, Priority.MEDIUM)

    private val cardsJson =
        """{"cards":[{"title":"Zakupy","type":"checklist","priority":"medium","body":"","items":["Mleko"],"due":null},""" +
            """{"title":"Zadzwonić do mamy","type":"note","priority":"high","body":"","items":[],"due":"2026-11-01"}]}"""

    private fun json(text: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `gemini reply becomes cards and the key goes in a header, not the url`() = runTest {
        var url = ""
        var keyHeader: String? = null
        val engine = MockEngine { req ->
            url = req.url.toString()
            keyHeader = req.headers["x-goog-api-key"]
            respond("""{"candidates":[{"content":{"parts":[{"text":${json(cardsJson)}}]}}]}""", headers = jsonHeaders)
        }
        val notes = GeminiStructurer(aiHttpClient(engine), "secret-key").structure(request)

        assertEquals(2, notes.size)
        assertEquals(NoteType.CHECKLIST, notes[0].type)
        assertEquals(listOf("Mleko"), notes[0].items.map { it.text })
        assertEquals(Priority.HIGH, notes[1].priority)
        assertEquals(saturday + 1, notes[1].dueEpochDay)
        assertTrue(notes.all { it.byAi })
        assertEquals("secret-key", keyHeader)
        assertFalse("key must not be in the URL", "secret-key" in url)
    }

    @Test
    fun `gemini thought parts are skipped`() = runTest {
        val engine = MockEngine {
            respond(
                """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":${json(cardsJson)}}]}}]}""",
                headers = jsonHeaders,
            )
        }
        assertEquals(2, GeminiStructurer(aiHttpClient(engine), "k").structure(request).size)
    }

    @Test
    fun `groq reply becomes cards with a bearer token`() = runTest {
        var auth: String? = null
        val engine = MockEngine { req ->
            auth = req.headers[HttpHeaders.Authorization]
            respond("""{"choices":[{"message":{"role":"assistant","content":${json(cardsJson)}}}]}""", headers = jsonHeaders)
        }
        val notes = GroqStructurer(aiHttpClient(engine), "gsk_123").structure(request)
        assertEquals(2, notes.size)
        assertEquals("Bearer gsk_123", auth)
    }

    @Test
    fun `http errors map to plain error kinds`() = runTest {
        suspend fun errorFor(status: HttpStatusCode, body: String = "{}"): AiError {
            val engine = MockEngine { respond(body, status, jsonHeaders) }
            try {
                GeminiStructurer(aiHttpClient(engine), "k").structure(request)
            } catch (e: AiError) {
                return e
            }
            fail("expected an AiError"); throw IllegalStateException()
        }
        assertEquals(AiError.RateLimited, errorFor(HttpStatusCode.TooManyRequests))
        assertEquals(AiError.InvalidKey, errorFor(HttpStatusCode.Unauthorized))
        assertEquals(AiError.InvalidKey, errorFor(HttpStatusCode.BadRequest, """{"error":{"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""))
        assertTrue(errorFor(HttpStatusCode.InternalServerError) is AiError.Server)
        assertTrue(errorFor(HttpStatusCode.OK, """{"candidates":[]}""") is AiError.BadResponse)
    }

    @Test
    fun `network failure is reported as network error`() = runTest {
        val engine = MockEngine { throw IOException("offline") }
        try {
            GroqStructurer(aiHttpClient(engine), "k").structure(request)
            fail("expected an AiError")
        } catch (e: AiError) {
            assertTrue(e is AiError.Network)
        }
    }

    @Test
    fun `parser cleans up odd model output`() {
        val messy = "```json\n" +
            """{"cards":[{"title":"  Lista  ","type":"checklist","items":[" ","Chleb"],"due":"2026-02-30","priority":"urgent"},""" +
            """{"title":"","type":"checklist","items":[]}]}""" + "\n```"
        val notes = AiContract.parse(messy, request)
        assertEquals(1, notes.size) // the empty card is dropped
        assertEquals("Lista", notes[0].title)
        assertEquals(listOf("Chleb"), notes[0].items.map { it.text })
        assertNull("impossible date is dropped", notes[0].dueEpochDay)
        assertEquals("unknown priority falls back to default", Priority.MEDIUM, notes[0].priority)
    }

    @Test
    fun `checklist without items becomes a note`() {
        val notes = AiContract.parse("""{"cards":[{"title":"Zakupy","type":"checklist","items":[]}]}""", request)
        assertEquals(NoteType.NOTE, notes.single().type)
    }

    @Test
    fun `prompt tells the model today's date and weekday`() {
        val prompt = AiContract.systemPrompt(request)
        assertTrue(prompt.contains("Today is 2026-10-31 (Saturday)"))
    }

    @Test
    fun `shopping items keep their shop section, in string or object form`() {
        val reply = """{"cards":[{"title":"Zakupy","type":"shopping",""" +
            """"items":[{"text":"Mleko","group":"dairy"},{"text":"Gwoździe","group":"tools"},"Chleb"]}]}"""
        val list = AiContract.parse(reply, request).single()
        assertEquals(NoteType.SHOPPING, list.type)
        assertEquals(listOf("Mleko", "Gwoździe", "Chleb"), list.items.map { it.text })
        // Unknown or missing section -> "other", never a crash.
        assertEquals(listOf(ShoppingGroup.DAIRY, ShoppingGroup.OTHER, ShoppingGroup.OTHER), list.items.map { it.group })
    }

    @Test
    fun `event keeps time and place, idea loses its due date`() {
        val reply = """{"cards":[""" +
            """{"title":"Dentysta","type":"event","due":"2026-11-03","time":"10:30","place":"Gabinet na Długiej"},""" +
            """{"title":"Piknik","type":"idea","due":"2026-11-01","time":"12:00","place":"park"}]}"""
        val (event, idea) = AiContract.parse(reply, request)
        assertEquals(NoteType.EVENT, event.type)
        assertEquals(10 * 60 + 30, event.timeMinutes)
        assertEquals("Gabinet na Długiej", event.place)
        assertEquals(NoteType.IDEA, idea.type)
        assertNull(idea.dueEpochDay)
        assertNull("only events have a time", idea.timeMinutes)
        assertNull(idea.place)
    }

    @Test
    fun `chosen type is enforced on the answer and named in the prompt`() {
        val chosen = request.copy(chosenType = NoteType.TASK)
        assertTrue(AiContract.systemPrompt(chosen).contains("The user chose the type \"task\""))
        // The model ignored the choice and split into two notes - the app keeps one task.
        val notes = AiContract.parse(cardsJson, chosen)
        assertEquals(NoteType.TASK, notes.single().type)
    }

    @Test
    fun `null fields from the model get defaults instead of failing`() {
        val reply = """{"cards":[{"title":"Zadzwonić do mamy","type":"task","body":null,"items":null,"priority":null}]}"""
        val note = AiContract.parse(reply, request).single()
        assertEquals("Zadzwonić do mamy", note.title)
        assertEquals("", note.body)
        assertEquals(Priority.MEDIUM, note.priority)
    }

    @Test
    fun `an item with an object instead of text is skipped, not a crash`() {
        val reply = """{"cards":[{"title":"Zakupy","type":"shopping","items":[{"text":{"x":1}},{"text":"Mleko","group":["dairy"]}]}]}"""
        val note = AiContract.parse(reply, request).single()
        assertEquals(listOf("Mleko"), note.items.map { it.text })
        assertEquals(ShoppingGroup.OTHER, note.items.single().group)
    }

    @Test
    fun `bad time from the model is dropped`() {
        val reply = """{"cards":[{"title":"Kino","type":"event","time":"25:99"}]}"""
        assertNull(AiContract.parse(reply, request).single().timeMinutes)
    }

    private suspend fun geminiError(status: HttpStatusCode, body: String): AiError {
        val engine = MockEngine { respond(body, status, jsonHeaders) }
        try {
            GeminiStructurer(aiHttpClient(engine), "k").structure(request)
        } catch (e: AiError) {
            return e
        }
        fail("expected an AiError"); throw IllegalStateException()
    }

    @Test
    fun `gemini answer blocked by the safety filter is a bad response, not a crash`() = runTest {
        // A blocked answer has a candidate without "content", or no candidates at all.
        val noContent = """{"candidates":[{"finishReason":"SAFETY","index":0,"safetyRatings":[]}]}"""
        assertTrue(geminiError(HttpStatusCode.OK, noContent) is AiError.BadResponse)
        val blockedPrompt = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        assertTrue(geminiError(HttpStatusCode.OK, blockedPrompt) is AiError.BadResponse)
    }

    @Test
    fun `http 403 means a wrong key`() = runTest {
        assertEquals(AiError.InvalidKey, geminiError(HttpStatusCode.Forbidden, """{"error":{"code":403}}"""))
    }

    @Test
    fun `a bare array or null cards is a bad response`() {
        for (reply in listOf("""[{"title":"Mleko","type":"note"}]""", """{"cards":null}""", """{}""", "")) {
            try {
                AiContract.parse(reply, request)
                fail("expected BadResponse for: $reply")
            } catch (e: AiError) {
                assertTrue(reply, e is AiError.BadResponse)
            }
        }
    }

    @Test
    fun `oversized answers are trimmed to the limits`() {
        val longText = "x".repeat(5_000)
        val items = (1..500).joinToString(",") { "\"Punkt $it ${"y".repeat(100)}\"" }
        val card = """{"title":"${"T".repeat(200)}","type":"checklist","body":"$longText","items":[$items]}"""
        val reply = """{"cards":[${List(15) { card }.joinToString(",")}]}"""
        val notes = AiContract.parse(reply, request)

        assertEquals(10, notes.size)
        val first = notes.first()
        assertEquals(80, first.title.length)
        assertEquals(4_000, first.body.length)
        assertEquals(50, first.items.size)
        assertTrue(first.items.all { it.text.length == 80 })
        assertEquals("Punkt 1 ", first.items.first().text.take(8))
    }

    // --- NoteStructurer: AI with fallback to rules ---

    private class FakeSecrets(val key: String?) : SecretStore {
        override suspend fun apiKey(provider: AiProvider) = key
        override suspend fun setApiKey(provider: AiProvider, key: String?) = Unit
    }

    private fun structurer(key: String?, ai: AiStructurer) = NoteStructurer(FakeSecrets(key)) { _, _ -> ai }

    private suspend fun NoteStructurer.run(provider: AiProvider) =
        structure("kup mleko", provider, null, Priority.MEDIUM, ListTitles(), saturday, 0L)

    @Test
    fun `no provider uses rules without error`() = runTest {
        val result = structurer("k", failingAi(AiError.RateLimited)).run(AiProvider.NONE)
        assertNull(result.aiError)
        assertFalse(result.notes.single().byAi)
    }

    @Test
    fun `missing key falls back to rules and says why`() = runTest {
        val result = structurer(null, failingAi(AiError.RateLimited)).run(AiProvider.GEMINI)
        assertEquals(AiError.MissingKey, result.aiError)
        assertEquals(listOf("Mleko"), result.notes.single().items.map { it.text })
    }

    @Test
    fun `a key that cannot be read right now is not reported as missing`() = runTest {
        val unreadable = object : SecretStore {
            override suspend fun apiKey(provider: AiProvider): String? = throw IOException("keystore busy")
            override suspend fun setApiKey(provider: AiProvider, key: String?) = Unit
        }
        val result = NoteStructurer(unreadable) { _, _ -> failingAi(AiError.RateLimited) }.run(AiProvider.GEMINI)
        assertEquals(AiError.KeyUnreadable, result.aiError)
        assertEquals(listOf("Mleko"), result.notes.single().items.map { it.text })
    }

    @Test
    fun `ai failure falls back to rules`() = runTest {
        val result = structurer("k", failingAi(AiError.RateLimited)).run(AiProvider.GROQ)
        assertEquals(AiError.RateLimited, result.aiError)
        assertFalse(result.notes.single().byAi)
    }

    @Test
    fun `unexpected crash in the ai path still gives the note from rules`() = runTest {
        val crashing = object : AiStructurer {
            override suspend fun structure(request: StructureRequest): List<Note> = throw IllegalStateException("boom")
        }
        val result = structurer("k", crashing).run(AiProvider.GEMINI)
        assertTrue(result.aiError is AiError.BadResponse)
        assertEquals(listOf("Mleko"), result.notes.single().items.map { it.text })
    }

    private fun failingAi(error: AiError) = object : AiStructurer {
        override suspend fun structure(request: StructureRequest) = throw error
    }
}
