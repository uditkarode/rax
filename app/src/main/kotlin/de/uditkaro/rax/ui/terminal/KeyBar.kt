package de.uditkaro.rax.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.semantics.disabled
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
fun KeyBar(terminal: GhosttyTerminalState, enabled: Boolean, onHideKeyboard: () -> Unit, modifier: Modifier = Modifier) {
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
        // The keys share the width, so all of them fit on a narrow phone.
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val key = Modifier.weight(1f)
            Key(TermKey.Esc.cap, key, label = stringResource(R.string.key_esc), enabled = enabled, padding = 2.dp, onTap = { terminal.press(TermKey.Esc) })
            Key("ctrl", key, label = stringResource(R.string.key_ctrl), state = ctrl, enabled = enabled, padding = 2.dp, onTap = { extraKeys.toggleCtrl() })
            Key("alt", key, label = stringResource(R.string.key_alt), state = alt, enabled = enabled, padding = 2.dp, onTap = { extraKeys.toggleAlt() })
            Key(
                TermKey.Tab.cap, key, label = stringResource(R.string.key_tab), enabled = enabled, padding = 2.dp,
                onHold = { terminal.press(TermKey.ShiftTab) }, onTap = { terminal.press(TermKey.Tab) },
            )
            for (arrow in listOf(TermKey.Left, TermKey.Up, TermKey.Down, TermKey.Right)) {
                Key(
                    modifier = key, icon = arrow.icon, label = stringResource(arrow.description), repeat = true, enabled = enabled, padding = 2.dp,
                    onTap = { terminal.press(arrow) },
                )
            }
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
    enabled: Boolean = true,
    height: Dp = 40.dp,
    padding: Dp = 12.dp,
    onHold: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    val c = Rax.colors
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val filled = accent || state != StickyState.INACTIVE
    val background = when {
        accent || state == StickyState.LOCKED -> c.accent
        state == StickyState.ARMED -> c.accentContainer
        pressed -> c.control
        plain -> Color.Transparent
        else -> c.card
    }.let {
        when {
            filled && !enabled -> it.copy(alpha = it.alpha * 0.38f)
            filled && pressed -> it.copy(alpha = it.alpha * 0.7f)
            else -> it
        }
    }
    val content = when {
        accent || state == StickyState.LOCKED -> c.onAccent
        state == StickyState.ARMED -> c.onAccentContainer
        else -> c.text
    }.let { if (enabled) it else it.copy(alpha = 0.38f) }
    val armed = stringResource(R.string.key_armed)
    val locked = stringResource(R.string.key_locked)
    Box(
        modifier
            .height(height)
            .widthIn(min = 44.dp)
            .clip(if (accent) CircleShape else RoundedCornerShape(10.dp))
            .background(background)
            .semantics {
                role = Role.Button
                label?.let { contentDescription = it }
                when (state) {
                    StickyState.ARMED -> stateDescription = armed
                    StickyState.LOCKED -> stateDescription = locked
                    StickyState.INACTIVE -> Unit
                }
                if (enabled) onClick { tap(); true } else disabled()
            }
            .then(if (enabled) Modifier.pointerInput(repeat) {
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
            } else Modifier)
            .padding(horizontal = padding),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            RaxIcon(icon, null, tint = content, size = 22.dp)
        } else if (text != null) {
            Text(text, style = RaxType.subhead.copy(fontWeight = FontWeight.Medium), color = content, maxLines = 1, softWrap = false)
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
    enabled: Boolean,
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
        Key(icon = R.drawable.ic_keyboard, label = stringResource(R.string.key_show_keyboard), plain = true, enabled = enabled, height = 48.dp, onTap = onKeyboard)
        Key(TermKey.Esc.cap, label = stringResource(R.string.key_esc), plain = true, enabled = enabled, height = 48.dp, onTap = { terminal.press(TermKey.Esc) })
        Key(icon = R.drawable.ic_keyboard_arrow_up, label = stringResource(R.string.key_up), plain = true, repeat = true, enabled = enabled, height = 48.dp, onTap = { terminal.press(TermKey.Up) })
        Key(icon = R.drawable.ic_keyboard_arrow_down, label = stringResource(R.string.key_down), plain = true, repeat = true, enabled = enabled, height = 48.dp, onTap = { terminal.press(TermKey.Down) })
        Key(
            icon = R.drawable.ic_keyboard_return,
            label = stringResource(R.string.key_enter),
            accent = true,
            enabled = enabled,
            height = 48.dp,
            modifier = Modifier.size(48.dp),
            onTap = { terminal.press(TermKey.Enter) },
        )
        Key(icon = R.drawable.ic_keyboard_command_key, label = stringResource(R.string.key_more), plain = true, enabled = enabled, height = 48.dp, onTap = onMore)
    }
}
