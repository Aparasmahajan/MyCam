package io.frame.core.recording

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.frame.core.model.EventSource
import io.frame.core.model.EventType
import io.frame.core.model.Mode
import io.frame.core.model.StopReason
import kotlinx.coroutines.launch
import timber.log.Timber

/** Implemented by the Application so the service can reach the process-wide engine. */
interface RecordingEngineHost {
    val recordingEngine: RecordingEngine
}

/**
 * Keeps the process in the foreground (type camera|microphone|location) while a session runs,
 * so recording continues with the screen off. The engine does the actual work.
 */
class RecordingService : LifecycleService() {

    private val engine get() = (application as RecordingEngineHost).recordingEngine
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false
    private var mode = Mode.DASHCAM

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        lifecycleScope.launch {
            var sawActive = false
            engine.state.collect { state ->
                when (state) {
                    is RecorderState.Recording -> {
                        sawActive = true
                        acquireWakeLock()
                        getSystemService(NotificationManager::class.java)
                            .notify(Notifications.ID_RECORDING, Notifications.recording(this@RecordingService, state.mode, state))
                    }
                    is RecorderState.Starting, is RecorderState.Stopping -> sawActive = true
                    else -> if (sawActive) shutDown()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.DASHCAM
                if (!goForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val title = intent?.getStringExtra(EXTRA_TITLE)
                lifecycleScope.launch {
                    if (engine.start(mode, title).isFailure && engine.state.value !is RecorderState.Recording) shutDown()
                }
            }
            ACTION_STOP -> lifecycleScope.launch {
                engine.stop(StopReason.USER)
                if (!engine.isRecording) shutDown()
            }
            ACTION_MARK -> lifecycleScope.launch {
                val state = engine.state.value as? RecorderState.Recording ?: return@launch
                val type = if (state.mode == Mode.SPORTS) EventType.HIGHLIGHT_CUSTOM else EventType.MANUAL
                engine.markEvent(type, EventSource.NOTIFICATION)
            }
            else -> if (!inForeground) stopSelf()
        }
        // Not sticky: the OS must not restart the camera on its own; recovery offers to resume instead.
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        val notification = Notifications.recording(this, mode, null)
        return try {
            if (types == 0) {
                startForeground(Notifications.ID_RECORDING, notification)
            } else {
                ServiceCompat.startForeground(this, Notifications.ID_RECORDING, notification, types)
            }
            inForeground = true
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException: started from the background
            Timber.e(e, "Could not enter the foreground")
            Notifications.alert(this, "Could not start recording", "Open FRAME and tap Start.")
            false
        }
    }

    private fun shutDown() {
        releaseWakeLock()
        if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "frame:recording")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "io.frame.action.START"
        const val ACTION_STOP = "io.frame.action.STOP"
        const val ACTION_MARK = "io.frame.action.MARK"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TITLE = "title"
        private const val WAKE_LOCK_MAX_MS = 12L * 60 * 60 * 1000

        /** Call from a visible screen: Android only lets a camera service start while the app is in front. */
        fun start(context: Context, mode: Mode, title: String? = null) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
