package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences

/**
 * Shade's settings catalog — pure data, no Context needed to build it.
 * String resources are resolved by the adapter at bind time.
 *
 * Every pref key here is exactly what ShadeService.applyPrefs() reads.
 * The catalog also surfaces prefs that the old card-based UI never exposed
 * (multi-word suggestions, autofill chips, learning fine-tuning, key-repeat
 * timing, toolbar default state, typing insights and the two missing custom
 * colors keyfn/text).
 */
sealed class SettingsItem {
    /** Lowercase search keywords; bidirectional substring match. */
    abstract val keywords: List<String>

    data class Toggle(
        val key: String,
        val titleRes: Int,
        val subtitleRes: Int = 0,
        val default: Boolean,
        override val keywords: List<String>
    ) : SettingsItem()

    data class Slider(
        val key: String,
        val titleRes: Int,
        val min: Int,
        val max: Int,
        val step: Int,
        val default: Int,
        val format: String,
        override val keywords: List<String>
    ) : SettingsItem()

    /** A segmented control; each option is (prefValue, labelRes). */
    data class Choice(
        val key: String,
        val titleRes: Int,
        val options: List<Pair<String, Int>>,
        val default: String,
        override val keywords: List<String>
    ) : SettingsItem()

    /** A color row that opens the HSV picker sheet. */
    data class Color(
        val key: String,
        val titleRes: Int,
        val autoColor: Int,
        override val keywords: List<String>
    ) : SettingsItem()

    data class Action(
        val titleRes: Int,
        val subtitleRes: Int = 0,
        override val keywords: List<String>,
        val onClick: (Context, SharedPreferences) -> Unit
    ) : SettingsItem()

    data class Info(val textRes: Int) : SettingsItem() {
        override val keywords: List<String> = emptyList()
    }
}

object SettingsCatalog {

    data class Section(val id: Int, val titleRes: Int, val items: List<SettingsItem>)

    const val SECTION_APPEARANCE = 0
    const val SECTION_TYPING = 1
    const val SECTION_LANGUAGES = 2
    const val SECTION_BACKUP = 3
    const val SECTION_ABOUT = 4

    val sections: List<Section> = listOf(
        Section(SECTION_APPEARANCE, R.string.section_appearance, listOf(
            SettingsItem.Choice(
                key = "theme", titleRes = R.string.pref_theme,
                options = listOf(
                    "system" to R.string.theme_system,
                    "light" to R.string.theme_light,
                    "dark" to R.string.theme_dark,
                    "amoled" to R.string.theme_amoled,
                    "material" to R.string.theme_material,
                    "custom" to R.string.theme_custom
                ),
                default = "system",
                keywords = listOf("theme", "dark", "light", "amoled", "night", "mode", "custom",
                    "material", "you", "wallpaper", "monet", "dynamic")
            ),
            SettingsItem.Color(
                "color_bg", R.string.color_bg, 0xFF1C1C1E.toInt(),
                listOf("background", "bg", "canvas", "color")
            ),
            SettingsItem.Color(
                "color_key", R.string.color_key, 0xFF3C3C40.toInt(),
                listOf("key", "keys", "button", "color")
            ),
            SettingsItem.Color(
                "color_keyfn", R.string.color_keyfn, 0xFF2C2C2E.toInt(),
                listOf("function", "symbols", "fn", "shift", "color")
            ),
            SettingsItem.Color(
                "color_text", R.string.color_text, 0xFFFFFFFF.toInt(),
                listOf("text", "label", "letters", "font", "color")
            ),
            SettingsItem.Color(
                "color_accent", R.string.color_accent, 0xFF8AB4F8.toInt(),
                listOf("accent", "highlight", "suggestion", "color")
            ),
            SettingsItem.Toggle("key_borders", R.string.pref_key_borders, 0, true,
                listOf("border", "outline", "separator", "edge")),
            SettingsItem.Toggle("show_hints", R.string.pref_show_hints, 0, true,
                listOf("hint", "accent letters", "long press", "popup letters")),
            SettingsItem.Toggle("popup_preview", R.string.pref_popup_preview, 0, true,
                listOf("popup", "preview", "bubble", "key press")),
            SettingsItem.Slider("key_height", R.string.pref_key_height, 36, 70, 1, 50, "%ddp",
                listOf("height", "size", "big", "small", "tall")),
            SettingsItem.Choice(
                key = "one_handed", titleRes = R.string.pref_one_handed,
                options = listOf(
                    "off" to R.string.one_handed_off,
                    "left" to R.string.one_handed_left,
                    "right" to R.string.one_handed_right
                ),
                default = "off",
                keywords = listOf("one hand", "one-handed", "thumb", "left", "right", "reach")
            ),
            SettingsItem.Choice(
                key = "split_keyboard", titleRes = R.string.pref_split_keyboard,
                options = listOf(
                    "off" to R.string.split_off,
                    "landscape" to R.string.split_landscape,
                    "always" to R.string.split_always
                ),
                default = "off",
                keywords = listOf("split", "tablet", "landscape", "halves")
            ),
            SettingsItem.Toggle("toolbar_open_default", R.string.pref_toolbar_open, 0, false,
                listOf("toolbar", "default", "open", "strip")),
            SettingsItem.Toggle("number_row", R.string.pref_number_row, 0, false,
                listOf("number", "digit", "123", "row"))
        )),

        Section(SECTION_TYPING, R.string.section_typing, listOf(
            SettingsItem.Toggle("autocorrect", R.string.pref_autocorrect, 0, true,
                listOf("autocorrect", "typo", "spell", "correct", "fix")),
            SettingsItem.Toggle("block_offensive", R.string.pref_block_offensive,
                R.string.pref_block_offensive_sum, true,
                listOf("offensive", "profanity", "swear", "censor", "filter", "vulgar")),
            SettingsItem.Toggle("suggestions", R.string.pref_suggestions, 0, true,
                listOf("suggestion", "predict", "autocomplete", "candidate")),
            SettingsItem.Toggle("neural_predictions", R.string.pref_neural, 0, true,
                listOf("neural", "ai", "model", "onnx", "smart")),
            SettingsItem.Toggle("phrase_suggestions", R.string.pref_phrase_sugg,
                R.string.pref_phrase_sugg_sum, false,
                listOf("phrase", "multi word", "good morning")),
            SettingsItem.Toggle("smart_correction", R.string.pref_smart_correction, 0, true,
                listOf("smart", "correction", "swipe")),
            SettingsItem.Toggle("auto_caps", R.string.pref_auto_caps, 0, true,
                listOf("capital", "caps", "shift", "uppercase")),
            SettingsItem.Toggle("double_space", R.string.pref_double_space, 0, true,
                listOf("double", "space", "period", "full stop")),
            SettingsItem.Toggle("learn_words", R.string.pref_learn_words, 0, true,
                listOf("learn", "word", "dictionary")),
            SettingsItem.Toggle("autofill_chips", R.string.pref_autofill_chips,
                R.string.pref_autofill_chips_sum, true,
                listOf("autofill", "password", "chip", "address", "card")),
            SettingsItem.Toggle("glide_typing", R.string.pref_glide_typing,
                R.string.pref_glide_typing_sub, true,
                listOf("glide", "swipe", "slide", "finger")),
            SettingsItem.Toggle("trackpad_mode", R.string.pref_trackpad, 0, true,
                listOf("trackpad", "cursor", "space bar")),
            SettingsItem.Toggle("haptic", R.string.pref_haptic, 0, true,
                listOf("haptic", "vibrate", "vibration", "feedback")),
            SettingsItem.Choice(
                key = "haptic_strength", titleRes = R.string.pref_haptic_strength,
                options = listOf(
                    "light" to R.string.haptic_light,
                    "medium" to R.string.haptic_medium,
                    "strong" to R.string.haptic_strong
                ),
                default = "medium",
                keywords = listOf("haptic", "vibration", "strong", "weak", "light", "medium")
            ),
            SettingsItem.Toggle("sound", R.string.pref_sound, 0, false,
                listOf("sound", "click", "audio", "tone")),
            SettingsItem.Toggle("typing_burst", R.string.pref_typing_burst, 0, false,
                listOf("burst", "animation", "effect", "flash")),
            SettingsItem.Slider("longpress_delay", R.string.pref_longpress_delay, 200, 700, 10, 300, "%dms",
                listOf("long press", "delay", "hold")),
            SettingsItem.Slider("repeat_delay", R.string.pref_repeat_delay, 150, 700, 10, 350, "%dms",
                listOf("repeat", "delay", "backspace", "hold")),
            SettingsItem.Slider("repeat_interval", R.string.pref_repeat_interval, 20, 100, 5, 40, "%dms",
                listOf("repeat", "interval", "speed", "backspace"))
        )),

        Section(SECTION_LANGUAGES, R.string.section_languages, listOf(
            SettingsItem.Toggle("emoji_suggestions", R.string.pref_emoji_suggestions, 0, true,
                listOf("emoji", "smiley", "suggestion")),
            SettingsItem.Toggle("gif_enabled", R.string.pref_gif, 0, true,
                listOf("gif", "klipy", "sticker", "animated")),
            SettingsItem.Toggle("translator_enabled", R.string.pref_translator, 0, true,
                listOf("translate", "translation")),
            SettingsItem.Toggle("voice_input", R.string.pref_voice_input, 0, true,
                listOf("voice", "dictation", "microphone", "speech", "mic")),
            SettingsItem.Toggle("learning_adaptive", R.string.pref_learning_adaptive,
                R.string.pref_learning_adaptive_sum, true,
                listOf("recency", "frequent", "adaptive", "learning")),
            SettingsItem.Toggle("learning_appctx", R.string.pref_learning_appctx,
                R.string.pref_learning_appctx_sum, true,
                listOf("per app", "vocabulary", "context", "learning")),
            SettingsItem.Toggle("learning_suppressor", R.string.pref_learning_suppressor,
                R.string.pref_learning_suppressor_sum, true,
                listOf("undo", "revert", "suppressor", "learning")),
            SettingsItem.Toggle("learning_calibrator", R.string.pref_learning_calibrator,
                R.string.pref_learning_calibrator_sum, true,
                listOf("calibration", "confidence", "learning")),
            SettingsItem.Toggle("learning_ranker", R.string.pref_learning_ranker,
                R.string.pref_learning_ranker_sum, true,
                listOf("ranker", "training", "candidate", "learning")),
            SettingsItem.Toggle("learning_correction", R.string.pref_learning_correction,
                R.string.pref_learning_correction_sum, true,
                listOf("correction", "memory", "personal", "autocorrect", "learning")),
            SettingsItem.Toggle("learning_touch", R.string.pref_learning_touch,
                R.string.pref_learning_touch_sum, true,
                listOf("touch", "bias", "finger", "aim", "accuracy", "learning")),
            SettingsItem.Toggle("incognito", R.string.pref_incognito, 0, false,
                listOf("incognito", "private", "privacy"))
        )),

        Section(SECTION_BACKUP, R.string.section_backup, listOf(
            SettingsItem.Toggle("clipboard_history", R.string.pref_clipboard_history, 0, true,
                listOf("clipboard", "history", "copy", "paste")),
            SettingsItem.Toggle("contribute_data", R.string.pref_contribute_data, 0, false,
                listOf("contribute", "training", "export", "share")),
            SettingsItem.Info(R.string.info_backup),
            SettingsItem.Action(R.string.action_export, R.string.action_export_sub,
                listOf("backup", "export", "save")
            ) { ctx, _ -> (ctx as? SettingsActivity)?.startExport() },
            SettingsItem.Action(R.string.action_import, R.string.action_import_sub,
                listOf("backup", "import", "restore")
            ) { ctx, _ -> (ctx as? SettingsActivity)?.startImport() },
            SettingsItem.Action(R.string.action_clear_learned, R.string.action_clear_learned_sub,
                listOf("clear", "reset", "delete", "learned")
            ) { ctx, _ -> (ctx as? SettingsActivity)?.confirmClearLearned() }
        )),

        Section(SECTION_ABOUT, R.string.section_about, listOf(
            SettingsItem.Info(R.string.info_about),
            SettingsItem.Toggle("typing_insights", R.string.settings_typing, 0, false,
                listOf("insights", "stats", "analytics", "wpm")),
            SettingsItem.Action(R.string.action_typing_stats, 0,
                listOf("typing stats", "insights", "analytics")
            ) { ctx, _ -> (ctx as? SettingsActivity)?.openTypingStats() },
            SettingsItem.Toggle("auto_update_check", R.string.pref_auto_update, 0, false,
                listOf("update", "auto", "check", "version")),
            SettingsItem.Action(R.string.action_check_update, 0,
                listOf("check", "update", "version", "download")
            ) { ctx, _ -> (ctx as? SettingsActivity)?.checkForUpdates() }
        ))
    )

    fun flat(): List<SettingsItem> = sections.flatMap { it.items }

    fun section(id: Int): Section? = sections.firstOrNull { it.id == id }

    /**
     * Pure keyword search: the query matches when any keyword is a substring
     * of it or vice versa (so "glides" finds "glide", "vibrate" finds "vibration").
     * Empty query → no results (the UI shows the full catalog instead).
     */
    fun search(query: String): List<SettingsItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return flat().filter { item ->
            item.keywords.any { kw -> kw.contains(q) || q.contains(kw) }
        }
    }
}
