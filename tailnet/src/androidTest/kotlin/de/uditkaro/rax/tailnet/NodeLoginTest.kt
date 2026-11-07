package de.uditkaro.rax.tailnet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Go node against Tailscale's control server. Needs network
 * access. It does not log in; the test package's node has never logged in, so
 * it must stop at a login URL.
 */
@RunWith(AndroidJUnit4::class)
class NodeLoginTest {
    private val tailnet = Tailnet.get(InstrumentationRegistry.getInstrumentation().targetContext)

    private suspend fun awaitLoginUrl(): String {
        val status = withTimeout(90.seconds) {
            tailnet.status.first { it !is TailnetStatus.Starting }
        }
        assertTrue("status is $status", status is TailnetStatus.NeedsLogin)
        return (status as TailnetStatus.NeedsLogin).authUrl
    }

    @Test
    fun startStopsAtLoginUrl() = runBlocking {
        tailnet.start()
        val url = awaitLoginUrl()
        assertTrue(url, url.startsWith("https://login.tailscale.com/"))
        assertEquals(emptyList<TailnetPeer>(), tailnet.peers.value)

        // Without a login, lookups and dials fail at once.
        assertThrows(UnknownHostException::class.java) { tailnet.dns.lookup("desktop") }
        val e = assertThrows(ConnectException::class.java) {
            tailnet.socketFactory.createSocket()
                .connect(InetSocketAddress(InetAddress.getByName("100.64.0.2"), 80), 5000)
        }
        assertTrue(e.message, e.message!!.contains("not connected"))

        tailnet.login()
        assertTrue(awaitLoginUrl().startsWith("https://login.tailscale.com/"))
        tailnet.logout()
        assertTrue(awaitLoginUrl().startsWith("https://login.tailscale.com/"))

        tailnet.stop()
        assertEquals(TailnetStatus.Stopped, tailnet.status.value)

        // The node starts again in the same process.
        tailnet.start()
        awaitLoginUrl()
        tailnet.stop()
    }
}
