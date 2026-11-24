package de.uditkaro.rax.ui.hosts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.connection.LocalNetwork
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.data.SshSignIn
import de.uditkaro.rax.data.SshStore
import de.uditkaro.rax.rex.Problem
import de.uditkaro.rax.rex.RexClient
import de.uditkaro.rax.rex.RexEndpoint
import de.uditkaro.rax.rex.SshFailure
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.SystemActions
import de.uditkaro.rax.ui.home.addressProblem
import de.uditkaro.rax.ui.terminal.rememberTerminalFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HostEditorRoute(graph: AppGraph, system: SystemActions, key: Screen.HostEditor, onClose: () -> Unit) {
    val context = LocalContext.current
    val hosts by graph.connections.hosts.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val states by graph.connections.states.collectAsStateWithLifecycle()
    val asked by graph.permissions.asked.collectAsStateWithLifecycle()
    val list = hosts ?: return
    val host = key.hostId?.let { id -> list.firstOrNull { it.id == id } ?: return }
    val savedSecrets by produceState<SshStore.Saved?>(null, host?.id) {
        value = host?.let { graph.sshStore.saved(it.id) } ?: SshStore.Saved(privateKey = false, password = false)
    }
    val secrets = savedSecrets ?: return
    val mono = rememberTerminalFontFamily(graph, settings?.fontId ?: return)
    val r = context.resources
    val state = host?.let { states[it.id] }
    val changedHostKey = when (val link = state?.link) {
        is RexClient.State.Down -> link.problem
        is RexClient.State.Connecting -> link.lastProblem
        else -> null
    }.let { it as? Problem.Ssh }?.takeIf { it.reason == SshFailure.Reason.HostKeyChanged }?.hostKey
    val ssh = remember(secrets, changedHostKey, host) {
        SshEditing(
            phoneKey = { withContext(Dispatchers.IO) { graph.phoneKey.authorizedKey() } },
            hasPrivateKey = secrets.privateKey,
            hasPassword = secrets.password,
            changedHostKey = changedHostKey,
            onTrust = {
                if (host != null && changedHostKey != null) {
                    graph.scope.launch {
                        graph.sshStore.saveHostKey(host.id, changedHostKey)
                        graph.retry(host)
                    }
                }
            },
        )
    }

    HostEditorScreen(
        host = host ?: key.name?.let { Host(name = it, address = key.address.orEmpty(), tailnet = key.tailnet) },
        isNew = host == null,
        server = state?.takeIf { it.ready }?.let { st ->
            val version = st.version?.rexVersion ?: return@let null
            ServerSummary(version, newerThanRax = (st.welcome?.serverRevision ?: 0) > RexClient.API_REVISION)
        },
        mono = mono,
        describe = { address, tailnet ->
            when (val parsed = RexEndpoint.parse(address, tailnet)) {
                is RexEndpoint.Companion.Parsed.Ok -> {
                    val e = parsed.endpoint
                    val target = e.ssh
                    HostCheck.Ok(
                        when {
                            target != null -> r.getString(
                                if (tailnet) R.string.host_summary_ssh_tailnet else R.string.host_summary_ssh,
                                target.user,
                                target.address,
                            )
                            tailnet -> r.getString(R.string.host_summary_tailnet, e.display)
                            e.secure -> r.getString(R.string.host_summary_tls, e.display)
                            else -> r.getString(R.string.host_summary_plain, e.display)
                        },
                    )
                }
                is RexEndpoint.Companion.Parsed.Invalid ->
                    if (parsed.reason == RexEndpoint.Companion.Reason.Empty) HostCheck.Ok("")
                    else HostCheck.Invalid(addressProblem(r, parsed.reason))
            }
        },
        ssh = ssh,
        askCamera = system.askCamera,
        onSave = { saved, typed ->
            val isSsh = RexEndpoint.isSsh(saved.address)
            graph.scope.launch {
                // The secrets go first, because saving the host can start its connection at once.
                when {
                    !isSsh -> graph.sshStore.forget(saved.id)
                    saved.sshSignIn == SshSignIn.PhoneKey -> graph.sshStore.forgetSecrets(saved.id)
                    saved.sshSignIn == SshSignIn.OwnKey -> typed.privateKey?.let { graph.sshStore.saveOwnKey(saved.id, it, typed.passphrase) }
                    else -> typed.password?.let { graph.sshStore.savePassword(saved.id, it) }
                }
                graph.hostStore.save(saved)
                // A host that failed to sign in tries again with what was just saved.
                if (isSsh && host != null) graph.retry(saved)
            }
            // Asked here, where the user has just chosen a computer on the local network, and only once;
            // after that, Home offers it on the hosts that need it.
            if (!saved.tailnet && !graph.localNetwork.granted.value && LocalNetwork.PERMISSION !in asked && mayBeLocal(graph, saved)) {
                system.askLocalNetwork()
            }
            onClose()
        },
        onDelete = { deleted ->
            graph.scope.launch {
                graph.hostStore.delete(deleted.id)
                graph.sshStore.forget(deleted.id)
            }
            onClose()
        },
        onClose = onClose,
    )
}

/** Whether [host] can be on the local network. A name that was not looked up yet can be. */
private fun mayBeLocal(graph: AppGraph, host: Host): Boolean {
    val name = (RexEndpoint.parse(host.address, host.tailnet) as? RexEndpoint.Companion.Parsed.Ok)?.endpoint?.host ?: return false
    return LocalNetwork.covers(name) ?: graph.localNetwork.names.value[name] ?: true
}
