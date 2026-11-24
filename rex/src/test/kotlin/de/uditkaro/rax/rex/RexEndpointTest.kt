package de.uditkaro.rax.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RexEndpointTest {
    private fun url(input: String, tailnet: Boolean = false) =
        (RexEndpoint.parse(input, tailnet) as RexEndpoint.Companion.Parsed.Ok).endpoint.controlUrl

    private fun reason(input: String, tailnet: Boolean = false) =
        (RexEndpoint.parse(input, tailnet) as RexEndpoint.Companion.Parsed.Invalid).reason

    @Test
    fun bareLanAddressUsesTheDefaultPort() {
        assertEquals("ws://192.168.1.20:7297/api/control", url("192.168.1.20"))
        assertEquals("ws://studio.local:7297/api/control", url("studio.local"))
    }

    @Test
    fun explicitPortKeepsPlainHttpUnless443() {
        assertEquals("ws://10.0.2.2:7297/api/control", url("10.0.2.2:7297"))
        assertEquals("wss://box.example.com/api/control", url("box.example.com:443"))
        assertEquals("ws://studio:7297/api/control", url("studio:7297", tailnet = true))
        assertEquals("ws://100.101.102.103:7297/api/control", url("100.101.102.103:7297", tailnet = true))
    }

    @Test
    fun tailnetNamesUseHttps() {
        assertEquals("wss://kuro-rex.tail1234.ts.net/api/control", url("kuro-rex.tail1234.ts.net"))
        assertEquals("wss://kuro-rex.tail1234.ts.net/api/control", url("kuro-rex.tail1234.ts.net", tailnet = true))
        assertEquals("wss://box.example.com/api/control", url("box.example.com", tailnet = true))
    }

    @Test
    fun tailnetAddressesThatNoCertificateNames() {
        assertEquals(RexEndpoint.Companion.Reason.ShortTailnetName, reason("kuro-rex", tailnet = true))
        assertEquals("ws://100.101.102.103:7297/api/control", url("100.101.102.103", tailnet = true))
        assertEquals("ws://[fd7a:115c:a1e0::5]:7297/api/control", url("fd7a:115c:a1e0::5", tailnet = true))
        assertEquals("ws://[fd7a:115c:a1e0::5]:7297/api/control", url("[fd7a:115c:a1e0::5]", tailnet = true))
    }

    @Test
    fun urlsAreUsedAsGivenWithoutTheirPath() {
        assertEquals("ws://host:9000/api/control", url("ws://host:9000/api/control"))
        assertEquals("wss://host/api/control", url("https://host/"))
        assertEquals("ws://host:7297/api/data", (RexEndpoint.parse("http://host:7297", false) as RexEndpoint.Companion.Parsed.Ok).endpoint.dataUrl)
    }

    @Test
    fun aPathAfterABareHostIsDropped() {
        assertEquals("ws://192.168.1.20:7297/api/control", url("192.168.1.20/api/control"))
        assertEquals("ws://192.168.1.20:8000/api/control", url("192.168.1.20:8000/api/control"))
        assertEquals("ws://100.101.102.103:7297/api/control", url("100.101.102.103:7297/api/control", tailnet = true))
        assertEquals("wss://kuro-rex.tail1234.ts.net/api/control", url("kuro-rex.tail1234.ts.net/api/control"))
        assertEquals("ws://[::1]:9000/api/control", url("[::1]:9000/api/control"))
    }

    @Test
    fun ipv6() {
        assertEquals("ws://[fd7a:115c:a1e0::1]:7297/api/control", url("fd7a:115c:a1e0::1"))
        assertEquals("ws://[::1]:9000/api/control", url("[::1]:9000"))
        assertEquals("ws://[fd00::5]:7297/api/control", url("[fd00::5]"))
        assertEquals("wss://[fd00::5]/api/control", url("[fd00::5]:443"))
    }

    @Test
    fun hostOfWhatWasTyped() {
        assertEquals("Studio.local", RexEndpoint.hostOf("Studio.local:7297"))
        assertEquals("fe80::1", RexEndpoint.hostOf("[fe80::1]:7297"))
        assertEquals("fd7a:115c:a1e0::5", RexEndpoint.hostOf("fd7a:115c:a1e0::5"))
        assertEquals("box.example.com", RexEndpoint.hostOf("https://box.example.com/api/control"))
        assertEquals("100.101.102.103", RexEndpoint.hostOf("100.101.102.103:7297/api/control"))
        assertNull(RexEndpoint.hostOf(""))
    }

    @Test
    fun rejectsNonsense() {
        assertTrue(RexEndpoint.parse("", false) is RexEndpoint.Companion.Parsed.Invalid)
        assertTrue(RexEndpoint.parse("ftp://host", false) is RexEndpoint.Companion.Parsed.Invalid)
        assertTrue(RexEndpoint.parse("http://", false) is RexEndpoint.Companion.Parsed.Invalid)
        assertEquals(RexEndpoint.Companion.Reason.BadAddress, reason("host:abc"))
        assertEquals(RexEndpoint.Companion.Reason.BadAddress, reason("[fd00::5"))
    }

    private fun ssh(input: String, tailnet: Boolean = false) =
        (RexEndpoint.parse(input, tailnet) as RexEndpoint.Companion.Parsed.Ok).endpoint

    @Test
    fun sshAddressesTakeTheFormOfRexS() {
        val e = ssh("ssh://me@devbox")
        assertEquals(RexEndpoint.SshTarget("me", "devbox", 22, null), e.ssh)
        assertEquals("me@devbox", e.display)
        assertEquals("devbox", e.host)

        assertEquals(RexEndpoint.SshTarget("me", "10.0.2.2", 2222, null), ssh("ssh://me@10.0.2.2:2222").ssh)
        assertEquals("me@10.0.2.2:2222", ssh("SSH://me@10.0.2.2:2222/").display)
        assertEquals(
            RexEndpoint.SshTarget("me", "devbox", 22, "/home/me/.local/state/rex/server.sock"),
            ssh("ssh://me@devbox/home/me/.local/state/rex/server.sock").ssh,
        )
        assertEquals(RexEndpoint.SshTarget("me", "fd00::5", 2200, null), ssh("ssh://me@[fd00::5]:2200").ssh)
        assertEquals("me@[fd00::5]:2200", ssh("ssh://me@[fd00::5]:2200").display)
        assertEquals("devbox.tail1234.ts.net", ssh("ssh://me@devbox.tail1234.ts.net", tailnet = true).host)
        assertEquals("devbox", RexEndpoint.hostOf("ssh://me@devbox:2222"))
    }

    @Test
    fun sshAddressesNeedAUserAndAHost() {
        assertEquals(RexEndpoint.Companion.Reason.SshNoUser, reason("ssh://devbox"))
        assertEquals(RexEndpoint.Companion.Reason.BadAddress, reason("ssh://"))
        assertEquals(RexEndpoint.Companion.Reason.BadAddress, reason("ssh://me@"))
        assertEquals(RexEndpoint.Companion.Reason.BadAddress, reason("ssh://me@devbox:0"))
        assertEquals(RexEndpoint.Companion.Reason.BadAddress, reason("ssh://me@devbox:70000"))
    }

    @Test
    fun sshEndpointsDifferByTarget() {
        assertTrue(ssh("ssh://me@devbox") != ssh("ssh://you@devbox"))
        assertEquals(ssh("ssh://me@devbox"), ssh("ssh://me@devbox:22"))
    }
}
