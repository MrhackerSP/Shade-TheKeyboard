package com.shade.keyboard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM helper backed by an Android Keystore key (one key per [alias]).
 * Output format: Base64(iv[12] + ciphertext + tag).
 * Both calls FAIL CLOSED: they return null on any error and never fall back to
 * plaintext. Callers must treat null as "don't persist".
 */
object SecureStore {
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /** Why a decrypt failed: data/key permanently unusable vs. retry-later error. */
    sealed interface DecryptOutcome {
        class Plain(val text: String) : DecryptOutcome
        /** Malformed payload, bad GCM tag, or a lost/invalidated key: the bytes are gone. */
        object Corrupt : DecryptOutcome
        /** Keystore daemon / IPC hiccup: the file may still be fine — retry later. */
        object Transient : DecryptOutcome
    }

    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, SecretKey>()

    private fun secretKey(alias: String): SecretKey {
        keyCache[alias]?.let { return it }
        return loadOrCreateKey(alias).also { keyCache[alias] = it }
    }

    private fun loadOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(alias: String, plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(alias))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    } catch (t: Exception) {
        ShadeLog.w("SecureStore", "keystore encrypt failed", t, once = true)
        keyCache.remove(alias)
        null
    }

    fun decryptTyped(alias: String, stored: String): DecryptOutcome {
        val bytes = try {
            Base64.decode(stored.trim(), Base64.NO_WRAP)
        } catch (t: Exception) {
            return DecryptOutcome.Corrupt
        }
        if (bytes.size <= IV_SIZE) return DecryptOutcome.Corrupt
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(alias),
                GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_SIZE))
            )
            DecryptOutcome.Plain(String(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8))
        } catch (t: Exception) {
            ShadeLog.w("SecureStore", "keystore decrypt failed", t, once = true)
            keyCache.remove(alias)
            when (t) {
                is AEADBadTagException,
                is KeyPermanentlyInvalidatedException,
                is UnrecoverableKeyException -> DecryptOutcome.Corrupt
                else -> DecryptOutcome.Transient
            }
        }
    }

    fun decrypt(alias: String, stored: String): String? =
        (decryptTyped(alias, stored) as? DecryptOutcome.Plain)?.text
}
