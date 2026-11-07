package de.uditkaro.rax.ui.terminal

import android.os.SystemClock
import android.view.KeyEvent
import io.github.sagernet.libghostty.GhosttyTerminalView

/**
 * A key the bar can send. It goes through the terminal view as a real key event, so the terminal
 * encodes it the way the running program asked for (cursor keys mode, the kitty protocol).
 */
enum class TermKey(val label: String?, val keyCode: Int, val meta: Int = 0, val icon: Int? = null) {
    Esc("esc", KeyEvent.KEYCODE_ESCAPE),
    Tab("tab", KeyEvent.KEYCODE_TAB),
    ShiftTab("⇧tab", KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
    Left(null, KeyEvent.KEYCODE_DPAD_LEFT, icon = de.uditkaro.rax.R.drawable.ic_keyboard_arrow_left),
    Up(null, KeyEvent.KEYCODE_DPAD_UP, icon = de.uditkaro.rax.R.drawable.ic_keyboard_arrow_up),
    Down(null, KeyEvent.KEYCODE_DPAD_DOWN, icon = de.uditkaro.rax.R.drawable.ic_keyboard_arrow_down),
    Right(null, KeyEvent.KEYCODE_DPAD_RIGHT, icon = de.uditkaro.rax.R.drawable.ic_keyboard_arrow_right),
    Enter(null, KeyEvent.KEYCODE_ENTER, icon = de.uditkaro.rax.R.drawable.ic_keyboard_return),
    Home("home", KeyEvent.KEYCODE_MOVE_HOME),
    End("end", KeyEvent.KEYCODE_MOVE_END),
    PageUp("pgup", KeyEvent.KEYCODE_PAGE_UP),
    PageDown("pgdn", KeyEvent.KEYCODE_PAGE_DOWN),
    CtrlC("^C", KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
    CtrlD("^D", KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
    CtrlZ("^Z", KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
    CtrlL("^L", KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
    CtrlR("^R", KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
}

fun GhosttyTerminalView.press(key: TermKey, repeat: Int = 0) {
    val now = SystemClock.uptimeMillis()
    dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key.keyCode, repeat, key.meta))
    dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key.keyCode, 0, key.meta))
}
