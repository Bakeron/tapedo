package io.github.bakeron.tapedo.ui.cards

import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeTypeTest {

    private val list = Note(
        title = "Lista",
        type = NoteType.CHECKLIST,
        createdAtMillis = 0,
        items = listOf(ChecklistItem("chleb"), ChecklistItem("mleko", checked = true)),
    )

    @Test
    fun `a list turned into a note keeps its items as lines of the text`() {
        val note = list.changeType(NoteType.NOTE)
        assertEquals("chleb\n✓ mleko", note.body)
        assertTrue(note.items.isEmpty())
    }

    @Test
    fun `text already on the list stays above the items`() {
        val note = list.copy(body = "na jutro").changeType(NoteType.TASK)
        assertEquals("na jutro\nchleb\n✓ mleko", note.body)
    }

    @Test
    fun `back to a list the lines become items again, ticked ones ticked`() {
        val back = list.changeType(NoteType.NOTE).changeType(NoteType.CHECKLIST)
        assertEquals(list.items, back.items)
        assertEquals("", back.body)
    }

    @Test
    fun `a shopping list stays a list when it becomes a checklist`() {
        val shopping = Note(title = "Zakupy", type = NoteType.SHOPPING, items = listOf(ChecklistItem("chleb", group = ShoppingGroup.BAKERY)), createdAtMillis = 0)
        assertEquals(shopping.items, shopping.changeType(NoteType.CHECKLIST).items)
    }
}
