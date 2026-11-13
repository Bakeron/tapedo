package io.github.bakeron.tapedo.ui.cards

import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextTest {

    private fun note(id: Long, type: NoteType, at: Long, title: String = "x") =
        Note(id = id, title = title, type = type, createdAtMillis = at)

    @Test
    fun `each kind is numbered from 1 in the order recorded`() {
        val notes = listOf(
            note(5, NoteType.TASK, at = 300),
            note(2, NoteType.SHOPPING, at = 100),
            note(3, NoteType.TASK, at = 100),
            note(4, NoteType.SHOPPING, at = 200),
        )
        assertEquals(mapOf(3L to 1, 5L to 2, 2L to 1, 4L to 2), kindNumbers(notes))
    }

    @Test
    fun `cards recorded at the same moment keep the id order`() {
        val notes = listOf(note(9, NoteType.IDEA, at = 50), note(7, NoteType.IDEA, at = 50))
        assertEquals(mapOf(7L to 1, 9L to 2), kindNumbers(notes))
    }

    @Test
    fun `a list titled only with its kind is recognised, a real heading is not`() {
        val names = listOf("Lista punktów", "Lista", "Zakupy")
        assertTrue(titleIsKindName(note(1, NoteType.CHECKLIST, 0, title = "Lista"), names))
        assertTrue(titleIsKindName(note(1, NoteType.SHOPPING, 0, title = "zakupy:"), names))
        assertFalse(titleIsKindName(note(1, NoteType.CHECKLIST, 0, title = "Lista na wyjazd"), names))
    }

    @Test
    fun `a title made of the text's own words is a repeat, a real heading is not`() {
        // From the phone 11.11.2026: the rules skipped "pietruszki klawiatura" to fit the title.
        val body = "lista zakupów monitor klawiatura natka pietruszki klawiatura druga głośniki słuchawki shake"
        val repeat = Note(title = "Lista zakupów monitor klawiatura natka druga…", body = body, type = NoteType.IDEA, createdAtMillis = 0)
        assertTrue(titleComesFromBody(repeat))
        assertTrue(titleComesFromBody(Note(title = "Zadzwonić do mamy.", body = "zadzwonić do mamy", type = NoteType.TASK, createdAtMillis = 0)))
        assertFalse(titleComesFromBody(Note(title = "Sprzęt do biura", body = body, type = NoteType.IDEA, createdAtMillis = 0)))
        assertFalse(titleComesFromBody(Note(title = "druga lista", body = body, type = NoteType.NOTE, createdAtMillis = 0)))
        assertFalse(titleComesFromBody(Note(title = "Pomysł", body = "", type = NoteType.IDEA, createdAtMillis = 0)))
    }
}
