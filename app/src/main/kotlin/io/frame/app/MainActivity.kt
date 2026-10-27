package io.frame.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.frame.app.ui.FrameNavHost
import io.frame.app.ui.theme.FrameTheme
import io.frame.core.model.EventSource
import io.frame.core.model.EventType
import io.frame.core.model.Mode
import io.frame.core.recording.RecorderState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as FrameApp).container

    /** Set by the Quick Settings tile; the nav host opens Dashcam and starts recording. */
    private val startDashcamRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startDashcamRequest.value = intent?.getBooleanExtra(EXTRA_START_DASHCAM, false) == true
        setContent {
            FrameTheme {
                FrameNavHost(
                    container = container,
                    startDashcamRequested = startDashcamRequest.value,
                    onStartDashcamHandled = { startDashcamRequest.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_DASHCAM, false)) startDashcamRequest.value = true
    }

    /** Volume keys (and cheap Bluetooth selfie remotes, which send volume-up) save the moment. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val state = container.engine.state.value
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            state is RecorderState.Recording
        ) {
            if (event?.repeatCount == 0) {
                val type = if (state.mode == Mode.SPORTS) EventType.HIGHLIGHT_CUSTOM else EventType.MANUAL
                container.scope.launch { container.engine.markEvent(type, EventSource.VOLUME_KEY) }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_START_DASHCAM = "start_dashcam"
    }
}
