package com.livesub.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.livesub.ai.model.EngineMode
import com.livesub.ai.model.HybridSttProvider
import com.livesub.ai.model.LiveSubRuntimeState
import com.livesub.ai.model.OverlayConfig
import com.livesub.ai.model.SurfaceStyle
import com.livesub.ai.model.TextProtection
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
        val engineName = prefs.getString(KEY_ENGINE_MODE, EngineMode.HYBRID_CLOUD_STT.name)
        val providerName = prefs.getString(KEY_HYBRID_PROVIDER, HybridSttProvider.GROQ.name)
        val styleName = prefs.getString(KEY_SURFACE_STYLE, SurfaceStyle.GLASS_DARK.name)
        val protName = prefs.getString(KEY_TEXT_PROTECTION, TextProtection.HALO_SHADOW.name)

        val config = OverlayConfig(
            engineMode = EngineMode.entries.find { it.name == engineName } ?: EngineMode.HYBRID_CLOUD_STT,
            hybridProvider = HybridSttProvider.entries.find { it.name == providerName } ?: HybridSttProvider.GROQ,
            sourceLangCode = prefs.getString(KEY_SOURCE_LANG, "auto") ?: "auto",
            targetLangCode = prefs.getString(KEY_TARGET_LANG, "id") ?: "id",
            offlineSttModelId = prefs.getString(KEY_OFFLINE_STT_ID, "en") ?: "en",
            surfaceStyle = SurfaceStyle.entries.find { it.name == styleName } ?: SurfaceStyle.GLASS_DARK,
            textProtection = TextProtection.entries.find { it.name == protName } ?: TextProtection.HALO_SHADOW,
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, 15f).coerceIn(10f, 28f),
            blurRadiusPx = prefs.getInt(KEY_BLUR_RADIUS, 28).coerceIn(0, 60),
            tintOpacity = prefs.getFloat(KEY_TINT_OPACITY, 0.56f).coerceIn(0.15f, 0.95f),
            showOriginalText = prefs.getBoolean(KEY_SHOW_ORIGINAL, false),
            groqApiKey = prefs.getString(KEY_GROQ_API_KEY, "") ?: "",
            groqModel = prefs.getString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL)?.ifBlank { DEFAULT_GROQ_MODEL }
                ?: DEFAULT_GROQ_MODEL,
            cloudflareAccountId = prefs.getString(KEY_CF_ACCOUNT_ID, "") ?: "",
            cloudflareApiToken = prefs.getString(KEY_CF_API_TOKEN, "") ?: "",
            cloudflareModel = prefs.getString(KEY_CF_MODEL, DEFAULT_CF_MODEL)?.ifBlank { DEFAULT_CF_MODEL }
                ?: DEFAULT_CF_MODEL
        )
        LiveSubStateHolder.updateConfig { config }
        return config
    }

    fun save(config: OverlayConfig) {
        val clamped = config.copy(
            fontSizeSp = config.fontSizeSp.coerceIn(10f, 28f),
            blurRadiusPx = config.blurRadiusPx.coerceIn(0, 60),
            tintOpacity = config.tintOpacity.coerceIn(0.15f, 0.95f),
            groqApiKey = config.groqApiKey.trim(),
            groqModel = config.groqModel.trim().ifEmpty { DEFAULT_GROQ_MODEL },
            cloudflareAccountId = config.cloudflareAccountId.trim(),
            cloudflareApiToken = config.cloudflareApiToken.trim(),
            cloudflareModel = config.cloudflareModel.trim().ifEmpty { DEFAULT_CF_MODEL }
        )
        prefs.edit()
            .putString(KEY_ENGINE_MODE, clamped.engineMode.name)
            .putString(KEY_HYBRID_PROVIDER, clamped.hybridProvider.name)
            .putString(KEY_SOURCE_LANG, clamped.sourceLangCode)
            .putString(KEY_TARGET_LANG, clamped.targetLangCode)
            .putString(KEY_OFFLINE_STT_ID, clamped.offlineSttModelId)
            .putString(KEY_SURFACE_STYLE, clamped.surfaceStyle.name)
            .putString(KEY_TEXT_PROTECTION, clamped.textProtection.name)
            .putFloat(KEY_FONT_SIZE, clamped.fontSizeSp)
            .putInt(KEY_BLUR_RADIUS, clamped.blurRadiusPx)
            .putFloat(KEY_TINT_OPACITY, clamped.tintOpacity)
            .putBoolean(KEY_SHOW_ORIGINAL, clamped.showOriginalText)
            .putString(KEY_GROQ_API_KEY, clamped.groqApiKey)
            .putString(KEY_GROQ_MODEL, clamped.groqModel)
            .putString(KEY_CF_ACCOUNT_ID, clamped.cloudflareAccountId)
            .putString(KEY_CF_API_TOKEN, clamped.cloudflareApiToken)
            .putString(KEY_CF_MODEL, clamped.cloudflareModel)
            .apply()
        LiveSubStateHolder.updateConfig { clamped }
    }

    companion object {
        private const val PREFS_NAME = "livesub_ai_prefs_v2"
        const val DEFAULT_GROQ_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_CF_MODEL = "@cf/openai/whisper-large-v3-turbo"

        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_HYBRID_PROVIDER = "hybrid_provider"
        private const val KEY_SOURCE_LANG = "source_lang_code"
        private const val KEY_TARGET_LANG = "target_lang_code"
        private const val KEY_OFFLINE_STT_ID = "offline_stt_model_id"
        private const val KEY_SURFACE_STYLE = "surface_style"
        private const val KEY_TEXT_PROTECTION = "text_protection"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_BLUR_RADIUS = "blur_radius"
        private const val KEY_TINT_OPACITY = "tint_opacity"
        private const val KEY_SHOW_ORIGINAL = "show_original_text"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_CF_ACCOUNT_ID = "cf_account_id"
        private const val KEY_CF_API_TOKEN = "cf_api_token"
        private const val KEY_CF_MODEL = "cf_model"
    }
}
