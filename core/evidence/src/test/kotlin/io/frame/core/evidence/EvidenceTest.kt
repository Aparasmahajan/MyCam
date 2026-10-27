package io.frame.core.evidence

import io.frame.core.evidence.Evidence.Link
import io.frame.core.evidence.Evidence.Record
import io.frame.core.evidence.Evidence.Status
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EvidenceTest {

    @TempDir
    lateinit var dir: File

    /** Records a session of [count] segments the way the engine does: hash, then chain. */
    private fun recordSession(count: Int, sessionId: String = "S1"): MutableList<Record> {
        var prev = Evidence.GENESIS
        return (1..count).map { seq ->
            val file = File(dir, "$sessionId-$seq.mp4").apply { writeBytes(ByteArray(10_000) { (it * seq).toByte() }) }
            val link = Link(sessionId, seq, seq * 180_000L, seq * 180_000L + 179_000)
            val fileHash = Evidence.sha256(file)
            val chain = Evidence.chain(prev, fileHash, link)
            Record(link, file.path, fileHash, prev, chain).also { prev = chain }
        }.toMutableList()
    }

    private fun verify(records: List<Record>) =
        Evidence.verify(records) { path -> File(path).takeIf { it.exists() }?.let(Evidence::sha256) }

    @Test
    fun `sha256 matches the known value`() {
        val file = File(dir, "abc.txt").apply { writeText("abc") }
        Evidence.sha256(file) shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `an untouched session verifies`() {
        val report = verify(recordSession(5))
        report.intact shouldBe true
        report.verifiedCount shouldBe 5
    }

    @Test
    fun `editing one byte of a file is detected`() {
        val records = recordSession(4)
        File(records[2].path).appendBytes(byteArrayOf(1))
        val report = verify(records)
        report.intact shouldBe false
        report.tampered.map { it.seq to it.status } shouldBe listOf(3 to Status.FILE_CHANGED)
    }

    @Test
    fun `segments deleted by retention leave a verifiable chain`() {
        val records = recordSession(5)
        records[1] = records[1].copy(deleted = true)
        File(records[1].path).delete()
        val report = verify(records)
        report.intact shouldBe true
        report.results.map { it.status } shouldBe listOf(Status.OK, Status.DELETED, Status.OK, Status.OK, Status.OK)
    }

    @Test
    fun `a forged segment that replaces a real one breaks the chain`() {
        val records = recordSession(3)
        val forged = File(dir, "forged.mp4").apply { writeBytes(ByteArray(10_000) { 7 }) }
        val forgedHash = Evidence.sha256(forged)
        // the forger can rehash the file but doesn't know how to make the next link match
        records[1] = records[1].copy(path = forged.path, fileHash = forgedHash,
            chainHash = Evidence.chain(records[1].prevChainHash!!, forgedHash, records[1].link))
        val report = verify(records)
        report.tampered.map { it.seq to it.status } shouldBe listOf(3 to Status.CHAIN_BROKEN)
    }

    @Test
    fun `a segment moved to another session breaks its chain`() {
        val records = recordSession(2)
        records[1] = records[1].copy(link = records[1].link.copy(sessionId = "OTHER"))
        verify(records).tampered.single().status shouldBe Status.CHAIN_BROKEN
    }

    @Test
    fun `a missing file that should be there is reported`() {
        val records = recordSession(2)
        File(records[0].path).delete()
        val report = verify(records)
        report.intact shouldBe false
        report.results.first().status shouldBe Status.MISSING
    }
}
