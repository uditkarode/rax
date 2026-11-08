package io.github.xchacha20_poly1305.libghostty.compose

/** How a [GhosttyTerminal] lays its rows out against its `contentPadding`. */
public enum class GhosttyContentPaddingMode {
    /**
     * Like a lazy list: the grid fills the view, so rows show behind whatever covers the padding, and only the ends of
     * the scrollback keep clear of it. At rest the last row with content sits above the bottom padding, and at the top
     * of the scrollback the first row sits below the top padding. Suits output that is only read, such as a log; a
     * full-screen program such as vim gets rows that stay behind the padding.
     */
    SCROLL_UNDER,

    /**
     * The grid fits between the top and bottom padding, so the terminal has exactly the rows left in sight and a
     * full-screen program sees all of its screen. Nothing is drawn into the vertical padding.
     */
    INSET,
}
