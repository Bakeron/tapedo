package io.github.bakeron.tapedo.data

import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.data.db.NoteDao
import io.github.bakeron.tapedo.data.db.NoteEntity
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RoomNoteRepositoryTest {

    /** Behaves like Room's @Upsert: the new row id on insert, -1 on update. */
    private class FakeDao : NoteDao {
        val rows = MutableStateFlow<Map<Long, NoteEntity>>(emptyMap())
        private var nextId = 100L

        override fun observeAll(): Flow<List<NoteEntity>> = rows.map { it.values.toList() }
        override suspend fun get(id: Long): NoteEntity? = rows.value[id]
        override suspend fun upsert(note: NoteEntity): Long {
            if (note.id != 0L && note.id in rows.value) {
                rows.update { it + (note.id to note) }
                return -1
            }
            val id = if (note.id == 0L) nextId++ else note.id
            rows.update { it + (id to note.copy(id = id)) }
            return id
        }
        override suspend fun delete(id: Long) = rows.update { it - id }
    }

    /** The first open fails like a busy Keystore after a restart; later ones work. */
    private class FlakyOpen(private val dao: NoteDao, private val failures: Int = 1) : () -> NoteDao {
        val calls = AtomicInteger()
        override fun invoke(): NoteDao {
            // Long enough that parallel callers really overlap.
            Thread.sleep(20)
            if (calls.incrementAndGet() <= failures) throw IOException("keystore busy")
            return dao
        }
    }

    private val note = Note(title = "Dentysta", createdAtMillis = 1)

    @Test
    fun `a failed open is retried on the next call, not remembered`() = runBlocking {
        val open = FlakyOpen(FakeDao())
        val repo = RoomNoteRepository(open)
        try {
            repo.get(1)
            fail("the first open should fail")
        } catch (e: IOException) {
            // expected
        }
        assertNull(repo.get(1))
        repo.get(2)
        assertEquals("opened once after the failure, then reused", 2, open.calls.get())
    }

    @Test
    fun `save returns the new id for a new card and the same id for an existing one`() = runBlocking {
        val dao = FakeDao()
        val repo = RoomNoteRepository { dao }
        val id = repo.save(note)
        assertEquals(100L, id)

        val again = repo.save(note.copy(id = id, title = "Dentysta o 9"))
        assertEquals("an update must not return Room's -1", id, again)
        assertEquals("Dentysta o 9", repo.get(id)?.title)
        assertEquals(listOf("Dentysta o 9"), repo.observeAll().first().map { it.title })
    }

    @Test
    fun `parallel first calls open the database once`() = runBlocking {
        val open = FlakyOpen(FakeDao(), failures = 0)
        val repo = RoomNoteRepository(open)
        (1..20).map { async(Dispatchers.Default) { repo.get(it.toLong()) } }.awaitAll()
        assertEquals(1, open.calls.get())
    }
}
