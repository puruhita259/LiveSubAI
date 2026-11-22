package com.livesub.ai.model

enum class SurfaceStyle(val label: String, val subtitle: String) {
    SOLID_DARK("Solid Gelap", "Latar pekat standar, kontras tertinggi"),
    GLASS_DARK("Glass Hitam", "Kaca gelap blur dengan kilau tepi"),
    GLASS_CLEAR("Glass Bening", "Kaca bening refraktif, video tetap terlihat")
}

enum class TextProtection(val label: String, val subtitle: String) {
    HALO_SHADOW("Halo Bayangan", "Bayangan gelap berlapis di balik huruf"),
    THIN_OUTLINE("Outline Tipis", "Garis tepi gelap standar subtitle siaran"),
    MICRO_SCRIM("Scrim Mikro", "Lapisan gelap tipis di belakang teks")
}

enum class TranslationEngine(val label: String, val subtitle: String) {
    LIVE_WEBSOCKET("Live WebSocket (< 1 dtk)", "Streaming PCM real-time lewat Gemini Live API"),
    FAST_REST("REST Cepat (Paralel)", "Chunk 1,8 dtk paralel + thinkingBudget 0 + auto-fallback")
}

enum class CaptureMode(val label: String) {
    INTERNAL_AUDIO("Audio Video (Internal)"),
    MICROPHONE("Mikrofon (Fallback DRM)"),
    DEMO_SIMULATION("Simulasi (Tanpa API Key)")
}

enum class ServiceStatus(val label: String) {
    IDLE("Siap dijalankan"),
    CONNECTING("Menghubungkan Gemini Live..."),
    LISTENING("Live · Mendengarkan..."),
    TRANSLATING("Menerjemahkan..."),
    PAUSED("Terjemahan dijeda"),
    DRM_BLOCKED("Audio aplikasi ini dilindungi DRM"),
    OFFLINE("Koneksi terputus")
}

data class OverlayConfig(
    val sourceLanguage: String = "Deteksi otomatis",
    val targetLanguage: String = "Bahasa Indonesia",
    val surfaceStyle: SurfaceStyle = SurfaceStyle.GLASS_DARK,
    val textProtection: TextProtection = TextProtection.HALO_SHADOW,
    val translationEngine: TranslationEngine = TranslationEngine.LIVE_WEBSOCKET,
    val fontSizeSp: Float = 18f,
    val blurRadiusPx: Int = 28,
    val tintOpacity: Float = 0.56f,
    val geminiApiKey: String = "",
    val liveModel: String = "auto",
    val geminiModel: String = "gemini-flash-lite-latest"
)

data class LiveSubRuntimeState(
    val isOverlayActive: Boolean = false,
    val isPaused: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.INTERNAL_AUDIO,
    val activeEngine: TranslationEngine = TranslationEngine.LIVE_WEBSOCKET,
    val activeModelName: String = "",
    val status: ServiceStatus = ServiceStatus.IDLE,
    val currentSubtitle: String = "Siap menerjemahkan suara video secara real-time.",
    val lastDetectedLanguage: String = "Auto",
    val errorHint: String? = null
)
