package io.github.xchacha20_poly1305.libghostty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `rex-snapshot.ghostsnp` is the snapshot Rex sent at the start of a pane's stream, in a pane that ran
 * `rex-snapshot.sh` on an 80×24 screen.
 */
class GhosttySnapshotTest {

    // The session posts to the main thread, which a JVM test does not have.
    @BeforeTest
    fun setMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    private fun snapshot(): ByteArray = javaClass.getResourceAsStream("/rex-snapshot.ghostsnp")!!.use { it.readBytes() }

    private inline fun restored(block: (GhosttyTerminalSession) -> Unit) {
        val session = assertNotNull(GhosttyTerminalSession.restore(snapshot()))
        try {
            block(session)
        } finally {
            session.close()
        }
    }

    @Test
    fun restoresTheScreen() = restored { session ->
        val screen = assertNotNull(session.screenText())
        assertContains(screen, "line 300 colour 4 bold")
        assertContains(screen, "truecolor ── ██ done")
        assertFalse("line 001" in screen)
    }

    @Test
    fun restoresTheScrollback() = restored { session ->
        val transcript = assertNotNull(session.transcriptText())
        assertContains(transcript, "line 001 colour 1 bold")
        assertContains(transcript, "line 150 colour 6 bold")
    }

    @Test
    fun takesOutputAfterTheSnapshot() = restored { session ->
        session.feedOutput("after the snapshot\r\n".toByteArray())
        assertContains(assertNotNull(session.screenText()), "after the snapshot")
    }

    @Test
    fun rejectsADamagedSnapshot() {
        val damaged = snapshot().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertNull(GhosttyTerminalSession.restore(damaged))
        assertNull(GhosttyTerminalSession.restore(snapshot().copyOf(1000)))
        assertNull(GhosttyTerminalSession.restore("not a snapshot".toByteArray()))
    }
}
