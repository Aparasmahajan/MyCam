package io.frame.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.frame.app.FrameApp
import io.frame.app.MainActivity
import kotlinx.coroutines.launch

/**
 * Quick Settings tile. Starting opens the app first: Android 14+ only lets a camera service
 * start from a visible activity, so the tile launches MainActivity, which starts the dashcam.
 */
class RecordTileService : TileService() {

    private val container get() = (application as FrameApp).container

    override fun onStartListening() {
        super.onStartListening()
        val recording = container.engine.isRecording
        qsTile?.apply {
            state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (recording) "Stop dashcam" else "Dashcam"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (container.engine.isRecording) {
            container.scope.launch { container.engine.stop() }
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = "Dashcam"
                updateTile()
            }
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_START_DASHCAM, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
