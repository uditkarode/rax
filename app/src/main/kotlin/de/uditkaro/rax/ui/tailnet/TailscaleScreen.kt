package de.uditkaro.rax.ui.tailnet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.tailnet.TailnetStatus
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.components.ActionRow
import de.uditkaro.rax.ui.components.ConfirmDialog
import de.uditkaro.rax.ui.components.Footnote
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.RaxIcon
import de.uditkaro.rax.ui.components.RowBase
import de.uditkaro.rax.ui.components.SectionHeader
import de.uditkaro.rax.ui.terminal.rememberTerminalFontFamily
import kotlinx.coroutines.launch

/** Rax's own device on the user's tailnet: signing in and out, and the Rex servers it can reach there. */
@Composable
fun TailscaleScreen(graph: AppGraph, onBack: () -> Unit, open: (Screen) -> Unit) {
    val status by graph.tailnet.status.collectAsStateWithLifecycle()
    val peers by graph.tailnet.peers.collectAsStateWithLifecycle()
    val hosts by graph.connections.hosts.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val mono = rememberTerminalFontFamily(graph, settings?.fontId ?: return)
    val signIn = rememberTailnetSignIn(graph)
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { graph.tailnet.start() }

    // Which computers answer as Rex, probed again when a computer comes online or goes away.
    val online = peers.filter { it.online && it.os?.lowercase() !in phoneSystems }
    val onlineKey = online.map { it.dnsName }.sorted()
    var rex by remember { mutableStateOf<List<RexServer>?>(null) }
    LaunchedEffect(onlineKey, status is TailnetStatus.Running) {
        if (status !is TailnetStatus.Running) return@LaunchedEffect
        rex = findRexServers(graph.tailnetHttp, online)
    }

    LargeTitleScreen(stringResource(R.string.tailscale_title), onBack) {
        when (val s = status) {
            TailnetStatus.Stopped, TailnetStatus.Starting -> item(key = "starting") {
                Group(Modifier.padding(top = 16.dp)) { row { InfoRow(stringResource(R.string.tailscale_starting)) } }
            }
            is TailnetStatus.NeedsLogin -> item(key = "signed-out") {
                Group(Modifier.padding(top = 16.dp)) { row { ActionRow(stringResource(R.string.tailscale_sign_in), signIn) } }
                Footnote(stringResource(R.string.tailscale_about))
            }
            TailnetStatus.NeedsApproval -> item(key = "approval") {
                Group(Modifier.padding(top = 16.dp)) { row { InfoRow(stringResource(R.string.tailscale_waiting_approval)) } }
                Footnote(stringResource(R.string.tailscale_approval_footnote))
            }
            is TailnetStatus.Error -> item(key = "error") {
                Group(Modifier.padding(top = 16.dp)) {
                    row { ActionRow(stringResource(R.string.link_retry), { graph.scope.launch { graph.tailnet.start() } }) }
                }
                Footnote(s.message)
            }
            is TailnetStatus.Running -> {
                item(key = "account") {
                    Group(Modifier.padding(top = 16.dp)) {
                        row { InfoRow(stringResource(R.string.tailscale_tailnet), s.tailnetName) }
                        row { InfoRow(stringResource(R.string.tailscale_this_phone), s.dnsName.trimEnd('.').substringBefore('.')) }
                    }
                }
                item(key = "rex") {
                    SectionHeader(stringResource(R.string.tailscale_rex))
                    when (val found = rex) {
                        null -> Group { row { InfoRow(stringResource(R.string.tailscale_looking)) } }
                        else -> if (found.isEmpty()) {
                            Group { row { InfoRow(stringResource(R.string.tailscale_no_rex)) } }
                            Footnote(stringResource(R.string.tailscale_no_rex_footnote))
                        } else {
                            Group {
                                found.forEach { server ->
                                    val saved = hosts.orEmpty().firstOrNull { it.tailnet && sameAddress(it.address, server.address) }
                                    row {
                                        ServerRow(server, mono, added = saved != null) {
                                            open(
                                                if (saved != null) Screen.HostEditor(saved.id)
                                                else Screen.HostEditor(null, name = server.name, address = server.address, tailnet = true),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "sign-out") {
                    Group(Modifier.padding(top = 24.dp)) {
                        row { ActionRow(stringResource(R.string.tailscale_sign_out), { confirmSignOut = true }, destructive = true) }
                    }
                }
            }
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.tailscale_sign_out_title),
            body = stringResource(R.string.tailscale_sign_out_body),
            action = stringResource(R.string.tailscale_sign_out),
            onConfirm = {
                confirmSignOut = false
                graph.scope.launch { runCatching { graph.tailnet.logout() } }
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

private val phoneSystems = setOf("ios", "android", "tvos")

@Composable
private fun InfoRow(title: String, value: String? = null) {
    RowBase(onClick = null) {
        Text(title, Modifier.weight(1f), style = RaxType.body, color = if (value == null) Rax.colors.textSecondary else Rax.colors.text)
        if (value != null) {
            Text(value, style = RaxType.body, color = Rax.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun sameAddress(a: String, b: String) = a.trim().trimEnd('.').equals(b.trim().trimEnd('.'), ignoreCase = true)

@Composable
private fun ServerRow(server: RexServer, mono: FontFamily, added: Boolean, onClick: () -> Unit) {
    val c = Rax.colors
    RowBase(onClick) {
        Column(Modifier.weight(1f)) {
            Text(server.name, style = RaxType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                server.address,
                style = RaxType.footnote.copy(fontFamily = mono),
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (added) {
            Text(stringResource(R.string.tailscale_added), style = RaxType.body, color = c.textSecondary)
            RaxIcon(R.drawable.ic_chevron_right, null, tint = c.textTertiary, size = 20.dp)
        } else {
            RaxIcon(R.drawable.ic_add, stringResource(R.string.add_host), tint = c.link, size = 22.dp)
        }
    }
}
