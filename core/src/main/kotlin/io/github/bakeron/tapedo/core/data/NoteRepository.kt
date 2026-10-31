package io.github.bakeron.tapedo.core.data

import io.github.bakeron.tapedo.core.model.Note
import kotlinx.coroutines.flow.Flow

/** Where notes live. Android implements it with an encrypted Room database. */
interface NoteRepository {
    /** All notes, newest first; emits again after every change. */
    fun observeAll(): Flow<List<Note>>

    suspend fun get(id: Long): Note?

    /** Inserts when [Note.id] is 0, otherwise updates. Returns the note id. */
    suspend fun save(note: Note): Long

    suspend fun delete(id: Long)
}
