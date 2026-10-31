package io.github.bakeron.tapedo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.bakeron.tapedo.security.DatabasePassphrase
import io.github.bakeron.tapedo.security.KeyLostException
import java.io.File
import java.io.IOException
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [NoteEntity::class], version = 1, exportSchema = true)
abstract class TapedoDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        private const val FILE_NAME = "tapedo.db"

        /** Left next to the database when old files were set aside; the home screen explains it once. */
        private const val LOCKED_NOTICE_FILE = "tapedo-locked-notice"
        private val SQLITE_SIDE_FILES = listOf("", "-wal", "-shm", "-journal")

        /**
         * Opens the database encrypted with SQLCipher (AES-256); the whole file is unreadable without the key.
         * Does disk and Keystore work - call it off the main thread.
         * A temporary Keystore / disk error is thrown as is and changes nothing; the next call tries again.
         */
        fun open(context: Context): TapedoDatabase {
            System.loadLibrary("sqlcipher")
            val dbFile = context.getDatabasePath(FILE_NAME)
            val passphrase = try {
                DatabasePassphrase.get(context, mayCreate = !dbFile.exists())
            } catch (e: KeyLostException) {
                // Android lost the key: these notes can never be decrypted, by anyone. Keep the files
                // (renamed, never deleted) and start an empty database instead of crashing on every launch.
                setAside(context, dbFile)
                DatabasePassphrase.get(context, mayCreate = true)
            }
            // Raw-key form x'<64 hex>': the key is already random, so SQLCipher skips its slow
            // password stretching (PBKDF2, 256 000 rounds) on every launch.
            val rawKey = "x'".toByteArray(Charsets.US_ASCII) + passphrase + "'".toByteArray(Charsets.US_ASCII)
            passphrase.fill(0)
            // The factory wipes rawKey from memory once the database is open.
            val factory = SupportOpenHelperFactory(rawKey)
            return Room.databaseBuilder(context, TapedoDatabase::class.java, FILE_NAME)
                .openHelperFactory(factory)
                .build()
        }

        /** Renames the database files to "tapedo-locked-<time>.db*" (the home screen tells the user once). */
        private fun setAside(context: Context, dbFile: File) {
            val suffix = "locked-${System.currentTimeMillis()}"
            for (side in SQLITE_SIDE_FILES) {
                val f = File(dbFile.path + side)
                if (f.exists() && !f.renameTo(File(dbFile.parentFile, "tapedo-$suffix.db$side"))) {
                    // Opening a new database over the old file would fail anyway - better to retry later.
                    throw IOException("Could not set aside ${f.name}")
                }
            }
            DatabasePassphrase.setAside(context, suffix)
            File(dbFile.parentFile, LOCKED_NOTICE_FILE).createNewFile()
        }

        /** True when notes were set aside after a lost key and the user has not been told yet. */
        fun lockedNoticePending(context: Context): Boolean =
            File(context.getDatabasePath(FILE_NAME).parentFile, LOCKED_NOTICE_FILE).exists()

        fun dismissLockedNotice(context: Context) {
            File(context.getDatabasePath(FILE_NAME).parentFile, LOCKED_NOTICE_FILE).delete()
        }
    }
}
