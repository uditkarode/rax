package de.uditkaro.rax.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.uditkaro.rax.R

/**
 * Google Sans Flex, a variable font. A family can only pick a face by weight, so the large titles
 * get their own family set to a display optical size; everything else uses the text size.
 */
private fun flex(weight: FontWeight, opticalSize: Float) = Font(
    R.font.google_sans_flex,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("opsz", opticalSize),
    ),
)

private val weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

val TextFamily = FontFamily(weights.map { flex(it, 16f) })
val DisplayFamily = FontFamily(weights.map { flex(it, 32f) })

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: FontWeight, family: FontFamily = TextFamily, tracking: Float = 0f) =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em,
        lineHeightStyle = trim,
    )

/** Seven styles, one job each. Screens use these and nothing else. */
object RaxType {
    /** A screen's large title. */
    val largeTitle = style(32, 38, FontWeight.Bold, DisplayFamily, -0.02f)

    /** A host's name on the home screen, a sheet's title. */
    val title = style(22, 28, FontWeight.SemiBold, DisplayFamily, -0.01f)

    /** The title in a collapsed top bar and on primary buttons. */
    val headline = style(17, 22, FontWeight.SemiBold)

    /** Row titles and body text. */
    val body = style(16, 22, FontWeight.Normal)

    /** Row titles that carry emphasis, such as a tab's name. */
    val bodyStrong = style(16, 22, FontWeight.Medium)

    /** Section headers, row subtitles, values. */
    val subhead = style(14, 19, FontWeight.Medium)

    /** Footnotes under a group, captions. */
    val footnote = style(13, 18, FontWeight.Normal)

    val material = Typography(
        displayLarge = largeTitle, displayMedium = largeTitle, displaySmall = largeTitle,
        headlineLarge = largeTitle, headlineMedium = title, headlineSmall = title,
        titleLarge = title, titleMedium = headline, titleSmall = subhead,
        bodyLarge = body, bodyMedium = body, bodySmall = footnote,
        labelLarge = subhead, labelMedium = footnote, labelSmall = footnote,
    )
}
