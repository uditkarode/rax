package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * A thumb showing where the viewport of [state] is in the scrollback. It appears when the user scrolls and fades out
 * shortly after. Its height is the track, so place it along the end edge of the terminal, inside the same vertical
 * content padding.
 */
@Composable
public fun GhosttyScrollIndicator(
    state: GhosttyTerminalState,
    modifier: Modifier = Modifier,
    color: Color = Color(0xB3AAAAAA),
    thickness: Dp = 3.dp,
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state) {
        state.userScrolls.collectLatest {
            alpha.snapTo(1f)
            delay(FADE_DELAY)
            alpha.animateTo(0f, tween(FADE_MS))
        }
    }
    Canvas(modifier.fillMaxHeight().width(thickness)) {
        val scrollbar = state.scrollbar
        if (scrollbar.total <= scrollbar.length || alpha.value <= 0f) return@Canvas
        val barWidth = thickness.toPx()
        val top = size.height * scrollbar.offset / scrollbar.total
        val thumbHeight = max(MIN_THUMB_HEIGHT.toPx(), size.height * scrollbar.length / scrollbar.total)
        drawRoundRect(
            color.copy(alpha = color.alpha * alpha.value),
            Offset(0f, top),
            Size(barWidth, min(thumbHeight, size.height - top)),
            CornerRadius(barWidth / 2),
        )
    }
}

private val MIN_THUMB_HEIGHT = 16.dp
private val FADE_DELAY = 800.milliseconds
private const val FADE_MS = 600
