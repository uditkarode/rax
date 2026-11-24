package de.uditkaro.rax.ui.hosts

import de.uditkaro.rax.rex.RexEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAddressTest {
    @Test
    fun tailnetAddressesAreRecognised() {
        assertTrue(looksLikeTailnet("studio-rex.tail1234.ts.net"))
        assertTrue(looksLikeTailnet("100.101.102.103:7297"))
        assertTrue(looksLikeTailnet("fd7a:115c:a1e0::5"))
        assertTrue(looksLikeTailnet("[fd7a:115c:a1e0:ab12::1]:7297"))
        assertFalse(looksLikeTailnet("192.168.1.20"))
        assertFalse(looksLikeTailnet("[fd00::5]"))
    }

    @Test
    fun defaultNames() {
        assertEquals("studio", defaultName("studio.local:7297"))
        assertEquals("Studio", defaultName("https://Studio.example.com/api/control"))
        assertEquals("192.168.1.20", defaultName("192.168.1.20:7297"))
        assertEquals("fe80::1", defaultName("[fe80::1]:7297"))
        assertEquals("fd00::5", defaultName("fd00::5"))
        assertEquals("", defaultName(""))
    }

    @Test
    fun sshAddressesGiveTheirHost() {
        assertTrue(RexEndpoint.isSsh(" SSH://me@devbox"))
        assertFalse(RexEndpoint.isSsh("devbox"))
        assertEquals("devbox", defaultName("ssh://me@devbox.example.com:2222"))
        assertTrue(looksLikeTailnet("ssh://me@devbox.tail1234.ts.net"))
    }

    @Test
    fun aPairingCodeGivesTheAddressToSave() {
        // What rex server tailscale pair puts in its code.
        assertEquals("kuro-rex.tail1234.ts.net", pairingAddress("https://kuro-rex.tail1234.ts.net"))
        assertEquals("kuro-rex.tail1234.ts.net", pairingAddress("https://kuro-rex.tail1234.ts.net/\n"))
        assertEquals("http://192.168.1.20:7297", pairingAddress("http://192.168.1.20:7297"))
        assertEquals("ssh://me@devbox", pairingAddress("ssh://me@devbox"))
        assertNull(pairingAddress("hello"))
        assertNull(pairingAddress("mailto://x"))
        assertEquals("kuro", rexDeviceName(defaultName("kuro-rex.tail1234.ts.net")))
        assertEquals("rex", rexDeviceName("rex"))
    }
}
