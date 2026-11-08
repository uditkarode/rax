package de.uditkaro.rax.ui.home

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.SystemActions
import de.uditkaro.rax.ui.showActionFailed
import de.uditkaro.rax.ui.tailnet.rememberTailnetSignIn
import de.uditkaro.rax.ui.terminal.rememberTerminalFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(graph: AppGraph, system: SystemActions, open: (Screen) -> Unit) {
    val context = LocalContext.current
    val resources = context.resources
    val settings by graph.settings.collectAsStateWithLifecycle()
    val signIn = rememberTailnetSignIn(graph)
    val homes = remember(graph, resources) { homeStates(graph, resources) }
    val built by homes.collectAsStateWithLifecycle(initialValue = remember(homes) { homeNow(graph, resources) })
    val mono = rememberTerminalFontFamily(graph, settings?.fontId ?: return)
    val home = built ?: return

    val currentOpen by rememberUpdatedState(open)
    // Tied to Home being on screen, so a reply that comes after the user left opens nothing.
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(emptySet<String>()) }
    val actions = remember(graph, system, signIn, scope) {
        HomeActions(
            openTab = { tab ->
                currentOpen(Screen.Terminal(tab.hostId, tab.sessionId, tab.tabId, tab.paneId))
            },
            addHost = { currentOpen(Screen.HostEditor(null)) },
            editHost = { currentOpen(Screen.HostEditor(it.id)) },
            connect = { host ->
                if (host.tailnet) graph.scope.launch { graph.tailnet.retry() }
                graph.connections.retry(host.id)
            },
            newSession = newSession@{ host ->
                val rex = graph.connections.get(host.id)?.rex ?: return@newSession
                if (host.id in creating) return@newSession
                creating = creating + host.id
                scope.launch {
                    try {
                        val (sessionId, windowId, blockId) = rex.createSession()
                        if (windowId != null) currentOpen(Screen.Terminal(host.id, sessionId, windowId, blockId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        showActionFailed(context, e)
                    } finally {
                        creating = creating - host.id
                    }
                }
            },
            allow = { need ->
                when (need) {
                    Need.TailscaleSignIn -> signIn()
                    Need.LocalNetwork -> system.askLocalNetwork()
                }
            },
            settings = { currentOpen(Screen.Settings) },
        )
    }

    HomeScreen(state = home, mono = mono, actions = actions)
}

/** Home as the hosts and their connections change, built off the main thread. Null until the hosts are read. */
private fun homeStates(graph: AppGraph, r: Resources): Flow<HomeState?> =
    combine(
        graph.connections.hosts,
        graph.connections.states,
        graph.tailnet.status,
        graph.localNetwork.granted,
        graph.localNetwork.names,
    ) { hosts, states, tailnet, local, names ->
        hosts?.let { buildHome(r, it, states, Access(tailnet, local, names)) }
    }.flowOn(Dispatchers.Default).conflate()

/** Home as it is now, for the first frame. */
private fun homeNow(graph: AppGraph, r: Resources): HomeState? {
    val hosts = graph.connections.hosts.value ?: return null
    val access = Access(graph.tailnet.status.value, graph.localNetwork.granted.value, graph.localNetwork.names.value)
    return buildHome(r, hosts, graph.connections.states.value, access)
}
