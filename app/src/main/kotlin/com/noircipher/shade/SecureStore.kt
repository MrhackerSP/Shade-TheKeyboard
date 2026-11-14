package com.noircipher.shade

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM helper backed by an Android Keystore key (one key per [alias]).
 *
 * Output format: Base64(iv[12] + ciphertext + tag). This matches the format the
 * old per-class helpers in WordDictionary / BigramModel produced, so existing
 * on-disk data stays readable.
 *
 * Both calls FAIL CLOSED: they return null on any error and never fall back to
 * plaintext. Callers must treat null as "don't persist".
 */
object SecureStore {

    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    // Keystore lookups are slow (can be tens of ms); keep the key handle in memory.
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
    } catch (_: Exception) {
        keyCache.remove(alias)
        null
    }

    fun decrypt(alias: String, stored: String): String? = try {
        val bytes = Base64.decode(stored.trim(), Base64.NO_WRAP)
        if (bytes.size <= IV_SIZE) {
            null
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(alias),
                GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_SIZE))
            )
            String(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        keyCache.remove(alias)
        null
    }
}
