package com.shade.keyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * Shade's settings catalog — pure data, no Context needed to build it.
 * String resources are resolved by the adapter at bind time.
 *
 * Every pref key here is exactly what ShadeService.applyPrefs() reads.
 *
 * The catalog is organised the way Gboard organises its settings: a home
 * screen of grouped CATEGORIES ([Page]), each opening a page made of small
 * labelled sub-sections ([Section]) of rows ([SettingsItem]). Search and the
 * tests still see one flat list ([flat]).
 */
sealed class SettingsItem {
    /** Lowercase search keywords; bidirectional substring match. */
    abstract val keywords: List<String>

    data class Toggle(
        val key: String,
        val titleRes: Int,
        val subtitleRes: Int = 0,
        val default: Boolean,
        override val keywords: List<String>,
        /** A page's master switch, drawn as a full-width pill ("Use voice typing"). */
        val hero: Boolean = false
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

    /** A labelled group of rows on a page. [titleRes] == 0 means no label. */
    data class Section(val id: Int, val titleRes: Int, val items: List<SettingsItem>)

    /**
     * One category of the settings home screen (Gboard: "Preferences",
     * "Glide typing", …). [largeTitle] pages open with a big heading that
     * collapses into the top bar as you scroll; the others show the small
     * top-bar title straight away.
     */
    data class Page(
        val id: Int,
        val titleRes: Int,
        val iconRes: Int,
        val largeTitle: Boolean,
        val sections: List<Section>
    )

    // ── Page ids ─────────────────────────────────────────────────────────
    const val PAGE_LANGUAGES = 0
    const val PAGE_PREFERENCES = 1
    const val PAGE_THEME = 2
    const val PAGE_CORRECTIONS = 3
    const val PAGE_GLIDE = 4
    const val PAGE_VOICE = 5
    const val PAGE_EMOJI = 6
    const val PAGE_CLIPBOARD = 7
    const val PAGE_DICTIONARY = 8
    const val PAGE_PRIVACY = 9
    const val PAGE_ABOUT = 10
    const val PAGE_ADVANCED = 11
    const val PAGE_UPDATES = 12

    // ── Section ids (unique; display order is the order in [pages]) ──────
    const val SECTION_LANGUAGES = 0
    const val SECTION_KEYS = 1
    const val SECTION_LAYOUT = 2
    const val SECTION_SHORTCUTS = 3
    const val SECTION_KEY_TAP = 4
    const val SECTION_APPEARANCE = 5
    const val SECTION_COLORS = 6
    const val SECTION_BACKGROUND = 7
    const val SECTION_CORRECTIONS = 8
    const val SECTION_SUGGESTIONS = 9
    const val SECTION_GESTURES = 10
    const val SECTION_VOICE_MAIN = 11
    const val SECTION_VOICE = 12
    const val SECTION_EMOJI = 13
    const val SECTION_GIF = 14
    const val SECTION_CLIPBOARD = 15
    const val SECTION_DICTIONARY = 16
    const val SECTION_PRIVACY = 17
    const val SECTION_SHARING = 18
    const val SECTION_ABOUT = 19
    const val SECTION_UPDATES = 20
    const val SECTION_FEATURES = 21
    const val SECTION_ENGINE = 22
    const val SECTION_BACKUP = 23

    /** The settings home, in Gboard's grouping: each inner list is one card group. */
    val homeGroups: List<List<Int>> = listOf(
        listOf(PAGE_LANGUAGES, PAGE_PREFERENCES, PAGE_THEME),
        listOf(PAGE_CORRECTIONS, PAGE_GLIDE, PAGE_VOICE, PAGE_EMOJI),
        listOf(PAGE_CLIPBOARD, PAGE_DICTIONARY),
        listOf(PAGE_PRIVACY, PAGE_ABOUT, PAGE_ADVANCED, PAGE_UPDATES)
    )

    val pages: List<Page> = listOf(

        // ── Languages ────────────────────────────────────────────────────
        Page(PAGE_LANGUAGES, R.string.page_languages, R.drawable.ic_cat_language, false, listOf(
            Section(SECTION_LANGUAGES, R.string.label_languages, listOf(
                SettingsItem.Action(R.string.action_languages, R.string.action_languages_sub,
                    listOf("language", "languages", "dictionary", "english", "hindi", "hinglish", "locale")
                ) { ctx, prefs -> (ctx as? SettingsActivity)?.showLanguagesDialog(prefs) },
                SettingsItem.Choice(
                    key = "layout", titleRes = R.string.pref_layout,
                    options = listOf(
                        "qwerty" to R.string.layout_qwerty,
                        "qwertz" to R.string.layout_qwertz,
                        "azerty" to R.string.layout_azerty,
                        "dvorak" to R.string.layout_dvorak,
                        "colemak" to R.string.layout_colemak
                    ),
                    default = "qwerty",
                    keywords = listOf("layout", "qwerty", "qwertz", "azerty", "dvorak", "colemak", "arrangement")
                )
            ))
        )),

        // ── Preferences ──────────────────────────────────────────────────
        Page(PAGE_PREFERENCES, R.string.page_preferences, R.drawable.ic_cat_preferences, true, listOf(
            Section(SECTION_KEYS, R.string.label_keys, listOf(
                SettingsItem.Toggle("number_row", R.string.pref_number_row, 0, false,
                    listOf("number", "digit", "123", "row")),
                SettingsItem.Toggle("show_hints", R.string.pref_show_hints, 0, true,
                    listOf("hint", "accent letters", "long press", "popup letters"))
            )),
            Section(SECTION_LAYOUT, R.string.label_layout, listOf(
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
                SettingsItem.Toggle("fullscreen_landscape", R.string.pref_fullscreen_landscape, 0, true,
                    listOf("fullscreen", "full screen", "extract", "landscape editor")),
                SettingsItem.Toggle("toolbar_open_default", R.string.pref_toolbar_open, 0, false,
                    listOf("toolbar", "default", "open", "strip"))
            )),
            Section(SECTION_SHORTCUTS, R.string.label_shortcuts, listOf(
                SettingsItem.Toggle("double_space", R.string.pref_double_space, 0, true,
                    listOf("double", "space", "period", "full stop"))
            )),
            Section(SECTION_KEY_TAP, R.string.label_key_tap, listOf(
                SettingsItem.Toggle("sound", R.string.pref_sound, 0, false,
                    listOf("sound", "click", "audio", "tone")),
                SettingsItem.Toggle("haptic", R.string.pref_haptic, 0, true,
                    listOf("haptic", "vibrate", "vibration", "feedback")),
                // Vibration strength: 0-100 slider. The same value is a duration
                // in ms on legacy devices and a strength percentage on
                // haptic-primitive hardware; the label switches accordingly
                // (SliderVH renders it through the engine).
                SettingsItem.Slider(
                    key = "vibration_duration", titleRes = R.string.pref_vibration_duration,
                    min = 0, max = 100, step = 1, default = 50, format = "%d",
                    keywords = listOf("haptic", "vibration", "strength", "duration", "strong", "weak")
                ),
                SettingsItem.Toggle("popup_preview", R.string.pref_popup_preview, 0, true,
                    listOf("popup", "preview", "bubble", "key press")),
                SettingsItem.Slider("longpress_delay", R.string.pref_longpress_delay, 200, 700, 10, 300, "%dms",
                    listOf("long press", "delay", "hold")),
                SettingsItem.Slider("repeat_delay", R.string.pref_repeat_delay, 150, 700, 10, 350, "%dms",
                    listOf("repeat", "delay", "backspace", "hold")),
                SettingsItem.Slider("repeat_interval", R.string.pref_repeat_interval, 20, 100, 5, 40, "%dms",
                    listOf("repeat", "interval", "speed", "backspace"))
            ))
        )),

        // ── Theme ────────────────────────────────────────────────────────
        Page(PAGE_THEME, R.string.page_theme, R.drawable.ic_cat_theme, true, listOf(
            Section(SECTION_APPEARANCE, R.string.label_style, listOf(
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
                SettingsItem.Choice(
                    key = "design", titleRes = R.string.pref_design,
                    options = listOf(
                        "" to R.string.design_default,
                        "classic" to R.string.design_classic,
                        "glass" to R.string.design_glass,
                        "neon" to R.string.design_neon,
                        "neumorph" to R.string.design_neumorph,
                        "rounded" to R.string.design_rounded,
                        "dot" to R.string.design_dot
                    ),
                    default = "",
                    keywords = listOf("design", "style", "preset", "skin", "classic", "glass",
                        "neon", "neumorphism", "rounded", "dot", "look")
                ),
                SettingsItem.Toggle("key_borders", R.string.pref_key_borders, 0, true,
                    listOf("border", "outline", "separator", "edge")),
                SettingsItem.Toggle("typing_burst", R.string.pref_typing_burst, 0, false,
                    listOf("burst", "animation", "effect", "flash"))
            )),
            Section(SECTION_COLORS, R.string.section_colors, listOf(
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
                SettingsItem.Slider("key_opacity", R.string.pref_key_opacity, 20, 100, 5, 100, "%d%%",
                    listOf("key opacity", "opacity", "translucent", "transparent", "keys", "glass")),
                SettingsItem.Slider("bg_transparency", R.string.pref_bg_transparency, 0, 100, 5, 0, "%d%%",
                    listOf("background transparency", "see through", "seethrough", "transparent", "translucent", "opacity")),
                SettingsItem.Action(R.string.action_reset_colors, R.string.action_reset_colors_sub,
                    listOf("reset", "restore", "colors", "background", "design", "default")
                ) { ctx, prefs -> (ctx as? SettingsActivity)?.resetColorsAndBackground(prefs) }
            )),
            Section(SECTION_BACKGROUND, R.string.section_background, listOf(
                SettingsItem.Action(R.string.action_bg_pick, R.string.action_bg_pick_sub,
                    listOf("background image", "photo", "wallpaper", "picture", "gallery", "image")
                ) { ctx, _ -> (ctx as? SettingsActivity)?.pickBackgroundImage() },
                SettingsItem.Action(R.string.action_bg_remove, R.string.action_bg_remove_sub,
                    listOf("background image", "remove", "delete", "photo", "picture")
                ) { ctx, _ -> (ctx as? SettingsActivity)?.removeBackgroundImage() },
                SettingsItem.Choice(
                    key = "photo_fit", titleRes = R.string.pref_photo_fit,
                    options = listOf(
                        "cover" to R.string.fit_cover,
                        "fit" to R.string.fit_fit,
                        "stretch" to R.string.fit_stretch
                    ),
                    default = "cover",
                    keywords = listOf("photo placement", "fit", "cover", "stretch", "fill", "crop", "image")
                ),
                SettingsItem.Slider("photo_zoom", R.string.pref_photo_zoom, 50, 300, 5, 100, "%d%%",
                    listOf("photo zoom", "zoom", "scale", "image", "magnify")),
                SettingsItem.Slider("photo_width", R.string.pref_photo_width, 50, 200, 5, 100, "%d%%",
                    listOf("photo width", "width", "image", "resize")),
                SettingsItem.Slider("photo_height", R.string.pref_photo_height, 50, 200, 5, 100, "%d%%",
                    listOf("photo height", "height", "image", "resize")),
                SettingsItem.Slider("photo_x", R.string.pref_photo_x, -100, 100, 5, 0, "%d%%",
                    listOf("photo shift", "pan", "move", "horizontal", "image", "position")),
                SettingsItem.Slider("photo_y", R.string.pref_photo_y, -100, 100, 5, 0, "%d%%",
                    listOf("photo shift", "pan", "move", "vertical", "image", "position")),
                SettingsItem.Slider("bg_dim", R.string.pref_bg_dim, 0, 90, 5, 35, "%d%%",
                    listOf("dim", "dimming", "darken", "readability", "photo", "image")),
                SettingsItem.Slider("bg_blur", R.string.pref_bg_blur, 0, 24, 1, 0, "%ddp",
                    listOf("blur", "frosted", "photo", "image", "soft"))
            ))
        )),

        // ── Corrections and suggestions ──────────────────────────────────
        Page(PAGE_CORRECTIONS, R.string.page_corrections, R.drawable.ic_cat_corrections, true, listOf(
            Section(SECTION_CORRECTIONS, R.string.label_auto_corrections, listOf(
                SettingsItem.Toggle("autocorrect", R.string.pref_autocorrect, 0, true,
                    listOf("autocorrect", "typo", "spell", "correct", "fix")),
                SettingsItem.Toggle("smart_correction", R.string.pref_smart_correction, 0, true,
                    listOf("smart", "correction", "swipe")),
                SettingsItem.Toggle("auto_caps", R.string.pref_auto_caps, 0, true,
                    listOf("capital", "caps", "shift", "uppercase"))
            )),
            Section(SECTION_SUGGESTIONS, R.string.label_suggestions, listOf(
                SettingsItem.Toggle("block_offensive", R.string.pref_block_offensive,
                    R.string.pref_block_offensive_sum, true,
                    listOf("offensive", "profanity", "swear", "censor", "filter", "vulgar")),
                SettingsItem.Toggle("suggestions", R.string.pref_suggestions, 0, true,
                    listOf("suggestion", "predict", "autocomplete", "candidate")),
                SettingsItem.Toggle("neural_predictions", R.string.pref_neural, 0, true,
                    listOf("neural", "ai", "model", "smart")),
                SettingsItem.Toggle("phrase_suggestions", R.string.pref_phrase_sugg,
                    R.string.pref_phrase_sugg_sum, false,
                    listOf("phrase", "multi word", "good morning")),
                SettingsItem.Toggle("autofill_chips", R.string.pref_autofill_chips,
                    R.string.pref_autofill_chips_sum, true,
                    listOf("autofill", "password", "chip", "address", "card"))
            ))
        )),

        // ── Glide typing ─────────────────────────────────────────────────
        Page(PAGE_GLIDE, R.string.page_glide, R.drawable.ic_cat_glide, true, listOf(
            Section(SECTION_GESTURES, 0, listOf(
                SettingsItem.Toggle("glide_typing", R.string.pref_glide_typing,
                    R.string.pref_glide_typing_sub, true,
                    listOf("glide", "swipe", "slide", "finger")),
                SettingsItem.Toggle("trackpad_mode", R.string.pref_trackpad, 0, true,
                    listOf("trackpad", "cursor", "space bar"))
            ))
        )),

        // ── Voice typing ─────────────────────────────────────────────────
        Page(PAGE_VOICE, R.string.page_voice, R.drawable.ic_cat_voice, true, listOf(
            Section(SECTION_VOICE_MAIN, 0, listOf(
                SettingsItem.Toggle("voice_input", R.string.pref_voice_input, 0, true,
                    listOf("voice", "dictation", "microphone", "speech", "mic"), hero = true)
            )),
            Section(SECTION_VOICE, R.string.label_speech, listOf(
                SettingsItem.Toggle("voice_on_device", R.string.pref_voice_on_device,
                    R.string.pref_voice_on_device_sum, true,
                    listOf("voice", "offline", "on-device", "speech", "recognition")),
                SettingsItem.Toggle("strip_voice_button", R.string.pref_strip_voice_button,
                    R.string.pref_strip_voice_button_sum, true,
                    listOf("voice", "mic", "strip", "toolbar", "button", "suggestion"))
            ))
        )),

        // ── Emojis and GIFs ──────────────────────────────────────────────
        Page(PAGE_EMOJI, R.string.page_emoji, R.drawable.ic_cat_emoji, false, listOf(
            Section(SECTION_EMOJI, R.string.label_suggestion_strip, listOf(
                SettingsItem.Toggle("emoji_suggestions", R.string.pref_emoji_suggestions, 0, true,
                    listOf("emoji", "smiley", "suggestion"))
            )),
            Section(SECTION_GIF, R.string.label_gifs, listOf(
                SettingsItem.Toggle("gif_enabled", R.string.pref_gif, 0, true,
                    listOf("gif", "sticker", "animated")),
                SettingsItem.Action(R.string.pref_gif_api_key, R.string.pref_gif_api_key_sum,
                    listOf("gif", "api", "key", "token", "sticker")) { ctx, prefs ->
                    (ctx as? SettingsActivity)?.showGifApiKeyDialog(prefs)
                }
            ))
        )),

        // ── Clipboard ────────────────────────────────────────────────────
        Page(PAGE_CLIPBOARD, R.string.page_clipboard, R.drawable.ic_cat_clipboard, false, listOf(
            Section(SECTION_CLIPBOARD, 0, listOf(
                SettingsItem.Toggle("clipboard_history", R.string.pref_clipboard_history, 0, true,
                    listOf("clipboard", "history", "copy", "paste"))
            ))
        )),

        // ── Dictionary ───────────────────────────────────────────────────
        Page(PAGE_DICTIONARY, R.string.page_dictionary, R.drawable.ic_cat_dictionary, false, listOf(
            Section(SECTION_DICTIONARY, 0, listOf(
                SettingsItem.Action(R.string.action_import_words, R.string.action_import_words_sub,
                    listOf("import", "words", "dictionary", "migrate", "switch", "learned")
                ) { ctx, _ -> (ctx as? SettingsActivity)?.startWordImport() },
                SettingsItem.Action(R.string.action_clear_learned, R.string.action_clear_learned_sub,
                    listOf("clear", "reset", "delete", "learned")
                ) { ctx, _ -> (ctx as? SettingsActivity)?.confirmClearLearned() }
            ))
        )),

        // ── Privacy ──────────────────────────────────────────────────────
        Page(PAGE_PRIVACY, R.string.page_privacy, R.drawable.ic_cat_privacy, true, listOf(
            Section(SECTION_PRIVACY, R.string.label_your_data, listOf(
                SettingsItem.Toggle("learn_words", R.string.pref_learn_words, 0, true,
                    listOf("learn", "word", "dictionary", "personalise", "personalize")),
                SettingsItem.Toggle("incognito", R.string.pref_incognito, 0, false,
                    listOf("incognito", "private", "privacy")),
                SettingsItem.Toggle("typing_insights", R.string.settings_typing, 0, false,
                    listOf("insights", "stats", "analytics", "wpm")),
                SettingsItem.Action(R.string.action_typing_stats, 0,
                    listOf("typing stats", "insights", "analytics")
                ) { ctx, _ -> (ctx as? SettingsActivity)?.openTypingStats() }
            )),
            Section(SECTION_SHARING, R.string.label_sharing, listOf(
                SettingsItem.Toggle("contribute_data", R.string.pref_contribute_data, 0, false,
                    listOf("contribute", "training", "export", "share"))
            ))
        )),

        // ── About ────────────────────────────────────────────────────────
        Page(PAGE_ABOUT, R.string.page_about, R.drawable.ic_cat_about, true, listOf(
            Section(SECTION_ABOUT, 0, listOf(
                SettingsItem.Info(R.string.info_about)
            ))
        )),

        // ── Advanced settings ────────────────────────────────────────────
        Page(PAGE_ADVANCED, R.string.page_advanced, R.drawable.ic_cat_advanced, false, listOf(
            Section(SECTION_FEATURES, R.string.label_features, listOf(
                SettingsItem.Toggle("translator_enabled", R.string.pref_translator, 0, true,
                    listOf("translate", "translation"))
            )),
            Section(SECTION_ENGINE, R.string.section_learning_engine, listOf(
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
                    listOf("touch", "bias", "finger", "aim", "accuracy", "learning"))
            )),
            Section(SECTION_BACKUP, R.string.section_backup, listOf(
                SettingsItem.Info(R.string.info_backup),
                SettingsItem.Action(R.string.action_export, R.string.action_export_sub,
                    listOf("backup", "export", "save")
                ) { ctx, _ -> (ctx as? SettingsActivity)?.startExport() },
                SettingsItem.Action(R.string.action_import, R.string.action_import_sub,
                    listOf("backup", "import", "restore")
                ) { ctx, _ -> (ctx as? SettingsActivity)?.startImport() }
            ))
        )),

        // ── Shade updates ────────────────────────────────────────────────
        Page(PAGE_UPDATES, R.string.page_updates, R.drawable.ic_cat_updates, false, listOf(
            Section(SECTION_UPDATES, R.string.label_actions, listOf(
                SettingsItem.Action(R.string.action_check_update, 0,
                    listOf("check", "update", "version", "download")
                ) { ctx, _ -> (ctx as? SettingsActivity)?.checkForUpdates() },
                SettingsItem.Toggle("auto_update_check", R.string.pref_auto_update, 0, false,
                    listOf("update", "auto", "check", "version"))
            ))
        ))
    )

    /** Every section in display order (page by page). */
    val sections: List<Section> = pages.flatMap { it.sections }

    fun page(id: Int): Page? = pages.firstOrNull { it.id == id }

    fun flat(): List<SettingsItem> = sections.flatMap { it.items }

    fun section(id: Int): Section? = sections.firstOrNull { it.id == id }

    /**
     * Keyword search. Empty query → no results (the UI shows the full catalog).
     *
     * A keyword hits when it contains the query, or — for keywords of 4+
     * letters, so "ai"/"bg"/"fn" cannot match half the alphabet — when the
     * query contains it ("glides" finds "glide"). A one-letter query only
     * matches keywords that START with it; otherwise typing "s" returned most
     * of the catalog. When [titleOf] is supplied (the UI passes
     * Context.getString) the visible title is searched too, so a row is always
     * findable by the words printed on it.
     */
    fun search(query: String, titleOf: ((Int) -> String)? = null): List<SettingsItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return flat().filter { item ->
            item.keywords.any { kw -> keywordHit(kw, q) } || titleHit(item, q, titleOf)
        }
    }

    private fun keywordHit(kw: String, q: String): Boolean = when {
        q.length == 1 -> kw.startsWith(q)
        kw.contains(q) -> true
        else -> kw.length >= 4 && q.contains(kw)
    }

    private fun titleHit(item: SettingsItem, q: String, titleOf: ((Int) -> String)?): Boolean {
        if (titleOf == null || q.length < 2) return false
        val res = when (item) {
            is SettingsItem.Toggle -> item.titleRes
            is SettingsItem.Slider -> item.titleRes
            is SettingsItem.Choice -> item.titleRes
            is SettingsItem.Color -> item.titleRes
            is SettingsItem.Action -> item.titleRes
            is SettingsItem.Info -> return false
        }
        return titleOf(res).lowercase().contains(q)
    }
}
