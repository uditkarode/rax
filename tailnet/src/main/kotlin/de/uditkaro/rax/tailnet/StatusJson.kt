package de.uditkaro.rax.tailnet

import java.net.InetAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The status JSON from Go (statusJSON in go/status.go). [state] is an ipn.State name. */
@Serializable
internal data class StatusJson(
    val state: String,
    val authURL: String? = null,
    val error: String? = null,
    val loggingRequired: Boolean = false,
    val tailnet: String? = null,
    val self: PeerJson? = null,
    val peers: List<PeerJson> = emptyList(),
)

@Serializable
internal data class PeerJson(
    val dnsName: String,
    val hostName: String = "",
    val os: String? = null,
    val online: Boolean = false,
    val ips: List<String> = emptyList(),
)

internal val json = Json { ignoreUnknownKeys = true }

internal data class NodeState(val status: TailnetStatus, val peers: List<TailnetPeer>)

internal fun parseStatus(text: String): NodeState {
    val s = json.decodeFromString<StatusJson>(text)
    val status = when {
        s.state == "Running" -> TailnetStatus.Running(
            tailnetName = s.tailnet.orEmpty(),
            dnsName = s.self?.dnsName.orEmpty(),
            addresses = s.self?.ips.orEmpty().map(::parseIp),
        )
        !s.error.isNullOrEmpty() -> TailnetStatus.Error(s.error, s.loggingRequired)
        s.state == "NeedsLogin" ->
            // The URL arrives a moment after the state; until then the node is still starting.
            s.authURL?.takeIf { it.isNotEmpty() }?.let(TailnetStatus::NeedsLogin) ?: TailnetStatus.Starting
        s.state == "NeedsMachineAuth" -> TailnetStatus.NeedsApproval
        s.state == "Stopped" -> TailnetStatus.Stopped
        else -> TailnetStatus.Starting
    }
    val peers = if (status is TailnetStatus.Running) s.peers.map { it.toPeer() } else emptyList()
    return NodeState(status, peers)
}

private fun PeerJson.toPeer() = TailnetPeer(
    dnsName = dnsName,
    hostName = hostName,
    os = os?.takeIf { it.isNotEmpty() },
    online = online,
    addresses = ips.map(::parseIp),
)

/** [ip] is always a literal, so this does no DNS lookup. */
internal fun parseIp(ip: String): InetAddress = InetAddress.getByName(ip)
