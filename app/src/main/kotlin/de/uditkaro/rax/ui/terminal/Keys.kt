package de.uditkaro.rax.ui.terminal

import androidx.annotation.StringRes
import de.uditkaro.rax.R
import io.github.xchacha20_poly1305.libghostty.GhosttyKey
import io.github.xchacha20_poly1305.libghostty.GhosttyMods
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyTerminalState

/**
 * A key the bar can send. The terminal encodes it the way the running program asked for (cursor
 * keys mode, the kitty protocol). [cap] is what the key shows, and [description] is what a screen
 * reader says. [text] is what a letter key types, which a Ctrl combination is built from.
 */
enum class TermKey(
    val cap: String?,
    @StringRes val description: Int,
    val key: GhosttyKey,
    val mods: Int = 0,
    val text: String? = null,
    val icon: Int? = null,
) {
    Esc("esc", R.string.key_esc, GhosttyKey.ESCAPE),
    Tab("tab", R.string.key_tab, GhosttyKey.TAB),
    ShiftTab("⇧tab", R.string.key_shift_tab, GhosttyKey.TAB, GhosttyMods.SHIFT),
    Left(null, R.string.key_left, GhosttyKey.ARROW_LEFT, icon = R.drawable.ic_keyboard_arrow_left),
    Up(null, R.string.key_up, GhosttyKey.ARROW_UP, icon = R.drawable.ic_keyboard_arrow_up),
    Down(null, R.string.key_down, GhosttyKey.ARROW_DOWN, icon = R.drawable.ic_keyboard_arrow_down),
    Right(null, R.string.key_right, GhosttyKey.ARROW_RIGHT, icon = R.drawable.ic_keyboard_arrow_right),
    Enter(null, R.string.key_enter, GhosttyKey.ENTER, icon = R.drawable.ic_keyboard_return),
    Home("home", R.string.key_home, GhosttyKey.HOME),
    End("end", R.string.key_end, GhosttyKey.END),
    PageUp("pgup", R.string.key_page_up, GhosttyKey.PAGE_UP),
    PageDown("pgdn", R.string.key_page_down, GhosttyKey.PAGE_DOWN),
    CtrlC("^C", R.string.key_ctrl_c, GhosttyKey.KEY_C, GhosttyMods.CTRL, "c"),
    CtrlD("^D", R.string.key_ctrl_d, GhosttyKey.KEY_D, GhosttyMods.CTRL, "d"),
    CtrlZ("^Z", R.string.key_ctrl_z, GhosttyKey.KEY_Z, GhosttyMods.CTRL, "z"),
    CtrlL("^L", R.string.key_ctrl_l, GhosttyKey.KEY_L, GhosttyMods.CTRL, "l"),
    CtrlR("^R", R.string.key_ctrl_r, GhosttyKey.KEY_R, GhosttyMods.CTRL, "r"),
}

fun GhosttyTerminalState.press(key: TermKey) = sendKey(key.key, key.mods, key.text)
