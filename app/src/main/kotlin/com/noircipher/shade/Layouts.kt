package com.noircipher.shade

import android.graphics.Rect

object Codes {
    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -3
    const val SYMBOLS = -4
    const val LETTERS = -5
    const val SYMBOLS2 = -6
    const val EMOJI = -7
    const val SPACE = 32
}

enum class KeyKind { CHAR, FUNC, SPACE, ENTER, SPACER }

enum class KeyOuterCorner { NONE, BOTTOM_LEFT, BOTTOM_RIGHT }

class Key(
    val label: String,
    val code: Int = 0,
    val weight: Float = 1f,
    val kind: KeyKind = KeyKind.CHAR,
    val alts: String = ""
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    var invalidateRect: Rect? = null
    var outerCorner: KeyOuterCorner = KeyOuterCorner.NONE

    fun updateInvalidateRect(pad: Int) {
        val r = invalidateRect ?: Rect().also { invalidateRect = it }
        r.set(
            (left - pad).toInt(),
            (top - pad).toInt(),
            (right + pad).toInt(),
            (bottom + pad).toInt()
        )
    }
}

object Layouts {
    val LAYOUT_IDS = listOf("qwerty", "qwertz", "azerty", "dvorak", "colemak", "custom")

    private val letterAlts = mapOf(
        'a' to "àáâäæãåāąª", 'c' to "çćčĉ", 'd' to "ðď", 'e' to "èéêëēėęě",
        'g' to "ğ", 'i' to "ìíîïīįı", 'l' to "łľ", 'n' to "ñńň", 'o' to "òóôöõøœōº",
        'r' to "řŕ", 's' to "ßśšşș", 't' to "ťţț", 'u' to "ùúûüūůű", 'y' to "ýÿ",
        'z' to "žźż"
    )

    private fun letter(c: Char, prefix: String = ""): Key =
        Key(c.toString(), c.code, alts = prefix + (letterAlts[c] ?: ""))

    private fun sym(c: Char, alts: String = ""): Key = Key(c.toString(), c.code, alts = alts)
    private fun spacer(w: Float) = Key("", 0, w, KeyKind.SPACER)
    private fun fn(code: Int, w: Float, label: String = "") = Key(label, code, w, KeyKind.FUNC)

    private fun bottomRow(left: Key): List<Key> = listOf(
        left,
        fn(Codes.EMOJI, 1f),
        sym(','),
        Key("", Codes.SPACE, 4f, KeyKind.SPACE),
        sym('.', ",?!:;'\"-@/"),
        Key("", Codes.ENTER, 1.5f, KeyKind.ENTER)
    )

    private fun rowsFor(name: String): List<String> = when (name) {
        "qwertz" -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
        "azerty" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        "dvorak" -> listOf("pyfgcrl", "aoeuidhtns", "qjkxbmwvz")
        "colemak" -> listOf("qwfpgjluy", "arstdhneio", "zxcvbkm")
        else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }

    fun parseCustom(text: String): List<List<Key>>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.size !in 2..4) return null
        val rows = lines.map { line ->
            line.split(Regex("\\s+")).mapNotNull { tok ->
                val parts = tok.split('|', limit = 2)
                val label = parts[0]
                if (label.isEmpty() || label.codePointCount(0, label.length) != 1) null
                else Key(label, label.codePointAt(0), alts = parts.getOrElse(1) { "" })
            }
        }
        if (rows.any { it.isEmpty() || it.size > 12 }) return null
        return rows
    }

    fun letters(name: String, numberRow: Boolean, custom: String): List<List<Key>> {
        val digits = "1234567890"
        val specs: List<List<Key>> = (if (name == "custom") parseCustom(custom) else null)
            ?: rowsFor(if (name == "custom") "qwerty" else name).map { r -> r.map { letter(it) } }

        val rows = mutableListOf<List<Key>>()
        if (numberRow) rows += digits.map { sym(it) }
        specs.forEachIndexed { i, keys0 ->
            val keys = if (i == 0 && !numberRow) {
                keys0.mapIndexed { idx, k ->
                    if (idx < 10 && k.kind == KeyKind.CHAR && k.alts.none { it.isDigit() })
                        Key(k.label, k.code, k.weight, k.kind, digits[idx] + k.alts)
                    else k
                }
            } else keys0
            val n = keys.size
            if (i == specs.lastIndex) {
                val w = ((10 - n) / 2f).coerceIn(1f, 2f)
                rows += listOf(fn(Codes.SHIFT, w)) + keys + fn(Codes.DELETE, w)
            } else {
                val pad = maxOf(0f, (10 - n) / 2f)
                rows += if (pad > 0f) listOf(spacer(pad)) + keys + spacer(pad) else keys
            }
        }
        rows += bottomRow(fn(Codes.SYMBOLS, 1.4f, "?123"))
        return rows
    }

    fun symbols1(): List<List<Key>> = listOf(
        "1234567890".map { sym(it) },
        listOf(
            sym('@'), sym('#'), sym('$', "€£¥¢₹"), sym('_'), sym('&', "§"),
            sym('-', "–—"), sym('+', "±"), sym('(', "[{<"), sym(')', "]}>"), sym('/')
        ),
        listOf(fn(Codes.SYMBOLS2, 1.5f, "=\\<")) +
            listOf(
                sym('*'), sym('"', "“”„«»"), sym('\'', "‘’‚‹›"), sym(':'),
                sym(';'), sym('!', "¡"), sym('?', "¿")
            ) + fn(Codes.DELETE, 1.5f),
        bottomRow(fn(Codes.LETTERS, 1.4f, "ABC"))
    )

    fun symbols2(): List<List<Key>> = listOf(
        "~`|•√π÷×¶∆".map { sym(it) },
        "€£¥¢©®™°%=".map { sym(it) },
        listOf(fn(Codes.SYMBOLS, 1.5f, "?123")) +
            "[]{}<>^".map { sym(it) } +
            fn(Codes.DELETE, 1.5f),
        bottomRow(fn(Codes.LETTERS, 1.4f, "ABC"))
    )

    fun numpad(): List<List<Key>> = listOf(
        listOf(sym('1'), sym('2'), sym('3'), sym('+', "*#")),
        listOf(sym('4'), sym('5'), sym('6'), sym('-', "/")),
        listOf(sym('7'), sym('8'), sym('9'), fn(Codes.DELETE, 1f)),
        listOf(fn(Codes.LETTERS, 1f, "ABC"), sym(','), sym('0'), sym('.', ";:()"), Key("", Codes.ENTER, 1f, KeyKind.ENTER))
    )
}
