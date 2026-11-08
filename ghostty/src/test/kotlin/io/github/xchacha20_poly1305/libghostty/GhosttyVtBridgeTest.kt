package io.github.xchacha20_poly1305.libghostty

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises each native method of [GhosttyVt] against a headless terminal. */
class GhosttyVtBridgeTest {

    private val handle = GhosttyVt.nativeCreate(
        80,
        24,
        1000,
        "libghostty-test",
        "xterm-256color",
        GhosttyCursorStyle.BLOCK.nativeValue,
        true,
        false,
        null,
    ).also { assertNotEquals(0L, it, "terminal creation failed") }

    @AfterTest
    fun free() {
        GhosttyVt.nativeFree(handle)
    }

    private fun write(text: String): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return GhosttyVt.nativeWrite(handle, bytes, 0, bytes.size)?.toString(Charsets.UTF_8)
    }

    private fun ByteArray?.text() = this?.toString(Charsets.UTF_8)

    @Test
    fun writeRejectsOutOfRangeSlices() {
        val bytes = "abc".toByteArray()
        assertNull(GhosttyVt.nativeWrite(handle, bytes, 2, 2))
        assertNull(GhosttyVt.nativeWrite(handle, bytes, -1, 1))
    }

    @Test
    fun deviceAttributesMatchGhostty() {
        assertEquals("\u001b[?62;22c", write("\u001b[c"))
        assertEquals("\u001b[>1;10;0c", write("\u001b[>c"))
    }

    @Test
    fun xtversionReportsConfiguredVersion() {
        assertEquals("\u001bP>|libghostty-test\u001b\\", write("\u001b[>q"))
    }

    @Test
    fun graphemeClusterModeFollowsDefault() {
        val requestGraphemeCluster = "\u001b[?2027\$p"
        assertEquals("\u001b[?2027;2\$y", write(requestGraphemeCluster))

        val clustering = GhosttyVt.nativeCreate(
            80,
            24,
            1000,
            "libghostty-test",
            "xterm-256color",
            GhosttyCursorStyle.BLOCK.nativeValue,
            true,
            true,
            null,
        )
        assertNotEquals(0L, clustering, "terminal creation failed")
        try {
            fun writeClustering(text: String): String? {
                val bytes = text.toByteArray(Charsets.UTF_8)
                return GhosttyVt.nativeWrite(clustering, bytes, 0, bytes.size)?.toString(Charsets.UTF_8)
            }
            assertEquals("\u001b[?2027;1\$y", writeClustering(requestGraphemeCluster))
            // A full reset restores the configured default, not the mode's built-in one.
            writeClustering("\u001b[?2027l\u001bc")
            assertEquals("\u001b[?2027;1\$y", writeClustering(requestGraphemeCluster))
        } finally {
            GhosttyVt.nativeFree(clustering)
        }
    }

    @Test
    fun sizeReportNeedsCellSize() {
        assertNull(write("\u001b[14t"))
        GhosttyVt.nativeResize(handle, 100, 30, 9, 18)
        assertEquals("\u001b[4;540;900t", write("\u001b[14t"))
    }

    @Test
    fun titlePwdAndBellRaiseEvents() {
        write("\u001b]2;hello\u0007\u001b]7;file://host/tmp/dir\u0007\u0007")
        val flags = GhosttyVt.nativeTakeEventFlags(handle)
        assertEquals(GhosttyVt.EVENT_TITLE, flags and GhosttyVt.EVENT_TITLE)
        assertEquals(GhosttyVt.EVENT_PWD, flags and GhosttyVt.EVENT_PWD)
        assertEquals(GhosttyVt.EVENT_BELL, flags and GhosttyVt.EVENT_BELL)
        assertEquals("hello", GhosttyVt.nativeGetTitle(handle).text())
        assertEquals("file://host/tmp/dir", GhosttyVt.nativeGetPwd(handle).text())
        assertEquals(0, GhosttyVt.nativeTakeEventFlags(handle))
    }

    @Test
    fun fullResetClearsTitleAndPwdAndRaisesEvents() {
        write("\u001b]2;hello\u0007\u001b]7;file://host/tmp/dir\u0007")
        GhosttyVt.nativeTakeEventFlags(handle)

        write("\u001bc")

        val flags = GhosttyVt.nativeTakeEventFlags(handle)
        assertEquals(GhosttyVt.EVENT_TITLE, flags and GhosttyVt.EVENT_TITLE)
        assertEquals(GhosttyVt.EVENT_PWD, flags and GhosttyVt.EVENT_PWD)
        assertNull(GhosttyVt.nativeGetTitle(handle))
        assertNull(GhosttyVt.nativeGetPwd(handle))
    }

    @Test
    fun clipboardWriteIsTakenOnce() {
        val payload = Base64.getEncoder().encodeToString("copied".toByteArray())
        write("\u001b]52;c;$payload\u001b\\")
        assertEquals(GhosttyVt.EVENT_CLIPBOARD, GhosttyVt.nativeTakeEventFlags(handle) and GhosttyVt.EVENT_CLIPBOARD)
        assertEquals("copied", GhosttyVt.nativeTakeClipboard(handle).text())
        assertNull(GhosttyVt.nativeTakeClipboard(handle))
    }

    @Test
    fun notificationsQueueInOrder() {
        write("\u001b]777;notify;First;one\u0007\u001b]777;notify;Second;two\u0007")
        assertEquals(
            GhosttyVt.EVENT_NOTIFICATION,
            GhosttyVt.nativeTakeEventFlags(handle) and GhosttyVt.EVENT_NOTIFICATION,
        )
        for ((title, body) in listOf("First" to "one", "Second" to "two")) {
            val packed = assertNotNull(GhosttyVt.nativeTakeNotification(handle))
            val titleLength = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(title, String(packed, 4, titleLength, Charsets.UTF_8))
            assertEquals(body, String(packed, 4 + titleLength, packed.size - 4 - titleLength, Charsets.UTF_8))
        }
        assertNull(GhosttyVt.nativeTakeNotification(handle))
    }

    @Test
    fun progressReport() {
        write("\u001b]9;4;1;42\u0007")
        assertEquals(GhosttyVt.EVENT_PROGRESS, GhosttyVt.nativeTakeEventFlags(handle) and GhosttyVt.EVENT_PROGRESS)
        assertContentEquals(intArrayOf(GhosttyVt.PROGRESS_STATE_SET, 42), GhosttyVt.nativeGetProgress(handle))
    }

    @Test
    fun colorSchemeReportOnlyWhenSubscribedAndChanged() {
        assertNull(GhosttyVt.nativeSetColorScheme(handle, false))
        write("\u001b[?2031h")
        assertNull(GhosttyVt.nativeSetColorScheme(handle, false))
        assertEquals("\u001b[?997;1n", GhosttyVt.nativeSetColorScheme(handle, true).text())
        assertEquals("\u001b[?997;1n", write("\u001b[?996n"))
    }

    @Test
    fun focusReportsNeedMode1004() {
        assertNull(GhosttyVt.nativeEncodeFocus(handle, true))
        write("\u001b[?1004h")
        assertEquals("\u001b[I", GhosttyVt.nativeEncodeFocus(handle, true).text())
        assertEquals("\u001b[O", GhosttyVt.nativeEncodeFocus(handle, false).text())
    }

    @Test
    fun hyperlinkAtCell() {
        write("\u001b]8;;https://example.com\u001b\\link\u001b]8;;\u001b\\ plain")
        assertEquals("https://example.com", GhosttyVt.nativeHyperlinkAt(handle, 2, 0).text())
        assertNull(GhosttyVt.nativeHyperlinkAt(handle, 6, 0))
        assertNull(GhosttyVt.nativeHyperlinkAt(handle, -1, 0))
        assertNull(GhosttyVt.nativeHyperlinkAt(handle, 500, 0))
    }

    @Test
    fun themeChangesBackground() {
        val palette = LongArray(256) { -1L }
        palette[1] = 0xFF123456
        GhosttyVt.nativeSetTheme(handle, 0xFFEEEEEE, 0xFF101010, -1L, palette)
        assertEquals(0xFF101010.toInt(), GhosttyVt.nativeGetBackgroundColor(handle))
        // OSC 4 queries report the palette in X11 rgb: form.
        assertEquals("\u001b]4;1;rgb:1212/3434/5656\u001b\\", write("\u001b]4;1;?\u001b\\"))
    }

    @Test
    fun parseColor() {
        assertEquals(0xFF336699.toInt(), GhosttyVt.nativeParseColor("#336699"))
        assertEquals(0, GhosttyVt.nativeParseColor("not a color"))
    }

    @Test
    fun scrollbarAndScrolling() {
        for (i in 1..50) write("line-$i\r\n")
        val bottom = assertNotNull(GhosttyVt.nativeScrollbar(handle))
        assertEquals(24L, bottom[2])
        assertEquals(bottom[0] - bottom[2], bottom[1])
        GhosttyVt.nativeScroll(handle, -10)
        assertEquals(bottom[1] - 10, GhosttyVt.nativeScrollbar(handle)!![1])
        GhosttyVt.nativeScrollToBottom(handle)
        assertEquals(bottom[1], GhosttyVt.nativeScrollbar(handle)!![1])
    }

    @Test
    fun viewportState() {
        assertEquals(0, GhosttyVt.nativeViewportState(handle))
        write("\u001b[?1049h\u001b[?1000h\u001b[?2026h")
        assertEquals(
            GhosttyVt.VIEWPORT_ALTERNATE_SCREEN or GhosttyVt.VIEWPORT_MOUSE_TRACKING or GhosttyVt.VIEWPORT_SYNC_OUTPUT,
            GhosttyVt.nativeViewportState(handle),
        )
    }

    @Test
    fun snapshotReportsOnlyDirtyRows() {
        val buffer = ByteBuffer.allocateDirect(512 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, GhosttyVt.nativeSnapshot(handle, buffer, false))
        assertEquals(0, GhosttyVt.nativeSnapshot(handle, buffer, false))
        write("\u001b[5;1Hx")
        // The row the cursor left is redrawn too.
        val rows = GhosttyVt.nativeSnapshot(handle, buffer, false)
        assertTrue(rows in 1 until 24, "wrote $rows rows")
        assertEquals(DIRTY_PARTIAL, buffer.getInt(4), "dirty kind")
        val cols = buffer.getInt(8)
        val indices = (0 until rows).map { row ->
            buffer.getInt(rowOffset(buffer, cols, row))
        }
        assertTrue(4 in indices, "dirty rows $indices")
    }

    /** Byte offset of the [row]th row record, skipping the header and earlier rows with their text. */
    private fun rowOffset(buffer: ByteBuffer, cols: Int, row: Int): Int {
        var offset = 15 * 4
        repeat(row) {
            val textBytes = buffer.getInt(offset + 12) * 2
            offset += (16 + cols * 16 + textBytes + 3) / 4 * 4
        }
        return offset
    }

    @Test
    fun snapshotTruncatesIntoSmallBuffer() {
        val buffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
        val rows = GhosttyVt.nativeSnapshot(handle, buffer, false)
        assertTrue(rows in 1 until 24, "wrote $rows rows")
        assertEquals(1, buffer.getInt(13 * 4), "truncated flag")
        // The rows that did not fit stay dirty.
        val large = ByteBuffer.allocateDirect(512 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, GhosttyVt.nativeSnapshot(handle, large, false))
    }

    @Test
    fun encodeKey() {
        fun key(key: GhosttyKey, mods: Int = 0, text: String? = null) = GhosttyVt.nativeEncodeKey(
            handle, key.value, GhosttyVt.KEY_ACTION_PRESS, mods, 0, false, text?.toByteArray(),
        ).text()
        assertEquals("a", key(GhosttyKey.KEY_A, text = "a"))
        assertEquals("\u0001", key(GhosttyKey.KEY_A, GhosttyMods.CTRL))
        assertEquals("\r", key(GhosttyKey.ENTER))
        assertEquals("\u001b[A", key(GhosttyKey.ARROW_UP))
        write("\u001b[?1h")
        assertEquals("\u001bOA", key(GhosttyKey.ARROW_UP))
        assertEquals("\u001b[1;5A", key(GhosttyKey.ARROW_UP, GhosttyMods.CTRL))
        // Release events only report with the kitty keyboard protocol.
        assertNull(
            GhosttyVt.nativeEncodeKey(handle, GhosttyKey.KEY_A.value, GhosttyVt.KEY_ACTION_RELEASE, 0, 0, false, null),
        )
    }

    @Test
    fun encodeMouse() {
        assertNull(mouse(GhosttyVt.MOUSE_ACTION_PRESS, GhosttyVt.MOUSE_BUTTON_LEFT, 0, 0))
        write("\u001b[?1000h\u001b[?1006h")
        assertEquals("\u001b[<0;3;2M", mouse(GhosttyVt.MOUSE_ACTION_PRESS, GhosttyVt.MOUSE_BUTTON_LEFT, 2, 1))
        assertEquals("\u001b[<0;3;2m", mouse(GhosttyVt.MOUSE_ACTION_RELEASE, GhosttyVt.MOUSE_BUTTON_LEFT, 2, 1))
        assertEquals("\u001b[<64;1;1M", mouse(GhosttyVt.MOUSE_ACTION_PRESS, GhosttyVt.MOUSE_BUTTON_WHEEL_UP, 0, 0))
        assertEquals(
            "\u001b[<4;1;1M",
            GhosttyVt.nativeEncodeMouse(
                handle, GhosttyVt.MOUSE_ACTION_PRESS, GhosttyVt.MOUSE_BUTTON_LEFT, 0, 0, GhosttyMods.SHIFT,
            ).text(),
        )
        // Mode 1000 does not report motion.
        assertNull(mouse(GhosttyVt.MOUSE_ACTION_MOTION, GhosttyVt.MOUSE_BUTTON_LEFT, 5, 5))
    }

    private fun mouse(action: Int, button: Int, col: Int, row: Int) =
        GhosttyVt.nativeEncodeMouse(handle, action, button, col, row, 0).text()

    @Test
    fun selection() {
        write("hello world\r\nsecond line")
        assertNull(GhosttyVt.nativeSelectionText(handle))
        assertTrue(GhosttyVt.nativeSelectWord(handle, 7, 0))
        assertEquals("world", GhosttyVt.nativeSelectionText(handle).text())
        assertTrue(GhosttyVt.nativeSetSelection(handle, 6, 0, 5, 1))
        assertEquals("world\nsecond", GhosttyVt.nativeSelectionText(handle).text())
        assertTrue(GhosttyVt.nativeSelectAll(handle))
        assertEquals("hello world\nsecond line", GhosttyVt.nativeSelectionText(handle).text())
        GhosttyVt.nativeClearSelection(handle)
        assertNull(GhosttyVt.nativeSelectionText(handle))
        assertFalse(GhosttyVt.nativeSetSelection(handle, 0, 0, 500, 0))
    }

    @Test
    fun paste() {
        assertTrue(GhosttyVt.nativeIsPasteSafe("ls".toByteArray()))
        assertFalse(GhosttyVt.nativeIsPasteSafe("rm -rf ~\n".toByteArray()))
        assertEquals("ab\r", GhosttyVt.nativeEncodePaste(handle, "ab\n".toByteArray()).text())
        write("\u001b[?2004h")
        assertEquals("\u001b[200~ab\n\u001b[201~", GhosttyVt.nativeEncodePaste(handle, "ab\n".toByteArray()).text())
    }

    @Test
    fun kittyImageAndPlacement() {
        GhosttyVt.nativeResize(handle, 80, 24, 10, 20)
        assertNull(GhosttyVt.nativeKittyPlacements(handle))
        // A 2x1 RGB image (red, green), placed at the cursor in row 3.
        val pixels = Base64.getEncoder().encodeToString(byteArrayOf(-1, 0, 0, 0, -1, 0))
        write("\u001b[3;5H\u001b_Ga=T,f=24,s=2,v=1,i=7,q=2;$pixels\u001b\\")

        val snapshot = ByteBuffer.allocateDirect(512 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        GhosttyVt.nativeSnapshot(handle, snapshot, false)
        assertTrue(snapshot.getInt(4) and (1 shl 8) != 0, "kitty placements dirty")
        GhosttyVt.nativeSnapshot(handle, snapshot, false)
        assertEquals(0, snapshot.getInt(4) and (1 shl 8), "kitty placements clean")
        val placements = assertNotNull(GhosttyVt.nativeKittyPlacements(handle))
        assertEquals(GhosttyVt.KITTY_PLACEMENT_LONGS, placements.size)
        assertEquals(7L, placements[0], "image id")
        assertEquals(4L, placements[2], "viewport column")
        assertEquals(2L, placements[3], "viewport row")
        assertEquals(listOf(2L, 1L), listOf(placements[10], placements[11]), "source size")

        val image = assertNotNull(GhosttyVt.nativeKittyImage(handle, 7))
        assertContentEquals(intArrayOf(2, 1, 0xFFFF0000.toInt(), 0xFF00FF00.toInt()), image)
        assertNull(GhosttyVt.nativeKittyImage(handle, 8))
    }

    @Test
    fun kittyPngIsDecoded() {
        // 1x1 opaque blue PNG.
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNgYPgPAAEDAQAIicLsAAAAAElFTkSuQmCC"
        write("\u001b_Ga=t,f=100,i=9,q=2;$png\u001b\\")
        assertContentEquals(intArrayOf(1, 1, 0xFF0000FF.toInt()), GhosttyVt.nativeKittyImage(handle, 9))
    }

    private companion object {
        const val DIRTY_PARTIAL = 1
    }
}
