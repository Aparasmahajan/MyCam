package io.frame.core.recording

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import timber.log.Timber

/**
 * Burns text (time, speed, score) into the video and the preview with CameraX's OverlayEffect.
 * The text becomes part of the recorded frames, so it can't be removed later — what you want for
 * dashcam evidence. [lines] is called once per frame on the overlay thread and must be cheap.
 */
class VideoOverlay(private val lines: () -> List<String>) {

    private val thread = HandlerThread("frame-overlay").apply { start() }

    val effect: OverlayEffect = OverlayEffect(
        CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
        0,
        Handler(thread.looper),
    ) { error -> Timber.e(error, "Overlay effect failed") }.apply {
        setOnDrawListener { frame ->
            draw(frame)
            true
        }
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val boxPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val matrix = Matrix()
    private val bounds = RectF()

    private fun draw(frame: Frame) {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val text = runCatching(lines).getOrDefault(emptyList())
        if (text.isEmpty()) return

        // The buffer is in sensor orientation; draw in upright ("display") coordinates instead.
        val bufferW = frame.size.width.toFloat()
        val bufferH = frame.size.height.toFloat()
        val rotation = frame.rotationDegrees
        val displayW = if (rotation % 180 == 0) bufferW else bufferH
        val displayH = if (rotation % 180 == 0) bufferH else bufferW
        matrix.setRotate(-rotation.toFloat())
        bounds.set(0f, 0f, displayW, displayH)
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)

        canvas.save()
        canvas.concat(matrix)
        drawBox(canvas, text, displayH)
        canvas.restore()
    }

    /** Bottom-left caption box, text sized relative to the frame so 720p and 4K look alike. */
    private fun drawBox(canvas: Canvas, text: List<String>, displayH: Float) {
        textPaint.textSize = displayH * TEXT_SCALE
        val pad = textPaint.textSize * 0.45f
        val lineHeight = textPaint.textSize * 1.25f
        val boxW = text.maxOf { textPaint.measureText(it) } + pad * 2
        val boxH = lineHeight * text.size + pad
        val left = pad * 2
        val top = displayH - boxH - pad * 2
        canvas.drawRect(left, top, left + boxW, top + boxH, boxPaint)
        text.forEachIndexed { i, line ->
            canvas.drawText(line, left + pad, top + pad / 2 + lineHeight * (i + 1) - textPaint.descent(), textPaint)
        }
    }

    fun release() {
        effect.close()
        thread.quitSafely()
    }

    private companion object {
        const val TEXT_SCALE = 0.032f
    }
}
