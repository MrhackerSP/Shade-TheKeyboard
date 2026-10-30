package com.noircipher.shade.analytics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.noircipher.shade.KeyKind
import com.noircipher.shade.Layouts
import kotlin.math.max
import kotlin.math.min

class TypingHeatmapView(context: Context, private val model: TouchBiasModel) : View(context) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rows = Layouts.letters("qwerty", false, "")
        val maxCount = model.all().values.maxOfOrNull { it.count } ?: 1
        var y = 24f
        val rowHeight = (height - 48f) / rows.size
        for (row in rows) {
            val charKeys = row.filter { it.kind == KeyKind.CHAR }
            if (charKeys.isEmpty()) continue
            val totalWeight = charKeys.sumOf { it.weight.toDouble() }.toFloat()
            var x = 0f
            for (key in charKeys) {
                val w = width * (key.weight / totalWeight)
                val bias = model.bias(key.label)
                val intensity = if (bias == null) 0f else bias.count.toFloat() / maxCount
                val r = (80 + 120 * intensity).toInt()
                val g = (140 - 60 * intensity).toInt()
                val b = (255 - 120 * intensity).toInt()
                fillPaint.color = Color.argb((70 + 150 * intensity).toInt(), r, g, b)
                canvas.drawRoundRect(x + 3f, y + 3f, x + w - 3f, y + rowHeight - 3f, 14f, 14f, fillPaint)
                canvas.drawRoundRect(x + 3f, y + 3f, x + w - 3f, y + rowHeight - 3f, 14f, 14f, strokePaint)
                textPaint.textSize = min(30f, rowHeight * 0.42f)
                canvas.drawText(key.label.uppercase(), x + w / 2f, y + rowHeight / 2f + 10f, textPaint)
                bias?.let {
                    textPaint.textSize = min(20f, rowHeight * 0.28f)
                    canvas.drawText("${it.avgDx.toInt()},${it.avgDy.toInt()}", x + w / 2f, y + rowHeight - 10f, textPaint)
                }
                x += w
            }
            y += rowHeight
        }
    }
}
