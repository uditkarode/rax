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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.TextFamily
import de.uditkaro.rax.theme.readableOn

/** What kind of program a pane runs, which decides the mark next to its name. */
enum class ProgramKind { Shell, Agent, Editor, Other }

/** The logos Rex shows in colour, with their colours. The rest take the theme's colour. */
private val logoColors = mapOf(
    R.drawable.ic_program_gnubash to Color(0xFF7EE687),
    R.drawable.ic_program_zsh to Color(0xFF7EE687),
    R.drawable.ic_program_fishshell to Color(0xFF7EE687),
    R.drawable.ic_program_git to Color(0xFFF03C2E),
    R.drawable.ic_program_redis to Color(0xFFFF4438),
    R.drawable.ic_program_podman to Color(0xFF503E68),
    R.drawable.ic_program_llvm to Color(0xFF262D3A),
    R.drawable.ic_program_jj to Color(0xFF042652),
    R.drawable.ic_program_d2 to Color(0xFF255EF5),
    R.drawable.ic_program_yazi to Color(0xFFF7EA72),
    R.drawable.ic_program_kakoune to Color(0xFF4461B8),
    R.drawable.ic_program_orbstack to Color(0xFF4461B8),
    R.drawable.ic_program_junie to Color(0xFF47E054),
    R.drawable.ic_program_kiro to Color(0xFF9046FF),
    R.drawable.ic_program_hermesagent to Color(0xFF0A04F2),
)

@Composable
fun ProgramGlyph(
    kind: ProgramKind,
    name: String,
    @DrawableRes icon: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val c = Rax.colors
    val tint = logoColors[icon]?.let { readableOn(it, c.control, c.text, minContrast = 3f) }
        ?: if (kind == ProgramKind.Agent) c.link else c.textSecondary
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
