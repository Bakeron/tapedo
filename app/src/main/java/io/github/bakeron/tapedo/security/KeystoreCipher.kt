package io.github.bakeron.tapedo.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.IOException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The data was encrypted with a Keystore key that no longer exists or no longer matches
 * (e.g. Android lost it after a system update). It can never be decrypted - not later, not by anyone.
 * Any other exception from [KeystoreCipher] is temporary: nothing changed, try again.
 */
class KeyLostException(message: String) : Exception(message)

/**
 * AES-256-GCM with a key that lives inside Android Keystore: the key never leaves
 * the secure hardware (or the OS keystore), so the app can encrypt and decrypt
 * but cannot read or export the key itself.
 *
 * Output format of [encrypt]: 12-byte IV followed by ciphertext + 16-byte auth tag.
 */
class KeystoreCipher(private val alias: String) {

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size <= IV_SIZE) throw KeyLostException("Encrypted blob is too short")
        // Never create a key here - a new key could not decrypt old data anyway.
        val key = existingKey() ?: throw KeyLostException("Keystore key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
            return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyLostException("Keystore key was invalidated")
        } catch (e: AEADBadTagException) {
            throw KeyLostException("Data does not match the key")
        }
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

    private fun key(): SecretKey {
        existingKey()?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/** All or nothing: a crash or a dead battery mid-write leaves the old file, never half of a new one. */
internal fun AtomicFile.writeAtomically(bytes: ByteArray) {
    val out = startWrite()
    try {
        out.write(bytes)
        finishWrite(out)
    } catch (e: IOException) {
        failWrite(out)
        throw e
    }
}
