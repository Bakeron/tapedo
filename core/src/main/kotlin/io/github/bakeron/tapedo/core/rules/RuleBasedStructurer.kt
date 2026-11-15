package io.github.bakeron.tapedo.core.rules

import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.time.EpochDays

/** Titles for lists that were not given a heading - they come from the app's language. */
data class ListTitles(
    val shopping: String = "Zakupy",
    val checklist: String = "Lista",
)

/**
 * "No AI" mode: turns a transcript into cards with simple keyword rules (Polish and English).
 *
 * With the type left on "auto" (`chosenType == null`):
 * - shopping words ("kup", "zakupy", "buy", "shopping") -> a shopping list, items grouped by shop section,
 * - list words ("lista", "spakuj", "packing list") -> a checklist,
 * - everything else -> one card: an idea if it starts with "pomysł" / "idea", an event if it names
 *   a meeting or an hour ("spotkanie", "o 10"), a task if it has a due date or starts with an action
 *   ("zadzwonić", "muszę", "call"), otherwise a note.
 *
 * With a type chosen by the user, the whole recording becomes one card of that type.
 * Always: "pilne" / "ważne" / "urgent" -> high priority, "kiedyś" / "someday" -> low;
 * "dziś", "jutro", "w piątek", "tomorrow" -> due date.
 *
 * Dates are plain epoch-day arithmetic (no date library) so this stays platform-free.
 */
class RuleBasedStructurer(
    private val defaultPriority: Priority = Priority.MEDIUM,
    private val titles: ListTitles = ListTitles(),
) {

    fun structure(
        transcript: String,
        todayEpochDay: Long,
        nowMillis: Long,
        chosenType: NoteType? = null,
    ): List<Note> {
        val text = transcript.trim().replace(WHITESPACE, " ")
        if (text.isEmpty()) return emptyList()

        val ctx = Context(text, detectPriority(text), detectDueDay(text, todayEpochDay), nowMillis)
        return if (chosenType != null) listOf(cardOfType(chosenType, ctx)) else autoCards(ctx)
    }

    /** What every card of one recording shares. */
    private class Context(val text: String, val priority: Priority, val due: Long?, val nowMillis: Long)

    private fun autoCards(ctx: Context): List<Note> {
        val sentences = splitSentences(ctx.text)
        val shopping = sentences.filter { SHOPPING_TRIGGER.containsMatchIn(it) }
        val lists = (sentences - shopping.toSet()).filter { LIST_TRIGGER.containsMatchIn(it) }
        val rest = sentences - shopping.toSet() - lists.toSet()

        val cards = mutableListOf<Note>()
        if (rest.isNotEmpty()) cards += singleCard(classify(rest.joinToString(" ")), rest.joinToString(" "), ctx)
        if (lists.isNotEmpty()) cards += listCard(NoteType.CHECKLIST, lists, LIST_TRIGGER, ctx)
        if (shopping.isNotEmpty()) cards += listCard(NoteType.SHOPPING, shopping, SHOPPING_TRIGGER, ctx)
        return cards
    }

    private fun cardOfType(type: NoteType, ctx: Context): Note = when (type) {
        NoteType.CHECKLIST, NoteType.SHOPPING -> {
            val trigger = if (type == NoteType.SHOPPING) SHOPPING_TRIGGER else LIST_OR_SHOPPING
            val said = splitSentences(ctx.text).filter { trigger.containsMatchIn(it) }
            val items = said.flatMap { extractItems(it, trigger) }
            // No "kup" / "lista" with items after it - every phrase of the recording is an item.
            listNote(type, items.ifEmpty { cleanItems(ctx.text) }, headingOf(type, said + ctx.text, ctx), ctx)
        }
        else -> singleCard(type, ctx.text, ctx)
    }

    /** A card that is not a list: note, task, idea or event. */
    private fun singleCard(type: NoteType, body: String, ctx: Context): Note {
        val isIdea = type == NoteType.IDEA
        return Note(
            title = titleOf(body),
            body = body,
            type = type,
            // An idea has no priority or due date - it is just kept.
            priority = if (isIdea) defaultPriority else ctx.priority,
            dueEpochDay = if (isIdea) null else ctx.due,
            timeMinutes = if (type == NoteType.EVENT) detectTime(body) else null,
            createdAtMillis = ctx.nowMillis,
        )
    }

    private fun listCard(type: NoteType, sentences: List<String>, trigger: Regex, ctx: Context): Note {
        val items = sentences.flatMap { extractItems(it, trigger) }
        // A list word with nothing after it ("muszę zrobić zakupy") - keep the words as a task.
        if (items.isEmpty()) return singleCard(NoteType.TASK, sentences.joinToString(" "), ctx)
        return listNote(type, items, headingOf(type, sentences, ctx), ctx)
    }

    /** A heading the user said: text before a colon, or "zakupy" / "shopping list" for shopping. */
    private fun headingOf(type: NoteType, sentences: List<String>, ctx: Context): String? =
        sentences.firstNotNullOfOrNull { titleBeforeColon(it) }
            ?: if (type == NoteType.SHOPPING) SHOPPING_TITLE.find(ctx.text)?.let { titleFor(it.value) } else null

    private fun listNote(type: NoteType, items: List<String>, heading: String?, ctx: Context) = Note(
        title = heading ?: if (type == NoteType.SHOPPING) titles.shopping else titles.checklist,
        type = type,
        priority = ctx.priority,
        items = items.map {
            ChecklistItem(it, group = if (type == NoteType.SHOPPING) ShoppingGroups.groupOf(it) else null)
        },
        dueEpochDay = ctx.due,
        createdAtMillis = ctx.nowMillis,
    )

    /** Picks the type of a non-list card when the user left "auto". */
    internal fun classify(text: String): NoteType = when {
        IDEA_START.containsMatchIn(text) -> NoteType.IDEA
        EVENT_WORDS.containsMatchIn(text) || detectTime(text) != null -> NoteType.EVENT
        detectDueDay(text, 0) != null || startsWithAction(text) -> NoteType.TASK
        else -> NoteType.NOTE
    }

    private fun startsWithAction(text: String): Boolean {
        if (TASK_START.containsMatchIn(text)) return true
        // Polish infinitive first ("Zadzwonić do...", "Oddać raport") - the usual way to dictate a task.
        val first = text.substringBefore(' ').lowercase().trim(',', '.', ':', '!')
        return first.length >= 4 && first.endsWith("ć") && !first.endsWith("ość") && first !in NOT_VERBS
    }

    internal fun detectPriority(text: String): Priority = when {
        // "nie pilne" contains "pilne", so low-priority phrases are checked first.
        LOW_PRIORITY.containsMatchIn(text) -> Priority.LOW
        HIGH_PRIORITY.containsMatchIn(text) -> Priority.HIGH
        else -> defaultPriority
    }

    internal fun detectDueDay(text: String, today: Long): Long? {
        RELATIVE_DAYS.forEach { (regex, offset) -> if (regex.containsMatchIn(text)) return today + offset }
        WEEKDAYS.forEachIndexed { weekday, regex ->
            if (regex.containsMatchIn(text)) return nextWeekday(today, weekday)
        }
        return null
    }

    /** "o 10", "o 10:30", "o godzinie 7 wieczorem", "at 9", "9pm", "14:15" -> minutes after midnight. */
    internal fun detectTime(text: String): Int? {
        TIME_AM_PM.find(text)?.let { m ->
            val hour12 = m.groups["h"]!!.value.toInt()
            if (hour12 !in 1..12) return null
            val pm = m.groups["pm"]!!.value.startsWith("p", ignoreCase = true)
            return minutesOf(hour12 % 12 + if (pm) 12 else 0, m.groups["m"]?.value)
        }
        val m = TIME_SAID.findAll(text).firstOrNull { !isCountOfThings(text, it) } ?: TIME_COLON.find(text) ?: return null
        var hour = m.groups["h"]!!.value.toInt()
        val after = text.substring(m.range.last + 1)
        // "o 7 wieczorem" -> 19:00. "W nocy" is late evening up to 11 and early morning after midnight:
        // "o 11 w nocy" -> 23:00, "o 12 w nocy" -> 0:00, "o 2 w nocy" -> 2:00.
        if (NIGHT_AFTER.containsMatchIn(after)) {
            if (hour == 12) hour = 0 else if (hour in 6..11) hour += 12
        } else if (hour < 12 && EVENING_AFTER.containsMatchIn(after)) {
            hour += 12
        }
        return minutesOf(hour, m.groups["m"]?.value)
    }

    /** "pamiętaj o 5 zdjęciach", "zapytaj o 3 bilety", "kup o 2 więcej" - "o" + a number that counts things. */
    private fun isCountOfThings(text: String, m: MatchResult): Boolean {
        if (!m.groups["p"]!!.value.equals("o", ignoreCase = true)) return false
        return COUNTED_AFTER.containsMatchIn(text.substring(m.range.last + 1)) ||
            ABOUT_VERB_BEFORE.containsMatchIn(text.substring(0, m.range.first))
    }

    private fun minutesOf(hour: Int, minute: String?): Int? {
        val min = minute?.toInt() ?: 0
        return if (hour in 0..23 && min in 0..59) hour * 60 + min else null
    }

    /** Next given weekday strictly after today (0 = Monday ... 6 = Sunday). */
    internal fun nextWeekday(today: Long, weekday: Int): Long {
        val todayWeekday = EpochDays.weekday(today)
        var diff = weekday - todayWeekday
        if (diff <= 0) diff += 7
        return today + diff
    }

    /** Items said after the list word ("kup mleko i chleb"), or after a colon ("Lista na wyjazd: ..."). */
    internal fun extractItems(sentence: String, trigger: Regex = SHOPPING_TRIGGER): List<String> {
        val match = trigger.find(sentence) ?: return emptyList()
        val colon = sentence.indexOf(':', match.range.first)
        val tail = if (colon >= 0) sentence.substring(colon + 1) else sentence.substring(match.range.last + 1)
        return cleanItems(tail)
    }

    private fun cleanItems(phrase: String): List<String> {
        var tail = phrase.substringAfter(':').replace(TRAILING_PUNCTUATION, "")
        tail = FILLER.replace(tail, " ")
        tail = NOT_AN_ITEM.replace(tail, " ")
        return tail.split(ITEM_SEPARATOR)
            .map { it.trim(' ', ',', ':', ';', '-') }
            .filter { it.isNotEmpty() }
            .map { item -> item.replaceFirstChar { it.uppercaseChar() } }
    }

    /** "Lista na wyjazd: ręczniki, krem" -> "Lista na wyjazd"; null without a colon. */
    private fun titleBeforeColon(sentence: String): String? =
        sentence.substringBefore(':', "").takeIf { it.isNotBlank() }?.let(::titleFor)

    internal fun titleOf(body: String): String {
        val first = splitSentences(body).firstOrNull().orEmpty().trimEnd('.', '!', '?')
        return titleFor(first)
    }

    private fun titleFor(phrase: String): String {
        val said = phrase.trim().trimEnd('.', '!', '?', ',', ':')
        // "Pilne zadzwonić do hydraulika" - the priority already shows on the card's tape.
        val clean = PRIORITY_WORDS.replace(said, " ").replace(WHITESPACE, " ").trim(' ', ',', ':', '-').ifEmpty { said }
        val words = clean.split(' ')
        // Speech recognition often returns no punctuation at all - cap the title length.
        val short = if (clean.length <= MAX_TITLE || words.size <= 1) clean
        else words.fold("") { acc, w -> if ((acc + " " + w).trim().length > MAX_TITLE) acc else (acc + " " + w).trim() } + "…"
        return short.replaceFirstChar { it.uppercaseChar() }
    }

    private fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val MAX_TITLE = 48

        // (?iu): case-insensitive with Unicode case folding; \p{L} handles Polish letters,
        // which \b and \w do not treat as word characters by default.
        fun words(vararg alternatives: String) =
            Regex("(?iu)(?<!\\p{L})(" + alternatives.joinToString("|") + ")(?!\\p{L})")

        val WHITESPACE = Regex("\\s+")
        val SENTENCE_END = Regex("(?<=[.!?;])\\s+")
        val TRAILING_PUNCTUATION = Regex("[.!?;]+$")

        val SHOPPING_TRIGGER = words(
            "kup", "kupić", "kupię", "kupimy", "kupcie", "dokup", "dokupić",
            "zakupy", "zakupów", "lista zakupów", "buy", "shopping", "groceries", "shopping list",
        )
        val SHOPPING_TITLE = words("zakupy", "lista zakupów", "shopping list", "groceries")
        val LIST_TRIGGER = words(
            "lista", "listę", "listy", "spakować", "spakuj", "do spakowania",
            "list", "checklist", "packing list", "to-do list", "todo list", "pack",
        )
        val LIST_OR_SHOPPING = Regex(LIST_TRIGGER.pattern + "|" + SHOPPING_TRIGGER.pattern)

        val IDEA_START = Regex(
            "(?iu)^(pomysł|pomysl|mam pomysł|idea|a może by|może by|a gdyby|what if|maybe we could)(?!\\p{L})",
        )
        val EVENT_WORDS = words(
            "spotkanie", "spotkaniu", "spotkania", "spotykamy się", "zebranie", "wizyta", "wizytę", "wizycie",
            "umówiony", "umówiona", "umówieni", "urodziny", "imieniny", "ślub", "wesele", "koncert", "kino",
            "kolacja z", "obiad z", "lekarz o", "dentysta o",
            "meeting", "appointment", "party", "birthday", "wedding", "concert", "dinner with", "lunch with",
        )
        val TASK_START = Regex(
            "(?iu)^(muszę|musze|trzeba|pamiętaj|pamietaj|nie zapomnij|zadzwoń|zadzwon|zrób|zrob|wyślij|wyslij|" +
                "zapłać|zaplac|oddaj|napisz|sprawdź|sprawdz|odbierz|umów|umow|załatw|zalatw|" +
                "remember to|need to|have to|don't forget|dont forget|call|pay|send|fix|book|email|write|" +
                "finish|check|pick up|return|clean|order|schedule|renew|cancel)(?!\\p{L})",
        )
        // Common nouns / numbers ending in "ć" that are not infinitives ("-ość" nouns are excluded separately).
        val NOT_VERBS = setOf("część", "pięć", "sześć", "dziewięć", "dziesięć", "pamięć", "chęć", "śmierć", "nić", "maść")

        val HIGH_PRIORITY = words(
            "pilne", "pilnie", "pilny", "pilna", "ważne", "ważny", "ważna", "bardzo ważne",
            "natychmiast", "asap", "urgent", "urgently", "important",
        )
        val LOW_PRIORITY = words(
            "nie pilne", "niepilne", "kiedyś", "bez pośpiechu", "na spokojnie",
            "not urgent", "someday", "no rush", "low priority",
        )
        // Low first, so "nie pilne" goes as a whole instead of leaving "nie".
        val PRIORITY_WORDS = Regex(LOW_PRIORITY.pattern + "|" + HIGH_PRIORITY.pattern)

        val RELATIVE_DAYS = listOf(
            words("pojutrze", "day after tomorrow") to 2L,
            words("jutro", "jutra", "tomorrow") to 1L,
            words("dziś", "dzisiaj", "today", "tonight") to 0L,
        )

        // Index = weekday, 0 = Monday. Polish case forms after "w/we" included.
        val WEEKDAYS = listOf(
            words("poniedziałek", "poniedziałku", "monday"),
            words("wtorek", "wtorku", "tuesday"),
            words("środa", "środę", "środy", "wednesday"),
            words("czwartek", "czwartku", "thursday"),
            words("piątek", "piątku", "friday"),
            words("sobota", "sobotę", "soboty", "saturday"),
            words("niedziela", "niedzielę", "niedzieli", "sunday"),
        )

        // Speech recognition writes hours as digits: "o 10", "o 10:30", "at 9".
        val TIME_SAID = Regex(
            "(?iu)(?<!\\p{L})(?<p>o godzinie|na godzinę|godz\\.?|o|at)\\s+(?<h>\\d{1,2})(?:[:.](?<m>\\d{2}))?(?!\\d)",
        )
        // After a plain "o": a locative plural ("o 3 fakturach") or a quantity word means a count, not an hour.
        val COUNTED_AFTER = Regex("(?iu)^\\s+(\\p{L}+ach|więcej|wiecej|mniej|razy|sztuk|sztuki|procent)(?!\\p{L})")
        // Verbs that take "o" as "about" / "for" - "o" + a number right after them is not an hour.
        val ABOUT_VERB_BEFORE = Regex(
            "(?iu)(?<!\\p{L})(pamiętaj|pamietaj|pamiętać|pamietac|zapomnij|zapomnieć|zapomniec|zapytaj|zapytać|zapytac|" +
                "spytaj|spytać|spytac|dopytaj|poproś|popros|poprosić|poprosic|proś|pros|prosić|prosic|przypomnij)\\s+$",
        )
        // (?!\p{L}) at the end: "2 ampułki" is not 2 a.m.
        val TIME_AM_PM = Regex("(?iu)(?<![\\d:])(?<h>\\d{1,2})(?::(?<m>\\d{2}))?\\s*(?<pm>am|pm|a\\.m\\.|p\\.m\\.)(?!\\p{L})")
        val TIME_COLON = Regex("(?<![\\d:])(?<h>\\d{1,2}):(?<m>\\d{2})(?![\\d:])")
        val EVENING_AFTER = Regex("(?iu)^\\s*(wieczorem|wieczór|po południu|in the evening)")
        val NIGHT_AFTER = Regex("(?iu)^\\s*w nocy")

        val ITEM_SEPARATOR = Regex("(?iu),|\\.\\s+|\\s+(i|oraz|a także|and|plus)\\s+")
        val FILLER = Regex(
            "(?iu)(?<!\\p{L})(też|jeszcze|trochę|parę|muszę|trzeba|also|some|please|proszę)(?!\\p{L})",
        )
        // Due-date and priority phrases said inside the list ("kup chleb na jutro") - cut out
        // before splitting so they do not stick to the last item.
        val NOT_AN_ITEM = Regex(
            "(?iu)(?<!\\p{L})((na|w|we|on|by|for)\\s+)?(jutro|dziś|dzisiaj|pojutrze|pilne|pilnie|ważne|" +
                "poniedziałek|wtorek|środę|czwartek|piątek|sobotę|niedzielę|" +
                "tomorrow|today|tonight|urgent|urgently|important|" +
                "monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?!\\p{L})",
        )
    }
}
