package com.livesub.ai.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.livesub.ai.R
import com.livesub.ai.model.LanguageCatalog
import com.livesub.ai.model.LiveSubRuntimeState
import com.livesub.ai.model.OverlayConfig
import com.livesub.ai.model.ServiceStatus
import com.livesub.ai.model.SurfaceStyle
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayViewController(
    private val context: Context,
    private val onFontSizeDelta: (Float) -> Unit,
    private val onCycleStyle: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onSwitchToMic: () -> Unit,
    private val onCloseOverlay: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootContainer: LinearLayout? = null
    private var subtitleCard: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var originalTranscriptText: TextView? = null
    private var subtitleText: ReadableSubtitleTextView? = null
    private var fallbackMicButton: TextView? = null
    private var menuIconButton: ImageView? = null
    private var controlPillContainer: LinearLayout? = null
    private var fontSizeIndicator: TextView? = null
    private var stylePillButton: TextView? = null
    private var pausePillButton: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isMenuExpanded: Boolean = false

    fun show(config: OverlayConfig, runtime: LiveSubRuntimeState) {
        if (rootContainer != null) {
            render(config, runtime)
            return
        }

        isMenuExpanded = false
        val dm = context.resources.displayMetrics
        val cardWidth = (dm.widthPixels * 0.90f).roundToInt().coerceAtMost(dp(440))

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
            y = (dm.heightPixels * 0.62f).roundToInt()
        }
        layoutParams = params

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(12))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.argb(235, 147, 197, 253))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.argb(220, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val menuBtn = createCornerIconButton(
            drawableRes = R.drawable.ic_overlay_menu,
            description = "Menu kontrol",
            active = false
        ).apply {
            setOnClickListener { toggleMenuVisibility() }
        }

        val closeBtn = createCornerIconButton(
            drawableRes = R.drawable.ic_overlay_close,
            description = "Tutup overlay",
            active = false
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginStart = dp(6)
            }
            setOnClickListener { onCloseOverlay() }
        }

        topBar.addView(statusTv)
        topBar.addView(menuBtn)
        topBar.addView(closeBtn)

        val origTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.argb(205, 226, 232, 240))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.argb(230, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginEnd = dp(4)
            }
        }

        val subTv = ReadableSubtitleTextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1.08f)
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                marginEnd = dp(4)
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
        card.addView(origTv)
        card.addView(subTv)
        card.addView(micBtn)

        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(Color.argb(228, 15, 23, 42))
                setStroke(dp(1), Color.argb(85, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }

        val fontDownBtn = createPillButton("A-", horizontalPadDp = 10).apply {
            setOnClickListener { onFontSizeDelta(-1f) }
        }
        val fontSizeTv = TextView(context).apply {
            text = "${config.fontSizeSp.roundToInt()}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.argb(215, 148, 163, 184))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        val fontUpBtn = createPillButton("A+", horizontalPadDp = 10).apply {
            setOnClickListener { onFontSizeDelta(1f) }
        }

        val divider = View(context).apply {
            setBackgroundColor(Color.argb(65, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }

        val styleBtn = createPillButton(config.surfaceStyle.shortLabel, horizontalPadDp = 10).apply {
            setOnClickListener { onCycleStyle() }
        }
        val pauseBtn = createPillButton("Jeda", horizontalPadDp = 10).apply {
            setOnClickListener { onTogglePause() }
        }

        pillRow.addView(fontDownBtn)
        pillRow.addView(fontSizeTv)
        pillRow.addView(fontUpBtn)
        pillRow.addView(divider)
        pillRow.addView(styleBtn)
        pillRow.addView(pauseBtn)

        attachDragListener(card)

        root.addView(card)
        root.addView(pillRow)

        rootContainer = root
        subtitleCard = card
        statusLabel = statusTv
        originalTranscriptText = origTv
        subtitleText = subTv
        fallbackMicButton = micBtn
        menuIconButton = menuBtn
        controlPillContainer = pillRow
        fontSizeIndicator = fontSizeTv
        stylePillButton = styleBtn
        pausePillButton = pauseBtn

        windowManager.addView(root, params)
        render(config, runtime)
    }

    fun render(config: OverlayConfig, runtime: LiveSubRuntimeState) {
        val root = rootContainer ?: return
        val card = subtitleCard ?: return
        val params = layoutParams ?: return

        val targetShort = LanguageCatalog.findLanguageByCode(config.targetLangCode).shortCode
        val latencyBadge = if (runtime.translateLatencyMs > 0L) " · ${runtime.translateLatencyMs}ms" else ""
        statusLabel?.text = when (runtime.status) {
            ServiceStatus.DRM_BLOCKED -> "Audio dilindungi DRM"
            ServiceStatus.PAUSED -> "Terjemahan dijeda"
            ServiceStatus.OFFLINE -> "Koneksi terputus"
            else -> "${runtime.status.label} [${runtime.activeProviderTag} → $targetShort$latencyBadge]"
        }

        if (config.showOriginalText && runtime.lastSourceTranscript.isNotBlank()) {
            originalTranscriptText?.text = runtime.lastSourceTranscript
            originalTranscriptText?.visibility = View.VISIBLE
        } else {
            originalTranscriptText?.visibility = View.GONE
        }

        subtitleText?.apply {
            text = runtime.currentSubtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
            protectionMode = config.textProtection
        }

        fallbackMicButton?.visibility =
            if (runtime.status == ServiceStatus.DRM_BLOCKED) View.VISIBLE else View.GONE

        controlPillContainer?.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
        menuIconButton?.background = createCornerIconBackground(active = isMenuExpanded)

        fontSizeIndicator?.text = "${config.fontSizeSp.roundToInt()}"
        stylePillButton?.text = config.surfaceStyle.shortLabel
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
        originalTranscriptText = null
        subtitleText = null
        fallbackMicButton = null
        menuIconButton = null
        controlPillContainer = null
        fontSizeIndicator = null
        stylePillButton = null
        pausePillButton = null
        layoutParams = null
        isMenuExpanded = false
    }

    private fun toggleMenuVisibility() {
        isMenuExpanded = !isMenuExpanded
        controlPillContainer?.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
        menuIconButton?.background = createCornerIconBackground(active = isMenuExpanded)
        val root = rootContainer ?: return
        val params = layoutParams ?: return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: IllegalArgumentException) {
        }
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

    private fun createCornerIconButton(
        drawableRes: Int,
        description: String,
        active: Boolean
    ): ImageView {
        return ImageView(context).apply {
            setImageResource(drawableRes)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = createCornerIconBackground(active)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
    }

    private fun createCornerIconBackground(active: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (active) {
                setColor(Color.argb(175, 59, 130, 246))
                setStroke(dp(1), Color.argb(150, 255, 255, 255))
            } else {
                setColor(Color.argb(120, 15, 23, 42))
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
        }
    }

    private fun createPillButton(label: String, horizontalPadDp: Int = 11): TextView {
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(38)
            setPadding(dp(horizontalPadDp), dp(7), dp(horizontalPadDp), dp(7))
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

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).roundToInt()
    }
}
