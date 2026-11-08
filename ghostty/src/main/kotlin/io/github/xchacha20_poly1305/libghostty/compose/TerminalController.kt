package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import io.github.xchacha20_poly1305.libghostty.GhosttyCellFlags
import io.github.xchacha20_poly1305.libghostty.GhosttyCellSize
import io.github.xchacha20_poly1305.libghostty.GhosttyClipboard
import io.github.xchacha20_poly1305.libghostty.GhosttyKey
import io.github.xchacha20_poly1305.libghostty.GhosttyKeyAction
import io.github.xchacha20_poly1305.libghostty.GhosttyMods
import io.github.xchacha20_poly1305.libghostty.GhosttyMouseAction
import io.github.xchacha20_poly1305.libghostty.GhosttyMouseButton
import io.github.xchacha20_poly1305.libghostty.GhosttyRenderState
import io.github.xchacha20_poly1305.libghostty.GhosttyScrollbar
import io.github.xchacha20_poly1305.libghostty.GhosttyTerminal
import io.github.xchacha20_poly1305.libghostty.GhosttyTerminalHost
import io.github.xchacha20_poly1305.libghostty.GhosttyTerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Everything [GhosttyTerminal] does between the session and the platform: frames, input, selection and scrolling.
 * Main thread only.
 */
internal class TerminalController(
    val state: GhosttyTerminalState,
    private val scope: CoroutineScope,
) : GhosttyTerminalHost {

    lateinit var clipboard: GhosttyClipboard
    lateinit var uriHandler: UriHandler
    lateinit var haptics: HapticFeedback
    lateinit var textToolbar: TextToolbar
    var onFinishedSessionKey: () -> Unit = {}

    /** Shows the software keyboard; set by the platform input modifier while it is attached. */
    var showSoftwareKeyboard: () -> Unit = {}

    var session: GhosttyTerminalSession? = null
        private set

    /**
     * The rows read from [session]. A render state only reads the rows its terminal marks dirty, so each attached
     * session gets a new one; a reused state would keep the previous session's rows.
     */
    var render = GhosttyRenderState()
        private set

    /** Changes whenever there is something new to draw. */
    var frame by mutableIntStateOf(0)
        private set

    var glyphs: GlyphPainter? by mutableStateOf(null)
        private set

    /** The grid this controller laid out and reported to the session. */
    var columns = 0
        private set
    var rows = 0
        private set
    private var widthPx = 0
    private var heightPx = 0

    /** The content padding in pixels; see [GhosttyTerminal]'s `contentPadding`. */
    private var padding = PaddingPx.Zero
    private var paddingMode = GhosttyContentPaddingMode.SCROLL_UNDER

    /**
     * How far the grid is shifted down from the top of the view: into the top padding at the top of the scrollback,
     * or up (negative) at the bottom, so the last row with content clears the bottom padding.
     */
    private var overscroll by mutableFloatStateOf(0f)

    /** Whether the bottom of the scrollback is shown at rest, so new output stays in sight. */
    private var followsBottom = true

    /** The last row with content the last time the viewport was at the bottom, or -1. */
    private var lastContentRow = -1

    /** Where the grid's top left corner is drawn in the view. */
    val gridOrigin: Offset
        get() = Offset(padding.left, overscroll)

    var layoutCoordinates: LayoutCoordinates? = null
    var density = 1f

    var composingText: String? by mutableStateOf(null)
        private set

    var cursorBlinkOn by mutableStateOf(true)
        private set
    private var blinkJob: Job? = null

    var focused by mutableStateOf(false)
        private set

    var selectionActive by mutableStateOf(false)
        private set

    /** Where a mouse context menu is open, or null. */
    var contextMenuAt: Offset? by mutableStateOf(null)

    var magnifierCenter by mutableStateOf(Offset.Unspecified)

    var accessibilityText: String? by mutableStateOf(null)
        private set
    private var accessibilityJob: Job? = null

    private val kittyImages = KittyImageCache()

    private val frameRequests = Channel<Unit>(Channel.CONFLATED)
    private var syncOutputSince: TimeMark? = null

    fun attach(newSession: GhosttyTerminalSession?) {
        if (newSession === session) return
        if (selectionActive) clearSelection()
        session?.detach(this)
        kittyImages.clear()
        composingText = null
        render = GhosttyRenderState()
        state.scrollbar = render.scrollbar
        overscroll = 0f
        followsBottom = true
        lastContentRow = -1
        session = newSession
        newSession?.attach(this)
        if (newSession != null && newSession.clipboard == null) {
            newSession.clipboard = clipboard
        }
        relayout()
        requestFrame()
    }

    fun setFont(fontFamily: FontFamily, fontSizePx: Float, resolver: FontFamily.Resolver) {
        glyphs = GlyphPainter(fontFamily, fontSizePx, resolver)
        if (selectionActive) clearSelection()
        relayout(force = true)
    }

    fun setSize(width: Int, height: Int) {
        if (width == widthPx && height == heightPx) return
        widthPx = width
        heightPx = height
        relayout()
    }

    fun setContentPadding(value: PaddingPx, mode: GhosttyContentPaddingMode) {
        if (value == padding && mode == paddingMode) return
        padding = value
        paddingMode = mode
        relayout()
        requestFrame()
    }

    val cellSize: GhosttyCellSize?
        get() = glyphs?.let { GhosttyCellSize(it.cellWidth, it.cellHeight) }

    /**
     * The grid spans the padded width. In [GhosttyContentPaddingMode.SCROLL_UNDER] it spans the whole height, since rows
     * scroll under the vertical padding; in [GhosttyContentPaddingMode.INSET] only the padded height.
     */
    private fun relayout(force: Boolean = false) {
        val session = session ?: return
        val size = cellSize ?: return
        val gridWidthPx = widthPx - (padding.left + padding.right).roundToInt()
        val gridHeightPx = when (paddingMode) {
            GhosttyContentPaddingMode.SCROLL_UNDER -> heightPx
            GhosttyContentPaddingMode.INSET -> heightPx - (padding.top + padding.bottom).roundToInt()
        }
        if (gridWidthPx <= 0 || gridHeightPx <= 0) return
        val newColumns = size.columnsFor(gridWidthPx)
        val newRows = size.rowsFor(gridHeightPx)
        if (!force && newColumns == columns && newRows == rows) return
        columns = newColumns
        rows = newRows
        session.resize(columns, rows, size.widthPx.roundToInt(), size.heightPx)
        requestFrame()
    }

    // GhosttyTerminalHost

    override fun onTerminalChanged() = requestFrame()

    override fun onBell() {
        if (state.bellHapticEnabled) {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        }
    }

    override fun requestClipboardRead(respond: (approved: Boolean) -> Unit) {
        state.dialogRequest = GhosttyTerminalState.DialogRequest.ClipboardRead(respond)
    }

    // Frames

    fun requestFrame() {
        frameRequests.trySend(Unit)
    }

    /** Updates the render state once per display frame while the terminal changes. */
    suspend fun runFrames() {
        for (request in frameRequests) {
            withFrameNanos { }
            if (updateFrame() == FrameResult.DEFERRED) {
                delay(SYNC_OUTPUT_RETRY)
                requestFrame()
            }
        }
    }

    private enum class FrameResult { UPDATED, UNCHANGED, DEFERRED }

    private fun updateFrame(): FrameResult {
        val session = session ?: return FrameResult.UNCHANGED
        return session.withTerminal { terminal ->
            if (deferForSyncOutput(terminal)) {
                return@withTerminal FrameResult.DEFERRED
            }
            val changed = render.update(terminal, session.selectionBackground, session.selectionForeground)
            state.scrollbar = render.scrollbar
            if (changed && render.scrollbar.isAtBottom) {
                lastContentRow = findLastContentRow()
            }
            settleOverscroll(render.scrollbar)
            if (!changed) {
                return@withTerminal FrameResult.UNCHANGED
            }
            kittyImages.update(terminal, render)
            if (selectionActive && (0 until render.rows).none { render.selection(it) != null }) {
                selectionActive = false
            }
            syncBlink()
            scheduleAccessibilityText()
            frame++
            FrameResult.UPDATED
        } ?: FrameResult.UNCHANGED
    }

    /**
     * Whether to skip this frame because the program holds synchronized output (mode 2026), which it may do for at
     * most [SYNC_OUTPUT_TIMEOUT_MS] before frames resume.
     */
    private fun deferForSyncOutput(terminal: GhosttyTerminal): Boolean {
        if (!terminal.isSynchronizedOutput) {
            syncOutputSince = null
            return false
        }
        val since = syncOutputSince ?: TimeSource.Monotonic.markNow().also { syncOutputSince = it }
        return since.elapsedNow().inWholeMilliseconds < SYNC_OUTPUT_TIMEOUT_MS
    }

    fun kittyImage(generation: Long): ImageBitmap? = kittyImages[generation]

    private fun scheduleAccessibilityText() {
        if (accessibilityJob?.isActive == true) return
        accessibilityJob = scope.launch {
            delay(ACCESSIBILITY_TEXT_DELAY)
            accessibilityText = session?.withTerminal { it.viewportText() }
        }
    }

    // Cursor blink

    fun onFocusChanged(value: Boolean) {
        focused = value
        syncBlink()
    }

    private fun syncBlink() {
        val wants = render.cursorBlinking && render.cursorVisible && focused
        if (wants && blinkJob?.isActive != true) {
            blinkJob = scope.launch {
                while (true) {
                    delay(BLINK_INTERVAL)
                    cursorBlinkOn = !cursorBlinkOn
                }
            }
        } else if (!wants && blinkJob != null) {
            blinkJob?.cancel()
            blinkJob = null
            cursorBlinkOn = true
        }
    }

    private fun holdBlinkSolid() {
        cursorBlinkOn = true
        if (blinkJob != null) {
            blinkJob?.cancel()
            blinkJob = null
            syncBlink()
        }
    }

    // Input

    private fun send(bytes: ByteArray, typed: Boolean = false) {
        val session = session ?: return
        if (bytes.isEmpty()) return
        if (session.isFinished) {
            onFinishedSessionKey()
            return
        }
        if (selectionActive) clearSelection()
        if (!render.scrollbar.isAtBottom || !followsBottom) {
            scrollToBottom()
        }
        holdBlinkSolid()
        if (typed) session.sendTypedInput(bytes) else session.sendRawInput(bytes)
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        val stickyMods = stickyMods()
        if (stickyMods != 0 && text.length == 1) {
            val char = text[0]
            // ghostty's ctrlSeq (src/input/key_encode.zig) maps a one-byte
            // utf8 argument to its C0 byte when the logical key has none.
            val bytes = session?.withTerminal { terminal ->
                encodeKeyTap(terminal, keyForChar(char), stickyMods, char.lowercaseChar().code, text)
            }
            if (bytes != null) {
                state.stickyModifiers?.consumeModifiers()
                send(bytes)
                return
            }
        }
        send(text.encodeToByteArray(), typed = true)
    }

    fun sendKey(key: GhosttyKey, mods: Int = 0, text: String? = null) {
        val stickyMods = stickyMods()
        val bytes = session?.withTerminal {
            encodeKeyTap(it, key, mods or stickyMods, text?.singleOrNull()?.code ?: 0, text)
        } ?: return
        if (stickyMods != 0) {
            state.stickyModifiers?.consumeModifiers()
        }
        send(bytes)
    }

    private fun stickyMods(): Int = state.stickyModifiers?.currentMods() ?: 0

    private fun encodeKeyTap(
        terminal: GhosttyTerminal,
        key: GhosttyKey,
        mods: Int,
        unshiftedCodepoint: Int,
        text: String?,
    ): ByteArray? {
        val press = terminal.encodeKey(key, GhosttyKeyAction.PRESS, mods, text, unshiftedCodepoint) ?: return null
        val release = terminal.encodeKey(key, GhosttyKeyAction.RELEASE, mods, text, unshiftedCodepoint)
        return if (release == null) {
            press
        } else {
            press + release
        }
    }

    /**
     * A key event from a keyboard. [text] is what the key types with the layout and modifiers, [unshiftedCodepoint]
     * what it types without modifiers. Returns whether the terminal consumed the event.
     */
    fun onKey(
        key: GhosttyKey,
        action: GhosttyKeyAction,
        mods: Int,
        text: String?,
        unshiftedCodepoint: Int,
        composing: Boolean = false,
    ): Boolean {
        val session = session ?: return false
        if (session.isFinished) {
            if (action == GhosttyKeyAction.PRESS) onFinishedSessionKey()
            return true
        }
        if (action != GhosttyKeyAction.RELEASE && mods and GhosttyMods.SHIFT != 0 &&
            (key == GhosttyKey.PAGE_UP || key == GhosttyKey.PAGE_DOWN)
        ) {
            scrollViewport(
                if (key == GhosttyKey.PAGE_UP) {
                    -rows
                } else {
                    rows
                }
            )
            return true
        }
        val stickyMods = if (action == GhosttyKeyAction.PRESS && !key.isModifier) {
            stickyMods()
        } else {
            0
        }
        val bytes = session.withTerminal {
            it.encodeKey(key, action, mods or stickyMods, text, unshiftedCodepoint, composing)
        } ?: return false
        if (stickyMods != 0) {
            state.stickyModifiers?.consumeModifiers()
        }
        send(bytes)
        return true
    }

    fun setComposing(text: String?) {
        composingText = text?.takeIf { it.isNotEmpty() }
    }

    /** The cursor cell in root coordinates, where input method windows go. */
    fun cursorRectInRoot(): Rect? {
        val glyphs = glyphs ?: return null
        val coordinates = layoutCoordinates?.takeIf { it.isAttached } ?: return null
        if (render.cursorColumn < 0 || render.cursorRow < 0) return null
        val topLeft = Offset(render.cursorColumn * glyphs.cellWidth, render.cursorRow * glyphs.cellHeight.toFloat())
        val local = Rect(topLeft, Size(glyphs.cellWidth, glyphs.cellHeight.toFloat())).translate(gridOrigin)
        return Rect(coordinates.localToRoot(local.topLeft), coordinates.localToRoot(local.bottomRight))
    }

    fun requestKeyboard() {
        if (state.showImeOnTap) {
            showSoftwareKeyboard()
        }
    }

    fun onWindowFocusChanged(focused: Boolean) {
        session?.sendFocus(focused)
    }

    // Clipboard

    fun paste() {
        val text = clipboard.getText()
        if (text.isNullOrEmpty() || session == null) return
        if (GhosttyTerminal.isPasteSafe(text)) {
            sendPaste(text)
        } else {
            state.dialogRequest = GhosttyTerminalState.DialogRequest.UnsafePaste { sendPaste(text) }
        }
    }

    private fun sendPaste(text: String) {
        val bytes = session?.withTerminal { it.encodePaste(text) } ?: return
        send(bytes)
    }

    fun copySelection() {
        val text = session?.withTerminal { it.selectionText() } ?: return
        clipboard.setText(text)
        clearSelection()
    }

    // Selection

    fun selectWord(column: Int, row: Int): Boolean {
        val selected = session?.withTerminal { it.selectWord(column, row) } ?: false
        selectionActive = selected
        requestFrame()
        return selected
    }

    fun selectAll() {
        if (session?.withTerminal { it.selectAll() } == true) {
            selectionActive = true
            requestFrame()
        }
    }

    /**
     * Extends the selection from the anchor cell to the cell under [position]. Dragging onto the first or last row
     * clear of the padding scrolls, and the anchor with it.
     */
    fun dragSelection(anchor: Cell, position: Offset, biasRows: Int = 0): Cell {
        val column = columnAt(position.x)
        val row = (rowAt(position.y) - biasRows).fastCoerceIn(0, rows - 1)
        var anchorRow = anchor.row
        session?.withTerminal { terminal ->
            val edge = when {
                row <= rowAt(padding.top) -> -1
                row >= rowAt(heightPx - padding.bottom - 1) -> 1
                else -> 0
            }
            if (edge != 0) {
                val moved = scrollScrollback(terminal, edge)
                anchorRow = (anchorRow - moved).fastCoerceIn(0, rows - 1)
            }
            selectionActive = terminal.select(anchor.column, anchorRow, column, row)
        }
        requestFrame()
        return Cell(anchor.column, anchorRow)
    }

    fun clearSelection() {
        session?.withTerminal { it.clearSelection() }
        selectionActive = false
        textToolbar.hide()
        requestFrame()
    }

    /** The first and last selected cells, from the last frame. */
    fun selectionBounds(): Pair<Cell, Cell>? {
        var first: Cell? = null
        var last: Cell? = null
        for (row in 0 until render.rows) {
            val range = render.selection(row) ?: continue
            if (first == null) {
                first = Cell(range.first, row)
            }
            last = Cell(range.last, row)
        }
        return if (first != null && last != null) {
            first to last
        } else {
            null
        }
    }

    /** Shows the text toolbar over the selection, or over [at] when there is none. */
    fun showTextToolbar(at: Cell) {
        val glyphs = glyphs ?: return
        val coordinates = layoutCoordinates ?: return
        val bounds = selectionBounds()
        val inGrid = if (bounds != null) {
            val (first, last) = bounds
            val singleRow = first.row == last.row
            Rect(
                left = if (singleRow) {
                    first.column * glyphs.cellWidth
                } else {
                    0f
                },
                top = first.row * glyphs.cellHeight.toFloat(),
                right = if (singleRow) {
                    (last.column + 1) * glyphs.cellWidth
                } else {
                    gridWidth
                },
                bottom = (last.row + 1) * glyphs.cellHeight.toFloat(),
            )
        } else {
            Rect(
                left = at.column * glyphs.cellWidth,
                top = at.row * glyphs.cellHeight.toFloat(),
                right = (at.column + 1) * glyphs.cellWidth,
                bottom = (at.row + 1) * glyphs.cellHeight.toFloat(),
            )
        }
        val local = inGrid.translate(gridOrigin)
        textToolbar.showMenu(
            rect = Rect(coordinates.localToRoot(local.topLeft), coordinates.localToRoot(local.bottomRight)),
            onCopyRequested = if (selectionActive) {
                ::copySelection
            } else {
                null
            },
            onPasteRequested = ::paste,
            onSelectAllRequested = ::selectAll,
        )
    }

    // Links

    /** The link under a cell: an OSC 8 hyperlink, or a URL in the text when detection is enabled. */
    fun linkAt(cell: Cell): String? = session?.withTerminal { it.hyperlinkAt(cell.column, cell.row) }
        ?: if (state.urlDetectionEnabled) {
            detectUrlAt(cell)
        } else {
            null
        }

    private fun detectUrlAt(cell: Cell): String? {
        if (cell.row >= render.rows || render.columns == 0) return null
        val chars = render.rowText(cell.row)
        val builder = StringBuilder(render.columns)
        val columnStart = IntArray(render.columns)
        for (column in 0 until render.columns) {
            columnStart[column] = builder.length
            if (render.flags(column, cell.row) and GhosttyCellFlags.SPACER != 0) continue
            val length = render.textLength(column, cell.row)
            val offset = render.textOffset(column, cell.row)
            if (length == 0 || offset + length > chars.size) {
                builder.append(' ')
            } else {
                builder.appendRange(
                    chars,
                    offset,
                    offset + length
                )
            }
        }
        val pressIndex = columnStart[cell.column.fastCoerceIn(0, render.columns - 1)]
        return state.urlPattern.findAll(builder)
            .firstOrNull { pressIndex in it.range }
            ?.value
            ?.trimEnd('.', ',', ';', ':', ')', ']', '>', '"', '\'')
    }

    fun showLinkMenu(url: String) {
        state.dialogRequest = GhosttyTerminalState.DialogRequest.LinkMenu(
            url,
            openLink = { uriHandler.openUri(url) },
            copyUrl = { clipboard.setText(url) },
        )
    }

    // Scrolling and mouse

    val isMouseTracking: Boolean
        get() = session?.withTerminal { it.isMouseTracking } ?: false

    /** Whether the program, rather than the scrollback, takes scroll gestures. */
    val scrollsProgram: Boolean
        get() = session?.withTerminal { it.isAlternateScreen || it.isMouseTracking } ?: false

    fun sendMouse(action: GhosttyMouseAction, button: GhosttyMouseButton?, cell: Cell, mods: Int = 0) {
        val bytes = session?.withTerminal { it.encodeMouse(action, button, cell.column, cell.row, mods) } ?: return
        session?.sendRawInput(bytes)
    }

    /**
     * Scrolls by [deltaRows]: as wheel reports when the program tracks the mouse, as arrow keys on the alternate
     * screen, and through the scrollback otherwise.
     */
    fun scrollContent(deltaRows: Int, at: Cell) {
        if (deltaRows == 0) return
        val session = session ?: return
        session.withTerminal { terminal ->
            when {
                terminal.isMouseTracking -> {
                    val button = if (deltaRows > 0) GhosttyMouseButton.WHEEL_DOWN else GhosttyMouseButton.WHEEL_UP
                    repeat(abs(deltaRows)) {
                        terminal.encodeMouse(GhosttyMouseAction.PRESS, button, at.column, at.row)
                            ?.let(session::sendRawInput)
                    }
                }

                terminal.isAlternateScreen -> {
                    val key = if (deltaRows > 0) GhosttyKey.ARROW_DOWN else GhosttyKey.ARROW_UP
                    repeat(abs(deltaRows)) {
                        encodeKeyTap(terminal, key, 0, 0, null)?.let(session::sendRawInput)
                    }
                }

                else -> scrollScrollback(terminal, deltaRows)
            }
        }
    }

    private fun scrollViewport(deltaRows: Int) {
        session?.withTerminal { scrollScrollback(it, deltaRows) }
    }

    /**
     * Scrolls through the scrollback by [deltaRows], a row at a time: at either end of the scrollback the grid moves
     * into the padding, and coming back it leaves the padding before the viewport moves. Returns how many rows the
     * viewport itself moved.
     */
    private fun scrollScrollback(terminal: GhosttyTerminal, deltaRows: Int): Int {
        val offsetBefore = terminal.scrollbar.offset
        for (step in 0 until abs(deltaRows)) {
            val moved = if (deltaRows > 0) scrollRowDown(terminal) else scrollRowUp(terminal)
            if (!moved) break
        }
        val scrollbar = terminal.scrollbar
        followsBottom = scrollbar.isAtBottom && overscroll <= overscrollRange(scrollbar).start
        state.onUserScrolled()
        requestFrame()
        return (scrollbar.offset - offsetBefore).toInt()
    }

    /** Moves the content a row up, toward the bottom of the scrollback; returns false at the end. */
    private fun scrollRowDown(terminal: GhosttyTerminal): Boolean {
        val cellHeight = glyphs?.cellHeight?.toFloat() ?: return false
        val scrollbar = terminal.scrollbar
        val range = overscrollRange(scrollbar)
        val topPaddingEnd = max(0f, range.start)
        when {
            overscroll > topPaddingEnd -> overscroll = max(overscroll - cellHeight, topPaddingEnd)
            !scrollbar.isAtBottom -> terminal.scroll(1)
            overscroll > range.start -> overscroll = max(overscroll - cellHeight, range.start)
            else -> return false
        }
        return true
    }

    /** Moves the content a row down, toward the top of the scrollback; returns false at the end. */
    private fun scrollRowUp(terminal: GhosttyTerminal): Boolean {
        val cellHeight = glyphs?.cellHeight?.toFloat() ?: return false
        val scrollbar = terminal.scrollbar
        val range = overscrollRange(scrollbar)
        when {
            overscroll < 0f -> overscroll = min(overscroll + cellHeight, 0f)
            scrollbar.offset > 0 -> terminal.scroll(-1)
            overscroll < range.endInclusive -> overscroll = min(overscroll + cellHeight, range.endInclusive)
            else -> return false
        }
        return true
    }

    fun scrollToBottom() {
        session?.withTerminal { it.scrollToBottom() }
        followsBottom = true
        requestFrame()
    }

    /**
     * Jumps the viewport to start at [row], without the row-by-row padding steps of [scrollScrollback]: the grid rests
     * below the top padding at the top of the scrollback and above the bottom padding at the bottom.
     */
    fun scrollTo(row: Long) {
        val session = session ?: return
        session.withTerminal { terminal ->
            val before = terminal.scrollbar
            val upper = (before.total - before.length).fastCoerceAtLeast(0L)
            val target = row.fastCoerceIn(0L, max(0L, upper))
            terminal.scroll((target - before.offset).toInt())
            val after = terminal.scrollbar
            val range = overscrollRange(after)
            overscroll = when {
                after.isAtBottom -> range.start
                after.offset == 0L -> range.endInclusive
                else -> 0f
            }
            followsBottom = after.isAtBottom
        }
        state.onUserScrolled()
        requestFrame()
    }

    /**
     * How far [overscroll] may go: down into the top padding at the top of the scrollback, and at the bottom up until
     * the last row with content clears the bottom padding. When both ends are shown at once, the bottom wins. In
     * [GhosttyContentPaddingMode.INSET] the grid stays below the top padding.
     */
    private fun overscrollRange(scrollbar: GhosttyScrollbar): ClosedFloatingPointRange<Float> {
        if (paddingMode == GhosttyContentPaddingMode.INSET) return padding.top..padding.top
        val cellHeight = glyphs?.cellHeight ?: return 0f..0f
        val top = if (scrollbar.offset == 0L) padding.top else 0f
        val bottom = if (scrollbar.isAtBottom) {
            min(top, heightPx - padding.bottom - (lastContentRow + 1) * cellHeight)
        } else {
            0f
        }
        return bottom..top
    }

    /** Keeps [overscroll] in its range after the content, the size or the padding changed. */
    private fun settleOverscroll(scrollbar: GhosttyScrollbar) {
        val range = overscrollRange(scrollbar)
        overscroll = if (followsBottom && scrollbar.isAtBottom) range.start else overscroll.coerceIn(range)
    }

    /** The last row of [render] with text, a background, or the visible cursor; -1 when there is none. */
    private fun findLastContentRow(): Int {
        val cursorRow = if (render.cursorVisible) render.cursorRow else -1
        for (row in render.rows - 1 downTo cursorRow + 1) {
            for (column in 0 until render.columns) {
                if (render.textLength(column, row) > 0 || render.background(column, row) != render.defaultBackground) {
                    return row
                }
            }
        }
        return cursorRow
    }

    // Geometry

    /** The column under [x] in view coordinates. */
    fun columnAt(x: Float): Int =
        floor((x - gridOrigin.x) / (glyphs?.cellWidth ?: 1f)).toInt().fastCoerceIn(0, max(columns - 1, 0))

    /** The row under [y] in view coordinates. */
    fun rowAt(y: Float): Int =
        floor((y - gridOrigin.y) / (glyphs?.cellHeight ?: 1)).toInt().fastCoerceIn(0, max(rows - 1, 0))

    fun cellAt(position: Offset): Cell = Cell(columnAt(position.x), rowAt(position.y))

    val gridWidth: Float
        get() = ceil(columns * (glyphs?.cellWidth ?: 0f))

    fun changeFontSize(steps: Int) {
        state.fontSizeSteps(steps)
    }

    data class Cell(val column: Int, val row: Int)

    data class PaddingPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        companion object {
            val Zero = PaddingPx(0f, 0f, 0f, 0f)
        }
    }

    private companion object {
        val BLINK_INTERVAL = 600.milliseconds
        val SYNC_OUTPUT_RETRY = 32.milliseconds
        const val SYNC_OUTPUT_TIMEOUT_MS = 1000L
        val ACCESSIBILITY_TEXT_DELAY = 500.milliseconds
    }
}

/** Kitty graphics images as bitmaps, kept by image generation up to a total pixel budget. */
private class KittyImageCache {
    private val images = LinkedHashMap<Long, ImageBitmap>(16, 0.75f, true)
    private var pixels = 0L

    operator fun get(generation: Long): ImageBitmap? = images[generation]

    fun update(terminal: GhosttyTerminal, render: GhosttyRenderState) {
        for (placement in render.kittyPlacements) {
            if (placement.imageGeneration in images) continue
            val image = terminal.kittyImage(placement.imageId) ?: continue
            images[placement.imageGeneration] = imageBitmapOf(image.width, image.height, image.pixels)
            pixels += image.width.toLong() * image.height
        }
        val iterator = images.entries.iterator()
        while (pixels > MAX_PIXELS && iterator.hasNext()) {
            val bitmap = iterator.next().value
            pixels -= bitmap.width.toLong() * bitmap.height
            iterator.remove()
        }
    }

    fun clear() {
        images.clear()
        pixels = 0
    }

    private companion object {
        /** 64 MiB of ARGB pixels. */
        const val MAX_PIXELS = 16L * 1024 * 1024
    }
}
