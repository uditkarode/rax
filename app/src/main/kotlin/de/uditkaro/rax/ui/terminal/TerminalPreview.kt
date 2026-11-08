package de.uditkaro.rax.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uditkaro.rax.theme.TerminalColors
import io.github.xchacha20_poly1305.libghostty.GhosttyTerminalSession
import io.github.xchacha20_poly1305.libghostty.compose.GhosttyTerminal
import io.github.xchacha20_poly1305.libghostty.compose.rememberGhosttyTerminalState

private const val SAMPLE =
    "\u001b[?25l\u001b[2J\u001b[H" +
        "\u001b[34m~/rax\u001b[0m \u001b[35mmain\u001b[0m \u001b[32m❯\u001b[0m ./gradlew test\r\n" +
        "\u001b[1;32mBUILD SUCCESSFUL\u001b[0m in 12s\r\n" +
        "\u001b[90m0O 1lI {}[]() => != ==\u001b[0m"

/**
 * Three lines drawn by the real terminal renderer, so the font picker shows exactly what a pane
 * will look like. It takes no input.
 */
@Composable
fun TerminalPreview(font: FontFamily, fontSize: Float, colors: TerminalColors, modifier: Modifier = Modifier) {
    val session = remember { GhosttyTerminalSession(GhosttyTerminalSession.Options(columns = 40, rows = 3)) }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(font, fontSize, colors) { session.feedOutput(SAMPLE.toByteArray()) }
    val state = rememberGhosttyTerminalState()
    state.showImeOnTap = false
    state.urlDetectionEnabled = false

    // Three rows at the line height the renderer uses, about 1.25 times the font size.
    val height = (fontSize * 1.32f * 3f).dp
    Box(modifier.fillMaxWidth().height(height)) {
        GhosttyTerminal(
            session = session,
            modifier = Modifier.fillMaxSize(),
            state = state,
            theme = remember(colors) { colors.toGhostty() },
            darkColorScheme = true,
            fontFamily = font,
            fontSize = fontSize.sp,
            focusOnAttach = false,
        )
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}
