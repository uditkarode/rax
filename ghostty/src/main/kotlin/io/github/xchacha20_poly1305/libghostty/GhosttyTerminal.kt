package io.github.xchacha20_poly1305.libghostty

import java.nio.ByteBuffer

/**
 * The terminal of a [GhosttyTerminalSession], lent out by [GhosttyTerminalSession.withTerminal] and valid only
 * inside that block. Positions are viewport cells, zero-based.
 */
public class GhosttyTerminal internal constructor() {
    internal var handle: Long = 0L

    public val isAlternateScreen: Boolean
        get() = viewportState() and GhosttyVt.VIEWPORT_ALTERNATE_SCREEN != 0

    /** Whether the program asked for mouse reports, so pointer input belongs to it. */
    public val isMouseTracking: Boolean
        get() = viewportState() and GhosttyVt.VIEWPORT_MOUSE_TRACKING != 0

    /** Whether the program holds synchronized output (mode 2026) while it draws a frame. */
    public val isSynchronizedOutput: Boolean
        get() = viewportState() and GhosttyVt.VIEWPORT_SYNC_OUTPUT != 0

    private fun viewportState(): Int = GhosttyVt.nativeViewportState(handle)

    public val scrollbar: GhosttyScrollbar
        get() {
            val values = GhosttyVt.nativeScrollbar(handle) ?: return GhosttyScrollbar(0, 0, 0)
            return GhosttyScrollbar(values[0], values[1], values[2])
        }

    /** Scrolls the viewport through the scrollback; positive [deltaRows] moves toward the bottom. */
    public fun scroll(deltaRows: Int) {
        GhosttyVt.nativeScroll(handle, deltaRows)
    }

    public fun scrollToBottom() {
        GhosttyVt.nativeScrollToBottom(handle)
    }

    /** The URI of the OSC 8 hyperlink at a cell. */
    public fun hyperlinkAt(column: Int, row: Int): String? =
        GhosttyVt.nativeHyperlinkAt(handle, column, row)?.toString(Charsets.UTF_8)

    public fun selectWord(column: Int, row: Int): Boolean = GhosttyVt.nativeSelectWord(handle, column, row)

    public fun selectAll(): Boolean = GhosttyVt.nativeSelectAll(handle)

    /** Selects the cells from the anchor to the given cell in reading order, both included. */
    public fun select(anchorColumn: Int, anchorRow: Int, column: Int, row: Int): Boolean =
        GhosttyVt.nativeSetSelection(handle, anchorColumn, anchorRow, column, row)

    public fun clearSelection() {
        GhosttyVt.nativeClearSelection(handle)
    }

    public fun selectionText(): String? = GhosttyVt.nativeSelectionText(handle)?.toString(Charsets.UTF_8)

    public fun viewportText(): String? = GhosttyVt.nativeViewportText(handle)?.toString(Charsets.UTF_8)

    /**
     * Encodes a key event for the program, following its keyboard modes. [text] is what the key produces with
     * the current layout and modifiers, and [unshiftedCodepoint] what it produces without modifiers; both feed
     * the Kitty keyboard protocol. Returns null when the event sends nothing.
     */
    public fun encodeKey(
        key: GhosttyKey,
        action: GhosttyKeyAction,
        mods: Int = 0,
        text: String? = null,
        unshiftedCodepoint: Int = 0,
        composing: Boolean = false,
    ): ByteArray? = GhosttyVt.nativeEncodeKey(
        handle,
        key.value,
        action.value,
        mods,
        unshiftedCodepoint,
        composing,
        text?.toByteArray(Charsets.UTF_8),
    )?.takeIf { it.isNotEmpty() }

    /** Encodes a mouse event in the program's mouse reporting mode, or returns null when it is not reported. */
    public fun encodeMouse(
        action: GhosttyMouseAction,
        button: GhosttyMouseButton?,
        column: Int,
        row: Int,
        mods: Int = 0,
    ): ByteArray? = GhosttyVt.nativeEncodeMouse(
        handle,
        action.value,
        button?.value ?: 0,
        column,
        row,
        mods,
    )?.takeIf { it.isNotEmpty() }

    /** Encodes pasted text, bracketing it when the program enabled bracketed paste. */
    public fun encodePaste(text: String): ByteArray? =
        GhosttyVt.nativeEncodePaste(handle, text.toByteArray(Charsets.UTF_8))?.takeIf { it.isNotEmpty() }

    /** The current frame of a Kitty graphics image. */
    public fun kittyImage(imageId: Int): GhosttyImage? {
        val pixels = GhosttyVt.nativeKittyImage(handle, imageId) ?: return null
        if (pixels.size < 2) return null
        val width = pixels[0]
        val height = pixels[1]
        if (width <= 0 || height <= 0 || pixels.size < 2 + width * height) {
            return null
        }
        return GhosttyImage(width, height, pixels.copyOfRange(2, 2 + width * height))
    }

    internal fun snapshot(buffer: ByteBuffer, full: Boolean): Int = GhosttyVt.nativeSnapshot(handle, buffer, full)

    internal fun kittyPlacements(): LongArray? = GhosttyVt.nativeKittyPlacements(handle)

    public companion object {
        /**
         * Whether pasting [text] cannot run commands by itself: it has no line break and cannot end bracketed
         * paste early.
         */
        public fun isPasteSafe(text: String): Boolean = GhosttyVt.nativeIsPasteSafe(text.toByteArray(Charsets.UTF_8))
    }
}

/** The viewport within the scrollback, in rows: [offset] is where the viewport starts in [total]. */
public class GhosttyScrollbar(
    public val total: Long,
    public val offset: Long,
    public val length: Long,
) {
    public val isAtBottom: Boolean
        get() = offset + length >= total

    override fun equals(other: Any?): Boolean =
        other is GhosttyScrollbar && total == other.total && offset == other.offset && length == other.length

    override fun hashCode(): Int = (total.hashCode() * 31 + offset.hashCode()) * 31 + length.hashCode()

    override fun toString(): String = "GhosttyScrollbar(total=$total, offset=$offset, length=$length)"
}

/** ARGB pixels in rows. */
public class GhosttyImage(
    public val width: Int,
    public val height: Int,
    public val pixels: IntArray,
)

public enum class GhosttyKeyAction(internal val value: Int) {
    RELEASE(GhosttyVt.KEY_ACTION_RELEASE),
    PRESS(GhosttyVt.KEY_ACTION_PRESS),
    REPEAT(GhosttyVt.KEY_ACTION_REPEAT),
}

public enum class GhosttyMouseAction(internal val value: Int) {
    PRESS(GhosttyVt.MOUSE_ACTION_PRESS),
    RELEASE(GhosttyVt.MOUSE_ACTION_RELEASE),
    MOTION(GhosttyVt.MOUSE_ACTION_MOTION),
}

public enum class GhosttyMouseButton(internal val value: Int) {
    LEFT(GhosttyVt.MOUSE_BUTTON_LEFT),
    RIGHT(GhosttyVt.MOUSE_BUTTON_RIGHT),
    MIDDLE(GhosttyVt.MOUSE_BUTTON_MIDDLE),
    WHEEL_UP(GhosttyVt.MOUSE_BUTTON_WHEEL_UP),
    WHEEL_DOWN(GhosttyVt.MOUSE_BUTTON_WHEEL_DOWN),
}
