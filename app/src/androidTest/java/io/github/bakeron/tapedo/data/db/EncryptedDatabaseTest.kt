package io.github.bakeron.tapedo.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The encrypted database on a real Android (emulator only) - what step 3 of docs/plan-1a.md checked by hand.
 * Own file names and Keystore alias, so the real notes are never opened, set aside or deleted.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = TapedoDatabase.Files(database = "test-tapedo.db", passphrase = "test-db.key", keyAlias = "test_db_wrap")
    private val dbDir: File get() = context.getDatabasePath(files.database).parentFile!!

    @Before
    @After
    fun clean() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keystore.containsAlias(files.keyAlias)) keystore.deleteEntry(files.keyAlias)
        dbDir.listFiles { f -> f.name.startsWith(files.base) }?.forEach { it.delete() }
        context.noBackupFilesDir.listFiles { f -> f.name.startsWith("test-db") }?.forEach { it.delete() }
    }

    @Test
    fun fileIsUnreadableWithoutKeyAndReopensWithIt() = runBlocking {
        val note = Note(
            title = "Zakupy",
            type = NoteType.SHOPPING,
            items = listOf(ChecklistItem("Mleko", checked = true)),
            createdAtMillis = 1_000,
        )
        val first = TapedoDatabase.open(context, files)
        val id = first.notes().upsert(note.toEntity())
        first.close()

        val header = context.getDatabasePath(files.database).inputStream().use { stream -> ByteArray(16).also { stream.read(it) } }
        assertNotEquals("plain SQLite header - the file is not encrypted", "SQLite format 3\u0000", header.toString(Charsets.US_ASCII))

        // The raw x'...' key must open the same file again on the next launch.
        val second = TapedoDatabase.open(context, files)
        val read = second.notes().get(id)?.toNote()
        second.close()
        assertEquals(note.copy(id = id), read)
    }

    @Test
    fun lostKeySetsFilesAsideAndStartsEmpty() = runBlocking {
        val db = TapedoDatabase.open(context, files)
        db.notes().upsert(Note(title = "Stara", createdAtMillis = 1).toEntity())
        db.close()
        val before = dbDir.listFiles { f -> f.name.startsWith(files.base) }!!.map { it.name }.toSet()
        assertTrue(files.database in before)

        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(files.keyAlias)

        val fresh = TapedoDatabase.open(context, files)
        assertNull(fresh.notes().get(1))
        fresh.close()

        val aside = dbDir.listFiles { f -> f.name.startsWith("${files.base}-locked-") && f.name.contains(".db") }!!.map { it.name }
        // Every old file was renamed (none lost), all with the same time suffix.
        assertEquals(before.size, aside.size)
        assertEquals(1, aside.map { it.substringBefore(".db") }.toSet().size)
        assertTrue(TapedoDatabase.lockedNoticePending(context, files))
        val keysAside = context.noBackupFilesDir.listFiles { f -> f.name.startsWith("test-db-locked-") }!!
        assertEquals(1, keysAside.size)
    }
}
