package io.github.xchacha20_poly1305.libghostty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GhosttyTerminalSessionTest {

    // The session posts to the main thread, which a JVM test does not have.
    @BeforeTest
    fun setMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    private class RecordingTransport : GhosttyTerminalSession.Transport {
        val input = ByteArrayOutputStream()
        val events = mutableListOf<String>()

        override fun sendInput(data: ByteArray) {
            input.write(data)
            events += "input ${data.toString(Charsets.UTF_8)}"
        }

        override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
            events += "resize ${columns}x$rows"
        }

        override fun close() {}

        fun text() = input.toString(Charsets.UTF_8.name())
    }

    private class RecordingClipboard : GhosttyClipboard {
        var content: String? = "secret"
        var reads = 0

        override fun hasText() = content != null

        override fun getText(): String? {
            reads++
            return content
        }

        override fun setText(text: String) {
            content = text
        }
    }

    private fun GhosttyTerminalSession.feed(text: String) = feedOutput(text.toByteArray(Charsets.UTF_8))

    @Test
    fun sizeReportFollowsTheResizeItDescribes() {
        val session = GhosttyTerminalSession()
        val transport = RecordingTransport()
        try {
            session.resize(80, 24, 9, 18)
            session.transport = transport
            session.feed("\u001b[?2048h")
            transport.events.clear()
            session.resize(100, 30, 9, 18)
            assertEquals(listOf("resize 100x30", "input \u001b[48;30;100;540;900t"), transport.events)
        } finally {
            session.close()
        }
    }

    @Test
    fun sessionThatDoesNotReplyOnlySendsWhatTheUserTypes() {
        val session = GhosttyTerminalSession(GhosttyTerminalSession.Options(repliesToProgram = false))
        val transport = RecordingTransport()
        val clipboard = RecordingClipboard()
        var prompts = 0
        session.clipboard = clipboard
        session.attach(object : GhosttyTerminalHost {
            override fun requestClipboardRead(respond: (approved: Boolean) -> Unit) {
                prompts++
                respond(true)
            }
        })
        try {
            session.resize(80, 24, 9, 18)
            session.transport = transport
            session.feed("\u001b[?2048h\u001b[?2031h\u001b[?1004h")
            // Queries, a clipboard read, and the reports the program subscribed to.
            session.feed("\u001b[c\u001b[>q\u001b]52;c;?\u001b\\")
            session.resize(100, 30, 9, 18)
            session.setColorScheme(dark = false)
            session.sendFocus(gained = false)
            assertEquals("", transport.text())
            assertEquals(0, prompts)
            assertEquals(0, clipboard.reads)

            session.sendTypedInput("ls\n".toByteArray())
            assertEquals("ls\r", transport.text())
        } finally {
            session.close()
        }
    }

    @Test
    fun clipboardWritesApplyOnlyWhileAHostIsVisible() {
        val session = GhosttyTerminalSession()
        val clipboard = RecordingClipboard()
        session.clipboard = clipboard
        var visible = false
        val host = object : GhosttyTerminalHost {
            override val isVisible: Boolean
                get() = visible
        }
        fun write(text: String) = session.feed("\u001b]52;c;${Base64.getEncoder().encodeToString(text.toByteArray())}\u001b\\")
        try {
            write("no host")
            assertEquals("secret", clipboard.content)
            session.attach(host)
            write("hidden")
            assertEquals("secret", clipboard.content)
            visible = true
            write("shown")
            assertEquals("shown", clipboard.content)
        } finally {
            session.close()
        }
    }

    @Test
    fun selectionBoundsReachRowsOutsideTheViewport() {
        val session = GhosttyTerminalSession(GhosttyTerminalSession.Options(columns = 20, rows = 5))
        try {
            repeat(30) { session.feed("line $it\r\n") }
            session.withTerminal { terminal ->
                assertEquals(null, terminal.selectionBounds())
                terminal.select(0, 0, 3, 4)
                assertEquals(listOf(0, 0, 3, 4), terminal.selectionBounds()?.toList())
                terminal.scroll(-2)
                assertEquals(listOf(0, 2, 3, 6), terminal.selectionBounds()?.toList())
                // Dragged from below the viewport to its top row.
                assertEquals(true, terminal.select(5, 6, 1, 0))
                assertEquals(listOf(1, 0, 5, 6), terminal.selectionBounds()?.toList())
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun longOutputIsParsedAcrossParts() {
        val session = GhosttyTerminalSession()
        try {
            // The title sequence straddles the boundary between two parts.
            val output = "x".repeat(64 * 1024 - 3) + "\u001b]2;split title\u0007"
            session.feed(output)
            assertEquals("split title", session.title.value)
        } finally {
            session.close()
        }
    }

    @Test
    fun clipboardReadWithoutApprovalAnswersEmptyClipboard() {
        val session = GhosttyTerminalSession()
        session.clipboard = object : GhosttyClipboard {
            override fun hasText() = true

            override fun getText() = "secret"

            override fun setText(text: String) {}
        }
        val sent = ByteArrayOutputStream()
        session.transport = object : GhosttyTerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                sent.write(data)
            }

            override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {}

            override fun close() {}
        }
        try {
            session.feedOutput("\u001b]52;c;?\u001b\\".toByteArray(Charsets.UTF_8))
            assertEquals("\u001b]52;c;\u001b\\", sent.toString(Charsets.UTF_8.name()))
        } finally {
            session.close()
        }
    }
}
