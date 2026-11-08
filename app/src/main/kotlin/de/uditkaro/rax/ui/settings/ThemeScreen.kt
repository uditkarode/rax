package de.uditkaro.rax.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Appearance
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.theme.RexTheme
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.SectionHeader
import de.uditkaro.rax.ui.components.SegmentedControl
import de.uditkaro.rax.ui.terminal.rememberTerminalFontFamily

@Composable
fun ThemeScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.collectAsStateWithLifecycle()
    val catalog by graph.themes.collectAsStateWithLifecycle()
    val s = settings ?: return
    val themes = catalog ?: return
    val mono = rememberTerminalFontFamily(graph, s.fontId)
    val modes = listOf(Appearance.System, Appearance.Light, Appearance.Dark)
    val labels = listOf(
        stringResource(R.string.appearance_automatic),
        stringResource(R.string.appearance_light),
        stringResource(R.string.appearance_dark),
    )
    val darkFirst = s.appearance != Appearance.Light && Rax.colors.dark

    LargeTitleScreen(stringResource(R.string.theme_title), onBack) {
        item(key = "appearance") {
            SegmentedControl(
                labels,
                modes.indexOf(s.appearance),
                onSelect = { i -> graph.updateSettings { setAppearance(modes[i]) } },
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        val dark = themes.dark(s.darkThemeId)
        val light = themes.light(s.lightThemeId)
        val sections = listOf(
            Triple(R.string.theme_dark, themes.dark, dark.id),
            Triple(R.string.theme_light, themes.light, light.id),
        ).let { if (darkFirst) it else it.reversed() }
        for ((title, list, selectedId) in sections) {
            themeSection(title, list, selectedId, mono) { graph.updateSettings { setTheme(it.id, it.dark) } }
        }
    }
}

private fun LazyListScope.themeSection(
    title: Int,
    themes: List<RexTheme>,
    selectedId: String,
    mono: FontFamily,
    onPick: (RexTheme) -> Unit,
) {
    item(key = "header-$title") { SectionHeader(stringResource(title), Modifier.padding(top = 8.dp)) }
    items(themes.chunked(3), key = { row -> row.first().id }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { theme ->
                ThemeTile(theme, theme.id == selectedId, mono, { onPick(theme) }, Modifier.weight(1f))
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ThemeTile(theme: RexTheme, selected: Boolean, mono: FontFamily, onClick: () -> Unit, modifier: Modifier) {
    val c = Rax.colors
    val t = theme.terminal
    fun col(argb: Int) = Color(argb)
    val line = TextStyle(fontFamily = mono, fontSize = 10.sp, lineHeight = 14.sp)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .border(2.dp, if (selected) c.accent else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(4.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(col(t.background))
                    .border(1.dp, theme.colors.stroke, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 9.dp)
                    // The sample lines are a picture of the theme, so a screen reader reads only its name.
                    .clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(col(t.palette[4]))) { append("~/rax ") }
                        withStyle(SpanStyle(col(t.palette[5]))) { append("main") }
                    },
                    style = line, maxLines = 1, softWrap = false,
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(col(t.palette[2]))) { append("❯ ") }
                        withStyle(SpanStyle(col(t.foreground))) { append("git log") }
                    },
                    style = line, maxLines = 1, softWrap = false,
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(col(t.palette[3]))) { append("6fe359c ") }
                        withStyle(SpanStyle(col(t.palette[8]))) { append("feat") }
                    },
                    style = line, maxLines = 1, softWrap = false,
                )
            }
        }
        Text(
            theme.name,
            Modifier.padding(horizontal = 4.dp),
            style = RaxType.footnote.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) c.text else c.textSecondary,
            maxLines = 2,
        )
    }
}
