package de.uditkaro.rax.tailnet

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * Resolves names the way a connection through the node does: MagicDNS names
 * (including short names such as `desktop`) and the tailnet's DNS records
 * first, then the system resolver.
 */
internal class TailnetDns(private val node: Node) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val result = try {
            node.lookup(hostname)
        } catch (e: Exception) {
            throw UnknownHostException("$hostname: ${e.message}").apply { initCause(e) }
        }
        val addresses = result.split(' ').filter { it.isNotEmpty() }.map {
            // Keep the name on the address, as the system resolver does.
            InetAddress.getByAddress(hostname, parseIp(it).address)
        }
        if (addresses.isEmpty()) throw UnknownHostException(hostname)
        return addresses
    }
}
