package de.uditkaro.rax.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.Inet4Address

/** Reports changes of the default network: its interface name and IPv4 gateway. */
internal interface NetworkWatcher {
    fun start(onChange: (interfaceName: String, gateway: String) -> Unit)
    fun stop()
}

internal class AndroidNetworkWatcher(context: Context) : NetworkWatcher {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun start(onChange: (interfaceName: String, gateway: String) -> Unit) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Callbacks arrive on one thread, in order.
            private var current: Network? = null

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                current = network
                val gateway = linkProperties.routes
                    .firstOrNull { it.isDefaultRoute && it.hasGateway() && it.gateway is Inet4Address }
                    ?.gateway?.hostAddress
                onChange(linkProperties.interfaceName.orEmpty(), gateway.orEmpty())
            }

            override fun onLost(network: Network) {
                // A loss of an older default can arrive after its replacement.
                if (network != current) return
                current = null
                onChange("", "")
            }
        }
        // The default network is what the node's own sockets use, including when
        // another VPN is active.
        connectivity.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    @Synchronized
    override fun stop() {
        callback?.let(connectivity::unregisterNetworkCallback)
        callback = null
    }
}
