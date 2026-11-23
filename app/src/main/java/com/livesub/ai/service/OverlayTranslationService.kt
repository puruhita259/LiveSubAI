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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.livesub.ai.MainActivity
import com.livesub.ai.R
import com.livesub.ai.ai.HybridSttClient
import com.livesub.ai.ai.OfflineSttManager
import com.livesub.ai.ai.OfflineTranslatorManager
import com.livesub.ai.audio.AudioCaptureManager
import com.livesub.ai.data.LiveSubStateHolder
import com.livesub.ai.data.SettingsRepository
import com.livesub.ai.model.CaptureMode
import com.livesub.ai.model.EngineMode
import com.livesub.ai.model.LanguageCatalog
import com.livesub.ai.model.OverlayConfig
import com.livesub.ai.model.ServiceStatus
import com.livesub.ai.model.SurfaceStyle
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

class OverlayTranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var hybridSttClient: HybridSttClient
    private lateinit var offlineSttManager: OfflineSttManager
    private lateinit var offlineTranslator: OfflineTranslatorManager
    private var overlayController: OverlayViewController? = null

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureJob: Job? = null
    private val workerJobs = mutableListOf<Job>()
    private var activeVoskSession: OfflineSttManager.StreamingSession? = null

    private var activePipelineSignature: String = ""
    private val highestDisplayedSeq = AtomicLong(0L)

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
        hybridSttClient = HybridSttClient()
        offlineSttManager = OfflineSttManager(this)
        offlineTranslator = OfflineTranslatorManager()

        overlayController = OverlayViewController(
            context = this,
            onFontSizeDelta = { delta -> adjustFontSize(delta) },
            onCycleStyle = { cycleOverlayStyle() },
            onTogglePause = { togglePause() },
            onSwitchToMic = { switchToMicrophoneFallback() },
            onCloseOverlay = { stopSelf() }
        )

        createNotificationChannel()

        // Render ulang overlay + auto-restart pipeline jika user mengganti mesin/bahasa saat overlay aktif
        serviceScope.launch {
            LiveSubStateHolder.config.collectLatest { cfg ->
                val rt = LiveSubStateHolder.runtime.value
                if (rt.isOverlayActive) {
                    overlayController?.render(cfg, rt)
                    val sig = pipelineSignature(cfg, rt.captureMode)
                    if (rt.captureMode != CaptureMode.DEMO_SIMULATION &&
                        activePipelineSignature.isNotEmpty() &&
                        sig != activePipelineSignature
                    ) {
                        startTranslationPipeline(rt.captureMode)
                    }
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
                activateOverlay(CaptureMode.DEMO_SIMULATION, LiveSubStateHolder.config.value)
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
                    releaseMediaProjection()
                    val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val proj = pm.getMediaProjection(resultCode, resultData)
                    if (proj != null) {
                        val cb = object : MediaProjection.Callback() {
                            override fun onStop() {
                                serviceScope.launch(Dispatchers.Main) {
                                    if (LiveSubStateHolder.runtime.value.isOverlayActive &&
                                        LiveSubStateHolder.runtime.value.captureMode == CaptureMode.INTERNAL_AUDIO
                                    ) {
                                        LiveSubStateHolder.updateRuntime {
                                            it.copy(
                                                status = ServiceStatus.DRM_BLOCKED,
                                                currentSubtitle = "Izin tangkap layar dihentikan sistem. Aktifkan ulang atau pakai mikrofon."
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        proj.registerCallback(cb, Handler(Looper.getMainLooper()))
                        projectionCallback = cb
                        mediaProjection = proj
                        startTranslationPipeline(CaptureMode.INTERNAL_AUDIO)
                    } else {
                        stopSelf()
                        return START_NOT_STICKY
                    }
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
        releaseMediaProjection()
        offlineTranslator.closeAll()
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

    private fun pipelineSignature(cfg: OverlayConfig, mode: CaptureMode): String {
        return "${mode.name}|${cfg.engineMode.name}|${cfg.hybridProvider.name}|${cfg.offlineSttModelId}|${cfg.sourceLangCode}|${cfg.targetLangCode}|${cfg.groqApiKey.hashCode()}|${cfg.cloudflareAccountId.hashCode()}|${cfg.cloudflareApiToken.hashCode()}"
    }

    private fun releaseMediaProjection() {
        projectionCallback?.let { cb ->
            try {
                mediaProjection?.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    private fun stopActivePipelines() {
        captureJob?.cancel()
        captureJob = null
        workerJobs.forEach { it.cancel() }
        workerJobs.clear()
        try {
            activeVoskSession?.close()
        } catch (_: Exception) {
        }
        activeVoskSession = null
        highestDisplayedSeq.set(0L)
    }

    private fun startTranslationPipeline(mode: CaptureMode) {
        stopActivePipelines()
        val cfg = LiveSubStateHolder.config.value
        activePipelineSignature = pipelineSignature(cfg, mode)
        activateOverlay(mode, cfg)

        when (cfg.engineMode) {
            EngineMode.HYBRID_CLOUD_STT -> startHybridPipeline(mode, cfg)
            EngineMode.FULL_OFFLINE -> startFullOfflinePipeline(mode, cfg)
        }
    }

    // =========================================================================
    // MODE 1: HYBRID (Overlapping Audio -> 2 Parallel Groq/CF STT -> ML Kit < 50ms)
    // =========================================================================
    private fun startHybridPipeline(mode: CaptureMode, cfg: OverlayConfig) {
        val hasGroq = cfg.groqApiKey.isNotBlank()
        val hasCf = cfg.cloudflareAccountId.isNotBlank() && cfg.cloudflareApiToken.isNotBlank()
        if (!hasGroq && !hasCf) {
            LiveSubStateHolder.updateRuntime {
                it.copy(
                    status = ServiceStatus.IDLE,
                    currentSubtitle = "Masukkan API Key Groq Console atau Account ID + Token Cloudflare di tab Tampilan & AI."
                )
            }
            return
        }

        val chunkQueue = Channel<AudioCaptureManager.OverlappingChunk>(
            capacity = 3,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        // 2 worker STT paralel agar audio tidak pernah menunggu/kepotong saat bicara cepat
        repeat(2) {
            val worker = serviceScope.launch(Dispatchers.IO) {
                for (chunk in chunkQueue) {
                    if (!isActive) break
                    if (LiveSubStateHolder.runtime.value.isPaused) continue
                    processHybridChunk(chunk)
                }
            }
            workerJobs.add(worker)
        }

        // Tahap 0: Perekam audio independen dengan overlap 350ms
        captureJob = serviceScope.launch {
            try {
                if (mode == CaptureMode.INTERNAL_AUDIO) {
                    val projection = mediaProjection ?: return@launch
                    audioCaptureManager.streamInternalOverlappingChunks(
                        mediaProjection = projection,
                        chunkDurationMs = 1550L,
                        overlapMs = 350L,
                        onChunkReady = { chunk ->
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                chunkQueue.trySend(chunk)
                            }
                        },
                        onAudioActive = { handleAudioResumed() },
                        onIdleSilence = { handleIdleSilence() },
                        onDrmBlockedDetected = { handleDrmBlocked() }
                    )
                } else {
                    audioCaptureManager.streamMicrophoneOverlappingChunks(
                        chunkDurationMs = 1550L,
                        overlapMs = 350L,
                        onChunkReady = { chunk ->
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                chunkQueue.trySend(chunk)
                            }
                        },
                        onAudioActive = { handleAudioResumed() }
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

    private suspend fun processHybridChunk(chunk: AudioCaptureManager.OverlappingChunk) {
        val cfg = LiveSubStateHolder.config.value

        val sttResult = withTimeoutOrNull(9500L) {
            hybridSttClient.transcribe(chunk.wavBytes, cfg)
        }

        if (sttResult == null) {
            LiveSubStateHolder.updateRuntime { it.copy(status = ServiceStatus.RECOVERING) }
            return
        }

        when (sttResult) {
            is HybridSttClient.SttResult.Success -> {
                val transcript = sttResult.transcript
                if (transcript.isBlank()) {
                    LiveSubStateHolder.updateRuntime { it.copy(status = ServiceStatus.LISTENING) }
                    return
                }

                // Abaikan jika chunk lebih baru sudah lebih dulu tampil
                if (chunk.seqId < highestDisplayedSeq.get()) return

                // Tahap 2: Translate teks lokal lewat ML Kit (< 50 ms)
                val sourceHint = if (cfg.sourceLangCode == "auto") {
                    sttResult.detectedLanguage
                } else {
                    cfg.sourceLangCode
                }

                try {
                    val translatedOut = offlineTranslator.translateOffline(
                        rawText = transcript,
                        sourceLangHint = sourceHint,
                        targetLangCode = cfg.targetLangCode,
                        allowAutoDownload = true
                    )

                    if (chunk.seqId < highestDisplayedSeq.get()) return
                    highestDisplayedSeq.set(chunk.seqId)

                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            activeProviderTag = sttResult.provider.shortTag,
                            status = ServiceStatus.LISTENING,
                            lastSourceTranscript = transcript,
                            lastDetectedLangCode = translatedOut.detectedSourceLang,
                            currentSubtitle = translatedOut.translatedText,
                            translateLatencyMs = translatedOut.latencyMs,
                            errorHint = null
                        )
                    }
                } catch (e: Exception) {
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            status = ServiceStatus.LISTENING,
                            currentSubtitle = e.localizedMessage ?: "Gagal memuat kamus terjemahan offline."
                        )
                    }
                }
            }
            is HybridSttClient.SttResult.Failure -> {
                if (sttResult.isRateLimited) {
                    LiveSubStateHolder.updateRuntime {
                        it.copy(status = ServiceStatus.RECOVERING, errorHint = sttResult.message)
                    }
                    delay(1200L)
                } else {
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            status = if (sttResult.isNetworkError) ServiceStatus.OFFLINE else ServiceStatus.LISTENING,
                            currentSubtitle = sttResult.message,
                            errorHint = sttResult.message
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // MODE 2: FULL OFFLINE (50ms PCM -> Vosk Streaming STT -> ML Kit < 50ms)
    // =========================================================================
    private fun startFullOfflinePipeline(mode: CaptureMode, cfg: OverlayConfig) {
        val spec = LanguageCatalog.findOfflineSttById(cfg.offlineSttModelId)
        val modelDir = offlineSttManager.resolveModelDir(spec.id)

        if (modelDir == null) {
            LiveSubStateHolder.updateRuntime {
                it.copy(
                    activeProviderTag = "OFFLINE",
                    status = ServiceStatus.IDLE,
                    currentSubtitle = "Model suara offline '${spec.displayName}' belum diunduh. Buka tab Paket Offline untuk mengunduhnya (${spec.sizeLabel})."
                )
            }
            return
        }

        val pcmChannel = Channel<ByteArray>(
            capacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val transcriptChannel = Channel<OfflineSttManager.StreamingSession.TranscriptEvent>(
            capacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        LiveSubStateHolder.updateRuntime {
            it.copy(
                activeProviderTag = "OFFLINE·${spec.langCode.uppercase()}",
                status = ServiceStatus.PREPARING,
                currentSubtitle = "Memuat model suara offline ${spec.displayName}..."
            )
        }

        // Worker 1 (STT Lokal): baca PCM 50ms -> Vosk streaming kata per kata
        val sttWorker = serviceScope.launch(Dispatchers.Default) {
            var session: OfflineSttManager.StreamingSession? = null
            try {
                session = OfflineSttManager.StreamingSession(modelDir)
                activeVoskSession = session

                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        status = ServiceStatus.LISTENING,
                        currentSubtitle = "Full Offline aktif (${spec.displayName}). Putar video bersuara..."
                    )
                }

                var lastEmittedPartial = ""
                var lastPartialSentAt = 0L

                for (pcm in pcmChannel) {
                    if (!isActive) break
                    if (LiveSubStateHolder.runtime.value.isPaused) continue

                    val event = session.feedPcm(pcm) ?: continue
                    val now = SystemClock.elapsedRealtime()
                    if (event.isFinal) {
                        lastEmittedPartial = ""
                        lastPartialSentAt = now
                        transcriptChannel.trySend(event)
                    } else if (event.text != lastEmittedPartial && (now - lastPartialSentAt) >= 160L) {
                        lastEmittedPartial = event.text
                        lastPartialSentAt = now
                        transcriptChannel.trySend(event)
                    }
                }
            } catch (e: Exception) {
                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        status = ServiceStatus.IDLE,
                        currentSubtitle = "Gagal memuat model offline: ${e.localizedMessage ?: "coba unduh ulang"}."
                    )
                }
            } finally {
                try {
                    session?.close()
                } catch (_: Exception) {
                }
                if (activeVoskSession === session) {
                    activeVoskSession = null
                }
                transcriptChannel.close()
            }
        }
        workerJobs.add(sttWorker)

        // Worker 2 (Translate Offline < 50 ms): terjemahkan teks streaming seketika tanpa tunggu STT
        val translateWorker = serviceScope.launch(Dispatchers.Default) {
            for (event in transcriptChannel) {
                if (!isActive) break
                val latestCfg = LiveSubStateHolder.config.value
                try {
                    val out = offlineTranslator.translateOffline(
                        rawText = event.text,
                        sourceLangHint = spec.langCode,
                        targetLangCode = latestCfg.targetLangCode,
                        allowAutoDownload = true
                    )
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            status = ServiceStatus.LISTENING,
                            lastSourceTranscript = event.text,
                            lastDetectedLangCode = spec.langCode,
                            currentSubtitle = out.translatedText,
                            translateLatencyMs = out.latencyMs,
                            errorHint = null
                        )
                    }
                } catch (e: Exception) {
                    // Tetap tampilkan transkrip asli jika kamus terjemahan target belum diunduh saat offline penuh
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            status = ServiceStatus.LISTENING,
                            lastSourceTranscript = event.text,
                            currentSubtitle = "${event.text} (Unduh kamus ${latestCfg.targetLangCode.uppercase()} di tab Paket Offline)",
                            errorHint = e.localizedMessage
                        )
                    }
                }
            }
        }
        workerJobs.add(translateWorker)

        // Tahap 0: Perekam audio 50ms nonstop (terpisah total dari STT & Translate)
        captureJob = serviceScope.launch {
            try {
                if (mode == CaptureMode.INTERNAL_AUDIO) {
                    val projection = mediaProjection ?: return@launch
                    audioCaptureManager.streamInternalPcmFrames(
                        mediaProjection = projection,
                        frameDurationMs = 50L,
                        onFrameReady = { frame ->
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                pcmChannel.trySend(frame.pcmBytes)
                            }
                        },
                        onAudioActive = { handleAudioResumed() },
                        onIdleSilence = { handleIdleSilence() },
                        onDrmBlockedDetected = { handleDrmBlocked() }
                    )
                } else {
                    audioCaptureManager.streamMicrophonePcmFrames(
                        frameDurationMs = 50L,
                        onFrameReady = { frame ->
                            if (!LiveSubStateHolder.runtime.value.isPaused) {
                                pcmChannel.trySend(frame.pcmBytes)
                            }
                        },
                        onAudioActive = { handleAudioResumed() }
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
                pcmChannel.close()
            }
        }
    }

    private fun handleAudioResumed() {
        val rt = LiveSubStateHolder.runtime.value
        if (rt.isPaused) return
        if (rt.status == ServiceStatus.DRM_BLOCKED || rt.status == ServiceStatus.WAITING_AUDIO) {
            LiveSubStateHolder.updateRuntime { current ->
                val clearDrmText = current.currentSubtitle.startsWith("Audio aplikasi ini dilindungi")
                current.copy(
                    status = ServiceStatus.LISTENING,
                    currentSubtitle = if (clearDrmText) "Suara video terdeteksi. Menerjemahkan..." else current.currentSubtitle
                )
            }
        }
    }

    private fun handleIdleSilence() {
        val rt = LiveSubStateHolder.runtime.value
        if (rt.isPaused || rt.status == ServiceStatus.DRM_BLOCKED || rt.status == ServiceStatus.PREPARING) return
        LiveSubStateHolder.updateRuntime {
            it.copy(status = ServiceStatus.WAITING_AUDIO)
        }
    }

    private fun handleDrmBlocked() {
        if (LiveSubStateHolder.runtime.value.isPaused) return
        LiveSubStateHolder.updateRuntime {
            it.copy(
                status = ServiceStatus.DRM_BLOCKED,
                currentSubtitle = "Audio aplikasi ini dilindungi DRM. Ketuk tombol di bawah untuk pakai mikrofon."
            )
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

    private fun activateOverlay(mode: CaptureMode, cfg: OverlayConfig) {
        val tag = when (cfg.engineMode) {
            EngineMode.HYBRID_CLOUD_STT -> cfg.hybridProvider.shortTag
            EngineMode.FULL_OFFLINE -> "OFFLINE"
        }
        LiveSubStateHolder.updateRuntime {
            it.copy(
                isOverlayActive = true,
                isPaused = false,
                captureMode = mode,
                activeEngine = cfg.engineMode,
                activeProviderTag = tag,
                status = ServiceStatus.LISTENING,
                translateLatencyMs = 0L,
                lastSourceTranscript = "",
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
        activePipelineSignature = ""
        captureJob = serviceScope.launch {
            var index = 0
            while (isActive) {
                delay(2600L)
                if (!LiveSubStateHolder.runtime.value.isPaused) {
                    index = (index + 1) % demoLines.size
                    LiveSubStateHolder.updateRuntime {
                        it.copy(
                            status = ServiceStatus.LISTENING,
                            translateLatencyMs = 28L,
                            currentSubtitle = demoLines[index]
                        )
                    }
                }
            }
        }
    }

    private fun adjustFontSize(deltaSp: Float) {
        val current = LiveSubStateHolder.config.value
        val nextSize = (current.fontSizeSp + deltaSp).coerceIn(10f, 28f)
        if (nextSize != current.fontSizeSp) {
            settingsRepository.save(current.copy(fontSizeSp = nextSize))
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
        releaseMediaProjection()
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
