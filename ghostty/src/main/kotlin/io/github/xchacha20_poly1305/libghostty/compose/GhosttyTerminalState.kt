package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.xchacha20_poly1305.libghostty.GhosttyCellSize
import io.github.xchacha20_poly1305.libghostty.GhosttyKey
import io.github.xchacha20_poly1305.libghostty.GhosttyScrollbar
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * State and settings of a [GhosttyTerminal]. Widgets around the terminal, such as [GhosttyExtraKeysBar],
 * [GhosttyJumpToBottomButton], [GhosttyScrollIndicator] and [GhosttyDialogs], work through this state only, so an app
 * can replace any of them with its own.
 */
@Stable
public class GhosttyTerminalState {

    /**
     * Modifiers held outside the keyboard, added to the next key the terminal sends; [GhosttyExtraKeysBar] sets its
     * own while it is shown.
     */
    public var stickyModifiers: GhosttyStickyModifiers? = null

    /** Vibrates on BEL. */
    public var bellHapticEnabled: Boolean by mutableStateOf(true)

    /** Shows the software keyboard when the terminal is tapped. */
    public var showImeOnTap: Boolean by mutableStateOf(true)

    /**
     * Makes the input method treat the terminal as a text field without suggestions, instead of a raw key target.
     * Some Samsung keyboards keep composing state across raw key targets (termux/termux-app#686).
     */
    public var enforceCharBasedInput: Boolean by mutableStateOf(false)

    /** Matches plain text as a URL, in addition to the hyperlinks the terminal reported. */
    public var urlDetectionEnabled: Boolean by mutableStateOf(true)

    public var urlPattern: Regex by mutableStateOf(Regex("""https?://\S+"""))

    /** The font sizes pinch zoom may reach, in sp. */
    public var fontSizeRangeSp: ClosedFloatingPointRange<Float> by mutableStateOf(8f..48f)

    /** The cell size of the current font in pixels, once the terminal is laid out. */
    public val cellSize: GhosttyCellSize?
        get() = controller?.cellSize

    /** Where the viewport is in the scrollback, as of the last frame; empty while no session is shown. */
    public var scrollbar: GhosttyScrollbar by mutableStateOf(GhosttyScrollbar(0, 0, 0))
        internal set

    /**
     * Emits each time the user scrolls the scrollback (touch, wheel, Shift+Page Up/Down, or dragging a selection past
     * an edge), but not when new output moves it.
     */
    public val userScrolls: SharedFlow<Unit>
        field = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal fun onUserScrolled() {
        userScrolls.tryEmit(Unit)
    }

    internal var controller: TerminalController? by mutableStateOf(null)

    /** The font size zoom moved to; reset when the font size parameter changes. */
    internal var zoomedFontSize: TextUnit? by mutableStateOf(null)
    internal var baseFontSize: TextUnit = 14.sp

    internal fun fontSizeSteps(steps: Int) {
        val current = zoomedFontSize ?: baseFontSize
        val next = (current.value + steps).coerceIn(fontSizeRangeSp).sp
        if (next != current) zoomedFontSize = next
    }

    /** Types [text] as if typed on a keyboard, with the [stickyModifiers]. */
    public fun sendText(text: String) {
        controller?.sendText(text)
    }

    /**
     * Taps [key] with [mods] ([GhosttyMods] bits) and the [stickyModifiers]. [text] is what the key types without
     * modifiers, which the legacy encoding of a Ctrl or Alt combination is built from.
     */
    public fun sendKey(key: GhosttyKey, mods: Int = 0, text: String? = null) {
        controller?.sendKey(key, mods, text)
    }

    /** Pastes the clipboard, asking first when the text could run commands by itself. */
    public fun paste() {
        controller?.paste()
    }

    /** Scrolls to the bottom of the scrollback, past the bottom content padding, and follows new output again. */
    public fun scrollToBottom() {
        controller?.scrollToBottom()
    }

    /**
     * Moves the viewport so it starts at [row] of the scrollback, clamped to the range [scrollbar] describes, the way
     * dragging a scrollbar thumb does. Counts as a user scroll.
     */
    public fun scrollTo(row: Long) {
        controller?.scrollTo(row)
    }

    /** Gives the terminal input focus and shows the software keyboard. */
    public fun showKeyboard() {
        focusTerminal()
        controller?.showSoftwareKeyboard?.invoke()
    }

    internal var focusRequester: FocusRequester? = null

    internal fun focusTerminal() {
        focusRequester?.requestFocus()
    }

    internal sealed interface DialogRequest {
        class UnsafePaste(val paste: () -> Unit) : DialogRequest

        class ClipboardRead(val respond: (approved: Boolean) -> Unit) : DialogRequest

        class LinkMenu(val url: String, val openLink: () -> Unit, val copyUrl: () -> Unit) : DialogRequest
    }

    internal var dialogRequest: DialogRequest? by mutableStateOf(null)
}

@Composable
public fun rememberGhosttyTerminalState(): GhosttyTerminalState = remember { GhosttyTerminalState() }
