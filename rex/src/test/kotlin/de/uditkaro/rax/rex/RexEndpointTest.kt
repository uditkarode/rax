package de.uditkaro.rax.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RexEndpointTest {
    private fun url(input: String, tailnet: Boolean = false) =
        (RexEndpoint.parse(input, tailnet) as RexEndpoint.Companion.Parsed.Ok).endpoint.controlUrl

    @Test
    fun bareLanAddressUsesTheDefaultPort() {
        assertEquals("ws://192.168.1.20:7297/api/control", url("192.168.1.20"))
        assertEquals("ws://studio.local:7297/api/control", url("studio.local"))
    }

    @Test
    fun explicitPortKeepsPlainHttpUnless443() {
        assertEquals("ws://10.0.2.2:7297/api/control", url("10.0.2.2:7297"))
        assertEquals("wss://box.example.com/api/control", url("box.example.com:443"))
    }

    @Test
    fun tailnetNamesUseHttps() {
        assertEquals("wss://kuro-rex.tail1234.ts.net/api/control", url("kuro-rex.tail1234.ts.net"))
        assertEquals("wss://kuro-rex/api/control", url("kuro-rex", tailnet = true))
    }

    @Test
    fun urlsAreUsedAsGivenWithoutTheirPath() {
        assertEquals("ws://host:9000/api/control", url("ws://host:9000/api/control"))
        assertEquals("wss://host/api/control", url("https://host/"))
        assertEquals("ws://host:7297/api/data", (RexEndpoint.parse("http://host:7297", false) as RexEndpoint.Companion.Parsed.Ok).endpoint.dataUrl)
    }

    @Test
    fun ipv6() {
        assertEquals("ws://[fd7a:115c:a1e0::1]:7297/api/control", url("fd7a:115c:a1e0::1"))
        assertEquals("ws://[::1]:9000/api/control", url("[::1]:9000"))
    }

    @Test
    fun rejectsNonsense() {
        assertTrue(RexEndpoint.parse("", false) is RexEndpoint.Companion.Parsed.Invalid)
        assertTrue(RexEndpoint.parse("ftp://host", false) is RexEndpoint.Companion.Parsed.Invalid)
        assertTrue(RexEndpoint.parse("http://", false) is RexEndpoint.Companion.Parsed.Invalid)
    }
}
