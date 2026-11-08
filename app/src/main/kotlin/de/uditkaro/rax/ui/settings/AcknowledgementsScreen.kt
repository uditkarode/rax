package de.uditkaro.rax.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.components.Group
import de.uditkaro.rax.ui.components.LargeTitleScreen
import de.uditkaro.rax.ui.components.NavRow
import de.uditkaro.rax.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Credit(val name: String, val license: String, val asset: String)

private val software = listOf(
    Credit("libghostty-cmp", "MIT", "licenses/libghostty-cmp.txt"),
    Credit("Ghostty", "MIT", "licenses/ghostty.txt"),
    Credit("Tailscale", "BSD-3-Clause", "licenses/tailscale.txt"),
    Credit("OkHttp", "Apache-2.0", "licenses/apache-2.0.txt"),
    Credit("Kotlin and kotlinx", "Apache-2.0", "licenses/apache-2.0.txt"),
    Credit("Android Jetpack", "Apache-2.0", "licenses/apache-2.0.txt"),
    Credit("Material Symbols", "Apache-2.0", "licenses/apache-2.0.txt"),
    Credit("Simple Icons", "CC0-1.0", "licenses/simple-icons.txt"),
    Credit("Lobe Icons", "MIT", "licenses/lobe-icons.txt"),
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
            runCatching { context.assets.open(asset).bufferedReader().use { reflowLicense(it.readText()) } }.getOrDefault("")
        }
    }
    LargeTitleScreen(title, onBack) {
        item(key = "text") {
            SelectionContainer {
                Text(
                    text.value,
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = RaxType.footnote,
                    color = Rax.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * Joins the lines that a license file wrapped by hand, so its paragraphs wrap to the screen. A line that ends well
 * before the paragraph's longest one ends where its author meant it to, as in a heading. A paragraph of only short
 * lines, such as a list of copyright holders, keeps its lines, and list items and rules start their own lines.
 */
internal fun reflowLicense(text: String): String =
    text.replace("\r\n", "\n").trim('\n').split(PARAGRAPH_BREAK).joinToString("\n\n") { paragraph ->
        val lines = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val longest = lines.maxOfOrNull { it.length } ?: 0
        buildString {
            lines.forEachIndexed { i, line ->
                if (i > 0) {
                    val previous = lines[i - 1]
                    val ends = longest < WRAPPED_LINE || previous.length < longest * 3 / 4 ||
                        LIST_ITEM.containsMatchIn(line) || RULE.matches(line) || RULE.matches(previous)
                    append(if (ends) '\n' else ' ')
                }
                append(line)
            }
        }
    }

private val PARAGRAPH_BREAK = Regex("\n[ \t]*\n\\s*")
private val LIST_ITEM = Regex("""^(\d+\.|\([a-z0-9]+\)|[-*•])\s""")
private val RULE = Regex("""[-=_*~]{3,}""")

/** License files wrap at 70 to 80 columns, so a paragraph with no line this long was not wrapped by hand. */
private const val WRAPPED_LINE = 55
