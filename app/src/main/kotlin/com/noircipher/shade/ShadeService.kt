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
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        "floating" to "⬚", "settings" to "⚙"
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
    private lateinit var slotRow: LinearLayout
    private lateinit var slots: List<TextView>
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
    private var suggestionsOn = true
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

    private var floating: FloatingKeyboard? = null
    private var floatingMode = false

    private val undoStack = ArrayDeque<UndoEntry>(10)

    private val tail = StringBuilder(16)
    private var cursorPos = -1
    private var selectionEnd = -1
    private var tailValid = false

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var suggestJob: Job? = null
    private var slotFadeAnimator: ValueAnimator? = null

    private var modeTransitionAnimator: ValueAnimator? = null
    private var currentTransition: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        dict = WordDictionary(this)
        dict.bigramModel = BigramModel(this)
        clips = ClipboardStore(this)
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try { cm.addPrimaryClipChangedListener(clipListener) } catch (_: Exception) {}

        val locale = prefs.getString("locale", "en") ?: "en"
        scope.launch(Dispatchers.Default) {
            dict.configure(locale)
        }
    }

    override fun onDestroy() {
        try { cm.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        if (floatingMode) disableFloating()
        dict.bigramModel?.save()
        suggestJob?.cancel()
        slotFadeAnimator?.cancel()
        modeTransitionAnimator?.cancel()
        serviceJob.cancel()

        if (this::kb.isInitialized) kb.release()
        bgBmp = null
        candidates = emptyList()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
        info = null

        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = buildInputView()

    private fun buildInputView(): View {
        kb = KeyboardView(this).also { it.listener = this }

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
                textSize = 17f
                tag = i
                setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
                setOnClickListener { pickSuggestion(it.tag as Int) }
            }
        }
        slotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (tv in slots) addView(tv, LinearLayout.LayoutParams(0, MATCH, 1f))
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
            addView(slotRow, FrameLayout.LayoutParams(MATCH, MATCH))
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
            addView(strip, LinearLayout.LayoutParams(MATCH, dp(44f).toInt()))
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

        val type = editorInfo.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val noSuggest = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val blocked = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
        canCompose = (cls == InputType.TYPE_CLASS_TEXT) && (noSuggest == false) && (blocked == false)

        applyPrefs()
        val m = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> Mode.NUMPAD
            InputType.TYPE_CLASS_DATETIME -> Mode.SYM1
            else -> Mode.LETTERS
        }
        setMode(m, animate = false)
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
        updateShiftFromCaps()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (floatingMode) disableFloating()
        suggestJob?.cancel()
        info = null
        candidates = emptyList()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
        cursorPos = -1
        selectionEnd = -1
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentInputConnection?.finishComposingText()
        composing.clear()
        undoStack.clear()
        tail.setLength(0)
        tailValid = false
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd
        )
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
        kb.longPressMs = prefs.getInt("longpress_delay", 300).toLong()
        kb.repeatDelayMs = prefs.getInt("repeat_delay", 400).toLong()
        kb.repeatIntervalMs = prefs.getInt("repeat_interval", 50).toLong()

        val locale = prefs.getString("locale", "en") ?: "en"
        scope.launch(Dispatchers.Default) { dict.configure(locale) }

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
        kb.oneHanded = when (prefs.getString("one_handed", "off")) {
            "left" -> 1; "right" -> 2; else -> 0
        }
        kb.setSplitAnimated(split)
        kb.keyBorders = prefs.getBoolean("key_borders", true)
        kb.showHints = prefs.getBoolean("show_hints", true)
        kb.haptic = prefs.getBoolean("haptic", true)
        kb.sound = prefs.getBoolean("sound", false)
        kb.showPreview = prefs.getBoolean("popup_preview", true)
        kb.bgBitmap = bgBmp
        kb.spaceLabel = if (layoutName == "custom") "Custom" else layoutName.uppercase()

        (kb.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        (emoji.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        (clipPanel.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }

        emoji.setTheme(kbTheme)
        clipPanel.setTheme(kbTheme)

        root.setBackgroundColor(kbTheme.bg)
        toggle.setTextColor(kbTheme.hint)
        for (tv in slots) tv.setTextColor(kbTheme.text)
        rebuildToolbar()
        refreshStrip()
        setMode(mode, animate = false)
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
            val tv = TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(kbTheme.text)
                setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
                isClickable = true
                isFocusable = false
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.animate().cancel()
                            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(60L).start()
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().cancel()
                            v.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(180L)
                                .setInterpolator(OvershootInterpolator(2.5f))
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
            .setDuration(90L)
            .withEndAction {
                toggle.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .start()
            }
            .start()
    }

    private fun refreshStrip() {
        if (this::strip.isInitialized == false) return
        val suggestionsActive = suggestionsOn && canCompose
        val showTools = toolbarOpen || (suggestionsActive == false)
        val targetSlotAlpha = if (showTools) 0f else 1f
        val targetToolAlpha = if (showTools) 1f else 0f
        animateAlpha(slotRow, slotRow.alpha, targetSlotAlpha)
        animateAlpha(toolScroll, toolScroll.alpha, targetToolAlpha)
        slotRow.visibility = View.VISIBLE
        toolScroll.visibility = View.VISIBLE
        toggle.text = if (showTools) "‹" else "›"
        toggle.visibility = if (suggestionsActive) View.VISIBLE else View.GONE
        strip.visibility = if (panel == Panel.NONE) View.VISIBLE else View.GONE
    }

    private fun animateAlpha(v: View, from: Float, to: Float) {
        if (from == to) { v.alpha = to; return }
        ValueAnimator.ofFloat(from, to).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { v.alpha = it.animatedValue as Float }
            start()
        }
    }

    // ------------------------------------------------------------------ transition controller

    private fun runTransition(tag: String, apply: () -> Unit) {
        if (currentTransition == tag) { apply(); return }
        modeTransitionAnimator?.cancel()
        currentTransition = tag
        modeTransitionAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 80L
            interpolator = DecelerateInterpolator()
            addUpdateListener { root.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    apply()
                    modeTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 120L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { root.alpha = it.animatedValue as Float }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                currentTransition = null
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun setMode(m: Mode, animate: Boolean = true) {
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
        if (animate) kb.setRowsAnimated(rows) else kb.setRows(rows)
        kb.shiftState = if (m == Mode.LETTERS) shiftState else 0
        kb.relayout()
    }

    private fun transitionToPanel(p: Panel, animate: Boolean = true) {
        if (this::emoji.isInitialized == false) return
        if (p != Panel.NONE) endWord()
        if (animate && panel != p) {
            runTransition("panel:${p.name}") { applyPanelState(p) }
        } else {
            applyPanelState(p)
        }
    }

    private fun applyPanelState(p: Panel) {
        panel = p
        when (p) {
            Panel.NONE -> {
                crossfadePanel(emoji, false)
                crossfadePanel(clipPanel, false)
                kb.visibility = View.VISIBLE
            }
            Panel.EMOJI -> {
                kb.visibility = View.GONE
                crossfadePanel(clipPanel, false)
                crossfadePanel(emoji, true)
            }
            Panel.CLIP -> {
                kb.visibility = View.GONE
                crossfadePanel(emoji, false)
                crossfadePanel(clipPanel, true)
                clipPanel.refresh()
            }
        }
        refreshStrip()
    }

    private fun crossfadePanel(v: View, show: Boolean) {
        val targetAlpha = if (show) 1f else 0f
        if (v.alpha == targetAlpha) {
            v.visibility = if (show) View.VISIBLE else View.GONE
            return
        }
        v.visibility = View.VISIBLE
        ValueAnimator.ofFloat(v.alpha, targetAlpha).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener { v.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (show == false) v.visibility = View.GONE
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ floating keyboard

    private fun toggleFloatingMode() {
        if (floatingMode) disableFloating() else enableFloating()
    }

    private fun enableFloating() {
        if (floatingMode) return
        val hasOverlayPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            android.provider.Settings.canDrawOverlays(this)
        if (hasOverlayPermission == false) {
            startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(android.net.Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        val f = FloatingKeyboard(this)
        f.listener = object : FloatingKeyboard.Listener {
            override fun onBoundsChanged(x: Int, y: Int, width: Int, height: Int) {
                prefs.edit()
                    .putInt("float_x", x)
                    .putInt("float_y", y)
                    .putInt("float_w", width)
                    .putInt("float_h", height)
                    .apply()
            }
            override fun onFocusRestored() {}
        }

        (kb.parent as? ViewGroup)?.removeView(kb)

        val metrics = resources.displayMetrics
        val w = prefs.getInt("float_w", (metrics.widthPixels * 0.85f).toInt())
        val h = prefs.getInt("float_h", dp(220f).toInt())
        val x = prefs.getInt("float_x", (metrics.widthPixels - w) / 2)
        val y = prefs.getInt("float_y", (metrics.heightPixels * 0.55f).toInt())

        val shown = f.show(kb, w, h, x, y)
        if (shown == false) {
            root.addView(kb, 1, LinearLayout.LayoutParams(MATCH, dp(200f).toInt()))
            applyPrefs()
            return
        }
        floating = f
        floatingMode = true
    }

    private fun disableFloating() {
        val f = floating ?: return
        f.hide()
        floating = null
        floatingMode = false

        (kb.parent as? ViewGroup)?.removeView(kb)
        root.addView(kb, 1, LinearLayout.LayoutParams(MATCH, dp(200f).toInt()))
        applyPrefs()
    }

    // ------------------------------------------------------------------ toolbar

    private fun onTool(id: String) {
        val ic = currentInputConnection
        when (id) {
            "clip" -> transitionToPanel(Panel.CLIP)
            "emoji" -> transitionToPanel(Panel.EMOJI)
            "numpad" -> { endWord(); runTransition("mode:NUMPAD") { setMode(Mode.NUMPAD, animate = false) } }
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
            "onehand" -> {
                val next = when (prefs.getString("one_handed", "off")) {
                    "off" -> "left"; "left" -> "right"; else -> "off"
                }
                prefs.edit().putString("one_handed", next).apply()
                runTransition("onehand:$next") {
                    kb.oneHanded = when (next) { "left" -> 1; "right" -> 2; else -> 0 }
                    kb.relayout()
                }
            }
            "floating" -> toggleFloatingMode()
            "settings" -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        updateShiftFromCaps()
        updateSuggestions()
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

    // ------------------------------------------------------------------ KeyboardView.Listener

    override fun onKey(code: Int) {
        when (code) {
            Codes.SHIFT -> onShift()
            Codes.DELETE -> backspace()
            Codes.ENTER -> enter()
            Codes.SPACE -> space()
            Codes.SYMBOLS -> { endWord(); runTransition("mode:SYM1") { setMode(Mode.SYM1, animate = false) } }
            Codes.SYMBOLS2 -> runTransition("mode:SYM2") { setMode(Mode.SYM2, animate = false) }
            Codes.LETTERS -> {
                endWord()
                runTransition("mode:LETTERS") {
                    setMode(Mode.LETTERS, animate = false)
                    updateShiftFromCaps()
                }
            }
            Codes.EMOJI -> transitionToPanel(Panel.EMOJI)
        }
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return

        if (text != " ") clearUndo()

        val isWordChar = canCompose && mode == Mode.LETTERS && text.length == 1 &&
            (text[0].isLetter() || (text[0] == '\'' && composing.isNotEmpty()))

        if (isWordChar) {
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
                        if (c == '.' || c == '!' || c == '?') {
                            resetBigramContext()
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
        while (i < dx) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            i += 1
        }
        while (i > dx) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            i -= 1
        }
        var j = 0
        while (j < dy) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            j += 1
        }
        while (j > dy) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            j -= 1
        }
        clearUndo()
        tailValid = false
    }

    override fun onTrackpadEnter() {}
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
        var corrected: String? = null

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

            if (sep != null && autocorrectOn && canCompose &&
                w.drop(1).none { it.isUpperCase() }
            ) {
                corrected = dict.correction(w)
            }
            if (corrected != null) {
                ic?.commitText(corrected, 1)
                pushUndo(corrected.length, w)
            } else {
                if (learnOn && incognito == false && canCompose) {
                    dict.learn(w, prev2 = null, prev1 = lastWord())
                }
                ic?.finishComposingText()
            }
            composing.clear()
        } else {
            ic?.finishComposingText()
        }
        return corrected
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
            if (ch == ' ' || ch == '\n' || ch == '\t') {
                i -= 1
                continue
            }
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

    // ------------------------------------------------------------------ suggestions

    private fun updateSuggestions() {
        if (this::slots.isInitialized == false) return
        suggestJob?.cancel()
        if (suggestionsOn == false || canCompose == false) {
            setCandidates(emptyList())
            return
        }
        val prefix = composing.toString()
        if (prefix.isEmpty()) {
            val prev1 = lastWord()
            val next: List<String>
            if (prev1 != null) {
                next = dict.predictNext(null, prev1, 3)
            } else {
                next = emptyList()
            }
            val list: List<String>
            if (next.isNotEmpty()) {
                list = next
            } else if (atSentenceEndBeforeCursor()) {
                list = listOf("I", "The", "I'm")
            } else {
                list = emptyList()
            }
            setCandidates(list)
            return
        }
        val emoji = if (emojiSuggest) EmojiKeywords.map[prefix.lowercase()] else null
        suggestJob = scope.launch {
            val list = LinkedHashSet<String>()
            if (dict.contains(prefix) == false) list.add(prefix)
            list.addAll(dict.suggestAsync(prefix, 3))
            val words = list.take(3)
            val final = if (emoji != null) words.take(2) + emoji else words
            setCandidates(final)
        }
    }

    private fun setCandidates(list: List<String>) {
        if (list == candidates) return
        candidates = list
        slotFadeAnimator?.cancel()
        renderSlots()
        slotFadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val alpha = 0.55f + 0.45f * t
                val dy = (1f - t) * dp(3f)
                slotRow.alpha = alpha
                slotRow.translationY = dy
                slots.forEachIndexed { i, tv ->
                    val slotT = ((t - i * 0.08f) / (1f - i * 0.08f)).coerceIn(0f, 1f)
                    tv.alpha = 0.55f + 0.45f * slotT
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slotRow.alpha = 1f
                    slotRow.translationY = 0f
                    for (tv in slots) tv.alpha = 1f
                }
            })
            start()
        }
    }

    private fun renderSlots() {
        if (this::slots.isInitialized == false) return
        slots.forEachIndexed { i, tv ->
            tv.text = candidates.getOrNull(i) ?: ""
            tv.setTypeface(null, if (i == 0) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun pickSuggestion(i: Int) {
        val w = candidates.getOrNull(i) ?: return
        val ic = currentInputConnection ?: return
        composing.clear()
        clearUndo()
        ic.beginBatchEdit()
        try {
            ic.commitText(w, 1)
            appendTail(w)
            if (w[0].isLetter()) {
                ic.commitText(" ", 1)
                appendTail(" ")
            }
        } finally {
            ic.endBatchEdit()
        }
        if (w[0].isLetter()) {
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
