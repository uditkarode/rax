package de.uditkaro.rax.connection

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun privateAndLinkLocalAddressesAreLocal() {
        listOf("10.0.2.2", "172.16.0.1", "192.168.1.20", "169.254.3.4", "fe80::1", "fd00::5", "fc12::1").forEach {
            assertEquals(it, true, LocalNetwork.covers(it))
        }
    }

    @Test
    fun otherAddressesAreNot() {
        listOf("203.0.113.5", "100.101.102.103", "127.0.0.1", "2001:db8::1", "::1", "localhost").forEach {
            assertEquals(it, false, LocalNetwork.covers(it))
        }
    }

    @Test
    fun namesOnlyALocalServerAnswersAreLocal() {
        assertEquals(true, LocalNetwork.covers("studio.local"))
        assertEquals(true, LocalNetwork.covers("studio"))
        assertEquals(null, LocalNetwork.covers("rex.example.com"))
        assertEquals(null, LocalNetwork.covers("999.1.1.1"))
    }

    @Test
    fun lookedUpAddresses() {
        assertTrue(LocalNetwork.isLocal(InetAddress.getByName("192.168.1.20")))
        assertTrue(LocalNetwork.isLocal(InetAddress.getByName("fd7a:115c:a1e0::5")))
        assertFalse(LocalNetwork.isLocal(InetAddress.getByName("93.184.216.34")))
    }
}
