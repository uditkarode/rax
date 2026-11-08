package de.uditkaro.rax.ui.terminal

import io.github.xchacha20_poly1305.libghostty.GhosttyKey
import io.github.xchacha20_poly1305.libghostty.GhosttyMods
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyTerminalState

/**
 * A key the bar can send. The terminal encodes it the way the running program asked for (cursor
 * keys mode, the kitty protocol). [text] is what a letter key types, which a Ctrl combination is
 * built from.
 */
enum class TermKey(val label: String?, val key: GhosttyKey, val mods: Int = 0, val text: String? = null, val icon: Int? = null) {
    Esc("esc", GhosttyKey.ESCAPE),
    Tab("tab", GhosttyKey.TAB),
    ShiftTab("⇧tab", GhosttyKey.TAB, GhosttyMods.SHIFT),
    Left(null, GhosttyKey.ARROW_LEFT, icon = de.uditkaro.rax.R.drawable.ic_keyboard_arrow_left),
    Up(null, GhosttyKey.ARROW_UP, icon = de.uditkaro.rax.R.drawable.ic_keyboard_arrow_up),
    Down(null, GhosttyKey.ARROW_DOWN, icon = de.uditkaro.rax.R.drawable.ic_keyboard_arrow_down),
    Right(null, GhosttyKey.ARROW_RIGHT, icon = de.uditkaro.rax.R.drawable.ic_keyboard_arrow_right),
    Enter(null, GhosttyKey.ENTER, icon = de.uditkaro.rax.R.drawable.ic_keyboard_return),
    Home("home", GhosttyKey.HOME),
    End("end", GhosttyKey.END),
    PageUp("pgup", GhosttyKey.PAGE_UP),
    PageDown("pgdn", GhosttyKey.PAGE_DOWN),
    CtrlC("^C", GhosttyKey.KEY_C, GhosttyMods.CTRL, "c"),
    CtrlD("^D", GhosttyKey.KEY_D, GhosttyMods.CTRL, "d"),
    CtrlZ("^Z", GhosttyKey.KEY_Z, GhosttyMods.CTRL, "z"),
    CtrlL("^L", GhosttyKey.KEY_L, GhosttyMods.CTRL, "l"),
    CtrlR("^R", GhosttyKey.KEY_R, GhosttyMods.CTRL, "r"),
}

fun GhosttyTerminalState.press(key: TermKey) = sendKey(key.key, key.mods, key.text)
