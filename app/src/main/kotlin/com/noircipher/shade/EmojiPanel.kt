package com.noircipher.shade

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.abs

class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onDelete: () -> Unit
) : LinearLayout(context) {

    // Comprehensive emoji set — 8 categories covering the most-used Unicode emoji.
    // Each category has 40-60 emojis. Total ~400 emojis, matching what Gboard
    // and SwiftKey ship in their default panels (full Unicode 15.1 set is 3700+,
    // but most users only need the common ones; the rest are reachable via search).
    private val baseCategories: List<Pair<String, List<String>>> = listOf(
        // Smileys & emotion
        "😀" to listOf(
            "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊","😇","🥰","😍","🤩",
            "😘","😗","😚","😙","😋","😛","😜","🤪","😝","🤗","🤭","🫢","🫣","🤔","🫡","🤐",
            "🤨","😐","😑","😶","🫥","😏","😒","🙄","😬","😮‍💨","🤥","😌","😔","😪","🤤","😴",
            "😷","🤒","🤕","🤢","🤮","🤧","🥵","🥶","🥴","😵","🤯","🤠","🥳","🥸","😎","🤓",
            "🧐","😕","🫤","😟","🙁","☹️","😮","😯","😲","😳","🥺","🥹","😦","😧","😨","😰",
            "😥","😢","😭","😱","😖","😣","😞","😓","😩","😫","🥱","😤","😡","😠","🤬","😈",
            "👿","💀","☠️","💩","🤡","👹","👺","👻","👽","👾","🤖","😺","😸","😹","😻","😼",
            "😽","🙀","😿","😾","🙈","🙉","🙊","💌","💘","💝","💖","💗","💓","💞","💕","💟"
        ),
        // People & gestures
        "👍" to listOf(
            "👍","👎","👌","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","🖕","👇","☝️","🫵",
            "👋","🤚","✋","🖖","🤌","🤏","👏","🙌","🤝","🙏","✍️","💪","🦾","🦿","🦵","🦶",
            "👂","🦻","👃","🧠","🫀","🫁","🦷","🦴","👀","👁️","👅","👄","🫦","💋","🧒","👦",
            "👧","🧑","👱","👨","👩","🧓","👴","👵","🙇","💁","🙅","🙆","🙋","🧏","🤦","🤷",
            "💆","💇","🚶","🧍","🧎","🏃","💃","🕺","🕴️","👯","🧖","🧗","🤺","🏇","⛷️","🏂",
            "🏌️","🏄","🚣","🏊","⛹️","🏋️","🚴","🤸","🤼","🤽","🤾","🤹","👶","🤱","👼","🎅"
        ),
        // Animals & nature
        "🐻" to listOf(
            "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐽","🐸","🐵",
            "🙈","🙉","🙊","🐒","🐔","🐧","🐦","🐤","🐣","🐥","🦆","🦅","🦉","🦇","🐺","🐗",
            "🐴","🦄","🐝","🪱","🐛","🦋","🐌","🐞","🐜","🪰","🪲","🪳","🦟","🦗","🕷️","🕸️",
            "🦂","🐢","🐍","🦎","🦖","🦕","🐙","🦑","🦐","🦞","🦀","🐡","🐠","🐟","🐬","🐳",
            "🐋","🦈","🐊","🐅","🐆","🦓","🦍","🦧","🐘","🦣","🦛","🦏","🐪","🐫","🦒","🦘",
            "🦬","🐃","🐂","🐄","🐎","🐖","🐏","🐑","🦙","🐐","🦌","🐕","🐩","🦮","🐕‍🦺","🐈",
            "🐈‍⬛","🪶","🐓","🦃","🦤","🦚","🦜","🦢","🦩","🕊️","🐇","🦝","🦨","🦡","🦫","🦦",
            "🦥","🐁","🐀","🐿️","🦔","🐾","🐉","🐲","🌵","🎄","🌲","🌳","🌴","🪵","🌱","🌿",
            "☘️","🍀","🎍","🪴","🎋","🍃","🍂","🍁","🍄","🐚","🪨","🌾","💐","🌷","🌹","🥀",
            "🌺","🌸","🌼","🌻","🌞","🌝","🌛","🌜","🌚","🌕","🌖","🌗","🌘","🌑","🌒","🌓",
            "🌔","🌙","🌎","🌍","🌏","🪐","💫","⭐","🌟","✨","⚡","☄️","💥","🔥","🌪️","🌈",
            "☀️","🌤️","⛅","🌥️","☁️","🌦️","🌧️","⛈️","🌩️","🌨️","❄️","☃️","⛄","🌬️","💨","💧",
            "💦","☔","☂️","🌊","🌫️"
        ),
        // Food & drink
        "🍎" to listOf(
            "🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈","🍒","🍑","🥭","🍍","🥥",
            "🥝","🍅","🍆","🥑","🥦","🥬","🥒","🌶️","🫑","🌽","🥕","🫒","🧄","🧅","🥔","🍠",
            "🥐","🥯","🍞","🥖","🥨","🧀","🧈","🥚","🍳","🧇","🥞","🧊","🥓","🥩","🍗","🍖",
            "🦴","🌭","🍔","🍟","🍕","🥪","🥙","🧆","🌮","🌯","🫔","🥗","🥘","🫕","🥫","🍝",
            "🍜","🍲","🍛","🍣","🍱","🥟","🦪","🍤","🍙","🍚","🍘","🍥","🥠","🥮","🍢","🍡",
            "🍧","🍨","🍦","🥧","🧁","🍰","🎂","🍮","🍭","🍬","🍫","🍿","🍩","🍪","🌰","🥜",
            "🍯","🥛","🍼","🫖","☕","🍵","🧃","🥤","🧋","🍶","🍺","🍻","🥂","🍷","🥃","🍸",
            "🍹","🧉","🍾","🧊","🥄","🍴","🍽️","🥣","🥡","🥢","🧂"
        ),
        // Activities & sports
        "⚽" to listOf(
            "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🪀","🏓","🏸","🏒","🏑","🥍",
            "🏏","🪃","🥅","⛳","🪁","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷","⛸️","🥌",
            "🎿","⛷️","🏂","🪂","🏋️","🤼","🤸","⛹️","🤺","🤾","🏌️","🏇","🧘","🏄","🏊","🤽",
            "🚣","🧗","🚵","🚴","🏆","🥇","🥈","🥉","🏅","🎖️","🏵️","🎗️","🎫","🎟️","🎪","🤹",
            "🎭","🩰","🎨","🎬","🎤","🎧","🎼","🎹","🥁","🪘","🎷","🎺","🎸","🪕","🎻","🎲",
            "♟️","🎯","🎳","🎮","🎰","🧩"
        ),
        // Travel & places
        "🚗" to listOf(
            "🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🛻","🚚","🚛","🚜","🦯","🦽",
            "🦼","🛴","🚲","🛵","🏍️","🛺","🚨","🚔","🚍","🚘","🚖","🚡","🚠","🚟","🚃","🚋",
            "🚞","🚝","🚄","🚅","🚈","🚂","🚆","🚇","🚊","🚉","✈️","🛫","🛬","🛩️","💺","🛰️",
            "🚀","🛸","🚁","🛶","⛵","🚤","🛥️","🛳️","⛴️","🚢","⚓","🪝","⛽","🚧","🚦","🚥",
            "🚏","🗺️","🗿","🗽","🗼","🏰","🏯","🏟️","🎡","🎢","🎠","⛲","⛱️","🏖️","🏝️","🏜️",
            "🌋","⛰️","🏔️","🗻","🏕️","⛺","🛖","🏠","🏡","🏘️","🏚️","🏗️","🏭","🏢","🏬","🏣",
            "🏤","🏥","🏦","🏨","🏪","🏫","🏩","💒","🏛️","⛪","🕌","🕍","🛕","🕋","⛩️","🛤️",
            "🛣️","🗾","🎑","🏞️","🌅","🌄","🌠","🎇","🎆","🌇","🌆","🏙️","🌃","🌌","🌉","🌁"
        ),
        // Objects
        "💡" to listOf(
            "⌚","📱","📲","💻","⌨️","🖥️","🖨️","🖱️","🖲️","🕹️","🗜️","💽","💾","💿","📀","📼",
            "📷","📸","📹","🎥","📽️","🎞️","📞","☎️","📟","📠","📺","📻","🎙️","🎚️","🎛️","🧭",
            "⏱️","⏲️","⏰","🕰️","💡","🔦","🏮","🪔","📔","📕","📖","📗","📘","📙","📚","📓",
            "📒","📃","📜","📰","🗞️","📑","🔖","🏷️","💰","🪙","💴","💵","💶","💷","💸","💳",
            "🧾","✏️","✒️","🖋️","🖊️","🖌️","🖍️","📝","💼","📁","📂","🗂️","📅","📆","🗒️","🗓️",
            "📇","📈","📉","📊","📋","📌","📍","📎","🖇️","📏","📐","✂️","🗃️","🗄️","🗑️","🔒",
            "🔓","🔏","🔐","🔑","🗝️","🔨","🪓","⛏️","⚒️","🛠️","🗡️","⚔️","💣","🪃","🏹","🛡️",
            "🪚","🔧","🪛","🔩","⚙️","🗜️","⚖️","🦯","🔗","⛓️","🪝","🧰","🧲","🪜"
        ),
        // Symbols & hearts
        "❤️" to listOf(
            "❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖",
            "💘","💝","💟","❌","⭕","🛑","⛔","✅","✔️","❗","❓","⁉️","‼️","💲","💱","⚜️",
            "🔱","📛","🔰","✳️","❇️","©️","®️","™️","🔵","🔴","🟠","🟡","🟢","🟣","⚫","⚪",
            "🟤","🔺","🔻","🔸","🔹","🔶","🔷","🔳","🔲","▪️","▫️","◾","◽","◼️","◻️","🟥",
            "🟧","🟨","🟩","🟦","🟪","⬛","⬜","🟫","🔈","🔇","🔉","🔊","🔔","🔕","🎵","🎶",
            "➕","➖","➗","✖️","♾️","💤","💨","💦","♻️","⚠️","🚸","⚜️","🔱","🔰","⭕","✳️"
        )
    )

    /**
     * Base emojis that accept a Fitzpatrick skin-tone modifier. When long-pressed
     * a popup with the 5 skin tones is shown.
     */
    private val skinToneBases: Set<String> = setOf(
        "👋", "🤚", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
        "👈", "👉", "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊", "👊", "🤛", "🤜",
        "👏", "🙌", "🙏", "✍️", "💅", "🤝", "🫱", "🫲", "🫳", "🫴"
    )

    private val skinTones = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
    private val recentIcon = "🕘"

    private val recentPrefs =
        context.getSharedPreferences("shade_emoji_recent", Context.MODE_PRIVATE)
    private var recent: MutableList<String> = loadRecent()

    private var items: List<String> = baseCategories[0].second
    private val grid = GridView(context)
    private val bar = LinearLayout(context)
    private val abc = TextView(context)
    private val del = ImageView(context)
    private val suggestionScroll = HorizontalScrollView(context)
    private val suggestionRow = LinearLayout(context)
    private val tabs = ArrayList<TextView>()
    private var selected = 0
    private var theme: KbTheme = Themes.LIGHT

    private val searchBox = EditText(context)
    private val emptyView = TextView(context)
    var searchMode = false
        private set

    /**
     * Search results grid — shown ABOVE the search box when the user types a query.
     * The main [grid] is hidden during search; this grid takes its place.
     */
    private val searchGrid = GridView(context)
    private var searchResults: List<String> = emptyList()
    private val searchAdapter = object : BaseAdapter() {
        override fun getCount() = searchResults.size
        override fun getItem(position: Int) = searchResults[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 24f
                setPadding(0, dp(8f), 0, dp(8f))
            }
            tv.text = searchResults[position]
            return tv
        }
    }

    /**
     * Callbacks for the host (ShadeService) to show/hide the letter keyboard
     * when the user enters/exits emoji search mode.
     */
    var onSearchFocus: (() -> Unit)? = null
    var onSearchExit: (() -> Unit)? = null

    /**
     * The current search query text. Read by the host (ShadeService) when
     * the keyboard's backspace or letter keys are pressed during emoji
     * search — the host appends/removes characters here instead of the
     * editor. Setting it via [setSearchQuery] triggers [applySearch].
     */
    val searchQuery: String get() = searchBox.text.toString()

    /** Update the search box text from the host (keyboard input). */
    fun setSearchQuery(query: String) {
        searchBox.setText(query)
        searchBox.setSelection(query.length)
    }

    /**
     * Total number of selectable tabs = recent (1) + categories.
     * Used by the swipe-to-cycle-category gesture.
     */
    private val tabCount: Int get() = 1 + baseCategories.size

    /**
     * Gesture detector for vertical swipes on the emoji grid.
     *
     * WhatsApp-style: swiping UP on the grid cycles to the NEXT category AND
     * hides the category bar (slide-down animation). Swiping DOWN cycles to
     * the PREVIOUS category AND brings the bar back (slide-up animation).
     * The bar stays hidden until the user swipes down again — giving the
     * emoji grid more room while browsing.
     *
     * Horizontal swipes are left to the GridView (for scrolling within a
     * category). Only vertical swipes beyond the threshold trigger
     * a category change + bar toggle.
     */
    private val swipeThreshold: Int = dp(60f)
    private val swipeVelocityThreshold = 200f

    /** True when the category bar is currently hidden (slid out below). */
    private var barHidden = false
    /** Height of the category bar in px (for the slide animation distance). */
    private val barHeight: Int = dp(44f)
    /** Current slide animation (cancelled if a new swipe fires mid-animation). */
    private var barAnimator: ValueAnimator? = null

    private val gridGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            if (searchMode) return false  // don't cycle categories while searching
            if (e1 == null) return false
            val dy = e2.y - e1.y
            val dx = e2.x - e1.x
            // Only react to predominantly-vertical swipes (|dy| > |dx|)
            // so horizontal scrolling within a category still works.
            if (abs(dy) < abs(dx)) return false
            if (abs(dy) < swipeThreshold && abs(velocityY) < swipeVelocityThreshold) return false
            if (dy < 0) {
                // Swipe UP → next category + hide the bar
                select((selected + 1) % tabCount)
                hideBar()
            } else {
                // Swipe DOWN → previous category + show the bar
                select((selected - 1 + tabCount) % tabCount)
                showBar()
            }
            return true
        }
    })

    /**
     * Slide the category bar out of view (downward, below the keyboard).
     * The bar's height collapses to 0 so the grid expands to fill the space.
     */
    private fun hideBar() {
        if (barHidden) return
        barHidden = true
        barAnimator?.cancel()
        barAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                // Slide down: translationY goes from 0 to barHeight
                bar.translationY = barHeight * t
                // Fade out
                bar.alpha = 1f - t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bar.visibility = GONE
                    bar.translationY = 0f
                    bar.alpha = 1f
                    // Request layout so the grid expands to fill the bar's space
                    requestLayout()
                }
            })
            start()
        }
    }

    /**
     * Slide the category bar back into view (upward from below).
     * Restores the bar's height and the grid shrinks accordingly.
     */
    private fun showBar() {
        if (!barHidden) return
        barHidden = false
        bar.visibility = VISIBLE
        barAnimator?.cancel()
        barAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                // Slide up: translationY goes from barHeight to 0
                bar.translationY = barHeight * t
                // Fade in
                bar.alpha = 1f - t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bar.translationY = 0f
                    bar.alpha = 1f
                    requestLayout()
                }
            })
            start()
        }
    }

    /** keyword -> list of emojis, loaded lazily from assets/emoji/emoji_keywords_*.tsv */
    private val keywordMap: Map<String, List<String>> by lazy { loadKeywordMap() }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 24f
                setPadding(0, dp(8f), 0, dp(8f))
            }
            tv.text = items[position]
            return tv
        }
    }

    init {
        orientation = VERTICAL

        // ── Search box (top, always visible) ──────────────────────────────
        // In browse mode the search box shows the hint "Search emoji".
        // In search mode the user types here, and results appear in searchGrid below.
        searchBox.hint = context.getString(R.string.emoji_search_hint)
        searchBox.maxLines = 1
        searchBox.textSize = 14f
        searchBox.isSingleLine = true
        searchBox.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        searchBox.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(theme.keyFn)
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applySearch(s?.toString().orEmpty().trim())
            }
        })
        // When the search box gains focus, enter search mode — the host shows
        // the letter keyboard so the user can type, and the search results grid
        // appears below the search box.
        searchBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !searchMode) {
                enterSearch()
            }
        }
        addView(searchBox, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        // ── Suggestion row (below search box, hidden in search mode) ───────
        suggestionScroll.visibility = GONE
        suggestionScroll.isHorizontalScrollBarEnabled = false
        suggestionRow.orientation = HORIZONTAL
        suggestionScroll.addView(
            suggestionRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        )
        addView(suggestionScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        // ── Search results grid (hidden by default, shown in search mode) ──
        // This grid appears ABOVE the main grid when the user is searching.
        // It takes the same space as the main grid (weight 1).
        searchGrid.numColumns = 8
        searchGrid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        searchGrid.adapter = searchAdapter
        searchGrid.setSelector(android.R.color.transparent)
        searchGrid.visibility = GONE
        searchGrid.setOnItemClickListener { _, _, pos, _ ->
            val emoji = searchResults[pos]
            // Record to recents and insert — pressing back from search
            // updates only recents, everything else stays the same.
            recordRecent(emoji)
            onEmoji(emoji)
            // Stay in search mode so the user can pick more results.
            // The user exits search by pressing back.
        }
        addView(searchGrid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Main emoji grid ────────────────────────────────────────────────
        grid.numColumns = 8
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = adapter
        grid.setSelector(android.R.color.transparent)

        // WhatsApp-style vertical swipe to cycle categories.
        grid.setOnTouchListener { _, event ->
            gridGestureDetector.onTouchEvent(event)
            false  // let the GridView still handle scrolling
        }

        grid.setOnItemClickListener { _, _, pos, _ ->
            val emoji = items[pos]
            recordRecent(emoji)
            onEmoji(emoji)
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val emoji = items[pos]
            val base = skinToneBases.firstOrNull { emoji.startsWith(it) }
            if (base == null) {
                false
            } else {
                val child = grid.getChildAt(pos - grid.firstVisiblePosition)
                if (child != null) showSkinTonePopup(child, base)
                true
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Empty view (no search results) ────────────────────────────────
        emptyView.text = context.getString(R.string.emoji_no_results)
        emptyView.gravity = Gravity.CENTER
        emptyView.visibility = GONE
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        // ── Category bar (bottom, slides down/up on swipe) ────────────────
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        abc.text = context.getString(R.string.abc)
        abc.gravity = Gravity.CENTER
        abc.textSize = 14f
        abc.setOnClickListener { onBack() }
        bar.addView(abc, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))

        // Recent tab is always present (first), so the user can review their recents
        val recentTab = TextView(context).apply {
            text = recentIcon
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { select(0) }
        }
        tabs.add(recentTab)
        bar.addView(recentTab, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        baseCategories.forEachIndexed { i, (icon, _) ->
            val t = TextView(context).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener { select(i + 1) }
            }
            tabs.add(t)
            bar.addView(t, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        del.setImageResource(R.drawable.ic_backspace)
        del.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        del.setOnClickListener { onDelete() }
        bar.addView(del, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        select(if (recent.isNotEmpty()) 0 else 1)
    }

    private fun select(i: Int) {
        // Selecting a category tab exits search mode (if active) and shows the
        // chosen category. The user can re-enter search by typing in the box.
        if (searchMode) {
            exitSearch()
        }
        selected = i
        items = when (i) {
            0 -> if (recent.isNotEmpty()) recent else baseCategories[0].second
            else -> baseCategories[i - 1].second
        }
        // Cap recents at 4 rows (32 emojis = 8 columns × 4 rows)
        if (i == 0 && items.size > MAX_RECENT_ROWS * 8) {
            items = items.take(MAX_RECENT_ROWS * 8)
        }
        emptyView.visibility = GONE
        grid.visibility = VISIBLE
        searchGrid.visibility = GONE
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        paintTabs()
    }

    /**
     * Enter search mode: hide the main grid + suggestion row, show the search
     * results grid, and notify the host (ShadeService) to show the letter
     * keyboard so the user can type a query.
     *
     * The search box stays at the top; the search results appear in [searchGrid]
     * below it. The main [grid] and the category bar are hidden so the search
     * results have maximum room.
     */
    private fun enterSearch() {
        if (searchMode) return
        searchMode = true
        // Hide the suggestion row — the search box replaces it
        suggestionScroll.visibility = GONE
        // Show the search results grid, hide the main grid
        searchGrid.visibility = VISIBLE
        grid.visibility = GONE
        // Hide the category bar while searching — it takes up room and isn't
        // useful in search mode. It comes back when search exits.
        bar.visibility = GONE
        // Notify the host to show the letter keyboard
        onSearchFocus?.invoke()
        paintTabs()
    }

    /**
     * Exit search mode: clear the search query, hide the search results grid,
     * restore the main grid + category bar, and notify the host to hide the
     * letter keyboard.
     *
     * Recents have already been updated if the user tapped a search result —
     * pressing back from search changes ONLY recents, everything else stays
     * the same (the previously-selected category is re-shown).
     */
    fun exitSearch() {
        if (!searchMode) return
        searchMode = false
        searchBox.setText("")
        searchBox.clearFocus()
        // Hide the search results grid
        searchGrid.visibility = GONE
        searchResults = emptyList()
        searchAdapter.notifyDataSetChanged()
        // Restore the main grid + category bar
        grid.visibility = VISIBLE
        if (!barHidden) bar.visibility = VISIBLE
        // Restore the suggestion row if there are suggestions
        if (suggestionRow.childCount > 0) suggestionScroll.visibility = VISIBLE
        // Notify the host to hide the letter keyboard
        onSearchExit?.invoke()
        // Re-select the current category to refresh the grid
        select(selected)
    }

    private fun applySearch(query: String) {
        if (query.isEmpty()) {
            // Clear results but stay in search mode — the user might still
            // be typing. The search box has focus, so they can continue.
            searchResults = emptyList()
            searchAdapter.notifyDataSetChanged()
            emptyView.visibility = if (searchResults.isEmpty()) VISIBLE else GONE
            return
        }

        // Tokenize the query on whitespace and match ALL tokens (AND match).
        val tokens = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            searchResults = emptyList()
            searchAdapter.notifyDataSetChanged()
            return
        }

        // Score each emoji by how well it matches.
        data class Scored(val emoji: String, val score: Float)
        val scored = ArrayList<Scored>()

        for ((keyword, emojis) in keywordMap) {
            val kwLower = keyword.lowercase()
            if (!tokens.all { kwLower.contains(it) }) continue
            var score = keyword.length.toFloat()
            for (tok in tokens) {
                if (kwLower == tok) score -= 10f
                else if (kwLower.startsWith(tok)) score -= 5f
            }
            for (e in emojis) {
                scored.add(Scored(e, score))
            }
        }

        // Sort by score (lower = better), then deduplicate
        val results = LinkedHashSet<String>()
        for ((emoji, _) in scored.sortedBy { it.score }) {
            results.add(emoji)
            if (results.size >= 96) break
        }

        searchResults = results.toList()
        emptyView.visibility = if (searchResults.isEmpty()) VISIBLE else GONE
        searchGrid.visibility = if (searchResults.isEmpty()) GONE else VISIBLE
        searchAdapter.notifyDataSetChanged()
        searchGrid.setSelection(0)
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, t ->
            t.background = if (i == selected && !searchMode) GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(theme.keyFn)
            } else null
        }
    }

    private fun showSkinTonePopup(anchor: View, base: String) {
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(theme.keyFn)
            }
        }
        // The base (no modifier) first, then the 5 Fitzpatrick variants.
        val variants = listOf(base) + skinTones.map { base + it }
        // IMPORTANT: the popup must NOT be focusable. A focusable PopupWindow
        // steals window focus from the IME, which makes the framework restart
        // the input view (onStartInputView with restarting=true) — that resets
        // the panel to NONE, hiding the emoji grid and showing the letter
        // keyboard behind the popup. A non-focusable popup keeps the IME's
        // focus; child click handlers still fire because the popup window still
        // receives touches on its own content.
        val popup = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        popup.elevation = dp(6f).toFloat()
        popup.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                popup.dismiss()
                true
            } else false
        }
        variants.forEach { variant ->
            val tv = TextView(context).apply {
                text = variant
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                setOnClickListener {
                    recordRecent(variant)
                    onEmoji(variant)
                    popup.dismiss()
                }
            }
            container.addView(tv)
        }
        popup.showAsDropDown(anchor, 0, -anchor.height)
    }

    private fun loadRecent(): MutableList<String> {
        val set = recentPrefs.getStringSet("items", emptySet()) ?: return mutableListOf()
        // Order isn't preserved by StringSet, so we re-sort by the recorded order map.
        val order = recentPrefs.getString("order", "").orEmpty()
            .split('\n').filter { it.isNotEmpty() }
        val ordered = order.filter { set.contains(it) }.toMutableList()
        // Append any stray entries that aren't in the order list (defensive).
        for (e in set) if (e !in ordered) ordered.add(e)
        while (ordered.size > MAX_RECENT) ordered.removeAt(ordered.size - 1)
        return ordered
    }

    /** Records [emoji] as the most-recently-used. Persisted to SharedPreferences. */
    fun recordRecent(emoji: String) {
        if (emoji.isBlank()) return
        recent.removeAll { it == emoji }
        recent.add(0, emoji)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
        recentPrefs.edit()
            .putStringSet("items", recent.toSet())
            .putString("order", recent.joinToString("\n"))
            .apply()
        // If we're currently showing the recent category, refresh it (capped at 4 rows)
        if (selected == 0 && !searchMode) {
            items = recent.take(MAX_RECENT)
            adapter.notifyDataSetChanged()
        }
    }

    private fun loadKeywordMap(): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (tag in listOf("en", "hi", "concepts")) {
            try {
                context.assets.open("emoji/emoji_keywords_${tag}.tsv").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val parts = line.split('\t')
                        if (parts.size < 2) continue
                        val keyword = parts[0].trim().lowercase()
                        val emoji = parts[1].trim()
                        if (keyword.isNotEmpty() && emoji.isNotEmpty()) {
                            map.getOrPut(keyword) { mutableListOf() }.add(emoji)
                        }
                    }
                }
            } catch (_: Exception) {
                // Asset missing for this language; continue to the next.
            }
        }
        return map
    }

    fun setSuggestions(suggestions: List<String>) {
        suggestionRow.removeAllViews()
        // Don't show suggestions while in search mode — the search box is
        // the active input element.
        if (searchMode || suggestions.isEmpty()) {
            suggestionScroll.visibility = GONE
            return
        }

        suggestionScroll.visibility = VISIBLE
        suggestions.take(10).forEach { emoji ->
            val tv = TextView(context).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(dp(12f), 0, dp(12f), 0)
                setOnClickListener {
                    recordRecent(emoji)
                    onEmoji(emoji)
                }
            }
            suggestionRow.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        abc.setTextColor(t.text)
        del.setColorFilter(t.text)
        searchBox.setTextColor(t.text)
        searchBox.setHintTextColor(t.hint)
        searchBox.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(t.keyFn)
        }
        emptyView.setTextColor(t.hint)
        for (i in 0 until suggestionRow.childCount) {
            (suggestionRow.getChildAt(i) as? TextView)?.setTextColor(t.text)
        }
        paintTabs()
    }

    /**
     * Called when the emoji panel is about to be shown (from ShadeService's
     * transitionToPanel). Resets the bar to visible in case the user hid it
     * last time, and exits search mode if it was left active.
     */
    fun onShow() {
        // Exit search mode if it was left active (e.g. user navigated away
        // while searching). Recents are preserved.
        if (searchMode) exitSearch()
        // Reset the category bar to visible
        if (barHidden) {
            barHidden = false
            bar.visibility = VISIBLE
            bar.translationY = 0f
            bar.alpha = 1f
            barAnimator?.cancel()
            requestLayout()
        }
        // Re-select the current category to refresh the grid
        select(if (recent.isNotEmpty()) 0 else 1)
    }

    /**
     * Handle the system back button. If in search mode, exit search and
     * return to browse mode (recents already updated). Otherwise return
     * false so the host handles back (closes the emoji panel).
     *
     * @return true if the back press was consumed (search mode exited),
     *   false if the host should handle it.
     */
    fun onBackPressed(): Boolean {
        if (searchMode) {
            exitSearch()
            return true
        }
        return false
    }

    companion object {
        /** Max recent emojis stored = 4 rows × 8 columns = 32 visible at once. */
        private const val MAX_RECENT_ROWS = 4
        private const val MAX_RECENT = MAX_RECENT_ROWS * 8  // 32
    }
}


/** Keyword -> emoji for inline emoji suggestions while typing. */
object EmojiKeywords {
    val map: Map<String, String> = mapOf(
        "love" to "❤️", "heart" to "❤️", "happy" to "😊", "smile" to "😀", "laugh" to "😂",
        "lol" to "😂", "sad" to "😢", "cry" to "😭", "angry" to "😠", "cool" to "😎",
        "thanks" to "🙏", "thank" to "🙏", "please" to "🙏", "pray" to "🙏", "yes" to "👍",
        "ok" to "👌", "okay" to "👌", "good" to "👍", "no" to "👎", "bad" to "👎", "party" to "🎉",
        "birthday" to "🎂", "congrats" to "🎉", "congratulations" to "🎉", "fire" to "🔥",
        "hot" to "🔥", "star" to "⭐", "sun" to "☀️", "moon" to "🌙", "rain" to "☔",
        "snow" to "❄️", "cat" to "🐱", "dog" to "🐶", "pizza" to "🍕", "coffee" to "☕",
        "beer" to "🍺", "food" to "🍔", "car" to "🚗", "home" to "🏠", "house" to "🏠",
        "phone" to "📱", "money" to "💰", "gift" to "🎁", "music" to "🎵", "book" to "📚",
        "sleep" to "😴", "tired" to "😴", "think" to "🤔", "wow" to "😮", "sorry" to "🙏",
        "hello" to "👋", "hi" to "👋", "bye" to "👋", "goodbye" to "👋", "strong" to "💪",
        "idea" to "💡", "time" to "⏰", "world" to "🌍", "rocket" to "🚀", "flower" to "🌸"
    )
}
