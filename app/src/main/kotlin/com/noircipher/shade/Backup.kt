package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup / restore of settings and learned words as a single JSON file.
 *
 * Schema v2: includes the 5 adaptive-learning SharedPreferences stores
 * (shade_adaptive_words, shade_app_context, shade_autocorrect_suppressor,
 * shade_confidence_calibrator, shade_online_ranker) in addition to the
 * legacy `user_words` store and the default preference set.
 *
 * Files are encrypted with AES-256-GCM using a key derived from a user passphrase
 * (PBKDF2-HMAC-SHA256). Android Keystore keys are device-bound and can't be exported,
 * so a passphrase is what makes a backup restorable on a new phone.
 * File layout: "SHD1" + salt(16) + iv(12) + ciphertext. Plaintext JSON backups are
 * not supported.
 *
 * Backward compat: imports of v1 backups (without `adaptive` block) still
 * work — the adaptive stores are left untouched in that case.
 */
object Backup {

    private const val SCHEMA_VERSION = 2

    /** SharedPreferences files included in the backup. */
    private val ADAPTIVE_PREFS_FILES = LearningDataCleaner.ADAPTIVE_PREFS_FILES

    private const val MAGIC = "SHD1"
    private const val PBKDF2_ITERATIONS = 210_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val KEY_SIZE_BITS = 256

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun encrypt(plain: String, passphrase: String): ByteArray? = try {
        val rng = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(iv)
        out.write(encrypted)
        out.toByteArray()
    } catch (_: Exception) { null }

    /** Returns null for a wrong passphrase or a damaged file (GCM tag mismatch). */
    private fun decrypt(data: ByteArray, passphrase: String): String? = try {
        if (data.size < 4 + SALT_SIZE + IV_SIZE + 16) null else {
            val salt = data.copyOfRange(4, 4 + SALT_SIZE)
            val iv = data.copyOfRange(4 + SALT_SIZE, 4 + SALT_SIZE + IV_SIZE)
            val encrypted = data.copyOfRange(4 + SALT_SIZE + IV_SIZE, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }
    } catch (_: Exception) { null }

    /** True when the file at [uri] is an encrypted Shade backup. */
    fun isEncrypted(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(head, read, 4 - read)
                if (n < 0) break
                read += n
            }
            read == 4 && String(head, Charsets.US_ASCII) == MAGIC
        } ?: false
    } catch (_: Exception) { false }

    fun export(ctx: Context, uri: Uri, passphrase: String): Boolean = try {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val p = JSONObject()
        for ((k, v) in prefs.all) {
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            p.put(k, o)
        }

        // Legacy `user_words` store — Float scores (NOT Int; older buggy
        // exports would silently drop these — fixed in schema v2).
        val words = JSONObject()
        val userWordsPrefs = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
        for ((w, n) in userWordsPrefs.all) {
            when (n) {
                is Float -> words.put(w, n.toDouble())
                is Int -> words.put(w, n.toDouble())
                is Long -> words.put(w, n.toDouble())
            }
        }

        // Adaptive-learning stores. Each is serialized as a JSONObject of
        // (string-key -> any-value). We store the raw SharedPreferences map
        // entries; restoration re-applies them with the same types.
        val adaptive = JSONObject()
        for (file in ADAPTIVE_PREFS_FILES) {
            val sp = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
            val entries = JSONObject()
            for ((k, v) in sp.all) {
                when (v) {
                    is Boolean -> entries.put(k, JSONObject().put("t", "b").put("v", v))
                    is Int -> entries.put(k, JSONObject().put("t", "i").put("v", v))
                    is Long -> entries.put(k, JSONObject().put("t", "l").put("v", v))
                    is Float -> entries.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                    is String -> entries.put(k, JSONObject().put("t", "s").put("v", v))
                    is Set<*> -> entries.put(k, JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() })))
                    else -> continue
                }
            }
            adaptive.put(file, entries)
        }

        val root = JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("prefs", p)
            .put("words", words)
            .put("adaptive", adaptive)
        val data = encrypt(root.toString(2), passphrase) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } != null
    } catch (_: Exception) {
        false
    }

    fun import(ctx: Context, uri: Uri, passphrase: String): Boolean {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            // Only encrypted (SHD1) backups are accepted; plaintext JSON is not supported.
            if (bytes.size < 4 || String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) != MAGIC) return false
            val text = decrypt(bytes, passphrase) ?: return false
            val root = JSONObject(text)
            val version = root.optInt("version", 1)

            // Default preference set.
            val edit = PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            val p = root.getJSONObject("prefs")
            for (k in p.keys()) {
                val o = p.getJSONObject(k)
                when (o.getString("t")) {
                    "b" -> edit.putBoolean(k, o.getBoolean("v"))
                    "i" -> edit.putInt(k, o.getInt("v"))
                    "l" -> edit.putLong(k, o.getLong("v"))
                    "f" -> edit.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> edit.putString(k, o.getString("v"))
                    "ss" -> {
                        val a = o.getJSONArray("v")
                        edit.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
            edit.apply()

            // Legacy user_words store.
            val we = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE).edit().clear()
            val words = root.optJSONObject("words")
            if (words != null) for (w in words.keys()) {
                we.putFloat(w, words.getDouble(w).toFloat())
            }
            we.apply()

            // Adaptive-learning stores.
            val adaptive = root.optJSONObject("adaptive")
            if (adaptive != null) {
                for (file in ADAPTIVE_PREFS_FILES) {
                    val entries = adaptive.optJSONObject(file) ?: continue
                    val sp = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
                    val e = sp.edit().clear()
                    for (k in entries.keys()) {
                        val o = entries.getJSONObject(k)
                        when (o.getString("t")) {
                            "b" -> e.putBoolean(k, o.getBoolean("v"))
                            "i" -> e.putInt(k, o.getInt("v"))
                            "l" -> e.putLong(k, o.getLong("v"))
                            "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                            "s" -> e.putString(k, o.getString("v"))
                            "ss" -> {
                                val a = o.getJSONArray("v")
                                e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                            }
                        }
                    }
                    e.apply()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
