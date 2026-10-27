package io.frame.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Exports go to shared folders through MediaStore (no storage permission on Android 10+):
 * videos to Movies/FRAME, reports to Download/FRAME. Recordings themselves stay in app storage.
 */
object Gallery {
    private const val FOLDER = "FRAME"
    const val VIDEO_MIME = "video/mp4"
    const val PDF_MIME = "application/pdf"
    val VIDEO_FOLDER = "${Environment.DIRECTORY_MOVIES}/$FOLDER"
    val DOCUMENT_FOLDER = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"

    fun save(context: Context, source: File, displayName: String): Uri =
        write(context, displayName, VIDEO_MIME) { out -> source.inputStream().use { it.copyTo(out) } }

    fun write(context: Context, displayName: String, mime: String, body: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val isVideo = mime == VIDEO_MIME
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) VIDEO_FOLDER else DOCUMENT_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create $displayName")
        try {
            val out = resolver.openOutputStream(uri) ?: error("Could not write $displayName")
            out.use(body)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun share(context: Context, uris: List<Uri>, mime: String = VIDEO_MIME) {
        if (uris.isEmpty()) return
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
