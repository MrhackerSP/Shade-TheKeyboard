package com.noircipher.shade

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** Backup / restore of settings and learned words as a single JSON file. */
object Backup {
    fun export(ctx: Context, uri: Uri): Boolean = try {
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
        val words = JSONObject()
        for ((w, n) in ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE).all) {
            if (n is Int) words.put(w, n)
        }
        val root = JSONObject().put("version", 1).put("prefs", p).put("words", words)
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) } != null
    } catch (_: Exception) {
        false
    }

    fun import(ctx: Context, uri: Uri): Boolean = try {
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return false
        val root = JSONObject(text)
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
        val we = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE).edit().clear()
        val words = root.optJSONObject("words")
        if (words != null) for (w in words.keys()) we.putInt(w, words.getInt(w))
        we.apply()
        true
    } catch (_: Exception) {
        false
    }
}
