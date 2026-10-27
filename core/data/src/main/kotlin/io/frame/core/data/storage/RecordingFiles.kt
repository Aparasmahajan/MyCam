package io.frame.core.data.storage

import android.content.Context
import android.os.StatFs
import io.frame.core.model.Mode
import io.frame.core.retention.FileDeleter
import io.frame.core.retention.StorageStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File layout (docs/05-camera-library-storage.md):
 * recordings/<mode>/<yyyy-MM-dd>/S_<sessionId>/seg_0001_081500.mp4 (+ .json sidecar)
 *
 * App-specific external storage needs no permission, only FRAME can delete these files,
 * and they are removed on uninstall (the app warns about that).
 */
class RecordingFiles(context: Context) {

    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")

    fun newSessionDir(mode: Mode, sessionId: String, startedAt: Long): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedAt))
        return File(root, "${mode.name.lowercase()}/$day/S_$sessionId").apply { mkdirs() }
    }

    fun segmentFile(sessionDir: File, seq: Int, startedAt: Long): File {
        val time = SimpleDateFormat("HHmmss", Locale.US).format(Date(startedAt))
        return File(sessionDir, "seg_%04d_%s.mp4".format(Locale.US, seq, time))
    }

    fun allVideoFiles(): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "mp4" }.toList()

    companion object {
        fun sidecarFor(video: File): File = File(video.parentFile, video.nameWithoutExtension + ".json")
    }
}

class FsStorageStats(private val files: RecordingFiles) : StorageStats {
    private fun statFs(): StatFs {
        files.root.mkdirs()
        return StatFs(files.root.path)
    }

    override fun freeBytes(): Long = statFs().availableBytes
    override fun totalBytes(): Long = statFs().totalBytes
}

object FsDeleter : FileDeleter {
    override fun delete(path: String): Boolean {
        val video = File(path)
        RecordingFiles.sidecarFor(video).delete()
        video.delete()
        val dir = video.parentFile
        if (dir != null && dir.listFiles().isNullOrEmpty()) dir.delete()
        return !video.exists()
    }
}
