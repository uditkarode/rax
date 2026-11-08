package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xchacha20_poly1305.libghostty.GhosttyKey
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyExtraKeysState.StickyState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

public sealed interface GhosttyExtraKey {
    public data object Esc : GhosttyExtraKey

    public data object Tab : GhosttyExtraKey

    public data object Ctrl : GhosttyExtraKey

    public data object Alt : GhosttyExtraKey

    public data object ArrowLeft : GhosttyExtraKey

    public data object ArrowUp : GhosttyExtraKey

    public data object ArrowDown : GhosttyExtraKey

    public data object ArrowRight : GhosttyExtraKey

    public data class Symbol(public val text: String) : GhosttyExtraKey

    public data object Paste : GhosttyExtraKey

    public data object Divider : GhosttyExtraKey
}

@Immutable
public class GhosttyExtraKeysColors(
    public val container: Color,
    public val keyContainer: Color,
    public val keyContent: Color,
    public val armedKeyContainer: Color,
    public val armedKeyContent: Color,
    public val lockedKeyContainer: Color,
    public val lockedKeyContent: Color,
)

public object GhosttyExtraKeysDefaults {

    public val keys: List<GhosttyExtraKey> = listOf(
        GhosttyExtraKey.Esc,
        GhosttyExtraKey.Tab,
        GhosttyExtraKey.Ctrl,
        GhosttyExtraKey.Alt,
        GhosttyExtraKey.Divider,
        GhosttyExtraKey.ArrowLeft,
        GhosttyExtraKey.ArrowUp,
        GhosttyExtraKey.ArrowDown,
        GhosttyExtraKey.ArrowRight,
        GhosttyExtraKey.Divider,
        GhosttyExtraKey.Symbol("|"),
        GhosttyExtraKey.Symbol("/"),
        GhosttyExtraKey.Symbol("~"),
        GhosttyExtraKey.Symbol("-"),
        GhosttyExtraKey.Symbol("_"),
        GhosttyExtraKey.Symbol("`"),
        GhosttyExtraKey.Symbol("'"),
        GhosttyExtraKey.Symbol("\""),
        GhosttyExtraKey.Paste,
    )

    @Composable
    public fun colors(): GhosttyExtraKeysColors = GhosttyExtraKeysColors(
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        keyContainer = MaterialTheme.colorScheme.surfaceContainerHighest,
        keyContent = MaterialTheme.colorScheme.onSurface,
        armedKeyContainer = MaterialTheme.colorScheme.primaryContainer,
        armedKeyContent = MaterialTheme.colorScheme.onPrimaryContainer,
        lockedKeyContainer = MaterialTheme.colorScheme.primary,
        lockedKeyContent = MaterialTheme.colorScheme.onPrimary,
    )
}

/**
 * A row of keys a software keyboard lacks, sent to the terminal of [state]. While shown, its sticky Ctrl and Alt are
 * the terminal's [GhosttyTerminalState.stickyModifiers].
 */
@Composable
public fun GhosttyExtraKeysBar(
    state: GhosttyTerminalState,
    modifier: Modifier = Modifier,
    extraKeysState: GhosttyExtraKeysState = rememberGhosttyExtraKeysState(),
    keys: List<GhosttyExtraKey> = GhosttyExtraKeysDefaults.keys,
    colors: GhosttyExtraKeysColors = GhosttyExtraKeysDefaults.colors(),
) {
    DisposableEffect(state, extraKeysState) {
        state.stickyModifiers = extraKeysState
        onDispose {
            if (state.stickyModifiers === extraKeysState) state.stickyModifiers = null
        }
    }
    val ctrlState by extraKeysState.ctrl.collectAsState()
    val altState by extraKeysState.alt.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.container,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (key in keys) when (key) {
                GhosttyExtraKey.Esc -> KeyButton(state, colors, label = "ESC") {
                    state.sendKey(GhosttyKey.ESCAPE)
                }

                GhosttyExtraKey.Tab -> KeyButton(state, colors, label = "TAB") {
                    state.sendKey(GhosttyKey.TAB)
                }

                GhosttyExtraKey.Ctrl -> KeyButton(
                    state,
                    colors,
                    label = "CTRL",
                    containerColor = stickyContainerColor(ctrlState, colors),
                    contentColor = stickyContentColor(ctrlState, colors),
                ) {
                    extraKeysState.toggleCtrl()
                }

                GhosttyExtraKey.Alt -> KeyButton(
                    state,
                    colors,
                    label = "ALT",
                    containerColor = stickyContainerColor(altState, colors),
                    contentColor = stickyContentColor(altState, colors),
                ) {
                    extraKeysState.toggleAlt()
                }

                GhosttyExtraKey.ArrowLeft -> ArrowKeyButton(state, colors, "◀") {
                    state.sendKey(GhosttyKey.ARROW_LEFT)
                }

                GhosttyExtraKey.ArrowUp -> ArrowKeyButton(state, colors, "▲") {
                    state.sendKey(GhosttyKey.ARROW_UP)
                }

                GhosttyExtraKey.ArrowDown -> ArrowKeyButton(state, colors, "▼") {
                    state.sendKey(GhosttyKey.ARROW_DOWN)
                }

                GhosttyExtraKey.ArrowRight -> ArrowKeyButton(state, colors, "▶") {
                    state.sendKey(GhosttyKey.ARROW_RIGHT)
                }

                is GhosttyExtraKey.Symbol -> KeyButton(state, colors, label = key.text) {
                    state.sendText(key.text)
                }

                GhosttyExtraKey.Paste -> KeyButton(state, colors, icon = pasteIcon) {
                    state.paste()
                }

                GhosttyExtraKey.Divider -> KeyDivider(colors)
            }
        }
    }
}

private val keyShape = RoundedCornerShape(8.dp)

private fun stickyContainerColor(state: StickyState, colors: GhosttyExtraKeysColors): Color = when (state) {
    StickyState.INACTIVE -> colors.keyContainer
    StickyState.ARMED -> colors.armedKeyContainer
    StickyState.LOCKED -> colors.lockedKeyContainer
}

private fun stickyContentColor(state: StickyState, colors: GhosttyExtraKeysColors): Color = when (state) {
    StickyState.INACTIVE -> colors.keyContent
    StickyState.ARMED -> colors.armedKeyContent
    StickyState.LOCKED -> colors.lockedKeyContent
}

@Composable
private fun KeyButton(
    state: GhosttyTerminalState,
    colors: GhosttyExtraKeysColors,
    label: String? = null,
    icon: ImageVector? = null,
    containerColor: Color = colors.keyContainer,
    contentColor: Color = colors.keyContent,
    onPress: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = if (icon != null) {
            Modifier.size(36.dp)
        } else {
            Modifier.defaultMinSize(minWidth = 42.dp).height(36.dp)
        }
            .clip(keyShape)
            .indication(interactionSource, ripple())
            .ghosttyExtraKey(state, interactionSource = interactionSource, onPress = onPress),
        shape = keyShape,
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
            } else if (label != null) {
                Text(
                    text = label,
                    fontSize = if (label.length > 1) 10.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ArrowKeyButton(
    state: GhosttyTerminalState,
    colors: GhosttyExtraKeysColors,
    label: String,
    onPress: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .size(36.dp)
            .clip(keyShape)
            .indication(interactionSource, ripple())
            .ghosttyExtraKey(state, repeatable = true, interactionSource = interactionSource, onPress = onPress),
        shape = keyShape,
        color = colors.keyContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = colors.keyContent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun KeyDivider(colors: GhosttyExtraKeysColors) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(5.dp)
            .clip(CircleShape)
            .background(colors.keyContent.copy(alpha = 0.3f)),
    )
}

private val pasteIcon: ImageVector = ImageVector.Builder(
    name = "Paste",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(
        "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2C3.9,2 3,2.9 3,4v16c0,1.1 " +
                "0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 " +
                "-1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z",
    ),
    fill = SolidColor(Color.Black),
).build()

/**
 * Handles presses on a key button outside the terminal, such as one of an extra keys bar: fires [onPress] on release,
 * and when [repeatable] is set, repeatedly while held instead. A touch that a scrollable parent takes over as a drag
 * fires nothing, so the keys can sit in a scrolling row. Every gesture returns focus to the terminal of [state] so the
 * button does not take the input target away from it. The gesture consumes the release, so the button must not also be
 * clickable; pass an [interactionSource] to drive `Modifier.indication` from it instead.
 */
public fun Modifier.ghosttyExtraKey(
    state: GhosttyTerminalState,
    repeatable: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    onPress: () -> Unit,
): Modifier = composed {
    val currentOnPress = rememberUpdatedState(onPress)
    pointerInput(repeatable, interactionSource) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                val press = PressInteraction.Press(down.position)
                // Like a clickable in a scrolling container, show the press only once the touch is unlikely to
                // become a drag.
                val pressIndication = interactionSource?.let { source ->
                    this@coroutineScope.launch {
                        delay(PRESS_INDICATION_DELAY)
                        source.emit(press)
                    }
                }
                var repeated = false
                val repeatJob = if (repeatable) {
                    this@coroutineScope.launch {
                        delay(REPEAT_START_DELAY)
                        while (isActive) {
                            repeated = true
                            currentOnPress.value()
                            delay(REPEAT_INTERVAL)
                        }
                    }
                } else {
                    null
                }
                // null when a scrollable parent consumed the move or the touch left the button
                val up = waitForUpOrCancellation()
                repeatJob?.cancel()
                if (up != null) {
                    up.consume()
                    if (!repeated) currentOnPress.value()
                }
                if (interactionSource != null && pressIndication != null) {
                    this@coroutineScope.launch {
                        if (pressIndication.isActive) {
                            pressIndication.cancel()
                            if (up == null) return@launch
                            interactionSource.emit(press)
                        }
                        interactionSource.emit(
                            if (up != null) {
                                PressInteraction.Release(press)
                            } else {
                                PressInteraction.Cancel(press)
                            },
                        )
                    }
                }
                state.focusTerminal()
            }
        }
    }
}

private val PRESS_INDICATION_DELAY = 100.milliseconds
private val REPEAT_START_DELAY = 400.milliseconds
private val REPEAT_INTERVAL = 80.milliseconds
