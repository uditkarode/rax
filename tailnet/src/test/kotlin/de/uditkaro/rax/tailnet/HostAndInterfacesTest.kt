package de.uditkaro.rax.tailnet

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAndInterfacesTest {
    @Test
    fun hostnames() {
        assertEquals("rax-pixel-9", hostnameFor("Pixel 9"))
        assertEquals("rax-sdk-gphone64-arm64", hostnameFor("sdk_gphone64_arm64"))
        assertEquals("rax-sm-s928b", hostnameFor("SM-S928B"))
        assertEquals("rax", hostnameFor("  "))
        assertEquals(63, hostnameFor("x".repeat(100)).length)
    }

    @Test
    fun nodeEnvironment() {
        val dir = File("/data/user/0/de.uditkaro.rax/no_backup/tailnet")
        val certs = Files.createTempDirectory("cacerts").toFile()
        assertEquals(
            mapOf(
                "TS_LOGS_DIR" to dir.path,
                "HOME" to dir.path,
                "TS_NO_LOGS_NO_SUPPORT" to "true",
                "SSL_CERT_DIR" to certs.path,
            ),
            nodeEnvironment(dir, certs),
        )
        certs.delete()
        assertEquals(setOf("TS_LOGS_DIR", "HOME", "TS_NO_LOGS_NO_SUPPORT"), nodeEnvironment(dir, certs).keys)
    }

    @Test
    fun interfacesIncludeLoopback() {
        val list = json.decodeFromString<List<InterfaceJson>>(interfacesJson())
        val loopback = list.single { it.loopback && it.addrs.isNotEmpty() }
        assertTrue(loopback.up)
        assertTrue(loopback.addrs.toString(), loopback.addrs.any { it == "127.0.0.1/8" })
        for (addr in list.flatMap { it.addrs }) {
            val (ip, bits) = addr.split('/')
            InetAddress.getByName(ip)
            assertTrue(addr, bits.toInt() in 0..128)
        }
    }
}
