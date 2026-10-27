package io.frame.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * An index over the files on disk. The files plus their JSON sidecars are the source of truth,
 * so this database can be rebuilt by [io.frame.core.data.storage.RecoveryScanner].
 */
@Database(
    entities = [
        SessionEntity::class,
        SegmentEntity::class,
        EventEntity::class,
        EventSegmentCrossRef::class,
        MatchEntity::class,
        ScoreChangeEntity::class,
        TelemetryPointEntity::class,
        CleanupLogEntity::class,
        RecordingLogEntity::class,
    ],
    // Still version 1: no build has shipped, so the schema is edited in place. Bump + migrate from the first release on.
    version = 1,
    exportSchema = true,
)
abstract class FrameDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun segmentDao(): SegmentDao
    abstract fun eventDao(): EventDao
    abstract fun matchDao(): MatchDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun logDao(): LogDao

    companion object {
        fun create(context: Context): FrameDatabase =
            Room.databaseBuilder(context, FrameDatabase::class.java, "frame.db").build()
    }
}
