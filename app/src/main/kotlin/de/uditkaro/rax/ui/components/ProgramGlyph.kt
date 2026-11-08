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
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.TextFamily

/** What kind of program a pane runs, which decides the mark next to its name. */
enum class ProgramKind { Shell, Agent, Editor, Other }

@Composable
fun ProgramGlyph(
    kind: ProgramKind,
    name: String,
    @DrawableRes icon: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val c = Rax.colors
    val tint = if (kind == ProgramKind.Agent) c.link else c.textSecondary
    Box(
        modifier.size(size).clip(RoundedCornerShape(percent = 25)).background(c.control),
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> RaxIcon(icon, null, tint = tint, size = size * 0.5f)
            kind == ProgramKind.Shell -> RaxIcon(R.drawable.ic_terminal, null, tint = tint, size = size * 0.55f)
            else -> Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                Modifier.clearAndSetSemantics {},
                style = TextStyle(
                    fontFamily = TextFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.4f).sp,
                ),
                color = tint,
            )
        }
    }
}
