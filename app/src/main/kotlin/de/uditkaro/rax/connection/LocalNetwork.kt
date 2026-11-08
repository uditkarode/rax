package de.uditkaro.rax.connection

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Dns

/**
 * Whether Rax may reach computers on the local network. From Android 17, an app that targets it
 * needs the user's permission for this; on earlier versions the permission is always there.
 */
class LocalNetwork(private val app: Application) {
    private val _granted = MutableStateFlow(check())
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    private val _names = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** For each host name that [dns] looked up, whether it points into the local network. */
    val names: StateFlow<Map<String, Boolean>> = _names.asStateFlow()

    /** The system's resolver. It notes in [names] where each name points. */
    val dns = Dns { hostname ->
        Dns.SYSTEM.lookup(hostname).also { addresses ->
            val local = addresses.any(::isLocal)
            _names.update { if (it[hostname] == local) it else it + (hostname to local) }
        }
    }

    /** Reads the permission again, after a request or a return from system settings. */
    fun refresh() {
        _granted.value = check()
    }

    private fun check() = !required || app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        val required = Build.VERSION.SDK_INT >= 37

        /**
         * Whether [host], as a URL has it, is on the local network: a private or link-local IP, a
         * `.local` name, or a name without a dot, which only a local DNS server can answer. Null for
         * any other name, where only a lookup tells.
         */
        fun covers(host: String): Boolean? {
            val h = host.lowercase()
            if (':' in h) {
                val first = h.substringBefore(':').toIntOrNull(16) ?: 0
                // fe80::/10 is link-local and fc00::/7 holds the unique local addresses.
                return (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00
            }
            ipv4(h)?.let { return isLocal(it) }
            return when {
                h == "localhost" -> false
                h.endsWith(".local") || '.' !in h -> true
                else -> null
            }
        }

        fun isLocal(address: InetAddress): Boolean =
            address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)

        /** [host] as an IPv4 address, read without a DNS lookup. */
        private fun ipv4(host: String): InetAddress? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val bytes = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 }?.toByte() ?: return null }
            return InetAddress.getByAddress(bytes.toByteArray())
        }
    }
}
