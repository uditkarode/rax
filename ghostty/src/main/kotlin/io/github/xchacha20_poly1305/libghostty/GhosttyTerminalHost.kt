package io.github.xchacha20_poly1305.libghostty

/** The UI showing a [GhosttyTerminalSession]. Methods are called on the main thread. */
public interface GhosttyTerminalHost {
    /** The terminal changed and should be redrawn. */
    public fun onTerminalChanged() {}

    public fun onBell() {}

    /**
     * Asks the user whether the running program may read the clipboard. [respond] must be called exactly once; an
     * approval lasts for the rest of the session.
     */
    public fun requestClipboardRead(respond: (approved: Boolean) -> Unit) {
        respond(false)
    }
}
