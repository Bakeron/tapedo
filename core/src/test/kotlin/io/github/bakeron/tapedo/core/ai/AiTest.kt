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
    fun `a saved card's type is named in the prompt, but a chosen type wins`() {
        val event = request.copy(cardType = NoteType.EVENT)
        assertTrue(AiContract.systemPrompt(event).contains("already saved as \"event\""))
        assertFalse(AiContract.systemPrompt(request).contains("already saved as"))
        assertFalse(AiContract.systemPrompt(event.copy(chosenType = NoteType.TASK)).contains("already saved as"))
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

    @Test
    fun `a bare array or null cards is a bad response`() {
        for (reply in listOf("""[{"title":"Mleko","type":"note"}]""", """{"cards":null}""", """{}""", "")) {
            try {
                AiContract.parse(reply, request)
                fail("expected a broken answer for: $reply")
            } catch (e: AiException) {
                assertEquals(reply, Problem.ANSWER, e.problem)
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

    @Test
    fun `json is found in a chatty answer`() {
        val notes = AiContract.parse("Sure! Here you go:\n```json\n$cardsJson\n```\nHope it helps {:", request)
        assertEquals(2, notes.size)
        for (text in listOf("no json here", "} {")) {
            try {
                AiContract.parse(text, request)
                fail("expected a broken answer for: $text")
            } catch (e: AiException) {
                assertEquals(Problem.ANSWER, e.problem)
            }
        }
    }
}
