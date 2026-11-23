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
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Klien WebSocket ultra-rendah latensi untuk Gemini Live API (BidiGenerateContent).
 * Optimasi anti-telat & anti-drift:
 * 1) thinkingBudget = 0 (dengan fallback otomatis jika model menolak) untuk memangkas latensi token pertama.
 * 2) Flush awal super cepat (~1,0 detik) agar subtitle pertama langsung muncul.
 * 3) Zero-Drift Turn Gating + Rolling Ring Buffer 1,2 detik: tidak pernah menumpuk antrean turn di server
 *    saat pembicara video berbicara cepat nonstop.
 */
class GeminiLiveWebSocketClient {

    interface Listener {
        fun onConnected(modelName: String, isAudioTranscriptionMode: Boolean)
        fun onSubtitleUpdated(subtitle: String)
        fun onFallbackToRest(reason: String)
        fun onDisconnected(reason: String, shouldReconnect: Boolean)
    }

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val discoveryClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
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
    private var useThinkingZeroInSetup: Boolean = true

    @Volatile
    private var useEnhancedRealtimeConfig: Boolean = true

    @Volatile
    private var pendingSpeechSinceLastOutputMs: Long = 0L

    private val flowLock = Any()
    private var awaitingTurnResponse: Boolean = false
    private var turnFlushedAtMs: Long = 0L
    private var hasProducedFirstSubtitle: Boolean = false
    private var speechFramesInCurrentTurn: Int = 0
    private var trailingSilenceFrames: Int = 0
    private val pendingRingBuffer = ArrayDeque<ByteArray>()

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
        useThinkingZeroInSetup = true
        useEnhancedRealtimeConfig = true

        if (candidateModels.isEmpty()) {
            listener.onFallbackToRest("Model Live WebSocket tidak ditemukan pada akun ini.")
            return
        }

        val firstModel = candidateModels[0]
        currentUseAudioModality = isNativeAudioModel(firstModel)
        openSocketForCurrentCandidate()
    }

    /**
     * Menerima frame PCM 50ms secara real-time.
     * - Jika server siap: kirim live langsung dan flush cepat (1,0 dtk untuk subtitle pertama, 1,4 dtk selanjutnya).
     * - Jika server masih memproses turn sebelumnya: tahan di rolling ring buffer max 1,2 detik agar tidak
     *   pernah terjadi antrean menumpuk (0 drift).
     */
    fun sendPcmFrame(pcm16kMonoBytes: ByteArray, isSpeech: Boolean): Boolean {
        val socket = activeSocket ?: return false
        if (!isSetupComplete || isClosedByUser) return false
        if (socket.queueSize() > MAX_QUEUE_BYTES) return false

        val now = SystemClock.elapsedRealtime()
        var shouldSendLiveNow = false
        var shouldFlushNow = false
        var drainedPayload: ByteArray? = null

        synchronized(flowLock) {
            if (isSpeech && pendingSpeechSinceLastOutputMs == 0L) {
                pendingSpeechSinceLastOutputMs = now
            }

            // Katup pengaman: jika turnComplete terlalu lama (> 3,2 dtk), buka kunci otomatis
            if (awaitingTurnResponse && (now - turnFlushedAtMs) >= TURN_LOCK_TIMEOUT_MS) {
                awaitingTurnResponse = false
                drainedPayload = drainRingBufferLocked()
            }

            if (awaitingTurnResponse) {
                // Simpan hanya audio terbaru (maksimal 1,2 detik terakhir) sambil menunggu turn selesai
                if (isSpeech || pendingRingBuffer.isNotEmpty()) {
                    pendingRingBuffer.addLast(pcm16kMonoBytes)
                    while (pendingRingBuffer.size > MAX_RING_FRAMES) {
                        pendingRingBuffer.removeFirst()
                    }
                }
                return true
            }

            shouldSendLiveNow = true
            if (isSpeech) {
                speechFramesInCurrentTurn++
                trailingSilenceFrames = 0
            } else if (speechFramesInCurrentTurn > 0) {
                trailingSilenceFrames++
            }

            val maxSpeechFrames = if (hasProducedFirstSubtitle) {
                STEADY_SPEECH_FLUSH_FRAMES
            } else {
                FIRST_SPEECH_FLUSH_FRAMES
            }

            val hitNaturalPause = speechFramesInCurrentTurn >= MIN_PAUSE_FLUSH_FRAMES &&
                trailingSilenceFrames >= PAUSE_SILENCE_FRAMES
            val hitMaxBurst = speechFramesInCurrentTurn >= maxSpeechFrames

            if (hitNaturalPause || hitMaxBurst) {
                shouldFlushNow = true
                awaitingTurnResponse = true
                turnFlushedAtMs = now
                speechFramesInCurrentTurn = 0
                trailingSilenceFrames = 0
            }
        }

        if (drainedPayload != null && drainedPayload!!.isNotEmpty()) {
            sendRawPcmBlob(socket, drainedPayload!!)
        }

        var sent = true
        if (shouldSendLiveNow) {
            sent = sendRawPcmBlob(socket, pcm16kMonoBytes)
        }
        if (sent && shouldFlushNow) {
            sendStreamEndSignal(socket)
        }
        return sent
    }

    fun flushTurn() {
        val socket = activeSocket ?: return
        if (!isSetupComplete || isClosedByUser) return
        synchronized(flowLock) {
            awaitingTurnResponse = true
            turnFlushedAtMs = SystemClock.elapsedRealtime()
            speechFramesInCurrentTurn = 0
            trailingSilenceFrames = 0
        }
        sendStreamEndSignal(socket)
    }

    fun stopSession() {
        isClosedByUser = true
        isSetupComplete = false
        pendingSpeechSinceLastOutputMs = 0L
        synchronized(flowLock) {
            awaitingTurnResponse = false
            turnFlushedAtMs = 0L
            hasProducedFirstSubtitle = false
            speechFramesInCurrentTurn = 0
            trailingSilenceFrames = 0
            pendingRingBuffer.clear()
        }
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

    private fun sendRawPcmBlob(socket: WebSocket, pcmBytes: ByteArray): Boolean {
        val base64Pcm = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val blob = JSONObject()
            .put("mimeType", "audio/pcm;rate=16000")
            .put("data", base64Pcm)

        val realtimeInput = JSONObject()
        if (useLegacyMediaChunks) {
            realtimeInput.put("mediaChunks", JSONArray().put(blob))
        } else {
            realtimeInput.put("audio", blob)
        }

        return socket.send(JSONObject().put("realtimeInput", realtimeInput).toString())
    }

    private fun sendStreamEndSignal(socket: WebSocket) {
        if (!useLegacyMediaChunks) {
            val endPayload = JSONObject().put(
                "realtimeInput",
                JSONObject().put("audioStreamEnd", true)
            )
            socket.send(endPayload.toString())
        } else {
            sendRawPcmBlob(socket, SILENT_250MS_PCM)
        }
    }

    private fun drainRingBufferLocked(): ByteArray? {
        if (pendingRingBuffer.isEmpty()) return null
        val out = ByteArrayOutputStream(pendingRingBuffer.size * 1600)
        while (pendingRingBuffer.isNotEmpty()) {
            out.write(pendingRingBuffer.removeFirst())
        }
        return out.toByteArray()
    }

    private fun openSocketForCurrentCandidate() {
        if (isClosedByUser) return
        if (candidateIndex >= candidateModels.size) {
            currentListener?.onFallbackToRest("Server Live WebSocket sedang penuh atau tidak mendukung akun ini.")
            return
        }

        val modelId = candidateModels[candidateIndex]
        isSetupComplete = false
        pendingSpeechSinceLastOutputMs = 0L
        synchronized(flowLock) {
            awaitingTurnResponse = false
            turnFlushedAtMs = 0L
            hasProducedFirstSubtitle = false
            speechFramesInCurrentTurn = 0
            trailingSilenceFrames = 0
            pendingRingBuffer.clear()
        }
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
                    includeThinkingZero = useThinkingZeroInSetup,
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
                pendingSpeechSinceLastOutputMs = 0L
                currentListener?.onConnected(modelId, currentUseAudioModality)
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                resetBufferOnNextToken = true
            }

            var emittedAnyText = false

            // 1. outputTranscription (model native-audio)
            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            val transcriptText = outputTranscription?.optString("text").orEmpty()
            if (transcriptText.isNotEmpty()) {
                appendAndEmitSubtitle(transcriptText)
                emittedAnyText = true
            }

            // 2. modelTurn.parts[].text (model TEXT)
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
                pendingSpeechSinceLastOutputMs = 0L
                synchronized(flowLock) {
                    hasProducedFirstSubtitle = true
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                resetBufferOnNextToken = true
                if (!emittedAnyText) {
                    pendingSpeechSinceLastOutputMs = 0L
                }
                dispatchFreshestBufferedAudioAfterTurn()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Begitu server selesai 1 turn (turnComplete), langsung kirim audio paling segar
     * (maksimal 1,2 detik terakhir) dari ring buffer agar subtitle selalu sinkron real-time.
     */
    private fun dispatchFreshestBufferedAudioAfterTurn() {
        val socket = activeSocket ?: return
        if (!isSetupComplete || isClosedByUser) return

        var freshAudio: ByteArray? = null
        var flushImmediately = false

        synchronized(flowLock) {
            val bufferedFrameCount = pendingRingBuffer.size
            if (bufferedFrameCount > 0) {
                freshAudio = drainRingBufferLocked()
                if (bufferedFrameCount >= MIN_IMMEDIATE_DRAIN_FRAMES) {
                    flushImmediately = true
                    awaitingTurnResponse = true
                    turnFlushedAtMs = SystemClock.elapsedRealtime()
                    speechFramesInCurrentTurn = 0
                    trailingSilenceFrames = 0
                } else {
                    awaitingTurnResponse = false
                    speechFramesInCurrentTurn = bufferedFrameCount
                    trailingSilenceFrames = 0
                }
            } else {
                awaitingTurnResponse = false
            }
        }

        val bytesToSend = freshAudio
        if (bytesToSend != null && bytesToSend.isNotEmpty()) {
            val ok = sendRawPcmBlob(socket, bytesToSend)
            if (ok && flushImmediately) {
                sendStreamEndSignal(socket)
            }
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
            // 1. Jika ditolak saat setup, coba lepas thinkingConfig dulu
            if (useThinkingZeroInSetup && code == 1007) {
                useThinkingZeroInSetup = false
                openSocketForCurrentCandidate()
                return
            }

            // 2. Jika masih ditolak, lepas realtimeInputConfig
            if (useEnhancedRealtimeConfig && code == 1007) {
                useEnhancedRealtimeConfig = false
                openSocketForCurrentCandidate()
                return
            }

            // 3. Jika masih ditolak, coba balik modality AUDIO <-> TEXT
            if (!triedAlternateModalityForCurrent && (code == 1007 || code == 1008 || code == 1003)) {
                triedAlternateModalityForCurrent = true
                currentUseAudioModality = !currentUseAudioModality
                openSocketForCurrentCandidate()
                return
            }

            candidateIndex++
            triedAlternateModalityForCurrent = false
            useThinkingZeroInSetup = true
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
        includeThinkingZero: Boolean,
        includeRealtimeConfig: Boolean,
        sourceLang: String,
        targetLang: String
    ): String {
        val fullModel = if (modelId.startsWith("models/")) modelId else "models/$modelId"
        val instruction = buildString {
            append("Kamu penerjemah subtitle real-time ultra-cepat. ")
            append("Terjemahkan ucapan audio ke ")
            append(targetLang)
            if (sourceLang != "Deteksi otomatis") {
                append(" dari ")
                append(sourceLang)
            }
            append(". Aturan wajib: sangat singkat dan padat (maks 10 kata), langsung inti ucapan, ")
            append("tanpa pembuka, tanpa penjelasan, jangan menjawab pembicara.")
        }

        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put(if (useAudioModality) "AUDIO" else "TEXT"))
            .put("temperature", 0.1)

        if (includeThinkingZero) {
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

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
                .put("silenceDurationMs", 200)
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
        private const val MAX_QUEUE_BYTES = 96_000L
        private const val MAX_SUBTITLE_CHARS = 120

        // Basis frame = 50ms
        private const val FIRST_SPEECH_FLUSH_FRAMES = 20 // 1,0 detik untuk subtitle pertama
        private const val STEADY_SPEECH_FLUSH_FRAMES = 28 // 1,4 detik untuk ucapan nonstop
        private const val MIN_PAUSE_FLUSH_FRAMES = 10 // 0,5 detik bicara + jeda 200ms -> langsung flush
        private const val PAUSE_SILENCE_FRAMES = 4 // 200ms jeda bicara
        private const val MAX_RING_FRAMES = 24 // simpan maks 1,2 detik audio paling segar (0 drift)
        private const val MIN_IMMEDIATE_DRAIN_FRAMES = 16 // >= 0,8 detik audio segar -> langsung proses
        private const val TURN_LOCK_TIMEOUT_MS = 3200L

        private val SILENT_250MS_PCM = ByteArray(16_000 * 2 * 250 / 1000)
        private val FALLBACK_LIVE_MODELS = listOf(
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-live-2.5-flash-preview",
            "gemini-2.0-flash-live-001"
        )
    }
}
