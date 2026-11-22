package com.livesub.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.livesub.ai.model.LiveSubRuntimeState
import com.livesub.ai.model.OverlayConfig
import com.livesub.ai.model.SurfaceStyle
import com.livesub.ai.model.TextProtection
import com.livesub.ai.model.TranslationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object LiveSubStateHolder {
    private val _config = MutableStateFlow(OverlayConfig())
    val config: StateFlow<OverlayConfig> = _config.asStateFlow()

    private val _runtime = MutableStateFlow(LiveSubRuntimeState())
    val runtime: StateFlow<LiveSubRuntimeState> = _runtime.asStateFlow()

    private val _sharedVideoUrl = MutableStateFlow<String?>(null)
    val sharedVideoUrl: StateFlow<String?> = _sharedVideoUrl.asStateFlow()

    fun updateConfig(transform: (OverlayConfig) -> OverlayConfig) {
        _config.update(transform)
    }

    fun updateRuntime(transform: (LiveSubRuntimeState) -> LiveSubRuntimeState) {
        _runtime.update(transform)
    }

    fun setSharedVideoUrl(url: String?) {
        _sharedVideoUrl.value = url
    }
}

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadIntoState(): OverlayConfig {
        val styleName = prefs.getString(KEY_SURFACE_STYLE, SurfaceStyle.GLASS_DARK.name)
        val protName = prefs.getString(KEY_TEXT_PROTECTION, TextProtection.HALO_SHADOW.name)
        val engineName = prefs.getString(KEY_ENGINE, TranslationEngine.LIVE_WEBSOCKET.name)
        val savedRestModel = prefs.getString(KEY_MODEL, DEFAULT_REST_MODEL) ?: DEFAULT_REST_MODEL
        val migratedRestModel = if (savedRestModel == "gemini-2.5-flash" || savedRestModel.isBlank()) {
            DEFAULT_REST_MODEL
        } else {
            savedRestModel
        }

        val config = OverlayConfig(
            sourceLanguage = prefs.getString(KEY_SOURCE_LANG, "Deteksi otomatis") ?: "Deteksi otomatis",
            targetLanguage = prefs.getString(KEY_TARGET_LANG, "Bahasa Indonesia") ?: "Bahasa Indonesia",
            surfaceStyle = SurfaceStyle.entries.find { it.name == styleName } ?: SurfaceStyle.GLASS_DARK,
            textProtection = TextProtection.entries.find { it.name == protName } ?: TextProtection.HALO_SHADOW,
            translationEngine = TranslationEngine.entries.find { it.name == engineName }
                ?: TranslationEngine.LIVE_WEBSOCKET,
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, 18f),
            blurRadiusPx = prefs.getInt(KEY_BLUR_RADIUS, 28),
            tintOpacity = prefs.getFloat(KEY_TINT_OPACITY, 0.56f),
            geminiApiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            liveModel = prefs.getString(KEY_LIVE_MODEL, "auto")?.ifBlank { "auto" } ?: "auto",
            geminiModel = migratedRestModel
        )
        LiveSubStateHolder.updateConfig { config }
        return config
    }

    fun save(config: OverlayConfig) {
        prefs.edit()
            .putString(KEY_SOURCE_LANG, config.sourceLanguage)
            .putString(KEY_TARGET_LANG, config.targetLanguage)
            .putString(KEY_SURFACE_STYLE, config.surfaceStyle.name)
            .putString(KEY_TEXT_PROTECTION, config.textProtection.name)
            .putString(KEY_ENGINE, config.translationEngine.name)
            .putFloat(KEY_FONT_SIZE, config.fontSizeSp)
            .putInt(KEY_BLUR_RADIUS, config.blurRadiusPx)
            .putFloat(KEY_TINT_OPACITY, config.tintOpacity)
            .putString(KEY_API_KEY, config.geminiApiKey.trim())
            .putString(KEY_LIVE_MODEL, config.liveModel.trim().ifEmpty { "auto" })
            .putString(KEY_MODEL, config.geminiModel.trim().ifEmpty { DEFAULT_REST_MODEL })
            .apply()
        LiveSubStateHolder.updateConfig { config }
    }

    companion object {
        private const val PREFS_NAME = "livesub_ai_prefs"
        private const val DEFAULT_REST_MODEL = "gemini-flash-lite-latest"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_SURFACE_STYLE = "surface_style"
        private const val KEY_TEXT_PROTECTION = "text_protection"
        private const val KEY_ENGINE = "translation_engine"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_BLUR_RADIUS = "blur_radius"
        private const val KEY_TINT_OPACITY = "tint_opacity"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_LIVE_MODEL = "gemini_live_model"
        private const val KEY_MODEL = "gemini_model"
    }
}
