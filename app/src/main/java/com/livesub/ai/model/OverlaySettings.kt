package com.livesub.ai.model

enum class SurfaceStyle(val label: String, val shortLabel: String, val subtitle: String) {
    SOLID_DARK("Solid Gelap", "Solid", "Latar pekat standar, kontras tertinggi"),
    GLASS_DARK("Glass Hitam", "Glass H", "Kaca gelap blur dengan kilau tepi"),
    GLASS_CLEAR("Glass Bening", "Glass B", "Kaca bening refraktif, video tetap terlihat")
}

enum class TextProtection(val label: String, val subtitle: String) {
    HALO_SHADOW("Halo Bayangan", "Bayangan gelap berlapis di balik huruf"),
    THIN_OUTLINE("Outline Tipis", "Garis tepi gelap standar subtitle siaran"),
    MICRO_SCRIM("Scrim Mikro", "Lapisan gelap tipis di belakang teks")
}

enum class EngineMode(val label: String, val shortTag: String, val subtitle: String) {
    HYBRID_CLOUD_STT(
        label = "Hybrid (Cloud STT + Translate Offline)",
        shortTag = "HYBRID",
        subtitle = "Groq / Cloudflare Whisper cepat + ML Kit Offline (< 50 ms)"
    ),
    FULL_OFFLINE(
        label = "Full Offline (Vosk Streaming + ML Kit)",
        shortTag = "OFFLINE",
        subtitle = "Streaming kata per kata lokal (~0,4 dtk), 0% internet setelah unduh"
    )
}

enum class HybridSttProvider(val label: String, val shortTag: String, val subtitle: String) {
    GROQ(
        label = "Groq Console",
        shortTag = "GROQ",
        subtitle = "Whisper Large v3 Turbo (~200 ms, sangat cepat & akurat)"
    ),
    CLOUDFLARE(
        label = "Cloudflare Workers AI",
        shortTag = "CF",
        subtitle = "@cf/openai/whisper-large-v3-turbo (Edge global cepat)"
    )
}

enum class CaptureMode(val label: String) {
    INTERNAL_AUDIO("Audio Video (Internal)"),
    MICROPHONE("Mikrofon (Fallback DRM)"),
    DEMO_SIMULATION("Simulasi (Tanpa API Key)")
}

enum class ServiceStatus(val label: String) {
    IDLE("Siap dijalankan"),
    PREPARING("Menyiapkan model..."),
    WAITING_AUDIO("Menunggu suara video..."),
    LISTENING("Mendengarkan..."),
    TRANSLATING("Menerjemahkan..."),
    RECOVERING("Menyegarkan koneksi..."),
    PAUSED("Terjemahan dijeda"),
    DRM_BLOCKED("Audio aplikasi ini dilindungi DRM"),
    OFFLINE("Koneksi terputus")
}

data class LanguageOption(
    val code: String,
    val displayName: String,
    val shortCode: String
)

data class OfflineSttModelSpec(
    val id: String,
    val langCode: String,
    val displayName: String,
    val sizeLabel: String,
    val downloadUrl: String
)

object LanguageCatalog {
    val AUTO_DETECT = LanguageOption("auto", "Deteksi otomatis", "AUTO")

    // 20 bahasa kamus ML Kit Translate Offline (~30 MB per bahasa)
    val TRANSLATE_LANGUAGES = listOf(
        LanguageOption("id", "Bahasa Indonesia", "ID"),
        LanguageOption("en", "Bahasa Inggris", "EN"),
        LanguageOption("ja", "Bahasa Jepang", "JA"),
        LanguageOption("ko", "Bahasa Korea", "KO"),
        LanguageOption("zh", "Bahasa Mandarin", "ZH"),
        LanguageOption("th", "Bahasa Thailand", "TH"),
        LanguageOption("ar", "Bahasa Arab", "AR"),
        LanguageOption("ms", "Bahasa Melayu", "MS"),
        LanguageOption("vi", "Bahasa Vietnam", "VI"),
        LanguageOption("hi", "Bahasa Hindi", "HI"),
        LanguageOption("es", "Bahasa Spanyol", "ES"),
        LanguageOption("fr", "Bahasa Prancis", "FR"),
        LanguageOption("de", "Bahasa Jerman", "DE"),
        LanguageOption("ru", "Bahasa Rusia", "RU"),
        LanguageOption("it", "Bahasa Italia", "IT"),
        LanguageOption("pt", "Bahasa Portugis", "PT"),
        LanguageOption("tr", "Bahasa Turki", "TR"),
        LanguageOption("nl", "Bahasa Belanda", "NL"),
        LanguageOption("pl", "Bahasa Polandia", "PL"),
        LanguageOption("tl", "Bahasa Tagalog", "TL")
    )

    // 16 bahasa model suara Streaming STT Offline (Vosk resmi)
    val OFFLINE_STT_MODELS = listOf(
        OfflineSttModelSpec("en", "en", "Inggris (US)", "40 MB", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
        OfflineSttModelSpec("ja", "ja", "Jepang", "48 MB", "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"),
        OfflineSttModelSpec("ko", "ko", "Korea", "82 MB", "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"),
        OfflineSttModelSpec("zh", "zh", "Mandarin (CN)", "42 MB", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),
        OfflineSttModelSpec("es", "es", "Spanyol", "39 MB", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),
        OfflineSttModelSpec("fr", "fr", "Prancis", "41 MB", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"),
        OfflineSttModelSpec("de", "de", "Jerman", "45 MB", "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"),
        OfflineSttModelSpec("ru", "ru", "Rusia", "45 MB", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"),
        OfflineSttModelSpec("it", "it", "Italia", "48 MB", "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"),
        OfflineSttModelSpec("pt", "pt", "Portugis", "31 MB", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"),
        OfflineSttModelSpec("hi", "hi", "Hindi", "42 MB", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"),
        OfflineSttModelSpec("vi", "vi", "Vietnam", "32 MB", "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"),
        OfflineSttModelSpec("tr", "tr", "Turki", "35 MB", "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"),
        OfflineSttModelSpec("nl", "nl", "Belanda", "39 MB", "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip"),
        OfflineSttModelSpec("pl", "pl", "Polandia", "50 MB", "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip"),
        OfflineSttModelSpec("ar", "ar", "Arab (Standar)", "318 MB", "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip")
    )

    fun findLanguageByCode(code: String): LanguageOption {
        if (code == "auto") return AUTO_DETECT
        return TRANSLATE_LANGUAGES.find { it.code.equals(code, ignoreCase = true) }
            ?: TRANSLATE_LANGUAGES.first()
    }

    fun findOfflineSttById(id: String): OfflineSttModelSpec {
        return OFFLINE_STT_MODELS.find { it.id == id } ?: OFFLINE_STT_MODELS.first()
    }
}

data class OverlayConfig(
    val engineMode: EngineMode = EngineMode.HYBRID_CLOUD_STT,
    val hybridProvider: HybridSttProvider = HybridSttProvider.GROQ,
    val sourceLangCode: String = "auto",
    val targetLangCode: String = "id",
    val offlineSttModelId: String = "en",
    val surfaceStyle: SurfaceStyle = SurfaceStyle.GLASS_DARK,
    val textProtection: TextProtection = TextProtection.HALO_SHADOW,
    val fontSizeSp: Float = 15f,
    val blurRadiusPx: Int = 28,
    val tintOpacity: Float = 0.56f,
    val showOriginalText: Boolean = false,
    // Hybrid STT credentials (disimpan lokal di perangkat saja)
    val groqApiKey: String = "",
    val groqModel: String = "whisper-large-v3-turbo",
    val cloudflareAccountId: String = "",
    val cloudflareApiToken: String = "",
    val cloudflareModel: String = "@cf/openai/whisper-large-v3-turbo"
)

data class LiveSubRuntimeState(
    val isOverlayActive: Boolean = false,
    val isPaused: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.INTERNAL_AUDIO,
    val activeEngine: EngineMode = EngineMode.HYBRID_CLOUD_STT,
    val activeProviderTag: String = "GROQ",
    val status: ServiceStatus = ServiceStatus.IDLE,
    val currentSubtitle: String = "Siap menerjemahkan suara video secara real-time.",
    val lastSourceTranscript: String = "",
    val lastDetectedLangCode: String = "auto",
    val translateLatencyMs: Long = 0L,
    val errorHint: String? = null
)
