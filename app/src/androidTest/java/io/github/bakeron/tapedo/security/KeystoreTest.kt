package io.github.bakeron.tapedo.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bakeron.tapedo.core.settings.AiProvider
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keystore encryption on a real Android (emulator only). Every test uses its own aliases and files,
 * so it never touches the real passphrase or API keys - but still: never run on a phone with real notes.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val dir = File(context.noBackupFilesDir, "test-keystore").apply { mkdirs() }

    @Before
    @After
    fun clean() {
        listOf(CIPHER_ALIAS, PASS_ALIAS, SECRETS_ALIAS).forEach { if (keystore.containsAlias(it)) keystore.deleteEntry(it) }
        dir.deleteRecursively()
        context.noBackupFilesDir.listFiles { f -> f.name.startsWith("test-db") }?.forEach { it.delete() }
        dir.mkdirs()
    }

    @Test
    fun encryptThenDecryptGivesTheSameBytes() {
        val cipher = KeystoreCipher(CIPHER_ALIAS)
        val plain = "zażółć gęślą jaźń".toByteArray()
        assertArrayEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test
    fun deletedKeyIsLostNotTemporary() {
        val cipher = KeystoreCipher(CIPHER_ALIAS)
        val blob = cipher.encrypt(byteArrayOf(1, 2, 3))
        keystore.deleteEntry(CIPHER_ALIAS)
        assertLost { cipher.decrypt(blob) }
        // Decrypting must never create a new key - it could not open old data anyway.
        assertFalse(keystore.containsAlias(CIPHER_ALIAS))
    }

    @Test
    fun changedByteIsLost() {
        val cipher = KeystoreCipher(CIPHER_ALIAS)
        val blob = cipher.encrypt(ByteArray(40) { it.toByte() })
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertLost { cipher.decrypt(blob) }
    }

    @Test
    fun tooShortBlobIsLost() {
        assertLost { KeystoreCipher(CIPHER_ALIAS).decrypt(ByteArray(12)) }
    }

    @Test
    fun passphraseIsStableAnd64Hex() {
        val first = DatabasePassphrase.get(context, mayCreate = true, PASS_FILE, PASS_ALIAS)
        val second = DatabasePassphrase.get(context, mayCreate = true, PASS_FILE, PASS_ALIAS)
        assertArrayEquals(first, second)
        val text = first.toString(Charsets.US_ASCII)
        assertEquals(64, text.length)
        assertTrue(text.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun missingPassphraseWithoutCreateIsLostAndCreatesNothing() {
        assertLost { DatabasePassphrase.get(context, mayCreate = false, PASS_FILE, PASS_ALIAS) }
        assertFalse(File(context.noBackupFilesDir, PASS_FILE).exists())
    }

    @Test
    fun apiKeyIsTrimmedDeletedWhenBlankAndMissingAfterKeyLoss() = runBlocking {
        val store = KeystoreSecretStore(context, dir, SECRETS_ALIAS)
        store.setApiKey(AiProvider.GEMINI, "  AIzaTestKey123  ")
        assertEquals("AIzaTestKey123", store.apiKey(AiProvider.GEMINI))

        store.setApiKey(AiProvider.GEMINI, "   ")
        assertNull(store.apiKey(AiProvider.GEMINI))
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("key_gemini") })

        store.setApiKey(AiProvider.GROQ, "gsk_test_key_1234")
        keystore.deleteEntry(SECRETS_ALIAS)
        // Keystore reset: the key is gone for good - shown as "no key", never a crash.
        assertNull(store.apiKey(AiProvider.GROQ))
    }

    private fun assertLost(block: () -> Unit) {
        try {
            block()
            fail("expected KeyLostException")
        } catch (e: KeyLostException) {
            // expected: permanent loss, not a temporary error
        }
    }

    private companion object {
        const val CIPHER_ALIAS = "test_cipher"
        const val PASS_ALIAS = "test_db_wrap"
        const val SECRETS_ALIAS = "test_api_keys"
        const val PASS_FILE = "test-db.key"
    }
}
