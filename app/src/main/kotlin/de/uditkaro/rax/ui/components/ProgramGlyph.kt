package de.uditkaro.rax.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
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
        modifier.size(width = height * 1.3f, height = height)
            .clip(RoundedCornerShape(percent = 27))
            .background(colors.tile),
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
