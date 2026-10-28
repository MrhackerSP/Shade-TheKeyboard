package com.noircipher.shade

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads keyboard layouts from JSON files in filesDir/layouts/.
 * Format:
 * {
 *   "name": "My Layout",
 *   "rows": [
 *     [{"label":"q","alts":"1"}, {"label":"w","alts":"2"}],
 *     ...
 *   ]
 * }
 */
object LayoutLoader {

    fun layoutsDir(ctx: Context): File = File(ctx.filesDir, "layouts").apply { mkdirs() }

    fun listLayouts(ctx: Context): List<String> {
        val builtIn = Layouts.LAYOUT_IDS
        val files = layoutsDir(ctx).listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.map { "file:" + it.name } ?: emptyList()
        return builtIn + files
    }

    fun load(ctx: Context, id: String): List<List<Key>>? {
        if (!id.startsWith("file:")) return null
        val f = File(layoutsDir(ctx), id.removePrefix("file:"))
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val rowsArr = root.getJSONArray("rows")
            val rows = ArrayList<List<Key>>(rowsArr.length())
            for (i in 0 until rowsArr.length()) {
                val rowArr = rowsArr.getJSONArray(i)
                val row = ArrayList<Key>(rowArr.length())
                for (j in 0 until rowArr.length()) {
                    val o = rowArr.getJSONObject(j)
                    val label = o.getString("label")
                    if (label.isEmpty()) continue
                    val alts = o.optString("alts", "")
                    val weight = o.optDouble("weight", 1.0).toFloat()
                    row.add(Key(label, label.codePointAt(0), weight, KeyKind.CHAR, alts))
                }
                rows.add(row)
            }
            if (rows.isEmpty()) null else rows
        } catch (_: Exception) {
            null
        }
    }

    fun save(ctx: Context, name: String, rows: List<List<Key>>): String? = try {
        val root = JSONObject()
        root.put("name", name)
        val rowsArr = JSONArray()
        for (row in rows) {
            val rowArr = JSONArray()
            for (k in row) {
                rowArr.put(JSONObject().apply {
                    put("label", k.label)
                    if (k.alts.isNotEmpty()) put("alts", k.alts)
                    if (k.weight != 1f) put("weight", k.weight.toDouble())
                })
            }
            rowsArr.put(rowArr)
        }
        root.put("rows", rowsArr)
        val f = File(layoutsDir(ctx), name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        f.writeText(root.toString(2))
        "file:${f.name}"
    } catch (_: Exception) {
        null
    }

    fun delete(ctx: Context, id: String): Boolean {
        if (!id.startsWith("file:")) return false
        return File(layoutsDir(ctx), id.removePrefix("file:")).delete()
    }
}
