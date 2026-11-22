package com.livesub.ai.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.livesub.ai.MainActivity
import com.livesub.ai.R
import com.livesub.ai.ai.GeminiLiveWebSocketClient
import com.livesub.ai.ai.GeminiTranslationClient
import com.livesub.ai.audio.AudioCaptureManager
import com.livesub.ai.data.LiveSubStateHolder
import com.livesub.ai.data.SettingsRepository
import com.livesub.ai.model.CaptureMode
import com.livesub.ai.model.ServiceStatus
import com.livesub.ai.model.SurfaceStyle
import com.livesub.ai.model.TranslationEngine
import com.livesub.ai.overlay.OverlayViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayTranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var geminiRestClient: GeminiTranslationClient
    private lateinit var geminiLiveClient: GeminiLiveWebSocketClient
    private var overlayController: OverlayViewController? = null

    private var mediaProjection: MediaProjection? = null
    private var captureJob: Job? = null
    private var consumerJob: Job? = null
    private var reconnectJob: Job? = null

    private val demoLines = listOf(
        "Pertama, panaskan kaldu dengan api kecil agar aromanya keluar perlahan.",
        "Tambahkan irisan jahe segar dan bawang putih panggang secukupnya.",
        "Tunggu hingga mendidih ringan, lalu kecilkan api selama dua puluh menit.",
        "Sekarang kuah siap disajikan dengan mi hangat dan topping pilihan Anda."
    )

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        settingsRepository.loadIntoState()
        audioCaptureManager = AudioCaptureManager(this)
        geminiRestClient = GeminiTranslationClient()
        geminiLiveClient = GeminiLiveWebSocketClient()

        overlayController = OverlayViewController(
            context = this,
            onCycleStyle = { cycleOverlayStyle() },
            onTogglePause = { togglePause() },
            onSwitchToMic = { switchToMicrophoneFallback() },
            onCloseOverlay = { stopSelf() }
        )

        createNotificationChannel()

        serviceScope.launch {
            LiveSubStateHolder.config.collectLatest { cfg ->
                if (LiveSubStateHolder.runtime.value.isOverlayActive) {
                    overlayController?.render(cfg, LiveSubStateHolder.runtime.value)
                }
            }
        }
        serviceScope.launch {
            LiveSubStateHolder.runtime.collectLatest { state ->
                if (state.isOverlayActive) {
                    overlayController?.render(LiveSubStateHolder.config.value, state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_DEMO -> {
                startServiceForeground(CaptureMode.DEMO_SIMULATION)
                activateOverlay(CaptureMode.DEMO_SIMULATION, TranslationEngine.LIVE_WEBSOCKET)
                startDemoLoop()
            }
            ACTION_START_MIC -> {
                startServiceForeground(CaptureMode.MICROPHONE)
                startTranslationPipeline(CaptureMode.MICROPHONE)
            }
            ACTION_START_MEDIA_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startServiceForeground(CaptureMode.INTERNAL_AUDIO)
                    val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection?.stop()
                    mediaProjection = pm.getMediaProjection(resultCode, resultData)
                    startTranslationPipeline(CaptureMode.INTERNAL_AUDIO)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopActivePipelines()
        mediaProjection?.stop()
        mediaProjection = null
        overlayController?.dismiss()
        LiveSubStateHolder.updateRuntime {
            it.copy(
                isOverlayActive = false,
                isPaused = false,
                status = ServiceStatus.IDLE
            )
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopActivePipelines() {
        reconnectJob?.cancel()
        reconnectJob = null
        captureJob?.cancel()
        captureJob = null
        consumerJob?.cancel()
        consumerJob = null
        geminiLiveClient.stopSession()
    }

    private fun startTranslationPipeline(mode: CaptureMode) {
        val cfg = LiveSubStateHolder.config.value
        if (cfg.geminiApiKey.isBlank()) {
            activateOverlay(mode, cfg.translationEngine)
            LiveSubStateHolder.updateRuntime {
                it.copy(
                    status = ServiceStatus.LISTENING,
                    currentSubtitle = "Masukkan Gemini API Key di tab Tampilan & AI untuk mulai menerjemahkan."
                )
            }
            return
        }

        if (cfg.translationEngine == TranslationEngine.LIVE_WEBSOCKET) {
            activateOverlay(mode, TranslationEngine.LIVE_WEBSOCKET)
            startLiveWebSocketPipeline(mode)
        } else {
            activateOverlay(mode, TranslationEngine.FAST_REST)
            startParallelRestPipeline(mode)
        }
    }

    private fun startLiveWebSocketPipeline(mode: CaptureMode) {
        stopActivePipelines()
        val cfg = LiveSubStateHolder.config.value

        LiveSubStateHolder.updateRuntime {
            it.copy(
                activeEngine = TranslationEngine.LIVE_WEBSOCKET,
                status = ServiceStatus.CONNECTING,
                currentSubtitle = "Menghubungkan Gemini Live WebSocket..."
            )
        }

        val listener = object : GeminiLiveWebSocketClient.Listener {
            override fun onConnected(modelName: String, isAudioTranscriptionMode: Boolean) {
                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        activeEngine = TranslationEngine.LIVE_WEBSOCKET,
                        activeModelName = modelName,
                        status = ServiceStatus.LISTENING,
                        currentSubtitle = "Live WS aktif ($modelName). Putar video bersuara...",
                        errorHint = null
                    )
                }
            }

            override fun onSubtitleUpdated(subtitle: String) {
                if (LiveSubStateHolder.runtime.value.isPaused) return
                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        status = ServiceStatus.LISTENING,
                        currentSubtitle = subtitle,
                        errorHint = null
                    )
                }
            }

            override fun onFallbackToRest(reason: String) {
                serviceScope.launch(Dispatchers.Main) {
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            activeEngine = TranslationEngine.FAST_REST,
                            status = ServiceStatus.LISTENING,
                            currentSubtitle = "$reason (Mode REST Cepat aktif)"
                        )
                    }
                    startParallelRestPipeline(mode)
                }
            }

            override fun onDisconnected(reason: String, shouldReconnect: Boolean) {
                if (!shouldReconnect || !LiveSubStateHolder.runtime.value.isOverlayActive) return
                val selfListener = this
                reconnectJob?.cancel()
                reconnectJob = serviceScope.launch(Dispatchers.Main) {
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            status = ServiceStatus.CONNECTING,
                            currentSubtitle = "Menyambungkan ulang Gemini Live..."
                        )
                    }
                    delay(1200L)
                    if (LiveSubStateHolder.runtime.value.isOverlayActive &&
                        LiveSubStateHolder.runtime.value.activeEngine == TranslationEngine.LIVE_WEBSOCKET
                    ) {
                        val latestCfg = LiveSubStateHolder.config.value
                        geminiLiveClient.startSession(
                            apiKey = latestCfg.geminiApiKey,
                            preferredModel = latestCfg.liveModel,
                            sourceLanguage = latestCfg.sourceLanguage,
                            targetLanguage = latestCfg.targetLanguage,
                            listener = selfListener
                        )
                    }
                }
            }
        }

        // 1. Jalankan koneksi WebSocket + auto-discovery model Live
        consumerJob = serviceScope.launch {
            geminiLiveClient.startSession(
                apiKey = cfg.geminiApiKey,
                preferredModel = cfg.liveModel,
                sourceLanguage = cfg.sourceLanguage,
                targetLanguage = cfg.targetLanguage,
                listener = listener
            )
        }

        // 2. Jalankan streaming PCM 100ms secara paralel tanpa memblokir
        captureJob = serviceScope.launch {
            try {
                if (mode == CaptureMode.INTERNAL_AUDIO) {
                    val projection = mediaProjection ?: return@launch
                    audioCaptureManager.streamInternalPcmFrames(
                        mediaProjection = projection,
                        frameDurationMs = 100L,
                        onFrameReady = { frame ->
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                geminiLiveClient.sendPcmFrame(frame.pcmBytes)
                            }
                        },
                        onDrmSilenceDetected = {
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                LiveSubStateHolder.updateRuntime {
                                    it.copy(
                                        status = ServiceStatus.DRM_BLOCKED,
                                        currentSubtitle = "Audio aplikasi ini dilindungi DRM atau hening. Ketuk tombol di bawah untuk pakai mikrofon."
                                    )
                                }
                            }
                        }
                    )
                } else {
                    audioCaptureManager.streamMicrophonePcmFrames(
                        frameDurationMs = 100L,
                        onFrameReady = { frame ->
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                geminiLiveClient.sendPcmFrame(frame.pcmBytes)
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        status = if (mode == CaptureMode.INTERNAL_AUDIO) ServiceStatus.DRM_BLOCKED else ServiceStatus.OFFLINE,
                        currentSubtitle = e.localizedMessage ?: "Gagal menangkap audio."
                    )
                }
            }
        }
    }

    private fun startParallelRestPipeline(mode: CaptureMode) {
        stopActivePipelines()

        val chunkQueue = Channel<AudioCaptureManager.AudioChunk>(
            capacity = 2,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        LiveSubStateHolder.updateRuntime {
            it.copy(
                activeEngine = TranslationEngine.FAST_REST,
                status = ServiceStatus.LISTENING
            )
        }

        // Consumer: kirim chunk ke Gemini REST paralel tanpa menahan AudioRecord
        consumerJob = serviceScope.launch(Dispatchers.IO) {
            for (chunk in chunkQueue) {
                if (!isActive) break
                if (LiveSubStateHolder.runtime.value.isPaused) continue
                processRestAudioChunk(chunk.wavBytes)
            }
        }

        // Producer: rekam chunk 1,8 detik (dengan early VAD flush) secara terus-menerus
        captureJob = serviceScope.launch {
            try {
                if (mode == CaptureMode.INTERNAL_AUDIO) {
                    val projection = mediaProjection ?: return@launch
                    audioCaptureManager.streamInternalAudio(
                        mediaProjection = projection,
                        chunkDurationMs = 1800L,
                        onChunkReady = { chunk ->
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                chunkQueue.trySend(chunk)
                            }
                        },
                        onDrmSilenceDetected = {
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                LiveSubStateHolder.updateRuntime {
                                    it.copy(
                                        status = ServiceStatus.DRM_BLOCKED,
                                        currentSubtitle = "Audio aplikasi ini dilindungi DRM atau hening. Ketuk tombol di bawah untuk pakai mikrofon."
                                    )
                                }
                            }
                        }
                    )
                } else {
                    audioCaptureManager.streamMicrophoneAudio(
                        chunkDurationMs = 1800L,
                        onChunkReady = { chunk ->
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                chunkQueue.trySend(chunk)
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        status = if (mode == CaptureMode.INTERNAL_AUDIO) ServiceStatus.DRM_BLOCKED else ServiceStatus.OFFLINE,
                        currentSubtitle = e.localizedMessage ?: "Gagal menangkap audio."
                    )
                }
            } finally {
                chunkQueue.close()
            }
        }
    }

    private suspend fun processRestAudioChunk(wavBytes: ByteArray) {
        val cfg = LiveSubStateHolder.config.value
        LiveSubStateHolder.updateRuntime { it.copy(status = ServiceStatus.TRANSLATING) }

        when (
            val result = geminiRestClient.translateAudioChunk(
                wavBytes = wavBytes,
                apiKey = cfg.geminiApiKey,
                model = cfg.geminiModel,
                sourceLanguage = cfg.sourceLanguage,
                targetLanguage = cfg.targetLanguage
            )
        ) {
            is GeminiTranslationClient.TranslationResult.Success -> {
                if (result.translatedText.isNotBlank()) {
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            activeModelName = result.usedModel,
                            status = ServiceStatus.LISTENING,
                            currentSubtitle = result.translatedText,
                            errorHint = null
                        )
                    }
                } else {
                    LiveSubStateHolder.updateRuntime { it.copy(status = ServiceStatus.LISTENING) }
                }
            }
            is GeminiTranslationClient.TranslationResult.Failure -> {
                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        status = if (result.isNetworkError) ServiceStatus.OFFLINE else ServiceStatus.LISTENING,
                        currentSubtitle = result.message,
                        errorHint = result.message
                    )
                }
            }
        }
    }

    private fun startServiceForeground(mode: CaptureMode) {
        val notification = buildNotification(mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgsType = when (mode) {
                CaptureMode.INTERNAL_AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                CaptureMode.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                CaptureMode.DEMO_SIMULATION -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            val fgsType = if (mode == CaptureMode.INTERNAL_AUDIO) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        }
    }

    private fun activateOverlay(mode: CaptureMode, engine: TranslationEngine) {
        LiveSubStateHolder.updateRuntime {
            it.copy(
                isOverlayActive = true,
                isPaused = false,
                captureMode = mode,
                activeEngine = engine,
                status = ServiceStatus.LISTENING,
                currentSubtitle = when (mode) {
                    CaptureMode.DEMO_SIMULATION -> demoLines.first()
                    CaptureMode.MICROPHONE -> "Mode mikrofon aktif. Arahkan suara video agar diterjemahkan..."
                    CaptureMode.INTERNAL_AUDIO -> "Mendengarkan suara video dari aplikasi lain..."
                },
                errorHint = null
            )
        }
        overlayController?.show(
            LiveSubStateHolder.config.value,
            LiveSubStateHolder.runtime.value
        )
    }

    private fun startDemoLoop() {
        stopActivePipelines()
        captureJob = serviceScope.launch {
            var index = 0
            while (isActive) {
                delay(2800L)
                if (!LiveSubStateHolder.runtime.value.isPaused) {
                    index = (index + 1) % demoLines.size
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            status = ServiceStatus.TRANSLATING,
                            currentSubtitle = demoLines[index]
                        )
                    }
                }
            }
        }
    }

    private fun cycleOverlayStyle() {
        val current = LiveSubStateHolder.config.value
        val styles = SurfaceStyle.entries
        val next = styles[(styles.indexOf(current.surfaceStyle) + 1) % styles.size]
        settingsRepository.save(current.copy(surfaceStyle = next))
    }

    private fun togglePause() {
        LiveSubStateHolder.updateRuntime { current ->
            val nextPaused = !current.isPaused
            current.copy(
                isPaused = nextPaused,
                status = if (nextPaused) ServiceStatus.PAUSED else ServiceStatus.LISTENING
            )
        }
    }

    private fun switchToMicrophoneFallback() {
        if (!audioCaptureManager.hasRecordAudioPermission()) {
            LiveSubStateHolder.updateRuntime {
                it.copy(currentSubtitle = "Buka aplikasi LiveSub AI lalu izinkan akses mikrofon terlebih dahulu.")
            }
            return
        }
        stopActivePipelines()
        mediaProjection?.stop()
        mediaProjection = null
        startServiceForeground(CaptureMode.MICROPHONE)
        startTranslationPipeline(CaptureMode.MICROPHONE)
    }

    private fun buildNotification(mode: CaptureMode): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayTranslationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_livesub)
            .setContentTitle("LiveSub AI aktif")
            .setContentText("Mode: ${mode.label}")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Hentikan", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Terjemahan Overlay LiveSub AI",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Menjaga layanan subtitle real-time tetap aktif di atas aplikasi video."
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "livesub_overlay_channel"
        const val NOTIFICATION_ID = 1701

        const val ACTION_START_MEDIA_PROJECTION = "com.livesub.ai.ACTION_START_MEDIA_PROJECTION"
        const val ACTION_START_MIC = "com.livesub.ai.ACTION_START_MIC"
        const val ACTION_START_DEMO = "com.livesub.ai.ACTION_START_DEMO"
        const val ACTION_STOP = "com.livesub.ai.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
