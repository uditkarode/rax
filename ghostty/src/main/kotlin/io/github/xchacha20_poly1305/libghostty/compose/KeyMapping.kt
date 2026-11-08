package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import io.github.xchacha20_poly1305.libghostty.GhosttyKey
import io.github.xchacha20_poly1305.libghostty.GhosttyMods

/** The physical key; [GhosttyKey.UNIDENTIFIED] for keys a terminal has no use for. */
internal val Key.ghosttyKey: GhosttyKey
    get() = composeKeys[this] ?: GhosttyKey.UNIDENTIFIED

internal val KeyEvent.ghosttyKey: GhosttyKey
    get() = key.ghosttyKey

/** The modifiers every platform reports; platforms add lock and side bits they know. */
internal val KeyEvent.ghosttyMods: Int
    get() {
        var mods = 0
        if (isShiftPressed) {
            mods = mods or GhosttyMods.SHIFT
        }
        if (isCtrlPressed) {
            mods = mods or GhosttyMods.CTRL
        }
        if (isAltPressed) {
            mods = mods or GhosttyMods.ALT
        }
        if (isMetaPressed) {
            mods = mods or GhosttyMods.SUPER
        }
        return mods
    }

internal val GhosttyKey.isModifier: Boolean
    get() = this in modifierKeys

private val modifierKeys = setOf(
    GhosttyKey.SHIFT_LEFT, GhosttyKey.SHIFT_RIGHT,
    GhosttyKey.CONTROL_LEFT, GhosttyKey.CONTROL_RIGHT,
    GhosttyKey.ALT_LEFT, GhosttyKey.ALT_RIGHT,
    GhosttyKey.META_LEFT, GhosttyKey.META_RIGHT,
    GhosttyKey.CAPS_LOCK,
)

/** The key that types [char] on a US layout, for synthesizing modified keys from typed text. */
internal fun keyForChar(char: Char): GhosttyKey {
    return when (val lower = char.lowercaseChar()) {
        in 'a'..'z' -> letterKeys[lower - 'a']
        in '0'..'9' -> digitKeys[lower - '0']
        ' ' -> GhosttyKey.SPACE
        '[' -> GhosttyKey.BRACKET_LEFT
        ']' -> GhosttyKey.BRACKET_RIGHT
        '-' -> GhosttyKey.MINUS
        '=' -> GhosttyKey.EQUAL
        '\\' -> GhosttyKey.BACKSLASH
        '/' -> GhosttyKey.SLASH
        '.' -> GhosttyKey.PERIOD
        ',' -> GhosttyKey.COMMA
        ';' -> GhosttyKey.SEMICOLON
        '\'' -> GhosttyKey.QUOTE
        '`' -> GhosttyKey.BACKQUOTE
        else -> GhosttyKey.UNIDENTIFIED
    }
}

private val letterKeys = ('A'..'Z').map { GhosttyKey.valueOf("KEY_$it") }
private val digitKeys = (0..9).map { GhosttyKey.valueOf("DIGIT_$it") }

private val composeKeys: Map<Key, GhosttyKey> = buildMap {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { i, key -> put(key, letterKeys[i]) }
    val digits =
        listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    digits.forEachIndexed { i, key -> put(key, digitKeys[i]) }
    val numpad = listOf(
        Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
        Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
    )
    numpad.forEachIndexed { i, key -> put(key, GhosttyKey.valueOf("NUMPAD_$i")) }
    val functions =
        listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
    functions.forEachIndexed { i, key -> put(key, GhosttyKey.valueOf("F${i + 1}")) }

    put(Key.Grave, GhosttyKey.BACKQUOTE)
    put(Key.Backslash, GhosttyKey.BACKSLASH)
    put(Key.LeftBracket, GhosttyKey.BRACKET_LEFT)
    put(Key.RightBracket, GhosttyKey.BRACKET_RIGHT)
    put(Key.Comma, GhosttyKey.COMMA)
    put(Key.Equals, GhosttyKey.EQUAL)
    put(Key.Minus, GhosttyKey.MINUS)
    put(Key.Period, GhosttyKey.PERIOD)
    put(Key.Apostrophe, GhosttyKey.QUOTE)
    put(Key.Semicolon, GhosttyKey.SEMICOLON)
    put(Key.Slash, GhosttyKey.SLASH)
    put(Key.AltLeft, GhosttyKey.ALT_LEFT)
    put(Key.AltRight, GhosttyKey.ALT_RIGHT)
    put(Key.Backspace, GhosttyKey.BACKSPACE)
    put(Key.CapsLock, GhosttyKey.CAPS_LOCK)
    put(Key.CtrlLeft, GhosttyKey.CONTROL_LEFT)
    put(Key.CtrlRight, GhosttyKey.CONTROL_RIGHT)
    put(Key.Enter, GhosttyKey.ENTER)
    put(Key.DirectionCenter, GhosttyKey.ENTER)
    put(Key.MetaLeft, GhosttyKey.META_LEFT)
    put(Key.MetaRight, GhosttyKey.META_RIGHT)
    put(Key.ShiftLeft, GhosttyKey.SHIFT_LEFT)
    put(Key.ShiftRight, GhosttyKey.SHIFT_RIGHT)
    put(Key.Spacebar, GhosttyKey.SPACE)
    put(Key.Tab, GhosttyKey.TAB)
    put(Key.Delete, GhosttyKey.DELETE)
    put(Key.MoveEnd, GhosttyKey.END)
    put(Key.MoveHome, GhosttyKey.HOME)
    put(Key.Insert, GhosttyKey.INSERT)
    put(Key.PageDown, GhosttyKey.PAGE_DOWN)
    put(Key.PageUp, GhosttyKey.PAGE_UP)
    put(Key.DirectionDown, GhosttyKey.ARROW_DOWN)
    put(Key.DirectionLeft, GhosttyKey.ARROW_LEFT)
    put(Key.DirectionRight, GhosttyKey.ARROW_RIGHT)
    put(Key.DirectionUp, GhosttyKey.ARROW_UP)
    put(Key.PrintScreen, GhosttyKey.PRINT_SCREEN)
    put(Key.Break, GhosttyKey.PAUSE)
    put(Key.NumLock, GhosttyKey.NUM_LOCK)
    put(Key.ScrollLock, GhosttyKey.SCROLL_LOCK)
    put(Key.NumPadAdd, GhosttyKey.NUMPAD_ADD)
    put(Key.NumPadComma, GhosttyKey.NUMPAD_COMMA)
    put(Key.NumPadDot, GhosttyKey.NUMPAD_DECIMAL)
    put(Key.NumPadDivide, GhosttyKey.NUMPAD_DIVIDE)
    put(Key.NumPadEnter, GhosttyKey.NUMPAD_ENTER)
    put(Key.NumPadEquals, GhosttyKey.NUMPAD_EQUAL)
    put(Key.NumPadMultiply, GhosttyKey.NUMPAD_MULTIPLY)
    put(Key.NumPadSubtract, GhosttyKey.NUMPAD_SUBTRACT)
    put(Key.Escape, GhosttyKey.ESCAPE)
}
