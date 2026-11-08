package io.github.xchacha20_poly1305.libghostty.compose

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import io.github.xchacha20_poly1305.libghostty.GhosttyKeyAction
import io.github.xchacha20_poly1305.libghostty.GhosttyMods
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Routes input to the terminal while it is focused: hardware keys and the platform input method, including its
 * composition.
 */
internal fun Modifier.terminalInput(controller: TerminalController): Modifier =
    this then TerminalInputElement(controller, controller.state.enforceCharBasedInput)

private data class TerminalInputElement(
    val controller: TerminalController,
    val enforceCharBasedInput: Boolean,
) : ModifierNodeElement<TerminalInputNode>() {
    override fun create() = TerminalInputNode(controller, enforceCharBasedInput)

    override fun update(node: TerminalInputNode) = node.update(controller, enforceCharBasedInput)

    override fun InspectorInfo.inspectableProperties() {
        name = "terminalInput"
    }
}

/**
 * Keeps an input method session open from the first keyboard request until the terminal loses focus. Focus alone
 * starts no session, because starting one always shows the software keyboard. The input method sees an always empty
 * editor: committed text goes straight to the terminal, and composing text is only drawn.
 */
private class TerminalInputNode(
    var controller: TerminalController,
    var enforceCharBasedInput: Boolean,
) : Modifier.Node(),
    PlatformTextInputModifierNode,
    FocusEventModifierNode,
    KeyInputModifierNode,
    CompositionLocalConsumerModifierNode {

    private val keys = AndroidKeyTranslator()
    private var session: Job? = null
    private var focused = false
    private var keyboardPending = false

    override fun onAttach() {
        controller.showSoftwareKeyboard = ::showKeyboard
    }

    override fun onDetach() {
        if (controller.showSoftwareKeyboard == ::showKeyboard) controller.showSoftwareKeyboard = {}
    }

    fun update(controller: TerminalController, enforceCharBasedInput: Boolean) {
        this.controller = controller
        controller.showSoftwareKeyboard = ::showKeyboard
        if (this.enforceCharBasedInput != enforceCharBasedInput) {
            this.enforceCharBasedInput = enforceCharBasedInput
            // The input type is read when the input method connects.
            if (session != null) {
                stopInput()
                startInput()
            }
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focused == focusState.isFocused) return
        focused = focusState.isFocused
        if (!focused) {
            keyboardPending = false
            stopInput()
        } else if (keyboardPending) {
            keyboardPending = false
            startInput()
        }
    }

    // Focus moving off a screen that is being popped dispatches a hide, which cancels a show issued in the same
    // frame; showing once the input session is established avoids racing it.
    private fun startInput() {
        session = coroutineScope.launch {
            establishTextInputSession {
                showKeyboardNow()
                startInputMethod(TerminalInputRequest())
            }
        }
    }

    private fun stopInput() {
        session?.cancel()
        session = null
        controller.setComposing(null)
    }

    private val view: View
        get() = currentValueOf(LocalView)

    private fun showKeyboard() {
        when {
            session != null -> showKeyboardNow()
            focused -> startInput()
            else -> keyboardPending = true
        }
    }

    private fun showKeyboardNow() {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, 0)
    }

    override fun onKeyEvent(event: ComposeKeyEvent): Boolean = handle(event.nativeKeyEvent)

    override fun onPreKeyEvent(event: ComposeKeyEvent): Boolean {
        val native = event.nativeKeyEvent
        // Back dismisses the selection before it reaches the activity.
        if (native.keyCode == KeyEvent.KEYCODE_BACK && controller.selectionActive) {
            if (native.action == KeyEvent.ACTION_DOWN) controller.clearSelection()
            return true
        }
        return false
    }

    private fun handle(event: KeyEvent): Boolean {
        @Suppress("DEPRECATION")
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
            controller.sendText(event.characters)
            return true
        }
        if (event.isSystem && controller.session?.isFinished == true) return false
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) GhosttyKeyAction.REPEAT else GhosttyKeyAction.PRESS
            KeyEvent.ACTION_UP -> GhosttyKeyAction.RELEASE
            else -> return false
        }
        val translated = keys.translate(event, action)
        return controller.onKey(
            Key(event.keyCode).ghosttyKey,
            action,
            translated.mods,
            translated.text,
            event.getUnicodeChar(0),
            translated.composing,
        )
    }

    private inner class TerminalInputRequest : PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
            // Gboard treats a TYPE_NULL field as one it cannot read back: it sends
            // Latin keystrokes and backspace as KeyEvents instead of replaying
            // deleteSurroundingText + commitText against its own copy of the
            // committed text, which drifts from the terminal after the first
            // echo, autocorrect or auto-space.
            outAttributes.inputType = if (enforceCharBasedInput) {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_NULL
            }
            outAttributes.imeOptions = EditorInfo.IME_ACTION_NONE or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return TerminalInputConnection(view)
        }
    }

    private inner class TerminalInputConnection(view: View) : BaseInputConnection(view, true) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            flushEditable()
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            super.setComposingText(text, newCursorPosition)
            controller.setComposing(editable?.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            flushEditable()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        private fun flushEditable() {
            controller.setComposing(null)
            val content = editable ?: return
            if (content.isNotEmpty()) controller.sendText(content.toString())
            content.clear()
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = handle(event) || super.sendKeyEvent(event)
    }
}

/** What an Android key event types, including dead key composition, and its modifiers. */
private class AndroidKeyTranslator {
    private var combiningAccent = 0

    class Result(val mods: Int, val text: String?, val composing: Boolean)

    fun translate(event: KeyEvent, action: GhosttyKeyAction): Result {
        val metaState = event.metaState
        // Ctrl and Meta never change the character; Alt does only as AltGr.
        var unicodeMask = KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK
        if (metaState and KeyEvent.META_ALT_RIGHT_ON == 0) {
            unicodeMask = unicodeMask or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var unicode = event.getUnicodeChar(metaState and unicodeMask.inv())
        var composing = false
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            if (action == GhosttyKeyAction.PRESS) combiningAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            composing = true
            unicode = 0
        } else if (combiningAccent != 0 && action == GhosttyKeyAction.PRESS && unicode != 0) {
            val combined = KeyCharacterMap.getDeadChar(combiningAccent, unicode)
            if (combined > 0) unicode = combined
            combiningAccent = 0
        }
        val text = if (unicode != 0) String(Character.toChars(unicode)) else null
        return Result(mods(metaState), text, composing)
    }

    private fun mods(metaState: Int): Int {
        var mods = 0
        for ((meta, mod) in metaMods) {
            if (metaState and meta != 0) mods = mods or mod
        }
        return mods
    }

    private companion object {
        val metaMods = listOf(
            KeyEvent.META_SHIFT_ON to GhosttyMods.SHIFT,
            KeyEvent.META_SHIFT_RIGHT_ON to GhosttyMods.SHIFT_RIGHT,
            KeyEvent.META_CTRL_ON to GhosttyMods.CTRL,
            KeyEvent.META_CTRL_RIGHT_ON to GhosttyMods.CTRL_RIGHT,
            KeyEvent.META_ALT_ON to GhosttyMods.ALT,
            KeyEvent.META_ALT_RIGHT_ON to GhosttyMods.ALT_RIGHT,
            KeyEvent.META_META_ON to GhosttyMods.SUPER,
            KeyEvent.META_META_RIGHT_ON to GhosttyMods.SUPER_RIGHT,
            KeyEvent.META_CAPS_LOCK_ON to GhosttyMods.CAPS_LOCK,
            KeyEvent.META_NUM_LOCK_ON to GhosttyMods.NUM_LOCK,
        )
    }
}
