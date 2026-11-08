package io.github.xchacha20_poly1305.libghostty

import android.net.Uri

/**
 * The system clipboard as programs reach it through OSC 52. Methods are called on the thread that calls
 * [GhosttyTerminalSession.feedOutput] and on the main thread, so implementations must be thread safe.
 */
public interface GhosttyClipboard {
    public fun hasText(): Boolean

    public fun getText(): String?

    public fun setText(text: String)

    /** The file the clipboard holds instead of text, such as a copied image, or null. */
    public fun getFile(): Uri? = null
}
