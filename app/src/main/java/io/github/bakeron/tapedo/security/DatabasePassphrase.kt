package io.github.bakeron.tapedo.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom

/**
 * The SQLCipher passphrase: 32 random bytes generated on first launch, stored on disk
 * only in encrypted form (wrapped by a Keystore key). Copying the app's files to another
 * device gives nothing - the wrapping key cannot leave this phone.
 */
object DatabasePassphrase {

    const val FILE_NAME = "db.key"
    const val KEY_ALIAS = "tapedo_db_wrap"

    // noBackupFilesDir: never included in backups, even if backup rules change later.
    private fun file(context: Context, fileName: String) = File(context.noBackupFilesDir, fileName)

    /**
     * The passphrase as 64 hex characters, created on first launch.
     * @param mayCreate false when a database already exists - a new passphrase could never open it.
     * @param fileName, alias only tests pass their own - so they never touch the real passphrase.
     * @throws KeyLostException the passphrase can never be read again (permanent).
     *   Any other exception is temporary (Keystore busy, I/O) - nothing was changed.
     */
    fun get(context: Context, mayCreate: Boolean, fileName: String = FILE_NAME, alias: String = KEY_ALIAS): ByteArray {
        val stored = AtomicFile(file(context, fileName))
        val cipher = KeystoreCipher(alias)
        val blob = try {
            stored.readFully()
        } catch (e: FileNotFoundException) {
            null
        }
        if (blob != null) return cipher.decrypt(blob)
        if (!mayCreate) throw KeyLostException("Passphrase file is missing")

        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Hex text instead of raw bytes keeps the passphrase plain ASCII - no encoding edge cases.
        val passphrase = raw.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        raw.fill(0)
        stored.writeAtomically(cipher.encrypt(passphrase))
        return passphrase
    }

    /** Moves the unreadable passphrase file aside under [suffix] ("db.key" -> "db-<suffix>.key") - renamed, never deleted. */
    fun setAside(context: Context, suffix: String, fileName: String = FILE_NAME) {
        val current = file(context, fileName)
        val base = fileName.removeSuffix(".key")
        if (current.exists() && !current.renameTo(File(context.noBackupFilesDir, "$base-$suffix.key"))) {
            throw IOException("Could not set the passphrase file aside")
        }
        // Leftovers of an interrupted write would otherwise be read back as the current file.
        AtomicFile(current).delete()
    }
}
