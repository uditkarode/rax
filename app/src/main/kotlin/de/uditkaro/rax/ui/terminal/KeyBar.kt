package de.uditkaro.rax.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.RaxIcon
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyExtraKeysState.StickyState
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyTerminalState
import io.github.xchacha20_poly1305.libghostty.compose.rememberGhosttyExtraKeysState

/**
 * A tap on Ctrl or Alt applies it to the next key, and a double tap keeps it on. Arrows repeat
 * while held. Holding Tab sends Shift+Tab. Ctrl and Alt apply only while the bar shows, and turn
 * off when it goes.
 */
@Composable
fun KeyBar(terminal: GhosttyTerminalState, onHideKeyboard: () -> Unit, modifier: Modifier = Modifier) {
    val c = Rax.colors
    val extraKeys = rememberGhosttyExtraKeysState()
    DisposableEffect(terminal, extraKeys) {
        terminal.stickyModifiers = extraKeys
        onDispose { if (terminal.stickyModifiers === extraKeys) terminal.stickyModifiers = null }
    }
    val ctrl by extraKeys.ctrl.collectAsState()
    val alt by extraKeys.alt.collectAsState()
    Row(
        modifier.fillMaxWidth().background(c.sheet).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Key("esc", onTap = { terminal.press(TermKey.Esc) })
            Key("ctrl", state = ctrl, onTap = { extraKeys.toggleCtrl() })
            Key("alt", state = alt, onTap = { extraKeys.toggleAlt() })
            Key("tab", onTap = { terminal.press(TermKey.Tab) }, onHold = { terminal.press(TermKey.ShiftTab) })
            Key(icon = R.drawable.ic_keyboard_arrow_left, label = stringResource(R.string.key_left), repeat = true, onTap = { terminal.press(TermKey.Left) })
            Key(icon = R.drawable.ic_keyboard_arrow_up, label = stringResource(R.string.key_up), repeat = true, onTap = { terminal.press(TermKey.Up) })
            Key(icon = R.drawable.ic_keyboard_arrow_down, label = stringResource(R.string.key_down), repeat = true, onTap = { terminal.press(TermKey.Down) })
            Key(icon = R.drawable.ic_keyboard_arrow_right, label = stringResource(R.string.key_right), repeat = true, onTap = { terminal.press(TermKey.Right) })
        }
        Spacer(Modifier.width(6.dp))
        Key(icon = R.drawable.ic_keyboard_hide, label = stringResource(R.string.key_hide_keyboard), onTap = onHideKeyboard, plain = true)
    }
}

/**
 * One key. [onHold] fires instead of [onTap] after a long press; with [repeat], holding repeats
 * [onTap] the way a hardware key does.
 */
@Composable
fun Key(
    text: String? = null,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    label: String? = text,
    state: StickyState = StickyState.INACTIVE,
    repeat: Boolean = false,
    plain: Boolean = false,
    accent: Boolean = false,
    height: Dp = 40.dp,
    onHold: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    val c = Rax.colors
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val background = when {
        accent -> c.accent
        state == StickyState.LOCKED -> c.accent
        state == StickyState.ARMED -> c.accentContainer
        pressed -> c.control
        plain -> Color.Transparent
        else -> c.card
    }
    val content = when {
        accent || state == StickyState.LOCKED -> c.onAccent
        state == StickyState.ARMED -> c.onAccentContainer
        else -> c.text
    }
    Box(
        modifier
            .height(height)
            .widthIn(min = 44.dp)
            .clip(if (accent) CircleShape else RoundedCornerShape(10.dp))
            .background(background)
            .semantics {
                role = Role.Button
                label?.let { contentDescription = it }
                if (state != StickyState.INACTIVE) stateDescription = state.name.lowercase()
                onClick { tap(); true }
            }
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    val first = withTimeoutOrNull(LONG_PRESS_MS) { waitForRelease() }
                    val h = hold
                    when {
                        first == Release.Up -> tap()
                        first == Release.Cancelled -> Unit
                        h != null -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            h()
                            waitForRelease()
                        }
                        repeat -> {
                            tap()
                            while (withTimeoutOrNull(REPEAT_MS) { waitForRelease() } == null) tap()
                        }
                        else -> if (waitForRelease() == Release.Up) tap()
                    }
                    pressed = false
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            RaxIcon(icon, null, tint = content, size = 22.dp)
        } else if (text != null) {
            Text(text, style = RaxType.subhead.copy(fontWeight = FontWeight.Medium), color = content)
        }
    }
}

private enum class Release { Up, Cancelled }

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForRelease(): Release =
    if (waitForUpOrCancellation() != null) Release.Up else Release.Cancelled

private const val LONG_PRESS_MS = 420L
private const val REPEAT_MS = 60L

/** The keys a pane needs most without the keyboard: answering a prompt, moving through a menu. */
@Composable
fun Dock(
    terminal: GhosttyTerminalState,
    onKeyboard: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Rax.colors
    Row(
        modifier
            .clip(RoundedCornerShape(30.dp))
            .background(c.card)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(icon = R.drawable.ic_keyboard, label = stringResource(R.string.key_show_keyboard), plain = true, height = 48.dp, onTap = onKeyboard)
        Key("esc", plain = true, height = 48.dp, onTap = { terminal.press(TermKey.Esc) })
        Key(icon = R.drawable.ic_keyboard_arrow_up, label = stringResource(R.string.key_up), plain = true, repeat = true, height = 48.dp, onTap = { terminal.press(TermKey.Up) })
        Key(icon = R.drawable.ic_keyboard_arrow_down, label = stringResource(R.string.key_down), plain = true, repeat = true, height = 48.dp, onTap = { terminal.press(TermKey.Down) })
        Key(
            icon = R.drawable.ic_keyboard_return,
            label = stringResource(R.string.key_enter),
            accent = true,
            height = 48.dp,
            modifier = Modifier.size(48.dp),
            onTap = { terminal.press(TermKey.Enter) },
        )
        Key(icon = R.drawable.ic_keyboard_command_key, label = stringResource(R.string.key_more), plain = true, height = 48.dp, onTap = onMore)
    }
}
