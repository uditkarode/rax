package de.uditkaro.rax.tailnet

import java.net.NetworkInterface
import java.net.SocketException
import kotlinx.serialization.Serializable

/** One interface for Go (interfaceJSON in go/interfaces.go). */
@Serializable
internal data class InterfaceJson(
    val name: String,
    val index: Int,
    val mtu: Int,
    val up: Boolean,
    val broadcast: Boolean,
    val loopback: Boolean,
    val pointToPoint: Boolean,
    val multicast: Boolean,
    /** `ip/prefixLength`. */
    val addrs: List<String>,
)

/**
 * Lists the device's interfaces for the node. Go's own interface listing
 * fails on Android because apps may not dump netlink routes, but
 * [NetworkInterface] still works.
 */
internal fun interfacesJson(): String {
    val list = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { nif ->
        try {
            InterfaceJson(
                name = nif.name,
                index = nif.index,
                mtu = nif.mtu,
                up = nif.isUp,
                broadcast = nif.interfaceAddresses.any { it.broadcast != null },
                loopback = nif.isLoopback,
                pointToPoint = nif.isPointToPoint,
                multicast = nif.supportsMulticast(),
                addrs = nif.interfaceAddresses.mapNotNull { ia ->
                    ia.address?.hostAddress?.let { "$it/${ia.networkPrefixLength}" }
                },
            )
        } catch (_: SocketException) {
            // The interface went away while we were reading it.
            null
        }
    }
    return json.encodeToString(list)
}
