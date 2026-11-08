package io.github.xchacha20_poly1305.libghostty

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GhosttyVtSmokeTest {

    private class Cell(
        val foreground: Int,
        val textOffset: Int,
        val textLength: Int,
        val flags: Int,
    )

    private class Row(
        val cells: List<Cell>,
        val text: CharArray,
    )

    private fun createTerminal(terminfoName: String? = "xterm-256color"): Long {
        val handle = GhosttyVt.nativeCreate(
            80,
            24,
            1000,
            "libghostty-test",
            terminfoName,
            GhosttyCursorStyle.BLOCK.nativeValue,
            true,
            false,
            null,
        )
        assertNotEquals(0L, handle, "terminal creation failed")
        return handle
    }

    @Test
    fun parseAndSnapshotColoredText() {
        val handle = createTerminal()
        try {
            val payload = "\u001b[38;2;255;0;0mred\u001b[0m\r\nplain".toByteArray(Charsets.UTF_8)
            val padded = ByteArray(payload.size + 7)
            payload.copyInto(padded, 3)
            GhosttyVt.nativeWrite(handle, padded, 3, payload.size)

            val buffer = ByteBuffer.allocateDirect(512 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val rowCount = GhosttyVt.nativeSnapshot(handle, buffer, false)
            assertTrue(rowCount > 0, "snapshot returned $rowCount")

            buffer.position(0)
            assertEquals(2, buffer.int, "snapshot version")
            buffer.int
            val cols = buffer.int
            assertEquals(80, cols)
            buffer.int
            buffer.int
            val defaultForeground = buffer.int
            buffer.position(15 * 4)

            val rows = HashMap<Int, Row>()
            repeat(rowCount) {
                val rowIndex = buffer.int
                buffer.int
                buffer.int
                val textUnits = buffer.int
                val cells = ArrayList<Cell>(cols)
                repeat(cols) {
                    val foreground = buffer.int
                    buffer.int
                    val textOffset = buffer.short.toInt() and 0xFFFF
                    val textLength = buffer.short.toInt() and 0xFFFF
                    val flags = buffer.short.toInt() and 0xFFFF
                    buffer.short
                    cells.add(Cell(foreground, textOffset, textLength, flags))
                }
                val text = CharArray(textUnits)
                for (i in 0 until textUnits) text[i] = buffer.char
                if (textUnits % 2 != 0) buffer.short
                rows[rowIndex] = Row(cells, text)
            }

            val redRow = rows[0] ?: throw AssertionError("row 0 not in snapshot")
            for ((i, expected) in "red".withIndex()) {
                val cell = redRow.cells[i]
                assertEquals(1, cell.textLength, "row 0 col $i text length")
                assertEquals(expected, redRow.text[cell.textOffset], "row 0 col $i char")
                assertEquals(0, cell.flags and FLAG_FG_DEFAULT, "row 0 col $i flags")
                assertEquals(0xFFFF0000.toInt(), cell.foreground, "row 0 col $i color")
            }

            val plainRow = rows[1] ?: throw AssertionError("row 1 not in snapshot")
            for ((i, expected) in "plain".withIndex()) {
                val cell = plainRow.cells[i]
                assertEquals(1, cell.textLength, "row 1 col $i text length")
                assertEquals(expected, plainRow.text[cell.textOffset], "row 1 col $i char")
                assertEquals(
                    FLAG_FG_DEFAULT,
                    cell.flags and FLAG_FG_DEFAULT,
                    "row 1 col $i uses the default foreground",
                )
            }
            assertEquals(0xFFFFFFFF.toInt(), defaultForeground)
        } finally {
            GhosttyVt.nativeFree(handle)
        }
    }

    @Test
    fun backgroundColorChangeRaisesColorsEvent() {
        val handle = createTerminal()
        try {
            GhosttyVt.nativeTakeEventFlags(handle)
            write(handle, "\u001b]11;#204060\u0007")
            val flags = GhosttyVt.nativeTakeEventFlags(handle)
            assertEquals(GhosttyVt.EVENT_COLORS, flags and GhosttyVt.EVENT_COLORS)
            assertEquals(0xFF204060.toInt(), GhosttyVt.nativeGetBackgroundColor(handle))
        } finally {
            GhosttyVt.nativeFree(handle)
        }
    }

    @Test
    fun transcriptIncludesScrollback() {
        val handle = createTerminal()
        try {
            for (i in 1..30) write(handle, "line-$i\r\n")
            val viewport = GhosttyVt.nativeViewportText(handle)!!.toString(Charsets.UTF_8)
            assertTrue("line-1\n" !in viewport, "viewport still holds the first line")
            assertTrue("line-30" in viewport)
            val transcript = GhosttyVt.nativeTranscriptText(handle)!!.toString(Charsets.UTF_8)
            assertTrue("line-1\n" in transcript, "transcript lost the first line")
            assertTrue("line-30" in transcript, "transcript lost the last line")
        } finally {
            GhosttyVt.nativeFree(handle)
        }
    }

    @Test
    fun xtgettcapReportsTerminfoName() {
        val handle = createTerminal(terminfoName = "xterm-ghostty")
        try {
            // XTGETTCAP: DCS + q <hex of "TN"> ST
            val response = write(handle, "\u001bP+q544e\u001b\\")
            val expected = "xterm-ghostty".toByteArray(Charsets.US_ASCII)
                .joinToString("") { "%02X".format(it) }
            assertTrue(
                response != null && expected in response.toString(Charsets.UTF_8).uppercase(),
                "response ${response?.toString(Charsets.UTF_8)} lacks $expected",
            )
        } finally {
            GhosttyVt.nativeFree(handle)
        }
    }

    private fun write(handle: Long, text: String): ByteArray? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return GhosttyVt.nativeWrite(handle, bytes, 0, bytes.size)
    }

    private companion object {
        const val FLAG_FG_DEFAULT = 1 shl 9
    }
}
