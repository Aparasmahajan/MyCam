package io.frame.core.recording

import androidx.room.withTransaction
import io.frame.core.data.Ids
import io.frame.core.data.db.EventEntity
import io.frame.core.data.db.EventSegmentCrossRef
import io.frame.core.data.db.FrameDatabase
import io.frame.core.data.db.SegmentEntity
import io.frame.core.model.EventSource
import io.frame.core.model.EventType
import io.frame.core.model.RetentionPolicy

/**
 * Protection windows (docs/02-smart-loop-engine.md, "Event buffer"). Recording never stops, so
 * footage before an event is already on disk: protecting an event means linking every segment
 * that overlaps [time - before, time + after]. While the window is still in the future the event
 * stays open and each new segment is linked as it starts.
 */
class EventManager(private val db: FrameDatabase) {

    suspend fun create(
        sessionId: String,
        type: EventType,
        source: EventSource,
        time: Long,
        policy: RetentionPolicy,
        peakG: Float? = null,
        location: LocationFix? = null,
        label: String? = null,
    ): String {
        val window = policy.eventWindow(time)
        val event = EventEntity(
            id = Ids.newId(time),
            sessionId = sessionId,
            type = type,
            source = source,
            time = time,
            windowStart = window.start,
            windowEnd = window.end,
            open = true,
            peakG = peakG,
            lat = location?.lat,
            lon = location?.lon,
            speedKmh = location?.speedKmh,
            label = label,
        )
        db.withTransaction {
            db.eventDao().insert(event)
            for (segmentId in db.eventDao().segmentsOverlapping(sessionId, window.start, window.end)) {
                db.eventDao().link(EventSegmentCrossRef(event.id, segmentId))
            }
        }
        return event.id
    }

    /** A new segment is covered by every open event whose window reaches its start. */
    suspend fun onSegmentStarted(segment: SegmentEntity) {
        for (event in db.eventDao().openEvents(segment.sessionId)) {
            if (event.windowEnd >= segment.startedAt) {
                db.eventDao().link(EventSegmentCrossRef(event.id, segment.id))
            }
        }
    }

    /** Events whose window ended inside this segment are now fully covered. */
    suspend fun onSegmentFinished(sessionId: String, endedAt: Long) {
        for (event in db.eventDao().openEvents(sessionId)) {
            if (event.windowEnd <= endedAt) db.eventDao().close(event.id)
        }
    }

    suspend fun closeAll(sessionId: String) = db.eventDao().closeAll(sessionId)

    suspend fun eventsFor(segmentId: String): List<String> = db.eventDao().eventsForSegment(segmentId)
}
