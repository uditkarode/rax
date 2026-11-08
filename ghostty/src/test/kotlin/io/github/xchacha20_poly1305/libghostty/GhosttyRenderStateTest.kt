package io.github.xchacha20_poly1305.libghostty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttyRenderStateTest {

    init {
        // The session posts to the main thread, which a JVM test does not have.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    private val session = GhosttyTerminalSession(GhosttyTerminalSession.Options(columns = 20, rows = 5))

    @AfterTest
    fun close() {
        session.close()
        Dispatchers.resetMain()
    }

    private fun write(text: String) = session.feedOutput(text.encodeToByteArray())

    private fun GhosttyRenderState.text(row: Int): String = buildString {
        for (col in 0 until columns) {
            val length = textLength(col, row)
            append(if (length == 0) ' ' else String(rowText(row), textOffset(col, row), length))
        }
    }.trimEnd()

    private fun update(state: GhosttyRenderState) = session.withTerminal { state.update(it) }!!

    @Test
    fun readsGridCursorAndColors() {
        write("\u001b[1;31mred\u001b[0m plain\r\n\u001b[4:3mwavy\u001b[0m")
        val state = GhosttyRenderState()
        assertTrue(update(state))
        assertEquals(20, state.columns)
        assertEquals(5, state.rows)
        assertEquals("red plain", state.text(0))
        assertEquals("wavy", state.text(1))
        // Bold base colors are drawn bright.
        assertEquals(GhosttyCellFlags.BOLD, state.flags(0, 0) and GhosttyCellFlags.BOLD)
        assertTrue(state.foreground(0, 0) != state.defaultForeground)
        assertEquals(state.defaultForeground, state.foreground(4, 0))
        assertEquals(GhosttyCellFlags.UNDERLINE_CURLY, state.underline(0, 1))
        assertEquals(4, state.cursorColumn)
        assertEquals(1, state.cursorRow)
        assertEquals(GhosttyCursorStyle.BLOCK, state.cursorStyle)
        assertTrue(state.cursorVisible)
        assertFalse(update(state), "nothing changed")
    }

    @Test
    fun graphemeClusterJoinsZwjSequenceIntoOneCell() {
        val family = "👨‍👩‍👧"
        val clustering = GhosttyTerminalSession(
            GhosttyTerminalSession.Options(columns = 20, rows = 5, defaultGraphemeCluster = true),
        )
        try {
            clustering.feedOutput("${family}x".encodeToByteArray())
            val state = GhosttyRenderState()
            assertTrue(clustering.withTerminal { state.update(it) }!!)
            assertEquals(family, String(state.rowText(0), state.textOffset(0, 0), state.textLength(0, 0)))
            assertEquals(GhosttyCellFlags.WIDE, state.flags(0, 0) and GhosttyCellFlags.WIDE)
            assertEquals("x", String(state.rowText(0), state.textOffset(2, 0), state.textLength(2, 0)))
        } finally {
            clustering.close()
        }
    }

    @Test
    fun newStateReadsTheWholeScreen() {
        write("first\r\nsecond")
        assertTrue(update(GhosttyRenderState()))
        // The terminal reported every row to the first state; a second one still sees them all.
        val state = GhosttyRenderState()
        assertTrue(update(state))
        assertEquals("first", state.text(0))
        assertEquals("second", state.text(1))
    }

    @Test
    fun selectionInvertsColors() {
        write("hello world")
        val state = GhosttyRenderState()
        update(state)
        session.withTerminal { assertTrue(it.selectWord(1, 0)) }
        update(state)
        assertEquals(0..4, state.selection(0))
        assertNull(state.selection(1))
        assertEquals(state.defaultBackground, state.foreground(0, 0))
        assertEquals(state.defaultForeground, state.background(0, 0))
        assertEquals(state.defaultForeground, state.foreground(6, 0))
    }

    @Test
    fun wideCharactersAndScrollback() {
        write("中文\r\n")
        repeat(10) { write("line $it\r\n") }
        val state = GhosttyRenderState()
        update(state)
        assertTrue(state.scrollbar.total > state.scrollbar.length)
        assertTrue(state.scrollbar.isAtBottom)
        session.withTerminal { it.scroll(-100) }
        update(state)
        assertEquals("中文", state.text(0).replace(" ", ""))
        assertEquals(GhosttyCellFlags.WIDE, state.flags(0, 0) and GhosttyCellFlags.WIDE)
        assertEquals(GhosttyCellFlags.SPACER, state.flags(1, 0) and GhosttyCellFlags.SPACER)
        assertFalse(state.scrollbar.isAtBottom)
    }

    @Test
    fun largeScreenGrowsTheBuffer() {
        val big = GhosttyTerminalSession(GhosttyTerminalSession.Options(columns = 300, rows = 100))
        try {
            big.feedOutput("x".repeat(30_000).encodeToByteArray())
            val state = GhosttyRenderState()
            assertTrue(big.withTerminal { state.update(it) }!!)
            assertEquals(100, state.rows)
            assertEquals("x".repeat(300), String(state.rowText(50), 0, 300))
        } finally {
            big.close()
        }
    }

    @Test
    fun defaultColorChangeRedrawsEveryRow() {
        write("first\r\nsecond")
        val state = GhosttyRenderState()
        update(state)
        write("\u001b]11;#ffffff\u0007")
        assertTrue(update(state))
        assertEquals(0xFFFFFFFF.toInt(), state.defaultBackground)
        assertEquals(state.defaultBackground, state.background(0, 0))
        write("\u001b]10;#102030\u0007")
        assertTrue(update(state))
        assertEquals(0xFF102030.toInt(), state.foreground(0, 0))
        write("\u001b]111\u0007")
        assertTrue(update(state))
        assertEquals(state.defaultBackground, state.background(0, 1))
        assertEquals(0xFF000000.toInt(), state.defaultBackground)
    }

    @Test
    fun themeColorsReachTheState() {
        val state = GhosttyRenderState()
        update(state)
        session.setTheme(
            GhosttyTheme(0xFFD0D0D0.toInt(), 0xFF212121.toInt(), null, null, null, List(256) { null }),
        )
        assertTrue(update(state))
        assertEquals(0xFF212121.toInt(), state.defaultBackground)
        assertEquals(0xFFD0D0D0.toInt(), state.defaultForeground)
    }
}
