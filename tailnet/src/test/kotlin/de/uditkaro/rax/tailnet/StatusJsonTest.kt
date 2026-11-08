package de.uditkaro.rax.tailnet

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusJsonTest {
    @Test
    fun running() {
        val state = parseStatus(
            """
            {"state":"Running","tailnet":"user@example.com",
             "self":{"dnsName":"rax-pixel-9.tail1234.ts.net","hostName":"rax-pixel-9","os":"android",
                     "online":true,"ips":["100.101.102.103","fd7a:115c:a1e0::1"]},
             "peers":[{"dnsName":"desktop.tail1234.ts.net","hostName":"desktop","os":"linux","online":true,
                       "ips":["100.64.0.2"]},
                      {"dnsName":"laptop.tail1234.ts.net","hostName":"laptop","online":false,"ips":[]}],
             "someNewField":1}
            """,
        )
        assertEquals(
            TailnetStatus.Running(
                tailnetName = "user@example.com",
                dnsName = "rax-pixel-9.tail1234.ts.net",
                addresses = listOf(InetAddress.getByName("100.101.102.103"), InetAddress.getByName("fd7a:115c:a1e0::1")),
            ),
            state.status,
        )
        assertEquals(
            listOf(
                TailnetPeer("desktop.tail1234.ts.net", "desktop", "linux", true, listOf(InetAddress.getByName("100.64.0.2"))),
                TailnetPeer("laptop.tail1234.ts.net", "laptop", null, false, emptyList()),
            ),
            state.peers,
        )
    }

    @Test
    fun needsLoginWithUrl() {
        val state = parseStatus("""{"state":"NeedsLogin","authURL":"https://login.tailscale.com/a/abc"}""")
        assertEquals(TailnetStatus.NeedsLogin("https://login.tailscale.com/a/abc"), state.status)
        assertEquals(emptyList<TailnetPeer>(), state.peers)
    }

    @Test
    fun needsLoginBeforeUrlIsStarting() {
        assertEquals(TailnetStatus.Starting, parseStatus("""{"state":"NeedsLogin"}""").status)
    }

    @Test
    fun otherStates() {
        assertEquals(TailnetStatus.NeedsApproval, parseStatus("""{"state":"NeedsMachineAuth"}""").status)
        assertEquals(TailnetStatus.Starting, parseStatus("""{"state":"Starting"}""").status)
        assertEquals(TailnetStatus.Starting, parseStatus("""{"state":"NoState"}""").status)
        assertEquals(TailnetStatus.Stopped, parseStatus("""{"state":"Stopped"}""").status)
    }

    @Test
    fun errorWinsUnlessRunning() {
        assertEquals(
            TailnetStatus.Error("invalid key"),
            parseStatus("""{"state":"NeedsLogin","authURL":"https://x","error":"invalid key"}""").status,
        )
        assertEquals(
            TailnetStatus.Running("t", "me.t.ts.net", emptyList()),
            parseStatus("""{"state":"Running","tailnet":"t","self":{"dnsName":"me.t.ts.net"},"error":"old"}""").status,
        )
    }

    @Test
    fun loggingRequired() {
        assertEquals(
            TailnetStatus.Error("tailnet requires logging", loggingRequired = true),
            parseStatus("""{"state":"Stopped","error":"tailnet requires logging","loggingRequired":true}""").status,
        )
        assertEquals(TailnetStatus.Error("boom"), parseStatus("""{"state":"Starting","error":"boom"}""").status)
    }

    @Test
    fun peersOnlyWhileRunning() {
        val state = parseStatus("""{"state":"Starting","peers":[{"dnsName":"a.t.ts.net"}]}""")
        assertEquals(emptyList<TailnetPeer>(), state.peers)
    }
}
