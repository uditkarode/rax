package io.github.xchacha20_poly1305.libghostty.compose

import io.github.xchacha20_poly1305.libghostty.GhosttyMods

/**
 * Modifiers held outside the keyboard, such as the Ctrl and Alt of an extra keys bar. Set on
 * [GhosttyTerminalState.stickyModifiers], they apply to the next key the terminal sends, whether it comes from a
 * keyboard, the input method or [GhosttyTerminalState.sendKey].
 */
public interface GhosttyStickyModifiers {

    /** The held modifiers as [GhosttyMods] bits. */
    public fun currentMods(): Int

    /** Called after a key press used [currentMods], to release the modifiers held for a single key. */
    public fun consumeModifiers()
}
