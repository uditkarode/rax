package de.uditkaro.rax.ui.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
