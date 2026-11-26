package com.shade.keyboard.slide

import android.content.Context
import com.shade.keyboard.ShadeLog
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.tanh

class SwipeModel private constructor(
    private val weights: FloatArray,
    private val bias: Float,
    private val hiddenW: FloatArray,
    private val hiddenB: FloatArray,
    private val outW: FloatArray,
    private val outB: Float,
    private val normMean: FloatArray,
    private val normStd: FloatArray,
    private val bigrams: FloatArray?,
    private val trigrams: FloatArray?,
    val blend: Float
) {
    fun penalty(features: FloatArray, scratch: FloatArray? = null): Float {
        if (features.size != FEATURE_NAMES.size) return 0f
        val logit = if (hiddenW.isEmpty()) {
            var dot = 0f
            for (i in weights.indices) dot += weights[i] * features[i]
            bias + dot
        } else {
            val f = if (normMean.size == features.size) {
                val dst = if (scratch != null && scratch.size == features.size) scratch
                          else FloatArray(features.size)
                for (i in features.indices) {
                    val z = (features[i] - normMean[i]) / normStd[i]
                    dst[i] = when {
                        z > Z_CLAMP -> Z_CLAMP
                        z < -Z_CLAMP -> -Z_CLAMP
                        else -> z
                    }
                }
                dst
            } else features
            val units = hiddenB.size
            var out = outB
            for (u in 0 until units) {
                var acc = hiddenB[u]
                val row = u * features.size
                for (i in f.indices) acc += hiddenW[row + i] * f[i]
                out += outW[u] * tanh(acc)
            }
            out
        }
        return -blend * logit
    }

    fun bigramScore(word: String): Float {
        val table = bigrams ?: return 0f
        var prev = -1
        var sum = 0f
        var n = 0
        for (raw in word) {
            val ch = raw.lowercaseChar()
            if (ch < 'a' || ch > 'z') continue
            val c = ch - 'a'
            if (prev >= 0) {
                sum += table[prev * 26 + c]
                n++
            }
            prev = c
        }
        return if (n > 0) sum / n else 0f
    }

    fun trigramScore(word: String): Float {
        val table = trigrams ?: return 0f
        var prev2 = -1
        var prev1 = -1
        var sum = 0f
        var n = 0
        for (raw in word) {
            val ch = raw.lowercaseChar()
            if (ch < 'a' || ch > 'z') continue
            val c = ch - 'a'
            if (prev2 >= 0) {
                sum += table[prev2 * 676 + prev1 * 26 + c]
                n++
            }
            prev2 = prev1
            prev1 = c
        }
        return if (n > 0) sum / n else 0f
    }

    companion object {
        val FEATURE_NAMES = listOf(
            "shape", "adh", "corner", "cdist", "vowel", "edit",
            "lenratio", "prior", "letters",
            "shape2", "adh2", "editlen", "startd", "endd", "bigram",
            "align", "trigram"
        )
        const val BIGRAM_TABLE_SIZE = 26 * 26
        const val TRIGRAM_TABLE_SIZE = 26 * 26 * 26
        private const val Z_CLAMP = 3.5f
        private const val MAX_BLEND = 10f
        const val ASSET = "swipe_model.json"

        @Volatile
        private var cached: SwipeModel? = null

        fun parse(json: String): SwipeModel? {
            return try {
                val obj = JSONObject(json)
                val version = obj.optInt("version", 0)
                val names = obj.optJSONArray("features") ?: return null
                if (names.length() != FEATURE_NAMES.size) return null
                for (i in 0 until names.length()) {
                    if (names.optString(i) != FEATURE_NAMES[i]) return null
                }
                val blend = obj.optDouble("blend", 0.0).toFloat()
                if (!blend.isFinite() || blend < 0f || blend > MAX_BLEND) return null
                val bigrams = parseBigrams(obj)
                if (version == 1) {
                    val w = obj.optJSONArray("weights") ?: return null
                    if (w.length() != FEATURE_NAMES.size) return null
                    val weights = FloatArray(FEATURE_NAMES.size)
                    for (i in weights.indices) {
                        val v = w.getDouble(i).toFloat()
                        if (!v.isFinite()) return null
                        weights[i] = v
                    }
                    val bias = obj.optDouble("bias", 0.0).toFloat()
                    return SwipeModel(weights, if (bias.isFinite()) bias else 0f,
                        FloatArray(0), FloatArray(0), FloatArray(0), 0f,
                        FloatArray(0), FloatArray(0), bigrams, parseTrigrams(obj), blend)
                }
                if (version == 2 || version == 3) {
                    val hidden = obj.optJSONObject("hidden") ?: return null
                    val output = obj.optJSONObject("output") ?: return null
                    val units = hidden.optInt("units", -1)
                    if (units <= 0 || units > 256) return null
                    val hw = hidden.optJSONArray("w") ?: return null
                    val hb = hidden.optJSONArray("b") ?: return null
                    val ow = output.optJSONArray("w") ?: return null
                    if (hw.length() != units * FEATURE_NAMES.size ||
                        hb.length() != units || ow.length() != units
                    ) return null
                    val hiddenW = FloatArray(units * FEATURE_NAMES.size)
                    for (i in hiddenW.indices) {
                        val v = hw.getDouble(i).toFloat()
                        if (!v.isFinite()) return null
                        hiddenW[i] = v
                    }
                    val hiddenB = FloatArray(units)
                    for (i in hiddenB.indices) {
                        val v = hb.getDouble(i).toFloat()
                        if (!v.isFinite()) return null
                        hiddenB[i] = v
                    }
                    val outW = FloatArray(units)
                    for (i in outW.indices) {
                        val v = ow.getDouble(i).toFloat()
                        if (!v.isFinite()) return null
                        outW[i] = v
                    }
                    val outB = output.optDouble("b", 0.0).toFloat()
                    if (!outB.isFinite()) return null
                    var normMean = FloatArray(0)
                    var normStd = FloatArray(0)
                    if (version == 3) {
                        val norm = obj.optJSONObject("norm") ?: return null
                        val meanArr = norm.optJSONArray("mean") ?: return null
                        val stdArr = norm.optJSONArray("std") ?: return null
                        if (meanArr.length() != FEATURE_NAMES.size ||
                            stdArr.length() != FEATURE_NAMES.size
                        ) return null
                        normMean = FloatArray(FEATURE_NAMES.size)
                        normStd = FloatArray(FEATURE_NAMES.size)
                        for (i in FEATURE_NAMES.indices) {
                            val mu = meanArr.getDouble(i).toFloat()
                            val sd = stdArr.getDouble(i).toFloat()
                            if (!mu.isFinite() || !sd.isFinite() || sd <= 1e-6f) return null
                            normMean[i] = mu
                            normStd[i] = sd
                        }
                    }
                    return SwipeModel(FloatArray(0), 0f, hiddenW, hiddenB, outW, outB,
                        normMean, normStd, bigrams, parseTrigrams(obj), blend)
                }
                null
            } catch (t: Throwable) {
                ShadeLog.w("SwipeModel", "model JSON rejected", t, once = true)
                null
            }
        }

        private fun parseBigrams(obj: JSONObject): FloatArray? {
            val arr = obj.optJSONArray("bigrams") ?: return null
            val table = FloatArray(BIGRAM_TABLE_SIZE)
            for (i in table.indices) {
                val v = arr.getDouble(i).toFloat()
                if (!v.isFinite()) throw JSONException("non-finite bigram score at $i")
                table[i] = v
            }
            return table
        }

        private fun parseTrigrams(obj: JSONObject): FloatArray? {
            val arr = obj.optJSONArray("trigrams") ?: return null
            val table = FloatArray(TRIGRAM_TABLE_SIZE)
            for (i in table.indices) {
                val v = arr.getDouble(i).toFloat()
                if (!v.isFinite()) throw JSONException("non-finite trigram score at $i")
                table[i] = v
            }
            return table
        }

        @Synchronized
        fun load(context: Context): SwipeModel? {
            cached?.let { return it }
            val bytes = try {
                context.applicationContext.assets.open(ASSET).use { it.readBytes() }
            } catch (t: Throwable) {
                ShadeLog.w("SwipeModel", "bundled model asset missing", t, once = true)
                return null
            }
            val parsed = parse(bytes.decodeToString())
            if (parsed == null) {
                ShadeLog.w("SwipeModel", "bundled model present but rejected by parser",
                    IllegalStateException("SwipeModel.parse returned null"), once = true)
            } else {
                cached = parsed
            }
            return parsed
        }

        fun resetForTests() {
            cached = null
        }
    }
}
