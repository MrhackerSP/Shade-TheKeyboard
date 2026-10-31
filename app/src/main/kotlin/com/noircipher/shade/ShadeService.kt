package com.noircipher.shade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipDescription
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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import android.util.TypedValue
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.preference.PreferenceManager
import com.noircipher.shade.ai.NeuralPredictionEngine
import com.noircipher.shade.analytics.TouchBiasModel
import com.noircipher.shade.analytics.TypingStatsStore
import com.noircipher.shade.emoji.EmojiPredictionEngine
import com.noircipher.shade.engine.GrammarEngine
import com.noircipher.shade.engine.HeuristicSuggestionEngine
import com.noircipher.shade.engine.CandidateRanker
import com.noircipher.shade.engine.TfliteCandidateRankingScorer
import com.noircipher.shade.engine.TfliteContextEncoder
import com.noircipher.shade.engine.EmojiPredictor
import com.noircipher.shade.engine.LanguageModelEngine
import com.noircipher.shade.engine.UserWordMemory
import com.noircipher.shade.engine.AdaptiveWordMemory
import com.noircipher.shade.engine.AppContextModel
import com.noircipher.shade.engine.AutocorrectSuppressor
import com.noircipher.shade.engine.ConfidenceCalibrator
import com.noircipher.shade.engine.OnlineRankerTrainer
import com.noircipher.shade.engine.RankingFeatureExtractor
import com.noircipher.shade.engine.RewriteModelManager
import com.noircipher.shade.engine.SessionBigramBoost
import com.noircipher.shade.engine.SentencePositionScorer
import com.noircipher.shade.engine.SuggestionRejectionTracker
import com.noircipher.shade.engine.AcceptanceRateAdapter
import com.noircipher.shade.engine.PerAppVocabularySnapshot
import com.noircipher.shade.motion.ShadeMotion
import com.noircipher.shade.slide.SlideDecoder
import com.noircipher.shade.voice.VoiceInputController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.ArrayDeque

class ShadeService : InputMethodService(), KeyboardView.Listener {

    private enum class Mode { LETTERS, SYM1, SYM2, NUMPAD }
    private enum class Panel { NONE, EMOJI, CLIP }

    private class UndoEntry(
        val charsCommitted: Int,
        val restore: String,
        // Absolute cursor position right after the commit this entry undoes.
        // backspace() uses this to skip stale undo entries when the cursor has
        // since moved elsewhere (prevents deleting the wrong characters).
        val cursorAtCommit: Int = -1
    )

    private data class CorrectionEvent(
        val original: String,
        val corrected: String,
        val timestamp: Long,
        val separator: String? = null,
        val separatorDeleted: Boolean = false
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
    // Tracks when the user has explicitly pressed shift to turn OFF auto-caps
    // (so smart-correction's sentence-end auto-capitalisation does not override
    // their choice). Cleared when sentence-ending punctuation is committed, so
    // auto-caps can apply again for the following sentence.
    private var userShiftOverride = false
    private var justAutoSpaced = false
    private val composing = StringBuilder()
    private var canCompose = true
    private var suggestionsAllowed = true
    private var suggestionsOn = true
    private var neuralOn = true
    @Volatile
    private var neural: LanguageModelEngine? = null
    @Volatile
    private var neuralLocale: String? = null
    private val neuralLock = Any()
    private val failedNeuralLocales = HashMap<String, Long>()
    private var configuredLocale: String? = null
    private val localeAssetsMutex = Mutex()
    private var emojiPredictor: EmojiPredictor = EmojiPredictionEngine.builtIn()
    private var voice: VoiceInputController? = null
    private val slideDecoder: SlideDecoder by lazy { SlideDecoder(dict) }
    private var rankingScorer: com.noircipher.shade.engine.CandidateRankingScorer? = null
    private val userWordMemory: UserWordMemory by lazy { UserWordMemory(this) }
    private val typingStatsStore: TypingStatsStore by lazy { TypingStatsStore(this) }
    private val adaptiveWordMemory: AdaptiveWordMemory by lazy { AdaptiveWordMemory(this) }
    private val appContextModel: AppContextModel by lazy { AppContextModel(this) }
    private val autocorrectSuppressor: AutocorrectSuppressor by lazy { AutocorrectSuppressor(this) }
    private val confidenceCalibrator: ConfidenceCalibrator by lazy { ConfidenceCalibrator(this) }
    private val onlineRankerTrainer: OnlineRankerTrainer by lazy { OnlineRankerTrainer(this) }
    private val touchBiasModel: TouchBiasModel by lazy { TouchBiasModel(this) }
    private val sessionBigramBoost by lazy { com.noircipher.shade.engine.SessionBigramBoost() }
    private val sentencePositionScorer by lazy { com.noircipher.shade.engine.SentencePositionScorer(this) }
    private val rejectionTracker by lazy { com.noircipher.shade.engine.SuggestionRejectionTracker(this) }
    private val acceptanceRateAdapter by lazy { com.noircipher.shade.engine.AcceptanceRateAdapter(this) }
    private val perAppVocabSnapshot by lazy { com.noircipher.shade.engine.PerAppVocabularySnapshot(appContextModel) }
    private var typingInsightsOn = false
    private val rewriteManager: RewriteModelManager by lazy { RewriteModelManager(this) }
    private var contextEncoder: com.noircipher.shade.engine.ContextEncoder? = null
    private val contextEncoderVocab: Map<String, Int> by lazy { loadContextEncoderVocab() }
    private var learnOn = true
    private var learningAdaptiveOn = true
    private var learningAppCtxOn = true
    private var learningSuppressorOn = true
    private var learningCalibratorOn = true
    private var learningRankerOn = true
    private var autoCaps = true
    private var doubleSpace = true
    private var autocorrectOn = true
    private var smartCorrectionOn = true
    private var emojiSuggest = true
    private var incognito = false
    private var fieldNoPersonalizedLearning = false
    private var fieldSecureInput = false
    private val lastRankedFeatures = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    private val lastRawRankerScores = java.util.concurrent.ConcurrentHashMap<String, Float>()
    @Volatile
    private var lastShownCandidates: List<String> = emptyList()
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
    private var lastCorrectionEvent: CorrectionEvent? = null

    private val tail = StringBuilder(16)
    private var cursorPos = -1
    private var selectionEnd = -1
    // Absolute end position of the composing region, as reported by the
    // framework in onUpdateSelection(). Used by backspace() to detect when the
    // user has moved the cursor *inside* an active composing word (so we delete
    // at the cursor instead of always chopping the last composing char and
    // snapping the cursor to the end).
    private var composingEnd = -1
    private var tailValid = false

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var suggestJob: Job? = null
    private var suggestDebounceJob: Job? = null
    private var correctionJob: Job? = null
    private var slotAnimator: ValueAnimator? = null
    private var panelSpring: SpringAnimation? = null

    private var lastOrientation = -1

    // 4b — Field-type classification for the current input field. Drives
    // gating decisions in updateSuggestions / endWord.
    private var currentFieldType: FieldTypeDetector.FieldType = FieldTypeDetector.FieldType.NORMAL_TEXT

    /**
     * Most-recently-copied short text. Shown as a "clipboard chip" in the
     * suggestion bar when there are no other candidates. Cleared once real
     * suggestions arrive, or when the user picks it. See [maybeShowClipboardChip]
     * and [setCandidates].
     */
    private var lastClipText: String? = null

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
        clips.cleanupExpired()
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try { cm.addPrimaryClipChangedListener(clipListener) } catch (_: Exception) {}

        val locale = dictIds()
        scope.launch(Dispatchers.Default) {
            configureLocaleAssets(locale)
        }
    }

    override fun onDestroy() {
        try { cm.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        dict.bigramModel?.save()
        suggestJob?.cancel()
        suggestDebounceJob?.cancel()
        correctionJob?.cancel()
        slotAnimator?.cancel()
        panelSpring?.cancel()
        try { neural?.close() } catch (_: Exception) {}
        neural = null
        neuralLocale = null
        synchronized(neuralLock) { failedNeuralLocales.clear() }
        try { contextEncoder?.close() } catch (_: Exception) {}
        contextEncoder = null
        try { adaptiveWordMemory.shutdown() } catch (_: Exception) {}
        try { appContextModel.shutdown() } catch (_: Exception) {}
        try { rewriteManager.close() } catch (_: Exception) {}
        try { rankingScorer?.close() } catch (_: Exception) {}
        rankingScorer = null
        try { emojiPredictor.close() } catch (_: Exception) {}
        try { voice?.cancel() } catch (_: Exception) {}
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
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12, 19, 1, TypedValue.COMPLEX_UNIT_SP)
                setOnClickListener { pickSuggestion(it.tag as Int) }
            }
        }
        slotOlds = List(3) {
            TextView(this).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                textSize = 19f
                setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12, 19, 1, TypedValue.COMPLEX_UNIT_SP)
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
            onPaste = { clip ->
                pasteClip(clip)
            },
            onBack = { transitionToPanel(Panel.NONE) },
            onDelete = { backspace() }
        )
        emoji.visibility = View.GONE
        clipPanel.visibility = View.GONE

        val content = FrameLayout(this).apply {
            addView(kb, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emoji, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(clipPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(MATCH, dp(52f).toInt()))
            addView(content, LinearLayout.LayoutParams(MATCH, dp(200f).toInt()))
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
        fieldNoPersonalizedLearning =
            (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        fieldSecureInput = isSecureInputType(editorInfo.inputType)
        incognito = prefs.getBoolean("incognito", false) || fieldNoPersonalizedLearning || fieldSecureInput
        // 4c — classify the active field so suggestion / autocorrect / smart-compose
        // pipelines can be gated by field type (password / email / URL / etc.).
        currentFieldType = FieldTypeDetector.detect(editorInfo)
        if (appContextLearningAllowed()) {
            appContextModel.setCurrentPackage(editorInfo.packageName)
        } else {
            appContextModel.setCurrentPackage(null)
        }
        // Mirror whether an active app-context is set into the per-app
        // vocabulary snapshot. The snapshot just needs to know "yes/no" —
        // lookups themselves delegate to the AppContextModel which holds
        // the actual hashed-package key.
        perAppVocabSnapshot.onEnterApp(if (appContextLearningAllowed()) (editorInfo.packageName ?: "") else "")
        dict.reloadUser()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
        cursorPos = -1
        selectionEnd = -1
        composingEnd = -1
        justAutoSpaced = false
        lastSpaceTime = 0L
        shiftState = 0
        autoShifted = false
        userShiftOverride = false
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
        maybeShowClipboardChip()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        suggestJob?.cancel()
        suggestDebounceJob?.cancel()
        correctionJob?.cancel()
        info = null
        candidates = emptyList()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
        cursorPos = -1
        selectionEnd = -1
        composingEnd = -1
        userShiftOverride = false
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
        composingEnd = -1
        userShiftOverride = false
        pendingDeleteWords = 0
        deletePreviewActive = false

        if (this::dict.isInitialized) {
            scope.launch(Dispatchers.IO) {
                try { dict.bigramModel?.save() } catch (t: Throwable) { Log.e("ShadeService", "Failed to save bigrams", t) }
            }
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
        if (newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation
            applyPrefs()
        } else {
            root.post { applyOneHandedToStrip() }
        }
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
        // Track the composing-region end so backspace() can tell whether the
        // cursor still sits at the end of the composing word or has been moved
        // elsewhere by the user.
        composingEnd = candidatesEnd
        if (composing.isNotEmpty() &&
            (candidatesStart == -1 || newSelStart < candidatesStart || newSelStart > candidatesEnd)
        ) {
            currentInputConnection?.finishComposingText()
            composing.clear()
            updateSuggestions()
        }
    }

    // ------------------------------------------------------------------ prefs / theme

    private fun applyPrefs() {
        if (this::kb.isInitialized == false) return
        kbTheme = Themes.resolve(this, prefs)
        numberRow = prefs.getBoolean("number_row", false)
        suggestionsOn = prefs.getBoolean("suggestions", true)
        typingInsightsOn = prefs.getBoolean("typing_insights", false)
        neuralOn = prefs.getBoolean("neural_predictions", true)
        neural?.enabled = neuralOn
        learnOn = prefs.getBoolean("learn_words", true)
        learningAdaptiveOn = prefs.getBoolean("learning_adaptive", true)
        learningAppCtxOn = prefs.getBoolean("learning_appctx", true)
        learningSuppressorOn = prefs.getBoolean("learning_suppressor", true)
        learningCalibratorOn = prefs.getBoolean("learning_calibrator", true)
        learningRankerOn = prefs.getBoolean("learning_ranker", true)
        autoCaps = prefs.getBoolean("auto_caps", true)
        doubleSpace = prefs.getBoolean("double_space", true)
        autocorrectOn = prefs.getBoolean("autocorrect", true)
        smartCorrectionOn = prefs.getBoolean("smart_correction", true)
        emojiSuggest = prefs.getBoolean("emoji_suggestions", true)
        incognito = prefs.getBoolean("incognito", false) || fieldNoPersonalizedLearning || fieldSecureInput
        layoutName = prefs.getString("layout", "qwerty") ?: "qwerty"
        kb.trackpadEnabled = prefs.getBoolean("trackpad_mode", true)
        kb.typingBurstEnabled = prefs.getBoolean("typing_burst", false)
        kb.longPressMs = prefs.getInt("longpress_delay", 300).toLong()
        kb.repeatDelayMs = prefs.getInt("repeat_delay", 350).toLong()
        kb.repeatIntervalMs = prefs.getInt("repeat_interval", 40).toLong()

        val locale = dictIds()
        scope.launch(Dispatchers.Default) {
            configureLocaleAssets(locale)
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
        strip.visibility = View.VISIBLE
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
        val currentComposing = composing.toString()
        val emojiContext = if (currentComposing.isNotEmpty()) currentComposing else lastWord().orEmpty()
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
            scope.launch(Dispatchers.Default) {
                val suggestions = safeSuggestion(emptyList()) {
                    if (emojiSuggest) emojiPredictor.predictEmoji(emojiContext, 10) else emptyList()
                }
                withContext(Dispatchers.Main) { emoji.setSuggestions(suggestions) }
            }
        }

        if (!animate) {
            outgoing?.visibility = View.GONE
            incoming?.visibility = if (p == Panel.NONE) View.GONE else View.VISIBLE
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
            "voice" -> {
                if (!prefs.getBoolean("voice_input", true)) return
                val v = voice
                if (v == null) {
                    Toast.makeText(this, R.string.voice_input_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    v.start()
                }
            }
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

            val item = clip.getItemAt(0)
            val mime = clip.description?.getMimeType(0).orEmpty()

            if (mime.startsWith("image/") && item.uri != null) {
                val uri = item.uri!!
                scope.launch(Dispatchers.IO) {
                    val bytes = readCapped(uri, 12 * 1024 * 1024)
                    if (bytes != null && bytes.isNotEmpty()) clips.addImage(bytes, mime)
                }
                return
            }

            val t = item.coerceToText(this)?.toString() ?: return
            if (t.isBlank() || t.length > 2000 || isLikelySensitiveText(t)) return
            clips.add(t)
            maybeShowClipboardChip()
        } catch (_: Exception) {}
    }

    /**
     * Refreshes [lastClipText] from the clipboard store. Called from [captureClip]
     * after a new clip is added, and from [onStartInputView] so the chip is
     * available when the keyboard appears. Only short text clips (< 30 chars)
     * qualify; image clips and longer text are ignored for the chip.
     */
    private fun maybeShowClipboardChip() {
        if (!prefs.getBoolean("clipboard_history", true) || incognito ||
            fieldNoPersonalizedLearning || fieldSecureInput
        ) {
            lastClipText = null
            return
        }
        try {
            val clip = clips.load().firstOrNull()
            if (clip != null && clip.text.isNotEmpty() && clip.text.length < 30) {
                lastClipText = clip.text
            } else {
                lastClipText = null
            }
        } catch (_: Exception) {
            lastClipText = null
        }
    }

    private fun readCapped(uri: android.net.Uri, maxBytes: Int): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isLikelySensitiveText(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("://")) return false
        if (Regex("""(pass(word)?|pwd|token|secret|api[_-]?key|otp|pin)\s*[:=]""").containsMatchIn(lower)) return true
        if (!text.contains(" ") && text.length >= 10 && text.any { it.isLetter() } && text.any { it.isDigit() } && text.any { !it.isLetterOrDigit() }) return true
        return false
    }

    // ------------------------------------------------------------------ undo stack

    private fun separatorForMatch(separator: String?): String {
        if (separator.isNullOrEmpty() || separator.isBlank()) return separator.orEmpty()
        return if (smartCorrectionOn && !separator.last().isLetterOrDigit()) {
            separator + " "
        } else {
            separator
        }
    }

    private fun recordCorrectionEvent(original: String, corrected: String, separator: String? = null) {
        lastCorrectionEvent = CorrectionEvent(
            original = original,
            corrected = corrected,
            timestamp = SystemClock.uptimeMillis(),
            separator = separator
        )
    }

    private fun tryDeleteCorrectionSeparator(): Boolean {
        val event = lastCorrectionEvent ?: return false
        val sep = event.separator
        if (sep.isNullOrEmpty() || event.separatorDeleted) return false
        // A6 — removed 4-second timer: a separator delete always reverts the
        // preceding autocorrect, even if the user paused before backspace.
        val ic = currentInputConnection ?: return false
        val matchSep = separatorForMatch(sep)
        val length = event.corrected.length + matchSep.length
        val current = ic.getTextBeforeCursor(length, 0)?.toString() ?: return false
        if (!current.equals(event.corrected + matchSep, ignoreCase = true)) return false

        clearUndo()
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(matchSep.length, 0)
        } finally {
            ic.endBatchEdit()
        }
        lastCorrectionEvent = event.copy(separatorDeleted = true)
        return true
    }

    private fun tryRevertLastCorrectionEvent(): Boolean {
        val event = lastCorrectionEvent ?: return false
        // A6 — removed 4-second timer: any backspace inside the corrected
        // word reverts the autocorrect, no matter how long ago it was applied.
        val ic = currentInputConnection ?: return false
        val sep = event.separator.orEmpty()
        val matchSep = separatorForMatch(sep)
        val fullLength = event.corrected.length + matchSep.length
        val current = ic.getTextBeforeCursor(fullLength, 0)?.toString() ?: return false
        val matchesFull = current.equals(event.corrected + matchSep, ignoreCase = true)
        val matchesAfterSeparatorDeleted = sep.isNotEmpty() && event.separatorDeleted &&
            current.equals(event.corrected, ignoreCase = true)
        if (!matchesFull && !matchesAfterSeparatorDeleted) return false

        lastCorrectionEvent = null
        clearUndo()
        val prevForSuppressor = contextWordsExcluding("").dropLast(1).lastOrNull()
        if (suppressorLearningAllowed()) {
            autocorrectSuppressor.recordRejection(prevForSuppressor, event.original, event.corrected)
        }
        if (insightsAllowed()) typingStatsStore.recordAutocorrectRevert()

        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(if (matchesFull) fullLength else event.corrected.length, 0)
            ic.commitText(event.original, 1)
            popTail(event.corrected.length)
            appendTail(event.original)
            userWordMemory.bump(event.original)
            userWordMemory.penalize(event.corrected)
        } finally {
            ic.endBatchEdit()
        }
        updateShiftFromCaps()
        updateSuggestions()
        return true
    }

    private fun pushUndo(charsCommitted: Int, restore: String) {
        if (charsCommitted <= 0 && restore.isEmpty()) return
        if (undoStack.size >= MAX_UNDO) undoStack.removeLast()
        // Record the cursor position the commit is expected to leave the caret
        // at. backspace() checks this before replaying the undo, so a stale
        // entry (cursor has since moved elsewhere) is skipped instead of
        // deleting the wrong characters at the new caret position.
        val expectedCursor = if (cursorPos >= 0) cursorPos + charsCommitted else -1
        undoStack.offerLast(UndoEntry(charsCommitted, restore, expectedCursor))
    }

    private fun commitSmart(text: String, charsCommitted: Int) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        pushUndo(charsCommitted, "")
    }

    private fun pasteClip(clip: ClipboardStore.Clip) {
        endWord()
        if (clip.isImage) {
            val bytes = clips.imageBytes(clip)
            val file = bytes?.let { clips.writeTempImage(it, clip.mime) }
            if (file != null) {
                val committed = try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val desc = ClipDescription("image", arrayOf(clip.mime))
                    currentInputConnection?.commitContent(
                        InputContentInfo(uri, desc, null),
                        android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                        null
                    ) == true
                } catch (_: Exception) {
                    false
                }
                if (committed) {
                    Handler(Looper.getMainLooper()).postDelayed({ file.delete() }, 30_000L)
                    transitionToPanel(Panel.NONE)
                    afterText()
                    return
                }
                file.delete()
            }
            Toast.makeText(this, R.string.clip_image, Toast.LENGTH_SHORT).show()
        } else {
            commitSmart(clip.text, clip.text.length)
        }
        transitionToPanel(Panel.NONE)
        afterText()
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

    override fun onTouchSample(key: com.noircipher.shade.Key, x: Float, y: Float) {
        if (!insightsAllowed()) return
        try { touchBiasModel.record(key, x, y) } catch (_: Exception) {}
    }

    override fun onSwipePath(path: List<FloatArray>): Boolean {
        if (path.size < 4) return false
        val layout = kbKeyLayout() ?: return false
        suggestJob?.cancel()
        suggestJob = scope.launch(Dispatchers.Default) {
            delay(32)
            if (!isActive) return@launch
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

        // 4d — track keystroke timing for TypingSpeedAdapter (drives the
        // autocorrect-confidence-threshold adjustment).
        com.noircipher.shade.engine.TypingSpeedAdapter.onKeystroke()

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
                composing.isEmpty() && atSentenceEndBeforeCursor() &&
                !userShiftOverride
            if (sentenceEnd) {
                val upper = text.uppercase()
                lastCorrectionEvent = null
                composing.append(upper)
                ic.setComposingText(composing.toString(), 1)
                pushUndo(upper.length, text)
                appendTail(upper)
                afterText()
                return
            }

            lastCorrectionEvent = null
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
                        if (c == '.' || c == '!' || c == '?') {
                            resetBigramContext()
                            // A sentence has ended: allow auto-caps to apply
                            // again for the start of the next sentence, even
                            // if the user had pressed shift-off for this one.
                            userShiftOverride = false
                        }
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
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            // For horizontal: use sendDownUpKeyEvents (still the most reliable
            // way to move within text — setSelection with computed offsets is
            // fragile because we don't know the line structure).
            var i = 0
            while (i < dx) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT); i += 1 }
            while (i > dx) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT); i -= 1 }
            var j = 0
            while (j < dy) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN); j += 1 }
            while (j > dy) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP); j -= 1 }
        } finally {
            ic.endBatchEdit()
        }
        clearUndo()
        tailValid = false
        updateShiftFromCaps()
        updateSuggestions()
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
        val prev = shiftState
        shiftState = when {
            shiftState == 2 -> 0
            shiftState == 1 && now - lastShiftTime < 350 -> 2
            shiftState == 1 -> 0
            else -> 1
        }
        autoShifted = false
        lastShiftTime = now
        kb.shiftState = shiftState
        // Track explicit user intent for lowercase at an auto-caps position.
        // When the user turns shift OFF manually we set the override so neither
        // updateShiftFromCaps() nor the sentenceEnd smart-correction branch
        // capitalise the next letter over their choice. Turning shift back ON
        // (single or caps-lock) clears the override.
        if (shiftState == 0 && prev != 0) userShiftOverride = true
        else if (shiftState != 0) userShiftOverride = false
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (insightsAllowed()) typingStatsStore.recordBackspace()

        if (tryDeleteCorrectionSeparator()) return
        if (tryRevertLastCorrectionEvent()) return

        if (undoStack.isNotEmpty() && composing.isEmpty()) {
            val undo = undoStack.peekLast()
            // Only replay the undo if the caret still sits at the position the
            // original commit left it at. If the user has since moved the caret
            // elsewhere, the undo entry is stale and replaying it would delete
            // the wrong characters at the new caret position — fall through to a
            // plain KEYCODE_DEL at the caret instead. (cursorAtCommit < 0 means
            // we never recorded a position — accept the undo as before.)
            val caretMatches = undo == null ||
                undo.cursorAtCommit < 0 || undo.cursorAtCommit == cursorPos
            if (undo != null && caretMatches) {
                undoStack.pollLast()
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
            // If the user has moved the caret away from the end of the composing
            // region (e.g. tapped back into the middle of the word being typed),
            // deleting the last composing char and re-setting composing text would
            // snap the caret to the end and delete the wrong character. Instead,
            // finish composing and let a standard KEYCODE_DEL delete at the caret.
            val cursorAtComposingEnd = composingEnd < 0 || cursorPos < 0 || cursorPos == composingEnd
            if (cursorAtComposingEnd) {
                composing.deleteCharAt(composing.length - 1)
                if (composing.isEmpty()) ic.commitText("", 1)
                else ic.setComposingText(composing.toString(), 1)
                popTail(1)
            } else {
                ic.finishComposingText()
                composing.clear()
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                popTail(1)
            }
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
            val corrected = endWord(" ")

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
                    // Double-space produced a sentence end: allow auto-caps for
                    // the next sentence (mirrors the punctuation branch above).
                    userShiftOverride = false
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
            val originalWord = composing.toString()
            var w = originalWord
            val prevWords = contextWordsExcluding(originalWord)
            val prev1 = prevWords.lastOrNull()
            val prev2 = prevWords.dropLast(1).lastOrNull()

            // Lightweight live rewrite layer for common informal typed forms.
            if (smartCorrectionOn) {
                try {
                    val rewritten = rewriteManager.engine.rewrite(originalWord)
                    if (!rewritten.isNullOrBlank() && rewritten != originalWord && rewritten.length <= 60) {
                        w = rewritten
                        recordCorrectionEvent(originalWord, rewritten, sep)
                    }
                } catch (_: Exception) {
                    // Keep the user's original text if rewrite fails.
                }
            }

            if (insightsAllowed()) {
                typingStatsStore.recordCharacters(w.length, neuralLocale ?: "en")
                typingStatsStore.recordWord(originalWord)
            }

            if (smartCorrectionOn && canCompose && w == "i" && sep != null && isEnglishLocale()) {
                ic?.commitText("I", 1)
                popTail(1)
                appendTail("I")
                recordCorrectionEvent("i", "I", sep)
                composing.clear()
                return "I"
            }

            val shouldCorrect = sep != null && autocorrectOn && canCompose &&
                w.drop(1).none { it.isUpperCase() }

            // 4f — URL/email detection suppresses autocorrect mid-token so
            // "user@example" doesn't get "corrected" into a dictionary word.
            val beforeCursorForUrl = ic?.getTextBeforeCursor(50, 0)?.toString() ?: ""
            val urlDetection = UrlEmailDetector.detect(originalWord, beforeCursorForUrl)
            val suppressForUrl = UrlEmailDetector.suppressAutocorrect(urlDetection)

            // 4f — Slang words (gonna, lol, etc.) shouldn't be autocorrected.
            val isSlang = com.noircipher.shade.engine.SlangDetector.shouldNotAutocorrect(w)
            val effectiveShouldCorrect = shouldCorrect && !isSlang && !suppressForUrl

            // 4f — Abbreviation expansion (btw → by the way).
            val abbrevExpansion = if (effectiveShouldCorrect) {
                com.noircipher.shade.engine.AbbreviationExpander.expand(w)
            } else null

            // 4f — Multi-word correction (alot → a lot, aswell → as well, ...).
            val multiWordCorrection = if (effectiveShouldCorrect && abbrevExpansion == null) {
                com.noircipher.shade.engine.MultiWordCorrector.correct(w, isDictionaryWord = dict.contains(w))
            } else null

            val corrected = when {
                abbrevExpansion != null -> abbrevExpansion
                multiWordCorrection != null -> multiWordCorrection
                !effectiveShouldCorrect -> null
                else -> {
                    val candidate = try { dict.correction(w, isEnglishLocale()) } catch (_: Exception) { null }
                    if (candidate == null) null else {
                        // 4f — autocorrect confidence gate (#31 + A7 + #21).
                        // Combine the raw ranker score (if available) with a
                        // typing-speed-based threshold adjustment.
                        val effectiveRaw = lastRawRankerScores[candidate] ?: 0f
                        val speedAdjustment = com.noircipher.shade.engine.TypingSpeedAdapter.confidenceThresholdAdjustment()
                        val effectiveThreshold = (AUTOCORRECT_CONFIDENCE_THRESHOLD + speedAdjustment)
                            .coerceIn(0.5f, 0.99f)
                        if (autocorrectSuppressor.shouldSuppress(prev1, w, candidate)) null
                        else if (confidenceCalibrator.calibrated(effectiveRaw) >= effectiveThreshold) candidate
                        else null
                    }
                }
            }
            val finalWord = corrected ?: w

            if (corrected != null && corrected != w) {
                recordCorrectionEvent(w, corrected, sep)
                userWordMemory.bump(corrected)
            }

            popTail(originalWord.length)
            appendTail(finalWord)
            ic?.commitText(finalWord, 1)
            composing.clear()

            // 4g — Session bigram observation + rejection tracking.
            sessionBigramBoost.observe(finalWord)
            if (lastShownCandidates.isNotEmpty() && finalWord !in lastShownCandidates) {
                lastShownCandidates.take(3).forEach { suggested ->
                    if (suggested.isNotEmpty() && !suggested.equals(finalWord, ignoreCase = true)) {
                        rejectionTracker.recordRejection(suggested)
                    }
                }
            }
            if (personalLearningAllowed()) {
                val pos = sentencePositionScorer.currentPosition(contextWordsExcluding(finalWord))
                sentencePositionScorer.recordShown(pos)
            }

            if (learnOn && personalLearningAllowed() && canCompose) {
                dict.learn(finalWord, prev2 = prev2, prev1 = prev1)
            }
            if (adaptiveLearningAllowed()) {
                adaptiveWordMemory.record(finalWord)
            }
            if (appContextLearningAllowed()) {
                appContextModel.recordWord(finalWord)
            }
            return finalWord
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

    private fun isEnglishLocale(): Boolean =
        dictIds().split(',').firstOrNull()?.trim()?.equals("en", ignoreCase = true) == true

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
                // Respect an explicit user request for lowercase: if the user
                // pressed shift to turn auto-caps OFF, don't silently re-arm
                // shift on the next updateShiftFromCaps() call.
                if (caps != 0 && shiftState == 0 && !userShiftOverride) { shiftState = 1; autoShifted = true }
                else if (caps == 0 && autoShifted && shiftState == 1) { shiftState = 0; autoShifted = false }
            }
        }
        kb.shiftState = shiftState
    }

    // ------------------------------------------------------------------ suggestions (cascade)

    private suspend fun configureLocaleAssets(locale: String) {
        localeAssetsMutex.withLock {
            if (configuredLocale == locale) return@withLock
            configuredLocale = locale

            dict.configure(locale)
            emojiPredictor = EmojiPredictionEngine.load(this@ShadeService, locale)

            try { rankingScorer?.close() } catch (_: Exception) {}
            rankingScorer = try { TfliteCandidateRankingScorer(this@ShadeService) } catch (_: Exception) { null }

            try { contextEncoder?.close() } catch (_: Exception) {}
            contextEncoder = if (locale.contains("hi") || locale.contains("hg") || locale.contains("hinglish")) {
                try { TfliteContextEncoder(this@ShadeService, "ranking/context_encoder_hi_xa.tflite") } catch (_: Exception) { null }
            } else {
                null
            }

            reloadNeuralEngine(desiredNeuralLocale(emptyList(), locale))
        }
        warmUpNeuralEngines()
    }

    private fun warmUpNeuralEngines() {
        try { neural?.predictNext("", null, 1) } catch (_: Throwable) {}
        try { rankingScorer?.score(FloatArray(83)) } catch (_: Throwable) {}
        try { contextEncoder?.encode(0) } catch (_: Throwable) {}
    }

    private fun desiredNeuralLocale(contextWords: List<String>, localeList: String = dictIds()): String {
        val available = localeList.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.equals("hinglish", ignoreCase = true)) "hg" else it }
        val last = contextWords.lastOrNull() ?: return available.firstOrNull() ?: "en"

        val hasDevanagari = last.any { it in '\u0900'..'\u097F' }
        if (hasDevanagari && available.contains("hi")) return "hi"

        // For Roman-script multilingual typing, prefer a Hinglish model if enabled.
        if (available.contains("hg") && last.any { it.isLetter() }) return "hg"
        return available.firstOrNull() ?: "en"
    }

    private fun reloadNeuralEngine(locale: String) {
        synchronized(neuralLock) {
            if (neuralLocale == locale && neural != null) return
            val failedAt = failedNeuralLocales[locale]
            if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < NEURAL_FAILURE_TTL_MS) return
            failedNeuralLocales.remove(locale)

            val old = neural
            neural = null
            neuralLocale = null
            try { old?.close() } catch (_: Exception) {}

            val created = try {
                NeuralPredictionEngine.create(this@ShadeService, locale)
            } catch (_: Exception) {
                null
            }
            if (created != null) {
                created.enabled = neuralOn
                neural = created
                neuralLocale = locale
                failedNeuralLocales.remove(locale)
            } else {
                neuralLocale = null
                failedNeuralLocales[locale] = SystemClock.elapsedRealtime()
            }
        }
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
        userWordMemory.bump(cleaned)
        pushUndo(cleaned.length + 1, "")
        composing.clear()
        afterText()
    }

    private fun commitSlideWord(word: String) {
        endWord()
        currentInputConnection?.commitText("$word ", 1)
        appendTail("$word ")
        userWordMemory.bump(word)
        pushUndo(word.length + 1, "")
        composing.clear()
        afterText()
    }


    private fun contextWordsExcluding(typedWord: String): List<String> {
        val raw = currentInputConnection
            ?.getTextBeforeCursor(120, 0)
            ?.toString()
            ?: ""
        val context = if (
            typedWord.isNotEmpty() &&
            raw.endsWith(typedWord, ignoreCase = true)
        ) {
            raw.dropLast(typedWord.length)
        } else {
            raw
        }
        return Regex("[\\p{L}\\p{M}\\p{N}'_-]+")
            .findAll(context)
            .map { it.value.lowercase() }
            .toList()
    }

    private fun loadContextEncoderVocab(): Map<String, Int> {
        val map = HashMap<String, Int>()
        try {
            assets.open("languages/hi/vocab_hi.tsv").bufferedReader().useLines { lines ->
                var id = 0
                for (line in lines) {
                    if (id >= 32252) break
                    val parts = line.split('\t')
                    val token = parts.getOrNull(2)?.lowercase()
                    if (!token.isNullOrEmpty()) map[token] = id
                    id++
                }
            }
        } catch (_: Exception) {
            // Keep empty fallback.
        }
        return map
    }

    private fun contextEmbeddingForCurrentLocale(contextWords: List<String>): FloatArray? {
        val encoder = contextEncoder ?: return null
        val last = contextWords.lastOrNull()?.lowercase() ?: return null
        val idx = contextEncoderVocab[last] ?: return null
        return try { encoder.encode(idx) } catch (_: Exception) { null }
    }

    private fun learnedRankCandidates(
        candidates: List<String>,
        typedPrefix: String,
        touches: List<FloatArray>,
        layout: Map<Char, Key>?,
        contextWords: List<String>
    ): List<String> {
        val scorer = rankingScorer ?: return candidates
        if (candidates.size <= 1) return candidates

        val embedding = contextEmbeddingForCurrentLocale(contextWords)
        val contextText = contextWords.joinToString(" ")
        val neuralScores = neural
            ?.predictNext(contextText, typedPrefix, 12)
            ?.associate { it.first.lowercase() to it.second }
            .orEmpty()
        val prev1 = contextWords.lastOrNull()
        val prev2 = contextWords.dropLast(1).lastOrNull()
        val userBigramScores = dict.bigramModel
            ?.predict(prev2, prev1, 32)
            ?.associate { it.first.lowercase() to it.second }
            .orEmpty()
        val modelWeight = if (dictIds().split(',').firstOrNull()?.trim()?.equals("en", ignoreCase = true) == true) 0.32f else 0.12f

        val ranked = candidates.take(8).mapIndexed { index, candidate ->
            val base = 1f / (index + 1)
            val neural = neuralScores[candidate.lowercase()] ?: (base * 0.25f)
            val spatial = if (layout != null) SpatialScorer.score(candidate, touches, layout) else 0f
            val features = RankingFeatureExtractor.extract(
                candidate = candidate,
                typedPrefix = typedPrefix,
                baseRankScore = base,
                neuralScore = neural,
                spatialScore = spatial,
                touches = touches,
                layout = layout ?: emptyMap(),
                contextEmbedding = embedding,
                userUnigramScore = userWordMemory.score(candidate),
                userBigramScore = userBigramScores[candidate.lowercase()] ?: 0f
            )
            val modelScore = 0f
            candidate to (base to features)
        }

        val batchScores = runCatching {
            scorer.scoreBatch(ranked.map { it.second.second })
        }.getOrElse {
            FloatArray(ranked.size) { idx -> runCatching { scorer.score(ranked[idx].second.second) }.getOrDefault(0f) }
        }

        val newRankedFeatures = HashMap<String, FloatArray>(ranked.size)
        val newRawScores = HashMap<String, Float>(ranked.size)
        val rescored = ranked.mapIndexed { index, item ->
            val candidate = item.first
            val features = item.second.second
            val base = item.second.first
            val learnedBoost = adaptiveWordMemory.score(candidate) * 0.10f + appContextModel.score(candidate) * 0.08f
            // 4l — session bigram boost: tiny boost for candidates the user has
            // already used in this IME session, and for candidates that follow
            // the last context word in the session bigram.
            val sessionBoost = sessionBigramBoost.score(candidate) * 0.06f +
                sessionBigramBoost.bigramScore(contextWords.lastOrNull(), candidate) * 0.04f
            val adapted = onlineRankerTrainer.score(features)
            val raw = batchScores.getOrElse(index) { 0f }
            val calibrated = confidenceCalibrator.calibrated(raw)
            val modelScore = calibrated * 0.7f + adapted * 0.3f
            newRankedFeatures[candidate] = features
            newRawScores[candidate] = raw
            val combined = (1f - modelWeight) * base + modelWeight * modelScore + learnedBoost + sessionBoost
            candidate to combined
        }
        // Snapshot-then-putAll so readers see either old or new state,
        // never half-mutated maps.
        lastRankedFeatures.apply { clear(); putAll(newRankedFeatures) }
        lastRawRankerScores.apply { clear(); putAll(newRawScores) }
        lastShownCandidates = rescored.map { it.first }
        return rescored.sortedByDescending { it.second }.map { it.first } + candidates.drop(8)
    }

    private fun isSecureInputType(inputType: Int): Boolean {
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        return when (inputType and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_TEXT -> variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            EditorInfo.TYPE_CLASS_NUMBER -> variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private inline fun <T> safeSuggestion(fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun personalLearningAllowed(): Boolean =
        !incognito && !fieldNoPersonalizedLearning && !fieldSecureInput

    private fun adaptiveLearningAllowed(): Boolean =
        personalLearningAllowed() && learningAdaptiveOn
    private fun appContextLearningAllowed(): Boolean =
        personalLearningAllowed() && learningAppCtxOn
    private fun suppressorLearningAllowed(): Boolean =
        personalLearningAllowed() && learningSuppressorOn
    private fun calibratorLearningAllowed(): Boolean =
        personalLearningAllowed() && learningCalibratorOn
    private fun rankerLearningAllowed(): Boolean =
        personalLearningAllowed() && learningRankerOn

    private fun insightsAllowed(): Boolean =
        typingInsightsOn && !incognito && !fieldNoPersonalizedLearning

    private fun updateSuggestions() {
        if (this::slots.isInitialized == false) return
        suggestJob?.cancel()
        suggestDebounceJob?.cancel()
        if (suggestionsOn == false || suggestionsAllowed == false) {
            setCandidates(emptyList())
            return
        }
        // 4e — field-type gate: passwords, numbers, phone, datetime, and
        // explicitly-marked "no suggestions" fields suppress the bar entirely.
        if (!FieldTypeDetector.shouldShowSuggestions(currentFieldType)) {
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

        // 4e — URL / email / phone detection. If the user is mid-typing an
        // email or URL, suppress suggestions to avoid interrupting the flow.
        val beforeCursor = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString() ?: ""
        val urlDetection = UrlEmailDetector.detect(typedWord, beforeCursor)
        if (UrlEmailDetector.suppressSuggestions(urlDetection)) {
            setCandidates(emptyList())
            return
        }

        if (typedWord.isEmpty()) {
            val contextWords = contextWordsExcluding("")
            val desiredLocale = desiredNeuralLocale(contextWords)
            if (neuralLocale != desiredLocale) {
                scope.launch(Dispatchers.Default) { reloadNeuralEngine(desiredLocale) }
            }
            val prev1 = contextWords.lastOrNull()
            val prev2 = contextWords.dropLast(1).lastOrNull()
            val context = neuralContext("")

            suggestDebounceJob = scope.launch {
                delay(SUGGEST_DEBOUNCE_MS)
                if (!isActive) return@launch
                suggestJob = scope.launch(Dispatchers.Default) {
                    val neuralNext = withContext(Dispatchers.Default) {
                        neural
                            ?.predictNext(context, null, 6)
                            ?.map { it.first }
                            .orEmpty()
                    }
                    val dictionaryNext = prev1
                        ?.let { dict.predictNext(prev2, it, 8) }
                        .orEmpty()
                    val smartNext = HeuristicSuggestionEngine.smart(contextWords, null, 12)
                    val trigramNext = HeuristicSuggestionEngine.trigram(contextWords, null, 8)
                    val phraseNext = HeuristicSuggestionEngine.phrase(prev1, 6)
                    val grammarNext = GrammarEngine.predictNext(contextWords, 6)
                    val merged = CandidateRanker.mergeNextWord(
                        neural = smartNext + trigramNext + neuralNext + grammarNext + phraseNext,
                        dictionary = dictionaryNext,
                        limit = 3
                    )

                    val learned = safeSuggestion(merged) {
                        withContext(Dispatchers.Default) {
                            learnedRankCandidates(
                                candidates = merged,
                                typedPrefix = "",
                                touches = emptyList(),
                                layout = null,
                                contextWords = contextWords
                            )
                        }
                    }
                    val finalMerged = learned.ifEmpty { merged }
                    val list: List<String> = when {
                        finalMerged.isNotEmpty() -> centerBest(finalMerged)
                        atSentenceEndBeforeCursor() && isEnglishLocale() -> listOf("The", "I", "I'm")
                        else -> emptyList()
                    }
                    withContext(Dispatchers.Main.immediate) { setCandidates(list) }
                }
            }
            return
        }

        val prefix = typedWord
        val contextWords = contextWordsExcluding(typedWord)
        val desiredLocale = desiredNeuralLocale(contextWords)
        if (neuralLocale != desiredLocale) {
            scope.launch(Dispatchers.Default) { reloadNeuralEngine(desiredLocale) }
        }
        val prev1 = contextWords.lastOrNull()
        val prev2 = contextWords.dropLast(1).lastOrNull()
        val touches = if (composingActive) touchTrace.last(touchTrace.size()) else emptyList()
        val layout = kbKeyLayout()
        val neuralCtx = neuralContext(typedWord)

        suggestDebounceJob = scope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            if (!isActive) return@launch
            suggestJob = scope.launch(Dispatchers.Default) {
                // #11 — short-circuit on SuggestionCache hit when (prev2, prev1, prefix)
                // match a recently-computed result. Skips the whole pipeline.
                com.noircipher.shade.engine.SuggestionCache.get(prev2, prev1, prefix)?.let { cached ->
                    withContext(Dispatchers.Main.immediate) { setCandidates(centerBest(cached.take(3))) }
                    return@launch
                }
                val known = dict.contains(prefix)
                val stage1 = LinkedHashSet<String>()
                // Strict prefix rule: every suggestion must match every typed alphabet
                // after the last space.
                val effectivePrefix = prefix
                // 4k — keep the raw predictions (with scores) so we can inject
                // user vocabulary before flattening to strings.
                val rawPredictions = withContext(Dispatchers.Default) {
                    neural?.predictNext(neuralCtx, effectivePrefix, 24).orEmpty()
                }
                val neuralPrefixCandidates = if (effectivePrefix.isNotEmpty() && personalLearningAllowed()) {
                    val userWords = userWordMemory.snapshot()
                        .filterKeys { it.startsWith(effectivePrefix, ignoreCase = true) }
                    com.noircipher.shade.engine.UserVocabularyInjector.inject(
                        rawPredictions, userWords, effectivePrefix, 24
                    ).map { it.first }
                } else {
                    rawPredictions.map { it.first }
                }
                stage1.addAll(neuralPrefixCandidates)
                val grammarCandidates = GrammarEngine.suggest(contextWords, prefix, 6)
                val grammarTop = grammarCandidates.firstOrNull()
                val smartCandidates = HeuristicSuggestionEngine.smart(contextWords, effectivePrefix, 16)
                val trigramCandidates = HeuristicSuggestionEngine.trigram(contextWords, effectivePrefix, 12)
                val phraseCandidates = HeuristicSuggestionEngine.phrase(prev1, effectivePrefix, 12)
                val contextCandidates = prev1
                    ?.let { dict.predictNext(prev2, it, 48) }
                    .orEmpty()
                    .filter { it.startsWith(effectivePrefix, ignoreCase = true) }
                stage1.addAll(grammarCandidates)
                stage1.addAll(smartCandidates)
                stage1.addAll(trigramCandidates)
                stage1.addAll(phraseCandidates)
                stage1.addAll(contextCandidates)
                stage1.addAll(dict.suggestAsync(prefix, 48))

                // Enforce strict prefix matching even if a source injected a correction
                // or unrelated candidate.
                val strictStage1 = stage1.filter { it.startsWith(prefix, ignoreCase = true) }
                stage1.clear()
                stage1.addAll(strictStage1)

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

                var ranked = withContext(Dispatchers.Default) {
                    neural?.rerankPrefix(neuralCtx, effectivePrefix, stage3)
                } ?: stage3
                ranked = safeSuggestion(ranked) {
                    withContext(Dispatchers.Default) {
                        learnedRankCandidates(
                            candidates = ranked,
                            typedPrefix = prefix,
                            touches = touches,
                            layout = layout,
                            contextWords = contextWords
                        )
                    }
                }

                // 4j — typed-word pipeline injections.
                // #1 — Multi-word phrase completion (good→morning, thank→you, ...).
                run {
                    val phraseCompletions = com.noircipher.shade.engine.PhraseCompletion
                        .suggestPhraseCompletions(contextWords, prefix, limit = 3)
                    if (phraseCompletions.isNotEmpty()) {
                        ranked = (phraseCompletions + ranked).distinct()
                    }
                }
                // #15 — Smart compose for common responses (ok → ok sounds good, ...).
                if (FieldTypeDetector.shouldSmartCompose(currentFieldType)) {
                    val smartResponses = com.noircipher.shade.engine.SmartResponseComposer.suggest(prefix, limit = 2)
                    if (smartResponses.isNotEmpty()) {
                        ranked = (smartResponses + ranked).distinct()
                    }
                }
                // #6+#7 — Cross-sentence context + sentence starters.
                val extendedContext = com.noircipher.shade.engine.CrossSentenceContext.extendContext(contextWords)
                if (com.noircipher.shade.engine.CrossSentenceContext.isSentenceStart(extendedContext)) {
                    val starters = com.noircipher.shade.engine.CrossSentenceContext.sentenceStarters(prefix, limit = 2)
                    if (starters.isNotEmpty()) {
                        ranked = (starters + ranked).distinct()
                    }
                }
                // #18 — Greeting/time-aware bias.
                val timeWords = com.noircipher.shade.engine.GreetingTimeBias.timeAwareWords(prefix)
                if (timeWords.isNotEmpty()) {
                    ranked = (timeWords + ranked).distinct()
                }
                // #9 — Smart number prediction (after "in", "costs", "at", ...).
                val numberSuggestions = suggestNumbersForContext(extendedContext, prefix)
                if (numberSuggestions.isNotEmpty()) {
                    ranked = (numberSuggestions + ranked).distinct()
                }
                // #17 — Emoji context prediction (after "love", "happy", ...).
                val emojiSuggestions = com.noircipher.shade.engine.EmojiContextPredictor.suggest(prev1, limit = 2)
                if (emojiSuggestions.isNotEmpty()) {
                    ranked = (emojiSuggestions + ranked).distinct()
                }
                // #14 — Rejection-pattern learning: drop recently-rejected candidates.
                ranked = rejectionTracker.filter(ranked)
                // #5 — Prediction diversity (morphological): keep one variant per stem.
                ranked = com.noircipher.shade.engine.MorphologicalDiversity.deduplicate(ranked, limit = 6)
                // #20 — Per-app vocabulary snapshot.
                val appWords = perAppVocabSnapshot.wordsForPrefix(prefix, limit = 2)
                if (appWords.isNotEmpty()) {
                    ranked = (appWords + ranked).distinct()
                }
                // #22 — Confusable-pairs correction (their/there/they're, etc.).
                // Surface the context-corrected form when it isn't already ranked.
                com.noircipher.shade.engine.ConfusablePairs.correct(prefix, prev1)?.let { conf ->
                    if (ranked.none { it.equals(conf, ignoreCase = true) }) {
                        ranked = (listOf(conf) + ranked).distinct()
                    }
                }
                // #23 — Phonetic-match suggestions (for typo class that isn't a
                // keyboard slip but a pronunciation-based misspelling).
                if (ranked.size < 3) {
                    val phonetic = com.noircipher.shade.engine.PhoneticMatcher
                        .findPhoneticMatches(prefix, dict.suggest(prefix, 32), limit = 2)
                    if (phonetic.isNotEmpty()) {
                        ranked = (ranked + phonetic).distinct()
                    }
                }
                // #24 — Punctuation predictor: append a hint punctuation candidate
                // when the user has typed a recognized sentence ender.
                com.noircipher.shade.engine.PunctuationPredictor
                    .suggestPunctuation(contextWords)?.let { punct ->
                        if (ranked.none { it == punct }) {
                            ranked = (ranked + listOf(punct)).distinct().take(6)
                        }
                    }
                // #21 — Multi-word sentence completion ("How are you" → "doing today?").
                val sentenceCompletions = com.noircipher.shade.engine.SentenceCompleter
                    .suggest(contextWords, limit = 2)
                if (sentenceCompletions.isNotEmpty()) {
                    ranked = (sentenceCompletions + ranked).distinct()
                }
                // #11 — Suggestion cache: nothing to inject here, but record the
                // final ranked list so subsequent keystrokes can hit the cache.
                com.noircipher.shade.engine.SuggestionCache.put(prev2, prev1, prefix, ranked)
                // #32 — Multi-armed bandit exploration: occasionally swap top-2
                // to learn whether the runner-up would have been accepted.
                if (onlineRankerTrainer.shouldExplore() && ranked.size > 2) {
                    val mutable = ranked.toMutableList()
                    val tmp = mutable[0]
                    mutable[0] = mutable[1]
                    mutable[1] = tmp
                    ranked = mutable
                }

                val typedWordIsValid = dict.contains(prefix)
                val shown: List<String> = when {
                    grammarTop != null && !grammarTop.equals(prefix, ignoreCase = true) && !typedWordIsValid ->
                        (listOf(grammarTop) + ranked + listOf(prefix)).distinct().take(3)

                    !known && ranked.isNotEmpty() ->
                        listOf(prefix) + ranked.take(2)

                    else ->
                        centerBest(ranked.ifEmpty { listOf(prefix) }.take(3))
                }
                withContext(Dispatchers.Main.immediate) { setCandidates(shown) }
            }
        }
    }

    private fun centerBest(l: List<String>): List<String> = when (l.size) {
        0 -> l
        1 -> l
        2 -> listOf(l[1], l[0])
        else -> listOf(l[1], l[0], l[2])
    }

    /**
     * 4m — Smart number prediction. Surfaces short numeric completions after
     * words that commonly precede a quantity (in / costs / at / number / ...).
     *
     * Returns at most 2 candidates, all matching the typed prefix.
     */
    private fun suggestNumbersForContext(contextWords: List<String>, prefix: String): List<String> {
        if (prefix.isNotEmpty() && !prefix.all { it.isDigit() }) return emptyList()
        val last = contextWords.lastOrNull()?.lowercase() ?: return emptyList()
        return when (last) {
            "in" -> listOf("5", "10", "15", "20", "30").filter { it.startsWith(prefix) }.take(2)
            "costs", "cost", "price", "pay", "paid" -> if (prefix.isEmpty()) listOf("$") else emptyList()
            "at", "around", "about" -> listOf("5", "10", "20", "50").filter { it.startsWith(prefix) }.take(2)
            "number", "no." -> listOf("1", "2", "3").filter { it.startsWith(prefix) }.take(2)
            else -> emptyList()
        }
    }

    private fun setCandidates(list: List<String>) {
        // Clear the clipboard chip whenever real suggestions arrive, so it
        // doesn't reappear after the user starts typing. When the list is empty
        // and a chip is available, show the chip as the only candidate.
        if (list.isNotEmpty()) {
            lastClipText = null
        }
        val effective: List<String> =
            if (list.isEmpty() && lastClipText != null && !incognito &&
                !fieldNoPersonalizedLearning && !fieldSecureInput
            ) {
                listOf(lastClipText!!)
            } else {
                list
            }
        if (effective == candidates) return
        if (insightsAllowed() && effective.isNotEmpty()) typingStatsStore.recordSuggestionShown()
        // 4h — track global shown-count for the acceptance-rate adapter.
        if (personalLearningAllowed() && effective.isNotEmpty()) {
            acceptanceRateAdapter.recordShown()
        }
        if (calibratorLearningAllowed()) {
            effective.take(3).filter { it.isNotEmpty() }.forEach { w ->
                lastRawRankerScores[w]?.let { raw -> confidenceCalibrator.recordShown(raw) }
            }
        }
        val old = candidates
        candidates = effective
        slotAnimator?.cancel()

        var anyChanged = false
        for (i in 0 until 3) {
            val newWord = effective.getOrNull(i) ?: ""
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
        // If the picked candidate was the clipboard chip, clear it so it
        // doesn't reappear after the text is committed.
        if (lastClipText != null && w == lastClipText) lastClipText = null
        if (insightsAllowed()) typingStatsStore.recordSuggestionAccepted()
        // 4i — record acceptance for the acceptance-rate adapter + propagate
        // learning-rate multiplier to the online ranker; observe session
        // bigram; record sentence-position acceptance.
        if (personalLearningAllowed()) {
            acceptanceRateAdapter.recordAccepted()
            onlineRankerTrainer.setLearningRateMultiplier(acceptanceRateAdapter.learningRateMultiplier())
        }
        sessionBigramBoost.observe(w)
        val pos = sentencePositionScorer.currentPosition(contextWordsExcluding(w))
        if (personalLearningAllowed()) {
            sentencePositionScorer.recordAcceptance(pos)
        }
        val prevWords = contextWordsExcluding(w)
        val prev1 = prevWords.lastOrNull()
        val prev2 = prevWords.dropLast(1).lastOrNull()
        val typed = composing.toString().ifEmpty { currentTypedWordFromField() }

        if (learnOn && personalLearningAllowed()) {
            userWordMemory.bump(w)
        }
        if (adaptiveLearningAllowed()) {
            adaptiveWordMemory.record(w)
        }
        if (appContextLearningAllowed()) {
            appContextModel.recordWord(w)
        }
        if (rankerLearningAllowed() || calibratorLearningAllowed()) {
            lastRankedFeatures[w]?.let { positive ->
                if (rankerLearningAllowed()) {
                    val negatives = lastShownCandidates
                        .filterNot { it.equals(w, ignoreCase = true) }
                        .mapNotNull { lastRankedFeatures[it] }
                    onlineRankerTrainer.adapt(positive, negatives)
                }
                if (calibratorLearningAllowed()) {
                    lastRawRankerScores[w]?.let { raw -> confidenceCalibrator.recordAcceptance(raw) }
                }
            }
        }

        val ic = currentInputConnection ?: return
        clearUndo()
        ic.beginBatchEdit()
        try {
            if (composing.isNotEmpty()) {
                composing.clear()
                ic.commitText(w, 1)
            } else {
                if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
                ic.commitText(w, 1)
            }
            if (typed.isNotEmpty() && tailValid && tail.endsWith(typed, ignoreCase = true)) {
                popTail(typed.length)
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
            if (learnOn && personalLearningAllowed() && canCompose) {
                dict.learn(w, prev2 = prev2, prev1 = prev1)
            }
        }
        lastSpaceTime = 0L
        afterText()
    }

    companion object {
        private const val MAX_UNDO = 10
        private const val NEURAL_FAILURE_TTL_MS = 5L * 60L * 1000L
        private const val SUGGEST_DEBOUNCE_MS = 32L
        // 4o — autocorrect confidence threshold. Combined with the typing-speed
        // adjustment in [endWord] to gate aggressive autocorrects.
        private const val AUTOCORRECT_CONFIDENCE_THRESHOLD = 0.85f
    }
}
