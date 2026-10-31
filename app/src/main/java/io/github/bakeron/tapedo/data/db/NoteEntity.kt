package io.github.bakeron.tapedo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Database row. Kept separate from [Note] so the core model stays free of Room annotations. */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val type: String,
    val priority: String,
    /** List items (with shop section) as a JSON array - a list never needs to be queried item by item. */
    val itemsJson: String,
    val dueEpochDay: Long?,
    val timeMinutes: Int?,
    val place: String?,
    val done: Boolean,
    val byAi: Boolean,
    val createdAtMillis: Long,
)

private val itemsSerializer = ListSerializer(ChecklistItem.serializer())

// Lenient on read: a field added or renamed later must not make old rows unreadable.
private val itemsJsonFormat = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** A damaged row loses only its list items - never the whole stack (one bad row would fail the query). */
private fun decodeItems(json: String): List<ChecklistItem> =
    if (json.isBlank()) emptyList()
    else runCatching { itemsJsonFormat.decodeFromString(itemsSerializer, json) }.getOrDefault(emptyList())

fun NoteEntity.toNote() = Note(
    id = id,
    title = title,
    body = body,
    type = NoteType.entries.firstOrNull { it.name == type } ?: NoteType.NOTE,
    priority = Priority.entries.firstOrNull { it.name == priority } ?: Priority.MEDIUM,
    items = decodeItems(itemsJson),
    dueEpochDay = dueEpochDay,
    timeMinutes = timeMinutes,
    place = place,
    done = done,
    byAi = byAi,
    createdAtMillis = createdAtMillis,
)

fun Note.toEntity() = NoteEntity(
    id = id,
    title = title,
    body = body,
    type = type.name,
    priority = priority.name,
    itemsJson = if (items.isEmpty()) "" else Json.encodeToString(itemsSerializer, items),
    dueEpochDay = dueEpochDay,
    timeMinutes = timeMinutes,
    place = place,
    done = done,
    byAi = byAi,
    createdAtMillis = createdAtMillis,
)
