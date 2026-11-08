package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * A button that scrolls the terminal of [state] back to the bottom, shown while the viewport is in the scrollback.
 * Place it over the terminal, for example aligned to the bottom end of a `Box` holding both.
 */
@Composable
public fun GhosttyJumpToBottomButton(
    state: GhosttyTerminalState,
    modifier: Modifier = Modifier,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
) {
    AnimatedVisibility(
        visible = !state.scrollbar.isAtBottom,
        modifier = modifier,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        SmallFloatingActionButton(onClick = state::scrollToBottom, containerColor = containerColor) {
            Icon(arrowDownwardIcon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

private val arrowDownwardIcon: ImageVector = ImageVector.Builder(
    name = "ArrowDownward",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes("M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z"),
    fill = SolidColor(Color.Black),
).build()
