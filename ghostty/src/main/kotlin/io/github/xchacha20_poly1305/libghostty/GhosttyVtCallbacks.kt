package io.github.xchacha20_poly1305.libghostty

/**
 * Every call the JNI library makes into Kotlin. The native side looks Kotlin members up by name only on this
 * class and [GhosttyVt], so the consumer rules keep both whole; a callback added anywhere else would be
 * deleted by the shrinker of an app that minifies.
 *
 * The methods run on the thread that calls [GhosttyTerminalSession.feedOutput].
 */
internal class GhosttyVtCallbacks(private val session: GhosttyTerminalSession) {
    fun clipboardHasText(): Boolean = session.clipboardHasText()

    fun readClipboardForProgram(granted: Boolean): ByteArray? = session.readClipboardForProgram(granted)
}
