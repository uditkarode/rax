package de.uditkaro.rax.ui.terminal

import de.uditkaro.rax.rex.RexException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenDumpTest {

    /**
     * A pane on Rex whose `format` result is [screen], sent in parts of at most [partBytes] bytes. Its size calls
     * return [sizes] in turn, then the last one. A call takes [latency] on [clock].
     */
    private class Pane(
        val screen: String,
        val partBytes: Int,
        val columns: Int = 119,
        val rows: Int = 43,
        val withEof: Boolean = true,
        val failBlobRead: Exception? = null,
        val sizes: List<Pair<Int, Int>> = listOf(columns to rows),
        val latency: Map<String, Long> = emptyMap(),
    ) {
        val bytes = screen.encodeToByteArray()
        val log = mutableListOf<String>()
        val blobOffsets = mutableListOf<Long>()
        var formatArgs: JsonObject? = null
        var clock = 0L
        private var sizeCalls = 0

        suspend fun call(method: String, args: JsonObject?): JsonObject {
            log += method
            clock += latency[method] ?: 0
            return when (method) {
                "size" -> buildJsonObject {
                    val (columns, rows) = sizes[minOf(sizeCalls++, sizes.lastIndex)]
                    put("columns", columns)
                    put("rows", rows)
                    put("cell_width_px", 16)
                    put("cell_height_px", 32)
                    put("owner", JsonNull)
                    putJsonArray("desired") {}
                }
                "format" -> {
                    formatArgs = args
                    val first = part(0)
                    buildJsonObject {
                        put("content", first.decodeToString())
                        if (first.size < bytes.size) put("id", BLOB_ID)
                        if (withEof) {
                            put("total", bytes.size)
                            put("eof", first.size == bytes.size)
                        }
                    }
                }
                "blob_read" -> {
                    failBlobRead?.let { throw it }
                    assertEquals(BLOB_ID, args!!["id"]!!.jsonPrimitive.long)
                    val offset = args["offset"]!!.jsonPrimitive.long
                    blobOffsets += offset
                    val data = part(offset.toInt())
                    buildJsonObject {
                        put("id", BLOB_ID)
                        put("data", data.decodeToString())
                        put("total", bytes.size)
                        put("eof", offset + data.size == bytes.size.toLong())
                    }
                }
                else -> error("unexpected $method")
            }
        }

        /** Up to [partBytes] bytes from [offset], ending on a whole character, as Rex does. */
        private fun part(offset: Int): ByteArray {
            var end = minOf(offset + partBytes, bytes.size)
            while (end < bytes.size && bytes[end].toInt() and 0xC0 == 0x80) end--
            return bytes.copyOfRange(offset, end)
        }
    }

    private val longScreen = buildString {
        repeat(200) { append("\u001b[34m~/rax\u001b[0m ❯ écho $it\r\n") }
        append("\u001b[12;7H")
    }

    @Test
    fun aLongScreenIsReadInOrder() = runTest {
        val pane = Pane(longScreen, partBytes = 1000)
        val dump = readScreenDump(pane::call)
        assertNotNull(dump)
        assertArrayEquals(pane.bytes, dump!!.parts.reduce { a, b -> a + b })
        // Each part starts where the bytes before it end, multi-byte characters included.
        val ends = dump.parts.runningFold(0L) { sum, part -> sum + part.size }
        assertEquals(ends.drop(1).dropLast(1), pane.blobOffsets)
    }

    @Test
    fun aShortScreenComesInOneReply() = runTest {
        val pane = Pane("\u001b[Hhello", partBytes = 1000)
        val dump = readScreenDump(pane::call)!!
        assertEquals(1, dump.parts.size)
        assertEquals(listOf("size", "format", "size"), pane.log)
    }

    @Test
    fun aReplyWithoutIdHoldsTheWholeScreenOnOlderServers() = runTest {
        val pane = Pane("\u001b[Hhello", partBytes = 1000, withEof = false)
        assertEquals("\u001b[Hhello", readScreenDump(pane::call)!!.parts.single().decodeToString())
    }

    @Test
    fun aMissingPartGivesNoScreen() = runTest {
        val pane = Pane(longScreen, partBytes = 1000, failBlobRead = RexException("not_found", 404, "gone"))
        assertNull(readScreenDump(pane::call))
    }

    @Test
    fun cancellationIsNotTakenForAFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                readScreenDump({ method, _ -> if (method == "format") throw CancellationException("picked another pane") else Pane("x", 10).call(method, null) })
            }
        }
    }

    @Test
    fun theScreenIsLaidOutAtRexsSizeWithThePhonesPalette() = runTest {
        val pane = Pane("\u001b[Hhello", partBytes = 1000, columns = 119, rows = 43)
        val dump = readScreenDump(pane::call)!!
        assertEquals(119, dump.columns)
        assertEquals(43, dump.rows)
        val args = pane.formatArgs!!
        assertEquals(JsonPrimitive("vt"), args["format"])
        assertEquals(JsonPrimitive(true), args["extra_cursor"])
        assertFalse(args.containsKey("extra_palette"))
    }

    @Test
    fun theDrawingTimeIsARoundTripAfterTheCallWentOut() = runTest {
        // The size call times a round trip of 10; the long reply takes 1000 to arrive.
        val pane = Pane(longScreen, partBytes = 1000, latency = mapOf("size" to 10L, "format" to 1000L))
        val dump = readScreenDump(pane::call, now = { pane.clock })!!
        assertEquals(20L, dump.drawnAt)
    }

    @Test
    fun aShortReplyDrawsNoLaterThanItArrives() = runTest {
        val pane = Pane("\u001b[Hhello", partBytes = 1000, latency = mapOf("size" to 100L, "format" to 5L))
        val dump = readScreenDump(pane::call, now = { pane.clock })!!
        assertEquals(105L, dump.drawnAt)
    }

    @Test
    fun aResizeBetweenTheReadsReadsTheScreenAgain() = runTest {
        val pane = Pane("\u001b[Hhello", partBytes = 1000, sizes = listOf(119 to 43, 80 to 24))
        val dump = readScreenDump(pane::call)!!
        assertEquals(listOf("size", "format", "size", "format", "size"), pane.log)
        assertEquals(80, dump.columns)
        assertEquals(24, dump.rows)
    }

    private companion object {
        const val BLOB_ID = 7L
    }
}
