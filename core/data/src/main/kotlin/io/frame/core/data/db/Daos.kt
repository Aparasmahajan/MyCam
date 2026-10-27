package io.frame.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.frame.core.model.SegmentState
import io.frame.core.model.StopReason
import kotlinx.coroutines.flow.Flow

/** "Protected" = manual flag or linked to an event. Shared by several queries below. */
private const val IS_PROTECTED =
    "(s.protectedManually = 1 OR EXISTS (SELECT 1 FROM event_segment es WHERE es.segmentId = s.id))"

private const val LIVE = "s.state NOT IN ('DELETED','DELETING')"

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity)

    @Query("UPDATE recording_session SET endedAt = :endedAt, endReason = :reason WHERE id = :id")
    suspend fun end(id: String, endedAt: Long, reason: StopReason)

    @Query("SELECT * FROM recording_session WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM recording_session WHERE endedAt IS NULL")
    suspend fun unfinished(): List<SessionEntity>

    @Query(
        """
        SELECT r.*,
          (SELECT COUNT(*) FROM segment s WHERE s.sessionId = r.id AND $LIVE) AS segmentCount,
          (SELECT COALESCE(SUM(s.sizeBytes), 0) FROM segment s WHERE s.sessionId = r.id AND $LIVE) AS totalBytes,
          (SELECT COUNT(*) FROM event e WHERE e.sessionId = r.id) AS eventCount
        FROM recording_session r
        WHERE (:mode IS NULL OR r.mode = :mode)
        ORDER BY r.startedAt DESC
        """,
    )
    fun observeSummaries(mode: String?): Flow<List<SessionSummary>>

    @Query("SELECT * FROM recording_session WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("DELETE FROM recording_session WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SegmentDao {
    @Insert
    suspend fun insert(segment: SegmentEntity)

    @Update
    suspend fun update(segment: SegmentEntity)

    @Query("SELECT * FROM segment WHERE id = :id")
    suspend fun get(id: String): SegmentEntity?

    @Query("SELECT * FROM segment WHERE filePath = :path LIMIT 1")
    suspend fun byPath(path: String): SegmentEntity?

    @Query(
        "UPDATE segment SET state = :state, endedAt = :endedAt, durationMs = :durationMs, sizeBytes = :sizeBytes " +
            "WHERE id = :id",
    )
    suspend fun finish(id: String, state: SegmentState, endedAt: Long, durationMs: Long?, sizeBytes: Long)

    @Query("UPDATE segment SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: SegmentState)

    @Query("UPDATE segment SET sha256 = :sha256, prevChainHash = :prevChainHash, chainHash = :chainHash WHERE id = :id")
    suspend fun setHashes(id: String, sha256: String, prevChainHash: String, chainHash: String)

    /** The chain hash to link the next segment to, including segments whose files were since deleted. */
    @Query(
        "SELECT chainHash FROM segment WHERE sessionId = :sessionId AND seq < :seq AND chainHash IS NOT NULL " +
            "ORDER BY seq DESC LIMIT 1",
    )
    suspend fun previousChainHash(sessionId: String, seq: Int): String?

    /** Every row of a session, deleted ones included (their hashes keep the chain verifiable). */
    @Query("SELECT * FROM segment WHERE sessionId = :sessionId ORDER BY seq")
    suspend fun allForSession(sessionId: String): List<SegmentEntity>

    @Query("UPDATE segment SET protectedManually = :value WHERE id = :id")
    suspend fun setProtectedManually(id: String, value: Boolean)

    @Query("UPDATE segment SET favorite = :value WHERE id = :id")
    suspend fun setFavorite(id: String, value: Boolean)

    @Query("SELECT * FROM segment WHERE state IN ('WRITING','FINALIZING','DELETING')")
    suspend fun interrupted(): List<SegmentEntity>

    @Query("SELECT * FROM segment s WHERE s.sessionId = :sessionId AND $LIVE ORDER BY s.seq")
    suspend fun liveForSession(sessionId: String): List<SegmentEntity>

    /** Playable protected segments, oldest first. [sessionId] null = all sessions. */
    @Query(
        "SELECT s.* FROM segment s WHERE s.state = 'COMPLETE' AND $IS_PROTECTED " +
            "AND (:sessionId IS NULL OR s.sessionId = :sessionId) ORDER BY s.startedAt",
    )
    suspend fun protectedSegments(sessionId: String?): List<SegmentEntity>

    @Query("SELECT s.*, $IS_PROTECTED AS isProtected FROM segment s WHERE s.sessionId = :sessionId AND $LIVE ORDER BY s.seq")
    fun observeForSession(sessionId: String): Flow<List<SegmentWithProtection>>

    @Query(
        """
        SELECT s.id, s.mode, s.filePath,
               COALESCE(s.endedAt, s.startedAt) AS endedAt,
               COALESCE(s.sizeBytes, 0) AS sizeBytes
        FROM segment s
        WHERE s.state IN ('COMPLETE','CORRUPT')
          AND NOT $IS_PROTECTED
          AND (:mode IS NULL OR s.mode = :mode)
          AND COALESCE(s.endedAt, s.startedAt) < :endedBefore
        ORDER BY COALESCE(s.endedAt, s.startedAt) ASC
        LIMIT :limit
        """,
    )
    suspend fun oldestEligible(mode: String?, endedBefore: Long, limit: Int): List<EligibleSegmentRow>

    /** Moves an eligible segment to DELETING in one statement; returns 0 if it is no longer eligible. */
    @Query(
        """
        UPDATE segment SET state = 'DELETING'
        WHERE id = :id
          AND state IN ('COMPLETE','CORRUPT')
          AND protectedManually = 0
          AND NOT EXISTS (SELECT 1 FROM event_segment es WHERE es.segmentId = segment.id)
        """,
    )
    suspend fun beginDelete(id: String): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segment WHERE mode = :mode AND state != 'DELETED'")
    suspend fun bytesUsed(mode: String): Long

    @Query(
        "SELECT s.mode AS mode, COALESCE(SUM(s.sizeBytes), 0) AS bytes, COUNT(*) AS count " +
            "FROM segment s WHERE $LIVE GROUP BY s.mode",
    )
    fun observeUsage(): Flow<List<ModeUsage>>

    @Query("SELECT COALESCE(SUM(s.sizeBytes), 0) FROM segment s WHERE $LIVE AND $IS_PROTECTED")
    fun observeProtectedBytes(): Flow<Long>
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: EventSegmentCrossRef)

    @Query("SELECT * FROM event WHERE sessionId = :sessionId AND open = 1")
    suspend fun openEvents(sessionId: String): List<EventEntity>

    @Query("UPDATE event SET open = 0 WHERE id = :id")
    suspend fun close(id: String)

    @Query("UPDATE event SET open = 0 WHERE sessionId = :sessionId")
    suspend fun closeAll(sessionId: String)

    @Query(
        "SELECT id FROM segment WHERE sessionId = :sessionId AND state NOT IN ('DELETED','DELETING') " +
            "AND startedAt <= :end AND (endedAt IS NULL OR endedAt >= :start)",
    )
    suspend fun segmentsOverlapping(sessionId: String, start: Long, end: Long): List<String>

    @Query("SELECT * FROM event WHERE sessionId = :sessionId ORDER BY time")
    fun observeForSession(sessionId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM event WHERE sessionId = :sessionId ORDER BY time")
    suspend fun listForSession(sessionId: String): List<EventEntity>

    @Query("SELECT eventId FROM event_segment WHERE segmentId = :segmentId")
    suspend fun eventsForSegment(segmentId: String): List<String>

    @Query("DELETE FROM event_segment WHERE segmentId = :segmentId")
    suspend fun unlinkSegment(segmentId: String)
}

@Dao
interface MatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(match: MatchEntity)

    @Query("SELECT * FROM `match` WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): MatchEntity?

    @Query("SELECT * FROM `match` WHERE sessionId = :sessionId")
    fun observe(sessionId: String): Flow<MatchEntity?>

    @Insert
    suspend fun insertScore(change: ScoreChangeEntity)
}

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insert(point: TelemetryPointEntity)

    @Query("SELECT * FROM telemetry_point WHERE sessionId = :sessionId AND time BETWEEN :from AND :to ORDER BY time")
    suspend fun range(sessionId: String, from: Long, to: Long): List<TelemetryPointEntity>
}

@Dao
interface LogDao {
    @Insert
    suspend fun insertCleanup(entry: CleanupLogEntity)

    @Query("SELECT * FROM cleanup_log ORDER BY time DESC LIMIT :limit")
    fun observeCleanups(limit: Int = 200): Flow<List<CleanupLogEntity>>

    @Query("DELETE FROM cleanup_log WHERE time < :before")
    suspend fun pruneCleanups(before: Long)

    @Insert
    suspend fun insertRecording(entry: RecordingLogEntity)

    @Query("SELECT * FROM recording_log WHERE sessionId = :sessionId ORDER BY time")
    fun observeRecording(sessionId: String): Flow<List<RecordingLogEntity>>
}
