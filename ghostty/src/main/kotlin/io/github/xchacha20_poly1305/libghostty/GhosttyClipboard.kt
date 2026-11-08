package io.github.xchacha20_poly1305.libghostty

/**
 * The system clipboard as programs reach it through OSC 52. Methods are called on the thread that calls
 * [GhosttyTerminalSession.feedOutput] and on the main thread, so implementations must be thread safe.
 */
public interface GhosttyClipboard {
    public fun hasText(): Boolean

    public fun getText(): String?

    public fun setText(text: String)
}
