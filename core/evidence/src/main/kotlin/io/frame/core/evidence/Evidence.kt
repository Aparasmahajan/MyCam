package io.frame.core.evidence

import java.io.File
import java.security.MessageDigest

/**
 * Tamper evidence for recordings (docs/06-extra-features.md).
 *
 * Every finished segment gets the SHA-256 of its file, and a chain hash that also covers the
 * previous segment's chain hash and the segment's identity (session, position, times):
 *
 *   chain(n) = SHA-256(chain(n-1) | fileHash(n) | sessionId | seq | startedAt | endedAt)
 *
 * Changing a file, reordering segments, moving one to another session, or inserting a forged
 * segment all break the chain. Retention may legally delete old segments: their rows keep their
 * hashes, so the chain still links across the gap even though the file is gone.
 */
object Evidence {
    val GENESIS: String = "0".repeat(64)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun chain(previous: String, fileHash: String, link: Link): String {
        val material = listOf(previous, fileHash, link.sessionId, link.seq, link.startedAt, link.endedAt).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).toHex()
    }

    /** Checks every segment of one session, oldest first. [hashFile] returns null if the file is gone. */
    fun verify(records: List<Record>, hashFile: (path: String) -> String?): Report {
        val results = mutableListOf<Result>()
        var previousChain: String? = null
        for (r in records.sortedBy { it.link.seq }) {
            if (r.fileHash == null || r.chainHash == null || r.prevChainHash == null) {
                results += Result(r.link.seq, Status.UNHASHED)
                continue
            }
            val linked = r.prevChainHash == (previousChain ?: GENESIS) ||
                // the first surviving segment may follow segments that were never hashed
                (previousChain == null && r.link.seq > 1)
            val chainOk = r.chainHash == chain(r.prevChainHash, r.fileHash, r.link)
            previousChain = r.chainHash
            val status = when {
                !chainOk || !linked -> Status.CHAIN_BROKEN
                r.deleted -> Status.DELETED
                else -> when (hashFile(r.path)) {
                    null -> Status.MISSING
                    r.fileHash -> Status.OK
                    else -> Status.FILE_CHANGED
                }
            }
            results += Result(r.link.seq, status)
        }
        return Report(results)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val BUFFER = 1 shl 20

    data class Link(val sessionId: String, val seq: Int, val startedAt: Long, val endedAt: Long)

    data class Record(
        val link: Link,
        val path: String,
        val fileHash: String?,
        val prevChainHash: String?,
        val chainHash: String?,
        /** Deleted by retention or the user: hashes are kept, the file is not. */
        val deleted: Boolean = false,
    )

    enum class Status { OK, DELETED, UNHASHED, MISSING, FILE_CHANGED, CHAIN_BROKEN }

    data class Result(val seq: Int, val status: Status)

    data class Report(val results: List<Result>) {
        val tampered: List<Result> get() = results.filter { it.status == Status.FILE_CHANGED || it.status == Status.CHAIN_BROKEN }
        val intact: Boolean get() = tampered.isEmpty() && results.none { it.status == Status.MISSING }
        val verifiedCount: Int get() = results.count { it.status == Status.OK }
    }
}
