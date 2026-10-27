package io.frame.core.data.storage

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal MP4 box walker. A plain MP4 that was cut off (process killed, power loss) has no
 * top-level 'moov' box and cannot be played; that is how recovery tells COMPLETE from CORRUPT.
 */
object Mp4Inspector {

    fun hasMoov(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var pos = 0L
            while (pos + 8 <= length) {
                raf.seek(pos)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }.decodeToString()
                if (type == "moov") return@use true
                size = when (size) {
                    0L -> return@use false // box runs to end of file
                    1L -> if (pos + 16 <= length) raf.readLong() else return@use false
                    else -> size
                }
                if (size < 8) return@use false
                pos += size
            }
            false
        }
    }.getOrDefault(false)
}
