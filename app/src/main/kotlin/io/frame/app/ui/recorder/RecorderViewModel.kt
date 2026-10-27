package io.frame.app.ui.recorder

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.frame.app.AppContainer
import io.frame.app.sports.SportPreset
import io.frame.core.data.db.MatchEntity
import io.frame.core.data.settings.FrameSettings
import io.frame.core.model.EventSource
import io.frame.core.model.EventType
import io.frame.core.model.Mode
import io.frame.core.recording.RecorderState
import io.frame.core.recording.RecordingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecorderViewModel(private val container: AppContainer, val mode: Mode) : ViewModel() {

    private val engine = container.engine
    val state = engine.state
    val health = engine.health
    val location = engine.location

    val settings: StateFlow<FrameSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrameSettings())

    // Sports match setup, entered before the match starts
    var sport by mutableStateOf(SportPreset.FOOTBALL)
    var teamA by mutableStateOf("Home")
    var teamB by mutableStateOf("Away")

    @OptIn(ExperimentalCoroutinesApi::class)
    val match: StateFlow<MatchEntity?> = state
        .map { (it as? RecorderState.Recording)?.sessionId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else container.sports.match(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        if (mode == Mode.SPORTS) {
            viewModelScope.launch {
                state.collect { st ->
                    if (st is RecorderState.Recording && st.mode == Mode.SPORTS && container.sports.get(st.sessionId) == null) {
                        container.sports.createMatch(st.sessionId, sport, teamA, teamB)
                    }
                }
            }
            // Keep the burned-in scoreboard in step with the score.
            viewModelScope.launch {
                match.collect { m ->
                    engine.setOverlayCaption(m?.let { "${it.teamA} ${it.scoreA} - ${it.scoreB} ${it.teamB} · P${it.period}" })
                }
            }
        }
    }

    fun start(context: Context) {
        val title = if (mode == Mode.SPORTS) "${teamA.ifBlank { "Home" }} vs ${teamB.ifBlank { "Away" }}" else null
        RecordingService.start(context, mode, title)
    }

    // Engine calls run in the app scope so leaving the screen never cancels a stop or a save.
    fun stop() {
        container.scope.launch { engine.stop() }
    }

    fun mark() {
        val type = if (mode == Mode.SPORTS) EventType.HIGHLIGHT_CUSTOM else EventType.MANUAL
        container.scope.launch { engine.markEvent(type, EventSource.SCREEN) }
    }

    fun changeScore(teamA: Boolean, delta: Int) {
        val st = state.value as? RecorderState.Recording ?: return
        container.scope.launch {
            val updated = container.sports.changeScore(st.sessionId, teamA, delta) ?: return@launch
            if (delta > 0 && settings.value.sports.autoHighlightOnScore) {
                engine.markEvent(
                    EventType.HIGHLIGHT_POINT,
                    EventSource.SCORE_CHANGE,
                    label = "${updated.teamA} ${updated.scoreA} - ${updated.scoreB} ${updated.teamB}",
                )
            }
        }
    }

    fun nextPeriod() {
        val st = state.value as? RecorderState.Recording ?: return
        container.scope.launch { container.sports.nextPeriod(st.sessionId) }
    }

    fun acknowledge() = engine.acknowledge()
}
