package de.uditkaro.rax.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.NavRow
import de.uditkaro.rax.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Credit(val name: String, val license: String, val asset: String)

private val software = listOf(
    Credit("libghostty-android", "MIT", "licenses/libghostty-android.txt"),
    Credit("Ghostty", "MIT", "licenses/ghostty.txt"),
    Credit("Tailscale", "BSD-3-Clause", "licenses/tailscale.txt"),
    Credit("OkHttp", "Apache-2.0", "licenses/apache-2.0.txt"),
    Credit("Kotlin and kotlinx", "Apache-2.0", "licenses/apache-2.0.txt"),
    Credit("Android Jetpack", "Apache-2.0", "licenses/apache-2.0.txt"),
    Credit("Material Symbols", "Apache-2.0", "licenses/apache-2.0.txt"),
    Credit("Simple Icons", "CC0-1.0", "licenses/simple-icons.txt"),
)

@Composable
fun AcknowledgementsScreen(graph: AppGraph, onBack: () -> Unit, open: (Screen) -> Unit) {
    val fonts = graph.fonts.bundled.map { Credit(it.name, it.license, "fonts/${it.id}/LICENSE.txt") } +
        Credit("Symbols Nerd Font", "MIT, CC BY 4.0", "fonts/symbols-nerd/LICENSE.txt") +
        Credit("JuliaMono", "OFL-1.1", "fonts/juliamono/LICENSE.txt") +
        Credit("Google Sans Flex", "OFL-1.1", "fonts/google-sans-flex-LICENSE.txt")

    LargeTitleScreen(stringResource(R.string.acknowledgements_title), onBack) {
        item(key = "themes") {
            SectionHeader(stringResource(R.string.acknowledgements_themes))
            Group { row { NavRow("Rex", { open(Screen.License("Rex", "licenses/rex-themes.txt")) }, value = "Superlogical") } }
        }
        item(key = "software") {
            SectionHeader(stringResource(R.string.acknowledgements_software))
            Group {
                software.forEach { c -> row { NavRow(c.name, { open(Screen.License(c.name, c.asset)) }, value = c.license) } }
            }
        }
        item(key = "fonts") {
            SectionHeader(stringResource(R.string.acknowledgements_fonts))
            Group {
                fonts.forEach { c -> row { NavRow(c.name, { open(Screen.License(c.name, c.asset)) }, value = c.license) } }
            }
        }
    }
}

@Composable
fun LicenseScreen(title: String, asset: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = produceState("", asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    LargeTitleScreen(title, onBack) {
        item(key = "text") {
            SelectionContainer {
                Text(
                    text.value,
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                    color = Rax.colors.textSecondary,
                )
            }
        }
    }
}
