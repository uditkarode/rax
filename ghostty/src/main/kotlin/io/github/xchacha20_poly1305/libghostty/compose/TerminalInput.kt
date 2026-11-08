package io.github.xchacha20_poly1305.libghostty.compose

import android.content.Context
import android.graphics.Matrix
import android.os.Bundle
import android.text.InputType
import android.text.Selection
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
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

    /** The connection whose input method follows the cursor, to place its windows next to it. */
    private var cursorWatcher: TerminalInputConnection? = null

    override fun onAttach() {
        controller.showSoftwareKeyboard = ::showKeyboard
        controller.hideSoftwareKeyboard = ::hideKeyboard
        controller.onFrameUpdated = ::onFrameUpdated
    }

    override fun onDetach() {
        if (controller.showSoftwareKeyboard == ::showKeyboard) controller.showSoftwareKeyboard = {}
        if (controller.hideSoftwareKeyboard == ::hideKeyboard) controller.hideSoftwareKeyboard = {}
        if (controller.onFrameUpdated == ::onFrameUpdated) controller.onFrameUpdated = {}
    }

    fun update(controller: TerminalController, enforceCharBasedInput: Boolean) {
        this.controller = controller
        controller.showSoftwareKeyboard = ::showKeyboard
        controller.hideSoftwareKeyboard = ::hideKeyboard
        controller.onFrameUpdated = ::onFrameUpdated
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

    private fun onFrameUpdated() {
        cursorWatcher?.reportCursor()
    }

    private fun stopInput() {
        session?.cancel()
        session = null
        cursorWatcher = null
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

    private fun hideKeyboard() {
        keyboardPending = false
        stopInput()
        val imm = view.context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboardNow() {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, 0)
    }

    override fun onKeyEvent(event: ComposeKeyEvent): Boolean = handle(event.nativeKeyEvent)

    override fun onPreKeyEvent(event: ComposeKeyEvent): Boolean = false

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
            // A plain text field keeps the keyboard's voice input and gesture typing, which a
            // password or TYPE_NULL field turns off. Without suggestions, Gboard commits each
            // character as it is typed, so programs that read single keys still get them at
            // once. Its deletes and corrections arrive as deleteSurroundingText, which becomes
            // backspaces.
            outAttributes.inputType = if (enforceCharBasedInput) {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            outAttributes.imeOptions = EditorInfo.IME_ACTION_NONE or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (controller.state.onReceiveContent != null) outAttributes.contentMimeTypes = arrayOf("*/*")
            return TerminalInputConnection(view)
        }
    }

    private inner class TerminalInputConnection(private val target: View) : BaseInputConnection(target, true) {
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
            if (cursorUpdateMode and InputConnection.CURSOR_UPDATE_MONITOR != 0) {
                cursorWatcher = this
            } else if (cursorWatcher === this) {
                cursorWatcher = null
            }
            if (cursorUpdateMode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) reportCursor()
            return true
        }

        fun reportCursor() {
            val rect = controller.cursorRectInRoot() ?: return
            val content = editable ?: return
            val onScreen = IntArray(2).also(target::getLocationOnScreen)
            val builder = CursorAnchorInfo.Builder()
                .setMatrix(Matrix().apply { setTranslate(onScreen[0].toFloat(), onScreen[1].toFloat()) })
                .setSelectionRange(Selection.getSelectionStart(content), Selection.getSelectionEnd(content))
                .setInsertionMarkerLocation(
                    rect.left,
                    rect.top,
                    rect.bottom,
                    rect.bottom,
                    CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
                )
            val composingStart = BaseInputConnection.getComposingSpanStart(content)
            val composingEnd = BaseInputConnection.getComposingSpanEnd(content)
            if (composingStart in 0 until composingEnd) {
                builder.setComposingText(composingStart, content.subSequence(composingStart, composingEnd))
            }
            target.context.getSystemService(InputMethodManager::class.java)
                .updateCursorAnchorInfo(target, builder.build())
        }

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

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

        private fun flushEditable() {
            controller.setComposing(null)
            val content = editable ?: return
            val text = content.toString()
            content.clear()
            when {
                text.isEmpty() -> {}
                // Keyboards insert clipboard clips this way; with line breaks, they must not run line by line.
                text.length > 1 && (text.contains('\n') || text.contains('\r')) -> controller.pasteText(text)
                else -> controller.sendText(text)
            }
        }

        // Images, GIFs and stickers from the keyboard.
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
            val receive = controller.state.onReceiveContent ?: return false
            if (flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                try {
                    inputContentInfo.requestPermission()
                } catch (_: Exception) {
                    return false
                }
            }
            receive(GhosttyContent(inputContentInfo.contentUri, inputContentInfo::releasePermission))
            return true
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
        val unicodeMask = KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK
        val rightAlt = metaState and KeyEvent.META_ALT_RIGHT_ON != 0
        val withoutAlt = event.getUnicodeChar(metaState and (unicodeMask or KeyEvent.META_ALT_MASK).inv())
        var unicode = if (rightAlt) event.getUnicodeChar(metaState and unicodeMask.inv()) else withoutAlt
        // The layout used right Alt to type another character, so Alt is not also a modifier of the key.
        val altGr = rightAlt && unicode != 0 && unicode != withoutAlt
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
        return Result(keyMods(metaState, altGr), text, composing)
    }
}

/** [GhosttyMods] bits for an Android meta state. With [altGr], right Alt typed the character and is left out. */
internal fun keyMods(metaState: Int, altGr: Boolean): Int {
    var state = metaState
    if (altGr) {
        state = state and KeyEvent.META_ALT_RIGHT_ON.inv()
        if (state and KeyEvent.META_ALT_LEFT_ON == 0) state = state and KeyEvent.META_ALT_ON.inv()
    }
    var mods = 0
    for ((meta, mod) in metaMods) {
        if (state and meta != 0) mods = mods or mod
    }
    return mods
}

private val metaMods = listOf(
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
