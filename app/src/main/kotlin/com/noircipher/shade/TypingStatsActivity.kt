package com.noircipher.shade

import android.graphics.Color
import android.os.Bundle
import androidx.preference.PreferenceManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.noircipher.shade.analytics.TouchBiasModel
import com.noircipher.shade.analytics.TypingHeatmapView
import com.noircipher.shade.analytics.TypingStatsStore

class TypingStatsActivity : AppCompatActivity() {
    private lateinit var stats: TypingStatsStore
    private lateinit var touch: TouchBiasModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stats = TypingStatsStore(this)
        touch = TouchBiasModel(this)
        supportActionBar?.setTitle(R.string.typing_stats_title)
        window.statusBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.BLACK)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val toggle = Switch(this).apply {
            text = getString(R.string.typing_stats_toggle)
            isChecked = prefs.getBoolean("typing_insights", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("typing_insights", checked).apply()
            }
        }
        root.addView(toggle)

        val snap = stats.snapshot()
        root.addView(card("Average speed", "%.1f WPM".format(snap.averageWpm)))
        root.addView(card("Peak speed", "${snap.peakWpm} WPM"))
        root.addView(card("Typed characters", snap.totalChars.toString()))
        root.addView(card("Active typing", "${snap.activeMinutes} min"))
        root.addView(card("Backspaces", snap.backspaces.toString()))
        val acceptRate = if (snap.suggestionsShown > 0) (100.0 * snap.suggestionsAccepted / snap.suggestionsShown).toInt() else 0
        root.addView(card("Suggestion acceptance", "$acceptRate%"))
        root.addView(card("Autocorrect reverts", snap.autocorrectReverts.toString()))

        val wordsTitle = section("Top words")
        root.addView(wordsTitle)
        snap.topWords.forEach { root.addView(card(it.first, it.second.toString())) }

        val langTitle = section("Language usage")
        root.addView(langTitle)
        snap.languageCounts.forEach { root.addView(card(it.key, it.value.toString())) }

        root.addView(section("Typing heatmap"))
        val heatmap = TypingHeatmapView(this, touch)
        heatmap.minimumHeight = 520
        root.addView(heatmap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 560))


        val clear = Button(this).apply {
            text = getString(R.string.typing_stats_clear)
            setOnClickListener { stats.clear(); touch.clear(); recreate() }
        }
        root.addView(clear)
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 20f
        setTextColor(Color.WHITE)
        setPadding(0, 28, 0, 12)
    }

    private fun card(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20, 16, 20, 16)
        setBackgroundColor(0xFF0A0A0A.toInt())
        addView(TextView(this@TypingStatsActivity).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFBBBBBB.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@TypingStatsActivity).apply {
            text = value
            textSize = 16f
            setTextColor(Color.WHITE)
        })
    }
}
