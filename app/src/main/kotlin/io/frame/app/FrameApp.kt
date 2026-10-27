package io.frame.app

import android.app.Application
import io.frame.app.export.ClipExporter
import io.frame.app.sports.SportsRepository
import io.frame.app.work.CleanupWorker
import io.frame.core.data.LibraryRepository
import io.frame.core.data.RoomSegmentStore
import io.frame.core.data.db.FrameDatabase
import io.frame.core.data.settings.SettingsRepository
import io.frame.core.data.storage.FsDeleter
import io.frame.core.data.storage.FsStorageStats
import io.frame.core.data.storage.RecordingFiles
import io.frame.core.data.storage.RecoveryReport
import io.frame.core.data.storage.RecoveryScanner
import io.frame.core.recording.RecordingEngine
import io.frame.core.recording.RecordingEngineHost
import io.frame.core.retention.CleanupTrigger
import io.frame.core.retention.Clock
import io.frame.core.retention.RetentionEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class FrameApp : Application(), RecordingEngineHost {

    lateinit var container: AppContainer
        private set

    override val recordingEngine: RecordingEngine get() = container.engine

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        container = AppContainer(this)
        container.startup()
        CleanupWorker.schedule(this)
    }
}

/** Manual dependency wiring for the POC (Hilt can replace it once the module count grows). */
class AppContainer(app: Application) {
    /** Outlives screens: recording, stop and cleanup calls must not be cancelled by navigation. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock: Clock = Clock.System
    val db = FrameDatabase.create(app)
    val files = RecordingFiles(app)
    val settings = SettingsRepository(app)
    val storageStats = FsStorageStats(files)
    val retention = RetentionEngine(RoomSegmentStore(db, clock), storageStats, FsDeleter, clock) {
        settings.current().retention
    }
    val library = LibraryRepository(db, clock)
    val sports = SportsRepository(db, clock)
    val exporter = ClipExporter(app, library, scope)

    private val recovery = RecoveryScanner(db, files, clock)
    val recoveryGate = CompletableDeferred<RecoveryReport?>()

    private val _lastRecovery = MutableStateFlow<RecoveryReport?>(null)
    /** Set when the previous session ended in a crash, so Home can offer to resume. */
    val lastRecovery: StateFlow<RecoveryReport?> = _lastRecovery.asStateFlow()

    val engine = RecordingEngine(
        context = app,
        db = db,
        files = files,
        settings = settings,
        retention = retention,
        stats = storageStats,
        clock = clock,
        recoveryGate = recoveryGate,
        scope = scope,
    )

    fun startup() {
        scope.launch(Dispatchers.IO) {
            val report = runCatching { recovery.recover() }
                .onFailure { Timber.e(it, "Recovery failed") }
                .getOrNull()
            recoveryGate.complete(report)
            if (report != null && report.crashedSessions.isNotEmpty()) _lastRecovery.value = report
            runCatching { retention.runCleanup(CleanupTrigger.APP_START) }.onFailure { Timber.e(it, "Startup cleanup failed") }
            runCatching { db.logDao().pruneCleanups(clock.nowMillis() - CLEANUP_HISTORY_MS) }
        }
    }

    fun dismissRecovery() {
        _lastRecovery.value = null
    }

    private companion object {
        const val CLEANUP_HISTORY_MS = 30L * 24 * 60 * 60 * 1000
    }
}
