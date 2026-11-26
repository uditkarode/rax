package de.uditkaro.rax.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.TextFamily

/** What kind of program a pane runs, which decides the mark next to its name. */
enum class ProgramKind { Shell, Agent, Editor, Other }

/** A program's logo on its coloured tile, which is 1.3 times as wide as [height], as in Rex. */
@Composable
fun ProgramGlyph(
    kind: ProgramKind,
    name: String,
    @DrawableRes icon: Int?,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
) {
    val colors = when {
        icon != null -> logoColors[icon] ?: plainColors
        kind == ProgramKind.Shell -> shellColors
        else -> plainColors
    }
    Box(
        modifier.size(width = height * 1.3f, height = height).drawBehind { drawTile(colors.tile) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> RaxIcon(icon, null, tint = colors.logo, size = height * 0.56f)
            kind == ProgramKind.Shell -> RaxIcon(R.drawable.ic_terminal, null, tint = colors.logo, size = height * 0.62f)
            else -> Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                Modifier.clearAndSetSemantics {},
                style = TextStyle(
                    fontFamily = TextFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (height.value * 0.45f).sp,
                ),
                color = colors.logo,
            )
        }
    }
}

/**
 * The tile in [color] the way Rex draws it: a faint dark line around it, a soft light from the top
 * centre, and a thin light rim inside the edge that is brightest at the top.
 */
private fun DrawScope.drawTile(color: Color) {
    val ring = size.height * 0.016f
    val rim = size.height * 0.03f
    val radius = size.height * 0.27f
    val tileOffset = Offset(ring, ring)
    val tileSize = Size(size.width - 2 * ring, size.height - 2 * ring)
    val tileRadius = CornerRadius(radius - ring)

    drawRoundRect(Color.Black.copy(alpha = 0.28f), cornerRadius = CornerRadius(radius))
    drawRoundRect(color, tileOffset, tileSize, tileRadius)
    drawRoundRect(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
            center = Offset(size.width / 2, 0f),
            radius = size.height * 0.8f,
        ),
        tileOffset,
        tileSize,
        tileRadius,
    )
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.3f),
            0.5f to Color.White.copy(alpha = 0.1f),
            1f to Color.White.copy(alpha = 0.16f),
            startY = ring,
            endY = size.height - ring,
        ),
        tileOffset + Offset(rim / 2, rim / 2),
        Size(tileSize.width - rim, tileSize.height - rim),
        CornerRadius(radius - ring - rim / 2),
        style = Stroke(rim),
    )
}
