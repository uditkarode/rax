package de.uditkaro.rax.tailnet

import java.io.File
import java.net.Proxy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TailnetTest {
    private val node = FakeNode()
    private val network = FakeNetworkWatcher()

    private fun tailnet(dispatcher: CoroutineDispatcher, interfaces: () -> String = { "[]" }) = Tailnet(
        stateDir = File("/data/state/tailnet"),
        defaultHostname = "rax-pixel-9",
        deviceModel = "Google Pixel 9",
        osVersion = "17",
        node = node,
        network = network,
        dispatcher = dispatcher,
        listInterfaces = interfaces,
    )

    private val running = """
        {"state":"Running","tailnet":"t","self":{"dnsName":"rax-pixel-9.t.ts.net","ips":["100.64.0.1"]},
         "peers":[{"dnsName":"desktop.t.ts.net","online":true,"ips":["100.64.0.2"]}]}
    """

    @Test
    fun startPassesConfigAndFollowsStatus() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        assertEquals(TailnetStatus.Stopped, tailnet.status.value)

        tailnet.start()
        assertEquals(listOf("/data/state/tailnet", "rax-pixel-9", "Google Pixel 9", "17"), node.started)
        assertEquals(TailnetStatus.Starting, tailnet.status.value)

        network.listener!!("wlan0", "192.168.1.1")
        assertEquals(listOf("wlan0" to "192.168.1.1"), node.networkChanges)

        node.host.onStatus("""{"state":"NeedsLogin","authURL":"https://login.tailscale.com/a/1"}""")
        assertEquals(TailnetStatus.NeedsLogin("https://login.tailscale.com/a/1"), tailnet.status.value)

        node.host.onStatus(running)
        assertEquals("rax-pixel-9.t.ts.net", (tailnet.status.value as TailnetStatus.Running).dnsName)
        assertEquals(listOf("desktop.t.ts.net"), tailnet.peers.value.map { it.dnsName })
    }

    @Test
    fun startWithHostname() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        tailnet.start("rax-test")
        assertEquals("rax-test", node.started!![1])
    }

    @Test
    fun stopClearsStateAndIgnoresLateStatus() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        tailnet.start()
        node.host.onStatus(running)

        tailnet.stop()
        assertEquals(1, node.stops)
        assertEquals(null, network.listener)
        assertEquals(TailnetStatus.Stopped, tailnet.status.value)
        assertEquals(emptyList<TailnetPeer>(), tailnet.peers.value)

        node.host.onStatus(running)
        assertEquals(TailnetStatus.Stopped, tailnet.status.value)
    }

    @Test
    fun startFailureIsError() = runTest {
        node.startError = Exception("cannot create state dir")
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        tailnet.start()
        assertEquals(TailnetStatus.Error("cannot create state dir"), tailnet.status.value)
        assertEquals(null, network.listener)

        node.startError = null
        tailnet.start()
        assertEquals(TailnetStatus.Starting, tailnet.status.value)
    }

    @Test
    fun loginAndLogout() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        assertTrue(runCatching { tailnet.login() }.exceptionOrNull() is IllegalStateException)

        tailnet.start()
        tailnet.login()
        tailnet.logout()
        assertEquals(1, node.logins)
        assertEquals(1, node.logouts)

        node.loginError = Exception("control unreachable")
        val e = runCatching { tailnet.login() }.exceptionOrNull()
        assertTrue(e is TailnetException)
        assertEquals("login failed: control unreachable", e!!.message)
    }

    @Test
    fun retryRestartsANodeInError() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        tailnet.start("rax-test")
        node.host.onStatus("""{"state":"Stopped","error":"tailnet requires logging","loggingRequired":true}""")
        assertEquals(TailnetStatus.Error("tailnet requires logging", loggingRequired = true), tailnet.status.value)

        // start() leaves a started node alone, so only retry() gets it out of the error.
        tailnet.start()
        assertEquals(1, node.starts)
        tailnet.retry()
        assertEquals(1, node.stops)
        assertEquals(2, node.starts)
        assertEquals("rax-test", node.started!![1])
        assertEquals(TailnetStatus.Starting, tailnet.status.value)
    }

    @Test
    fun retryLeavesAWorkingNodeAlone() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        tailnet.retry()
        node.host.onStatus(running)
        tailnet.retry()
        assertEquals(1, node.starts)
        assertEquals(0, node.stops)
        assertTrue(tailnet.status.value is TailnetStatus.Running)
    }

    @Test
    fun retryStartsANodeThatFailedToStart() = runTest {
        node.startError = Exception("cannot create state dir")
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        tailnet.start()
        node.startError = null
        tailnet.retry()
        assertEquals(1, node.starts)
        assertEquals(0, node.stops)
        assertEquals(TailnetStatus.Starting, tailnet.status.value)
    }

    @Test
    fun failedInterfaceListGivesGoTheLastGoodOne() = runTest {
        var fail = false
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler)) {
            if (fail) throw java.net.SocketException("getifaddrs failed") else """[{"name":"wlan0"}]"""
        }
        tailnet.start()
        assertEquals("""[{"name":"wlan0"}]""", node.host.interfaces())
        fail = true
        assertEquals("""[{"name":"wlan0"}]""", node.host.interfaces())
    }

    @Test
    fun failedFirstInterfaceListIsEmpty() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler)) { throw IllegalStateException() }
        tailnet.start()
        assertEquals("[]", node.host.interfaces())
    }

    @Test
    fun badStatusIsIgnored() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        tailnet.start()
        node.host.onStatus("not json")
        assertEquals(TailnetStatus.Starting, tailnet.status.value)
    }

    @Test
    fun clientBuilderRoutesThroughTheTailnet() = runTest {
        val tailnet = tailnet(UnconfinedTestDispatcher(testScheduler))
        val client = tailnet.clientBuilder(OkHttpClient()).build()
        assertSame(tailnet.dns, client.dns)
        assertSame(tailnet.socketFactory, client.socketFactory)
        assertEquals(Proxy.NO_PROXY, client.proxy)
    }
}
