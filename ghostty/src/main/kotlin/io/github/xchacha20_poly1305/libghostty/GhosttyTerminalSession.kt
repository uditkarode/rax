package io.github.xchacha20_poly1305.libghostty

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Threading: [feedOutput] and [finish] may be called from any thread. All other methods and
 * property writes must happen on the main thread. State flows, [EventListener] and [GhosttyTerminalHost] methods
 * are called on the main thread, except [exitStatus], which is updated on the thread that calls [finish].
 * [Transport] methods may be called from any thread that calls [feedOutput], and from the main thread;
 * implementations must be thread safe and must not block.
 *
 * Lifecycle: [close] is idempotent; after it, every method is a no-op.
 */
public class GhosttyTerminalSession private constructor(
    options: Options,
    snapshot: ByteArray?,
) {

    public constructor(options: Options = Options()) : this(options, null)

    public class Options(
        public val columns: Int = 80,
        public val rows: Int = 24,
        public val maxScrollbackLines: Long = 10_000,
        public val reportedVersion: String = "libghostty",
        public val terminfoName: String? = "xterm-256color",
        public val defaultCursorStyle: GhosttyCursorStyle = GhosttyCursorStyle.BLOCK,
        public val defaultCursorBlink: Boolean = true,
        /**
         * Whether grapheme clustering (DEC mode 2027) is set initially and after a reset, so multi-codepoint
         * emoji and combining sequences take their Unicode width. Programs can still toggle the mode.
         */
        public val defaultGraphemeCluster: Boolean = false,
    ) {
        init {
            require(columns >= 1) { "columns must be >= 1" }
            require(rows >= 1) { "rows must be >= 1" }
            require(maxScrollbackLines >= 0) { "maxScrollbackLines must be >= 0" }
        }
    }

    public interface EventListener {
        public fun onBell(session: GhosttyTerminalSession) {}

        public fun onNotification(session: GhosttyTerminalSession, title: String, body: String) {}
    }

    public interface Transport {
        public fun sendInput(data: ByteArray)

        public fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int)

        public fun close()
    }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val terminalAccess = Any()
    private val outputDispatchPending = AtomicBoolean(false)
    private val outputDispatchScheduled = AtomicBoolean(false)

    private var handle = if (snapshot == null) {
        GhosttyVt.nativeCreate(
            options.columns,
            options.rows,
            options.maxScrollbackLines,
            options.reportedVersion,
            options.terminfoName,
            options.defaultCursorStyle.nativeValue,
            options.defaultCursorBlink,
            options.defaultGraphemeCluster,
            GhosttyVtCallbacks(this),
        )
    } else {
        GhosttyVt.nativeRestore(snapshot, options.reportedVersion, options.terminfoName, GhosttyVtCallbacks(this))
    }

    private val terminal = GhosttyTerminal().also { it.handle = handle }

    /**
     * Runs [block] with the terminal, holding it against concurrent [feedOutput]; returns null once the session
     * is closed. Main thread only.
     */
    public fun <T> withTerminal(block: (GhosttyTerminal) -> T): T? = synchronized(terminalAccess) {
        if (handle == 0L) {
            null
        } else {
            block(terminal)
        }
    }

    private inline fun <T> withHandle(block: (Long) -> T): T? = synchronized(terminalAccess) {
        if (handle == 0L) {
            null
        } else {
            block(handle)
        }
    }

    public var eventListener: EventListener? = null

    /**
     * The clipboard programs write with OSC 52 and, once the user approves through the [GhosttyTerminalHost], read.
     * Without one, clipboard writes are dropped and reads answer an empty clipboard.
     */
    @Volatile
    public var clipboard: GhosttyClipboard? = null

    public var transport: Transport? = null
        set(value) {
            field = value
            if (value != null && columns > 0 && rows > 0) {
                sentColumns = 0
                sentRows = 0
                sentCellWidthPixels = 0
                sentCellHeightPixels = 0
                sendTransportResize()
            }
        }

    public val title: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    public val workingDirectory: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    public val progress: StateFlow<GhosttyProgress?>
        field = MutableStateFlow<GhosttyProgress?>(null)

    public val backgroundColor: StateFlow<Int?>
        field = MutableStateFlow<Int?>(null)

    public val exitStatus: StateFlow<Int?>
        field = MutableStateFlow<Int?>(null)

    public val isFinished: Boolean
        get() = exitStatus.value != null

    public var selectionBackground: Int? = null
        private set

    public var selectionForeground: Int? = null
        private set

    public var columns: Int = 0
        private set

    public var rows: Int = 0
        private set

    /** The UI showing this session, if any. */
    public var host: GhosttyTerminalHost? = null
        private set

    @Volatile
    private var clipboardReadApproved = false
    private val clipboardReadPromptPending = AtomicBoolean(false)
    private var cellWidthPixels = 0
    private var cellHeightPixels = 0

    private var sentColumns = 0
    private var sentRows = 0
    private var sentCellWidthPixels = 0
    private var sentCellHeightPixels = 0
    private var resizeSend: Job? = null

    init {
        backgroundColor.value = withHandle { GhosttyVt.nativeGetBackgroundColor(it) }
            ?.takeIf { it != 0 }
    }

    /** Shows this session in [host]. A session is shown by at most one host at a time. */
    public fun attach(host: GhosttyTerminalHost) {
        check(this.host == null || this.host === host) { "session is already attached to another host" }
        this.host = host
    }

    public fun detach(host: GhosttyTerminalHost) {
        if (this.host === host) this.host = null
    }

    public fun feedOutput(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val response = withHandle { GhosttyVt.nativeWrite(it, data, offset, length) }
        if (response != null && response.isNotEmpty()) {
            // timg abandons its kitty graphics probe when the DA1 reply does not
            // arrive within a small time budget.
            sendRawInput(response)
        }
        scheduleOutputDispatch()
    }

    private fun scheduleOutputDispatch() {
        outputDispatchPending.set(true)
        if (!outputDispatchScheduled.compareAndSet(false, true)) return
        mainScope.launch {
            try {
                do {
                    outputDispatchPending.set(false)
                    withHandle { drainEvents(it) }
                    host?.onTerminalChanged()
                } while (outputDispatchPending.get())
            } finally {
                outputDispatchScheduled.set(false)
                if (outputDispatchPending.get()) scheduleOutputDispatch()
            }
        }
    }

    private fun drainEvents(handle: Long) {
        val flags = GhosttyVt.nativeTakeEventFlags(handle)
        if (flags == 0) return
        if (flags and GhosttyVt.EVENT_TITLE != 0) {
            title.value = GhosttyVt.nativeGetTitle(handle)?.toString(Charsets.UTF_8)
        }
        if (flags and GhosttyVt.EVENT_BELL != 0) {
            host?.onBell()
            eventListener?.onBell(this)
        }
        if (flags and GhosttyVt.EVENT_CLIPBOARD != 0) {
            val text = GhosttyVt.nativeTakeClipboard(handle)?.toString(Charsets.UTF_8)
            if (!text.isNullOrEmpty()) {
                try {
                    clipboard?.setText(text)
                } catch (e: Exception) {
                    logError(TAG, "clipboard write failed", e)
                }
            }
        }
        if (flags and GhosttyVt.EVENT_PWD != 0) {
            workingDirectory.value = GhosttyVt.nativeGetPwd(handle)
                ?.toString(Charsets.UTF_8)
                ?.let(::pwdToPath)
        }
        if (flags and GhosttyVt.EVENT_NOTIFICATION != 0) {
            while (true) {
                val packed = GhosttyVt.nativeTakeNotification(handle) ?: break
                val titleLength = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN).int
                eventListener?.onNotification(
                    this,
                    String(packed, 4, titleLength, Charsets.UTF_8),
                    String(packed, 4 + titleLength, packed.size - 4 - titleLength, Charsets.UTF_8),
                )
            }
        }
        if (flags and GhosttyVt.EVENT_PROGRESS != 0) {
            val report = GhosttyVt.nativeGetProgress(handle)
            if (report != null) {
                progress.value = when (report[0]) {
                    GhosttyVt.PROGRESS_STATE_SET -> GhosttyProgressState.SET
                    GhosttyVt.PROGRESS_STATE_ERROR -> GhosttyProgressState.ERROR
                    GhosttyVt.PROGRESS_STATE_INDETERMINATE -> GhosttyProgressState.INDETERMINATE
                    GhosttyVt.PROGRESS_STATE_PAUSE -> GhosttyProgressState.PAUSE
                    else -> null
                }?.let { GhosttyProgress(it, report[1].takeIf { percent -> percent >= 0 }) }
            }
        }
        if (flags and GhosttyVt.EVENT_COLORS != 0) {
            backgroundColor.value = GhosttyVt.nativeGetBackgroundColor(handle).takeIf { it != 0 }
        }
    }

    public fun setColorScheme(dark: Boolean) {
        val report = withHandle { GhosttyVt.nativeSetColorScheme(it, dark) }
        if (report != null && report.isNotEmpty()) sendRawInput(report)
    }

    internal fun clipboardHasText(): Boolean = try {
        clipboard?.hasText() ?: false
    } catch (e: Exception) {
        logError(TAG, "clipboard query failed", e)
        false
    }

    internal fun readClipboardForProgram(granted: Boolean): ByteArray? {
        val clipboard = clipboard ?: return null
        if (!granted && !clipboardReadApproved) {
            if (clipboardReadPromptPending.compareAndSet(false, true)) {
                mainScope.launch {
                    val host = host
                    if (host == null) {
                        clipboardReadPromptPending.set(false)
                        return@launch
                    }
                    host.requestClipboardRead { approved ->
                        clipboardReadPromptPending.set(false)
                        if (approved) clipboardReadApproved = true
                    }
                }
            }
            return null
        }
        return try {
            clipboard.getText().orEmpty().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            logError(TAG, "clipboard read failed", e)
            null
        }
    }

    public fun sendFocus(gained: Boolean) {
        if (isFinished) return
        val report = withHandle { GhosttyVt.nativeEncodeFocus(it, gained) }
        if (report != null && report.isNotEmpty()) sendRawInput(report)
    }

    public fun sendTypedInput(data: ByteArray) {
        sendRawInput(sanitizeInput(data))
    }

    public fun sendRawInput(data: ByteArray) {
        val currentTransport = transport ?: return
        try {
            currentTransport.sendInput(data)
        } catch (e: Exception) {
            logError(TAG, "transport send input failed", e)
            if (!isFinished) finish(-1)
        }
    }

    public fun screenText(): String? = withHandle { GhosttyVt.nativeViewportText(it) }
        ?.toString(Charsets.UTF_8)

    public fun selectionText(): String? = withHandle { GhosttyVt.nativeSelectionText(it) }
        ?.toString(Charsets.UTF_8)

    public fun transcriptText(): String? = withHandle { GhosttyVt.nativeTranscriptText(it) }
        ?.toString(Charsets.UTF_8)

    public fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        resizeTerminal(columns, rows, cellWidthPixels, cellHeightPixels)
        scheduleTransportResize()
    }

    // Resizing to a size the terminal is only going to hold for the length of a
    // keyboard animation must not reach the transport: a remote shell redrawing
    // for a size that is already gone leaves its prompt mid-line.
    private fun resizeTerminal(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (columns == this.columns && rows == this.rows &&
            cellWidthPixels == this.cellWidthPixels && cellHeightPixels == this.cellHeightPixels
        ) {
            return
        }
        this.columns = columns
        this.rows = rows
        this.cellWidthPixels = cellWidthPixels
        this.cellHeightPixels = cellHeightPixels
        withHandle { GhosttyVt.nativeResize(it, columns, rows, cellWidthPixels, cellHeightPixels) }
    }

    private fun scheduleTransportResize() {
        if (transport == null) return
        resizeSend?.cancel()
        resizeSend = null
        // zsh erases its end-of-line mark by printing COLUMNS-1 spaces and a
        // carriage return, so a prompt printed before the shell learns the
        // real width leaves the mark on screen.
        if (columns != sentColumns) {
            sendTransportResize()
            return
        }
        resizeSend = mainScope.launch {
            delay(RESIZE_SEND_DEBOUNCE)
            resizeSend = null
            sendTransportResize()
        }
    }

    private fun sendTransportResize() {
        val currentTransport = transport ?: return
        if (columns == sentColumns
            && rows == sentRows
            && cellWidthPixels == sentCellWidthPixels
            && cellHeightPixels == sentCellHeightPixels
        ) {
            return
        }
        sentColumns = columns
        sentRows = rows
        sentCellWidthPixels = cellWidthPixels
        sentCellHeightPixels = cellHeightPixels
        try {
            currentTransport.sendResize(columns, rows, columns * cellWidthPixels, rows * cellHeightPixels)
        } catch (e: Exception) {
            logError(TAG, "transport send resize failed", e)
            if (!isFinished) finish(-1)
        }
    }

    public fun setTheme(theme: GhosttyTheme) {
        selectionBackground = theme.selectionBackground
        selectionForeground = theme.selectionForeground
        withHandle {
            GhosttyVt.nativeSetTheme(
                it,
                theme.foreground?.toUInt()?.toLong() ?: -1L,
                theme.background?.toUInt()?.toLong() ?: -1L,
                theme.cursorColor?.toUInt()?.toLong() ?: -1L,
                LongArray(theme.palette.size) { index ->
                    theme.palette[index]?.toUInt()?.toLong() ?: -1L
                },
            )
            backgroundColor.value = GhosttyVt.nativeGetBackgroundColor(it).takeIf { color -> color != 0 }
        }
        host?.onTerminalChanged()
    }

    public fun finish(exitCode: Int = 0) {
        exitStatus.value = exitCode
    }

    public fun close() {
        try {
            transport?.close()
        } catch (e: Exception) {
            logError(TAG, "transport close failed", e)
        }
        transport = null
        mainScope.launch {
            withHandle {
                GhosttyVt.nativeFree(it)
                handle = 0
                terminal.handle = 0
            }
            mainScope.cancel()
        }
    }

    private fun sanitizeInput(data: ByteArray): ByteArray {
        var i = 0
        while (i < data.size) {
            val byte = data[i].toInt()
            if (byte == 0x0A || (byte == 0x1B && isBracketedPasteMarker(data, i))) {
                break
            }
            i++
        }
        if (i == data.size) return data
        val result = ByteArray(data.size)
        data.copyInto(result, endIndex = i)
        var writePosition = i
        while (i < data.size) {
            val byte = data[i]
            if (byte.toInt() == 0x1B && isBracketedPasteMarker(data, i)) {
                i += 6
                continue
            }
            result[writePosition++] = if (byte.toInt() == 0x0A) {
                0x0D
            } else {
                byte
            }
            i++
        }
        return if (writePosition == result.size) {
            result
        } else {
            result.copyOf(writePosition)
        }
    }

    private fun isBracketedPasteMarker(data: ByteArray, i: Int): Boolean = i + 5 < data.size
            && data[i + 1] == '['.code.toByte()
            && data[i + 2] == '2'.code.toByte()
            && data[i + 3] == '0'.code.toByte()
            && (data[i + 4] == '0'.code.toByte() || data[i + 4] == '1'.code.toByte())
            && data[i + 5] == '~'.code.toByte()

    public companion object {
        private const val TAG = "GhosttyTerminalSession"

        /**
         * Restores a session from a libghostty-vt snapshot (`GHOSTSNP`). The snapshot sets the size, both screens,
         * the scrollback and its limits, the colors, the modes and the cursor defaults; of [options], only
         * [Options.reportedVersion] and [Options.terminfoName] apply. Returns null when this build cannot read the
         * snapshot, for example because the encoder used a newer snapshot format.
         *
         * May be called from any thread, like [feedOutput].
         */
        public fun restore(snapshot: ByteArray, options: Options = Options()): GhosttyTerminalSession? {
            val session = GhosttyTerminalSession(options, snapshot)
            if (session.handle == 0L) {
                session.mainScope.cancel()
                return null
            }
            // Title, directory and colors came with the snapshot.
            session.scheduleOutputDispatch()
            return session
        }
        private val RESIZE_SEND_DEBOUNCE = 100.milliseconds

        /** OSC 7 reports a `file://host/path` URL; anything else is shown as is. */
        private fun pwdToPath(raw: String): String? {
            if (raw.isEmpty()) return null
            if (!raw.startsWith("file://")) {
                return raw
            }
            val path = try {
                URI(raw).path
            } catch (_: URISyntaxException) {
                // Shells that skip percent-encoding send paths with spaces as is.
                raw.substring("file://".length)
                    .let { rest ->
                        rest.indexOf('/').takeIf { it >= 0 }?.let(rest::substring)
                    }
            }
            return path?.takeIf { it.isNotEmpty() }
        }
    }
}
