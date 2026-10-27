package io.frame.app.sports

import io.frame.core.data.db.FrameDatabase
import io.frame.core.data.db.MatchEntity
import io.frame.core.data.db.ScoreChangeEntity
import io.frame.core.retention.Clock
import kotlinx.coroutines.flow.Flow

enum class SportPreset(val label: String) {
    FOOTBALL("Football"),
    CRICKET("Cricket"),
    BADMINTON("Badminton"),
    BASKETBALL("Basketball"),
    OTHER("Other"),
}

/** Match metadata and a timestamped score history for a sports session. */
class SportsRepository(
    private val db: FrameDatabase,
    private val clock: Clock,
) {
    fun match(sessionId: String): Flow<MatchEntity?> = db.matchDao().observe(sessionId)

    suspend fun get(sessionId: String): MatchEntity? = db.matchDao().get(sessionId)

    suspend fun createMatch(sessionId: String, sport: SportPreset, teamA: String, teamB: String) {
        db.matchDao().upsert(MatchEntity(sessionId, sport.label, teamA.ifBlank { "Home" }, teamB.ifBlank { "Away" }))
    }

    suspend fun changeScore(sessionId: String, teamA: Boolean, delta: Int): MatchEntity? {
        val match = db.matchDao().get(sessionId) ?: return null
        val updated = if (teamA) {
            match.copy(scoreA = (match.scoreA + delta).coerceAtLeast(0))
        } else {
            match.copy(scoreB = (match.scoreB + delta).coerceAtLeast(0))
        }
        if (updated == match) return match
        db.matchDao().upsert(updated)
        db.matchDao().insertScore(
            ScoreChangeEntity(
                sessionId = sessionId,
                time = clock.nowMillis(),
                scoreA = updated.scoreA,
                scoreB = updated.scoreB,
                period = updated.period,
            ),
        )
        return updated
    }

    suspend fun nextPeriod(sessionId: String) {
        val match = db.matchDao().get(sessionId) ?: return
        db.matchDao().upsert(match.copy(period = match.period + 1))
    }
}
