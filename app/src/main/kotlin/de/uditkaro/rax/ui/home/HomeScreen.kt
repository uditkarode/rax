package de.uditkaro.rax.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.ActionRow
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.IconAction
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.Metrics
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
    val tailscale: () -> Unit,
    val refresh: () -> Unit,
)

@Composable
fun HomeScreen(
    state: HomeState,
    mono: FontFamily,
    actions: HomeActions,
    creating: Set<String>,
    updateAvailable: Boolean = false,
    refreshing: Boolean = false,
) {
    val empty = state.hosts.isEmpty()
    LargeTitleScreen(
        title = stringResource(if (empty) R.string.welcome_title else R.string.home_title),
        onBack = null,
        actions = { SettingsAction(updateAvailable, actions.settings) },
        bottomBar = if (empty) ({ PrimaryButton(stringResource(R.string.add_host), actions.addHost) }) else null,
        onRefresh = actions.refresh.takeUnless { empty },
        refreshing = refreshing,
    ) {
        if (empty) {
            welcome(mono, actions.tailscale)
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
        state.hosts.forEach { card -> host(card, actions, creating = card.host.id in creating) }
    }
}

/** Settings, with a dot when an update is out. */
@Composable
private fun SettingsAction(updateAvailable: Boolean, onClick: () -> Unit) {
    val c = Rax.colors
    Box {
        IconAction(
            R.drawable.ic_settings,
            stringResource(if (updateAvailable) R.string.settings_update_available else R.string.settings_title),
            onClick,
        )
        if (updateAvailable) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 9.dp, end = 9.dp)
                    .size(11.dp)
                    .background(c.page, CircleShape)
                    .padding(2.dp)
                    .background(c.accent, CircleShape),
            )
        }
    }
}

private fun LazyListScope.host(card: HostCard, actions: HomeActions, creating: Boolean) {
    item(key = "host-${card.host.id}") { HostHeader(card, actions, creating) }
    if (card.link == Link.Connecting) {
        item(key = "host-${card.host.id}-loading") { SessionsPlaceholder(Modifier.animateItem()) }
    }
    if (card.link is Link.Online && card.sessions.isEmpty()) {
        item(key = "host-${card.host.id}-empty") {
            Group(Modifier.animateItem().padding(top = 8.dp)) {
                row {
                    ActionRow(stringResource(R.string.session_new), { actions.newSession(card.host) }, icon = R.drawable.ic_add, enabled = !creating)
                }
            }
        }
    }
    card.sessions.forEach { session ->
        item(key = "session-${card.host.id}-${session.id}") { SessionGroupView(session, actions, Modifier.animateItem()) }
    }
}

/**
 * Stands in for a host's sessions while Rax connects to it, with the same sizes as a session's
 * name and two of its tabs, so nothing moves when the sessions arrive.
 */
@Composable
private fun SessionsPlaceholder(modifier: Modifier = Modifier) {
    val c = Rax.colors
    val pulse by rememberInfiniteTransition(label = "placeholder").animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    fun Modifier.shape(width: Dp, height: Dp, alpha: Float, shape: Shape) =
        size(width, height).graphicsLayer { this.alpha = pulse }.background(c.text.copy(alpha = alpha), shape)
    fun Modifier.bar(width: Dp, height: Dp, alpha: Float) = shape(width, height, alpha, RoundedCornerShape(height / 2))
    Column(modifier.clearAndSetSemantics {}) {
        Box(Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp).height(20.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.bar(64.dp, 10.dp, 0.09f))
        }
        Column(
            Modifier
                .padding(horizontal = Metrics.screenPadding)
                .fillMaxWidth()
                .clip(Metrics.groupShape)
                .background(c.card),
        ) {
            listOf(92.dp to 148.dp, 124.dp to 104.dp).forEach { (title, detail) ->
                RowBase(onClick = null) {
                    Box(Modifier.shape(36.dp, 36.dp, 0.06f, RoundedCornerShape(percent = 25)))
                    Column {
                        Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) { Box(Modifier.bar(title, 10.dp, 0.10f)) }
                        Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) { Box(Modifier.bar(detail, 8.dp, 0.06f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostHeader(card: HostCard, actions: HomeActions, creating: Boolean) {
    val c = Rax.colors
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 28.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkMark(card.link)
            Text(
                card.host.name,
                Modifier.weight(1f).semantics { heading() },
                style = RaxType.title,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.textSecondary, strokeWidth = 2.dp)
                } else {
                    IconAction(R.drawable.ic_more_horiz, stringResource(R.string.host_menu, card.host.name), { menu = true }, tint = c.textSecondary)
                }
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
        // Only a host that needs the user's attention says so in words.
        when (val link = card.link) {
            is Link.Offline -> ProblemLine(link.message, stringResource(R.string.link_retry).takeIf { link.retryable }) { actions.connect(card.host) }
            is Link.Blocked -> ProblemLine(
                stringResource(
                    when (link.need) {
                        Need.TailscaleSignIn -> R.string.link_needs_tailnet
                        Need.LocalNetwork -> R.string.link_needs_local_network
                    },
                ),
                stringResource(
                    when (link.need) {
                        Need.TailscaleSignIn -> R.string.link_sign_in
                        Need.LocalNetwork -> R.string.link_allow
                    },
                ),
            ) { actions.allow(link.need) }
            Link.Connecting, is Link.Online -> Unit
        }
    }
}

/** Why a host cannot be reached, under its name, with what the user can do about it. */
@Composable
private fun ProblemLine(message: String, action: String?, onAction: () -> Unit) {
    val c = Rax.colors
    Row(
        Modifier.padding(start = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            message,
            Modifier.weight(1f, fill = false),
            style = RaxType.footnote,
            color = c.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) {
            Text(
                action,
                Modifier.clickable(role = Role.Button, onClick = onAction).padding(vertical = 4.dp),
                style = RaxType.footnote,
                color = c.link,
            )
        }
    }
}

/** Whether the host is online, connecting or not reachable, before its name. */
@Composable
private fun LinkMark(link: Link) {
    val c = Rax.colors
    Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
        when (link) {
            is Link.Online -> StatusDot(
                online = true,
                contentDescription = stringResource(if (link.viaTailnet) R.string.link_online_tailnet else R.string.link_online),
            )
            Link.Connecting -> {
                val connecting = stringResource(R.string.link_connecting)
                CircularProgressIndicator(
                    Modifier.fillMaxSize().semantics { contentDescription = connecting },
                    color = c.textSecondary,
                    strokeWidth = 1.5.dp,
                )
            }
            is Link.Offline, is Link.Blocked -> StatusDot(online = false)
        }
    }
}

@Composable
private fun SessionGroupView(session: SessionGroup, actions: HomeActions, modifier: Modifier = Modifier) {
    val c = Rax.colors
    var open by rememberSaveable(session.id) { mutableStateOf(true) }
    Column(modifier.animateContentSize()) {
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
        ProgramGlyph(tab.program, tab.programName, tab.icon)
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
                    Text(
                        tab.sessionName,
                        Modifier.widthIn(max = 140.dp),
                        style = RaxType.footnote,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tab.detail != null) {
                Text(
                    tab.detail,
                    // The weight marks it too, since some themes' warning colour is close to their text.
                    style = if (tab.waiting) RaxType.footnoteStrong else RaxType.footnote,
                    color = if (tab.waiting) c.warning else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RaxIcon(R.drawable.ic_chevron_right, null, tint = c.textTertiary, size = 20.dp)
    }
}
