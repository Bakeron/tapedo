package io.github.bakeron.tapedo.data.db

import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/** The list items live as JSON inside one column - a format Room does not check, so these tests do. */
class NoteEntityTest {

    private fun row(itemsJson: String, type: String = "SHOPPING", priority: String = "HIGH") = NoteEntity(
        id = 7,
        title = "Zakupy",
        body = "",
        type = type,
        priority = priority,
        itemsJson = itemsJson,
        dueEpochDay = null,
        timeMinutes = null,
        place = null,
        done = false,
        byAi = false,
        createdAtMillis = 1_000,
    )

    @Test
    fun `every note type survives the trip to the database and back`() {
        val notes = listOf(
            Note(id = 1, title = "Film", body = "Polecił Marek", createdAtMillis = 10),
            Note(
                id = 2, title = "Spakować", type = NoteType.CHECKLIST, priority = Priority.LOW,
                items = listOf(ChecklistItem("Ręczniki", checked = true), ChecklistItem("Krem")),
                dueEpochDay = 20_760, createdAtMillis = 20,
            ),
            Note(id = 3, title = "Oddać raport", type = NoteType.TASK, dueEpochDay = 20_758, done = true, createdAtMillis = 30),
            Note(id = 4, title = "Aplikacja do kwiatów", type = NoteType.IDEA, byAi = true, createdAtMillis = 40),
            Note(
                id = 5, title = "Dentysta", type = NoteType.EVENT, priority = Priority.HIGH,
                dueEpochDay = 20_759, timeMinutes = 9 * 60 + 30, place = "Gabinet na Długiej", createdAtMillis = 50,
            ),
            Note(
                id = 6, title = "Zakupy", type = NoteType.SHOPPING,
                items = listOf(
                    ChecklistItem("Mleko", checked = true, group = ShoppingGroup.DAIRY),
                    ChecklistItem("Chleb", group = ShoppingGroup.BAKERY),
                    ChecklistItem("Coś", group = null),
                ),
                createdAtMillis = 60,
            ),
            // Nulls everywhere they are allowed, and a new card that has no id yet.
            Note(title = "Pusta", type = NoteType.EVENT, dueEpochDay = null, timeMinutes = null, place = null, createdAtMillis = 70),
        )
        for (note in notes) assertEquals(note.type.name, note, note.toEntity().toNote())
        assertEquals(NoteType.entries.toSet(), notes.map { it.type }.toSet())
    }

    @Test
    fun `items are stored in the version 1 format`() {
        val note = Note(
            title = "Zakupy", type = NoteType.SHOPPING, createdAtMillis = 1,
            items = listOf(ChecklistItem("Mleko", checked = true, group = ShoppingGroup.DAIRY), ChecklistItem("Chleb")),
        )
        assertEquals("""[{"text":"Mleko","checked":true,"group":"DAIRY"},{"text":"Chleb"}]""", note.toEntity().itemsJson)
        assertEquals("a card without items stores an empty column", "", note.copy(items = emptyList()).toEntity().itemsJson)
    }

    @Test
    fun `rows written by version 1 are still readable`() {
        val note = row("""[{"text":"Mleko","checked":true,"group":"DAIRY"},{"text":"Chleb","checked":false,"group":null}]""").toNote()
        assertEquals(
            listOf(ChecklistItem("Mleko", checked = true, group = ShoppingGroup.DAIRY), ChecklistItem("Chleb")),
            note.items,
        )
        assertEquals(NoteType.SHOPPING, note.type)
        assertEquals(Priority.HIGH, note.priority)
        assertEquals(emptyList<ChecklistItem>(), row("").toNote().items)
    }

    @Test
    fun `an unknown shop section or field does not hide the item`() {
        val note = row("""[{"text":"Gwoździe","checked":true,"group":"TOOLS","color":"red"},{"text":"Mleko","group":"DAIRY"}]""").toNote()
        assertEquals(
            listOf(ChecklistItem("Gwoździe", checked = true, group = null), ChecklistItem("Mleko", group = ShoppingGroup.DAIRY)),
            note.items,
        )
    }

    @Test
    fun `a damaged row loses only its items, never the whole card`() {
        val note = row("""[{"text":"Mleko",""").toNote()
        assertEquals("Zakupy", note.title)
        assertEquals(emptyList<ChecklistItem>(), note.items)
    }

    @Test
    fun `an unknown type or priority name falls back to a plain note`() {
        val note = row("", type = "REMINDER", priority = "CRITICAL").toNote()
        assertEquals(NoteType.NOTE, note.type)
        assertEquals(Priority.MEDIUM, note.priority)
    }
}
