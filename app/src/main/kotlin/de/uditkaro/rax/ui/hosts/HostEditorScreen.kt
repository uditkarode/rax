package de.uditkaro.rax.ui.hosts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.rex.RexEndpoint
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.ActionRow
import de.uditkaro.rax.ui.components.Footnote
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.SectionHeader
import de.uditkaro.rax.ui.components.ToggleRow
import de.uditkaro.rax.ui.components.IconAction
import de.uditkaro.rax.ui.components.RowBase
import java.util.UUID

/**
 * Adds or edits a computer. [describe] turns what was typed into the sentence under the address
 * that says where Rax will connect, or why it cannot.
 */
@Composable
fun HostEditorScreen(
    host: Host?,
    /** True when [host] is only a suggestion to fill in, not a saved host. */
    isNew: Boolean,
    /** What the connected Rex reports about itself, for a saved host that is online. */
    server: ServerSummary?,
    mono: FontFamily,
    describe: (address: String, tailnet: Boolean) -> HostCheck,
    onSave: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(host?.name.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(host?.address.orEmpty()) }
    var tailnet by rememberSaveable { mutableStateOf(host?.tailnet ?: false) }
    var tailnetTouched by rememberSaveable { mutableStateOf(host != null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A problem with the address shows once the user leaves the field or asks to save, not while
    // they type it.
    var checkAddress by rememberSaveable { mutableStateOf(host != null) }
    val newId = rememberSaveable { UUID.randomUUID().toString() }
    // The screen still takes taps while it slides away, and a second save or close must not run.
    var done by remember { mutableStateOf(false) }
    fun finish(action: () -> Unit) {
        if (done) return
        done = true
        action()
    }

    // A MagicDNS name or a tailnet IP means the user wants Tailscale, until they say otherwise.
    LaunchedEffect(address) {
        if (!tailnetTouched) tailnet = looksLikeTailnet(address)
    }
    val check = describe(address.trim(), tailnet)
    val canSave = address.isNotBlank() && check is HostCheck.Ok
    val save = {
        checkAddress = true
        if (canSave) {
            val trimmed = address.trim()
            val finalName = name.trim().ifEmpty { defaultName(trimmed) }
            val id = if (isNew || host == null) newId else host.id
            finish { onSave(Host(id = id, name = finalName, address = trimmed, tailnet = tailnet)) }
        }
    }
    val close = { finish(onClose) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (host == null) focus.requestFocus() }

    LargeTitleScreen(
        title = stringResource(if (isNew) R.string.add_host else R.string.host_edit),
        onBack = close,
        backIcon = R.drawable.ic_close,
        backLabel = stringResource(R.string.close),
        actions = {
            IconAction(R.drawable.ic_check, stringResource(R.string.save), save, tint = Rax.colors.link, enabled = canSave)
        },
    ) {
        item(key = "fields") {
            Column(Modifier.padding(top = 16.dp)) {
                Group {
                    row {
                        Field(
                            label = stringResource(R.string.host_name),
                            value = name,
                            onValue = { name = it },
                            placeholder = defaultName(address.trim()).ifEmpty { stringResource(R.string.host_name_placeholder) },
                            style = RaxType.body,
                            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        )
                    }
                    row {
                        Field(
                            label = stringResource(R.string.host_address),
                            value = address,
                            onValue = { address = it },
                            placeholder = stringResource(R.string.host_address_placeholder),
                            style = RaxType.body.copy(fontFamily = mono),
                            keyboard = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            onDone = save,
                            modifier = Modifier
                                .focusRequester(focus)
                                .onFocusChanged { if (!it.isFocused && address.isNotBlank()) checkAddress = true },
                        )
                    }
                }
                when (check) {
                    is HostCheck.Ok -> if (address.isNotBlank()) Footnote(check.summary)
                    is HostCheck.Invalid -> if (checkAddress) Footnote(check.reason)
                }
            }
        }
        item(key = "route") {
            SectionHeader(stringResource(R.string.host_route))
            Group {
                row {
                    ToggleRow(stringResource(R.string.host_tailnet), tailnet, {
                        tailnet = it
                        tailnetTouched = true
                    })
                }
            }
        }
        if (server != null) {
            item(key = "server") {
                SectionHeader(stringResource(R.string.host_server))
                Group {
                    row {
                        RowBase(onClick = null) {
                            Text(stringResource(R.string.host_server_version), Modifier.weight(1f), style = RaxType.body, color = Rax.colors.text)
                            Text(server.version, style = RaxType.body.copy(fontFamily = mono), color = Rax.colors.textSecondary)
                        }
                    }
                }
                if (server.newerThanRax) Footnote(stringResource(R.string.host_server_newer))
            }
        }
        if (!isNew && host != null) {
            item(key = "delete") {
                Group(Modifier.padding(top = 32.dp)) {
                    row { ActionRow(stringResource(R.string.host_delete), { confirmDelete = true }, destructive = true) }
                }
            }
        }
    }

    if (confirmDelete && host != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.host_delete_title, host.name)) },
            text = { Text(stringResource(R.string.host_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; finish { onDelete(host) } }) {
                    Text(stringResource(R.string.host_delete), color = Rax.colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = Rax.colors.sheet,
            titleContentColor = Rax.colors.text,
            textContentColor = Rax.colors.textSecondary,
        )
    }
}

data class ServerSummary(val version: String, val newerThanRax: Boolean)

sealed interface HostCheck {
    data class Ok(val summary: String) : HostCheck
    data class Invalid(val reason: String) : HostCheck
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    style: TextStyle,
    keyboard: KeyboardOptions,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val c = Rax.colors
    // The label and the padding are part of the field, so a tap anywhere on the row focuses it.
    BasicTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier.fillMaxWidth(),
        textStyle = style.copy(color = c.text),
        singleLine = true,
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboard,
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(label, style = RaxType.footnote, color = c.textSecondary)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = c.textTertiary, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

fun looksLikeTailnet(address: String): Boolean {
    val host = RexEndpoint.hostOf(address)?.lowercase() ?: return false
    if (host.endsWith(".ts.net")) return true
    // fd7a:115c:a1e0::/48 and 100.64.0.0/10, the ranges Tailscale gives its nodes.
    if (host.startsWith("fd7a:115c:a1e0:")) return true
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
}

/** The first label of a host name, or the address itself for an IP. */
fun defaultName(address: String): String {
    val host = RexEndpoint.hostOf(address) ?: return ""
    return if (RexEndpoint.isIpLiteral(host)) host else host.substringBefore('.')
}
