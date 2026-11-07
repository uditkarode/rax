package de.uditkaro.rax.theme

import android.content.res.AssetManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One of the themes Rex ships on the Mac, as `tools/rex-themes.py` writes it into
 * `assets/themes/rex-themes.json`: the terminal's colours and the colours Rex draws its own
 * interface in. Colours are `#rrggbb` or `#rrggbbaa`.
 */
@Serializable
data class RexThemeSource(
    val id: String,
    val name: String,
    val dark: Boolean,
    val terminal: TerminalSource,
    val ui: Map<String, String>,
) {
    @Serializable
    data class TerminalSource(
        val background: String,
        val foreground: String,
        val cursor: String,
        val cursorText: String,
        val selectionBackground: String,
        val selectionForeground: String,
        val palette: List<String>,
    )
}

@Immutable
data class RexTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val colors: RaxColors,
    val terminal: TerminalColors,
)

/** The terminal's colours as ARGB ints, the form the terminal view takes. */
@Immutable
data class TerminalColors(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val cursorText: Int,
    val selectionBackground: Int,
    val selectionForeground: Int,
    val palette: List<Int>,
)

class ThemeCatalog(val themes: List<RexTheme>) {
    private val byId = themes.associateBy { it.id }

    val dark: List<RexTheme> = themes.filter { it.dark }
    val light: List<RexTheme> = themes.filterNot { it.dark }

    fun dark(id: String?): RexTheme = byId[id]?.takeIf { it.dark } ?: byId.getValue(DEFAULT_DARK)
    fun light(id: String?): RexTheme = byId[id]?.takeUnless { it.dark } ?: byId.getValue(DEFAULT_LIGHT)

    companion object {
        /** Rex's own defaults, so the phone matches a Mac that was never changed. */
        const val DEFAULT_DARK = "silver-point-dark"
        const val DEFAULT_LIGHT = "silver-point-light"

        private val json = Json { ignoreUnknownKeys = true }

        fun load(assets: AssetManager): ThemeCatalog {
            val text = assets.open("themes/rex-themes.json").bufferedReader().use { it.readText() }
            val sources = json.decodeFromString<List<RexThemeSource>>(text)
            return ThemeCatalog(sources.map(::toTheme))
        }

        fun toTheme(source: RexThemeSource): RexTheme {
            val ui = source.ui
            fun c(name: String) = parseColor(ui[name] ?: error("${source.id}: missing ui.$name"))
            val t = source.terminal
            return RexTheme(
                id = source.id,
                name = source.name,
                dark = source.dark,
                colors = RaxColors(
                    dark = source.dark,
                    // A dark theme's page is its canvas; a light one uses the sidebar's tint so the
                    // raised cards stand out from it the way grouped lists need.
                    page = if (source.dark) c("canvas") else c("surfaceSubtle"),
                    card = c("surfaceRaised"),
                    sheet = if (source.dark) c("surfaceSubtle") else c("surface"),
                    stroke = c("stroke"),
                    strokeStrong = c("strokeStrong"),
                    control = c("controlPressed"),
                    text = c("content"),
                    textSecondary = c("contentSecondary"),
                    textTertiary = c("contentTertiary"),
                    accent = c("accent"),
                    link = readableOn(c("accent"), c("surfaceRaised"), c("content")),
                    onAccent = c("accentOnSolid"),
                    accentContainer = c("accentContainer"),
                    onAccentContainer = c("accentOnContainer"),
                    danger = c("danger"),
                    warning = c("warning"),
                    success = c("success"),
                    terminalBackground = parseColor(t.background),
                    terminalForeground = parseColor(t.foreground),
                ),
                terminal = TerminalColors(
                    background = parseArgb(t.background),
                    foreground = parseArgb(t.foreground),
                    cursor = parseArgb(t.cursor),
                    cursorText = parseArgb(t.cursorText),
                    selectionBackground = parseArgb(t.selectionBackground),
                    selectionForeground = parseArgb(t.selectionForeground),
                    palette = t.palette.map(::parseArgb),
                ),
            )
        }
    }
}

fun parseColor(hex: String): Color = Color(parseArgb(hex))

fun parseArgb(hex: String): Int {
    require(hex.startsWith("#") && (hex.length == 7 || hex.length == 9)) { "bad colour $hex" }
    val rgb = hex.substring(1, 7).toLong(16)
    val alpha = if (hex.length == 9) hex.substring(7, 9).toLong(16) else 0xFF
    return ((alpha shl 24) or rgb).toInt()
}

/**
 * [color] when it reads well as text on [background], else [fallback]. Some themes have a muted
 * accent meant for filled controls, which is too faint for words and icons.
 */
internal fun readableOn(color: Color, background: Color, fallback: Color): Color {
    val a = color.luminance() + 0.05f
    val b = background.luminance() + 0.05f
    val contrast = maxOf(a, b) / minOf(a, b)
    return if (contrast >= 4.5f) color else fallback
}
