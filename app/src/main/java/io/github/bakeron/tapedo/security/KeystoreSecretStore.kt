package io.github.bakeron.tapedo.security

import android.content.Context
import android.util.AtomicFile
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.settings.AiProvider
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Each provider's API key in its own file, encrypted with a Keystore key, excluded from backups. */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val dir = context.applicationContext.noBackupFilesDir
    private val cipher = KeystoreCipher("tapedo_api_keys")

    private fun file(provider: AiProvider) = AtomicFile(File(dir, "key_${provider.name.lowercase()}.bin"))

    override suspend fun apiKey(provider: AiProvider): String? = withContext(Dispatchers.IO) {
        val blob = try {
            file(provider).readFully()
        } catch (e: FileNotFoundException) {
            return@withContext null
        }
        // A key that cannot be decrypted (e.g. Keystore reset) is treated as missing.
        runCatching { cipher.decrypt(blob).toString(Charsets.UTF_8) }.getOrNull()
    }

    override suspend fun setApiKey(provider: AiProvider, key: String?) = withContext(Dispatchers.IO) {
        val f = file(provider)
        val clean = key?.trim()
        if (clean.isNullOrEmpty()) f.delete() else f.writeAtomically(cipher.encrypt(clean.toByteArray(Charsets.UTF_8)))
    }
}
