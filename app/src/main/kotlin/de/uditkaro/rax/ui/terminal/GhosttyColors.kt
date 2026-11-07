package de.uditkaro.rax.ui.terminal

import de.uditkaro.rax.theme.TerminalColors
import io.github.sagernet.libghostty.GhosttyTheme

fun TerminalColors.toGhostty(): GhosttyTheme = GhosttyTheme(
    foreground = foreground,
    background = background,
    cursorColor = cursor,
    selectionBackground = selectionBackground,
    selectionForeground = selectionForeground,
    palette = palette,
)
