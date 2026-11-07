package de.uditkaro.rax.ui.hosts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.connection.LocalNetwork
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.rex.RexClient
import de.uditkaro.rax.rex.RexEndpoint
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.SystemActions
import de.uditkaro.rax.ui.terminal.rememberTerminalFontFamily
import kotlinx.coroutines.launch

@Composable
fun HostEditorRoute(graph: AppGraph, system: SystemActions, key: Screen.HostEditor, onClose: () -> Unit) {
    val context = LocalContext.current
    val hosts by graph.connections.hosts.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val states by graph.connections.states.collectAsStateWithLifecycle()
    val list = hosts ?: return
    val host = key.hostId?.let { id -> list.firstOrNull { it.id == id } ?: return }
    val mono = rememberTerminalFontFamily(graph, settings?.fontId ?: return)
    val r = context.resources

    HostEditorScreen(
        host = host ?: key.name?.let { Host(name = it, address = key.address.orEmpty(), tailnet = key.tailnet) },
        isNew = host == null,
        server = host?.let { states[it.id] }?.takeIf { it.ready }?.let { st ->
            val version = st.version?.rexVersion ?: return@let null
            ServerSummary(version, newerThanRax = (st.welcome?.serverRevision ?: 0) > RexClient.API_REVISION)
        },
        mono = mono,
        describe = { address, tailnet ->
            when (val parsed = RexEndpoint.parse(address, tailnet)) {
                is RexEndpoint.Companion.Parsed.Ok -> {
                    val e = parsed.endpoint
                    HostCheck.Ok(
                        when {
                            tailnet -> r.getString(R.string.host_summary_tailnet, e.display)
                            e.secure -> r.getString(R.string.host_summary_tls, e.display)
                            else -> r.getString(R.string.host_summary_plain, e.display)
                        },
                    )
                }
                is RexEndpoint.Companion.Parsed.Invalid -> when (parsed.reason) {
                    RexEndpoint.Companion.Reason.Empty -> HostCheck.Ok("")
                    RexEndpoint.Companion.Reason.BadScheme -> HostCheck.Invalid(r.getString(R.string.host_invalid_scheme))
                    RexEndpoint.Companion.Reason.BadAddress -> HostCheck.Invalid(r.getString(R.string.host_invalid_address))
                }
            }
        },
        onSave = { saved ->
            graph.scope.launch { graph.hostStore.save(saved) }
            // Asked here, where the user has just chosen a computer on the local network, and only once;
            // after that, Home offers it on the hosts that need it.
            if (!saved.tailnet && !graph.localNetwork.granted.value && LocalNetwork.PERMISSION !in settings?.askedPermissions.orEmpty()) {
                system.askLocalNetwork()
            }
            onClose()
        },
        onDelete = { deleted ->
            graph.scope.launch { graph.hostStore.delete(deleted.id) }
            onClose()
        },
        onClose = onClose,
    )
}
