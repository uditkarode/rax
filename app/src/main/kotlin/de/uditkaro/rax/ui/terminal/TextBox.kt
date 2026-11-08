package de.uditkaro.rax.ui.terminal

import android.net.Uri
import android.view.inputmethod.InputContentInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.BundleCompat
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.IconAction
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyTerminalState

/**
 * A text field with the keyboard's usual help (autocorrect, suggestions, voice input, gesture
 * typing), which the terminal's own input turns off. Send pastes what was written into the pane
 * and presses Return. Images and other files the keyboard inserts go to [onFiles], with a
 * function that gives up the access to them once they have been read.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TextBox(
    terminal: GhosttyTerminalState,
    enabled: Boolean,
    onFiles: (List<Uri>, () -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Rax.colors
    val text = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val send = {
        val written = text.text.toString()
        if (written.isNotEmpty()) terminal.pasteText(written)
        terminal.press(TermKey.Enter)
        text.clearText()
    }
    val files = remember(onFiles) {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                val uris = ArrayList<Uri>()
                val rest = transferableContent.consume { item -> item.uri?.let(uris::add) != null }
                if (uris.isNotEmpty()) {
                    // Compose asked for access to a keyboard's content, and keeps its InputContentInfo here.
                    val info = transferableContent.platformTransferableContent?.extras
                        ?.let { BundleCompat.getParcelable(it, "EXTRA_INPUT_CONTENT_INFO", InputContentInfo::class.java) }
                    onFiles(uris) { info?.releasePermission() }
                }
                return rest
            }
        }
    }
    Row(
        modifier.fillMaxWidth().background(c.sheet).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconAction(R.drawable.ic_close, stringResource(R.string.text_box_close), onClose, tint = c.textSecondary)
        BasicTextField(
            state = text,
            modifier = Modifier.weight(1f).focusRequester(focus).contentReceiver(files),
            textStyle = RaxType.body.copy(color = c.text),
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
            ),
            onKeyboardAction = { if (enabled) send() },
            // The box around the text is part of the field, so a tap anywhere in it focuses it.
            decorator = { inner ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    inner()
                }
            },
        )
        Key(
            icon = R.drawable.ic_arrow_upward,
            label = stringResource(R.string.text_box_send),
            accent = true,
            enabled = enabled,
            padding = 0.dp,
            modifier = Modifier.padding(horizontal = 4.dp).size(40.dp),
            onTap = send,
        )
    }
}
