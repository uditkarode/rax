package de.uditkaro.rax.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.ActionRow
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.IconAction
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.PrimaryButton
import de.uditkaro.rax.ui.components.ProgramGlyph
import de.uditkaro.rax.ui.components.RaxIcon
import de.uditkaro.rax.ui.components.RowBase
import de.uditkaro.rax.ui.components.SectionHeader
import de.uditkaro.rax.ui.components.StatusDot

class HomeActions(
    val openTab: (TabRow) -> Unit,
    val addHost: () -> Unit,
    val editHost: (Host) -> Unit,
    val connect: (Host) -> Unit,
    val newSession: (Host) -> Unit,
    val allow: (Need) -> Unit,
    val settings: () -> Unit,
)

@Composable
fun HomeScreen(state: HomeState, mono: FontFamily, actions: HomeActions) {
    val empty = state.hosts.isEmpty()
    LargeTitleScreen(
        title = stringResource(if (empty) R.string.welcome_title else R.string.home_title),
        onBack = null,
        actions = { IconAction(R.drawable.ic_settings, stringResource(R.string.settings_title), actions.settings) },
        bottomBar = if (empty) ({ PrimaryButton(stringResource(R.string.add_host), actions.addHost) }) else null,
    ) {
        if (empty) {
            welcome(mono)
            return@LargeTitleScreen
        }
        if (state.waiting.isNotEmpty()) {
            item(key = "waiting") {
                SectionHeader(stringResource(R.string.home_waiting))
                Group(dividerInset = 64.dp) {
                    state.waiting.forEach { tab ->
                        row { TabRowView(tab, actions.openTab, showSession = true) }
                    }
                }
            }
        }
        state.hosts.forEach { card -> host(card, actions, multipleHosts = state.hosts.size > 1) }
    }
}

private fun LazyListScope.host(card: HostCard, actions: HomeActions, multipleHosts: Boolean) {
    item(key = "host-${card.host.id}") { HostHeader(card, actions) }
    if (card.link !is Link.Online) return
    if (card.sessions.isEmpty()) {
        item(key = "host-${card.host.id}-empty") {
            Group(Modifier.padding(top = 8.dp)) {
                row { ActionRow(stringResource(R.string.session_new), { actions.newSession(card.host) }, icon = R.drawable.ic_add) }
            }
        }
    }
    card.sessions.forEach { session ->
        item(key = "session-${card.host.id}-${session.id}") { SessionGroupView(session, actions) }
    }
}

@Composable
private fun HostHeader(card: HostCard, actions: HomeActions) {
    val c = Rax.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 28.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                card.host.name,
                Modifier.semantics { heading() },
                style = RaxType.title,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (val link = card.link) {
                    is Link.Online -> {
                        StatusDot(online = true)
                        Text(
                            stringResource(if (link.viaTailnet) R.string.link_online_tailnet else R.string.link_online),
                            style = RaxType.footnote,
                            color = c.textSecondary,
                        )
                    }
                    Link.Connecting -> {
                        StatusDot(online = false)
                        Text(stringResource(R.string.link_connecting), style = RaxType.footnote, color = c.textSecondary)
                    }
                    is Link.Offline -> {
                        StatusDot(online = false)
                        Text(
                            link.message,
                            Modifier.weight(1f, fill = false),
                            style = RaxType.footnote,
                            color = c.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (link.retryable) {
                            Text(
                                stringResource(R.string.link_retry),
                                Modifier.clickable(role = Role.Button) { actions.connect(card.host) }.padding(vertical = 4.dp),
                                style = RaxType.footnote,
                                color = c.link,
                            )
                        }
                    }
                    is Link.Blocked -> {
                        StatusDot(online = false)
                        Text(
                            stringResource(
                                when (link.need) {
                                    Need.TailscaleSignIn -> R.string.link_needs_tailnet
                                    Need.LocalNetwork -> R.string.link_needs_local_network
                                },
                            ),
                            Modifier.weight(1f, fill = false),
                            style = RaxType.footnote,
                            color = c.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                when (link.need) {
                                    Need.TailscaleSignIn -> R.string.link_sign_in
                                    Need.LocalNetwork -> R.string.link_allow
                                },
                            ),
                            Modifier.clickable(role = Role.Button) { actions.allow(link.need) }.padding(vertical = 4.dp),
                            style = RaxType.footnote,
                            color = c.link,
                        )
                    }
                }
            }
        }
        Box {
            IconAction(R.drawable.ic_more_horiz, stringResource(R.string.host_menu, card.host.name), { menu = true }, tint = c.textSecondary)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (card.link is Link.Online) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_new)) },
                        onClick = { menu = false; actions.newSession(card.host) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.host_edit)) },
                    onClick = { menu = false; actions.editHost(card.host) },
                )
            }
        }
    }
}

@Composable
private fun SessionGroupView(session: SessionGroup, actions: HomeActions) {
    val c = Rax.colors
    var open by rememberSaveable(session.id) { mutableStateOf(true) }
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.collapse else R.string.expand)) { open = !open }
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session.name,
                Modifier.weight(1f),
                style = RaxType.subhead,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!open) {
                Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.tab_count, session.tabs.size, session.tabs.size),
                    Modifier.padding(end = 6.dp),
                    style = RaxType.footnote,
                    color = c.textSecondary,
                )
            }
            RaxIcon(
                R.drawable.ic_expand_more,
                null,
                Modifier.rotate(if (open) 0f else -90f),
                tint = c.textTertiary,
                size = 20.dp,
            )
        }
        if (open) {
            Group(dividerInset = 64.dp) {
                session.tabs.forEach { tab -> row { TabRowView(tab, actions.openTab, showSession = false) } }
            }
        }
    }
}

@Composable
private fun TabRowView(tab: TabRow, onOpen: (TabRow) -> Unit, showSession: Boolean) {
    val c = Rax.colors
    RowBase(onClick = { onOpen(tab) }) {
        ProgramGlyph(tab.program, tab.programName)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tab.title,
                    Modifier.weight(1f, fill = false),
                    style = RaxType.bodyStrong,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showSession) {
                    Text(tab.sessionName, style = RaxType.footnote, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tab.detail != null) {
                Text(
                    tab.detail,
                    style = RaxType.footnote,
                    color = if (tab.waiting) c.warning else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RaxIcon(R.drawable.ic_chevron_right, null, tint = c.textTertiary, size = 20.dp)
    }
}
