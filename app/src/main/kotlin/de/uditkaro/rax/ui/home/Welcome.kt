package de.uditkaro.rax.ui.home

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.IconAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val LAN_COMMAND = "rex server start \\\n  --extra-listen :7297 \\\n  --allow-anonymous"

/** What Home shows before any computer is added: how to make Rex reachable, in two short parts. */
fun LazyListScope.welcome(mono: FontFamily, onTailscale: () -> Unit) {
    item(key = "welcome") {
        val c = Rax.colors
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.welcome_body), Modifier.padding(top = 6.dp), style = RaxType.body, color = c.textSecondary)

            Text(stringResource(R.string.welcome_lan_title), Modifier.padding(top = 32.dp), style = RaxType.headline, color = c.text)
            Text(stringResource(R.string.welcome_lan_body), Modifier.padding(top = 6.dp), style = RaxType.body, color = c.textSecondary)
            CommandBlock(LAN_COMMAND, mono, Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.welcome_lan_warning), Modifier.padding(top = 8.dp), style = RaxType.footnote, color = c.textSecondary)

            Text(stringResource(R.string.welcome_tailnet_title), Modifier.padding(top = 28.dp), style = RaxType.headline, color = c.text)
            Text(stringResource(R.string.welcome_tailnet_body), Modifier.padding(top = 6.dp), style = RaxType.body, color = c.textSecondary)
            Text(
                stringResource(R.string.welcome_tailnet_find),
                Modifier.padding(top = 4.dp).clickable(role = Role.Button, onClick = onTailscale).padding(vertical = 8.dp),
                style = RaxType.body,
                color = c.link,
            )
        }
    }
}

/** Shell text with a copy button. Copying puts the command on one line. */
@Composable
fun CommandBlock(command: String, mono: FontFamily, modifier: Modifier = Modifier) {
    val c = Rax.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.card)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            command,
            Modifier.weight(1f).padding(vertical = 8.dp),
            style = TextStyle(fontFamily = mono, fontSize = 13.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
            color = c.text,
        )
        IconAction(
            if (copied) R.drawable.ic_check else R.drawable.ic_content_copy,
            stringResource(if (copied) R.string.copied else R.string.copy),
            onClick = {
                val oneLine = command.replace("\\\n", "").replace(Regex("\\s+"), " ").trim()
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("command", oneLine)))
                    copied = true
                    delay(1500)
                    copied = false
                }
            },
            tint = c.textSecondary,
        )
    }
}
