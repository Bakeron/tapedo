package io.github.bakeron.tapedo.core.rules

import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedStructurerTest {

    private val rules = RuleBasedStructurer()

    // 2026-10-31 is a Saturday.
    private val saturday = 20_757L
    private val now = 0L

    @Test
    fun `epoch day constant really is a Saturday`() {
        // 0 = Monday ... 5 = Saturday; next Saturday is a full week ahead.
        assertEquals(saturday + 7, rules.nextWeekday(saturday, 5))
        assertEquals(saturday + 2, rules.nextWeekday(saturday, 0))
    }

    @Test
    fun `plain sentence becomes one note titled with its first sentence`() {
        val cards = rules.structure("Film o kosmosie był świetny. Polecił go Marek.", saturday, now)
        assertEquals(1, cards.size)
        assertEquals("Film o kosmosie był świetny", cards[0].title)
        assertEquals(NoteType.NOTE, cards[0].type)
        assertEquals(Priority.MEDIUM, cards[0].priority)
    }

    @Test
    fun `sentence starting with an action is a task`() {
        assertEquals(NoteType.TASK, rules.classify("Zadzwonić do dentysty"))
        assertEquals(NoteType.TASK, rules.classify("muszę oddać książki do biblioteki"))
        assertEquals(NoteType.TASK, rules.classify("call the plumber"))
        assertEquals(NoteType.TASK, rules.classify("w piątek oddać raport")) // due date makes it a task
        // Nouns and numbers ending in "ć" are not verbs.
        assertEquals(NoteType.NOTE, rules.classify("Część rzeczy jest w piwnicy"))
        assertEquals(NoteType.NOTE, rules.classify("Radość z małych rzeczy"))
    }

    @Test
    fun `idea and event are recognised`() {
        assertEquals(NoteType.IDEA, rules.classify("Pomysł: aplikacja do podlewania kwiatów"))
        assertEquals(NoteType.IDEA, rules.classify("what if we painted the fence green"))
        assertEquals(NoteType.EVENT, rules.classify("Spotkanie z Anią w kawiarni"))
        assertEquals(NoteType.EVENT, rules.classify("we wtorek o 10 dentysta"))
    }

    @Test
    fun `event gets the hour, idea never gets a due date`() {
        val event = rules.structure("Jutro o 10:30 wizyta u dentysty", saturday, now).single()
        assertEquals(NoteType.EVENT, event.type)
        assertEquals(10 * 60 + 30, event.timeMinutes)
        assertEquals(saturday + 1, event.dueEpochDay)

        val idea = rules.structure("Pomysł na jutro: piknik nad jeziorem", saturday, now).single()
        assertEquals(NoteType.IDEA, idea.type)
        assertNull(idea.dueEpochDay)
    }

    @Test
    fun `hours in different spoken forms`() {
        assertEquals(10 * 60, rules.detectTime("o 10"))
        assertEquals(19 * 60, rules.detectTime("o 7 wieczorem"))
        assertEquals(9 * 60 + 15, rules.detectTime("o godzinie 9.15"))
        assertEquals(21 * 60, rules.detectTime("dinner at 9pm"))
        assertEquals(0, rules.detectTime("12 am"))
        assertEquals(14 * 60 + 5, rules.detectTime("start 14:05"))
        assertNull(rules.detectTime("o 25"))
        assertNull(rules.detectTime("kup 2 litry mleka"))
    }

    @Test
    fun `a word starting with am or pm is not an hour`() {
        assertNull(rules.detectTime("weź 2 ampułki leku"))
        assertNull(rules.detectTime("9 amazing views"))
        assertEquals(NoteType.NOTE, rules.classify("Weź 2 ampułki leku"))
        assertEquals(9 * 60, rules.detectTime("lekarz 9 a.m."))
    }

    @Test
    fun `shopping words produce a shopping list with shop sections`() {
        val cards = rules.structure("Kup mleko, chleb, pomidory i papier toaletowy", saturday, now)
        val list = cards.single()
        assertEquals(NoteType.SHOPPING, list.type)
        assertEquals(listOf("Mleko", "Chleb", "Pomidory", "Papier toaletowy"), list.items.map { it.text })
        assertEquals(
            listOf(ShoppingGroup.DAIRY, ShoppingGroup.BAKERY, ShoppingGroup.PRODUCE, ShoppingGroup.HOUSEHOLD),
            list.items.map { it.group },
        )
    }

    @Test
    fun `longer stems win when grouping`() {
        assertEquals(ShoppingGroup.HOUSEHOLD, ShoppingGroups.groupOf("Serwetki"))
        assertEquals(ShoppingGroup.DAIRY, ShoppingGroups.groupOf("Ser żółty"))
        assertEquals(ShoppingGroup.PRODUCE, ShoppingGroups.groupOf("Winogrona"))
        assertEquals(ShoppingGroup.DRINKS, ShoppingGroups.groupOf("Wino czerwone"))
        assertEquals(ShoppingGroup.HOUSEHOLD, ShoppingGroups.groupOf("Pasta do zębów"))
        assertEquals(ShoppingGroup.OTHER, ShoppingGroups.groupOf("Prezent dla Oli"))
    }

    @Test
    fun `list words produce a checklist titled before the colon`() {
        val list = rules.structure("Lista na wyjazd: ręczniki, krem i ładowarka", saturday, now).single()
        assertEquals(NoteType.CHECKLIST, list.type)
        assertEquals("Lista na wyjazd", list.title)
        assertEquals(listOf("Ręczniki", "Krem", "Ładowarka"), list.items.map { it.text })
        assertTrue(list.items.all { it.group == null })
    }

    @Test
    fun `mixed recording gives a task and a shopping list`() {
        val cards = rules.structure(
            "Jutro oddać raport szefowi. Zakupy: jajka, pomidory oraz kawa.",
            saturday, now,
        )
        assertEquals(2, cards.size)
        assertEquals(NoteType.TASK, cards[0].type)
        assertEquals("Jutro oddać raport szefowi", cards[0].title)
        assertEquals(NoteType.SHOPPING, cards[1].type)
        assertEquals("Zakupy", cards[1].title)
        assertEquals(listOf("Jajka", "Pomidory", "Kawa"), cards[1].items.map { it.text })
    }

    @Test
    fun `shopping word without items is a task`() {
        assertEquals(NoteType.TASK, rules.structure("muszę zrobić zakupy", saturday, now).single().type)
    }

    @Test
    fun `chosen type makes one card of that type`() {
        val text = "Kup mleko. Jutro zadzwonić do mamy."
        assertEquals(NoteType.NOTE, rules.structure(text, saturday, now, NoteType.NOTE).single().type)
        assertEquals(NoteType.IDEA, rules.structure(text, saturday, now, NoteType.IDEA).single().type)

        val task = rules.structure(text, saturday, now, NoteType.TASK).single()
        assertEquals(NoteType.TASK, task.type)
        assertEquals(saturday + 1, task.dueEpochDay)
    }

    @Test
    fun `chosen list type without list words lists every phrase`() {
        val list = rules.structure("ręczniki, krem do opalania i ładowarka", saturday, now, NoteType.CHECKLIST).single()
        assertEquals(listOf("Ręczniki", "Krem do opalania", "Ładowarka"), list.items.map { it.text })
        assertEquals("Lista", list.title)

        val shopping = rules.structure("mleko i chleb", saturday, now, NoteType.SHOPPING).single()
        assertEquals(NoteType.SHOPPING, shopping.type)
        assertEquals(listOf(ShoppingGroup.DAIRY, ShoppingGroup.BAKERY), shopping.items.map { it.group })
    }

    @Test
    fun `english shopping list works too`() {
        val cards = rules.structure("buy milk, eggs and bread", saturday, now)
        assertEquals(listOf("Milk", "Eggs", "Bread"), cards.single().items.map { it.text })
    }

    @Test
    fun `urgent words raise priority, not-urgent lowers it`() {
        assertEquals(Priority.HIGH, rules.detectPriority("to jest pilne"))
        assertEquals(Priority.HIGH, rules.detectPriority("Bardzo WAŻNE spotkanie"))
        assertEquals(Priority.HIGH, rules.detectPriority("urgent: call back"))
        assertEquals(Priority.LOW, rules.detectPriority("to nie pilne, kiedyś"))
        assertEquals(Priority.LOW, rules.detectPriority("no rush"))
        assertEquals(Priority.MEDIUM, rules.detectPriority("zwykła notatka"))
    }

    @Test
    fun `priority words inside longer words are ignored`() {
        // "ważne" must not match inside "nieważne"... which actually means "unimportant".
        assertEquals(Priority.MEDIUM, rules.detectPriority("nieważne co"))
    }

    @Test
    fun `default priority comes from settings`() {
        val lowByDefault = RuleBasedStructurer(defaultPriority = Priority.LOW)
        assertEquals(Priority.LOW, lowByDefault.structure("coś tam", saturday, now).single().priority)
    }

    @Test
    fun `relative due dates`() {
        assertEquals(saturday, rules.detectDueDay("zrobić dziś", saturday))
        assertEquals(saturday + 1, rules.detectDueDay("Jutro rano", saturday))
        assertEquals(saturday + 2, rules.detectDueDay("pojutrze wieczorem", saturday))
        assertEquals(saturday + 1, rules.detectDueDay("tomorrow at 9", saturday))
        assertNull(rules.detectDueDay("bez terminu", saturday))
    }

    @Test
    fun `weekday due dates point to the next such day`() {
        assertEquals(saturday + 6, rules.detectDueDay("w piątek", saturday))
        assertEquals(saturday + 4, rules.detectDueDay("we środę", saturday))
        assertEquals(saturday + 1, rules.detectDueDay("w niedzielę", saturday))
        assertEquals(saturday + 7, rules.detectDueDay("on saturday", saturday))
    }

    @Test
    fun `transcript without punctuation gets a capped title`() {
        val text = "trzeba pamiętać żeby zadzwonić do pana od ogrzewania i umówić przegląd pieca przed zimą"
        val title = rules.structure(text, saturday, now).single().title
        assertTrue(title.length <= 49)
        assertTrue(title.startsWith("Trzeba pamiętać"))
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `due and priority words after the list are not items`() {
        val cards = rules.structure("kup chleb i mleko na jutro", saturday, now)
        assertEquals(listOf("Chleb", "Mleko"), cards.single().items.map { it.text })
        assertEquals(saturday + 1, cards.single().dueEpochDay)
    }

    @Test
    fun `empty transcript gives no cards`() {
        assertTrue(rules.structure("   ", saturday, now).isEmpty())
    }
}
