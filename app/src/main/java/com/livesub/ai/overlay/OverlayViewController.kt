package com.livesub.ai.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.livesub.ai.model.LiveSubRuntimeState
import com.livesub.ai.model.OverlayConfig
import com.livesub.ai.model.ServiceStatus
import com.livesub.ai.model.SurfaceStyle
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayViewController(
    private val context: Context,
    private val onCycleStyle: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onSwitchToMic: () -> Unit,
    private val onCloseOverlay: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootContainer: LinearLayout? = null
    private var subtitleCard: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var subtitleText: ReadableSubtitleTextView? = null
    private var fallbackMicButton: TextView? = null
    private var stylePillButton: TextView? = null
    private var pausePillButton: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show(config: OverlayConfig, runtime: LiveSubRuntimeState) {
        if (rootContainer != null) {
            render(config, runtime)
            return
        }

        val dm = context.resources.displayMetrics
        val cardWidth = (dm.widthPixels * 0.88f).roundToInt().coerceAtMost(dp(440))

        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (dm.heightPixels * 0.64f).roundToInt()
        }
        layoutParams = params

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val statusTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.argb(230, 147, 197, 253))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.argb(220, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dragHintTv = TextView(context).apply {
            text = "::: Geser"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.argb(215, 226, 232, 240))
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.argb(220, 0, 0, 0))
        }

        topBar.addView(statusTv)
        topBar.addView(dragHintTv)

        val subTv = ReadableSubtitleTextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1.08f)
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }

        val micBtn = createActionChip("Pakai mikrofon", Color.argb(230, 217, 119, 6)).apply {
            visibility = View.GONE
            setOnClickListener { onSwitchToMic() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        card.addView(topBar)
        card.addView(subTv)
        card.addView(micBtn)

        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(Color.argb(215, 15, 23, 42))
                setStroke(dp(1), Color.argb(75, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val styleBtn = createPillButton("Glass Hitam").apply {
            setOnClickListener { onCycleStyle() }
        }
        val pauseBtn = createPillButton("Jeda").apply {
            setOnClickListener { onTogglePause() }
        }
        val closeBtn = createPillButton("Tutup").apply {
            setTextColor(Color.rgb(252, 165, 165))
            setOnClickListener { onCloseOverlay() }
        }

        pillRow.addView(styleBtn)
        pillRow.addView(pauseBtn)
        pillRow.addView(closeBtn)

        attachDragListener(card)

        root.addView(card)
        root.addView(pillRow)

        rootContainer = root
        subtitleCard = card
        statusLabel = statusTv
        subtitleText = subTv
        fallbackMicButton = micBtn
        stylePillButton = styleBtn
        pausePillButton = pauseBtn

        windowManager.addView(root, params)
        render(config, runtime)
    }

    fun render(config: OverlayConfig, runtime: LiveSubRuntimeState) {
        val root = rootContainer ?: return
        val card = subtitleCard ?: return
        val params = layoutParams ?: return

        statusLabel?.text = when (runtime.status) {
            ServiceStatus.DRM_BLOCKED -> "Audio dilindungi DRM"
            ServiceStatus.PAUSED -> "Terjemahan dijeda"
            ServiceStatus.OFFLINE -> "Koneksi terputus"
            else -> "${runtime.status.label} (${shortLang(config.targetLanguage)})"
        }

        subtitleText?.apply {
            text = runtime.currentSubtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
            protectionMode = config.textProtection
        }

        fallbackMicButton?.visibility =
            if (runtime.status == ServiceStatus.DRM_BLOCKED) View.VISIBLE else View.GONE

        stylePillButton?.text = config.surfaceStyle.label
        pausePillButton?.text = if (runtime.isPaused) "Lanjut" else "Jeda"

        applyGlassSurface(card, params, config)

        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun dismiss() {
        val root = rootContainer ?: return
        try {
            windowManager.removeView(root)
        } catch (_: IllegalArgumentException) {
        }
        rootContainer = null
        subtitleCard = null
        statusLabel = null
        subtitleText = null
        fallbackMicButton = null
        stylePillButton = null
        pausePillButton = null
        layoutParams = null
    }

    private fun applyGlassSurface(
        card: LinearLayout,
        params: WindowManager.LayoutParams,
        config: OverlayConfig
    ) {
        val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            windowManager.isCrossWindowBlurEnabled

        val effectiveBlur = when (config.surfaceStyle) {
            SurfaceStyle.SOLID_DARK -> 0
            SurfaceStyle.GLASS_DARK -> config.blurRadiusPx.coerceIn(0, 60)
            SurfaceStyle.GLASS_CLEAR -> config.blurRadiusPx.coerceIn(0, 60)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (effectiveBlur > 0 && blurSupported) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = effectiveBlur
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                params.blurBehindRadius = 0
            }
        }

        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            when (config.surfaceStyle) {
                SurfaceStyle.SOLID_DARK -> {
                    val alpha = (config.tintOpacity.coerceIn(0.75f, 0.96f) * 255).roundToInt()
                    setColor(Color.argb(alpha, 12, 14, 20))
                    setStroke(dp(1), Color.argb(45, 255, 255, 255))
                }
                SurfaceStyle.GLASS_DARK -> {
                    val baseOpacity = if (blurSupported) config.tintOpacity else (config.tintOpacity + 0.18f)
                    val alpha = (baseOpacity.coerceIn(0.28f, 0.88f) * 255).roundToInt()
                    setColor(Color.argb(alpha, 10, 14, 24))
                    setStroke(dp(1), Color.argb(95, 255, 255, 255))
                }
                SurfaceStyle.GLASS_CLEAR -> {
                    val baseOpacity = if (blurSupported) (config.tintOpacity * 0.36f) else 0.34f
                    val alpha = (baseOpacity.coerceIn(0.08f, 0.45f) * 255).roundToInt()
                    setColor(Color.argb(alpha, 255, 255, 255))
                    setStroke(dp(1), Color.argb(145, 255, 255, 255))
                }
            }
        }
        card.background = bg
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(target: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        target.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val root = rootContainer ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).roundToInt()
                    val dy = (event.rawY - initialTouchY).roundToInt()
                    if (abs(dx) > 2 || abs(dy) > 2) {
                        params.x = initialX + dx
                        params.y = (initialY + dy).coerceAtLeast(dp(24))
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun createPillButton(label: String): TextView {
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
    }

    private fun createActionChip(label: String, bgColor: Int): TextView {
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(bgColor)
            }
        }
    }

    private fun shortLang(language: String): String {
        return when {
            language.contains("Indonesia", ignoreCase = true) -> "ID"
            language.contains("Inggris", ignoreCase = true) -> "EN"
            language.contains("Jepang", ignoreCase = true) -> "JA"
            language.contains("Korea", ignoreCase = true) -> "KO"
            language.contains("Arab", ignoreCase = true) -> "AR"
            language.contains("Mandarin", ignoreCase = true) -> "ZH"
            else -> language.take(2).uppercase()
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).roundToInt()
    }
}
