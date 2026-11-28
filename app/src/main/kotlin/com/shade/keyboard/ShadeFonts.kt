package com.shade.keyboard

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Bundled Google fonts, resolved once per process and reused everywhere.
 *
 *  - [sans]   Google Sans (variable GRAD/opsz/wght build) — the same family
 *             Google's own keyboard (Gboard) uses. Paint surfaces apply it
 *             directly: key labels, long-press popups.
 *  - [emoji]  Noto Color Emoji (CBDT bitmap build, the one Android itself
 *             ships) — renders Unicode 18 emoji in color even when the
 *             device's system emoji font predates the codepoint.
 *  - [family] Google Sans with Noto Color Emoji as per-glyph fallback — for
 *             views that mix words and emoji (suggestion slots, emoji-panel
 *             chrome); anything neither covers falls through to the system
 *             fonts.
 *
 * Every accessor is null-safe: if a font resource is missing or unreadable the
 * caller keeps the system font instead of crashing the IME. A benign race on
 * first load can create the typeface twice; the volatile write makes the
 * winner visible to all threads.
 */
object ShadeFonts {
    @Volatile private var sans: Typeface? = null
    @Volatile private var emoji: Typeface? = null
    @Volatile private var family: Typeface? = null

    /** Google Sans for pure-text Paint surfaces (key labels, popups). */
    fun sans(context: Context): Typeface? {
        if (sans == null) {
            sans = runCatching {
                ResourcesCompat.getFont(context.applicationContext, R.font.google_sans)
            }.getOrNull()
        }
        return sans
    }

    /** Noto Color Emoji for emoji-only surfaces (grid cells, search results). */
    fun emoji(context: Context): Typeface? {
        if (emoji == null) {
            emoji = runCatching {
                ResourcesCompat.getFont(context.applicationContext, R.font.noto_color_emoji)
            }.getOrNull()
        }
        return emoji
    }

    /** Google Sans + Noto Color Emoji fallback for mixed word/emoji views. */
    fun family(context: Context): Typeface? {
        if (family == null) {
            family = runCatching {
                ResourcesCompat.getFont(context.applicationContext, R.font.keyboard_font)
            }.getOrNull()
        }
        return family
    }
}
