package de.uditkaro.rax.ui.terminal

import de.uditkaro.rax.rex.PaneTheme
import de.uditkaro.rax.theme.TerminalColors
import io.github.xchacha20_poly1305.libghostty.GhosttyTheme

fun TerminalColors.toGhostty(): GhosttyTheme = GhosttyTheme(
    foreground = foreground,
    background = background,
    cursorColor = cursor,
    selectionBackground = selectionBackground,
    selectionForeground = selectionForeground,
    palette = palette,
)

fun TerminalColors.toPane(dark: Boolean): PaneTheme {
    fun hex(color: Int) = "#%06x".format(color and 0xFFFFFF)
    return PaneTheme(hex(foreground), hex(background), hex(cursor), palette.map(::hex), dark)
}
