package de.uditkaro.rax.tailnet

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetDnsTest {
    private val node = FakeNode()
    private val dns = TailnetDns(node)

    @Test
    fun returnsAddressesWithTheName() {
        node.lookups = { name ->
            assertEquals("desktop", name)
            "100.64.0.2 fd7a:115c:a1e0::2"
        }
        val addresses = dns.lookup("desktop")
        assertEquals(2, addresses.size)
        assertTrue(addresses[0] is Inet4Address)
        assertArrayEquals(byteArrayOf(100, 64, 0, 2), addresses[0].address)
        assertTrue(addresses[1] is Inet6Address)
        addresses.forEach { assertEquals("desktop", it.hostName) }
    }

    @Test
    fun nodeErrorIsUnknownHost() {
        node.lookups = { throw Exception("the tailnet is not connected") }
        val e = assertThrows(UnknownHostException::class.java) { dns.lookup("desktop.tail1234.ts.net") }
        assertEquals("desktop.tail1234.ts.net: the tailnet is not connected", e.message)
    }

    @Test
    fun noAddressesIsUnknownHost() {
        node.lookups = { "" }
        assertThrows(UnknownHostException::class.java) { dns.lookup("desktop") }
    }
}
