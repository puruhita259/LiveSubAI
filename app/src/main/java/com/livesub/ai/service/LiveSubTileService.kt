package com.livesub.ai.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.livesub.ai.MainActivity
import com.livesub.ai.data.LiveSubStateHolder

class LiveSubTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val isActive = LiveSubStateHolder.runtime.value.isOverlayActive
        if (isActive) {
            val stopIntent = Intent(this, OverlayTranslationService::class.java).apply {
                action = OverlayTranslationService.ACTION_STOP
            }
            startService(stopIntent)
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.updateTile()
        } else {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_AUTO_TRIGGER_OVERLAY, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    2026,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launchIntent)
            }
        }
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val active = LiveSubStateHolder.runtime.value.isOverlayActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "Aktif" else "Ketuk untuk mulai"
        }
        tile.updateTile()
    }
}
