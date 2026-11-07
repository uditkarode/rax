package de.uditkaro.rax.ui

import android.app.Application
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.ui.home.HomeRoute
import de.uditkaro.rax.ui.hosts.HostEditorRoute
import de.uditkaro.rax.ui.tailnet.TailscaleScreen
import de.uditkaro.rax.ui.settings.AcknowledgementsScreen
import de.uditkaro.rax.ui.settings.FontScreen
import de.uditkaro.rax.ui.settings.LicenseScreen
import de.uditkaro.rax.ui.settings.SettingsScreen
import de.uditkaro.rax.ui.settings.ThemeScreen
import de.uditkaro.rax.ui.terminal.TerminalScreen
import de.uditkaro.rax.ui.terminal.TerminalViewModel
import kotlinx.serialization.Serializable

sealed interface Screen : NavKey {
    @Serializable data object Home : Screen
    @Serializable data class Terminal(val hostId: String, val sessionId: String, val windowId: String, val blockId: String?) : Screen
    /** Edits [hostId], or adds a host filled in with [name], [address] and [tailnet]. */
    @Serializable data class HostEditor(
        val hostId: String?,
        val name: String? = null,
        val address: String? = null,
        val tailnet: Boolean = false,
    ) : Screen
    @Serializable data object Tailscale : Screen
    @Serializable data object Settings : Screen
    @Serializable data object Theme : Screen
    @Serializable data object Font : Screen
    @Serializable data object Acknowledgements : Screen
    @Serializable data class License(val title: String, val asset: String) : Screen
}

private const val PUSH_MS = 380

@Composable
fun RaxNavigation(graph: AppGraph, system: SystemActions, pendingOpen: Screen.Terminal?, onPendingOpened: () -> Unit) {
    val backStack = rememberNavBackStack(Screen.Home)
    val push: (Screen) -> Unit = { backStack.add(it) }
    val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    if (pendingOpen != null) {
        // A notification asked for a pane: show it on top of Home, replacing whatever was open.
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(pendingOpen)
        onPendingOpened()
    }

    val slide = tween<IntOffset>(PUSH_MS, easing = FastOutSlowInEasing)
    val pushIn = slideInHorizontally(slide) { it }
    val pushOut = slideOutHorizontally(slide) { -it / 4 } + fadeOut(tween(PUSH_MS), targetAlpha = 0.6f)
    val popIn = slideInHorizontally(slide) { -it / 4 } + fadeIn(tween(PUSH_MS), initialAlpha = 0.6f)
    val popOut = slideOutHorizontally(slide) { it }

    NavDisplay(
        backStack = backStack,
        onBack = pop,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = { pushIn togetherWith pushOut },
        popTransitionSpec = { popIn togetherWith popOut },
        predictivePopTransitionSpec = { popIn togetherWith popOut },
        entryProvider = entryProvider {
            entry<Screen.Home> { HomeRoute(graph, system, push) }
            entry<Screen.Terminal> { key ->
                val app = LocalContext.current.applicationContext as Application
                val vm = viewModel { TerminalViewModel(app, key.hostId, key.sessionId, key.windowId, key.blockId) }
                TerminalScreen(graph, vm, onBack = pop)
            }
            entry<Screen.HostEditor> { key -> HostEditorRoute(graph, system, key, onClose = pop) }
            entry<Screen.Tailscale> { TailscaleScreen(graph, onBack = pop, open = push) }
            entry<Screen.Settings> { SettingsScreen(graph, system, onBack = pop, open = push) }
            entry<Screen.Theme> { ThemeScreen(graph, onBack = pop) }
            entry<Screen.Font> { FontScreen(graph, onBack = pop) }
            entry<Screen.Acknowledgements> { AcknowledgementsScreen(graph, onBack = pop, open = push) }
            entry<Screen.License> { LicenseScreen(it.title, it.asset, onBack = pop) }
        },
    )
}
