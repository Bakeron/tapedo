package io.github.bakeron.tapedo.data

import io.github.bakeron.tapedo.core.data.NoteRepository
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.data.db.NoteDao
import io.github.bakeron.tapedo.data.db.toEntity
import io.github.bakeron.tapedo.data.db.toNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The database opens on first use, off the main thread (it reads the Keystore and a file),
 * so the app starts without waiting for it. A failed open is retried on the next call.
 */
class RoomNoteRepository(private val openDao: () -> NoteDao) : NoteRepository {

    private val mutex = Mutex()
    private var dao: NoteDao? = null

    private suspend fun dao(): NoteDao = mutex.withLock {
        dao ?: withContext(Dispatchers.IO) { openDao() }.also { dao = it }
    }

    override fun observeAll(): Flow<List<Note>> =
        flow { emitAll(dao().observeAll()) }.map { rows -> rows.map { it.toNote() } }

    override suspend fun get(id: Long): Note? = dao().get(id)?.toNote()

    override suspend fun save(note: Note): Long {
        val rowId = dao().upsert(note.toEntity())
        return if (note.id == 0L) rowId else note.id
    }

    override suspend fun delete(id: Long) = dao().delete(id)
}
