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
import com.livesub.ai.ai.GeminiTranslationClient
import com.livesub.ai.audio.AudioCaptureManager
import com.livesub.ai.data.LiveSubStateHolder
import com.livesub.ai.data.SettingsRepository
import com.livesub.ai.model.CaptureMode
import com.livesub.ai.model.ServiceStatus
import com.livesub.ai.model.SurfaceStyle
import com.livesub.ai.overlay.OverlayViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayTranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var geminiClient: GeminiTranslationClient
    private var overlayController: OverlayViewController? = null

    private var mediaProjection: MediaProjection? = null
    private var captureJob: Job? = null

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
        geminiClient = GeminiTranslationClient()

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
                activateOverlay(CaptureMode.DEMO_SIMULATION)
                startDemoLoop()
            }
            ACTION_START_MIC -> {
                startServiceForeground(CaptureMode.MICROPHONE)
                activateOverlay(CaptureMode.MICROPHONE)
                startMicCaptureLoop()
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
                    activateOverlay(CaptureMode.INTERNAL_AUDIO)
                    startInternalAudioLoop()
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
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

    private fun activateOverlay(mode: CaptureMode) {
        LiveSubStateHolder.updateRuntime {
            it.copy(
                isOverlayActive = true,
                isPaused = false,
                captureMode = mode,
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
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            var index = 0
            while (isActive) {
                delay(3200L)
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

    private fun startInternalAudioLoop() {
        val projection = mediaProjection ?: return
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            try {
                audioCaptureManager.streamInternalAudio(
                    mediaProjection = projection,
                    onChunkReady = { chunk ->
                        if (!LiveSubStateHolder.runtime.value.isPaused) {
                            processAudioChunk(chunk.wavBytes)
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
            } catch (e: Exception) {
                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        status = ServiceStatus.DRM_BLOCKED,
                        currentSubtitle = e.localizedMessage ?: "Gagal menangkap audio internal."
                    )
                }
            }
        }
    }

    private fun startMicCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            try {
                audioCaptureManager.streamMicrophoneAudio(
                    onChunkReady = { chunk ->
                        if (!LiveSubStateHolder.runtime.value.isPaused) {
                            processAudioChunk(chunk.wavBytes)
                        }
                    }
                )
            } catch (e: Exception) {
                LiveSubStateHolder.updateRuntime {
                    it.copy(
                        status = ServiceStatus.OFFLINE,
                        currentSubtitle = e.localizedMessage ?: "Izin mikrofon diperlukan untuk mode fallback."
                    )
                }
            }
        }
    }

    private suspend fun processAudioChunk(wavBytes: ByteArray) {
        val cfg = LiveSubStateHolder.config.value
        if (cfg.geminiApiKey.isBlank()) {
            LiveSubStateHolder.updateRuntime {
                it.copy(
                    status = ServiceStatus.LISTENING,
                    currentSubtitle = "Audio video tertangkap! Masukkan Gemini API Key di tab Tampilan & AI untuk terjemahan langsung."
                )
            }
            return
        }

        LiveSubStateHolder.updateRuntime { it.copy(status = ServiceStatus.TRANSLATING) }
        when (
            val result = geminiClient.translateAudioChunk(
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
        mediaProjection?.stop()
        mediaProjection = null
        startServiceForeground(CaptureMode.MICROPHONE)
        activateOverlay(CaptureMode.MICROPHONE)
        startMicCaptureLoop()
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
