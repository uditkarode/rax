package de.uditkaro.rax.ui.tailnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.tailnet.TailnetPeer
import de.uditkaro.rax.tailnet.TailnetStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A computer on the tailnet that answered as Rex, and the address to save for it. */
data class RexServer(
    val name: String,
    val address: String,
    /** Rex answered, but it does not let in the Tailscale account that Rax is signed in with. */
    val refused: Boolean = false,
)

/** The computers on the tailnet that can run Rex: online, and not a phone or a TV. */
fun rexCandidates(peers: List<TailnetPeer>): List<TailnetPeer> =
    peers.filter { it.online && it.os?.lowercase() !in phoneSystems }

private val phoneSystems = setOf("ios", "android", "tvos")

/**
 * Which computers on the tailnet answer as Rex, by MagicDNS name, with null for those that do not.
 * A computer is asked once, and again when it comes back online. The answers stay while the
 * Tailscale screen is in the back stack, so coming back to it from a host asks no one again.
 */
class RexDiscoveryModel(graph: AppGraph) : ViewModel() {
    private val probe = rexProbeClient(graph.tailnetHttp)
    private val _answers = MutableStateFlow<Map<String, RexServer?>>(emptyMap())
    val answers: StateFlow<Map<String, RexServer?>> = _answers.asStateFlow()

    init {
        viewModelScope.launch {
            combine(graph.tailnet.status, graph.tailnet.peers) { status, peers ->
                if (status is TailnetStatus.Running) rexCandidates(peers) else null
            }
                .distinctUntilChanged { a, b -> a?.map { it.dnsName }?.sorted() == b?.map { it.dnsName }?.sorted() }
                .collectLatest { online ->
                    if (online == null) return@collectLatest
                    val names = online.map { it.dnsName }.toSet()
                    _answers.update { it.filterKeys { name -> name in names } }
                    coroutineScope {
                        online.filter { it.dnsName !in _answers.value }.forEach { peer ->
                            launch {
                                val server = findRexServer(probe, peer)
                                _answers.update { it + (peer.dnsName to server) }
                            }
                        }
                    }
                }
        }
    }
}

/** The client for [findRexServer]: [tailnetHttp] with a short time limit. */
fun rexProbeClient(tailnetHttp: OkHttpClient): OkHttpClient =
    tailnetHttp.newBuilder().callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).build()

/**
 * Asks [peer] whether Rex listens there: on HTTPS, where Rex's built-in Tailscale serves, and on
 * port 7297, where Rex listens through the computer's own Tailscale. Rex answers a plain GET of its
 * control endpoint with 426, since that endpoint only takes WebSocket upgrades, and a caller that it
 * does not let in with 403 and a JSON error. Returns null if Rex answers in neither place.
 */
suspend fun findRexServer(probe: OkHttpClient, peer: TailnetPeer): RexServer? = coroutineScope {
    val host = peer.dnsName.trimEnd('.')
    val name = displayName(peer.hostName)
    val https = async { ask(probe, "https://$host/api/control") }
    val lan = async { ask(probe, "http://$host:$REX_PORT/api/control") }
    val secure = https.await()
    if (secure == Answer.Rex) {
        lan.cancel()
        return@coroutineScope RexServer(name, host)
    }
    val plain = lan.await()
    when {
        plain == Answer.Rex -> RexServer(name, "$host:$REX_PORT")
        secure == Answer.Refused -> RexServer(name, host, refused = true)
        plain == Answer.Refused -> RexServer(name, "$host:$REX_PORT", refused = true)
        else -> null
    }
}

private enum class Answer { Rex, Refused, Other }

/** Cancelling the caller cancels the request, which also closes its tailnet connection. */
private suspend fun ask(client: OkHttpClient, url: String): Answer = suspendCancellableCoroutine { cont ->
    val call = client.newCall(Request.Builder().url(url).build())
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resume(Answer.Other)

        override fun onResponse(call: Call, response: Response) {
            val answer = try {
                response.use(::answerOf)
            } catch (_: IOException) {
                Answer.Other
            }
            cont.resume(answer)
        }
    })
}

private fun answerOf(response: Response): Answer = when (response.code) {
    426 -> Answer.Rex
    403 -> if (isErrorJson(response.peekBody(MAX_BODY_BYTES).string())) Answer.Refused else Answer.Other
    else -> Answer.Other
}

private fun isErrorJson(body: String): Boolean =
    runCatching { Json.parseToJsonElement(body).jsonObject["error"] is JsonPrimitive }.getOrDefault(false)

/** Rex names its own tailnet device after the computer, as `rex` or `studio-rex`. */
private fun displayName(hostName: String): String =
    hostName.removeSuffix("-rex").removePrefix("rex-").ifEmpty { hostName }

private const val REX_PORT = 7297
private const val PROBE_TIMEOUT_S = 5L
private const val MAX_BODY_BYTES = 4096L
