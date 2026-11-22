package com.livesub.ai.ai

import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Klien WebSocket real-time untuk Gemini Live API (BidiGenerateContent).
 * Dilengkapi anti-macet untuk narasi video cepat tanpa jeda:
 * - NO_INTERRUPTION agar ucapan baru tidak membatalkan terjemahan yang sedang berjalan.
 * - Auto-flush (`audioStreamEnd: true`) saat narasi terus menerus > 2,5 detik.
 * - Fallback otomatis ke setup standar jika model tertentu menolak realtimeInputConfig.
 */
class GeminiLiveWebSocketClient {

    interface Listener {
        fun onConnected(modelName: String, isAudioTranscriptionMode: Boolean)
        fun onSubtitleUpdated(subtitle: String)
        fun onFallbackToRest(reason: String)
        fun onDisconnected(reason: String, shouldReconnect: Boolean)
    }

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val discoveryClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeSocket: WebSocket? = null

    @Volatile
    private var isSetupComplete: Boolean = false

    @Volatile
    private var isClosedByUser: Boolean = false

    @Volatile
    private var useLegacyMediaChunks: Boolean = false

    @Volatile
    private var useEnhancedRealtimeConfig: Boolean = true

    @Volatile
    private var lastServerOutputElapsedMs: Long = 0L

    @Volatile
    private var continuousSpeechFrames: Int = 0

    @Volatile
    private var pendingSpeechSinceLastOutputMs: Long = 0L

    private val subtitleBuffer = StringBuilder()
    private var resetBufferOnNextToken: Boolean = false

    private var candidateModels: List<String> = emptyList()
    private var candidateIndex: Int = 0
    private var triedAlternateModalityForCurrent: Boolean = false
    private var currentUseAudioModality: Boolean = false

    private var currentApiKey: String = ""
    private var currentSourceLang: String = "Deteksi otomatis"
    private var currentTargetLang: String = "Bahasa Indonesia"
    private var currentListener: Listener? = null

    fun isReady(): Boolean = isSetupComplete && !isClosedByUser && activeSocket != null

    fun getStallDurationMs(): Long {
        if (!isReady()) return 0L
        val speechStart = pendingSpeechSinceLastOutputMs
        if (speechStart == 0L) return 0L
        return (SystemClock.elapsedRealtime() - speechStart).coerceAtLeast(0L)
    }

    suspend fun startSession(
        apiKey: String,
        preferredModel: String,
        sourceLanguage: String,
        targetLanguage: String,
        listener: Listener
    ) {
        stopSession()
        isClosedByUser = false
        currentApiKey = apiKey.trim()
        currentSourceLang = sourceLanguage
        currentTargetLang = targetLanguage
        currentListener = listener

        if (currentApiKey.isBlank()) {
            listener.onFallbackToRest("API Key Gemini belum diisi di tab Tampilan & AI.")
            return
        }

        val discovered = discoverBidiModels(currentApiKey)
        val merged = linkedSetOf<String>()
        val trimmedPref = preferredModel.trim()
        if (trimmedPref.isNotEmpty() && !trimmedPref.equals("auto", ignoreCase = true)) {
            merged.add(trimmedPref.removePrefix("models/"))
        }
        discovered.forEach { merged.add(it) }
        FALLBACK_LIVE_MODELS.forEach { merged.add(it) }

        candidateModels = merged.toList()
        candidateIndex = 0
        triedAlternateModalityForCurrent = false
        useLegacyMediaChunks = false
        useEnhancedRealtimeConfig = true

        if (candidateModels.isEmpty()) {
            listener.onFallbackToRest("Model Live WebSocket tidak ditemukan pada akun ini.")
            return
        }

        val firstModel = candidateModels[0]
        currentUseAudioModality = isNativeAudioModel(firstModel)
        openSocketForCurrentCandidate()
    }

    fun sendPcmFrame(pcm16kMonoBytes: ByteArray, isSpeech: Boolean): Boolean {
        val socket = activeSocket ?: return false
        if (!isSetupComplete || isClosedByUser) return false
        if (socket.queueSize() > MAX_QUEUE_BYTES) return false

        val now = SystemClock.elapsedRealtime()
        if (isSpeech) {
            continuousSpeechFrames++
            if (pendingSpeechSinceLastOutputMs == 0L) {
                pendingSpeechSinceLastOutputMs = now
            }
        } else {
            if (continuousSpeechFrames >= 8) {
                // Jeda alami setelah bicara >= 0,8 detik -> bantu akhiri turn segera
                flushTurn()
            }
            continuousSpeechFrames = 0
        }

        val base64Pcm = Base64.encodeToString(pcm16kMonoBytes, Base64.NO_WRAP)
        val blob = JSONObject()
            .put("mimeType", "audio/pcm;rate=16000")
            .put("data", base64Pcm)

        val realtimeInput = JSONObject()
        if (useLegacyMediaChunks) {
            realtimeInput.put("mediaChunks", JSONArray().put(blob))
        } else {
            realtimeInput.put("audio", blob)
        }

        val sent = socket.send(JSONObject().put("realtimeInput", realtimeInput).toString())

        // Anti-macet narasi nonstop: paksa akhiri turn setiap ~2,5 detik bicara tanpa jeda
        if (sent && continuousSpeechFrames >= CONTINUOUS_SPEECH_FLUSH_FRAMES) {
            continuousSpeechFrames = 0
            flushTurn()
        }

        return sent
    }

    fun flushTurn() {
        val socket = activeSocket ?: return
        if (!isSetupComplete || isClosedByUser) return
        if (!useLegacyMediaChunks) {
            val endPayload = JSONObject().put(
                "realtimeInput",
                JSONObject().put("audioStreamEnd", true)
            )
            socket.send(endPayload.toString())
        } else {
            // Mode legacy: kirim 300ms PCM hening agar server VAD mendeteksi akhir kalimat
            val silentBase64 = Base64.encodeToString(SILENT_300MS_PCM, Base64.NO_WRAP)
            val blob = JSONObject()
                .put("mimeType", "audio/pcm;rate=16000")
                .put("data", silentBase64)
            val payload = JSONObject().put(
                "realtimeInput",
                JSONObject().put("mediaChunks", JSONArray().put(blob))
            )
            socket.send(payload.toString())
        }
    }

    fun stopSession() {
        isClosedByUser = true
        isSetupComplete = false
        continuousSpeechFrames = 0
        pendingSpeechSinceLastOutputMs = 0L
        synchronized(subtitleBuffer) {
            subtitleBuffer.clear()
            resetBufferOnNextToken = false
        }
        try {
            activeSocket?.close(1000, "Stopped by user")
        } catch (_: Exception) {
        }
        activeSocket = null
    }

    private fun openSocketForCurrentCandidate() {
        if (isClosedByUser) return
        if (candidateIndex >= candidateModels.size) {
            currentListener?.onFallbackToRest("Server Live WebSocket sedang penuh atau tidak mendukung akun ini.")
            return
        }

        val modelId = candidateModels[candidateIndex]
        isSetupComplete = false
        continuousSpeechFrames = 0
        pendingSpeechSinceLastOutputMs = 0L
        synchronized(subtitleBuffer) {
            subtitleBuffer.clear()
            resetBufferOnNextToken = false
        }

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$currentApiKey"
        val request = Request.Builder().url(url).build()

        activeSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isClosedByUser) {
                    webSocket.close(1000, "Closed")
                    return
                }
                val setupJson = buildSetupPayload(
                    modelId = modelId,
                    useAudioModality = currentUseAudioModality,
                    includeRealtimeConfig = useEnhancedRealtimeConfig,
                    sourceLang = currentSourceLang,
                    targetLang = currentTargetLang
                )
                webSocket.send(setupJson)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(modelId, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(modelId, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleSocketTermination(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code ?: 0
                val msg = t.localizedMessage ?: "Koneksi WebSocket terputus"
                handleSocketTermination(httpCode, msg)
            }
        })
    }

    private fun handleServerMessage(modelId: String, rawJson: String) {
        if (isClosedByUser || rawJson.isBlank()) return
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                isSetupComplete = true
                lastServerOutputElapsedMs = SystemClock.elapsedRealtime()
                pendingSpeechSinceLastOutputMs = 0L
                currentListener?.onConnected(modelId, currentUseAudioModality)
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                resetBufferOnNextToken = true
            }

            var emittedAnyText = false

            // 1. Cek outputTranscription (untuk model native-audio)
            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            val transcriptText = outputTranscription?.optString("text").orEmpty()
            if (transcriptText.isNotEmpty()) {
                appendAndEmitSubtitle(transcriptText)
                emittedAnyText = true
            }

            // 2. Cek modelTurn.parts[].text (untuk model responseModalities = TEXT)
            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue
                    val partText = part.optString("text").orEmpty()
                    if (partText.isNotEmpty()) {
                        appendAndEmitSubtitle(partText)
                        emittedAnyText = true
                    }
                }
            }

            if (emittedAnyText) {
                lastServerOutputElapsedMs = SystemClock.elapsedRealtime()
                pendingSpeechSinceLastOutputMs = 0L
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                resetBufferOnNextToken = true
                if (!emittedAnyText) {
                    pendingSpeechSinceLastOutputMs = 0L
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun appendAndEmitSubtitle(delta: String) {
        val cleanedDelta = delta.replace('\n', ' ').replace('\r', ' ')
        if (cleanedDelta.isBlank()) return

        val display = synchronized(subtitleBuffer) {
            if (resetBufferOnNextToken) {
                subtitleBuffer.clear()
                resetBufferOnNextToken = false
            }
            if (subtitleBuffer.isNotEmpty() && subtitleBuffer[subtitleBuffer.length - 1] != ' ' && !cleanedDelta.startsWith(" ")) {
                val first = cleanedDelta.first()
                if (first.isLetterOrDigit()) {
                    subtitleBuffer.append(' ')
                }
            }
            subtitleBuffer.append(cleanedDelta)

            if (subtitleBuffer.length > MAX_SUBTITLE_CHARS) {
                val cutFrom = subtitleBuffer.length - MAX_SUBTITLE_CHARS
                val spaceIdx = subtitleBuffer.indexOf(" ", cutFrom)
                val startIdx = if (spaceIdx in (cutFrom + 1) until subtitleBuffer.length) spaceIdx + 1 else cutFrom
                subtitleBuffer.delete(0, startIdx)
            }
            subtitleBuffer.toString().replace(Regex("\\s+"), " ").trim()
        }

        if (display.isNotEmpty()) {
            currentListener?.onSubtitleUpdated(display)
        }
    }

    private fun handleSocketTermination(code: Int, reason: String) {
        if (isClosedByUser) return
        activeSocket = null

        if (!isSetupComplete) {
            // Jika ditolak karena realtimeInputConfig, coba ulang model yg sama tanpa realtimeInputConfig
            if (useEnhancedRealtimeConfig && code == 1007) {
                useEnhancedRealtimeConfig = false
                openSocketForCurrentCandidate()
                return
            }

            // Jika masih ditolak di setup, coba balik modality TEXT <-> AUDIO
            if (!triedAlternateModalityForCurrent && (code == 1007 || code == 1008 || code == 1003)) {
                triedAlternateModalityForCurrent = true
                currentUseAudioModality = !currentUseAudioModality
                openSocketForCurrentCandidate()
                return
            }

            candidateIndex++
            triedAlternateModalityForCurrent = false
            useEnhancedRealtimeConfig = true
            if (candidateIndex < candidateModels.size) {
                currentUseAudioModality = isNativeAudioModel(candidateModels[candidateIndex])
                openSocketForCurrentCandidate()
            } else {
                currentListener?.onFallbackToRest(
                    if (reason.isNotBlank()) "Live WS ditutup ($reason). Beralih ke REST cepat..."
                    else "Live WS tidak tersedia. Beralih ke REST cepat..."
                )
            }
            return
        }

        // Jika sudah setupComplete lalu server menolak `audio` / `audioStreamEnd`, beralih ke `mediaChunks`
        if (code == 1007 && !useLegacyMediaChunks &&
            (reason.contains("audio", ignoreCase = true) || reason.contains("StreamEnd", ignoreCase = true))
        ) {
            useLegacyMediaChunks = true
            isSetupComplete = false
            openSocketForCurrentCandidate()
            return
        }

        isSetupComplete = false
        currentListener?.onDisconnected(
            reason = reason.ifBlank { "Sesi Live terputus (kode $code)" },
            shouldReconnect = true
        )
    }

    private fun buildSetupPayload(
        modelId: String,
        useAudioModality: Boolean,
        includeRealtimeConfig: Boolean,
        sourceLang: String,
        targetLang: String
    ): String {
        val fullModel = if (modelId.startsWith("models/")) modelId else "models/$modelId"
        val instruction = buildString {
            append("Kamu adalah penerjemah subtitle video real-time. ")
            append("Terjemahkan setiap ucapan dari audio secara langsung, akurat, dan ringkas ke dalam ")
            append(targetLang)
            append(". ")
            if (sourceLang != "Deteksi otomatis") {
                append("Bahasa sumber adalah ")
                append(sourceLang)
                append(". ")
            }
            append("Jangan mengobrol, jangan menjawab pembicara, jangan tambahkan catatan. ")
            append("Keluarkan HANYA teks terjemahan alami.")
        }

        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put(if (useAudioModality) "AUDIO" else "TEXT"))
            .put("temperature", 0.2)

        val setup = JSONObject()
            .put("model", fullModel)
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", instruction))
                )
            )

        if (useAudioModality) {
            setup.put("outputAudioTranscription", JSONObject())
        }

        if (includeRealtimeConfig) {
            val vadConfig = JSONObject()
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                .put("silenceDurationMs", 250)
            val realtimeCfg = JSONObject()
                .put("automaticActivityDetection", vadConfig)
                .put("activityHandling", "NO_INTERRUPTION")
            setup.put("realtimeInputConfig", realtimeCfg)
        }

        return JSONObject().put("setup", setup).toString()
    }

    private suspend fun discoverBidiModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val request = Request.Builder().url(url).get().build()
            discoveryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                val modelsArray = JSONObject(body).optJSONArray("models") ?: return@withContext emptyList()
                val bidiModels = mutableListOf<String>()
                for (i in 0 until modelsArray.length()) {
                    val item = modelsArray.optJSONObject(i) ?: continue
                    val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
                    var supportsBidi = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j) == "bidiGenerateContent") {
                            supportsBidi = true
                            break
                        }
                    }
                    if (supportsBidi) {
                        val rawName = item.optString("name").removePrefix("models/").trim()
                        if (rawName.isNotEmpty()) {
                            bidiModels.add(rawName)
                        }
                    }
                }
                bidiModels.sortedByDescending { scoreLiveModel(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun scoreLiveModel(modelId: String): Int {
        val lower = modelId.lowercase()
        var score = 0
        if (lower.contains("flash")) score += 30
        if (lower.contains("live")) score += 25
        if (lower.contains("native-audio")) score += 20
        if (lower.contains("3.5") || lower.contains("3.0") || lower.contains("3-")) score += 15
        if (lower.contains("2.5")) score += 10
        if (lower.contains("2.0")) score += 5
        if (lower.contains("exp")) score -= 3
        return score
    }

    private fun isNativeAudioModel(modelId: String): Boolean {
        return modelId.contains("native-audio", ignoreCase = true)
    }

    companion object {
        private const val MAX_QUEUE_BYTES = 128_000L
        private const val MAX_SUBTITLE_CHARS = 135
        private const val CONTINUOUS_SPEECH_FLUSH_FRAMES = 25 // ~2,5 detik bicara nonstop
        private val SILENT_300MS_PCM = ByteArray(16_000 * 2 * 300 / 1000)
        private val FALLBACK_LIVE_MODELS = listOf(
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-live-2.5-flash-preview",
            "gemini-2.0-flash-live-001"
        )
    }
}
