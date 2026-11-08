package de.uditkaro.rax.ui.settings

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.BuildConfig
import de.uditkaro.rax.R
import de.uditkaro.rax.activeTheme
import de.uditkaro.rax.tailnet.TailnetStatus
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.SystemActions
import de.uditkaro.rax.ui.components.ActionRow
import de.uditkaro.rax.ui.components.Footnote
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.ToggleRow
import de.uditkaro.rax.ui.terminal.rememberTerminalFontFamily
import de.uditkaro.rax.update.Failure
import de.uditkaro.rax.update.UpdateState
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.NavRow
import de.uditkaro.rax.ui.components.RaxIcon
import de.uditkaro.rax.ui.components.RowBase
import de.uditkaro.rax.ui.components.SectionHeader

@Composable
fun SettingsScreen(graph: AppGraph, system: SystemActions, onBack: (() -> Unit)?, open: (Screen) -> Unit) {
    val settings by graph.settings.collectAsStateWithLifecycle()
    val catalog by graph.themes.collectAsStateWithLifecycle()
    val s = settings ?: return
    val themes = catalog ?: return
    val theme = activeTheme(s, themes)

    val hosts by graph.connections.hosts.collectAsStateWithLifecycle()
    val tailnet by graph.tailnet.status.collectAsStateWithLifecycle()
    val notificationsAllowed by graph.notifications.allowed.collectAsStateWithLifecycle()
    val update by graph.updater.state.collectAsStateWithLifecycle()
    val mono = rememberTerminalFontFamily(graph, s.fontId)

    LargeTitleScreen(stringResource(R.string.settings_title), onBack) {
        if (update !is UpdateState.None) {
            item(key = "update") {
                Group(Modifier.padding(top = 16.dp)) { row { UpdateRow(update, graph.updater::install) } }
                when ((update as? UpdateState.Available)?.failed) {
                    Failure.Download -> Footnote(stringResource(R.string.update_download_failed))
                    Failure.Install -> Footnote(stringResource(R.string.update_install_failed))
                    null -> Unit
                }
            }
        }
        item(key = "hosts") {
            SectionHeader(stringResource(R.string.settings_hosts))
            Group {
                hosts.orEmpty().forEach { host ->
                    row {
                        NavRow(host.name, { open(Screen.HostEditor(host.id)) }, subtitle = host.address, subtitleFamily = mono)
                    }
                }
                row { ActionRow(stringResource(R.string.add_host), { open(Screen.HostEditor(null)) }, icon = R.drawable.ic_add) }
            }
        }
        item(key = "appearance") {
            SectionHeader(stringResource(R.string.settings_appearance))
            Group {
                row { NavRow(stringResource(R.string.theme_title), { open(Screen.Theme) }, value = theme.name) }
                row {
                    NavRow(
                        stringResource(R.string.font_title),
                        { open(Screen.Font) },
                        value = stringResource(R.string.font_value, graph.fonts.name(s.fontId), s.fontSize.toInt()),
                    )
                }
            }
        }
        item(key = "connection") {
            SectionHeader(stringResource(R.string.settings_connection))
            Group {
                row {
                    NavRow(
                        stringResource(R.string.tailscale_title),
                        { open(Screen.Tailscale) },
                        value = if (s.tailscaleApp) stringResource(R.string.tailscale_app) else when (val t = tailnet) {
                            is TailnetStatus.Running -> t.tailnetName
                            is TailnetStatus.NeedsLogin -> stringResource(R.string.tailscale_signed_out)
                            else -> null
                        },
                    )
                }
                row {
                    ToggleRow(stringResource(R.string.settings_background_alerts), s.backgroundAlerts, { on ->
                        graph.updateSettings { setBackgroundAlerts(on) }
                        if (on && !notificationsAllowed) system.askNotifications()
                    })
                }
                // Alerts work only while Rax may post notifications, which the user can turn off at any time.
                if (s.backgroundAlerts && !notificationsAllowed) {
                    row { ActionRow(stringResource(R.string.settings_allow_notifications), system.askNotifications) }
                }
            }
            Footnote(stringResource(R.string.settings_background_alerts_footnote))
        }
        item(key = "about") {
            SectionHeader(stringResource(R.string.settings_about))
            Group {
                row {
                    RowBase(onClick = null) {
                        Text(stringResource(R.string.settings_version), Modifier.weight(1f), style = RaxType.body, color = Rax.colors.text)
                        Text(BuildConfig.VERSION_NAME, style = RaxType.body, color = Rax.colors.textSecondary)
                    }
                }
                row { NavRow(stringResource(R.string.acknowledgements_title), { open(Screen.Acknowledgements) }) }
            }
        }
    }
}

/** A newer preview: the button to install it, or how far the download is. */
@Composable
private fun UpdateRow(update: UpdateState, onInstall: () -> Unit) {
    val c = Rax.colors
    val context = LocalContext.current
    when (update) {
        is UpdateState.Available -> RowBase(onInstall) {
            RaxIcon(R.drawable.ic_download, null, tint = c.link, size = 22.dp)
            Text(stringResource(R.string.update_to, update.release.number), Modifier.weight(1f), style = RaxType.body, color = c.link)
            Text(Formatter.formatShortFileSize(context, update.release.size), style = RaxType.body, color = c.textSecondary)
        }
        is UpdateState.Downloading, is UpdateState.Installing -> {
            val downloading = update as? UpdateState.Downloading
            val number = downloading?.release?.number ?: (update as UpdateState.Installing).release.number
            val progress by animateFloatAsState(downloading?.progress ?: 1f, label = "progress")
            Box(Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                RowBase(onClick = null) {
                    RaxIcon(R.drawable.ic_download, null, tint = c.textTertiary, size = 22.dp)
                    Text(
                        stringResource(if (downloading != null) R.string.update_downloading else R.string.update_installing, number),
                        Modifier.weight(1f),
                        style = RaxType.body,
                        color = c.text,
                    )
                    if (downloading != null) {
                        Text(stringResource(R.string.update_percent, (downloading.progress * 100).toInt()), style = RaxType.body, color = c.textSecondary)
                    }
                }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(c.stroke)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent))
                }
            }
        }
        UpdateState.None -> Unit
    }
}
