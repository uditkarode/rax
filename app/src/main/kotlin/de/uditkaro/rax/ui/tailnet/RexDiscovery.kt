package de.uditkaro.rax.ui.tailnet

import de.uditkaro.rax.tailnet.TailnetPeer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A computer on the tailnet that answered as Rex, and the address to save for it. */
data class RexServer(val name: String, val address: String)

/**
 * Asks each computer whether Rex listens there: on HTTPS, where Rex's built-in Tailscale serves,
 * and on port 7297, where Rex listens through the computer's own Tailscale. Rex answers a plain
 * GET of its control endpoint with 426, since that endpoint only takes WebSocket upgrades.
 */
suspend fun findRexServers(tailnetHttp: OkHttpClient, peers: List<TailnetPeer>): List<RexServer> = coroutineScope {
    val probe = tailnetHttp.newBuilder().callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).build()
    peers.map { peer ->
        async {
            val host = peer.dnsName.trimEnd('.')
            val https = async { answersAsRex(probe, "https://$host/api/control") }
            val lan = async { answersAsRex(probe, "http://$host:$REX_PORT/api/control") }
            when {
                https.await() -> RexServer(displayName(peer.hostName), host)
                lan.await() -> RexServer(displayName(peer.hostName), "$host:$REX_PORT")
                else -> null
            }
        }
    }.awaitAll().filterNotNull().sortedBy { it.name.lowercase() }
}

private suspend fun answersAsRex(client: OkHttpClient, url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.code == 426 } }.getOrDefault(false)
}

/** Rex names its own tailnet device after the computer, as `rex` or `studio-rex`. */
private fun displayName(hostName: String): String =
    hostName.removeSuffix("-rex").removePrefix("rex-").ifEmpty { hostName }

private const val REX_PORT = 7297
private const val PROBE_TIMEOUT_S = 5L
