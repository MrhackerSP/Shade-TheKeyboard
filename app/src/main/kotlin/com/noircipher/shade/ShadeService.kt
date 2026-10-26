package com.noircipher.shade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.preference.PreferenceManager
import com.noircipher.shade.ai.NeuralPredictionEngine
import com.noircipher.shade.emoji.EmojiPredictionEngine
import com.noircipher.shade.engine.CandidateRanker
import com.noircipher.shade.engine.EmojiPredictor
import com.noircipher.shade.engine.LanguageModelEngine
import com.noircipher.shade.motion.ShadeMotion
import com.noircipher.shade.slide.SlideDecoder
import com.noircipher.shade.voice.VoiceInputController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

class ShadeService : InputMethodService(), KeyboardView.Listener {

    private enum class Mode { LETTERS, SYM1, SYM2, NUMPAD }
    private enum class Panel { NONE, EMOJI, CLIP }

    private class UndoEntry(
        val charsCommitted: Int,
        val restore: String
    )

    private val toolDefs = listOf(
        "clip" to "📋", "emoji" to "😀", "numpad" to "123", "left" to "◀", "right" to "▶",
        "selectall" to "All", "copy" to "Copy", "cut" to "Cut", "paste" to "Paste",
        "undo" to "Undo", "incognito" to "🕶", "onehand" to "1-hand",
        "voice" to "🎤", "settings" to "⚙"
    )
    private val toolIcons = mapOf(
        "clip" to R.drawable.ic_tb_clip, "emoji" to R.drawable.ic_tb_emoji,
        "numpad" to R.drawable.ic_tb_numpad, "left" to R.drawable.ic_tb_left,
        "right" to R.drawable.ic_tb_right, "selectall" to R.drawable.ic_tb_selectall,
        "copy" to R.drawable.ic_tb_copy, "cut" to R.drawable.ic_tb_cut,
        "paste" to R.drawable.ic_tb_paste, "undo" to R.drawable.ic_tb_undo,
        "incognito" to R.drawable.ic_tb_incognito, "onehand" to R.drawable.ic_tb_onehand,
        "voice" to R.drawable.ic_tb_mic, "settings" to R.drawable.ic_tb_settings
    )
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private lateinit var dict: WordDictionary
    private lateinit var clips: ClipboardStore
    private lateinit var cm: ClipboardManager
    private lateinit var root: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var toggle: TextView
    private lateinit var slotFrame: FrameLayout
    private lateinit var slotRow: LinearLayout
    private lateinit var slotOldRow: LinearLayout
    private lateinit var slots: List<TextView>
    private lateinit var slotOlds: List<TextView>
    private lateinit var toolScroll: HorizontalScrollView
    private lateinit var toolRow: LinearLayout
    private lateinit var kb: KeyboardView
    private lateinit var emoji: EmojiPanel
    private lateinit var clipPanel: ClipboardPanel

    private var kbTheme: KbTheme = Themes.LIGHT
    private var mode = Mode.LETTERS
    private var panel = Panel.NONE
    private var shiftState = 0
    private var autoShifted = false
    private var lastShiftTime = 0L
    private var lastSpaceTime = 0L
    private var justAutoSpaced = false
    private val composing = StringBuilder()
    private var canCompose = true
    private var suggestionsAllowed = true
    private var suggestionsOn = true
    private var neuralOn = true
    private var neural: LanguageModelEngine? = null
    private var neuralLocale: String? = null
    private var emojiPredictor: EmojiPredictor = EmojiPredictionEngine.builtIn()
    private var voice: VoiceInputController? = null
    private val slideDecoder: SlideDecoder by lazy { SlideDecoder(dict) }
    private var learnOn = true
    private var autoCaps = true
    private var doubleSpace = true
    private var autocorrectOn = true
    private var smartCorrectionOn = true
    private var emojiSuggest = true
    private var incognito = false
    private var numberRow = false
    private var toolbarOpen = false
    private var layoutName = "qwerty"
    private var candidates: List<String> = emptyList()
    private var info: EditorInfo? = null
    private var bgBmp: Bitmap? = null
    private var bgStamp = -1L
    private var lastAppliedInputType = -1

    private var pendingDeleteWords = 0
    private var deletePreviewActive = false

    private val touchTrace = TouchTrace(16)
    private var lastWordTouches: List<FloatArray> = emptyList()

    private val undoStack = ArrayDeque<UndoEntry>(10)

    private val tail = StringBuilder(16)
    private var cursorPos = -1
    private var selectionEnd = -1
    private var tailValid = false

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var suggestJob: Job? = null
    private var correctionJob: Job? = null
    private var slotAnimator: ValueAnimator? = null
    private var panelSpring: SpringAnimation? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    private fun dictIds(): String =
        (prefs.getStringSet("dictionaries", setOf("en")) ?: setOf("en"))
            .ifEmpty { setOf("en") }.sorted().joinToString(",")

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        dict = WordDictionary(this)
        voice = VoiceInputController(this) { text -> commitVoiceText(text) }
        dict.bigramModel = BigramModel(this)
        clips = ClipboardStore(this)
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try { cm.addPrimaryClipChangedListener(clipListener) } catch (_: Exception) {}

        val locale = dictIds()
        scope.launch(Dispatchers.Default) {
            dict.configure(locale)
            emojiPredictor = EmojiPredictionEngine.load(this@ShadeService, locale)
            reloadNeuralEngine(locale)
        }
    }

    override fun onDestroy() {
        try { cm.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        dict.bigramModel?.save()
        suggestJob?.cancel()
        correctionJob?.cancel()
        slotAnimator?.cancel()
        panelSpring?.cancel()
        voice?.cancel()
        voice = null
        serviceJob.cancel()

        if (this::kb.isInitialized) kb.release()
        bgBmp = null
        candidates = emptyList()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
        info = null
        pendingDeleteWords = 0
        deletePreviewActive = false
        touchTrace.clear()
        lastWordTouches = emptyList()

        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = buildInputView()

    private fun buildInputView(): View {
        kb = KeyboardView(this).also { it.listener = this }
        kb.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        toggle = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            setOnClickListener {
                toolbarOpen = if (toolbarOpen) false else true
                animateToggle()
                refreshStrip()
            }
        }

        slots = List(3) { i ->
            TextView(this).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                textSize = 19f
                tag = i
                setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
                setOnClickListener { pickSuggestion(it.tag as Int) }
            }
        }
        slotOlds = List(3) {
            TextView(this).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                textSize = 19f
                setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
                alpha = 0f
                isClickable = false
                isFocusable = false
            }
        }
        slotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (tv in slots) addView(tv, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        slotOldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = false
            isFocusable = false
            for (tv in slotOlds) addView(tv, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        slotFrame = FrameLayout(this).apply {
            addView(slotOldRow, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(slotRow, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolRow, FrameLayout.LayoutParams(WRAP, MATCH))
        }
        val frame = FrameLayout(this).apply {
            addView(slotFrame, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(toolScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(toggle, LinearLayout.LayoutParams(dp(40f).toInt(), MATCH))
            addView(frame, LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        emoji = EmojiPanel(
            this,
            onEmoji = { text ->
                endWord()
                commitSmart(text, text.length)
            },
            onBack = { transitionToPanel(Panel.NONE) },
            onDelete = { backspace() }
        )
        clipPanel = ClipboardPanel(
            this, clips,
            onPaste = { text ->
                endWord()
                commitSmart(text, text.length)
                transitionToPanel(Panel.NONE)
                afterText()
            },
            onBack = { transitionToPanel(Panel.NONE) },
            onDelete = { backspace() }
        )
        emoji.visibility = View.GONE
        clipPanel.visibility = View.GONE

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(MATCH, dp(52f).toInt()))
            addView(kb, LinearLayout.LayoutParams(MATCH, dp(200f).toInt()))
            addView(emoji, LinearLayout.LayoutParams(MATCH, dp(200f).toInt()))
            addView(clipPanel, LinearLayout.LayoutParams(MATCH, dp(200f).toInt()))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, b)
            insets
        }
        registerInsetAnimationCallback()

        applyPrefs()

        if (kb.rows.isEmpty()) {
            kb.setRows(
                Layouts.letters(
                    layoutName, numberRow,
                    prefs.getString("custom_layout", "") ?: ""
                )
            )
            kb.relayout()
        }

        return root
    }

    private fun registerInsetAnimationCallback() {
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(
                DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    var imeVisible = false
                    for (anim in runningAnimations) {
                        if (anim.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            imeVisible = true
                            break
                        }
                    }
                    if (imeVisible) {
                        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        kb.translationY = imeBottom.toFloat()
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        kb.translationY = 0f
                    }
                }
            }
        )
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        info = editorInfo
        dict.reloadUser()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
        cursorPos = -1
        selectionEnd = -1
        justAutoSpaced = false
        lastSpaceTime = 0L
        shiftState = 0
        autoShifted = false
        toolbarOpen = prefs.getBoolean("toolbar_open_default", false)
        pendingDeleteWords = 0
        deletePreviewActive = false
        touchTrace.clear()
        lastWordTouches = emptyList()

        mode = Mode.LETTERS

        val type = editorInfo.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION

        val passwordField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS

        val searchField = variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
            (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        val isTextField = cls == InputType.TYPE_CLASS_TEXT

        canCompose = isTextField && (passwordField == false) && (searchField == false)
        suggestionsAllowed = isTextField && (passwordField == false)

        applyPrefs()

        val m = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> Mode.NUMPAD
            InputType.TYPE_CLASS_DATETIME -> Mode.SYM1
            else -> Mode.LETTERS
        }

        val inputTypeChanged = type != lastAppliedInputType
        lastAppliedInputType = type
        if (inputTypeChanged || mode != m || kb.rows.isEmpty()) {
            applyMode(m, force = kb.rows.isEmpty())
        }

        transitionToPanel(Panel.NONE, animate = false)

        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        kb.enterLabel = if (noEnterAction) "" else when (action) {
            EditorInfo.IME_ACTION_GO -> getString(R.string.go)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.search)
            EditorInfo.IME_ACTION_SEND -> getString(R.string.send)
            EditorInfo.IME_ACTION_NEXT -> getString(R.string.next)
            EditorInfo.IME_ACTION_DONE -> getString(R.string.done)
            else -> ""
        }
        kb.enterIsPrimary = noEnterAction == false &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        updateShiftFromCaps()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        suggestJob?.cancel()
        correctionJob?.cancel()
        info = null
        candidates = emptyList()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
        cursorPos = -1
        selectionEnd = -1
        mode = Mode.LETTERS
        lastAppliedInputType = -1
        pendingDeleteWords = 0
        deletePreviewActive = false
        touchTrace.clear()
        lastWordTouches = emptyList()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentInputConnection?.finishComposingText()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
        pendingDeleteWords = 0
        deletePreviewActive = false

        if (this::dict.isInitialized) {
            dict.bigramModel?.save()
        }
        scope.launch(Dispatchers.IO) {
            try {
                TrainingExport.autoExport(applicationContext)
            } catch (_: Exception) {
                // Silent. Auto-export is best-effort.
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.post { applyOneHandedToStrip() }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd
        )
        if (deletePreviewActive) return
        if (newSelStart != cursorPos || newSelEnd != selectionEnd) {
            cursorPos = newSelStart
            selectionEnd = newSelEnd
            tailValid = false
        }
        if (composing.isNotEmpty() &&
            (candidatesStart == -1 || newSelStart < candidatesStart || newSelStart > candidatesEnd)
        ) {
            composing.clear()
            currentInputConnection?.finishComposingText()
            updateSuggestions()
        }
    }

    // ------------------------------------------------------------------ prefs / theme

    private fun applyPrefs() {
        if (this::kb.isInitialized == false) return
        kbTheme = Themes.resolve(this, prefs)
        numberRow = prefs.getBoolean("number_row", false)
        suggestionsOn = prefs.getBoolean("suggestions", true)
        neuralOn = prefs.getBoolean("neural_predictions", true)
        neural?.enabled = neuralOn
        learnOn = prefs.getBoolean("learn_words", true)
        autoCaps = prefs.getBoolean("auto_caps", true)
        doubleSpace = prefs.getBoolean("double_space", true)
        autocorrectOn = prefs.getBoolean("autocorrect", true)
        smartCorrectionOn = prefs.getBoolean("smart_correction", true)
        emojiSuggest = prefs.getBoolean("emoji_suggestions", true)
        incognito = prefs.getBoolean("incognito", false)
        layoutName = prefs.getString("layout", "qwerty") ?: "qwerty"
        kb.trackpadEnabled = prefs.getBoolean("trackpad_mode", true)
        kb.typingBurstEnabled = prefs.getBoolean("typing_burst", false)
        kb.longPressMs = 300L
        kb.repeatDelayMs = 350L
        kb.repeatIntervalMs = 40L

        val locale = dictIds()
        scope.launch(Dispatchers.Default) {
            dict.configure(locale)
            emojiPredictor = EmojiPredictionEngine.load(this@ShadeService, locale)
            reloadNeuralEngine(locale)
        }

        val keyH = prefs.getInt("key_height", 50)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val factor = if (landscape) 0.65f else 1f
        val contentH = (dp(keyH.toFloat()) * (if (numberRow) 4.7f else 4f) * factor).toInt()
        val split = when (prefs.getString("split_keyboard", "off")) {
            "always" -> true
            "landscape" -> landscape
            else -> false
        }
        loadBackground()

        kb.kbTheme = kbTheme
        kb.contentHeightPx = contentH
        kb.setSplitAnimated(split)
        kb.keyBorders = prefs.getBoolean("key_borders", true)
        kb.showHints = prefs.getBoolean("show_hints", true)
        kb.haptic = prefs.getBoolean("haptic", true)
        kb.sound = prefs.getBoolean("sound", false)
        kb.showPreview = prefs.getBoolean("popup_preview", true)
        kb.bgBitmap = bgBmp

        val side = when (prefs.getString("one_handed", "off")) {
            "left" -> 1; "right" -> 2; else -> 0
        }
        kb.setOneHandedAnimated(side)

        (kb.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        (emoji.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        (clipPanel.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }

        emoji.setTheme(kbTheme)
        clipPanel.setTheme(kbTheme)

        root.setBackgroundColor(kbTheme.bg)
        toggle.setTextColor(kbTheme.hint)
        for (tv in slots) tv.setTextColor(kbTheme.text)
        for (tv in slotOlds) tv.setTextColor(kbTheme.text)
        slotRow.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        slotRow.dividerPadding = dp(14f).toInt()
        slotRow.dividerDrawable = GradientDrawable().apply {
            setSize(dp(1f).toInt(), 0)
            setColor((kbTheme.hint and 0x00FFFFFF) or 0x55000000)
        }
        rebuildToolbar()
        refreshStrip()
        root.post { applyOneHandedToStrip() }

        applyMode(mode, force = true)
    }

    private fun loadBackground() {
        val f = File(filesDir, "bg.jpg")
        if (f.exists() == false) { bgBmp = null; bgStamp = -1L; return }
        if (f.lastModified() == bgStamp && bgBmp != null) return
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / sample > 1400) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            bgBmp = BitmapFactory.decodeFile(f.path, o2)
            bgStamp = f.lastModified()
        } catch (_: Exception) { bgBmp = null }
    }

    private fun rebuildToolbar() {
        toolRow.removeAllViews()
        val enabled = prefs.getStringSet("toolbar_keys", null) ?: toolDefs.map { it.first }.toSet()
        for ((id, label) in toolDefs) {
            if (enabled.contains(id) == false) continue
            val tv = ImageView(this).apply {
                setImageResource(toolIcons[id] ?: R.drawable.ic_tb_settings)
                setColorFilter(if (id == "incognito" && incognito) kbTheme.accent else kbTheme.text)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt())
                contentDescription = label
                isClickable = true
                isFocusable = false
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.animate().cancel()
                            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(ShadeMotion.KEY_PRESS_DOWN_MS)
                                .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
                                .start()
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().cancel()
                            v.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(ShadeMotion.KEY_PRESS_UP_MS)
                                .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
                                .start()
                            false
                        }
                        else -> false
                    }
                }
                setOnClickListener { onTool(id) }
            }
            if (id == "incognito" && incognito) {
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(10f)
                    setColor(kbTheme.keyFn)
                }
            }
            toolRow.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
        }
    }

    private fun animateToggle() {
        toggle.animate().cancel()
        toggle.animate()
            .scaleX(1.3f).scaleY(1.3f)
            .setDuration(ShadeMotion.UI_TOGGLE_IN_MS)
                .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
            .withEndAction {
                toggle.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(ShadeMotion.UI_TOGGLE_OUT_MS)
                    .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
                    .start()
            }
            .start()
    }

    private fun refreshStrip() {
        if (this::strip.isInitialized == false) return
        val suggestionsActive = suggestionsOn && suggestionsAllowed
        val showTools = toolbarOpen || (suggestionsActive == false)

        slotFrame.visibility = if (showTools) View.GONE else View.VISIBLE
        toolScroll.visibility = if (showTools) View.VISIBLE else View.GONE

        slotFrame.alpha = 0f
        toolScroll.alpha = 0f
        val target = if (showTools) toolScroll else slotFrame
        target.animate()
            .alpha(1f)
            .setDuration(ShadeMotion.CANDIDATE_SWAP_MS)
            .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
            .start()

        toggle.text = if (showTools) "‹" else "›"
        toggle.visibility = if (suggestionsActive) View.VISIBLE else View.GONE
        strip.visibility = if (panel == Panel.NONE) View.VISIBLE else View.GONE
    }

    private fun applyOneHandedToStrip() {
        if (this::strip.isInitialized == false) return
        val w = root.width.toFloat()
        if (w <= 0f) return
        val availW = w * 0.75f
        val lp = strip.layoutParams as? LinearLayout.LayoutParams ?: return
        when (kb.oneHanded) {
            1 -> {
                lp.width = availW.toInt()
                lp.gravity = Gravity.START
            }
            2 -> {
                lp.width = availW.toInt()
                lp.gravity = Gravity.END
            }
            else -> {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.START
            }
        }
        strip.layoutParams = lp
        strip.requestLayout()
    }

    // ------------------------------------------------------------------ mode switching

    private fun setMode(m: Mode) {
        if (m == mode) return
        applyMode(m)
    }

    private fun applyMode(m: Mode, force: Boolean = false) {
        if (m == mode && !force && kb.rows.isNotEmpty()) return
        mode = m

        val rows = when (m) {
            Mode.LETTERS -> Layouts.letters(
                layoutName, numberRow,
                prefs.getString("custom_layout", "") ?: ""
            )
            Mode.SYM1 -> Layouts.symbols1()
            Mode.SYM2 -> Layouts.symbols2()
            Mode.NUMPAD -> Layouts.numpad()
        }

        kb.setRows(rows)
        kb.shiftState = if (m == Mode.LETTERS) shiftState else 0
        kb.relayout()
    }

    private fun approximateKeyWidth(): Float {
        return if (this::kb.isInitialized && kb.width > 0) kb.width / 10f else dp(24f)
    }

    // ------------------------------------------------------------------ panel transitions

    private fun transitionToPanel(p: Panel, animate: Boolean = true) {
        if (this::emoji.isInitialized == false) return
        val emojiContext = if (composing.isNotEmpty()) composing.toString() else lastWord().orEmpty()
        if (p != Panel.NONE) endWord()
        if (panel == p) return

        val outgoing = when (panel) {
            Panel.EMOJI -> emoji
            Panel.CLIP -> clipPanel
            Panel.NONE -> null
        }
        val incoming = when (p) {
            Panel.EMOJI -> emoji
            Panel.CLIP -> clipPanel
            Panel.NONE -> null
        }

        panel = p

        if (p == Panel.EMOJI) {
            emoji.setSuggestions(
                if (emojiSuggest) emojiPredictor.predictEmoji(emojiContext, 10) else emptyList()
            )
        }

        if (!animate) {
            outgoing?.visibility = View.GONE
            incoming?.visibility = View.GONE
            kb.visibility = if (p == Panel.NONE) View.VISIBLE else View.GONE
            refreshStrip()
            return
        }

        val w = root.width.toFloat().coerceAtLeast(1f)
        val slideDist = w * 0.15f

        incoming?.let { v ->
            v.visibility = View.VISIBLE
            v.translationX = slideDist
            v.alpha = 0f
            SpringAnimation(v, DynamicAnimation.TRANSLATION_X, 0f).apply {
                spring = SpringForce(0f).apply {
                    dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                    stiffness = SpringForce.STIFFNESS_MEDIUM
                }
            }.start()
            v.animate().alpha(1f).setDuration(ShadeMotion.PANEL_IN_MS).setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN).start()
        }

        outgoing?.let { v ->
            SpringAnimation(v, DynamicAnimation.TRANSLATION_X, -slideDist).apply {
                spring = SpringForce(-slideDist).apply {
                    dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                    stiffness = SpringForce.STIFFNESS_MEDIUM
                }
            }.start()
            v.animate().alpha(0f).setDuration(ShadeMotion.PANEL_OUT_MS).setInterpolator(ShadeMotion.FAST_OUT_LINEAR_IN)
                .withEndAction {
                    v.visibility = View.GONE
                    v.translationX = 0f
                    v.alpha = 1f
                }
                .start()
        }

        kb.visibility = if (p == Panel.NONE) View.VISIBLE else View.GONE
        if (p == Panel.CLIP) clipPanel.refresh()
        refreshStrip()
    }

    // ------------------------------------------------------------------ toolbar

    private fun onTool(id: String) {
        val ic = currentInputConnection
        when (id) {
            "clip" -> transitionToPanel(Panel.CLIP)
            "emoji" -> transitionToPanel(Panel.EMOJI)
            "voice" -> voice?.start()
            "numpad" -> {
                endWord()
                if (mode == Mode.NUMPAD) setMode(Mode.LETTERS) else setMode(Mode.NUMPAD)
            }
            "left" -> onCursor(-1)
            "right" -> onCursor(1)
            "selectall" -> ic?.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic?.performContextMenuAction(android.R.id.copy)
            "cut" -> ic?.performContextMenuAction(android.R.id.cut)
            "paste" -> { endWord(); ic?.performContextMenuAction(android.R.id.paste) }
            "undo" -> ic?.performContextMenuAction(android.R.id.undo)
            "incognito" -> {
                incognito = if (incognito) false else true
                prefs.edit().putBoolean("incognito", incognito).apply()
                rebuildToolbar()
            }
            "onehand" -> cycleOneHanded()
            "settings" -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        updateShiftFromCaps()
        updateSuggestions()
    }

    private fun cycleOneHanded() {
        val next = when (prefs.getString("one_handed", "off")) {
            "off" -> "left"
            "left" -> "right"
            else -> "off"
        }
        prefs.edit().putString("one_handed", next).apply()
        val side = when (next) { "left" -> 1; "right" -> 2; else -> 0 }
        kb.setOneHandedAnimated(side)
        applyOneHandedToStrip()
    }

    private fun showOneHandedMenu() {
        val popup = PopupMenu(this, toggle)
        popup.menu.add(0, 1, 0, "One-handed: Left")
        popup.menu.add(0, 2, 1, "One-handed: Right")
        popup.menu.add(0, 3, 2, "One-handed: Off")
        popup.setOnMenuItemClickListener { item ->
            val next = when (item.itemId) {
                1 -> "left"
                2 -> "right"
                else -> "off"
            }
            prefs.edit().putString("one_handed", next).apply()
            val side = when (next) { "left" -> 1; "right" -> 2; else -> 0 }
            kb.setOneHandedAnimated(side)
            applyOneHandedToStrip()
            true
        }
        popup.show()
    }

    private fun captureClip() {
        val historyOn = prefs.getBoolean("clipboard_history", true)
        if (historyOn == false || incognito) return
        try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val sensitive = clip.description.extras
                ?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            if (sensitive) return
            val t = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (t.isBlank() || t.length > 2000) return
            clips.add(t)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ undo stack

    private fun pushUndo(charsCommitted: Int, restore: String) {
        if (charsCommitted <= 0 && restore.isEmpty()) return
        if (undoStack.size >= MAX_UNDO) undoStack.removeLast()
        undoStack.offerLast(UndoEntry(charsCommitted, restore))
    }

    private fun commitSmart(text: String, charsCommitted: Int) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        pushUndo(charsCommitted, "")
    }

    private fun clearUndo() {
        undoStack.clear()
    }

    // ------------------------------------------------------------------ tail buffer

    private fun appendTail(s: String) {
        for (c in s) {
            tail.append(c)
            if (tail.length > 16) tail.deleteCharAt(0)
        }
    }

    private fun popTail(n: Int) {
        for (i in 0 until n) if (tail.isNotEmpty()) tail.deleteCharAt(tail.length - 1)
    }

    private fun lastWord(): String? {
        if (tailValid == false) {
            val before = currentInputConnection?.getTextBeforeCursor(16, 0)?.toString() ?: return null
            tail.setLength(0); tail.append(before); tailValid = true
        }
        val s = tail.toString().trimEnd()
        if (s.isEmpty()) return null
        val idx = s.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        return if (idx < 0) s else s.substring(idx + 1).takeIf { it.isNotEmpty() }
    }

    private fun currentTypedWordFromField(): String {
        val before = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString() ?: return ""
        var i = before.length - 1
        while (i >= 0) {
            val c = before[i]
            if (WordDictionary.isWordChar(c)) i -= 1 else break
        }
        return before.substring(i + 1)
    }

    private fun previousWordFromField(): String? {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return null
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end -= 1
        if (end == 0) return null
        var start = end
        while (start > 0) {
            val c = before[start - 1]
            if (WordDictionary.isWordChar(c)) start -= 1 else break
        }
        if (start >= end) return null
        return before.substring(start, end).takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ KeyboardView.Listener

    override fun onSwipePath(path: List<FloatArray>): Boolean {
        if (path.size < 4) return false
        val layout = kbKeyLayout() ?: return false
        suggestJob?.cancel()
        suggestJob = scope.launch {
            val decoded = slideDecoder.decode(path, layout)
            if (!decoded.isNullOrEmpty()) commitSlideWord(decoded)
        }
        return true
    }

    override fun onKey(code: Int) {
        when (code) {
            Codes.SHIFT -> onShift()
            Codes.DELETE -> backspace()
            Codes.ENTER -> enter()
            Codes.SPACE -> space()
            Codes.SYMBOLS -> { endWord(); setMode(Mode.SYM1) }
            Codes.SYMBOLS2 -> setMode(Mode.SYM2)
            Codes.LETTERS -> {
                endWord()
                setMode(Mode.LETTERS)
                updateShiftFromCaps()
            }
            Codes.EMOJI -> transitionToPanel(Panel.EMOJI)
        }
    }

    override fun onEnterLongPress() {
        showOneHandedMenu()
    }

    private fun wordDeleteRange(wordCount: Int): Int {
        if (wordCount <= 0) return 0
        val ic = currentInputConnection ?: return 0
        val before = ic.getTextBeforeCursor(500, 0)?.toString() ?: return 0
        if (before.isEmpty()) return 0

        var i = before.length
        var deleted = 0
        while (i > 0 && before[i - 1].isWhitespace()) i -= 1
        while (i > 0 && deleted < wordCount) {
            while (i > 0 && !before[i - 1].isWhitespace()) i -= 1
            deleted += 1
            while (i > 0 && before[i - 1].isWhitespace()) i -= 1
        }
        return before.length - i
    }

    override fun onDeletePreview(travelPx: Float): Int {
        val ic = currentInputConnection ?: return 0
        if (cursorPos < 0) return 0

        val before = ic.getTextBeforeCursor(500, 0)?.toString() ?: return 0
        if (before.isEmpty()) {
            deletePreviewActive = true
            pendingDeleteWords = 0
            ic.setSelection(cursorPos, cursorPos)
            return 0
        }

        val pxPerChar = approximateKeyWidth()
        val targetChars = (travelPx / pxPerChar).toInt().coerceAtLeast(0)

        var i = before.length
        var wordCount = 0
        var consumed = 0
        while (i > 0) {
            while (i > 0 && before[i - 1].isWhitespace()) i -= 1
            if (i == 0) break
            while (i > 0 && !before[i - 1].isWhitespace()) i -= 1
            wordCount += 1
            consumed = before.length - i
            if (consumed >= targetChars) break
        }

        deletePreviewActive = true
        pendingDeleteWords = wordCount

        if (consumed <= 0) {
            ic.setSelection(cursorPos, cursorPos)
            return 0
        }
        ic.setSelection(cursorPos - consumed, cursorPos)
        return wordCount
    }

    override fun onDeleteCommit() {
        val ic = currentInputConnection ?: return
        val words = pendingDeleteWords
        deletePreviewActive = false
        pendingDeleteWords = 0
        if (cursorPos < 0 || words <= 0) return
        val chars = wordDeleteRange(words)
        if (chars <= 0) return

        ic.beginBatchEdit()
        try {
            ic.setSelection(cursorPos - chars, cursorPos)
            ic.commitText("", 1)
        } finally {
            ic.endBatchEdit()
        }
        tail.setLength(0)
        tailValid = false
        composing.clear()
        clearUndo()
        afterText()
    }

    override fun onDeleteCancel() {
        val ic = currentInputConnection
        deletePreviewActive = false
        pendingDeleteWords = 0
        if (cursorPos < 0) return
        ic?.setSelection(cursorPos, cursorPos)
    }

    override fun onDeleteWords(count: Int) {
        val ic = currentInputConnection ?: return
        if (count <= 0) return
        val chars = wordDeleteRange(count)
        if (chars <= 0) return
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(chars, 0)
        } finally {
            ic.endBatchEdit()
        }
        tail.setLength(0)
        tailValid = false
        composing.clear()
        clearUndo()
        afterText()
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return

        if (text != " ") clearUndo()

        val isWordChar = canCompose && mode == Mode.LETTERS && text.length == 1 &&
            ((text[0] != '\'' && WordDictionary.isWordChar(text[0])) || (text[0] == '\'' && composing.isNotEmpty()))

        if (isWordChar) {
            recordLastTouch()

            if (shiftState == 1) {
                shiftState = 0
                autoShifted = false
                kb.shiftState = 0
            }

            val sentenceEnd = smartCorrectionOn && canCompose &&
                text.length == 1 && text[0].isLowerCase() &&
                composing.isEmpty() && atSentenceEndBeforeCursor()
            if (sentenceEnd) {
                val upper = text.uppercase()
                composing.append(upper)
                ic.setComposingText(composing.toString(), 1)
                pushUndo(upper.length, text)
                appendTail(upper)
                afterText()
                return
            }

            composing.append(text)
            ic.setComposingText(composing.toString(), 1)
            appendTail(text)
        } else {
            val isPunct = text.length == 1 && text[0] in ".,!?;:"
            ic.beginBatchEdit()
            try {
                if (justAutoSpaced && composing.isEmpty() && isPunct) {
                    ic.deleteSurroundingText(1, 0)
                    popTail(1)
                }
                endWord(if (isPunct) text else null)
                ic.commitText(text, 1)
                appendTail(text)

                if (smartCorrectionOn && canCompose && text.length == 1) {
                    val c = text[0]
                    val autoSpace = c == '.' || c == '!' || c == '?' ||
                        c == ',' || c == ';' || c == ':'
                    if (autoSpace) {
                        ic.commitText(" ", 1)
                        appendTail(" ")
                        pushUndo(1, "")
                        if (c == '.' || c == '!' || c == '?') resetBigramContext()
                    }
                }
            } finally {
                ic.endBatchEdit()
            }
        }
        lastSpaceTime = 0L
        afterText()
    }

    private fun snapshotWordTouches() {
        lastWordTouches = touchTrace.last(touchTrace.size())
        touchTrace.clear()
    }

    private fun recordLastTouch() {
        val w = composing.toString()
        if (w.isEmpty()) return
        val last = w.last()
        val layout = kbKeyLayout() ?: return
        val k = layout[last.lowercaseChar()] ?: return
        touchTrace.record(
            (k.left + k.right) / 2f,
            (k.top + k.bottom) / 2f
        )
    }

    private fun kbKeyLayout(): Map<Char, Key>? {
        if (this::kb.isInitialized == false) return null
        val rows = kb.rows
        if (rows.isEmpty()) return null
        val map = HashMap<Char, Key>(64)
        for (row in rows) for (k in row) {
            if (k.kind == KeyKind.CHAR && k.label.length == 1) {
                map[k.label[0].lowercaseChar()] = k
            }
        }
        return map
    }

    override fun onCursor(delta: Int) {
        endWord()
        val key = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        sendDownUpKeyEvents(key)
        clearUndo()
        tailValid = false
        updateShiftFromCaps()
        updateSuggestions()
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        var i = 0
        while (i < dx) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT); i += 1 }
        while (i > dx) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT); i -= 1 }
        var j = 0
        while (j < dy) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN); j += 1 }
        while (j > dy) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP); j -= 1 }
        clearUndo()
        tailValid = false
    }

    override fun onTrackpadEnter() {
        kb.haptic = prefs.getBoolean("haptic", true)
    }

    override fun onTrackpadExit() {
        updateShiftFromCaps()
        updateSuggestions()
    }

    override fun onLongPressSpecial() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showInputMethodPicker()
    }

    // ------------------------------------------------------------------ editing

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        shiftState = when {
            shiftState == 2 -> 0
            shiftState == 1 && now - lastShiftTime < 350 -> 2
            shiftState == 1 -> 0
            else -> 1
        }
        autoShifted = false
        lastShiftTime = now
        kb.shiftState = shiftState
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return

        if (undoStack.isNotEmpty() && composing.isEmpty()) {
            val undo = undoStack.pollLast()
            if (undo != null) {
                ic.beginBatchEdit()
                try {
                    if (undo.charsCommitted > 0) {
                        ic.deleteSurroundingText(undo.charsCommitted, 0)
                        popTail(undo.charsCommitted)
                    }
                    if (undo.restore.isNotEmpty()) {
                        ic.commitText(undo.restore, 1)
                        appendTail(undo.restore)
                    }
                } finally {
                    ic.endBatchEdit()
                }
                updateShiftFromCaps()
                updateSuggestions()
                return
            }
        }

        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) ic.commitText("", 1)
            else ic.setComposingText(composing.toString(), 1)
            popTail(1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            popTail(1)
        }
        lastSpaceTime = 0L
        updateShiftFromCaps()
        updateSuggestions()
    }

    private fun space() {
        val ic = currentInputConnection ?: return
        snapshotWordTouches()
        ic.beginBatchEdit()
        try {
            val originalLen = composing.length
            val corrected = endWord(" ")
            if (corrected != null) {
                popTail(originalLen)
                appendTail(corrected)
                pushUndo(corrected.length, "")
            }

            val now = SystemClock.uptimeMillis()
            if (smartCorrectionOn && canCompose && doubleSpace &&
                corrected == null && now - lastSpaceTime < 500
            ) {
                val before = if (tailValid) tail.toString()
                else currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
                if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    popTail(1)
                    appendTail(". ")
                    pushUndo(2, "  ")
                    lastSpaceTime = 0L
                    resetBigramContext()
                    return
                }
            }

            ic.commitText(" ", 1)
            appendTail(" ")
            lastSpaceTime = now
        } finally {
            ic.endBatchEdit()
        }
        afterText()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        snapshotWordTouches()
        endWord()
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noEnterAction == false &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) ic.performEditorAction(action)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        clearUndo()
        tail.setLength(0)
        tailValid = false
        lastSpaceTime = 0L
        resetBigramContext()
        afterText()
    }

    private fun endWord(sep: String? = null): String? {
        val ic = currentInputConnection

        if (composing.isNotEmpty()) {
            val w = composing.toString()

            if (smartCorrectionOn && canCompose && w == "i" && sep != null) {
                ic?.commitText("I", 1)
                popTail(1)
                appendTail("I")
                pushUndo(1, "i")
                composing.clear()
                return null
            }

            val shouldCorrect = sep != null && autocorrectOn && canCompose &&
                w.drop(1).none { it.isUpperCase() }

            if (shouldCorrect) {
                ic?.commitText(w, 1)
                composing.clear()
                val prev1 = lastWord()
                correctionJob?.cancel()
                correctionJob = scope.launch {
                    val c = dict.correctionAsync(w)
                    if (c != null && c != w) {
                        withContext(Dispatchers.Main) {
                            val ic2 = currentInputConnection ?: return@withContext
                            val current = ic2.getTextBeforeCursor(w.length, 0)?.toString()
                            if (current != w) return@withContext
                            ic2.beginBatchEdit()
                            try {
                                ic2.deleteSurroundingText(w.length, 0)
                                ic2.commitText(c, 1)
                            } finally {
                                ic2.endBatchEdit()
                            }
                            pushUndo(c.length, w)
                            appendTail(c)
                            updateShiftFromCaps()
                            updateSuggestions()
                        }
                    } else {
                        if (learnOn && incognito == false && canCompose) {
                            dict.learn(w, prev2 = null, prev1 = prev1)
                        }
                    }
                }
                return null
            } else {
                if (learnOn && incognito == false && canCompose) {
                    dict.learn(w, prev2 = null, prev1 = lastWord())
                }
                ic?.commitText(w, 1)
                composing.clear()
            }
        } else {
            ic?.finishComposingText()
        }
        return null
    }

    private fun resetBigramContext() {
        tail.setLength(0)
        tailValid = false
    }

    private fun afterText() {
        if (shiftState == 1) { shiftState = 0; autoShifted = false }
        updateShiftFromCaps()
        updateSuggestions()
    }

    private fun atSentenceEndBeforeCursor(): Boolean {
        val before: String = if (tailValid) {
            tail.toString()
        } else {
            val fromIc = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString()
            if (fromIc == null) return false
            fromIc
        }
        if (before.isEmpty()) return true
        var i = before.length - 1
        while (i >= 0) {
            val ch = before[i]
            if (ch == ' ' || ch == '\n' || ch == '\t') { i -= 1; continue }
            if (ch == '.' || ch == '!' || ch == '?') return true
            return false
        }
        return true
    }

    private fun updateShiftFromCaps() {
        if (this::kb.isInitialized == false) return
        if (mode != Mode.LETTERS) { kb.shiftState = 0; return }
        if (shiftState != 2) {
            val ei = info
            val ic = currentInputConnection
            if (autoCaps && ei != null && ic != null &&
                (ei.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
            ) {
                val caps = ic.getCursorCapsMode(ei.inputType)
                if (caps != 0 && shiftState == 0) { shiftState = 1; autoShifted = true }
                else if (caps == 0 && autoShifted && shiftState == 1) { shiftState = 0; autoShifted = false }
            }
        }
        kb.shiftState = shiftState
    }

    // ------------------------------------------------------------------ suggestions (cascade)

    @Synchronized
    private fun reloadNeuralEngine(locale: String) {
        if (neuralLocale == locale) return
        neuralLocale = locale
        val old = neural
        neural = null
        old?.close()
        val created = NeuralPredictionEngine.create(this@ShadeService, locale)
        created?.enabled = neuralOn
        neural = created
    }

    private fun neuralContext(typedWord: String): String {
        val raw = currentInputConnection
            ?.getTextBeforeCursor(96, 0)
            ?.toString()
            ?: ""
        return if (typedWord.isNotEmpty() && raw.endsWith(typedWord, ignoreCase = true)) {
            raw.dropLast(typedWord.length)
        } else {
            raw
        }
    }

    private fun commitVoiceText(text: String) {
        endWord()
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        currentInputConnection?.commitText("$cleaned ", 1)
        appendTail("$cleaned ")
        pushUndo(cleaned.length + 1, "")
        composing.clear()
        afterText()
    }

    private fun commitSlideWord(word: String) {
        endWord()
        currentInputConnection?.commitText("$word ", 1)
        appendTail("$word ")
        pushUndo(word.length + 1, "")
        composing.clear()
        afterText()
    }

    private fun updateSuggestions() {
        if (this::slots.isInitialized == false) return
        suggestJob?.cancel()
        if (suggestionsOn == false || suggestionsAllowed == false) {
            setCandidates(emptyList())
            return
        }

        val typedWord: String
        val composingActive = composing.isNotEmpty()
        if (composingActive) {
            typedWord = composing.toString()
        } else if (canCompose == false && suggestionsAllowed) {
            typedWord = currentTypedWordFromField()
        } else {
            typedWord = ""
        }

        if (typedWord.isEmpty()) {
            val prev1: String? = if (composingActive) lastWord()
                else if (canCompose == false && suggestionsAllowed) previousWordFromField()
                else lastWord()
            val context = neuralContext("")

            suggestJob = scope.launch {
                val neuralNext = withContext(Dispatchers.Default) {
                    neural
                        ?.predictNext(context, null, 6)
                        ?.map { it.first }
                        .orEmpty()
                }
                val dictionaryNext = prev1
                    ?.let { dict.predictNext(null, it, 6) }
                    .orEmpty()
                val merged = CandidateRanker.mergeNextWord(
                    neural = neuralNext,
                    dictionary = dictionaryNext,
                    limit = 3
                )

                val list: List<String> = when {
                    merged.isNotEmpty() -> centerBest(merged)
                    atSentenceEndBeforeCursor() -> listOf("The", "I", "I'm")
                    else -> emptyList()
                }
                setCandidates(list)
            }
            return
        }

        val prefix = typedWord
        val prev1 = lastWord()
        val touches = if (composingActive) touchTrace.last(touchTrace.size()) else emptyList()
        val layout = kbKeyLayout()
        val neuralCtx = neuralContext(typedWord)

        suggestJob = scope.launch {
            val known = dict.contains(prefix)
            val stage1 = LinkedHashSet<String>()
            stage1.addAll(dict.suggestAsync(prefix, 6))

            val stage2 = if (prev1 != null) {
                dict.rerankWithContext(null, prev1, stage1.toList())
            } else stage1.toList()

            val stage3 = if (stage2.size >= 2 && layout != null && touches.isNotEmpty()) {
                val a = stage2[0]
                val b = stage2[1]
                if (a.length == b.length && a.take(2) == b.take(2)) {
                    SpatialScorer.tiebreak(stage2, touches, layout)
                } else stage2
            } else stage2

            val ranked = withContext(Dispatchers.Default) {
                neural?.rerankPrefix(neuralCtx, prefix, stage3)
            } ?: stage3
            val shown: List<String> = if (!known && ranked.isNotEmpty()) {
                listOf(prefix) + ranked.take(2)
            } else {
                centerBest(ranked.ifEmpty { listOf(prefix) }.take(3))
            }
            setCandidates(shown)
        }
    }

    private fun centerBest(l: List<String>): List<String> = when (l.size) {
        0 -> l
        1 -> listOf("", l[0])
        2 -> listOf(l[1], l[0])
        else -> listOf(l[1], l[0], l[2])
    }

    private fun setCandidates(list: List<String>) {
        if (list == candidates) return
        val old = candidates
        candidates = list
        slotAnimator?.cancel()

        var anyChanged = false
        for (i in 0 until 3) {
            val newWord = list.getOrNull(i) ?: ""
            val oldWord = old.getOrNull(i) ?: ""
            if (newWord == oldWord) continue
            anyChanged = true

            slotOlds[i].text = oldWord
            slotOlds[i].alpha = 1f
            slotOlds[i].visibility = if (oldWord.isEmpty()) View.GONE else View.VISIBLE

            slots[i].text = newWord
            slots[i].alpha = 0f
            slots[i].scaleX = if (i == 1) 0.95f else 1f
            slots[i].scaleY = if (i == 1) 0.95f else 1f
            slots[i].setTypeface(null, if (i == 1) Typeface.BOLD else Typeface.NORMAL)
        }
        if (!anyChanged) return

        slotAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShadeMotion.CANDIDATE_SWAP_MS
            interpolator = ShadeMotion.FAST_OUT_SLOW_IN
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in 0 until 3) {
                    slotOlds[i].alpha = 1f - t
                    slots[i].alpha = t
                    if (i == 1) {
                        val s = 0.95f + 0.05f * t
                        slots[i].scaleX = s
                        slots[i].scaleY = s
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    for (i in 0 until 3) {
                        slotOlds[i].alpha = 0f
                        slotOlds[i].visibility = View.GONE
                        slots[i].alpha = 1f
                        slots[i].scaleX = 1f
                        slots[i].scaleY = 1f
                    }
                }
            })
            start()
        }
    }

    private fun pickSuggestion(i: Int) {
        val w = candidates.getOrNull(i) ?: return
        if (w.isEmpty()) return
        val ic = currentInputConnection ?: return
        clearUndo()
        ic.beginBatchEdit()
        try {
            if (composing.isNotEmpty()) {
                composing.clear()
                ic.commitText(w, 1)
            } else {
                val typed = currentTypedWordFromField()
                if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
                ic.commitText(w, 1)
            }
            appendTail(w)
            if (w.isNotEmpty() && w[0].isLetter()) {
                ic.commitText(" ", 1)
                appendTail(" ")
            }
        } finally {
            ic.endBatchEdit()
        }
        if (w.isNotEmpty() && w[0].isLetter()) {
            if (learnOn && incognito == false && canCompose) {
                dict.learn(w, prev2 = null, prev1 = lastWord())
            }
        }
        lastSpaceTime = 0L
        afterText()
    }

    companion object {
        private const val MAX_UNDO = 10
    }
}
