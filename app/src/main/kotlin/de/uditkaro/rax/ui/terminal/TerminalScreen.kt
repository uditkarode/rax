package de.uditkaro.rax.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.activeTheme
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.IconAction
import de.uditkaro.rax.ui.components.PrimaryButton
import de.uditkaro.rax.ui.components.ProgramGlyph
import de.uditkaro.rax.ui.components.RaxIcon
import de.uditkaro.rax.ui.home.appName
import de.uditkaro.rax.ui.home.shortPath
import de.uditkaro.rax.ui.home.tabRow
import io.github.sagernet.libghostty.compose.GhosttyTerminal
import io.github.sagernet.libghostty.compose.rememberGhosttyTerminalState
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(graph: AppGraph, vm: TerminalViewModel, onBack: () -> Unit) {
    val c = Rax.colors
    val context = LocalContext.current
    val settings by graph.settings.collectAsStateWithLifecycle()
    val themes by graph.themes.collectAsStateWithLifecycle()
    val host by vm.host.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val s = settings ?: return
    val theme = activeTheme(s, themes ?: return)
    val typeface = rememberTerminalTypeface(graph, s.fontId)
    val terminalState = rememberGhosttyTerminalState()
    val scope = rememberCoroutineScope()

    var actions by remember { mutableStateOf(false) }
    var moreKeys by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onVisible()
                Lifecycle.Event.ON_STOP -> vm.onHidden()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val window = vm.window(host)
    val sessionState = host?.session(vm.sessionId)
    val tab = window?.let { w ->
        tabRow(context.resources, vm.hostId, vm.sessionId, sessionState?.info?.label.orEmpty(), w, 0, host?.blocks.orEmpty(), target?.blockId)
    }
    val facts = target?.blockId?.let { host?.blocks?.get(it) }
    val panes = window?.blocks?.filter { it.isTerminal && it.id != null }.orEmpty()
    val imeVisible = WindowInsets.isImeVisible
    val terminalBackground = Color(theme.terminal.background)

    Column(
        Modifier
            .fillMaxSize()
            .background(terminalBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .imePadding(),
    ) {
        TopBar(
            title = tab?.title ?: sessionState?.info?.label.orEmpty(),
            subtitle = listOfNotNull(sessionState?.info?.label, shortPath(facts?.cwd, facts?.process?.home)).joinToString("  ·  "),
            glyph = { tab?.let { ProgramGlyph(it.program, it.programName, size = 30.dp) } },
            host = host,
            vm = vm,
            currentWindowId = target?.windowId,
            onBack = onBack,
            onActions = { actions = true },
        )

        // The terminal view draws its background outside its own area. The clip stops it from covering the top bar.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (session != null && typeface != null) {
                GhosttyTerminal(
                    session = session,
                    state = terminalState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    theme = theme.terminal.toGhostty(),
                    darkColorScheme = theme.dark,
                    typeface = typeface,
                    fontSizeSp = s.fontSize,
                    focusOnAttach = false,
                    onFontSizeChanged = { size -> graph.updateSettings { setFontSize(size) } },
                )
            }
            PaneOverlay(status, onUseHere = vm::useHere, onBack = onBack)
        }

        if (imeVisible) {
            KeyBar(
                view = terminalState.view,
                extraKeys = terminalState.extraKeys,
                onHideKeyboard = {
                    terminalState.view?.let { v -> ViewCompat.getWindowInsetsController(v)?.hide(WindowInsetsCompat.Type.ime()) }
                },
            )
        } else {
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 10.dp, top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (panes.size > 1) {
                    PaneSwitcher(panes.map { it.id!! }, target?.blockId, host, onPick = { id ->
                        target?.let { vm.show(PaneTarget(it.windowId, id)) }
                    })
                }
                Dock(
                    view = terminalState.view,
                    onKeyboard = { terminalState.view?.showIme() },
                    onMore = { moreKeys = true },
                )
            }
        }
    }

    if (actions && target != null && window != null) {
        ActionsSheet(
            graph = graph,
            vm = vm,
            windowId = target!!.windowId,
            blockId = target!!.blockId,
            tabTitle = tab?.title.orEmpty(),
            paneTitle = panes.firstOrNull { it.id == target!!.blockId }?.label ?: tab?.title.orEmpty(),
            sessionName = sessionState?.info?.label.orEmpty(),
            onDismiss = { actions = false },
            onLeave = onBack,
        )
    }
    if (moreKeys) {
        MoreKeysSheet(terminalState.view, terminalState.extraKeys, onDismiss = { moreKeys = false })
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    glyph: @Composable () -> Unit,
    host: HostState?,
    vm: TerminalViewModel,
    currentWindowId: String?,
    onBack: () -> Unit,
    onActions: () -> Unit,
) {
    val c = Rax.colors
    var tabs by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(60.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.terminal_switch_tab)) { tabs = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                glyph()
                Column(Modifier.weight(1f, fill = false)) {
                    Text(title, style = RaxType.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            subtitle,
                            Modifier.weight(1f, fill = false),
                            style = RaxType.footnote,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RaxIcon(R.drawable.ic_expand_more, null, tint = c.textSecondary, size = 16.dp)
                    }
                }
            }
            val view = host?.session(vm.sessionId)?.view
            DropdownMenu(expanded = tabs, onDismissRequest = { tabs = false }) {
                view?.tabs.orEmpty().forEachIndexed { i, w ->
                    val row = tabRow(context.resources, vm.hostId, vm.sessionId, "", w, i, host?.blocks.orEmpty()) ?: return@forEachIndexed
                    DropdownMenuItem(
                        text = {
                            Text(
                                row.title,
                                color = if (w.id == currentWindowId) c.link else c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { ProgramGlyph(row.program, row.programName, size = 24.dp) },
                        onClick = {
                            tabs = false
                            val block = w.primaryBlockId ?: return@DropdownMenuItem
                            vm.show(PaneTarget(w.id!!, block))
                        },
                    )
                }
            }
        }
        IconAction(R.drawable.ic_more_horiz, stringResource(R.string.terminal_actions), onActions)
    }
}

@Composable
private fun PaneSwitcher(panes: List<String>, selected: String?, host: HostState?, onPick: (String) -> Unit) {
    val c = Rax.colors
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(c.card)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        panes.forEach { id ->
            val facts = host?.blocks?.get(id)
            val name = facts?.status?.values?.firstNotNullOfOrNull { it.effectiveApp ?: it.app }?.let(::appName) ?: facts?.programName ?: "…"
            val on = id == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) c.control else Color.Transparent)
                    .clickable(role = Role.Tab) { onPick(id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    name,
                    style = RaxType.subhead,
                    color = when {
                        facts?.waiting != null -> c.warning
                        on -> c.text
                        else -> c.textSecondary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PaneOverlay(status: PaneStatus, onUseHere: () -> Unit, onBack: () -> Unit) {
    val c = Rax.colors
    AnimatedVisibility(status == PaneStatus.Reconnecting, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                stringResource(R.string.terminal_reconnecting),
                Modifier.clip(RoundedCornerShape(16.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 8.dp),
                style = RaxType.footnote,
                color = c.textSecondary,
            )
        }
    }
    val message = when (status) {
        PaneStatus.Opening -> null
        PaneStatus.ElsewhereOwned -> R.string.terminal_elsewhere
        PaneStatus.Closed -> R.string.terminal_closed
        PaneStatus.Unavailable -> R.string.terminal_unavailable
        else -> null
    }
    if (message != null) {
        Box(Modifier.fillMaxSize().background(c.page.copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(message), style = RaxType.body, color = c.text)
                if (status == PaneStatus.ElsewhereOwned) {
                    PrimaryButton(stringResource(R.string.terminal_use_here), onUseHere, Modifier.padding(horizontal = 24.dp))
                } else {
                    PrimaryButton(stringResource(R.string.back), onBack, Modifier.padding(horizontal = 24.dp))
                }
            }
        }
    }
}
