package de.uditkaro.rax.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType

/** Asks for a new name, with the old one selected so typing replaces it. */
@Composable
fun RenameDialog(title: String, current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Rax.colors
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val done = {
        onDismiss()
        if (value.text.trim() != current) onRename(value.text.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = RaxType.body.copy(color = c.text),
                singleLine = true,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
                // The box around the text is part of the field, so a tap anywhere in it focuses it.
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        inner()
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = done) { Text(stringResource(R.string.rename), color = c.link) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = c.textSecondary) } },
        containerColor = c.sheet,
        titleContentColor = c.text,
        textContentColor = c.textSecondary,
    )
}

/** Confirms an action that cannot be undone. [action] names it on the button, in the danger colour. */
@Composable
fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Rax.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action, color = c.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = c.textSecondary) } },
        containerColor = c.sheet,
        titleContentColor = c.text,
        textContentColor = c.textSecondary,
    )
}
