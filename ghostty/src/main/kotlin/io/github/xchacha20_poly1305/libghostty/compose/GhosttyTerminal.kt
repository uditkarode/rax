package io.github.xchacha20_poly1305.libghostty.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.xchacha20_poly1305.libghostty.GhosttyTerminalSession
import io.github.xchacha20_poly1305.libghostty.GhosttyTheme
import androidx.compose.ui.res.stringResource
import io.github.xchacha20_poly1305.libghostty.R
import kotlin.math.roundToInt

/**
 * Shows [session] and sends it what the user types. Pair it with [GhosttyDialogs] for the prompts the terminal
 * raises. Widgets sharing the same [state] add the rest, and an app may use its own instead: [GhosttyExtraKeysBar]
 * below it, [GhosttyScrollIndicator] and [GhosttyJumpToBottomButton] over it.
 *
 * @param fontSize applies whenever it changes; pinch zoom moves away from it and reports through [onFontSizeChanged].
 *
 * @param focusOnAttach takes input focus when a session attaches, so a hardware keyboard types into it, and
 * [keyboardOnAttach] also shows the software keyboard.
 *
 * @param contentPadding keeps rows clear of whatever covers the edges of the view, as [contentPaddingMode] describes.
 * Horizontal padding narrows the grid in either mode.
 */
@Composable
public fun GhosttyTerminal(
    session: GhosttyTerminalSession?,
    modifier: Modifier = Modifier,
    state: GhosttyTerminalState = rememberGhosttyTerminalState(),
    theme: GhosttyTheme? = null,
    darkColorScheme: Boolean = isSystemInDarkTheme(),
    fontFamily: FontFamily = FontFamily.Monospace,
    fontSize: TextUnit = 14.sp,
    focusOnAttach: Boolean = true,
    keyboardOnAttach: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentPaddingMode: GhosttyContentPaddingMode = GhosttyContentPaddingMode.SCROLL_UNDER,
    onFontSizeChanged: (TextUnit) -> Unit = {},
    onFinishedSessionKey: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val controller = remember(state) { TerminalController(state, scope) }
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val currentOnFinishedSessionKey by rememberUpdatedState(onFinishedSessionKey)
    val currentOnFontSizeChanged by rememberUpdatedState(onFontSizeChanged)

    controller.clipboard = rememberPlatformClipboard()
    controller.showMessage = rememberShowMessage()
    controller.uriHandler = LocalUriHandler.current
    controller.haptics = LocalHapticFeedback.current
    controller.textToolbar = LocalTextToolbar.current
    controller.onFinishedSessionKey = { currentOnFinishedSessionKey() }
    controller.density = density.density

    DisposableEffect(controller) {
        state.controller = controller
        state.focusRequester = focusRequester
        onDispose {
            controller.attach(null)
            if (state.controller === controller) {
                state.controller = null
                state.focusRequester = null
            }
        }
    }
    LaunchedEffect(controller) { controller.runFrames() }

    // Zoom overrides the parameter until the parameter itself changes.
    LaunchedEffect(fontSize) {
        state.baseFontSize = fontSize
        state.zoomedFontSize = null
    }
    val effectiveFontSize = state.zoomedFontSize ?: fontSize
    LaunchedEffect(effectiveFontSize) {
        if (effectiveFontSize != fontSize) currentOnFontSizeChanged(effectiveFontSize)
    }
    val fontSizePx = with(density) { effectiveFontSize.toPx() }
    LaunchedEffect(fontFamily, fontSizePx, resolver) {
        controller.setFont(fontFamily, fontSizePx, resolver)
    }

    LaunchedEffect(session) { controller.attach(session) }
    LaunchedEffect(session, theme, darkColorScheme) {
        if (session != null) {
            theme?.let(session::setTheme)
            session.setColorScheme(darkColorScheme)
        }
    }

    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(session, windowFocused) { controller.onWindowFocusChanged(windowFocused) }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(started) { controller.onStartedChanged(started) }

    val accessibilityEnabled by rememberAccessibilityEnabled()
    LaunchedEffect(accessibilityEnabled) { controller.onAccessibilityChanged(accessibilityEnabled) }

    BackHandler(enabled = controller.selectionActive) { controller.clearSelection() }

    LaunchedEffect(session, focusOnAttach) {
        if (session != null && focusOnAttach) {
            focusRequester.requestFocus()
            if (keyboardOnAttach) controller.requestKeyboard()
        }
    }

    val handleColor = LocalTextSelectionColors.current.handleColor
    val decay = rememberSplineBasedDecay<Float>()
    Box(
        modifier
            .layout { measurable, constraints ->
                // Read in the layout, so padding that follows an animation, such as the keyboard's, applies in the
                // frame it changes in.
                controller.setContentPadding(
                    TerminalController.PaddingPx(
                        left = contentPadding.calculateLeftPadding(layoutDirection).toPx(),
                        top = contentPadding.calculateTopPadding().toPx(),
                        right = contentPadding.calculateRightPadding(layoutDirection).toPx(),
                        bottom = contentPadding.calculateBottomPadding().toPx(),
                    ),
                    contentPaddingMode,
                )
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .onSizeChanged { controller.setSize(it.width, it.height) }
            .onGloballyPositioned { controller.layoutCoordinates = it }
            .semantics { controller.accessibilityText?.let { contentDescription = it } }
            .focusRequester(focusRequester)
            .onFocusChanged { controller.onFocusChanged(it.isFocused) }
            .terminalInput(controller)
            .focusable()
            .terminalPointerInput(controller, focusRequester, decay)
            .selectionMagnifier { controller.magnifierCenter }
            .drawBehind {
                controller.frame
                drawTerminal(controller)
            },
    ) {
        // Its own layer, so a cursor blink draws only this.
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawBehind {
                    controller.frame
                    drawTerminalOverlay(controller, handleColor)
                },
        )
        TerminalContextMenu(controller)
    }
}

/** The menu a secondary mouse click opens, anchored at the click. */
@Composable
private fun TerminalContextMenu(controller: TerminalController) {
    val at = controller.contextMenuAt ?: return
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = { controller.contextMenuAt = null }) {
            if (controller.selectionActive) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ghostty_copy)) },
                    onClick = {
                        controller.contextMenuAt = null
                        controller.copySelection()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ghostty_paste)) },
                onClick = {
                    controller.contextMenuAt = null
                    controller.paste()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ghostty_select_all)) },
                onClick = {
                    controller.contextMenuAt = null
                    controller.selectAll()
                },
            )
        }
    }
}
