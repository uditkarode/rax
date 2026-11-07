package de.uditkaro.rax.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.SystemActions
import de.uditkaro.rax.ui.tailnet.rememberTailnetSignIn
import de.uditkaro.rax.ui.terminal.rememberTerminalFontFamily
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(graph: AppGraph, system: SystemActions, open: (Screen) -> Unit) {
    val context = LocalContext.current
    val hosts by graph.connections.hosts.collectAsStateWithLifecycle()
    val states by graph.connections.states.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val tailnet by graph.tailnet.status.collectAsStateWithLifecycle()
    val localNetwork by graph.localNetwork.granted.collectAsStateWithLifecycle()
    val signIn = rememberTailnetSignIn(graph)
    val mono = rememberTerminalFontFamily(graph, settings?.fontId ?: return)
    val list = hosts ?: return
    val access = Access(tailnet, localNetwork)
    val home = remember(list, states, access) { buildHome(context.resources, list, states, access) }

    HomeScreen(
        state = home,
        mono = mono,
        actions = HomeActions(
            openTab = { tab ->
                open(Screen.Terminal(tab.hostId, tab.sessionId, tab.tabId, tab.paneId))
            },
            addHost = { open(Screen.HostEditor(null)) },
            editHost = { open(Screen.HostEditor(it.id)) },
            connect = { host ->
                if (host.tailnet) graph.scope.launch { graph.tailnet.start() }
                graph.connections.retry(host.id)
            },
            newSession = { host ->
                val rex = graph.connections.get(host.id)?.rex ?: return@HomeActions
                graph.scope.launch {
                    val (sessionId, windowId, blockId) = runCatching { rex.createSession() }.getOrNull() ?: return@launch
                    if (windowId != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            open(Screen.Terminal(host.id, sessionId, windowId, blockId))
                        }
                    }
                }
            },
            allow = { need ->
                when (need) {
                    Need.TailscaleSignIn -> signIn()
                    Need.LocalNetwork -> system.askLocalNetwork()
                }
            },
            settings = { open(Screen.Settings) },
        ),
    )
}
