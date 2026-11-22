package com.livesub.ai.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.widget.TextView
import com.livesub.ai.model.TextProtection

/**
 * Custom TextView yang menjaga keterbacaan teks subtitle di atas video terang/gelap
 * dengan 3 pilihan pelindung: Halo Bayangan, Outline Tipis (siaran), atau Scrim Mikro.
 */
class ReadableSubtitleTextView(context: Context) : TextView(context) {

    var protectionMode: TextProtection = TextProtection.HALO_SHADOW
        set(value) {
            field = value
            applyProtection()
            invalidate()
        }

    private val strokeColor: Int = Color.argb(235, 0, 0, 0)
    private val strokeWidthPx: Float = dpToPx(2.8f)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(148, 8, 10, 15)
        style = Paint.Style.FILL
    }
    private val scrimRect = RectF()

    init {
        setTextColor(Color.WHITE)
        applyProtection()
    }

    private fun applyProtection() {
        when (protectionMode) {
            TextProtection.HALO_SHADOW -> {
                setShadowLayer(dpToPx(6f), 0f, dpToPx(1.5f), Color.argb(245, 0, 0, 0))
            }
            TextProtection.THIN_OUTLINE -> {
                setShadowLayer(dpToPx(2f), 0f, dpToPx(1f), Color.argb(200, 0, 0, 0))
            }
            TextProtection.MICRO_SCRIM -> {
                setShadowLayer(dpToPx(2.5f), 0f, dpToPx(1f), Color.argb(190, 0, 0, 0))
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (protectionMode == TextProtection.MICRO_SCRIM && textLayout != null && !text.isNullOrBlank()) {
            val padH = dpToPx(6f)
            val padV = dpToPx(2f)
            val radius = dpToPx(6f)
            for (line in 0 until textLayout.lineCount) {
                val left = paddingLeft + textLayout.getLineLeft(line) - padH
                val right = paddingLeft + textLayout.getLineRight(line) + padH
                val top = paddingTop + textLayout.getLineTop(line) - padV
                val bottom = paddingTop + textLayout.getLineBottom(line) + padV
                scrimRect.set(left, top, right, bottom)
                canvas.drawRoundRect(scrimRect, radius, radius, scrimPaint)
            }
        }

        if (protectionMode == TextProtection.THIN_OUTLINE) {
            val originalColor = currentTextColor
            val paintRef = paint
            paintRef.style = Paint.Style.STROKE
            paintRef.strokeWidth = strokeWidthPx
            paintRef.strokeJoin = Paint.Join.ROUND
            setTextColor(strokeColor)
            super.onDraw(canvas)

            paintRef.style = Paint.Style.FILL
            setTextColor(originalColor)
            super.onDraw(canvas)
        } else {
            super.onDraw(canvas)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }
}
