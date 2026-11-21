package io.github.bakeron.tapedo.core.model

import kotlinx.serialization.Serializable

enum class Priority { HIGH, MEDIUM, LOW }

/**
 * What kind of card a note is. The user picks one on the home screen before recording,
 * or leaves "auto" (no type = `null` choice) and the rules / AI pick it from the words.
 */
enum class NoteType {
    /** Free text. */
    NOTE,

    /** Items to tick off (packing, to-do steps). */
    CHECKLIST,

    /** One thing to do, ticked off as a whole, usually with a due date. */
    TASK,

    /** A loose thought - no priority, no due date. */
    IDEA,

    /** Something happening at a date / time / place ("Tuesday at 10 at the dentist"). */
    EVENT,

    /** A shopping list whose items are grouped by shop section. */
    SHOPPING,
    ;

    val hasItems: Boolean get() = this == CHECKLIST || this == SHOPPING
}

/** Shop sections for [NoteType.SHOPPING] items. Stored as names, shown translated by the app. */
@Serializable
enum class ShoppingGroup { PRODUCE, BAKERY, DAIRY, MEAT, FROZEN, PANTRY, SPICES, DRINKS, HOUSEHOLD, OTHER }

@Serializable
data class ChecklistItem(
    val text: String,
    val checked: Boolean = false,
    /** Shop section - only on shopping lists. */
    val group: ShoppingGroup? = null,
)

/**
 * One index card. A single recording can produce several of them
 * (e.g. "buy milk and call the dentist tomorrow" -> a shopping list and a task).
 */
data class Note(
    val id: Long = 0,
    val title: String,
    val body: String = "",
    val type: NoteType = NoteType.NOTE,
    val priority: Priority = Priority.MEDIUM,
    val items: List<ChecklistItem> = emptyList(),
    /** Due date as epoch day (days since 1970-01-01), or null when none was mentioned. */
    val dueEpochDay: Long? = null,
    /** Event start as minutes after midnight (10:30 -> 630), or null when no time was said. */
    val timeMinutes: Int? = null,
    /** Where an event happens ("u dentysty"), or null. */
    val place: String? = null,
    /** A task ticked off as done. */
    val done: Boolean = false,
    /** True when an AI provider structured this card - shown as a small stamp. */
    val byAi: Boolean = false,
    val createdAtMillis: Long,
)
