package de.uditkaro.rax.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours every screen draws with, taken from the Rex theme in use so the phone looks like the
 * desktop. Each has one job; screens never pick raw colours.
 */
@Immutable
data class RaxColors(
    val dark: Boolean,
    /** Behind everything. */
    val page: Color,
    /** A grouped list, a field group, a key. */
    val card: Color,
    /** A bottom sheet. */
    val sheet: Color,
    /** Dividers inside a card. */
    val stroke: Color,
    /** Outlines that must be seen, such as an unselected theme swatch. */
    val strokeStrong: Color,
    /** The plate behind a pressed or latched control. */
    val control: Color,
    val text: Color,
    /** Values, subtitles, section headers. */
    val textSecondary: Color,
    /** Only for marks that carry no meaning on their own: chevrons, placeholders, dividers in text. */
    val textTertiary: Color,
    /** The one action colour. Kept to buttons, links, selection and the cursor. */
    val accent: Color,
    /** The accent for text and icons: actions, links, checkmarks. */
    val link: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    /** Actions that remove something. */
    val danger: Color,
    /** A program that waits for the user. */
    val warning: Color,
    /** A host that is online. */
    val success: Color,
    val terminalBackground: Color,
    val terminalForeground: Color,
)

val LocalRaxColors = staticCompositionLocalOf<RaxColors> { error("RaxTheme not set") }

object Rax {
    val colors: RaxColors
        @Composable @ReadOnlyComposable get() = LocalRaxColors.current
}

@Composable
fun RaxTheme(theme: RexTheme, content: @Composable () -> Unit) {
    val c = theme.colors
    val scheme = remember(c) {
        val base = if (c.dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = c.accent,
            onPrimary = c.onAccent,
            primaryContainer = c.accentContainer,
            onPrimaryContainer = c.onAccentContainer,
            secondary = c.accent,
            onSecondary = c.onAccent,
            background = c.page,
            onBackground = c.text,
            surface = c.page,
            onSurface = c.text,
            surfaceVariant = c.card,
            onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.page,
            surfaceContainerLow = c.sheet,
            surfaceContainer = c.sheet,
            surfaceContainerHigh = c.sheet,
            surfaceContainerHighest = c.card,
            outline = c.strokeStrong,
            outlineVariant = c.stroke,
            error = c.danger,
            scrim = Color.Black,
        )
    }
    val selection = remember(c) { TextSelectionColors(handleColor = c.accent, backgroundColor = c.accent.copy(alpha = 0.35f)) }
    MaterialTheme(colorScheme = scheme, typography = RaxType.material) {
        CompositionLocalProvider(
            LocalRaxColors provides c,
            LocalTextSelectionColors provides selection,
            LocalIndication provides ripple(color = c.text),
            content = content,
        )
    }
}
