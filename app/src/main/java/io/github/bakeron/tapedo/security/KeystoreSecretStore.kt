package io.github.bakeron.tapedo.security

import android.content.Context
import android.util.AtomicFile
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.settings.AiProvider
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Each provider's API key in its own file, encrypted with a Keystore key, excluded from backups.
 * [dir] and [alias] are only changed by tests - so they never touch the user's real keys.
 */
class KeystoreSecretStore(
    context: Context,
    private val dir: File = context.applicationContext.noBackupFilesDir,
    alias: String = "tapedo_api_keys",
) : SecretStore {

    private val cipher = KeystoreCipher(alias)

    private fun file(provider: AiProvider) = AtomicFile(File(dir, "key_${provider.name.lowercase()}.bin"))

    override suspend fun apiKey(provider: AiProvider): String? = withContext(Dispatchers.IO) {
        // A failure other than a lost key survives the retries and is thrown: the key is still there.
        try {
            retryTemporary {
                val blob = try {
                    file(provider).readFully()
                } catch (e: FileNotFoundException) {
                    return@retryTemporary null
                }
                cipher.decrypt(blob).toString(Charsets.UTF_8)
            }
        } catch (e: KeyLostException) {
            // Can never be decrypted again (e.g. Keystore reset) - the user has to paste the key once more.
            null
        }
    }

    override suspend fun setApiKey(provider: AiProvider, key: String?) = withContext(Dispatchers.IO) {
        val f = file(provider)
        val clean = key?.trim()
        if (clean.isNullOrEmpty()) f.delete() else f.writeAtomically(cipher.encrypt(clean.toByteArray(Charsets.UTF_8)))
    }
}

/**
 * The Keystore sometimes fails for a moment (busy right after boot or while another app uses it) -
 * pause and try again before bothering the user. A key gone for good ([KeyLostException]) is not retried.
 */
internal suspend fun <T> retryTemporary(pausesMs: List<Long> = listOf(100, 400), block: () -> T): T {
    for (pause in pausesMs) {
        try {
            return block()
        } catch (e: KeyLostException) {
            throw e
        } catch (e: Exception) {
            delay(pause)
        }
    }
    return block()
}
