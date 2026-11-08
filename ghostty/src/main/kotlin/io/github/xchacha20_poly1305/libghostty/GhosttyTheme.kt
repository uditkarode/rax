package io.github.xchacha20_poly1305.libghostty

/** Colors are ARGB values; null keeps the terminal default. */
public class GhosttyTheme(
    public val foreground: Int?,
    public val background: Int?,
    public val cursorColor: Int?,
    public val selectionBackground: Int?,
    public val selectionForeground: Int?,
    public val palette: List<Int?>,
) {
    init {
        require(palette.size == 256) { "palette must hold 256 entries" }
    }
}
