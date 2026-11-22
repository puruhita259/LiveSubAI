package com.livesub.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.livesub.ai.data.LiveSubStateHolder
import com.livesub.ai.data.SettingsRepository
import com.livesub.ai.service.OverlayTranslationService
import com.livesub.ai.ui.LiveSubApp
import com.livesub.ai.ui.theme.LiveSubTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository

    private val hasOverlayPermissionState = mutableStateOf(false)
    private val hasAudioPermissionState = mutableStateOf(false)

    private var pendingStartAfterPermissions: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        if (hasAudioPermissionState.value) {
            pendingStartAfterPermissions?.invoke()
        } else {
            Toast.makeText(
                this,
                "Izin audio diperlukan untuk menangkap suara video.",
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingStartAfterPermissions = null
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, OverlayTranslationService::class.java).apply {
                action = OverlayTranslationService.ACTION_START_MEDIA_PROJECTION
                putExtra(OverlayTranslationService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayTranslationService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            Toast.makeText(
                this,
                "Tangkap audio dibatalkan. Anda tetap bisa memakai Uji Overlay atau Mode Mikrofon.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsRepository = SettingsRepository(this)
        settingsRepository.loadIntoState()
        refreshPermissions()
        handleIncomingIntent(intent)

        setContent {
            LiveSubTheme {
                LiveSubApp(
                    hasOverlayPermission = hasOverlayPermissionState.value,
                    hasAudioPermission = hasAudioPermissionState.value,
                    onRequestOverlayPermission = { openOverlayPermissionSettings() },
                    onRequestAudioPermission = { requestAudioAndNotificationPermissions(null) },
                    onStartLiveCapture = { startLiveMediaProjectionFlow() },
                    onStartMicMode = { startMicrophoneFlow() },
                    onStartDemoOverlay = { startDemoOverlayFlow() },
                    onStopOverlay = { stopOverlayService() },
                    onSaveConfig = { updated ->
                        settingsRepository.save(updated)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                LiveSubStateHolder.setSharedVideoUrl(sharedText.trim())
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_TRIGGER_OVERLAY, false)) {
            intent.removeExtra(EXTRA_AUTO_TRIGGER_OVERLAY)
            startLiveMediaProjectionFlow()
        }
    }

    private fun refreshPermissions() {
        hasOverlayPermissionState.value = Settings.canDrawOverlays(this)
        hasAudioPermissionState.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestAudioAndNotificationPermissions(onGranted: (() -> Unit)?) {
        pendingStartAfterPermissions = onGranted
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startLiveMediaProjectionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan tampil di atas aplikasi lain terlebih dahulu.", Toast.LENGTH_SHORT).show()
            openOverlayPermissionSettings()
            return
        }
        if (!hasAudioPermissionState.value) {
            requestAudioAndNotificationPermissions { startLiveMediaProjectionFlow() }
            return
        }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startMicrophoneFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan tampil di atas aplikasi lain terlebih dahulu.", Toast.LENGTH_SHORT).show()
            openOverlayPermissionSettings()
            return
        }
        if (!hasAudioPermissionState.value) {
            requestAudioAndNotificationPermissions { startMicrophoneFlow() }
            return
        }
        val intent = Intent(this, OverlayTranslationService::class.java).apply {
            action = OverlayTranslationService.ACTION_START_MIC
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startDemoOverlayFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan tampil di atas aplikasi lain terlebih dahulu.", Toast.LENGTH_SHORT).show()
            openOverlayPermissionSettings()
            return
        }
        val intent = Intent(this, OverlayTranslationService::class.java).apply {
            action = OverlayTranslationService.ACTION_START_DEMO
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayTranslationService::class.java).apply {
            action = OverlayTranslationService.ACTION_STOP
        }
        startService(intent)
    }

    companion object {
        const val EXTRA_AUTO_TRIGGER_OVERLAY = "extra_auto_trigger_overlay"
    }
}
