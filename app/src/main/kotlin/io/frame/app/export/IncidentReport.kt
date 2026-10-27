package io.frame.app.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import io.frame.app.ui.components.label
import io.frame.core.data.LibraryRepository
import io.frame.core.data.db.EventEntity
import io.frame.core.data.db.TelemetryPointEntity
import io.frame.core.evidence.Evidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One-page (or more) PDF for an incident: when and where, speed and g-force, and the SHA-256 /
 * chain hash of every file that covers it, with the result of re-checking them now.
 */
class IncidentReport(private val context: Context, private val library: LibraryRepository) {

    suspend fun write(event: EventEntity): Uri = withContext(Dispatchers.IO) {
        val telemetry = library.telemetry(event.sessionId, event.windowStart - CHART_MARGIN_MS, event.windowEnd + CHART_MARGIN_MS)
        val verification = library.verifySession(event.sessionId)
        val statusBySeq = verification.results.associate { it.seq to it.status }
        val footage = library.playableSegments(event.sessionId)
            .filter { it.startedAt <= event.windowEnd && (it.endedAt ?: Long.MAX_VALUE) >= event.windowStart }

        val pdf = PdfDocument()
        try {
            val w = Writer(pdf)
            w.title("Incident report")
            w.muted("FRAME · generated ${localTime.format(Date())}")
            w.gap(10f)

            w.heading("Event")
            w.row("Type", event.type.label() + if (event.source.name == "G_SENSOR") " (detected by G-sensor)" else " (saved by driver)")
            w.row("Local time", localTime.format(Date(event.time)))
            w.row("UTC", utcTime.format(Date(event.time)))
            if (event.lat != null && event.lon != null) {
                w.row("Location", "%.6f, %.6f".format(Locale.US, event.lat, event.lon))
                w.row("Map", "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, event.lat, event.lon))
            } else {
                w.row("Location", "No GPS fix at the time")
            }
            w.row("Speed", event.speedKmh?.let { "%.0f km/h".format(Locale.US, it) } ?: "Unknown")
            event.peakG?.let { w.row("Peak force", "%.2f g".format(Locale.US, it)) }
            w.row(
                "Footage kept",
                "${(event.time - event.windowStart) / 1000} s before to ${(event.windowEnd - event.time) / 1000} s after",
            )

            val speeds = telemetry.filter { it.speedKmh != null }
            if (speeds.size >= 2) {
                w.gap(8f)
                w.heading("Speed around the event")
                w.speedChart(speeds, event.time, event.windowStart - CHART_MARGIN_MS, event.windowEnd + CHART_MARGIN_MS)
            }

            w.gap(8f)
            w.heading("Footage")
            if (footage.isEmpty()) w.text("The video for this event is no longer on the device.")
            for (seg in footage) {
                val status = statusBySeq[seg.seq]
                w.text("Segment ${seg.seq}  ${clock.format(Date(seg.startedAt))} – ${seg.endedAt?.let { clock.format(Date(it)) } ?: "?"}  ${File(seg.filePath).name}", bold = true)
                w.mono("SHA-256  ${seg.sha256 ?: "not hashed"}")
                w.mono("Chain    ${seg.chainHash ?: "not hashed"}")
                w.text("Check now: ${statusText(status)}", color = if (status == Evidence.Status.OK) GREEN else RED)
                w.gap(4f)
            }

            w.gap(8f)
            w.heading("Integrity of the whole recording")
            if (verification.intact) {
                w.text("${verification.verifiedCount} segment(s) re-hashed: unchanged since they were recorded.", color = GREEN)
            } else {
                w.text("WARNING: ${verification.tampered.size} segment(s) changed or out of order; ${verification.results.count { it.status == Evidence.Status.MISSING }} missing.", color = RED)
            }
            w.gap(10f)
            w.muted("How to check an exported clip: export the whole segment (not a trim, which is re-encoded) and compare its SHA-256,")
            w.muted("e.g. 'sha256sum file.mp4' or 'certutil -hashfile file.mp4 SHA256', with the value above.")
            w.finish()

            val name = "FRAME_incident_${fileTime.format(Date(event.time))}.pdf"
            Gallery.write(context, name, Gallery.PDF_MIME) { out -> pdf.writeTo(out) }
        } finally {
            pdf.close()
        }
    }

    private fun statusText(status: Evidence.Status?) = when (status) {
        Evidence.Status.OK -> "unchanged since recording"
        Evidence.Status.FILE_CHANGED -> "FILE HAS BEEN CHANGED"
        Evidence.Status.CHAIN_BROKEN -> "OUT OF ORDER OR REPLACED"
        Evidence.Status.MISSING -> "file missing"
        Evidence.Status.DELETED -> "deleted"
        Evidence.Status.UNHASHED, null -> "not hashed (recording was interrupted)"
    }

    /** A4 page writer with a cursor that starts a new page when one fills up. */
    private class Writer(private val pdf: PdfDocument) {
        private var pageNo = 0
        private var page: PdfDocument.Page = newPage()
        private val canvas: Canvas get() = page.canvas
        private var y = TOP

        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }
        private val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        private val mono = Paint(body).apply { typeface = Typeface.MONOSPACE; textSize = 7.5f }
        private val muted = Paint(body).apply { textSize = 8f; color = Color.DKGRAY }
        private val titlePaint = Paint(bold).apply { textSize = 20f }
        private val headingPaint = Paint(bold).apply { textSize = 12f }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f; style = Paint.Style.STROKE; color = Color.rgb(30, 90, 200) }
        private val grid = Paint().apply { strokeWidth = 0.5f; style = Paint.Style.STROKE; color = Color.LTGRAY }
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1f
            style = Paint.Style.STROKE
            color = RED
            pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
        }

        private fun newPage(): PdfDocument.Page {
            pageNo++
            return pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        }

        private fun ensure(height: Float) {
            if (y + height <= PAGE_H - BOTTOM) return
            pdf.finishPage(page)
            page = newPage()
            y = TOP
        }

        fun title(text: String) = draw(text, titlePaint, 26f)
        fun heading(text: String) = draw(text, headingPaint, 18f)
        fun muted(text: String) = draw(text, muted, 11f)
        fun mono(text: String) = draw(text, mono, 10f)
        fun gap(h: Float) {
            y += h
        }

        fun text(text: String, bold: Boolean = false, color: Int = Color.BLACK) {
            val paint = Paint(if (bold) this.bold else body).apply { this.color = color }
            draw(text, paint, 14f)
        }

        fun row(key: String, value: String) {
            ensure(14f)
            y += 14f
            canvas.drawText(key, LEFT, y, bold)
            canvas.drawText(value, LEFT + 90f, y, body)
        }

        private fun draw(text: String, paint: Paint, lineHeight: Float) {
            ensure(lineHeight)
            y += lineHeight
            canvas.drawText(text, LEFT, y, paint)
        }

        fun speedChart(points: List<TelemetryPointEntity>, eventTime: Long, from: Long, to: Long) {
            val h = 120f
            val w = PAGE_W - LEFT * 2 - 30f
            ensure(h + 30f)
            val top = y + 8f
            val left = LEFT + 30f
            val maxSpeed = maxOf(20f, points.maxOf { it.speedKmh ?: 0f } * 1.1f)
            fun x(t: Long) = left + (t - from).toFloat() / (to - from) * w
            fun yOf(v: Float) = top + h - v / maxSpeed * h

            canvas.drawRect(left, top, left + w, top + h, grid)
            canvas.drawText("%.0f km/h".format(Locale.US, maxSpeed), LEFT - 4f, top + 8f, muted)
            canvas.drawText("0", LEFT + 18f, top + h, muted)
            canvas.drawText("-${(eventTime - from) / 1000} s", left, top + h + 11f, muted)
            canvas.drawText("+${(to - eventTime) / 1000} s", left + w - 30f, top + h + 11f, muted)

            val path = Path()
            points.forEachIndexed { i, p ->
                val px = x(p.time)
                val py = yOf(p.speedKmh ?: 0f)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, line)
            canvas.drawLine(x(eventTime), top, x(eventTime), top + h, marker)
            canvas.drawText("event", x(eventTime) + 3f, top + 10f, muted)
            y = top + h + 16f
        }

        fun finish() = pdf.finishPage(page)

        companion object {
            const val PAGE_W = 595 // A4 in points
            const val PAGE_H = 842
            const val LEFT = 40f
            const val TOP = 40f
            const val BOTTOM = 40f
        }
    }

    private companion object {
        const val CHART_MARGIN_MS = 30_000L
        val GREEN = Color.rgb(0, 128, 60)
        val RED = Color.rgb(200, 30, 40)
        val localTime = SimpleDateFormat("EEE d MMM yyyy, HH:mm:ss z", Locale.getDefault())
        val utcTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val fileTime = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
