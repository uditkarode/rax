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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.data.SshSignIn
import de.uditkaro.rax.rex.RexEndpoint
import de.uditkaro.rax.tailnet.SshKeys
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.ActionRow
import de.uditkaro.rax.ui.components.CheckRow
import de.uditkaro.rax.ui.components.Footnote
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.IconAction
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.RowBase
import de.uditkaro.rax.ui.components.SectionHeader
import de.uditkaro.rax.ui.components.ToggleRow
import de.uditkaro.rax.ui.home.CommandBlock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    ssh: SshEditing,
    askCamera: () -> Unit,
    onSave: (Host, SshSecrets) -> Unit,
    onDelete: (Host) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(host?.name.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(host?.address.orEmpty()) }
    var tailnet by rememberSaveable { mutableStateOf(host?.tailnet ?: false) }
    var tailnetTouched by rememberSaveable { mutableStateOf(host != null) }
    var signIn by rememberSaveable { mutableStateOf(host?.sshSignIn ?: SshSignIn.PhoneKey) }
    // Secrets stay out of the saved state, which Android can write to disk.
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var keyCheck by remember { mutableStateOf(SshKeys.Check.Ok) }
    // The key and passphrase that keyCheck is for.
    var checkedKey by remember { mutableStateOf("" to "") }
    var checkSecrets by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A problem with the address shows once the user leaves the field or asks to save, not while
    // they type it.
    var checkAddress by rememberSaveable { mutableStateOf(host != null) }
    val newId = rememberSaveable { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
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
    val isSsh = RexEndpoint.isSsh(address)
    LaunchedEffect(privateKey, passphrase) {
        // Opening a key with a passphrase takes a moment of hashing, so it waits until the typing stops.
        if (passphrase.isNotEmpty()) delay(400)
        keyCheck = checkKey(privateKey, passphrase)
        checkedKey = privateKey to passphrase
    }
    val secretProblem: Int? = when {
        !isSsh -> null
        signIn == SshSignIn.OwnKey && privateKey.isBlank() -> R.string.host_ssh_key_missing.takeUnless { ssh.hasPrivateKey }
        signIn == SshSignIn.OwnKey -> when (keyCheck) {
            SshKeys.Check.Ok -> null
            SshKeys.Check.NeedsPassphrase -> R.string.host_ssh_key_needs_passphrase
            SshKeys.Check.WrongPassphrase -> R.string.host_ssh_key_wrong_passphrase
            SshKeys.Check.Invalid -> R.string.host_ssh_key_invalid
        }
        signIn == SshSignIn.Password && password.isEmpty() -> R.string.host_ssh_password_missing.takeUnless { ssh.hasPassword }
        else -> null
    }
    val canSave = address.isNotBlank() && check is HostCheck.Ok && secretProblem == null
    val save = {
        checkAddress = true
        checkSecrets = true
        if (canSave) {
            scope.launch {
                // The check of a key with a passphrase can still be running for what was typed last.
                if (isSsh && signIn == SshSignIn.OwnKey && checkedKey != (privateKey to passphrase) &&
                    checkKey(privateKey, passphrase) != SshKeys.Check.Ok
                ) return@launch
                val trimmed = address.trim()
                val finalName = name.trim().ifEmpty { defaultName(trimmed) }
                val id = if (isNew || host == null) newId else host.id
                val typed = SshSecrets(privateKey.trim().ifEmpty { null }, passphrase, password.ifEmpty { null })
                finish { onSave(Host(id = id, name = finalName, address = trimmed, tailnet = tailnet, sshSignIn = signIn), typed) }
            }
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
            // Save with a wrong address shows what is wrong with it.
            IconAction(R.drawable.ic_check, stringResource(R.string.save), save, tint = Rax.colors.link, enabled = address.isNotBlank())
        },
    ) {
        if (isNew) {
            item(key = "scan") {
                Group(Modifier.padding(top = 16.dp)) {
                    row { ActionRow(stringResource(R.string.host_scan), { scanning = true }, icon = R.drawable.ic_qr_code_scanner) }
                }
            }
        }
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
        if (isSsh) {
            val changed = ssh.changedHostKey
            if (changed != null) {
                item(key = "host-key") {
                    SectionHeader(stringResource(R.string.host_ssh_host_key))
                    Group { row { ActionRow(stringResource(R.string.host_ssh_trust), ssh.onTrust) } }
                    Footnote(stringResource(R.string.host_ssh_host_key_changed, SshKeys.fingerprint(changed) ?: changed))
                }
            }
            item(key = "ssh") {
                SectionHeader(stringResource(R.string.host_ssh))
                Group {
                    listOf(
                        SshSignIn.PhoneKey to R.string.host_ssh_phone_key,
                        SshSignIn.OwnKey to R.string.host_ssh_own_key,
                        SshSignIn.Password to R.string.host_ssh_password,
                    ).forEach { (choice, label) ->
                        row { CheckRow(stringResource(label), signIn == choice, { signIn = choice }) }
                    }
                }
            }
            item(key = "ssh-secret") {
                Column(Modifier.padding(top = 16.dp)) {
                    when (signIn) {
                        SshSignIn.PhoneKey -> {
                            val line by produceState<String?>(null) { value = ssh.phoneKey() }
                            line?.let { CommandBlock(it, mono, Modifier.padding(horizontal = 16.dp)) }
                            Footnote(stringResource(R.string.host_ssh_phone_key_hint))
                        }
                        SshSignIn.OwnKey -> Group {
                            row {
                                Field(
                                    label = stringResource(R.string.host_ssh_private_key),
                                    value = privateKey,
                                    onValue = { privateKey = it },
                                    placeholder = stringResource(
                                        if (ssh.hasPrivateKey) R.string.host_ssh_saved else R.string.host_ssh_private_key_placeholder,
                                    ),
                                    style = RaxType.footnote.copy(fontFamily = mono),
                                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                    singleLine = false,
                                )
                            }
                            if (keyCheck == SshKeys.Check.NeedsPassphrase || keyCheck == SshKeys.Check.WrongPassphrase || passphrase.isNotEmpty()) {
                                row {
                                    Field(
                                        label = stringResource(R.string.host_ssh_passphrase),
                                        value = passphrase,
                                        onValue = { passphrase = it },
                                        placeholder = "",
                                        style = RaxType.body,
                                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                        hidden = true,
                                    )
                                }
                            }
                        }
                        SshSignIn.Password -> Group {
                            row {
                                Field(
                                    label = stringResource(R.string.host_ssh_password),
                                    value = password,
                                    onValue = { password = it },
                                    placeholder = if (ssh.hasPassword) stringResource(R.string.host_ssh_saved) else "",
                                    style = RaxType.body,
                                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                                    onDone = save,
                                    hidden = true,
                                )
                            }
                        }
                    }
                    if (secretProblem != null && (checkSecrets || keyCheck != SshKeys.Check.Ok)) Footnote(stringResource(secretProblem))
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

    if (scanning) {
        QrScanner(
            accept = { text ->
                val scanned = pairingAddress(text) ?: return@QrScanner false
                address = scanned
                tailnet = looksLikeTailnet(scanned)
                tailnetTouched = false
                if (name.isBlank()) name = rexDeviceName(defaultName(scanned))
                checkAddress = true
                scanning = false
                true
            },
            askCamera = askCamera,
            onClose = { scanning = false },
        )
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

/** What the editor needs for a host that Rax reaches through SSH. */
class SshEditing(
    /** The line for authorized_keys of the key Rax made on this phone, made the first time it is asked for. */
    val phoneKey: suspend () -> String,
    /** Whether a private key or a password is saved for the host. */
    val hasPrivateKey: Boolean,
    val hasPassword: Boolean,
    /** The host key the computer showed instead of the saved one, or null. */
    val changedHostKey: String?,
    val onTrust: () -> Unit,
)

/** What the user typed to sign in. A null key or password keeps the saved one. */
data class SshSecrets(val privateKey: String?, val passphrase: String, val password: String?)

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
    singleLine: Boolean = true,
    hidden: Boolean = false,
) {
    val c = Rax.colors
    // The label and the padding are part of the field, so a tap anywhere on the row focuses it.
    BasicTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier.fillMaxWidth(),
        textStyle = style.copy(color = c.text),
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 8,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
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

/**
 * The address in a scanned QR code, such as the one `rex server tailscale pair` shows, or null when
 * the code holds no Rex address. Only a full URL counts, since any word would parse as a host name.
 * A secure address on the usual port becomes its bare name, the way a person types it.
 */
fun pairingAddress(text: String): String? {
    val t = text.trim()
    if (!t.contains("://")) return null
    val endpoint = (RexEndpoint.parse(t, looksLikeTailnet(t)) as? RexEndpoint.Companion.Parsed.Ok)?.endpoint ?: return null
    return if (endpoint.secure && endpoint.base.port == 443) endpoint.base.host else t
}

/** Whether [text] is a private key that [passphrase] opens. Empty text is fine, because it keeps the saved key. */
private suspend fun checkKey(text: String, passphrase: String): SshKeys.Check =
    if (text.isBlank()) SshKeys.Check.Ok else withContext(Dispatchers.Default) { SshKeys.check(text.trim(), passphrase) }

/** Rex names its own tailnet device after the computer, as `rex` or `studio-rex`. */
fun rexDeviceName(hostName: String): String = hostName.removeSuffix("-rex").removePrefix("rex-").ifEmpty { hostName }
