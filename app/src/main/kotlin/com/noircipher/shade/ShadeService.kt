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
import android.os.Bundle
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
import androidx.core.view.inputmethod.EditorInfoCompat
import com.noircipher.shade.gif.GifController
import com.noircipher.shade.translate.TranslationService
import com.noircipher.shade.gif.GifItem
import com.noircipher.shade.gif.KlipyConfig
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
import com.noircipher.shade.engine.NeuralSpellChecker
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
        "clip" to "📋", "emoji" to "😀", "gif" to "GIF", "translate" to "🌐", "numpad" to "123", "left" to "◀", "right" to "▶",
        "selectall" to "All", "copy" to "Copy", "cut" to "Cut", "paste" to "Paste",
        "undo" to "Undo", "incognito" to "🕶", "onehand" to "1-hand",
        "resize" to "Resize", "voice" to "🎤", "settings" to "⚙"
    )
    private val toolIcons = mapOf(
        "clip" to R.drawable.ic_tb_clip, "emoji" to R.drawable.ic_tb_emoji, "gif" to R.drawable.ic_tb_gif, "translate" to R.drawable.ic_tb_translate,
        "numpad" to R.drawable.ic_tb_numpad, "left" to R.drawable.ic_tb_left,
        "right" to R.drawable.ic_tb_right, "selectall" to R.drawable.ic_tb_selectall,
        "copy" to R.drawable.ic_tb_copy, "cut" to R.drawable.ic_tb_cut,
        "paste" to R.drawable.ic_tb_paste, "undo" to R.drawable.ic_tb_undo,
        "incognito" to R.drawable.ic_tb_incognito, "onehand" to R.drawable.ic_tb_onehand,
        "resize" to R.drawable.ic_tb_resize, "voice" to R.drawable.ic_tb_mic, "settings" to R.drawable.ic_tb_settings
    )
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private lateinit var dict: WordDictionary
    private lateinit var clips: ClipboardStore
    private lateinit var cm: ClipboardManager
    private lateinit var root: LinearLayout
    // Transparent, touch-through band above the keyboard. Long-press accent panels have two rows
    // and are taller than the suggestion strip, so on the top key row they need room above the
    // keyboard. The band is excluded from the insets (see onComputeInsets), so apps are not
    // resized for it and touches on it reach the app underneath.
    private lateinit var headroom: View
    // GIF picker (KLIPY): a compact bar above the keyboard. While it is open, keystrokes edit its
    // search query instead of the text field.
    private lateinit var gifController: GifController
    private var gifActive = false
    // Translator
    private var translatorActive = false
    private var translatorInput = StringBuilder()
    private var translatorTarget = "en"
    private lateinit var strip: LinearLayout
    private lateinit var toggle: TextView
    // floating drag handle above the keyboard. Dragging vertically
    // resizes the keyboard (adjusts key_height live). Long-pressing then
    // dragging moves the keyboard up from the bottom (floating offset).
    private lateinit var resizeHandle: View
    private lateinit var resizeOverlay: FrameLayout
    private lateinit var autofillScroll: HorizontalScrollView
    private lateinit var autofillRow: LinearLayout
    private var autofillCount = 0
    private var autofillGen = 0
    private var autofillHiddenByTyping = false
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
    private val composing = StringBuilder()
    private var canCompose = true
    private var suggestionsAllowed = true
    private var suggestionsOn = true
    private var neuralOn = true
    @Volatile
    private var neural: LanguageModelEngine? = null

    // On-device neural spell checker (loaded off the main thread; null while loading or
    // on devices where the models failed to initialize).
    private var neuralSpell: NeuralSpellChecker? = null
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
    private val typingStatsStore: TypingStatsStore by lazy { LiveStores.get { TypingStatsStore(applicationContext) } }
    private val adaptiveWordMemory: AdaptiveWordMemory by lazy { LiveStores.get { AdaptiveWordMemory(applicationContext) } }
    private val appContextModel: AppContextModel by lazy { LiveStores.get { AppContextModel(applicationContext) } }
    private val autocorrectSuppressor: AutocorrectSuppressor by lazy { LiveStores.get { AutocorrectSuppressor(applicationContext) } }
    private val confidenceCalibrator: ConfidenceCalibrator by lazy { LiveStores.get { ConfidenceCalibrator(applicationContext) } }
    private val onlineRankerTrainer: OnlineRankerTrainer by lazy { LiveStores.get { OnlineRankerTrainer(applicationContext) } }
    private val touchBiasModel: TouchBiasModel by lazy { LiveStores.get { TouchBiasModel(applicationContext) } }
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
    // When false (default) the suggestion strip only ever shows single-word
    // candidates — multi-word phrase completions / smart responses / sentence
    // completions are suppressed, and any space-containing candidate coming out
    // of the neural / heuristic pipeline is filtered out. Matches the
    private var phraseSuggestionsOn = false
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
    // Vertical floating offset in pixels — how far the keyboard is lifted from
    // the bottom of the screen. 0 = docked to bottom (default). Positive
    // values float the keyboard up so the user can position it wherever is
    // comfortable, like a floating keyboard.
    private var keyboardOffsetY = 0f
    // Raw suggestions as produced by the engines (usually lowercase). What the user sees is
    // `shownWords`: the same list re-cased to match the keyboard state (see SuggestionCasing).
    private var candidates: List<String> = emptyList()
    private var shownWords: List<String> = emptyList()
    private var info: EditorInfo? = null
    private var bgBmp: Bitmap? = null
    private var bgStamp = -1L
    private var lastAppliedInputType = -1

    private var pendingDeleteWords = 0
    private var deletePreviewActive = false

    private val touchTrace = TouchTrace(16)
    private var lastWordTouches: List<FloatArray> = emptyList()
    // Touch geometry offsets for the word currently being typed
    private var currentTouchOffsets: List<TouchGeometry.KeyTouch> = emptyList()

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
    /** Live preview decoding for glide typing (debounced; cancelled on commit/clear). */
    private var glidePreviewJob: Job? = null
    private var slotAnimator: ValueAnimator? = null
    private var panelSpring: SpringAnimation? = null

    private var lastOrientation = -1

    // 4b — Field-type classification for the current input field. Drives
    // gating decisions in updateSuggestions / endWord.
    private var currentFieldType: FieldTypeDetector.FieldType = FieldTypeDetector.FieldType.NORMAL_TEXT

    /**
     * Known messaging apps that get special treatment (more emoji suggestions,
     * looser autocorrect, chat-style abbreviations). Mirrors Gboard's per-app
     * package list. Detected from [EditorInfo.packageName] in [onStartInputView].
     */
    private val messagingApps = setOf(
        "com.google.android.apps.messaging",
        "com.facebook.orca",
        "com.facebook.katana",
        "com.verizon.messaging.vzmsgs",
        "com.snapchat.android",
        "com.discord",
        "com.motorola.messaging",
        "com.instagram.android",
        "com.whatsapp",
        "com.samsung.android.messaging",
        "com.android.messaging",
        "com.tencent.mm"  // WeChat
    )
    private var isInMessagingApp = false

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
        PrefsMigration.run(prefs)
        dict = WordDictionary(this)
        voice = VoiceInputController(this) { text -> commitVoiceText(text) }
        clips = ClipboardStore(this)
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try { cm.addPrimaryClipChangedListener(clipListener) } catch (_: Exception) {}

        val locale = dictIds()
        // Heavy disk/Keystore work stays off the main thread so the keyboard can
        // appear immediately; until it finishes the features simply skip the model.
        scope.launch(Dispatchers.Default) {
            try { dict.bigramModel = BigramModel(this@ShadeService) } catch (_: Throwable) {}
            try { clips.cleanupExpired() } catch (_: Throwable) {}
        }
        scope.launch(Dispatchers.Default) {
            try { configureLocaleAssets(locale) } catch (t: Throwable) {
                // The IME must never die from a background asset/model load; the features that
                // depend on this simply stay disabled until the next configure.
                Log.w("ShadeService", "configureLocaleAssets failed", t)
            }
        }
        scope.launch(Dispatchers.Default) {
            // Neural spell checker: mmap + a warm-up decode page the models in so the
            // first real correction on the commit path stays within its latency budget.
            try { neuralSpell = NeuralSpellChecker(this@ShadeService).also { it.warmUp() } }
            catch (t: Throwable) { Log.w("ShadeService", "neural spell checker unavailable", t) }
        }
    }

    override fun onDestroy() {
        try { cm.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        dict.shutdown() // flushes learned words + n-grams on a background thread
        SystemSpell.close() // close the system spell checker session
        try { neuralSpell?.close() } catch (_: Throwable) {}
        neuralSpell = null
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
        resetCandidates()
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
        // Autofill chips (saved passwords, addresses, cards) sit on top of the suggestion strip,
        // until the user starts typing.
        autofillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
        }
        autofillScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(autofillRow, FrameLayout.LayoutParams(WRAP, MATCH))
        }
        val frame = FrameLayout(this).apply {
            addView(slotFrame, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(toolScroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(autofillScroll, FrameLayout.LayoutParams(MATCH, MATCH))
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

        resizeOverlay = buildResizeOverlay()
        val content = FrameLayout(this).apply {
            addView(kb, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emoji, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(clipPanel, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(resizeOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        // Resize/float handle — hidden by default (standard behavior).
        // Only shown when the user enters "resize mode" from Settings →
        // Appearance → Resize keyboard. When visible, the user drags it to
        // resize the keyboard live. Long-press then drag to float the keyboard.
        // The handle auto-hides after the user finishes dragging.
        resizeHandle = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(0x11000000)
            }
            isClickable = true
            isFocusable = false
            visibility = View.GONE  // hidden by default
            val grip = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2f).toFloat()
                    setColor(0x44888888)
                }
                val gripSize = dp(4f).toInt()
                val gripWidth = dp(40f).toInt()
                layoutParams = android.widget.FrameLayout.LayoutParams(gripWidth, gripSize).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(grip)
            setOnTouchListener(ResizeHandleTouchListener())
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(MATCH, dp(52f).toInt()))
            addView(resizeHandle, LinearLayout.LayoutParams(MATCH, dp(22f).toInt()))
            addView(content, LinearLayout.LayoutParams(MATCH, dp(200f).toInt()))
        }

        gifController = GifController(
            context = this, scope = scope, prefs = prefs,
            onClose = { closeGif() },
            onSend = { item, query -> sendGif(item, query) }
        )
        gifController.bar.visibility = View.GONE
        root.addView(gifController.bar, 2, LinearLayout.LayoutParams(MATCH, WRAP))

        // [root] (opaque keyboard background, side/nav-bar padding, float offset) is wrapped so a
        // transparent headroom band can sit above it for the accent panel.
        headroom = View(this).apply { visibility = if (keyboardOffsetY == 0f) View.VISIBLE else View.GONE }
        val windowRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false   // keep the float-offset translation of [root] unclipped, as before
            addView(headroom, LinearLayout.LayoutParams(MATCH, dp(ACCENT_HEADROOM_DP).toInt()))
            addView(root, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // The accent panel dims the whole keyboard container, not the app above it.
        kb.popupScrimAnchor = root

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            navBarBottomPx = b
            v.setPadding(kbInsetLeftPx, 0, kbInsetRightPx, b)
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

        return windowRoot
    }

    /**
     * The headroom band above the keyboard is transparent and must neither resize the app nor eat
     * its touches, so both top insets are moved down by its height.
     */
    override fun onComputeInsets(outInsets: android.inputmethodservice.InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        if (!this::headroom.isInitialized || !isInputViewShown) return
        val h = if (headroom.visibility == View.VISIBLE) headroom.height else 0
        if (h > 0) {
            outInsets.contentTopInsets += h
            outInsets.visibleTopInsets += h
        }
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
                    if (imeVisible && keyboardOffsetY == 0f) {
                        // Only apply the IME inset translation when the keyboard
                        // is docked (not floating). When floating, the root's
                        // translationY already positions the keyboard and the
                        // inset animation would fight it.
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
        closeGif()
        closeTranslator()
        info = editorInfo
        fieldNoPersonalizedLearning =
            (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        fieldSecureInput = isSecureInputType(editorInfo.inputType)
        incognito = prefs.getBoolean("incognito", false) || fieldNoPersonalizedLearning || fieldSecureInput
        // 4c — classify the active field so suggestion / autocorrect / smart-compose
        // pipelines can be gated by field type (password / email / URL / etc.).
        currentFieldType = FieldTypeDetector.detect(editorInfo)
        // Load Android's Personal Dictionary (Settings → Personal dictionary)
        // so words the user added system-wide are recognized and not corrected.
        if (!incognito) dict.loadPersonalDictionary(this)
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
        lastSpaceTime = 0L
        shiftState = 0
        autoShifted = false
        userShiftOverride = false
        toolbarOpen = prefs.getBoolean("toolbar_open_default", false)
        pendingDeleteWords = 0
        deletePreviewActive = false
        // Detect known messaging apps for per-app behavior (Gboard-style).
        isInMessagingApp = (editorInfo.packageName in messagingApps)
        touchTrace.clear()
        lastWordTouches = emptyList()

        mode = Mode.LETTERS

        val type = editorInfo.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION

        val passwordField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        // Email addresses are typed verbatim (no composing, no autocorrect) but, unlike passwords,
        // still get the suggestion bar (this used to be lumped in with passwords and lost it).
        val emailField = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS

        val searchField = variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
            (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        val isTextField = cls == InputType.TYPE_CLASS_TEXT

        canCompose = isTextField && (passwordField == false) && (searchField == false) && (emailField == false)
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

        // Check if the user requested resize mode from Settings → Appearance →
        // Resize keyboard. If so, show the drag handle; it auto-hides after
        // the user finishes dragging (standard behavior).
        if (prefs.getBoolean("resize_mode_requested", false)) {
            prefs.edit().putBoolean("resize_mode_requested", false).apply()
            setResizeMode(true)
        }

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
        closeGif()
        closeTranslator()
        clearAutofillChips()
        try { voice?.cancel() } catch (_: Exception) {} // a late result must not land in the next field
        suggestJob?.cancel()
        suggestDebounceJob?.cancel()
        correctionJob?.cancel()
        info = null
        resetCandidates()
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
        try { voice?.cancel() } catch (_: Exception) {}
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
                try { dict.flushUser() } catch (t: Throwable) { Log.e("ShadeService", "Failed to save learned words", t) }
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
        phraseSuggestionsOn = prefs.getBoolean("phrase_suggestions", false)
        incognito = prefs.getBoolean("incognito", false) || fieldNoPersonalizedLearning || fieldSecureInput
        layoutName = prefs.getString("layout", "qwerty") ?: "qwerty"
        kb.trackpadEnabled = prefs.getBoolean("trackpad_mode", true)
        kb.glideEnabled = prefs.getBoolean("glide_typing", true) && !fieldSecureInput
        kb.typingBurstEnabled = prefs.getBoolean("typing_burst", false)
        kb.longPressMs = prefs.getInt("longpress_delay", 300).toLong()
        kb.repeatDelayMs = prefs.getInt("repeat_delay", 350).toLong()
        kb.repeatIntervalMs = prefs.getInt("repeat_interval", 40).toLong()

        val locale = dictIds()
        scope.launch(Dispatchers.Default) {
            try { configureLocaleAssets(locale) } catch (t: Throwable) {
                Log.w("ShadeService", "configureLocaleAssets failed", t)
            }
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
        kb.hapticStrength = when (prefs.getString("haptic_strength", "medium")) {
            "light" -> 0
            "strong" -> 2
            else -> 1
        }
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
        if (this::gifController.isInitialized) gifController.bar.setTheme(kbTheme)

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

        // Restore the saved floating offset (if the user previously undocked
        // the keyboard). Called after layout so translationY is applied to
        // the final measured view.
        keyboardOffsetY = PrefsMigration.readOffsetY(prefs)
        loadSideInsets()
        root.post { applyKeyboardOffset() }

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
            if (id == "gif" && !gifAvailable()) continue
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
        if (p != Panel.NONE) closeGif()
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
            // Hide the suggestion strip and resize handle when a panel (emoji/clip)
            // is open — they're not useful while browsing emoji, and showing them
            // creates a dead strip of wasted space above the panel.
            strip.visibility = if (p == Panel.NONE) View.VISIBLE else View.GONE
            if (this::resizeHandle.isInitialized) {
                resizeHandle.visibility = if (p == Panel.NONE && resizeModeActive) View.VISIBLE else View.GONE
            }
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
        // Hide the suggestion strip and resize handle when a panel is open.
        strip.visibility = if (p == Panel.NONE) View.VISIBLE else View.GONE
        if (this::resizeHandle.isInitialized) {
            resizeHandle.visibility = if (p == Panel.NONE && resizeModeActive) View.VISIBLE else View.GONE
        }
        if (p == Panel.CLIP) clipPanel.refresh()
        refreshStrip()
    }

    // ------------------------------------------------------------------ GIFs (KLIPY)

    private fun gifAvailable(): Boolean = KlipyConfig.isConfigured && prefs.getBoolean("gif_enabled", true)

    private fun openGif() {
        if (!this::gifController.isInitialized || !gifAvailable()) return
        if (fieldSecureInput || currentFieldType == FieldTypeDetector.FieldType.PASSWORD) {
            Toast.makeText(this, R.string.gif_blocked_secure, Toast.LENGTH_SHORT).show()
            return
        }
        endWord()
        if (panel != Panel.NONE) transitionToPanel(Panel.NONE, animate = false)
        gifActive = true
        strip.visibility = View.GONE
        gifController.bar.visibility = View.VISIBLE
        gifController.open()
    }

    private fun closeGif() {
        if (!gifActive) return
        gifActive = false
        gifController.close()
        gifController.bar.visibility = View.GONE
        if (panel == Panel.NONE) strip.visibility = View.VISIBLE
    }

    private fun sendGif(item: GifItem, query: String) {
        val mime = item.send.mime
        val editor = info
        val accepts = editor != null &&
            EditorInfoCompat.getContentMimeTypes(editor).any { ClipDescription.compareMimeTypes(mime, it) }
        if (!accepts) { sendGifAsLink(item, query); return }
        scope.launch {
            val file = gifController.downloadForSend(item)
            if (file == null) {
                Toast.makeText(this@ShadeService, R.string.gif_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val committed = try {
                val uri = FileProvider.getUriForFile(this@ShadeService, "$packageName.fileprovider", file)
                val desc = ClipDescription(item.title.ifBlank { "GIF" }, arrayOf(mime))
                currentInputConnection?.commitContent(
                    InputContentInfo(uri, desc, null),
                    android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                ) == true
            } catch (_: Exception) { false }
            if (committed) {
                Handler(Looper.getMainLooper()).postDelayed({ file.delete() }, 60_000L)
                gifController.registerShare(item, query)
                closeGif()
                afterText()
            } else { file.delete(); sendGifAsLink(item, query) }
        }
    }

    private fun sendGifAsLink(item: GifItem, query: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(item.send.url + " ", 1)
        gifController.registerShare(item, query)
        Toast.makeText(this, R.string.gif_pasted_link, Toast.LENGTH_SHORT).show()
        closeGif()
        afterText()
    }

    // ------------------------------------------------------------------ Translator

    private fun openTranslator() {
        if (!prefs.getBoolean("translator_enabled", true)) return
        if (fieldSecureInput || currentFieldType == FieldTypeDetector.FieldType.PASSWORD) return
        endWord()
        if (panel != Panel.NONE) transitionToPanel(Panel.NONE, animate = false)
        if (gifActive) closeGif()
        translatorActive = true
        translatorInput.clear()
        strip.visibility = View.GONE
        Toast.makeText(this, R.string.translator_hint, Toast.LENGTH_SHORT).show()
    }

    private fun closeTranslator() {
        if (!translatorActive) return
        translatorActive = false
        translatorInput.clear()
        if (panel == Panel.NONE) strip.visibility = View.VISIBLE
    }

    private fun onTranslatorKey(code: Int, text: String?) {
        when (code) {
            Codes.DELETE -> { if (translatorInput.isNotEmpty()) translatorInput.deleteCharAt(translatorInput.length - 1) }
            Codes.SPACE -> translatorInput.append(' ')
            Codes.ENTER -> {
                val query = translatorInput.toString().trim()
                if (query.isNotEmpty()) {
                    scope.launch {
                        val result = TranslationService.translate(query, "auto", translatorTarget)
                        if (result != null) {
                            currentInputConnection?.commitText(result + " ", 1)
                        } else {
                            Toast.makeText(this@ShadeService, R.string.translator_error, Toast.LENGTH_SHORT).show()
                        }
                        closeTranslator()
                    }
                } else closeTranslator()
            }
            Codes.SHIFT, Codes.SYMBOLS, Codes.SYMBOLS2, Codes.LETTERS -> { /* mode keys work normally */ }
            else -> { text?.let { translatorInput.append(it) } }
        }
    }

    // ------------------------------------------------------------------ toolbar

    private fun onTool(id: String) {
        val ic = currentInputConnection
        when (id) {
            "clip" -> transitionToPanel(Panel.CLIP)
            "emoji" -> transitionToPanel(Panel.EMOJI)
            "gif" -> openGif()
            "translate" -> openTranslator()
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
            "resize" -> setResizeMode(true)
            "settings" -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        updateShiftFromCaps()
        updateSuggestions()
    }

    // ------------------------------------------------------------------ autofill chips

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): android.view.inputmethod.InlineSuggestionsRequest? {
        if (!prefs.getBoolean("autofill_chips", true)) return null
        val style = androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder().build()
        val styles = androidx.autofill.inline.UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = android.widget.inline.InlinePresentationSpec.Builder(
            android.util.Size(dp(100f).toInt(), dp(40f).toInt()),
            android.util.Size(dp(320f).toInt(), dp(48f).toInt())
        ).setStyle(styles).build()
        return android.view.inputmethod.InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(6)
            .build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        if (this::autofillRow.isInitialized == false) return false
        val suggestions = response.inlineSuggestions
        val gen = ++autofillGen
        autofillRow.removeAllViews()
        autofillCount = 0
        if (suggestions.isEmpty()) {
            updateAutofillStrip()
            return false
        }
        autofillHiddenByTyping = false
        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val views = arrayOfNulls<View>(suggestions.size)
        var pending = suggestions.size
        suggestions.forEachIndexed { i, suggestion ->
            suggestion.inflate(this, android.util.Size(WRAP, WRAP), executor) { v ->
                if (gen != autofillGen) return@inflate // a newer response replaced this one
                views[i] = v
                pending--
                if (pending == 0) {
                    for (view in views) {
                        if (view == null) continue
                        autofillRow.addView(view, LinearLayout.LayoutParams(WRAP, MATCH).apply {
                            marginEnd = dp(6f).toInt()
                        })
                        autofillCount++
                    }
                    updateAutofillStrip()
                }
            }
        }
        return true
    }

    private fun updateAutofillStrip() {
        if (this::autofillScroll.isInitialized == false) return
        val show = autofillCount > 0 && !autofillHiddenByTyping
        if (show) autofillScroll.setBackgroundColor(kbTheme.bg)
        autofillScroll.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun clearAutofillChips() {
        autofillGen++
        autofillCount = 0
        autofillHiddenByTyping = false
        if (this::autofillRow.isInitialized) autofillRow.removeAllViews()
        updateAutofillStrip()
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

    // ------------------------------------------------------------------ resize & float

    /**
     * Touch listener for the drag handle above the keyboard.
     *
     * Two modes (as in standard floating-keyboard mode):
     *  - **Short drag**: resizes the keyboard vertically. Dragging up
     *    shrinks the keyboard, dragging down enlarges it. The new height
     *    is persisted to `key_height` and applied live.
     *  - **Long-press then drag**: moves the keyboard up from the bottom
     *    (floating / undocked mode). The offset is persisted to
     *    `keyboard_offset_y` and applied as a translationY on the root view.
     *
     * The mode is determined by whether the user long-pressed (≥400ms)
     * before moving, or started moving immediately.
     */
    private inner class ResizeHandleTouchListener : View.OnTouchListener {
        private var downY = 0f
        private var downTime = 0L
        private var startKeyHeight = 50
        private var startOffsetY = 0f
        private var isFloatMode = false
        private var longPressFired = false
        private val longPressTimeout = 400L
        private val longPressCheck = Handler(Looper.getMainLooper())
        private val longPressRunnable = Runnable {
            longPressFired = true
            isFloatMode = true
            kb.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    downTime = SystemClock.uptimeMillis()
                    startKeyHeight = prefs.getInt("key_height", 50)
                    startOffsetY = PrefsMigration.readOffsetY(prefs)
                    isFloatMode = false
                    longPressFired = false
                    longPressCheck.postDelayed(longPressRunnable, longPressTimeout)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY  // positive = finger moved down
                    if (!longPressFired && kotlin.math.abs(dy) > dp(10f)) {
                        // User started moving before long-press fired — treat
                        // as resize, not float.
                        longPressCheck.removeCallbacks(longPressRunnable)
                        isFloatMode = false
                    }
                    if (isFloatMode) {
                        // Floating: dragging UP (dy < 0) moves keyboard UP.
                        // Clamp to [0, screen_height * 0.5] so it can't go
                        // off-screen or more than half-way up.
                        val newOffset = (startOffsetY - dy).coerceAtLeast(0f)
                        val maxOffset = resources.displayMetrics.heightPixels * 0.5f
                        val clamped = newOffset.coerceAtMost(maxOffset)
                        keyboardOffsetY = clamped
                        applyKeyboardOffset()
                    } else {
                        // Resize: dragging UP (dy < 0) makes keyboard TALLER.
                        // Each dp of drag = ~0.5 dp of key height change.
                        val heightDelta = (-dy / dp(2f)).toInt()
                        val newHeight = (startKeyHeight + heightDelta)
                            .coerceIn(36, 80)
                        if (newHeight != prefs.getInt("key_height", 50)) {
                            prefs.edit().putInt("key_height", newHeight).apply()
                            // Apply live without full applyPrefs() (which reloads locale assets)
                            applyKeyHeight()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressCheck.removeCallbacks(longPressRunnable)
                    // Persist the floating offset
                    if (isFloatMode) {
                        PrefsMigration.writeOffsetY(prefs, keyboardOffsetY)
                    }
                    // Haptic feedback on release
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    // After the user finishes dragging, the
                    // resize handle auto-hides. The keyboard stays at the new
                    // size; the handle only reappears if the user re-enters
                    // resize mode from settings.
                    // The handles stay up until the user taps Done, so the size can be fine-tuned
                    // in several drags (width handles on the sides, height handle on top).
                    return true
                }
            }
            return false
        }
    }

    /** Applies the current [keyboardOffsetY] as a translation on the root view. */
    private fun applyKeyboardOffset() {
        if (this::root.isInitialized == false) return
        // Negative translationY moves the view UP, so we negate the offset.
        root.translationY = -keyboardOffsetY
        // A floating (offset) keyboard has no room-above semantics; drop the band so it can't
        // end up covering the keyboard's touchable area.
        if (this::headroom.isInitialized) {
            val want = if (keyboardOffsetY == 0f) View.VISIBLE else View.GONE
            if (headroom.visibility != want) headroom.visibility = want
        }
    }

    /**
     * Applies the current `key_height` preference to the keyboard, emoji panel,
     * and clip panel — without the full locale-asset reload that applyPrefs()
     * triggers. Called from the resize drag handler for live resizing.
     */
    private fun applyKeyHeight() {
        if (this::kb.isInitialized == false) return
        val keyH = prefs.getInt("key_height", 50)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val factor = if (landscape) 0.65f else 1f
        val contentH = (dp(keyH.toFloat()) * (if (numberRow) 4.7f else 4f) * factor).toInt()
        kb.contentHeightPx = contentH
        (kb.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        if (this::emoji.isInitialized) {
            (emoji.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        }
        if (this::clipPanel.isInitialized) {
            (clipPanel.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        }
        kb.requestLayout()
    }

    /**
     * Toggles the keyboard's "resize mode" — as in standard floating-keyboard mode's resize settings.
     *
     * When active, the drag handle appears above the keyboard so the user can
     * drag it to resize live. When deactivated, the handle disappears and the
     * keyboard returns to normal typing mode.
     *
     * Called from SettingsActivity via the "Resize keyboard" preference, which
     * sends an intent that the IME picks up.
     */
    fun setResizeMode(active: Boolean) {
        if (this::resizeHandle.isInitialized == false) return
        resizeModeActive = active
        resizeHandle.visibility = if (active) View.VISIBLE else View.GONE
        if (this::resizeOverlay.isInitialized) resizeOverlay.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            // Show a toast hint so the user knows what to do
            Toast.makeText(this, R.string.resize_handle_hint, Toast.LENGTH_SHORT).show()
            // Force the keyboard to be visible so the user sees the live preview.
            // InputMethodService.getWindow() returns the Dialog hosting the IME;
            // its Window's attributes carry the token the system needs.
            val imeWindow = window?.window
            val token = imeWindow?.attributes?.token
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (token != null && imm != null) {
                imm.showSoftInputFromInputMethod(token, 0)
            } else {
                // Fallback: request show via the input connection
                showWindow(true)
            }
        }
    }
    private var resizeModeActive = false

    // Side margins set with the left/right resize handles (they narrow the whole keyboard).
    private var kbInsetLeftPx = 0
    private var kbInsetRightPx = 0
    private var navBarBottomPx = 0

    private fun applySideInsets() {
        if (this::root.isInitialized == false) return
        root.setPadding(kbInsetLeftPx, 0, kbInsetRightPx, navBarBottomPx)
    }

    private fun loadSideInsets() {
        kbInsetLeftPx = dp(prefs.getInt("kb_inset_left", 0).toFloat()).toInt()
        kbInsetRightPx = dp(prefs.getInt("kb_inset_right", 0).toFloat()).toInt()
        applySideInsets()
    }

    /** Left/right edge handle: drag towards the middle to narrow the keyboard from that side. */
    private inner class SideHandleTouchListener(private val left: Boolean) : View.OnTouchListener {
        private var downX = 0f
        private var startInset = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    startInset = if (left) kbInsetLeftPx else kbInsetRightPx
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    // Keep at least 60% of the screen width for the keys.
                    val maxTotal = (resources.displayMetrics.widthPixels * 0.4f).toInt()
                    val other = if (left) kbInsetRightPx else kbInsetLeftPx
                    val wanted = (startInset + (if (left) dx else -dx)).toInt()
                    val clamped = wanted.coerceIn(0, (maxTotal - other).coerceAtLeast(0))
                    if (left) kbInsetLeftPx = clamped else kbInsetRightPx = clamped
                    applySideInsets()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit()
                        .putInt("kb_inset_left", (kbInsetLeftPx / dp(1f)).toInt())
                        .putInt("kb_inset_right", (kbInsetRightPx / dp(1f)).toInt())
                        .apply()
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
            }
            return false
        }
    }

    private fun resetKeyboardSize() {
        prefs.edit()
            .putInt("key_height", 50)
            .putInt("kb_inset_left", 0)
            .putInt("kb_inset_right", 0)
            .apply()
        kbInsetLeftPx = 0
        kbInsetRightPx = 0
        applySideInsets()
        applyKeyHeight()
        keyboardOffsetY = 0f
        PrefsMigration.writeOffsetY(prefs, 0f)
        applyKeyboardOffset()
    }

    private fun buildResizeOverlay(): FrameLayout {
        fun pill(w: Float, h: Float) = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(kbTheme.accent)
            }
            layoutParams = FrameLayout.LayoutParams(dp(w).toInt(), dp(h).toInt())
        }
        fun button(textRes: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(textRes)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (primary) kbTheme.accentText else kbTheme.text)
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(if (primary) kbTheme.accent else kbTheme.keyFn)
            }
            setPadding(dp(22f).toInt(), dp(10f).toInt(), dp(22f).toInt(), dp(10f).toInt())
            setOnClickListener { onClick() }
        }
        return FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
            isClickable = true // swallow touches: no typing while resizing
            visibility = View.GONE
            // Edge handles: a wide invisible touch area around a visible pill.
            for (isLeft in listOf(true, false)) {
                val touch = FrameLayout(context).apply {
                    setOnTouchListener(SideHandleTouchListener(isLeft))
                    addView(pill(6f, 72f).also {
                        (it.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
                    })
                }
                addView(touch, FrameLayout.LayoutParams(dp(36f).toInt(), MATCH).apply {
                    gravity = if (isLeft) Gravity.START else Gravity.END
                })
            }
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button(R.string.resize_reset, false) { resetKeyboardSize() })
                addView(
                    button(R.string.resize_done, true) { setResizeMode(false) },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12f).toInt() }
                )
            }
            addView(buttons, FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER })
        }
    }

    private fun showOneHandedMenu() {
        val popup = PopupMenu(this, toggle)
        popup.menu.add(0, 1, 0, "One-handed: Left")
        popup.menu.add(0, 2, 1, "One-handed: Right")
        popup.menu.add(0, 3, 2, "One-handed: Off")
        popup.menu.add(0, 4, 3, "Dock keyboard (reset position)")
        popup.menu.add(0, 5, 4, "Reset keyboard size")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1, 2, 3 -> {
                    val next = when (item.itemId) {
                        1 -> "left"; 2 -> "right"; else -> "off"
                    }
                    prefs.edit().putString("one_handed", next).apply()
                    val side = when (next) { "left" -> 1; "right" -> 2; else -> 0 }
                    kb.setOneHandedAnimated(side)
                    applyOneHandedToStrip()
                }
                4 -> {
                    // Dock: reset the floating offset to 0
                    keyboardOffsetY = 0f
                    PrefsMigration.writeOffsetY(prefs, 0f)
                    applyKeyboardOffset()
                    Toast.makeText(this, R.string.keyboard_docked, Toast.LENGTH_SHORT).show()
                }
                5 -> {
                    // Reset key height to default (50)
                    prefs.edit().putInt("key_height", 50).apply()
                    applyKeyHeight()
                    Toast.makeText(this, R.string.keyboard_size_reset, Toast.LENGTH_SHORT).show()
                }
            }
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
                    val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
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

    private val sensitiveKeyRegex = Regex("""(pass(word)?|pwd|token|secret|api[_-]?key|otp|pin)\s*[:=]""")

    private fun isLikelySensitiveText(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("://")) return false
        if (sensitiveKeyRegex.containsMatchIn(lower)) return true
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

    /** Receive per-character touch offsets from KeyboardView when a word is committed. */
    override fun onTouchOffsets(offsets: List<TouchGeometry.KeyTouch>) {
        currentTouchOffsets = offsets
    }

    /**
     * Glide typing live preview: the view sends the gesture engine's sampled path
     * while the finger is still moving. We decode it through the real [slideDecoder]
     * (async, off the main thread) and surface the result as composing text.
     *
     * This replaces the previous raw-crossing preview, which surfaced "qwertyui" —
     * i.e. every key the finger happened to cross — and made the slide feel like it
     * was triggering every character along the path. With the real decoder the
     * preview shows the actual intended word (e.g. "hello") whenever the dictionary
     * recognises the gesture, falling back to nothing instead of a raw letter soup.
     *
     * Debounced: a new path cancels the previous in-flight decode. Cancelled
     * outright by [onSwipePath] (commit) and by [onGlidePreview] with null (clear).
     */
    override fun onGlidePreviewPath(path: List<FloatArray>) {
        if (gifActive || translatorActive) return
        if (path.size < 4) return
        val layout = kbKeyLayout() ?: return
        val ctxJob = glideContext()
        glidePreviewJob?.cancel()
        glidePreviewJob = scope.launch {
            // Debounce: collapse rapid move events into one decode per ~32ms.
            delay(32)
            if (!isActive) return@launch
            val decoded = withContext(Dispatchers.Default) { slideDecoder.decode(path, layout, ctxJob.await()) }
            if (!isActive) return@launch
            // A committed glide (onSwipePath) clears composing and writes the word;
            // a cleared preview (onGlidePreview(null)) already emptied composing. In
            // either case, don't resurrect it from a stale preview decode.
            if (composing.isEmpty() && decoded.isNullOrEmpty()) return@launch
            if (decoded.isNullOrEmpty()) {
                // No dictionary match yet — show nothing rather than the raw letters.
                if (composing.isNotEmpty()) {
                    composing.clear()
                    currentInputConnection?.setComposingText("", 1)
                }
            } else if (decoded != composing.toString()) {
                composing.clear()
                composing.append(decoded)
                currentInputConnection?.setComposingText(decoded, 1)
            }
        }
    }

    /** Glide typing: live preview while the finger is still moving. */
    override fun onGlidePreview(word: String?) {
        if (gifActive || translatorActive) return
        if (word.isNullOrEmpty()) {
            // Cancel any in-flight preview decode so it can't revive the composing
            // text after the up handler has cleared it.
            glidePreviewJob?.cancel()
            glidePreviewJob = null
            glideCtxJob = null // forget it; a swipe in flight keeps its own reference
            // Clear the composing text
            if (composing.isNotEmpty()) {
                composing.clear()
                currentInputConnection?.setComposingText("", 1)
            }
            return
        }
        // Show the decoded word as composing text (live preview)
        composing.clear()
        composing.append(word)
        currentInputConnection?.setComposingText(word, 1)
    }

    override fun onSwipePath(path: List<FloatArray>): Boolean {
        if (gifActive || translatorActive) return false
        if (path.size < 4) return false
        val layout = kbKeyLayout() ?: return false
        // The final commit takes over: cancel any in-flight preview decode so it
        // can't race the commit and overwrite the just-finished composing region.
        glidePreviewJob?.cancel()
        glidePreviewJob = null
        val ctxJob = glideContext() // the editor is read here, on Main; models and decode run off-thread
        suggestJob?.cancel()
        suggestJob = scope.launch {
            // Only the pure decoding runs off the main thread; all editor/UI work stays on Main.
            val decoded = withContext(Dispatchers.Default) { slideDecoder.decode(path, layout, ctxJob.await()) }
            if (!isActive) return@launch
            if (!decoded.isNullOrEmpty()) {
                // Clear the live preview composing text
                composing.clear()
                currentInputConnection?.finishComposingText()
                commitSlideWord(decoded)
            } else {
                // Decoding failed: drop the raw preview letters instead of
                // leaving them behind as composing text.
                if (composing.isNotEmpty()) {
                    composing.clear()
                    currentInputConnection?.finishComposingText()
                }
            }
        }
        return true
    }

    override fun onKey(code: Int) {
        // If the user is in resize mode and taps any key, exit resize mode
        // (floating-keyboard behavior — tapping a key confirms the size and returns
        // to normal typing).
        if (resizeModeActive) {
            setResizeMode(false)
        }
        if (gifActive) {
            when (code) {
                Codes.DELETE -> { gifController.backspace(); return }
                Codes.SPACE -> { gifController.type(" "); return }
                Codes.ENTER -> { gifController.submit(); return }
                Codes.SHIFT, Codes.SYMBOLS, Codes.SYMBOLS2, Codes.LETTERS -> {}
                else -> closeGif()
            }
        }
        if (translatorActive) {
            onTranslatorKey(code, null)
            return
        }
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

    // --- Slide-on-delete selection -------------------------------------------------------------
    // Text before the caret when the slide started, and how many of its trailing characters are
    // currently selected. The caret itself (cursorPos) is not updated while a slide is active.
    private var delSlideText = ""
    private var delSlideChars = 0

    override fun onDeleteSlideStart() {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        composing.clear()
        delSlideText = ic.getTextBeforeCursor(500, 0)?.toString().orEmpty()
        delSlideChars = 0
        deletePreviewActive = true
        pendingDeleteWords = 0
    }

    override fun onDeleteSlideStep(units: Int, byWord: Boolean): Int {
        val ic = currentInputConnection ?: return 0
        if (cursorPos < 0 || !deletePreviewActive) return 0
        val text = delSlideText
        val len = text.length
        var start = len - delSlideChars // start of the current selection inside [text]
        var n = units
        while (n > 0 && start > 0) { // extend to the left
            if (byWord) {
                while (start > 0 && text[start - 1].isWhitespace()) start--
                while (start > 0 && !text[start - 1].isWhitespace()) start--
            } else {
                start--
            }
            n--
        }
        while (n < 0 && start < len) { // shrink back towards the caret
            if (byWord) {
                while (start < len && !text[start].isWhitespace()) start++
                while (start < len && text[start].isWhitespace()) start++
            } else {
                start++
            }
            n++
        }
        delSlideChars = len - start
        pendingDeleteWords = if (delSlideChars > 0) 1 else 0
        ic.setSelection(cursorPos - delSlideChars, cursorPos)
        return delSlideChars
    }

    override fun onDeleteCommit() {
        val ic = currentInputConnection ?: return
        val chars = delSlideChars
        deletePreviewActive = false
        pendingDeleteWords = 0
        delSlideChars = 0
        if (cursorPos < 0 || chars <= 0) return

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
        delSlideChars = 0
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
        if (gifActive) {
            gifController.type(text)
            if (shiftState == 1) { shiftState = 0; autoShifted = false; kb.shiftState = 0 }
            return
        }
        if (translatorActive) {
            onTranslatorKey(0, text)
            if (shiftState == 1) { shiftState = 0; autoShifted = false; kb.shiftState = 0 }
            return
        }
        // Clear touch offsets when starting a new composing word
        if (this::kb.isInitialized) kb.clearTouchOffsets()
        currentTouchOffsets = emptyList()
        val ic = currentInputConnection ?: return

        // 4d — track keystroke timing for TypingSpeedAdapter (drives the
        // autocorrect-confidence-threshold adjustment).
        com.noircipher.shade.engine.TypingSpeedAdapter.onKeystroke()

        if (text != " ") clearUndo()
        if (autofillCount > 0 && !autofillHiddenByTyping) {
            autofillHiddenByTyping = true // typing dismisses the autofill chips
            updateAutofillStrip()
        }

        val isWordChar = canCompose && mode == Mode.LETTERS && text.length == 1 &&
            ((text[0] != '\'' && WordDictionary.isWordChar(text[0])) || (text[0] == '\'' && composing.isNotEmpty()))

        if (isWordChar) {
            if (shiftState == 1) {
                shiftState = 0
                autoShifted = false
                kb.shiftState = 0
            }

            val sentenceEnd = smartCorrectionOn && canCompose &&
                text.length == 1 && text[0].isLowerCase() &&
                composing.isEmpty() && atSentenceEndBeforeCursor() &&
                fieldWantsAutoCaps() &&
                !userShiftOverride
            if (sentenceEnd) {
                val upper = text.uppercase()
                lastCorrectionEvent = null
                composing.append(upper)
                recordLastTouch(text[0])
                ic.setComposingText(composing.toString(), 1)
                pushUndo(upper.length, text)
                appendTail(upper)
                afterText()
                return
            }

            lastCorrectionEvent = null
            composing.append(text)
            recordLastTouch(text[0]) // after the append, so the trace lines up with the typed character
            ic.setComposingText(composing.toString(), 1)
            appendTail(text)

            // Feature 1: System Spell Checker — send each word to Android's
            // The result is usually ready by the time the user presses space.
            if (composing.length >= 2) {
                val locale = dictIds().split(',').firstOrNull()?.trim() ?: "en"
                SystemSpell.request(this@ShadeService, locale, composing.toString(), debounceMs = 150L)
            }
        } else {
            val isPunct = text.length == 1 && text[0] in ".,!?;:"
            ic.beginBatchEdit()
            try {
                endWord(if (isPunct) text else null)
                ic.commitText(text, 1)
                appendTail(text)

                // No space is inserted after typed punctuation: it split "3.14", "10:30", "1,000",
                // "example.com" and "https://" into "3. 14", "10: 30"... A space follows when the
                // person presses space (or after a suggestion / swipe), as on Gboard.
                if (smartCorrectionOn && canCompose && text.length == 1) {
                    val c = text[0]
                    if (c == '.' || c == '!' || c == '?') {
                        resetBigramContext()
                        // A sentence has ended: allow auto-caps to apply
                        // again for the start of the next sentence, even
                        // if the user had pressed shift-off for this one.
                        userShiftOverride = false
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

    private fun recordLastTouch(typed: Char) {
        val layout = kbKeyLayout() ?: return
        val k = layout[typed.lowercaseChar()] ?: return
        touchTrace.record(
            (k.left + k.right) / 2f,
            (k.top + k.bottom) / 2f
        )
    }

    private fun kbKeyLayout(): Map<Char, Key>? {
        if (this::kb.isInitialized == false) return null
        val rows = kb.rows
        if (rows.isEmpty()) return null
        // Letters only. Digits, punctuation and symbol keys would otherwise take part in decoding
        // (a swipe near the number row or the comma key picked up stray characters) and skew the
        // key-pitch estimate. Fewer than 10 letters means this is not a letter page (symbols,
        // numpad...), so there is nothing to glide over and the touch is treated as a normal tap.
        val map = HashMap<Char, Key>(64)
        for (row in rows) for (k in row) {
            if (k.kind == KeyKind.CHAR && k.label.length == 1) {
                val c = k.label[0].lowercaseChar()
                if (c in 'a'..'z') map[c] = k
            }
        }
        return if (map.size >= 10) map else null
    }

    /** Words likely to follow the previous words, for resolving ambiguous swipes. Cached per swipe. */
    private var glideCtxJob: Deferred<Map<String, Float>>? = null

    private fun glideContext(): Deferred<Map<String, Float>> =
        glideCtxJob ?: startGlideContext().also { glideCtxJob = it }

    /**
     * What the language models expect after the text before the cursor, as 4-feature LM signals
     * per word: the neural model's score/diff/position/ratio (mirroring Gboard's mlm_score_d/p/r/s),
     * plus the bigram model's likely words (scaled down; they are a coarser signal and also carry
     * what the person has typed before). Editor access happens here on Main, the model run on a
     * background thread.
     */
    private fun startGlideContext(): Deferred<Map<String, Float>> {
        val before = currentInputConnection?.getTextBeforeCursor(400, 0)?.toString().orEmpty()
        val bigram = bigramGlideContext()
        val engine = neural as? NeuralPredictionEngine
        return scope.async(Dispatchers.Default) {
            // Build a per-word composite bonus from the 4 neural features + bigram.
            // Each feature contributes independently with its own weight, then the
            // composite is used as the context bonus in SlideDecoder.stage1().
            val features = try {
                engine?.contextFeatures(before) ?: emptyMap()
            } catch (_: Throwable) { emptyMap() }

            val merged = HashMap<String, Float>(features.size + bigram.size)

            // Neural LM: combine score, diff, position, ratio with independent weights.
            // Weights tuned so that a word at rank 0 (position=0) with ratio=1 gets a
            // bonus of ~1.0, and a word at the median (diff=0) with ratio=0.5 gets ~0.5.
            for ((word, f) in features) {
                val composite = GLIDE_W_SCORE * f.score +
                    GLIDE_W_DIFF * f.diff.coerceIn(-0.5f, 0.5f) +  // clamp: extreme diffs dominate
                    GLIDE_W_POSITION * (1f - f.position) +          // invert: rank 0 = best
                    GLIDE_W_RATIO * f.ratio
                merged[word] = composite.coerceIn(0f, 1.5f)
            }

            // Bigram: scaled down as before, takes the max if the word already has a neural bonus.
            // Gboard's uhlm_score_d/p/r/s — we derive 4 features from the bigram model too,
            // but since the bigram model gives a simpler ranked list, we compute a composite
            // that approximates the same signal: top-ranked words get a higher bonus.
            val bigramRanked = bigram.entries.sortedByDescending { it.value }
            val bigramTop = if (bigramRanked.isNotEmpty()) bigramRanked[0].value else 1f
            for ((idx, entry) in bigramRanked.withIndex()) {
                val word = entry.key
                val fit = entry.value
                // Derive position (rank-based) and ratio (vs top) from the bigram ranking.
                val bPosition = if (bigramRanked.size > 1) idx.toFloat() / (bigramRanked.size - 1) else 0f
                val bRatio = if (bigramTop > 0f) (fit / bigramTop).coerceIn(0f, 1f) else 0f
                // Composite bigram bonus: scaled by GLIDE_BIGRAM_SCALE, enriched with
                // position and ratio signals (weaker than neural but still useful).
                val composite = fit * GLIDE_BIGRAM_SCALE * (1f - 0.3f * bPosition) * (0.5f + 0.5f * bRatio)
                if (composite > (merged[word] ?: 0f)) merged[word] = composite
            }
            merged
        }
    }

    private fun bigramGlideContext(): Map<String, Float> {
        if (atSentenceEndBeforeCursor()) return emptyMap()
        val words = contextWordsExcluding("")
        val prev1 = words.lastOrNull() ?: return emptyMap()
        // BOS feature: at the beginning of a sentence (no prior words, or prior word
        // ends a sentence), the neural model already handles this via its training.
        // For the bigram fallback, we boost common sentence-starters.
        val scores = dict.nextWordScores(words.dropLast(1).lastOrNull(), prev1)
        // In messaging apps, boost informal/chat words that Gboard also boosts there.
        if (isInMessagingApp) {
            val chatBoost = mapOf(
                "yeah" to 0.3f, "ok" to 0.3f, "haha" to 0.3f, "lol" to 0.25f,
                "sure" to 0.25f, "nice" to 0.25f, "cool" to 0.25f, "hey" to 0.3f,
                "yep" to 0.2f, "nope" to 0.2f, "thanks" to 0.2f, "thx" to 0.2f
            )
            val merged = HashMap(scores)
            for ((word, boost) in chatBoost) {
                val v = boost
                if (v > (merged[word] ?: 0f)) merged[word] = v
            }
            return merged
        }
        return scores
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
        kb.hapticStrength = when (prefs.getString("haptic_strength", "medium")) {
            "light" -> 0
            "strong" -> 2
            else -> 1
        }
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
        refreshCandidateCase()
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (insightsAllowed()) typingStatsStore.recordBackspace()

        // Pop the last touch offset (backspace within the word being typed)
        if (this::kb.isInitialized) kb.popTouchOffset()

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
                    pushUndo(2, " ")
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
            if (smartCorrectionOn && autocorrectOn && sep != null && canCompose) {
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
            // Only expand tokens that aren't real words ("bro", "babe" are words; "btw", "idk" are not).
            val abbrevExpansion = if (effectiveShouldCorrect && !dict.contains(w)) {
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
                    // Flush touch offsets from KeyboardView before correction
                    if (this::kb.isInitialized) kb.flushTouchOffsets()
                    val touches = currentTouchOffsets
                    var candidate = try { dict.correction(w, isEnglishLocale(), touches) } catch (_: Exception) { null }

                    // Feature 1b: Neural spell checker — on-device seq2seq over WPM pieces.
                    // Runs only when the dictionary had no correction at all (rare), stays
                    // under a latency budget, and returns nothing when it has no candidate
                    // better than what the user typed.
                    if (candidate == null && w.length >= 3) {
                        val neural = try { neuralSpell?.correct(w) } catch (_: Throwable) { null }
                        if (!neural.isNullOrEmpty()) candidate = neural.first()
                    }

                    // Feature 1: System Spell Checker — merge with Android's
                    // system says it's a typo and has a suggestion, use it.
                    if (candidate == null && w.length >= 3) {
                        val locale = dictIds().split(',').firstOrNull()?.trim() ?: "en"
                        SystemSpell.flush()
                        // 20ms: the spell-check round trip is async; the speculative request() fired on the
                        // keystrokes is usually done by now, and endWord() runs on Main so the
                        // bounded wait keeps the space press instant.
                        val sys = SystemSpell.await(locale, w, 20L)
                        if (sys != null && sys.typo && sys.suggestions.isNotEmpty()) {
                            candidate = sys.suggestions.first()
                        }
                    }

                    if (candidate == null) null else {
                        // 4f — autocorrect confidence gate (#31 + A7 + #21).
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

            // Confusable pairs that depend on the FOLLOWING word ("your welcome", "to late"):
            // now that this word is complete, fix the word before it. Skipped when this word was
            // itself autocorrected so there is only ever one correction to undo.
            if (corrected == null && smartCorrectionOn && canCompose && prev1 != null && isEnglishLocale()) {
                fixPreviousConfusable(prev1, prev2, finalWord, sep)
            }

            // 4g — Session bigram observation + rejection tracking.
            sessionBigramBoost.observeBigram(prev1, finalWord)
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

    private fun fixPreviousConfusable(prevLower: String, beforePrev: String?, word: String, sep: String?) {
        val ic = currentInputConnection ?: return
        val replacement = com.noircipher.shade.engine.ConfusablePairs
            .correctPrevious(prevLower, word, beforePrev) ?: return
        // Only rewrite when the text really is "<prev> <word>" with a single space between.
        val span = prevLower.length + 1 + word.length
        val before = ic.getTextBeforeCursor(span, 0)?.toString() ?: return
        if (before.length != span || before[prevLower.length] != ' ') return
        if (!before.regionMatches(0, prevLower, 0, prevLower.length, ignoreCase = true)) return
        if (!before.regionMatches(prevLower.length + 1, word, 0, word.length, ignoreCase = true)) return
        val fixed = com.noircipher.shade.engine.ConfusablePairs.matchCase(before.substring(0, prevLower.length), replacement)
        val newText = fixed + before.substring(prevLower.length)
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(span, 0)
            ic.commitText(newText, 1)
        } finally {
            ic.endBatchEdit()
        }
        popTail(span)
        appendTail(newText)
        // Same revert path as an autocorrect: backspace right after restores what the user typed.
        recordCorrectionEvent(before, newText, sep)
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

    /**
     * True when the focused field asks for automatic capitalisation (sentence/word/character
     * flags in its inputType) and the user has auto-caps on. A search box such as YouTube's sets
     * none of those flags, so typing there stays lowercase - the sentence-start capitalisation in
     * [onText] used to ignore this and capitalised every first letter anyway.
     */
    private fun fieldWantsAutoCaps(): Boolean {
        val ei = info ?: return false
        val ic = currentInputConnection ?: return false
        if (!autoCaps) return false
        if ((ei.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return ic.getCursorCapsMode(ei.inputType) != 0
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
        refreshCandidateCase()
    }

    // ------------------------------------------------------------------ suggestions (cascade)

    private suspend fun configureLocaleAssets(locale: String) {
        localeAssetsMutex.withLock {
            if (configuredLocale == locale) return@withLock
            configuredLocale = locale

            dict.configure(locale)
            // Build the glide lexicon now so the first swipe of the session is not slow.
            scope.launch(Dispatchers.Default) { try { dict.warmGlide() } catch (_: Exception) {} }
            val oldEmojiPredictor = emojiPredictor
            emojiPredictor = EmojiPredictionEngine.load(this@ShadeService, locale)
            try { oldEmojiPredictor.close() } catch (_: Exception) {}

            try {
                rankingScorer?.close()
            } catch (_: Exception) {}
            // Throwable (not Exception): a failed TFLite/ONNX native load is an Error and must
            // disable the model, not crash the IME process on the background loader thread.
            rankingScorer = try { TfliteCandidateRankingScorer(this@ShadeService) } catch (_: Throwable) { null }

            try { contextEncoder?.close() } catch (_: Exception) {}
            contextEncoder = if (locale.contains("hi") || locale.contains("hg") || locale.contains("hinglish")) {
                try { TfliteContextEncoder(this@ShadeService, "ranking/context_encoder_hi_xa.tflite") } catch (_: Throwable) { null }
            } else {
                null
            }

            reloadNeuralEngine(desiredNeuralLocale(emptyList(), locale))
        }
        warmUpNeuralEngines()
        // Build per-app helper objects now (background) so the first keyboard open
        // doesn't pay for loading them on the main thread.
        try { appContextModel; perAppVocabSnapshot } catch (_: Throwable) {}
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
            ?.getTextBeforeCursor(400, 0)
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
        if (personalLearningAllowed()) userWordMemory.bump(cleaned)
        pushUndo(cleaned.length + 1, "")
        composing.clear()
        afterText()
    }

    private fun commitSlideWord(decoded: String) {
        endWord()
        val ic = currentInputConnection ?: return
        val word = glideCased(decoded)

        // A glided word follows the previous one with a space, even when that one was typed and has
        // not been followed by a space yet ("hello" + swipe "world" -> "hello world").
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val lead = if (before.isNotEmpty() && (before.last().isLetterOrDigit() || before.last() in ".,!?;:")) " " else ""
        val text = "$lead$word "

        val prevWords = contextWordsExcluding("")
        ic.commitText(text, 1)
        appendTail(text)
        if (personalLearningAllowed()) userWordMemory.bump(decoded)
        // Teach the next-word model what follows what, but do not add the word itself to the learned
        // dictionary: a wrong decode would otherwise start to favour itself.
        if (learnOn && personalLearningAllowed()) {
            dict.observeBigram(prevWords.dropLast(1).lastOrNull(), prevWords.lastOrNull(), decoded)
        }
        pushUndo(text.length, "") // backspace right after a swipe removes the whole word
        composing.clear()
        if (shiftState == 1) { // a one-shot shift is used up by the swiped word
            shiftState = 0
            autoShifted = false
            kb.shiftState = 0
        }
        glideCtxJob = null
        afterText()
    }

    /** Applies shift / caps lock to a glided word and capitalises the pronoun "I". */
    private fun glideCased(word: String): String {
        var w = word
        if (isEnglishLocale() && (w == "i" || w.startsWith("i'"))) w = "I" + w.substring(1)
        return when (shiftState) {
            2 -> w.uppercase()
            1 -> w.replaceFirstChar { it.uppercaseChar() }
            else -> w
        }
    }


    private fun contextWordsExcluding(typedWord: String): List<String> {
        val raw = currentInputConnection
            ?.getTextBeforeCursor(400, 0)
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
                        // Neural improvement #3 — use beam search for phrase
                        // suggestions when the user has multi-word suggestions
                        // enabled. Beam search produces 2-word continuations
                        // ("are you", "to see") that greedy single-token
                        // prediction misses. Falls back to greedy for
                        // word-by-word mode (faster, single-word results).
                        if (phraseSuggestionsOn) {
                            (neural as? NeuralPredictionEngine)
                                ?.predictNextBeam(context, null, 8)
                                ?.map { it.first }
                                .orEmpty()
                        } else {
                            neural
                                ?.predictNext(context, null, 6)
                                ?.map { it.first }
                                .orEmpty()
                        }
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
                com.noircipher.shade.engine.SuggestionCache.salt = "$configuredLocale|$incognito"
                com.noircipher.shade.engine.SuggestionCache.get(prev2, prev1, prefix)?.let { cached ->
                    withContext(Dispatchers.Main.immediate) { setCandidates(cached) }
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
                    // Neural improvement #5 — pass bigram scores into the neural
                    // reranker so it can do log-linear interpolation instead of
                    // independent ranking. The bigram model catches user-specific
                    // phrases and rare words the neural model misses.
                    val bigramScores = dict.bigramModel
                        ?.predict(prev2, prev1, stage3.size * 2)
                        ?.associate { it.first.lowercase() to it.second }
                        .orEmpty()
                    (neural as? NeuralPredictionEngine)
                        ?.rerankPrefix(neuralCtx, effectivePrefix, stage3, bigramScores)
                        ?: neural?.rerankPrefix(neuralCtx, effectivePrefix, stage3)
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
                // Gated behind phraseSuggestionsOn: the bigram table contains
                // single-word completions (good→morning) AND multi-word ones
                // (good→"to see"); in word-by-word mode we still want the
                // single-word continuations, so we keep the source but filter
                // out space-containing entries.
                if (phraseSuggestionsOn) {
                    val phraseCompletions = com.noircipher.shade.engine.PhraseCompletion
                        .suggestPhraseCompletions(contextWords, prefix, limit = 3)
                    if (phraseCompletions.isNotEmpty()) {
                        ranked = (phraseCompletions + ranked).distinct()
                    }
                } else {
                    val phraseCompletions = com.noircipher.shade.engine.PhraseCompletion
                        .suggestPhraseCompletions(contextWords, prefix, limit = 3)
                        .filter { !it.contains(' ') }
                    if (phraseCompletions.isNotEmpty()) {
                        ranked = (phraseCompletions + ranked).distinct()
                    }
                }
                // #15 — Smart compose for common responses (ok → ok sounds good, ...).
                // These templates are inherently multi-word, so the whole source
                // is skipped in word-by-word mode.
                if (phraseSuggestionsOn && FieldTypeDetector.shouldSmartCompose(currentFieldType)) {
                    val smartResponses = com.noircipher.shade.engine.SmartResponseComposer.suggest(prefix, limit = 2)
                    if (smartResponses.isNotEmpty()) {
                        ranked = (smartResponses + ranked).distinct()
                    }
                }
                // #6+#7 — Cross-sentence context + sentence starters.
                val extendedContext = com.noircipher.shade.engine.CrossSentenceContext.extendContext(contextWords)
                val rawBeforeForSentence = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty()
                    .let { if (prefix.isNotEmpty() && it.endsWith(prefix, ignoreCase = true)) it.dropLast(prefix.length) else it }
                if (com.noircipher.shade.engine.CrossSentenceContext.isSentenceStart(extendedContext) ||
                    com.noircipher.shade.engine.CrossSentenceContext.endsWithSentenceTerminator(rawBeforeForSentence)) {
                    val starters = com.noircipher.shade.engine.CrossSentenceContext.sentenceStarters(prefix, limit = 2)
                    // In word-by-word mode drop multi-word starters ("let me",
                    // "thank you", "it would") and keep single-word ones ("the",
                    // "I", "how", ...).
                    val filteredStarters = if (phraseSuggestionsOn) starters
                        else starters.filter { !it.contains(' ') }
                    if (filteredStarters.isNotEmpty()) {
                        ranked = (filteredStarters + ranked).distinct()
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
                // Skipped entirely in word-by-word mode; in phrase mode keep only
                // the single-word completions ("soon", "later", "tomorrow", ...).
                run {
                    val sentenceCompletions = com.noircipher.shade.engine.SentenceCompleter
                        .suggest(contextWords, limit = 2)
                    val kept = if (phraseSuggestionsOn) sentenceCompletions
                        else sentenceCompletions.filter { !it.contains(' ') }
                    if (kept.isNotEmpty()) {
                        ranked = (kept + ranked).distinct()
                    }
                }
                // #11 — Suggestion cache: nothing to inject here, but record the
                // final ranked list so subsequent keystrokes can hit the cache.
                // #32 — Multi-armed bandit exploration: occasionally swap top-2
                // to learn whether the runner-up would have been accepted.
                if (onlineRankerTrainer.shouldExplore() && ranked.size > 2) {
                    val mutable = ranked.toMutableList()
                    val tmp = mutable[0]
                    mutable[0] = mutable[1]
                    mutable[1] = tmp
                    ranked = mutable
                }

                val shown = com.noircipher.shade.engine.SuggestionFinalizer.finalize(
                    prefix = prefix,
                    known = known,
                    grammarTop = grammarTop,
                    ranked = ranked,
                    phraseSuggestionsOn = phraseSuggestionsOn
                )
                // Cache the final list (after typo/grammar handling) so a cache hit
                // shows exactly what the full pipeline would.
                com.noircipher.shade.engine.SuggestionCache.put(prev2, prev1, prefix, shown)
                withContext(Dispatchers.Main.immediate) { setCandidates(shown) }
            }
        }
    }

    private fun centerBest(l: List<String>): List<String> =
        com.noircipher.shade.engine.SuggestionFinalizer.centerBest(l)

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

    /** Case the suggestion bar should use right now (see [SuggestionCasing.styleFor]). */
    private fun currentSuggestionStyle(): SuggestionCasing.Style {
        val typed = when {
            composing.isNotEmpty() -> composing.toString()
            !canCompose -> currentTypedWordFromField()
            else -> ""
        }
        return SuggestionCasing.styleFor(mode == Mode.LETTERS, shiftState, typed)
    }

    private fun casedCandidates(raw: List<String>): List<String> {
        if (raw.isEmpty()) return raw
        val style = currentSuggestionStyle() // computed once per update, not per suggestion
        return raw.map { SuggestionCasing.apply(it, style, lastClipText) }
    }

    /** Re-cases the visible suggestions after Shift / Caps Lock changed, without recomputing them. */
    private fun refreshCandidateCase() {
        if (!this::slots.isInitialized || candidates.isEmpty()) return
        val display = casedCandidates(candidates)
        if (display == shownWords) return
        shownWords = display
        for (i in 0 until 3) {
            val w = display.getOrNull(i) ?: ""
            if (slots[i].text.toString() != w) slots[i].text = w
        }
    }

    /** Empties both the candidate lists and the three slots (stale words must not survive a field change). */
    private fun resetCandidates() {
        candidates = emptyList()
        shownWords = emptyList()
        if (!this::slots.isInitialized) return
        slotAnimator?.cancel()
        for (i in 0 until 3) {
            slots[i].text = ""
            slotOlds[i].text = ""
            slotOlds[i].alpha = 0f
            slotOlds[i].visibility = View.GONE
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
        // Re-case for the current keyboard state (Shift / Caps Lock / typed capital).
        val display = casedCandidates(effective)
        val rawChanged = effective != candidates
        if (!rawChanged && display == shownWords) return
        if (rawChanged) {
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
        }
        val old = shownWords
        candidates = effective
        shownWords = display
        slotAnimator?.cancel()

        var anyChanged = false
        for (i in 0 until 3) {
            val newWord = display.getOrNull(i) ?: ""
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
        // `w` (raw) is used for all learning/statistics; `shown` is what the user tapped and what is committed.
        val shown = shownWords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: w
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
        sessionBigramBoost.observeBigram(contextWordsExcluding(w).lastOrNull(), w)
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
                ic.commitText(shown, 1)
            } else {
                if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
                ic.commitText(shown, 1)
            }
            if (typed.isNotEmpty() && tailValid && tail.endsWith(typed, ignoreCase = true)) {
                popTail(typed.length)
            }
            appendTail(shown)
            if (shown.isNotEmpty() && shown[0].isLetter()) {
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
        // Bigram fits (0..1 by rank) are weighted like the old fixed bonus: 3.0 / SlideDecoder.CONTEXT_WEIGHT.
        private const val GLIDE_BIGRAM_SCALE = 0.5f
        // Neural LM feature weights (mirroring Gboard's mlm_score_d/p/r/s).
        // Composite = W_SCORE * score + W_DIFF * diff + W_POSITION * (1-position) + W_RATIO * ratio.
        // Tuned so rank-0/ratio-1 → ~1.0, median/ratio-0.5 → ~0.5.
        private const val GLIDE_W_SCORE = 0.40f
        private const val GLIDE_W_DIFF = 0.20f
        private const val GLIDE_W_POSITION = 0.20f
        private const val GLIDE_W_RATIO = 0.20f
        // Height of the transparent band above the keyboard that accent panels may extend into.
        private const val ACCENT_HEADROOM_DP = 64f
        private const val NEURAL_FAILURE_TTL_MS = 5L * 60L * 1000L
        private const val SUGGEST_DEBOUNCE_MS = 32L
        // 4o — autocorrect confidence threshold. Combined with the typing-speed
        // adjustment in [endWord] to gate aggressive autocorrects.
        private const val AUTOCORRECT_CONFIDENCE_THRESHOLD = 0.85f
    }
}
