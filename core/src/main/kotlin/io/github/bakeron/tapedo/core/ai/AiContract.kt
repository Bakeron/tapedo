package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import io.github.bakeron.tapedo.core.time.EpochDays
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What every provider needs besides the transcript. Only text leaves the phone - no ids, no metadata. */
data class StructureRequest(
    val transcript: String,
    val todayEpochDay: Long,
    val nowMillis: Long,
    val defaultPriority: Priority,
    /** Type picked on the home screen, or null for "auto" - the model decides. */
    val chosenType: NoteType? = null,
)

/** Why an AI call failed - shown to the user in plain words, never with the key or the note text. */
sealed class AiError(message: String) : Exception(message) {
    object MissingKey : AiError("No API key")
    /** A key is saved, but reading it failed for now (even after retries) - it is not lost. */
    object KeyUnreadable : AiError("API key could not be read")
    object InvalidKey : AiError("API key rejected")
    object RateLimited : AiError("Free limit reached")
    class Network(cause: Throwable) : AiError("Network problem: ${cause::class.simpleName}")
    class Server(val status: Int) : AiError("Provider error $status")
    class BadResponse(detail: String) : AiError("Unexpected answer: $detail")
}

/** A provider that turns a transcript into cards (Gemini, Groq...). Strategy pattern - swappable. */
interface AiStructurer {
    suspend fun structure(request: StructureRequest): List<Note>
}

/**
 * The prompt and the JSON shape are shared by all providers, so switching providers
 * changes only the HTTP call, not the behaviour.
 */
object AiContract {

    private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private const val MAX_CARDS = 10
    private const val MAX_ITEMS = 50
    private const val MAX_TITLE = 80
    private const val MAX_BODY = 4_000
    private const val MAX_PLACE = 120

    /** Type names in the JSON - lowercase, stable, independent of the enum's Kotlin names. */
    private val TYPE_NAMES = mapOf(
        NoteType.NOTE to "note",
        NoteType.CHECKLIST to "checklist",
        NoteType.TASK to "task",
        NoteType.IDEA to "idea",
        NoteType.EVENT to "event",
        NoteType.SHOPPING to "shopping",
    )

    private val TIME = Regex("(\\d{1,2}):(\\d{2})")

    fun systemPrompt(request: StructureRequest): String {
        val today = EpochDays.toIso(request.todayEpochDay)
        val weekday = WEEKDAYS[EpochDays.weekday(request.todayEpochDay)]
        val defaultPriority = request.defaultPriority.name.lowercase()
        val groups = ShoppingGroup.entries.joinToString(", ") { it.name.lowercase() }
        val typeRule = request.chosenType?.let {
            "- The user chose the type \"${TYPE_NAMES.getValue(it)}\": make exactly ONE card of that type from the whole memo."
        } ?: "- One card per separate task or topic. A memo with one thing gives one card."
        return """
            You turn a short voice memo into index cards for a to-do app.
            The memo may be Polish, English or mixed. It comes from speech recognition, so punctuation
            may be missing and a word may be misheard.

            Rules:
            - Write titles, bodies and items in the language of the memo. Never translate.
            $typeRule
            - type, one of:
              "shopping" - things to buy; every item gets a group: $groups;
              "checklist" - other things to tick off (packing, steps);
              "task" - one thing to do, often with a due date;
              "event" - something happening at a date / time / place (meeting, appointment, birthday);
              "idea" - a loose thought or idea to keep;
              "note" - anything else.
            - items: only for "shopping" and "checklist", each item short with the first letter capitalised;
              an empty list for other types.
            - title: short, at most 6 words; for a task, start with the action.
            - body: the relevant part of the memo without filler words. May be empty for a list.
            - priority: "high" if urgent or important is said, "low" if it is "someday" / "no rush",
              otherwise "$defaultPriority".
            - due: a date YYYY-MM-DD only when the memo says when ("tomorrow", "on Friday", a date),
              otherwise null. Today is $today ($weekday). Always null for "idea".
            - time: "HH:MM" (24 h) only for "event" when an hour is said, otherwise null.
            - place: where an "event" happens, in the memo's words, otherwise null.
            - Do not invent tasks, items, people or details that are not in the memo.

            Answer with JSON only, exactly this shape:
            {"cards":[{"title":"...","type":"note","priority":"medium","body":"...","items":[],"due":null,"time":null,"place":null}]}
            Shopping items look like {"text":"Milk","group":"dairy"}; checklist items are plain strings.
        """.trimIndent()
    }

    fun userMessage(request: StructureRequest): String = "Memo:\n" + request.transcript.trim()

    @Serializable
    private data class Reply(val cards: List<Card> = emptyList())

    @Serializable
    private data class Card(
        val title: String = "",
        val type: String = "note",
        val priority: String? = null,
        val body: String = "",
        /** Plain strings, or {"text","group"} objects for shopping - both accepted from any model. */
        val items: List<JsonElement> = emptyList(),
        val due: String? = null,
        val time: String? = null,
        val place: String? = null,
    )

    // coerceInputValues: a model that sends "items": null or "body": null gets the default instead of a failure.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Parses and sanitises the model's answer; anything unusable throws [AiError.BadResponse]. */
    fun parse(text: String, request: StructureRequest): List<Note> {
        val raw = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val reply = runCatching { json.decodeFromString(Reply.serializer(), raw) }
            .getOrElse { throw AiError.BadResponse("not the expected JSON") }

        // A type chosen by the user means one card, even if the model returned more.
        val limit = if (request.chosenType != null) 1 else MAX_CARDS
        val notes = reply.cards.take(limit).mapNotNull { card ->
            val parsedType = TYPE_NAMES.entries.firstOrNull { it.value.equals(card.type.trim(), ignoreCase = true) }?.key
            val wanted = request.chosenType ?: parsedType ?: NoteType.NOTE
            val items = card.items.mapNotNull(::itemOf).take(MAX_ITEMS)
            // A list with nothing on it is useless - keep the text as a note instead.
            val type = if (wanted.hasItems && items.isEmpty()) NoteType.NOTE else wanted
            val title = card.title.trim().take(MAX_TITLE).ifEmpty { items.firstOrNull()?.text ?: return@mapNotNull null }
            Note(
                title = title,
                body = card.body.trim().take(MAX_BODY),
                type = type,
                priority = when (card.priority?.lowercase()) {
                    "high" -> Priority.HIGH
                    "low" -> Priority.LOW
                    "medium" -> Priority.MEDIUM
                    else -> request.defaultPriority
                },
                items = when (type) {
                    NoteType.SHOPPING -> items.map { it.copy(group = it.group ?: ShoppingGroup.OTHER) }
                    NoteType.CHECKLIST -> items.map { it.copy(group = null) }
                    else -> emptyList()
                },
                dueEpochDay = if (type == NoteType.IDEA) null else EpochDays.parseIso(card.due),
                timeMinutes = if (type == NoteType.EVENT) parseTime(card.time) else null,
                place = if (type == NoteType.EVENT) card.place?.trim()?.take(MAX_PLACE)?.ifEmpty { null } else null,
                byAi = true,
                createdAtMillis = request.nowMillis,
            )
        }
        if (notes.isEmpty()) throw AiError.BadResponse("no cards")
        return notes
    }

    private fun itemOf(element: JsonElement): ChecklistItem? {
        val (text, group) = when (element) {
            is JsonPrimitive -> element.contentOrNull to null
            // Safe casts: a nested object in "text" / "group" skips the item instead of crashing the app.
            is JsonObject -> (element["text"] as? JsonPrimitive)?.contentOrNull to (element["group"] as? JsonPrimitive)?.contentOrNull
            else -> null to null
        }
        val clean = text?.trim()?.take(MAX_TITLE)?.ifEmpty { null } ?: return null
        val shopGroup = ShoppingGroup.entries.firstOrNull { it.name.equals(group?.trim(), ignoreCase = true) }
        return ChecklistItem(clean, group = shopGroup)
    }

    /** "HH:MM" -> minutes after midnight; null for anything else. */
    private fun parseTime(text: String?): Int? {
        val match = TIME.matchEntire(text?.trim() ?: return null) ?: return null
        val (h, m) = match.destructured.toList().map { it.toInt() }
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
}
