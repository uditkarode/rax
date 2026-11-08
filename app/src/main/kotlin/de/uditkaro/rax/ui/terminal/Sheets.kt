package de.uditkaro.rax.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.ConfirmDialog
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.RaxIcon
import de.uditkaro.rax.ui.components.RenameDialog
import de.uditkaro.rax.ui.components.RowBase
import de.uditkaro.rax.ui.components.SectionHeader
import de.uditkaro.rax.ui.showActionFailed
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyTerminalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class Pending { RenamePane, RenameTab, RenameSession, ClosePane, CloseTab, EndSession }

/** Everything a pane, its tab and its session can do, in one sheet, the things that remove last. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsSheet(
    graph: AppGraph,
    vm: TerminalViewModel,
    windowId: String,
    blockId: String,
    tabTitle: String,
    paneTitle: String,
    sessionName: String,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
) {
    val c = Rax.colors
    val rex = vm.live?.rex ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pending by remember { mutableStateOf<Pending?>(null) }
    val sid = vm.sessionId
    val context = LocalContext.current.applicationContext
    // The sheet is gone by the time an action fails, so the failure shows as a toast.
    fun run(block: suspend () -> Unit) {
        graph.scope.launch(Dispatchers.Main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showActionFailed(context, e)
            }
        }
    }

    if (pending == null) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.sheet) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                SectionHeader(stringResource(R.string.action_group_pane))
                Group(dividerInset = 52.dp) {
                    row {
                        SheetRow(R.drawable.ic_splitscreen_right, stringResource(R.string.action_split_right)) {
                            onDismiss()
                            vm.openPane { it.split(sid, blockId, vertical = false)?.let { pane -> PaneTarget(windowId, pane) } }
                        }
                    }
                    row {
                        SheetRow(R.drawable.ic_splitscreen_bottom, stringResource(R.string.action_split_down)) {
                            onDismiss()
                            vm.openPane { it.split(sid, blockId, vertical = true)?.let { pane -> PaneTarget(windowId, pane) } }
                        }
                    }
                    row { SheetRow(R.drawable.ic_edit, stringResource(R.string.action_rename_pane)) { pending = Pending.RenamePane } }
                    row { SheetRow(R.drawable.ic_close, stringResource(R.string.action_close_pane), destructive = true) { pending = Pending.ClosePane } }
                }
                SectionHeader(stringResource(R.string.action_group_tab))
                Group(dividerInset = 52.dp) {
                    row {
                        SheetRow(R.drawable.ic_add, stringResource(R.string.action_new_tab)) {
                            onDismiss()
                            vm.openPane {
                                val (w, b) = it.newTab(sid)
                                if (w != null && b != null) PaneTarget(w, b) else null
                            }
                        }
                    }
                    row { SheetRow(R.drawable.ic_edit, stringResource(R.string.action_rename_tab)) { pending = Pending.RenameTab } }
                    row { SheetRow(R.drawable.ic_close, stringResource(R.string.action_close_tab), destructive = true) { pending = Pending.CloseTab } }
                }
                SectionHeader(stringResource(R.string.action_group_session))
                Group(dividerInset = 52.dp) {
                    row { SheetRow(R.drawable.ic_edit, stringResource(R.string.action_rename_session)) { pending = Pending.RenameSession } }
                    row { SheetRow(R.drawable.ic_delete, stringResource(R.string.action_end_session), destructive = true) { pending = Pending.EndSession } }
                }
            }
        }
    }

    when (pending) {
        Pending.RenamePane -> RenameDialog(stringResource(R.string.action_rename_pane), paneTitle, { run { rex.renamePane(sid, blockId, it) } }, onDismiss)
        Pending.RenameTab -> RenameDialog(stringResource(R.string.action_rename_tab), tabTitle, { run { rex.renameTab(sid, windowId, it) } }, onDismiss)
        Pending.RenameSession -> RenameDialog(stringResource(R.string.action_rename_session), sessionName, { run { rex.renameSession(sid, it) } }, onDismiss)
        Pending.ClosePane -> ConfirmDialog(
            stringResource(R.string.confirm_close_pane, paneTitle), stringResource(R.string.confirm_close_body),
            stringResource(R.string.action_close_pane), { run { rex.closePane(sid, blockId) } }, onDismiss,
        )
        Pending.CloseTab -> ConfirmDialog(
            stringResource(R.string.confirm_close_tab, tabTitle), stringResource(R.string.confirm_close_tab_body),
            stringResource(R.string.action_close_tab), { run { rex.closeTab(sid, windowId) } }, onDismiss,
        )
        Pending.EndSession -> ConfirmDialog(
            stringResource(R.string.confirm_end_session, sessionName), stringResource(R.string.confirm_end_session_body),
            stringResource(R.string.action_end_session), {
                run { rex.endSession(sid) }
                onLeave()
            }, onDismiss,
        )
        null -> {}
    }
}

@Composable
private fun SheetRow(icon: Int, text: String, destructive: Boolean = false, onClick: () -> Unit) {
    val c = Rax.colors
    val color = if (destructive) c.danger else c.text
    RowBase(onClick) {
        RaxIcon(icon, null, tint = if (destructive) c.danger else c.textSecondary, size = 22.dp)
        Text(text, Modifier.weight(1f), style = RaxType.body, color = color)
    }
}

/** The keys that do not fit on the bar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MoreKeysSheet(terminal: GhosttyTerminalState, onDismiss: () -> Unit) {
    val c = Rax.colors
    val keys = listOf(
        TermKey.Tab, TermKey.ShiftTab, TermKey.CtrlC, TermKey.CtrlD, TermKey.CtrlZ, TermKey.CtrlL, TermKey.CtrlR,
        TermKey.Left, TermKey.Right, TermKey.Home, TermKey.End, TermKey.PageUp, TermKey.PageDown,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.sheet) {
        FlowRow(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            keys.forEach { key ->
                Key(
                    text = key.cap,
                    icon = key.icon,
                    label = stringResource(key.description),
                    height = 48.dp,
                    repeat = key == TermKey.Left || key == TermKey.Right,
                    onTap = { terminal.press(key) },
                )
            }
            Key(stringResource(R.string.key_paste), height = 48.dp, onTap = {
                onDismiss()
                terminal.paste()
            })
        }
    }
}
