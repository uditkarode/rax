package de.uditkaro.rax.ui.tailnet

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
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
import de.uditkaro.rax.ui.components.ToggleRow
import de.uditkaro.rax.ui.terminal.rememberTerminalFontFamily
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * How Rax reaches the tailnet. With its own device on the tailnet: signing in and out, and the Rex
 * servers it can reach there. Or through the Tailscale app on the phone.
 */
@Composable
fun TailscaleScreen(graph: AppGraph, onBack: () -> Unit, open: (Screen) -> Unit) {
    val status by graph.tailnet.status.collectAsStateWithLifecycle()
    val peers by graph.tailnet.peers.collectAsStateWithLifecycle()
    val hosts by graph.connections.hosts.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val mono = rememberTerminalFontFamily(graph, settings?.fontId ?: return)
    val viaApp = settings?.tailscaleApp == true
    val signIn = rememberTailnetSignIn(graph)
    val context = LocalContext.current.applicationContext
    var confirmSignOut by remember { mutableStateOf(false) }
    val signingOut by graph.signingOut.collectAsStateWithLifecycle()

    LifecycleStartEffect(graph, viaApp) {
        val release = if (viaApp) null else graph.tailnetKeeper.hold()
        onStopOrDispose { release?.invoke() }
    }

    val discovery = viewModel { RexDiscoveryModel(graph) }
    val answers by discovery.answers.collectAsStateWithLifecycle()
    val online = rexCandidates(peers)
    val servers = online.mapNotNull { answers[it.dnsName] }.sortedBy { it.name.lowercase() }
    val looking = online.any { it.dnsName !in answers }

    LargeTitleScreen(stringResource(R.string.tailscale_title), onBack) {
        item(key = "mode") {
            Group(Modifier.padding(top = 16.dp)) {
                row { ToggleRow(stringResource(R.string.tailscale_use_app), viaApp, { on -> graph.updateSettings { setTailscaleApp(on) } }) }
            }
            if (viaApp) Footnote(stringResource(R.string.tailscale_app_about))
        }
        if (!viaApp) when (val s = status) {
            TailnetStatus.Stopped, TailnetStatus.Starting -> item(key = "starting") {
                Group(Modifier.padding(top = 24.dp)) { row { InfoRow(stringResource(R.string.tailscale_starting)) } }
            }
            is TailnetStatus.NeedsLogin -> item(key = "signed-out") {
                Group(Modifier.padding(top = 24.dp)) { row { ActionRow(stringResource(R.string.tailscale_sign_in), signIn) } }
                Footnote(stringResource(R.string.tailscale_about))
            }
            TailnetStatus.NeedsApproval -> {
                item(key = "approval") {
                    Group(Modifier.padding(top = 24.dp)) { row { InfoRow(stringResource(R.string.tailscale_waiting_approval)) } }
                    Footnote(stringResource(R.string.tailscale_approval_footnote))
                }
                item(key = "sign-out") { SignOutGroup(signingOut) { confirmSignOut = true } }
            }
            is TailnetStatus.Error -> {
                item(key = "error") {
                    Group(Modifier.padding(top = 24.dp)) {
                        row { InfoRow(stringResource(R.string.problem_tailnet_failed)) }
                        // Starting again does not help while the tailnet requires flow logs.
                        if (!s.loggingRequired) {
                            row { ActionRow(stringResource(R.string.link_retry), { graph.scope.launch { graph.tailnet.retry() } }) }
                        }
                    }
                    if (s.loggingRequired) Footnote(stringResource(R.string.tailscale_logging_required))
                }
                item(key = "sign-out") { SignOutGroup(signingOut) { confirmSignOut = true } }
            }
            is TailnetStatus.Running -> {
                item(key = "account") {
                    Group(Modifier.padding(top = 24.dp)) {
                        row { InfoRow(stringResource(R.string.tailscale_tailnet), s.tailnetName) }
                        row { InfoRow(stringResource(R.string.tailscale_this_phone), s.dnsName.trimEnd('.').substringBefore('.')) }
                    }
                }
                item(key = "rex") {
                    SectionHeader(stringResource(R.string.tailscale_rex))
                    when {
                        servers.isNotEmpty() -> Group {
                            servers.forEach { server ->
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
                        looking -> Group { row { InfoRow(stringResource(R.string.tailscale_looking)) } }
                        else -> {
                            Group { row { InfoRow(stringResource(R.string.tailscale_no_rex)) } }
                            Footnote(stringResource(R.string.tailscale_no_rex_footnote))
                        }
                    }
                }
                item(key = "sign-out") { SignOutGroup(signingOut) { confirmSignOut = true } }
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
                // The failure shows as a toast on whatever screen is open by then.
                graph.signOut { Toast.makeText(context, R.string.tailscale_sign_out_failed, Toast.LENGTH_LONG).show() }
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun SignOutGroup(signingOut: Boolean, onClick: () -> Unit) {
    Group(Modifier.padding(top = 24.dp)) {
        row {
            ActionRow(
                stringResource(if (signingOut) R.string.tailscale_signing_out else R.string.tailscale_sign_out),
                onClick,
                destructive = true,
                enabled = !signingOut,
            )
        }
    }
}

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
    RowBase(onClick.takeUnless { server.refused }) {
        Column(Modifier.weight(1f)) {
            Text(server.name, style = RaxType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (server.refused) {
                Text(stringResource(R.string.problem_refused_tailnet), style = RaxType.footnote, color = c.textSecondary)
            } else {
                Text(
                    server.address,
                    style = RaxType.footnote.copy(fontFamily = mono),
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (server.refused) return@RowBase
        if (added) {
            Text(stringResource(R.string.tailscale_added), style = RaxType.body, color = c.textSecondary)
            RaxIcon(R.drawable.ic_chevron_right, null, tint = c.textTertiary, size = 20.dp)
        } else {
            RaxIcon(R.drawable.ic_add, stringResource(R.string.add_host), tint = c.link, size = 22.dp)
        }
    }
}
