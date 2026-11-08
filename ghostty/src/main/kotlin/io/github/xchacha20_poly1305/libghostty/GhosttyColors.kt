package io.github.xchacha20_poly1305.libghostty

public object GhosttyColors {
    /** Parses a color in any format the ghostty configuration accepts; returns an ARGB value. */
    public fun parse(value: String): Int? = GhosttyVt.nativeParseColor(value).takeIf { it != 0 }
}
