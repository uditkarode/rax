package de.uditkaro.rax.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.NavRow
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
    val mono = rememberTerminalFontFamily(graph, s.fontId)

    LargeTitleScreen(stringResource(R.string.settings_title), onBack) {
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
