package de.uditkaro.rax.ui.terminal

import android.graphics.Typeface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.theme.TerminalColors
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.compose.GhosttyTerminal

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
fun TerminalPreview(typeface: Typeface, fontSize: Float, colors: TerminalColors, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = remember { GhosttyTerminalSession(context, GhosttyTerminalSession.Options(columns = 40, rows = 3)) }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(colors) { session.setTheme(colors.toGhostty()) }
    LaunchedEffect(typeface, fontSize, colors) { session.feedOutput(SAMPLE.toByteArray()) }

    // Three rows at the line height the renderer uses, about 1.25 times the font size.
    val height = (fontSize * 1.32f * 3f).dp
    Box(modifier.fillMaxWidth().height(height)) {
        GhosttyTerminal(
            session = session,
            modifier = Modifier.fillMaxSize(),
            darkColorScheme = true,
            typeface = typeface,
            fontSizeSp = fontSize,
            focusOnAttach = false,
            configure = { view ->
                view.showImeOnTap = false
                view.isFocusable = false
                view.urlDetectionEnabled = false
            },
        )
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}
