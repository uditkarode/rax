package de.uditkaro.rax.ui.terminal

import android.text.format.Formatter
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.activeTheme
import de.uditkaro.rax.data.Settings
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.IconAction
import de.uditkaro.rax.ui.components.PrimaryButton
import de.uditkaro.rax.ui.components.ProgramGlyph
import de.uditkaro.rax.ui.components.RaxIcon
import de.uditkaro.rax.ui.SystemActions
import de.uditkaro.rax.ui.home.tabRow
import de.uditkaro.rax.ui.tailnet.rememberTailnetSignIn
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyDialogs
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyJumpToBottomButton
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyTerminal
import io.github.xchacha20_poly1305.libghostty.compose.rememberGhosttyTerminalState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(graph: AppGraph, system: SystemActions, vm: TerminalViewModel, onBack: () -> Unit) {
    val c = Rax.colors
    val settings by graph.settings.collectAsStateWithLifecycle()
    val themes by graph.themes.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val signIn = rememberTailnetSignIn(graph)
    val s = settings ?: return
    val theme = activeTheme(s, themes ?: return)
    LaunchedEffect(vm, theme) { vm.setTheme(theme.terminal.toPane(theme.dark)) }
    val font = rememberTerminalFont(graph, s.fontId)
    val terminalState = rememberGhosttyTerminalState()
    // Pinch zoom stays in the sizes the settings keep.
    terminalState.fontSizeRangeSp = Settings.FONT_SIZES
    val rootView = LocalView.current
    // Ending the terminal's input session keeps the keyboard from coming back by itself when Rax returns.
    val hideKeyboard: () -> Unit = {
        terminalState.hideKeyboard()
        ViewCompat.getWindowInsetsController(rootView)?.hide(WindowInsetsCompat.Type.ime())
    }
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

    val tab = header.tab
    val panes = header.panes
    val imeVisible = WindowInsets.isImeVisible
    val terminalBackground = Color(theme.terminal.background)
    val hasScreen = session != null && font != null
    // Keys reach the pane while it shows, and while it reconnects, when they wait for the connection.
    val typing = hasScreen && (view == PaneView.Live || view == PaneView.Reconnecting)
    val message = view is PaneView.Failed || view == PaneView.ElsewhereOwned || view == PaneView.Closed || view == PaneView.Unavailable
    LaunchedEffect(message) {
        if (message) hideKeyboard()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(terminalBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .imePadding(),
    ) {
        // A short window, such as a phone in landscape, leaves room for the terminal with the keyboard open only
        // without the top bar.
        val short = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < SHORT_WINDOW
        if (!imeVisible || !short) {
            TopBar(
                title = header.title.ifEmpty { vm.live?.host?.name.orEmpty() },
                subtitle = header.subtitle,
                glyph = { tab?.let { ProgramGlyph(it.program, it.programName, it.icon, size = 30.dp) } },
                vm = vm,
                currentWindowId = target?.windowId,
                // The tab menu and the actions need the tab's layout.
                enabled = tab != null,
                working = working,
                onBack = onBack,
                onActions = { actions = true },
            )
        } else {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        }

        // Keeps the terminal from drawing over the top bar.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (session != null && font != null) {
                GhosttyTerminal(
                    session = session,
                    state = terminalState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    theme = remember(theme) { theme.terminal.toGhostty() },
                    darkColorScheme = theme.dark,
                    fontFamily = font,
                    fontSize = s.fontSize.sp,
                    keyboardOnAttach = false,
                    onFontSizeChanged = { size -> graph.updateSettings { setFontSize(size.value) } },
                )
            }
            if (view == PaneView.Live) {
                GhosttyJumpToBottomButton(
                    terminalState,
                    contentDescription = stringResource(R.string.terminal_jump_to_bottom),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    containerColor = c.card,
                    contentColor = c.text,
                )
            }
            PaneOverlay(
                // The pane can be ready before its font has loaded.
                view = if (font == null && (view == PaneView.Live || view == PaneView.Reconnecting)) PaneView.Loading(false, 0) else view,
                hostName = vm.live?.host?.name.orEmpty(),
                hasScreen = hasScreen,
                onAction = { action ->
                    when (action) {
                        PaneAction.Retry -> vm.retry()
                        PaneAction.SignIn -> signIn()
                        PaneAction.AllowLocalNetwork -> system.askLocalNetwork()
                    }
                },
                onUseHere = vm::useHere,
                onBack = onBack,
            )
        }

        if (imeVisible) {
            KeyBar(
                terminal = terminalState,
                enabled = typing,
                onHideKeyboard = hideKeyboard,
            )
        } else {
            val switcher: @Composable (Modifier) -> Unit = { modifier ->
                if (panes.size > 1) {
                    PaneSwitcher(panes, target?.blockId, modifier, onPick = { id ->
                        target?.let { vm.show(PaneTarget(it.windowId, id)) }
                    })
                }
            }
            val dock: @Composable () -> Unit = {
                Dock(
                    terminal = terminalState,
                    enabled = typing,
                    onKeyboard = terminalState::showKeyboard,
                    onMore = { moreKeys = true },
                )
            }
            val bottom = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 10.dp, top = 6.dp)
            // In a short window the pane switcher sits next to the dock, so the terminal keeps more rows.
            if (short) {
                Row(bottom, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    switcher(Modifier.weight(1f, fill = false))
                    dock()
                }
            } else {
                Column(bottom, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    switcher(Modifier)
                    dock()
                }
            }
        }
    }

    if (actions && target != null && tab != null) {
        ActionsSheet(
            graph = graph,
            vm = vm,
            windowId = target!!.windowId,
            blockId = target!!.blockId,
            tabTitle = tab.title,
            paneTitle = panes.firstOrNull { it.id == target!!.blockId }?.title ?: tab.title,
            sessionName = header.sessionName,
            onDismiss = { actions = false },
            onLeave = onBack,
        )
    }
    if (moreKeys) {
        MoreKeysSheet(terminalState, onDismiss = { moreKeys = false })
    }
    // Asks before an unsafe paste, and offers a tapped link.
    GhosttyDialogs(terminalState)
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    glyph: @Composable () -> Unit,
    vm: TerminalViewModel,
    currentWindowId: String?,
    enabled: Boolean,
    working: Boolean,
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
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.terminal_switch_tab)) { tabs = true }
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
                        if (enabled) RaxIcon(R.drawable.ic_expand_more, null, tint = c.textSecondary, size = 16.dp)
                    }
                }
            }
            DropdownMenu(expanded = tabs, onDismissRequest = { tabs = false }) {
                // Every tab's row follows the whole host, so it is read only while the menu is open.
                val host by vm.host.collectAsStateWithLifecycle()
                host?.session(vm.sessionId)?.view?.tabs.orEmpty().forEachIndexed { i, w ->
                    val row = tabRow(context.resources, vm.hostId, vm.sessionId, "", w, i, host?.blocks.orEmpty()) ?: return@forEachIndexed
                    DropdownMenuItem(
                        text = {
                            Text(
                                row.title,
                                color = when {
                                    w.id == currentWindowId -> c.link
                                    row.waiting -> c.warning
                                    else -> c.text
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { ProgramGlyph(row.program, row.programName, row.icon, size = 24.dp) },
                        trailingIcon = {
                            if (w.id == currentWindowId) RaxIcon(R.drawable.ic_check, null, tint = c.link, size = 20.dp)
                        },
                        onClick = {
                            tabs = false
                            val block = w.primaryBlockId ?: return@DropdownMenuItem
                            vm.show(PaneTarget(w.id!!, block))
                        },
                    )
                }
            }
        }
        // While a split or a new tab waits for Rex, a spinner takes the place of the actions.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp), color = c.textSecondary, strokeWidth = 2.dp)
            else IconAction(R.drawable.ic_more_horiz, stringResource(R.string.terminal_actions), onActions, enabled = enabled)
        }
    }
}

@Composable
private fun PaneSwitcher(panes: List<PaneChip>, selected: String?, modifier: Modifier, onPick: (String) -> Unit) {
    val c = Rax.colors
    val waiting = stringResource(R.string.waiting_generic)
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(c.card)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        panes.forEach { pane ->
            val on = pane.id == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) c.control else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onPick(pane.id) }
                    .semantics { if (pane.waiting) stateDescription = waiting }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    pane.shown,
                    style = RaxType.subhead,
                    color = when {
                        pane.waiting -> c.warning
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
private fun PaneOverlay(
    view: PaneView,
    hostName: String,
    hasScreen: Boolean,
    onAction: (PaneAction) -> Unit,
    onUseHere: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Rax.colors
    val context = LocalContext.current
    AnimatedVisibility(view == PaneView.Reconnecting, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                stringResource(R.string.terminal_reconnecting),
                Modifier.clip(RoundedCornerShape(16.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 8.dp),
                style = RaxType.footnote,
                color = c.textSecondary,
            )
        }
    }
    when (view) {
        is PaneView.Loading -> {
            val label = when {
                view.connecting -> stringResource(R.string.terminal_connecting, hostName)
                view.bytes >= 512 * 1024 -> stringResource(R.string.terminal_loading_size, Formatter.formatShortFileSize(context, view.bytes))
                else -> stringResource(R.string.terminal_loading)
            }
            // A pane that opens quickly, as most do, shows no spinner that flashes by.
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(LOADING_DELAY_MS)
                slow = true
            }
            AnimatedVisibility(slow, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = c.textSecondary, strokeWidth = 2.5.dp)
                    Text(label, style = RaxType.footnote, color = c.textSecondary, textAlign = TextAlign.Center)
                }
            }
        }
        is PaneView.Failed -> Message(view.message, hasScreen) {
            val action = view.action ?: return@Message
            val label = when (action) {
                PaneAction.Retry -> R.string.link_retry
                PaneAction.SignIn -> R.string.link_sign_in
                PaneAction.AllowLocalNetwork -> R.string.link_allow
            }
            PrimaryButton(stringResource(label), { onAction(action) }, Modifier.padding(horizontal = 24.dp))
        }
        PaneView.ElsewhereOwned -> Message(stringResource(R.string.terminal_elsewhere), hasScreen) {
            PrimaryButton(stringResource(R.string.terminal_use_here), onUseHere, Modifier.padding(horizontal = 24.dp))
        }
        PaneView.Closed -> Message(stringResource(R.string.terminal_closed), hasScreen) {
            PrimaryButton(stringResource(R.string.back), onBack, Modifier.padding(horizontal = 24.dp))
        }
        PaneView.Unavailable -> Message(stringResource(R.string.terminal_unavailable), hasScreen) {
            PrimaryButton(stringResource(R.string.back), onBack, Modifier.padding(horizontal = 24.dp))
        }
        PaneView.Live, PaneView.Reconnecting -> Unit
    }
}

/**
 * A message in the middle of the terminal area. It dims the screen behind it, if there is one, and
 * takes the touches meant for that screen, which cannot act on the pane now.
 */
@Composable
private fun Message(text: String, hasScreen: Boolean, action: @Composable () -> Unit) {
    val c = Rax.colors
    Box(
        Modifier.fillMaxSize().then(
            if (hasScreen) {
                Modifier
                    .background(c.page.copy(alpha = 0.86f))
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            } else {
                Modifier
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text, style = RaxType.body, color = c.text, textAlign = TextAlign.Center)
            action()
        }
    }
}

/** Windows shorter than this hide the top bar while the keyboard is open. */
private val SHORT_WINDOW = 480.dp

/** How long a pane loads before the screen says so. */
private const val LOADING_DELAY_MS = 400L
