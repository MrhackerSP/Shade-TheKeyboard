package com.noircipher.shade

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
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class ShadeService : InputMethodService(), KeyboardView.Listener {

    private enum class Mode { LETTERS, SYM1, SYM2, NUMPAD }
    private enum class Panel { NONE, EMOJI, CLIP }
    private class Revert(val original: String, val corrected: String, val sep: String)

    private val toolDefs = listOf(
        "clip" to "📋", "emoji" to "😀", "numpad" to "123", "left" to "◀", "right" to "▶",
        "selectall" to "All", "copy" to "Copy", "cut" to "Cut", "paste" to "Paste",
        "undo" to "Undo", "incognito" to "🕶", "onehand" to "1-hand", "settings" to "⚙"
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
    private var revert: Revert? = null
    private val ignored = HashSet<String>()
    private val composing = StringBuilder()
    private var canCompose = true
    private var suggestionsOn = true
    private var learnOn = true
    private var autoCaps = true
    private var doubleSpace = true
    private var autocorrectOn = true
    private var emojiSuggest = true
    private var incognito = false
    private var numberRow = false
    private var toolbarOpen = false
    private var layoutName = "qwerty"
    private var candidates: List<String> = emptyList()
    private var info: EditorInfo? = null
    private var bgBmp: Bitmap? = null
    private var bgStamp = -1L

    private val tail = StringBuilder(16)
    private var cursorPos = -1
    private var selectionEnd = -1
    private var tailValid = false

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var suggestJob: Job? = null
    private var correctJob: Job? = null

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
        dict.bigramModel?.save()
        suggestJob?.cancel()
        correctJob?.cancel()
        serviceJob.cancel()

        if (::kb.isInitialized) kb.release()
        bgBmp = null
        candidates = emptyList()
        composing.clear()
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
                toolbarOpen = !toolbarOpen
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
                currentInputConnection?.commitText(text, 1)
            },
            onBack = { showPanel(Panel.NONE) },
            onDelete = { backspace() }
        )
        clipPanel = ClipboardPanel(
            this, clips,
            onPaste = { text ->
                endWord()
                currentInputConnection?.commitText(text, 1)
                showPanel(Panel.NONE)
                afterText()
            },
            onBack = { showPanel(Panel.NONE) },
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
        applyPrefs()
        return root
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        info = editorInfo
        dict.reloadUser()
        composing.clear()
        tail.setLength(0)
        tailValid = false
        cursorPos = -1
        selectionEnd = -1
        justAutoSpaced = false
        lastSpaceTime = 0L
        revert = null
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
        canCompose = cls == InputType.TYPE_CLASS_TEXT && !noSuggest && !blocked

        applyPrefs()
        val m = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> Mode.NUMPAD
            InputType.TYPE_CLASS_DATETIME -> Mode.SYM1
            else -> Mode.LETTERS
        }
        setMode(m)
        showPanel(Panel.NONE)

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
        suggestJob?.cancel()
        correctJob?.cancel()
        info = null
        candidates = emptyList()
        composing.clear()
        tail.setLength(0)
        tailValid = false
        cursorPos = -1
        selectionEnd = -1
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentInputConnection?.finishComposingText()
        composing.clear()
        revert = null
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
        if (!::kb.isInitialized) return
        kbTheme = Themes.resolve(this, prefs)
        numberRow = prefs.getBoolean("number_row", false)
        suggestionsOn = prefs.getBoolean("suggestions", true)
        learnOn = prefs.getBoolean("learn_words", true)
        autoCaps = prefs.getBoolean("auto_caps", true)
        doubleSpace = prefs.getBoolean("double_space", true)
        autocorrectOn = prefs.getBoolean("autocorrect", true)
        emojiSuggest = prefs.getBoolean("emoji_suggestions", true)
        incognito = prefs.getBoolean("incognito", false)
        layoutName = prefs.getString("layout", "qwerty") ?: "qwerty"
        kb.trackpadEnabled = prefs.getBoolean("trackpad_mode", true)

        val locale = prefs.getString("locale", "en") ?: "en"
        scope.launch(Dispatchers.Default) {
            dict.configure(locale)
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
        kb.oneHanded = when (prefs.getString("one_handed", "off")) {
            "left" -> 1
            "right" -> 2
            else -> 0
        }
        kb.splitKeyboard = split
        kb.keyBorders = prefs.getBoolean("key_borders", true)
        kb.showHints = prefs.getBoolean("show_hints", true)
        kb.haptic = prefs.getBoolean("haptic", true)
        kb.sound = prefs.getBoolean("sound", false)
        kb.showPreview = prefs.getBoolean("popup_preview", true)
        kb.longPressMs = prefs.getInt("longpress_delay", 380).toLong()
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
        setMode(mode)
    }

    private fun loadBackground() {
        val f = File(filesDir, "bg.jpg")
        if (!f.exists()) {
            bgBmp = null
            bgStamp = -1L
            return
        }
        if (f.lastModified() == bgStamp && bgBmp != null) return
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / sample > 1400) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            bgBmp = BitmapFactory.decodeFile(f.path, o2)
            bgStamp = f.lastModified()
        } catch (_: Exception) {
            bgBmp = null
        }
    }

    private fun rebuildToolbar() {
        toolRow.removeAllViews()
        val enabled = prefs.getStringSet("toolbar_keys", null) ?: toolDefs.map { it.first }.toSet()
        for ((id, label) in toolDefs) {
            if (id !in enabled) continue
            val tv = TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(kbTheme.text)
                setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
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

    private fun refreshStrip() {
        if (!::strip.isInitialized) return
        val suggestionsActive = suggestionsOn && canCompose
        val showTools = toolbarOpen || !suggestionsActive
        slotRow.visibility = if (showTools) View.GONE else View.VISIBLE
        toolScroll.visibility = if (showTools) View.VISIBLE else View.GONE
        toggle.text = if (showTools) "‹" else "›"
        toggle.visibility = if (suggestionsActive) View.VISIBLE else View.GONE
        strip.visibility = if (panel == Panel.NONE) View.VISIBLE else View.GONE
    }

    private fun setMode(m: Mode) {
        mode = m
        kb.setRows(
            when (m) {
                Mode.LETTERS -> Layouts.letters(
                    layoutName, numberRow,
                    prefs.getString("custom_layout", "") ?: ""
                )
                Mode.SYM1 -> Layouts.symbols1()
                Mode.SYM2 -> Layouts.symbols2()
                Mode.NUMPAD -> Layouts.numpad()
            }
        )
        kb.shiftState = if (m == Mode.LETTERS) shiftState else 0
        kb.relayout()
    }

    private fun showPanel(p: Panel) {
        if (!::emoji.isInitialized) return
        if (p != Panel.NONE) endWord()
        panel = p
        kb.visibility = if (p == Panel.NONE) View.VISIBLE else View.GONE
        emoji.visibility = if (p == Panel.EMOJI) View.VISIBLE else View.GONE
        clipPanel.visibility = if (p == Panel.CLIP) View.VISIBLE else View.GONE
        if (p == Panel.CLIP) clipPanel.refresh()
        refreshStrip()
    }

    // ------------------------------------------------------------------ toolbar

    private fun onTool(id: String) {
        val ic = currentInputConnection
        when (id) {
            "clip" -> showPanel(Panel.CLIP)
            "emoji" -> showPanel(Panel.EMOJI)
            "numpad" -> { endWord(); setMode(Mode.NUMPAD) }
            "left" -> onCursor(-1)
            "right" -> onCursor(1)
            "selectall" -> ic?.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic?.performContextMenuAction(android.R.id.copy)
            "cut" -> ic?.performContextMenuAction(android.R.id.cut)
            "paste" -> { endWord(); ic?.performContextMenuAction(android.R.id.paste) }
            "undo" -> ic?.performContextMenuAction(android.R.id.undo)
            "incognito" -> {
                incognito = !incognito
                prefs.edit().putBoolean("incognito", incognito).apply()
                rebuildToolbar()
            }
            "onehand" -> {
                val next = when (prefs.getString("one_handed", "off")) {
                    "off" -> "left"; "left" -> "right"; else -> "off"
                }
                prefs.edit().putString("one_handed", next).apply()
                kb.oneHanded = when (next) {
                    "left" -> 1; "right" -> 2; else -> 0
                }
                kb.relayout()
            }
            "settings" -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        updateShiftFromCaps()
        updateSuggestions()
    }

    private fun captureClip() {
        if (!prefs.getBoolean("clipboard_history", true) || incognito) return
        try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            if (clip.description.extras
                    ?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            ) return
            val t = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (t.isBlank() || t.length > 2000) return
            clips.add(t)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ cached tail

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
        if (!tailValid) {
            val before = currentInputConnection?.getTextBeforeCursor(16, 0)?.toString() ?: return null
            tail.setLength(0)
            tail.append(before)
            tailValid = true
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
            Codes.SYMBOLS -> { endWord(); setMode(Mode.SYM1) }
            Codes.SYMBOLS2 -> setMode(Mode.SYM2)
            Codes.LETTERS -> { endWord(); setMode(Mode.LETTERS); updateShiftFromCaps() }
            Codes.EMOJI -> showPanel(Panel.EMOJI)
        }
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        val isWordChar = canCompose && mode == Mode.LETTERS && text.length == 1 &&
            (text[0].isLetter() || (text[0] == '\'' && composing.isNotEmpty()))
        if (isWordChar) {
            revert = null
            composing.append(text)
            ic.setComposingText(composing.toString(), 1)
            justAutoSpaced = false
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
            } finally {
                ic.endBatchEdit()
            }
            justAutoSpaced = false
        }
        lastSpaceTime = 0L
        afterText()
    }

    override fun onCursor(delta: Int) {
        endWord()
        val key = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        sendDownUpKeyEvents(key)
        tailValid = false
        updateShiftFromCaps()
        updateSuggestions()
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        var i = 0
        while (i < dx) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT); i++ }
        while (i > dx) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT); i-- }
        var j = 0
        while (j < dy) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN); j++ }
        while (j > dy) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP); j-- }
        tailValid = false
    }

    override fun onTrackpadEnter() {
        // Optional: could show a hint or dim the keyboard further
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
        val r = revert
        if (r != null) {
            revert = null
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(r.corrected.length + r.sep.length, 0)
                composing.clear()
                composing.append(r.original)
                ic.setComposingText(r.original, 1)
            } finally {
                ic.endBatchEdit()
            }
            ignored.add(r.original.lowercase())
            if (learnOn && !incognito) dict.learn(r.original)
        } else if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) ic.commitText("", 1)
            else ic.setComposingText(composing.toString(), 1)
            popTail(1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            popTail(1)
        }
        justAutoSpaced = false
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
            }
            val now = SystemClock.uptimeMillis()
            var done = false
            if (corrected == null && doubleSpace && now - lastSpaceTime < 500) {
                val before = if (tailValid) tail.toString()
                else currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
                if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    popTail(1)
                    appendTail(". ")
                    lastSpaceTime = 0L
                    done = true
                }
            }
            if (!done) {
                ic.commitText(" ", 1)
                appendTail(" ")
                lastSpaceTime = now
            }
        } finally {
            ic.endBatchEdit()
        }
        justAutoSpaced = false
        afterText()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        endWord()
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        tail.setLength(0)
        tailValid = false
        justAutoSpaced = false
        lastSpaceTime = 0L
        afterText()
    }

    private fun endWord(sep: String? = null): String? {
        revert = null
        val ic = currentInputConnection
        var corrected: String? = null
        if (composing.isNotEmpty()) {
            val w = composing.toString()
            if (sep != null && autocorrectOn && canCompose &&
                w.lowercase() !in ignored &&
                w.drop(1).none { it.isUpperCase() }
            ) {
                corrected = dict.correction(w)
            }
            if (corrected != null) {
                ic?.commitText(corrected, 1)
                revert = Revert(w, corrected, sep!!)
            } else {
                if (learnOn && !incognito && canCompose) {
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

    private fun afterText() {
        if (shiftState == 1) {
            shiftState = 0
            autoShifted = false
        }
        updateShiftFromCaps()
        updateSuggestions()
    }

    private fun updateShiftFromCaps() {
        if (!::kb.isInitialized) return
        if (mode != Mode.LETTERS) { kb.shiftState = 0; return }
        if (shiftState != 2) {
            val ei = info
            val ic = currentInputConnection
            if (autoCaps && ei != null && ic != null &&
                (ei.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
            ) {
                val caps = ic.getCursorCapsMode(ei.inputType)
                if (caps != 0 && shiftState == 0) {
                    shiftState = 1
                    autoShifted = true
                } else if (caps == 0 && autoShifted && shiftState == 1) {
                    shiftState = 0
                    autoShifted = false
                }
            }
        }
        kb.shiftState = shiftState
    }

    // ------------------------------------------------------------------ suggestions

    private fun atSentenceStart(): Boolean {
        val before = if (tailValid) tail.toString()
        else currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: return false
        val t = before.trimEnd()
        return t.isEmpty() || t.last() in ".!?"
    }

    private fun updateSuggestions() {
        if (!::slots.isInitialized) return
        suggestJob?.cancel()
        if (!suggestionsOn || !canCompose) {
            candidates = emptyList()
            renderSlots()
            return
        }
        val prefix = composing.toString()
        if (prefix.isEmpty()) {
            val prev1 = lastWord()
            val next = if (prev1 != null) dict.predictNext(null, prev1, 3) else emptyList()
            candidates = if (next.isNotEmpty()) next
            else if (atSentenceStart()) listOf("I", "The", "I'm")
            else emptyList()
            renderSlots()
            return
        }
        val emoji = if (emojiSuggest) EmojiKeywords.map[prefix.lowercase()] else null
        suggestJob = scope.launch {
            val list = LinkedHashSet<String>()
            if (!dict.contains(prefix)) list.add(prefix)
            list.addAll(dict.suggestAsync(prefix, 3))
            val words = list.take(3)
            val final = if (emoji != null) words.take(2) + emoji else words
            candidates = final
            renderSlots()
        }
    }

    private fun renderSlots() {
        if (!::slots.isInitialized) return
        slots.forEachIndexed { i, tv ->
            tv.text = candidates.getOrNull(i) ?: ""
            tv.setTypeface(null, if (i == 0) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun pickSuggestion(i: Int) {
        val w = candidates.getOrNull(i) ?: return
        val ic = currentInputConnection ?: return
        revert = null
        composing.clear()
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
            if (learnOn && !incognito && canCompose) {
                dict.learn(w, prev2 = null, prev1 = lastWord())
            }
            justAutoSpaced = true
        } else {
            justAutoSpaced = false
        }
        lastSpaceTime = 0L
        afterText()
    }
}
