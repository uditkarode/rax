package de.uditkaro.rax.ui.settings

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Settings
import de.uditkaro.rax.terminal.TerminalFonts
import de.uditkaro.rax.terminal.TerminalFonts.ImportResult
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.ActionRow
import de.uditkaro.rax.ui.components.CheckRow
import de.uditkaro.rax.ui.components.Footnote
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.RaxSlider
import de.uditkaro.rax.ui.components.SectionHeader
import de.uditkaro.rax.activeTheme
import de.uditkaro.rax.ui.terminal.TerminalPreview
import de.uditkaro.rax.ui.terminal.rememberTerminalFont
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FontScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.collectAsStateWithLifecycle()
    val catalog by graph.themes.collectAsStateWithLifecycle()
    val imported by graph.fonts.importedChoices.collectAsStateWithLifecycle()
    val s = settings ?: return
    val themes = catalog ?: return
    val theme = activeTheme(s, themes)
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<ImportResult.Reason?>(null) }

    // The slider moves the preview live and writes the size when the finger lifts.
    var size by remember(s.fontSize) { mutableFloatStateOf(s.fontSize) }
    val terminalFont = rememberTerminalFont(graph, s.fontId)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = graph.fonts.import(uris)) {
                is ImportResult.Added -> {
                    problem = null
                    val added = graph.fonts.importedChoices.value.firstOrNull { it.name == result.names.firstOrNull() }
                    if (added != null) graph.updateSettings { setFont(added.id) }
                }
                is ImportResult.Failed -> problem = result.reason
            }
        }
    }

    LargeTitleScreen(stringResource(R.string.font_title), onBack) {
        item(key = "preview") {
            Box(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(androidx.compose.ui.graphics.Color(theme.terminal.background))
                    .border(1.dp, Rax.colors.stroke, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (terminalFont != null) TerminalPreview(terminalFont, size, theme.terminal)
            }
        }
        item(key = "size") {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("A", style = RaxType.footnote, color = Rax.colors.textSecondary)
                RaxSlider(
                    value = size,
                    onValueChange = { size = it.roundToInt().toFloat() },
                    valueRange = Settings.FONT_SIZES,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    steps = (Settings.FONT_SIZES.endInclusive - Settings.FONT_SIZES.start).toInt() - 1,
                    onValueChangeFinished = { graph.updateSettings { setFontSize(size) } },
                )
                Text("A", style = RaxType.title, color = Rax.colors.textSecondary)
                Text(
                    size.roundToInt().toString(),
                    Modifier.width(36.dp).padding(start = 12.dp),
                    style = RaxType.bodyStrong,
                    color = Rax.colors.text,
                )
            }
        }
        item(key = "bundled") {
            SectionHeader(stringResource(R.string.font_bundled))
            Group {
                graph.fonts.bundledChoices.forEach { font ->
                    row { FontRow(graph, font, font.id == s.fontId) { graph.updateSettings { setFont(font.id) } } }
                }
            }
        }
        item(key = "imported") {
            SectionHeader(stringResource(R.string.font_imported))
            Group {
                imported.forEach { font ->
                    row {
                        FontRow(
                            graph, font, font.id == s.fontId,
                            onRemove = {
                                scope.launch {
                                    if (s.fontId == font.id) graph.updateSettings { setFont(Settings.DEFAULT_FONT) }
                                    graph.fonts.remove(font.id)
                                }
                            },
                        ) { graph.updateSettings { setFont(font.id) } }
                    }
                }
                row { ActionRow(stringResource(R.string.font_add), onClick = { pick.launch(arrayOf("*/*")) }) }
            }
            val message = when (problem) {
                ImportResult.Reason.NotAFont -> stringResource(R.string.font_error_not_font)
                ImportResult.Reason.NotMonospaced -> stringResource(R.string.font_error_proportional)
                ImportResult.Reason.Unreadable -> stringResource(R.string.font_error_unreadable)
                null -> null
            }
            if (message != null) {
                Text(
                    message,
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    style = RaxType.footnote,
                    color = Rax.colors.danger,
                )
            }
            Footnote(stringResource(R.string.font_add_footnote))
        }
    }
}

@Composable
private fun FontRow(
    graph: AppGraph,
    font: TerminalFonts.Choice,
    selected: Boolean,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val typeface by produceState<Typeface?>(null, font.id) {
        value = withContext(Dispatchers.IO) { graph.fonts.preview(font.id) }
    }
    val family = remember(typeface) { typeface?.let { FontFamily(it) } }
    var menu by remember { mutableStateOf(false) }
    Box {
        CheckRow(
            title = font.name,
            selected = selected,
            onClick = onClick,
            onLongClick = if (onRemove != null) ({ menu = true }) else null,
            titleStyle = RaxType.body.copy(fontFamily = family ?: RaxType.body.fontFamily, fontSize = 16.sp),
        )
        if (onRemove != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.font_remove), color = Rax.colors.danger) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}
