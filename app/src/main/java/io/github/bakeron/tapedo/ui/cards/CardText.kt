package io.github.bakeron.tapedo.ui.cards

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 630 -> "10:30". */
fun clockTime(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)

/** "today", "tomorrow", "yesterday", otherwise a short date in the app language ("pt 7 lis"). */
fun dueText(context: Context, epochDay: Long, today: Long, locale: Locale): String = when (epochDay - today) {
    0L -> context.getString(R.string.card_due_today)
    1L -> context.getString(R.string.card_due_tomorrow)
    -1L -> context.getString(R.string.card_due_yesterday)
    else -> LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
}

/** The locale of the app language (set per app), not of the phone. */
@Composable
fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** "jutro · 10:30 · u dentysty" - date, time and place, whichever the card has. */
fun metaLine(context: Context, note: Note, today: Long, locale: Locale): String = listOfNotNull(
    note.dueEpochDay?.let { dueText(context, it, today, locale) },
    note.timeMinutes?.let { clockTime(it) },
    note.place,
).joinToString(" · ")

fun groupNameRes(group: ShoppingGroup?): Int = when (group) {
    ShoppingGroup.PRODUCE -> R.string.group_produce
    ShoppingGroup.BAKERY -> R.string.group_bakery
    ShoppingGroup.DAIRY -> R.string.group_dairy
    ShoppingGroup.MEAT -> R.string.group_meat
    ShoppingGroup.FROZEN -> R.string.group_frozen
    ShoppingGroup.PANTRY -> R.string.group_pantry
    ShoppingGroup.DRINKS -> R.string.group_drinks
    ShoppingGroup.HOUSEHOLD -> R.string.group_household
    ShoppingGroup.OTHER, null -> R.string.group_other
}

fun typeNameRes(type: NoteType): Int = when (type) {
    NoteType.NOTE -> R.string.note_type_note
    NoteType.CHECKLIST -> R.string.note_type_checklist
    NoteType.TASK -> R.string.note_type_task
    NoteType.IDEA -> R.string.note_type_idea
    NoteType.EVENT -> R.string.note_type_event
    NoteType.SHOPPING -> R.string.note_type_shopping
}

/** "Lista 2", not "Lista punktów 2" - the short name of a numbered card. */
fun numberedNameRes(type: NoteType): Int =
    if (type == NoteType.CHECKLIST) R.string.default_checklist_title else typeNameRes(type)

/**
 * The cards of each kind numbered in the order they were recorded - the first task is "Zadanie 1"
 * (author 10.11.2026: "Zakupy" over "Zakupy" on every strip was a muddle). Note id -> number.
 */
fun kindNumbers(notes: List<Note>): Map<Long, Int> =
    notes.groupBy { it.type }.values.flatMap { same ->
        same.sortedWith(compareBy({ it.createdAtMillis }, { it.id })).mapIndexed { i, note -> note.id to i + 1 }
    }.toMap()

@Composable
fun numberedName(type: NoteType, number: Int): String =
    stringResource(R.string.card_numbered, stringResource(numberedNameRes(type)), number)

/** A list titled only with its kind ("Lista", "Zakupy:") - the header above says the same. */
fun titleIsKindName(note: Note, kindNames: List<String>): Boolean {
    val title = note.title.trim().trimEnd(':', '.').trim()
    return kindNames.any { it.equals(title, ignoreCase = true) }
}

@Composable
fun groupName(group: ShoppingGroup?): String = stringResource(groupNameRes(group))

/** Shopping items in shop order (produce first), each group once; other lists keep the spoken order. */
fun groupedItems(note: Note): List<Pair<ShoppingGroup?, List<IndexedValue<ChecklistItem>>>> =
    if (note.type != NoteType.SHOPPING) {
        listOf(null to note.items.withIndex().toList())
    } else {
        note.items.withIndex()
            .groupBy { it.value.group ?: ShoppingGroup.OTHER }
            .toSortedMap(compareBy { it.ordinal })
            .map { (group, items) -> group to items }
    }

/** A task ticked off, or a list with every item ticked - it moves to the "Done" divider. */
fun Note.isDone(): Boolean =
    (type == NoteType.TASK && done) || (type.hasItems && items.isNotEmpty() && items.all { it.checked })

/** Search: the words anywhere in the title, text, place or list items, ignoring case. */
fun Note.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, ignoreCase = true) ||
        body.contains(q, ignoreCase = true) ||
        place?.contains(q, ignoreCase = true) == true ||
        items.any { it.text.contains(q, ignoreCase = true) }
}

/**
 * The title only repeats the text: the rules build it from the first sentence's words in order (skipping
 * ones that do not fit, ending with "…"), so every title word shows up in the body in the same order.
 * Such a title is not shown on a whole card (author 11.11.2026) - the text says it all.
 */
fun titleComesFromBody(note: Note): Boolean {
    fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val title = words(note.title)
    if (title.isEmpty()) return false
    val body = words(note.body).iterator()
    return title.all { w -> body.asSequence().any { it == w } }
}

/** The rules often keep the whole sentence as the body - no need to print the title twice. */
fun bodyRepeatsTitle(note: Note): Boolean {
    fun norm(s: String) = s.trim().trimEnd('.', '!', '?', '…').trim().lowercase()
    return norm(note.body) == norm(note.title)
}

/** Plain text for "Share" - readable in a message or an e-mail, with ticked items marked. */
fun shareText(context: Context, note: Note, today: Long, locale: Locale): String = buildString {
    appendLine(note.title)
    val meta = metaLine(context, note, today, locale)
    if (meta.isNotEmpty()) appendLine(meta)
    if (note.body.isNotBlank() && !bodyRepeatsTitle(note)) {
        appendLine()
        appendLine(note.body)
    }
    if (note.items.isNotEmpty()) {
        appendLine()
        for ((group, items) in groupedItems(note)) {
            if (group != null) appendLine(context.getString(groupNameRes(group)) + ":")
            items.forEach { (_, item) -> appendLine((if (item.checked) "☑ " else "☐ ") + item.text) }
        }
    }
}.trimEnd()

fun shareNote(context: Context, note: Note, today: Long, locale: Locale) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, note.title)
        .putExtra(Intent.EXTRA_TEXT, shareText(context, note, today, locale))
    context.startActivity(Intent.createChooser(send, context.getString(R.string.card_share)))
}
