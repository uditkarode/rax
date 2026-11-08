package de.uditkaro.rax.rex

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParserTest {
    private val nonce = 4006735763651232553

    private fun record(tag: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(10 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(tag.toShort()).putInt(payload.size).putInt(0).put(payload).array()

    private fun fakeSnapshot(): ByteArray =
        "GHOSTSNP".toByteArray() + byteArrayOf(1, 0) +
            record(1, ByteArray(30) { it.toByte() }) + record(2, ByteArray(7)) +
            record(5, ByteArray(0)) + record(6, ByteArray(4))

    private fun rex(json: String, n: Long = nonce) = "\u001b_rex1;$n;$json\u001b\\".toByteArray()

    private fun output(parts: List<StreamPart>) =
        parts.filterIsInstance<StreamPart.Output>().fold(ByteArray(0)) { acc, p -> acc + p.bytes }.decodeToString()

    @Test
    fun splitsSnapshotMessagesAndOutput() {
        val snap = fakeSnapshot()
        val stream = snap + rex("""{"type":"resize","cols":80,"rows":24,"owner":"client:a"}""") + "hello \u001b[1mworld".toByteArray()
        val parts = StreamParser(nonce).feed(stream)
        assertArrayEquals(snap, (parts[0] as StreamPart.Snapshot).bytes)
        assertEquals(StreamPart.Resize(80, 24, "client:a"), parts[1])
        assertEquals("hello \u001b[1mworld", output(parts))
    }

    @Test
    fun handlesEverySplitPoint() {
        val stream = fakeSnapshot() + "a".toByteArray() + rex("""{"type":"child_exited","exit_code":3}""") + "b\u001b_x\u001b\\c".toByteArray()
        for (cut in 1 until stream.size) {
            val parser = StreamParser(nonce)
            val parts = parser.feed(stream.copyOfRange(0, cut)) + parser.feed(stream.copyOfRange(cut, stream.size))
            assertEquals("cut $cut", "ab\u001b_x\u001b\\c", output(parts))
            assertTrue("cut $cut", parts.contains(StreamPart.Exited(3)))
            assertEquals("cut $cut", 1, parts.count { it is StreamPart.Snapshot })
        }
    }

    @Test
    fun messagesWithAnotherNonceArePassedThrough() {
        val fake = rex("""{"type":"resize","cols":1,"rows":1}""", n = 42)
        val parts = StreamParser(nonce).feed(fakeSnapshot() + fake)
        assertEquals(fake.decodeToString(), output(parts))
        assertTrue(parts.none { it is StreamPart.Resize })
    }

    @Test
    fun nonceAboveTheSignedRangeMatchesItsUnsignedText() {
        // A WELCOME nonce with the top bit set reads as a negative Long; Rex writes it unsigned.
        val wire = -6_411_922_031_385_123_456L
        val text = java.lang.Long.toUnsignedString(wire)
        val message = "\u001b_rex1;$text;{\"type\":\"resize\",\"cols\":100,\"rows\":30,\"owner\":\"client:a\"}\u001b\\".toByteArray()
        val parts = StreamParser(wire).feed(fakeSnapshot() + message)
        assertEquals(StreamPart.Resize(100, 30, "client:a"), parts.single { it is StreamPart.Resize })
        assertEquals("", output(parts))
    }

    @Test
    fun streamWithoutSnapshotIsAllOutput() {
        val parts = StreamParser(nonce).feed("plain output".toByteArray())
        assertNull((parts[0] as StreamPart.Snapshot).bytes)
        assertEquals("plain output", output(parts))
    }

    @Test
    fun snapshotEndsAfterTheFinishRecord() {
        val snap = fakeSnapshot()
        val parser = StreamParser(nonce)
        assertTrue(parser.feed(snap.copyOfRange(0, snap.size - 1)).isEmpty())
        val parts = parser.feed(snap.copyOfRange(snap.size - 1, snap.size) + "x".toByteArray())
        assertArrayEquals(snap, (parts[0] as StreamPart.Snapshot).bytes)
        assertEquals("x", output(parts))
    }

    @Test
    fun unknownSnapshotVersionEndsAtRexsFirstMessage() {
        val snap = fakeSnapshot().also { it[8] = 2 }
        val parser = StreamParser(nonce)
        assertTrue(parser.feed(snap).isEmpty())
        val parts = parser.feed(rex("""{"type":"resize","cols":80,"rows":24}""") + "after".toByteArray())
        assertNull((parts[0] as StreamPart.Snapshot).bytes)
        assertEquals(StreamPart.Resize(80, 24, null), parts[1])
        assertEquals("after", output(parts))
    }

    @Test
    fun rexsFirstMessageIsFoundAmongBytesThatLookLikeIt() {
        // Payload made of the message's own characters, partial messages and another nonce.
        val junk = ("1;2;3;rex1;;\u001b_rex1;" + "\u001b_rex1;$nonce" + "\u001b_rex1;1$nonce;" + "_;x1e;").toByteArray()
        for (pad in 0..40) {
            val snap = "GHOSTSNP".toByteArray() + byteArrayOf(2, 0) + ByteArray(pad) { junk[it % junk.size] } + junk
            val stream = snap + rex("""{"type":"resize","cols":10,"rows":5}""") + "out".toByteArray()
            for (chunk in listOf(1, 7, 64, stream.size)) {
                val parser = StreamParser(nonce)
                val parts = stream.toList().chunked(chunk).flatMap { parser.feed(it.toByteArray()) }
                assertNull("pad $pad chunk $chunk", (parts.single { it is StreamPart.Snapshot } as StreamPart.Snapshot).bytes)
                assertTrue("pad $pad chunk $chunk", parts.contains(StreamPart.Resize(10, 5, null)))
                assertEquals("pad $pad chunk $chunk", "out", output(parts))
            }
        }
    }

    @Test
    fun recordTooLargeDoesNotThrow() {
        val snap = "GHOSTSNP".toByteArray() + byteArrayOf(1, 0) +
            ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putInt(-1).putInt(0).array()
        val parts = StreamParser(nonce).feed(snap + rex("""{"type":"child_exited","exit_code":0}""") + "x".toByteArray())
        assertNull((parts[0] as StreamPart.Snapshot).bytes)
        assertEquals(StreamPart.Exited(0), parts[1])
        assertEquals("x", output(parts))
    }

    @Test
    fun misreadRecordLengthEndsAtRexsFirstMessage() {
        // A record that claims 1 MiB, where Rex's message follows after a few bytes.
        val snap = "GHOSTSNP".toByteArray() + byteArrayOf(1, 0) +
            ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putInt(1 shl 20).putInt(0).putInt(7).array()
        val stream = snap + rex("""{"type":"resize","cols":10,"rows":5}""") + "out".toByteArray()
        for (cut in 1 until stream.size) {
            val parser = StreamParser(nonce)
            val parts = parser.feed(stream.copyOfRange(0, cut)) + parser.feed(stream.copyOfRange(cut, stream.size))
            assertNull("cut $cut", (parts.single { it is StreamPart.Snapshot } as StreamPart.Snapshot).bytes)
            assertTrue("cut $cut", parts.contains(StreamPart.Resize(10, 5, null)))
            assertEquals("cut $cut", "out", output(parts))
        }
    }

    @Test
    fun outputWithoutEscapeIsPassedOnWhole() {
        val parser = StreamParser(nonce)
        parser.feed(fakeSnapshot())
        val chunk = "plain text".toByteArray()
        val part = parser.feed(chunk).single() as StreamPart.Output
        assertArrayEquals(chunk, part.bytes)
    }
}
