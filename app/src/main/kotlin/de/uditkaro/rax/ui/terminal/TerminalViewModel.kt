package de.uditkaro.rax.ui.terminal

import android.app.Application
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uditkaro.rax.R
import de.uditkaro.rax.connection.LiveHost
import de.uditkaro.rax.graph
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.rex.PaneInput
import de.uditkaro.rax.rex.PaneTheme
import de.uditkaro.rax.rex.RexClient
import de.uditkaro.rax.rex.RexException
import de.uditkaro.rax.rex.RexHost
import de.uditkaro.rax.rex.TerminalStream
import de.uditkaro.rax.rex.WindowView
import de.uditkaro.rax.ui.home.Link
import de.uditkaro.rax.ui.home.Need
import de.uditkaro.rax.ui.home.TabRow
import de.uditkaro.rax.ui.home.appName
import de.uditkaro.rax.ui.home.hostLink
import de.uditkaro.rax.ui.home.shortPath
import de.uditkaro.rax.ui.home.tabRow
import de.uditkaro.rax.ui.showActionFailed
import io.github.xchacha20_poly1305.libghostty.GhosttyTerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the stream of the shown pane is doing. Failed means the stream ended without a screen to show. */
enum class PaneStatus { Opening, Live, Reconnecting, Failed, Closed, Unavailable }

/** What the user can do about a [PaneView.Failed]. */
enum class PaneAction { Retry, SignIn, AllowLocalNetwork }

/** What the terminal area shows. */
sealed interface PaneView {
    /**
     * Nothing to show yet. [connecting] is true while Rax waits for the computer. [bytes] counts the
     * part of the screen that has arrived.
     */
    data class Loading(val connecting: Boolean, val bytes: Long) : PaneView
    data object Live : PaneView
    /** The terminal keeps the last screen while the connection comes back. */
    data object Reconnecting : PaneView
    data class Failed(val message: String, val action: PaneAction?) : PaneView
    data object ElsewhereOwned : PaneView
    data object Closed : PaneView
    data object Unavailable : PaneView
}

data class PaneTarget(val windowId: String, val blockId: String)

/** What the screen shows around the terminal: the shown tab, its panes and the session. */
data class PaneHeader(
    val sessionName: String,
    /** Null until the tab's layout arrives, and after the tab closes. */
    val tab: TabRow?,
    /** The tab's title, which stays after the tab closes. Empty when nothing names it yet. */
    val title: String,
    val subtitle: String,
    val panes: List<PaneChip>,
)

/**
 * A pane of the shown tab. [label] is the name the user gave it, if any. [shown] is what the pane switcher
 * calls it, which tells apart panes that run the same program.
 */
data class PaneChip(val id: String, val name: String, val label: String?, val waiting: Boolean, val shown: String = name) {
    /** What the pane is called. */
    val title: String get() = label?.takeIf { it.isNotBlank() } ?: name
}

/**
 * Sends the pane's stream to the terminal emulator and the user's keys to Rex. When the terminal
 * is visible, Rax owns the pane and sets its size. When it's hidden, Rax releases the pane, and the
 * desktop sets the size again.
 */
class TerminalViewModel(
    app: Application,
    val hostId: String,
    val sessionId: String,
    windowId: String,
    blockId: String?,
) : AndroidViewModel(app) {

    private val graph = app.graph
    val live: LiveHost? = graph.connections.get(hostId)

    private val _target = MutableStateFlow<PaneTarget?>(null)
    val target: StateFlow<PaneTarget?> = _target.asStateFlow()

    private val _session = MutableStateFlow<GhosttyTerminalSession?>(null)
    val session: StateFlow<GhosttyTerminalSession?> = _session.asStateFlow()

    private val _status = MutableStateFlow(PaneStatus.Opening)
    val status: StateFlow<PaneStatus> = _status.asStateFlow()

    /** Another device took the shown pane. Main thread only; Use here or another pane ends it. */
    private val elsewhere = MutableStateFlow(false)

    val host: StateFlow<HostState?> = (live?.rex?.state ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.Eagerly, live?.rex?.state?.value)

    /** The last header that had its tab, so the top bar keeps the tab's name after the tab closes. */
    private var lastTabHeader: Pair<String, PaneHeader>? = null

    /** Only what the screen shows of the host, so events elsewhere on the host do not redraw it. */
    val header: StateFlow<PaneHeader> = combine(host, _target) { st, target -> headerOf(st, target) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, headerOf(host.value, _target.value))

    private val link: StateFlow<Link?> = combine(host, graph.access) { st, access ->
        live?.let { hostLink(app.resources, it.host, st, access) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val loaded = MutableStateFlow(0L)
    private val stalled = MutableStateFlow(false)

    /** When the stream last moved forward, on the [SystemClock.elapsedRealtime] clock. */
    @Volatile private var lastProgress = SystemClock.elapsedRealtime()

    val view: StateFlow<PaneView> = combine(combine(_status, elsewhere) { s, e -> s to e }, _session, link, loaded, stalled) {
            (status, takenElsewhere), session, link, bytes, stalled ->
        paneView(status, takenElsewhere, session != null, link, bytes, stalled)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PaneView.Loading(connecting = false, bytes = 0))

    /** The stream is read, and emulators are made and fed, on this one thread, in stream order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val terminalThread = Dispatchers.Default.limitedParallelism(1)

    private var streamJob: Job? = null
    private var input: PaneInput? = null
    @Volatile private var theme: PaneTheme? = null
    private var visible = true

    /** Ends the stream once the screen has been out of sight for [HIDDEN_STREAM_MS]. */
    private var pauseJob: Job? = null
    /** The stream ended because the screen was out of sight, so it starts again when the screen shows. */
    private var paused = false

    private class Grid(val columns: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int)

    /**
     * Output that arrived before this moment, on the [System.nanoTime] clock, is already in the screen Rex drew for
     * the emulator. Read and written on [terminalThread].
     */
    private var drawnUntil = Long.MIN_VALUE

    // Ownership, changed on the main thread only: the pane this phone should size now, the pane Rex
    // last confirmed as this phone's, the view revision at that moment, and the client that owns it.
    private var wanted: String? = null
    private var owned: String? = null
    private var ownedSince = -1L
    private var ownedBy: String? = null
    private var syncing = false

    init {
        val initial = blockId ?: host.value?.session(sessionId)?.view?.tabs?.firstOrNull { it.id == windowId }?.primaryBlockId
        if (live == null) {
            _status.value = PaneStatus.Unavailable
        } else if (initial != null) {
            show(PaneTarget(windowId, initial))
        } else {
            // The tab's layout has not arrived yet; open its first pane once it does.
            viewModelScope.launch {
                live.rex.state.collect { st ->
                    if (_target.value != null) return@collect
                    val window = st.session(sessionId)?.view?.tabs?.firstOrNull { it.id == windowId } ?: return@collect
                    window.primaryBlockId?.let { show(PaneTarget(windowId, it)) }
                }
            }
        }
        viewModelScope.launch {
            // A pane that closes under us moves to another pane of the tab, or another tab.
            combine(host, _status) { st, status -> st to status }.collect { (st, status) ->
                if (status != PaneStatus.Closed || st == null) return@collect
                val view = st.session(sessionId)?.view ?: return@collect
                val current = _target.value ?: return@collect
                // The view can still list the closed pane for a moment, so it is left out here.
                fun panesLeft(w: WindowView) = w.blocks.filter { it.isTerminal }.mapNotNull { it.id }.filter { it != current.blockId }
                val window = view.tabs.firstOrNull { it.id == current.windowId }?.takeIf { panesLeft(it).isNotEmpty() }
                    ?: view.tabs.firstOrNull { it.id != current.windowId && panesLeft(it).isNotEmpty() }
                    ?: return@collect
                val next = window.primaryBlockId?.takeIf { it != current.blockId } ?: panesLeft(window).first()
                show(PaneTarget(window.id!!, next))
            }
        }
        viewModelScope.launch {
            val rex = live?.rex ?: return@launch
            host.collect { st ->
                val pane = owned ?: return@collect
                val clientId = rex.connectedClientId() ?: return@collect
                if (clientId != ownedBy) {
                    // A new client owns nothing: Rex passed this phone's panes on when the old one left.
                    owned = null
                    ownedSince = -1
                    ownedBy = null
                    if (visible && _target.value?.blockId == pane && _status.value == PaneStatus.Live && !elsewhere.value) want(pane)
                    return@collect
                }
                // Another device took the pane this phone sized: stop sizing it and say so.
                val view = st?.session(sessionId)?.view ?: return@collect
                if (view.revision <= ownedSince) return@collect
                val owner = view.tabs.flatMap { it.blocks }.firstOrNull { it.id == pane }?.owner
                if (owner != null && owner != clientId) {
                    owned = null
                    ownedBy = null
                    wanted = null
                    if (_target.value?.blockId == pane) elsewhere.value = true
                }
            }
        }
        viewModelScope.launch {
            // Time spent waiting for the computer does not count against the stream.
            val online = link.map { it is Link.Online }.distinctUntilChanged().onEach { if (it) lastProgress = SystemClock.elapsedRealtime() }
            combine(_status, online) { status, isOnline -> isOnline && (status == PaneStatus.Opening || status == PaneStatus.Reconnecting) }
                .distinctUntilChanged()
                .collectLatest { waiting ->
                    if (!waiting) {
                        stalled.value = false
                        return@collectLatest
                    }
                    while (true) {
                        val left = lastProgress + STALL_MS - SystemClock.elapsedRealtime()
                        stalled.value = left <= 0
                        // Once stalled, look again each second, so the pane comes back as soon as anything arrives.
                        delay(if (left > 0) left else 1_000)
                    }
                }
        }
    }

    private fun paneView(status: PaneStatus, takenElsewhere: Boolean, hasScreen: Boolean, link: Link?, bytes: Long, stalled: Boolean): PaneView {
        val r = getApplication<Application>().resources
        return when {
            status == PaneStatus.Unavailable -> PaneView.Unavailable
            status == PaneStatus.Closed -> PaneView.Closed
            takenElsewhere -> PaneView.ElsewhereOwned
            else -> when (link) {
                is Link.Offline -> PaneView.Failed(link.message, PaneAction.Retry.takeIf { link.retryable })
                is Link.Blocked -> when (link.need) {
                    Need.TailscaleSignIn -> PaneView.Failed(r.getString(R.string.link_needs_tailnet), PaneAction.SignIn)
                    Need.LocalNetwork -> PaneView.Failed(r.getString(R.string.link_needs_local_network), PaneAction.AllowLocalNetwork)
                }
                null, Link.Connecting -> if (hasScreen) PaneView.Reconnecting else PaneView.Loading(connecting = true, bytes = 0)
                is Link.Online -> when {
                    status == PaneStatus.Live && hasScreen -> PaneView.Live
                    status == PaneStatus.Failed -> PaneView.Failed(r.getString(R.string.terminal_failed), PaneAction.Retry)
                    stalled -> PaneView.Failed(r.getString(if (hasScreen) R.string.terminal_lost else R.string.terminal_failed), PaneAction.Retry)
                    hasScreen -> PaneView.Reconnecting
                    else -> PaneView.Loading(connecting = false, bytes = bytes)
                }
            }
        }
    }

    private fun headerOf(st: HostState?, target: PaneTarget?): PaneHeader {
        val session = st?.session(sessionId)
        val sessionName = session?.info?.label.orEmpty()
        val window = window(st, target?.windowId)
        if (window == null && target != null) {
            lastTabHeader?.takeIf { it.first == target.windowId }?.let { (_, last) -> return last.copy(tab = null, panes = emptyList()) }
        }
        val blocks = st?.blocks.orEmpty()
        val facts = target?.blockId?.let { blocks[it] }
        val tab = window?.let { tabRow(getApplication<Application>().resources, hostId, sessionId, sessionName, it, 0, blocks, target?.blockId) }
        val chips = window?.blocks.orEmpty().filter { it.isTerminal && it.id != null }.map { block ->
            val f = blocks[block.id]
            PaneChip(
                id = block.id!!,
                name = f?.status?.values?.firstNotNullOfOrNull { it.effectiveApp ?: it.app }?.let(::appName) ?: f?.programName ?: "…",
                label = block.userLabel,
                waiting = f?.waiting != null,
            )
        }
        val header = PaneHeader(
            sessionName = sessionName,
            tab = tab,
            title = tab?.title ?: sessionName,
            // The title shows the session's name while there is no tab.
            subtitle = listOfNotNull(session?.info?.label?.takeIf { tab != null }, shortPath(facts?.cwd, facts?.process?.home))
                .joinToString("  ·  "),
            panes = distinctChips(chips, chips.map { blocks[it.id]?.cwd?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null } }),
        )
        if (tab != null && target != null) lastTabHeader = target.windowId to header
        return header
    }

    fun window(st: HostState?, windowId: String? = _target.value?.windowId): WindowView? =
        st?.session(sessionId)?.view?.tabs?.firstOrNull { it.id == windowId }

    fun show(target: PaneTarget, restart: Boolean = false) {
        val rex = live?.rex ?: return
        if (!restart && _target.value == target && streamJob?.isActive == true) return
        val samePane = _target.value?.blockId == target.blockId
        // Opening the same pane again keeps it this phone's, so its size does not go to the desktop and back.
        if (!samePane) {
            want(null)
            elsewhere.value = false
        }
        streamJob?.cancel()
        val oldInput = input
        oldInput?.close()
        val old = _session.value
        if (old != null && !samePane) {
            // Another pane's screen must not show while this one loads.
            _session.value = null
            old.close()
        }
        _target.value = target
        _status.value = PaneStatus.Opening
        loaded.value = 0
        stalled.value = false
        lastProgress = SystemClock.elapsedRealtime()
        // Keys still going out to this pane, such as the rest of a paste, go before new ones.
        val paneInput = PaneInput(rex, sessionId, target.blockId, viewModelScope, before = oldInput.takeIf { samePane }, onDropped = ::inputDropped)
        input = paneInput
        theme?.let(paneInput::setTheme)
        // Keys typed on the last screen while the stream comes back go out on the new connection.
        if (samePane) old?.transport = transport(target, paneInput)
        streamJob = viewModelScope.launch(terminalThread) {
            TerminalStream(rex, sessionId, target.blockId).events().collect { e -> onStreamEvent(e, target, paneInput) }
        }
    }

    private suspend fun onStreamEvent(e: TerminalStream.Event, target: PaneTarget, paneInput: PaneInput) {
        if (e !is TerminalStream.Event.Interrupted && e !is TerminalStream.Event.Closed) {
            lastProgress = SystemClock.elapsedRealtime()
        }
        when (e) {
            is TerminalStream.Event.Loading -> loaded.value = e.bytes
            is TerminalStream.Event.Started -> start(e.snapshot, target, paneInput)
            is TerminalStream.Event.Output -> if (e.receivedAt >= drawnUntil) feed(e.bytes)
            is TerminalStream.Event.Resize, is TerminalStream.Event.Exited -> Unit
            TerminalStream.Event.Interrupted -> {
                // A stream that drops after a quiet time gets the whole stall time to come back.
                if (_status.value == PaneStatus.Live) lastProgress = SystemClock.elapsedRealtime()
                _status.value = PaneStatus.Reconnecting
            }
            TerminalStream.Event.Resumed -> if (_status.value == PaneStatus.Reconnecting) {
                _status.value = PaneStatus.Live
                // The screen may have released the pane while the stream was down.
                viewModelScope.launch(Dispatchers.Main) {
                    if (visible && _target.value == target && !elsewhere.value) want(target.blockId)
                }
            }
            TerminalStream.Event.Closed -> _status.value = PaneStatus.Closed
        }
    }

    private suspend fun start(snapshot: ByteArray?, target: PaneTarget, paneInput: PaneInput) {
        val restored = snapshot?.let { GhosttyTerminalSession.restore(it, options()) }
        if (snapshot != null && restored == null) {
            Log.w(TAG, "snapshot did not restore, ${snapshot.size} bytes; redrawing from the screen")
        }
        drawnUntil = Long.MIN_VALUE
        val session = restored ?: redrawn(target) ?: run {
            // Without Rex's screen the pane would stay blank until the program draws again.
            _status.value = PaneStatus.Failed
            currentCoroutineContext().job.cancel()
            return
        }
        var swapped = false
        try {
            // Rewrapping a long scrollback to the phone's grid takes a while; doing it here, before the
            // screen shows, keeps it off the main thread.
            grid?.let { session.resize(it.columns, it.rows, it.cellWidth, it.cellHeight) }
            session.transport = transport(target, paneInput)
            // A new data connection can come from a new client, which starts with Rex's colours.
            theme?.let(paneInput::setTheme)
            withContext(Dispatchers.Main) {
                val old = _session.value
                _session.value = session
                old?.close()
                swapped = true
            }
        } catch (c: CancellationException) {
            // Another pane was picked before this one could show.
            if (!swapped) session.close()
            throw c
        }
        loaded.value = 0
        _status.value = PaneStatus.Live
    }

    /**
     * An emulator with the screen Rex draws as escape sequences, for a snapshot this build cannot read. It starts at
     * the pane's size on Rex, so rows and the cursor land where Rex has them. Null if Rex cannot send the screen.
     */
    private suspend fun redrawn(target: PaneTarget): GhosttyTerminalSession? {
        val rex = live?.rex ?: return null
        var screen: ScreenDump? = null
        for (attempt in 1..REDRAW_TRIES) {
            screen = try {
                readScreenDump(
                    call = { method, args -> rex.client.callBlock(sessionId, target.blockId, method, args) },
                    onProgress = { lastProgress = SystemClock.elapsedRealtime() },
                ) ?: return null
            } catch (e: RexException) {
                // The connection dropped, or a new one has not attached the session yet.
                rex.state.first { it.canCall(sessionId) }
                continue
            }
            break
        }
        if (screen == null) return null
        val session = GhosttyTerminalSession(options(screen.columns, screen.rows))
        screen.parts.forEach { session.feedOutput(it) }
        drawnUntil = screen.drawnAt
        return session
    }

    private fun feed(bytes: ByteArray) {
        _session.value?.feedOutput(bytes)
    }

    fun setTheme(theme: PaneTheme) {
        this.theme = theme
        input?.setTheme(theme)
    }

    // Rex's own terminal answers the program's queries and sends it the reports it asks for, so the
    // emulator here only passes on what the user types.
    private fun options(columns: Int = 80, rows: Int = 24) = GhosttyTerminalSession.Options(
        columns = columns,
        rows = rows,
        reportedVersion = "Rax",
        terminfoName = "xterm-256color",
        repliesToProgram = false,
    )

    private fun transport(target: PaneTarget, paneInput: PaneInput) = object : GhosttyTerminalSession.Transport {
        override fun sendInput(data: ByteArray) = paneInput.send(data)

        override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
            val cellWidth = widthPixels / columns.coerceAtLeast(1)
            val cellHeight = heightPixels / rows.coerceAtLeast(1)
            grid = Grid(columns, rows, cellWidth, cellHeight)
            paneInput.resize(columns, rows, cellWidth, cellHeight)
            viewModelScope.launch(Dispatchers.Main) {
                if (visible && _target.value == target && !elsewhere.value) want(target.blockId)
            }
        }

        override fun close() = Unit
    }

    private val _working = MutableStateFlow(false)

    /** An action that opens a pane, such as a split, waits for Rex. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /**
     * Runs an action that makes a pane, one at a time, and shows the pane it returns. A failure shows
     * as a toast.
     */
    fun openPane(make: suspend (RexHost) -> PaneTarget?) {
        val rex = live?.rex ?: return
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                make(rex)?.let(::show)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showActionFailed(getApplication(), e)
            } finally {
                _working.value = false
            }
        }
    }

    /** Shows [blockId] of tab [windowId], or the tab's main pane. */
    fun showTab(windowId: String, blockId: String?) {
        val block = blockId ?: window(host.value, windowId)?.primaryBlockId ?: return
        show(PaneTarget(windowId, block))
    }

    private fun inputDropped() {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(getApplication(), R.string.terminal_input_dropped, Toast.LENGTH_SHORT).show()
        }
    }

    /** Reconnects to the computer if needed and opens the pane's stream again. */
    fun retry() {
        val l = live ?: return
        if (l.host.tailnet) graph.scope.launch { graph.tailnet.retry() }
        graph.connections.retry(hostId)
        val target = _target.value ?: return
        if (_status.value != PaneStatus.Live) show(target, restart = true)
    }

    /** Asks for the pane back from the device that took it. */
    fun useHere() {
        val target = _target.value ?: return
        elsewhere.value = false
        want(target.blockId)
    }

    fun onVisible() {
        visible = true
        pauseJob?.cancel()
        pauseJob = null
        val target = _target.value ?: return
        if (paused) {
            paused = false
            show(target, restart = true)
            return
        }
        if (_status.value == PaneStatus.Live && !elsewhere.value) want(target.blockId)
    }

    fun onHidden() {
        visible = false
        want(null)
        // Output that nobody sees costs data and battery. Alerts need only the control connection.
        pauseJob?.cancel()
        pauseJob = viewModelScope.launch {
            delay(HIDDEN_STREAM_MS)
            if (streamJob?.isActive == true) {
                streamJob?.cancel()
                paused = true
            }
        }
    }

    /**
     * Sets the pane this phone should own and brings Rex in line. The work runs in the app's scope,
     * so a release still goes out after this screen is gone.
     */
    private fun want(blockId: String?) {
        wanted = blockId
        if (syncing) return
        syncing = true
        graph.scope.launch(Dispatchers.Main) {
            try {
                syncOwnership()
            } finally {
                syncing = false
            }
        }
    }

    private suspend fun syncOwnership() {
        val rex = live?.rex ?: return
        // A pane whose take may have reached Rex, though its answer was lost.
        var unsure: String? = null
        while (wanted != owned || unsure != null) {
            // While the connection is down, or a new one has not attached the session yet, wait for it:
            // when it resumes, Rex gives this phone back the panes it owned, so a release that could
            // not go out must still be sent.
            withTimeoutOrNull(RESUME_WINDOW_MS) { rex.state.first { it.canCall(sessionId) } } ?: return
            val want = wanted
            val have = owned
            val release = have?.takeIf { it != want } ?: unsure?.takeIf { it != want }
            if (release != null) {
                val failure = runCatching { rex.release(sessionId, release) }.exceptionOrNull()
                if (failure?.lostReply() == true) {
                    delay(RETRY_MS)
                    continue
                }
                // Done, or refused by Rex, which a second try does not change.
                if (release == have) {
                    owned = null
                    ownedBy = null
                }
                if (release == unsure) unsure = null
            } else if (want != null && have == null) {
                val revision = rex.state.value.session(sessionId)?.view?.revision ?: -1
                val clientId = rex.connectedClientId()
                val failure = runCatching { rex.take(sessionId, want) }.exceptionOrNull()
                if (failure?.lostReply() == true) {
                    // Taking a pane this client already owns changes nothing, so the take goes out again.
                    unsure = want
                    delay(RETRY_MS)
                    continue
                }
                unsure = null
                if (failure != null) return
                owned = want
                ownedSince = revision
                ownedBy = clientId
            } else {
                unsure = null
            }
        }
    }

    private fun Throwable.lostReply() = this is RexException && replyLost

    private fun RexHost.connectedClientId(): String? = (client.state.value as? RexClient.State.Connected)?.welcome?.clientId

    override fun onCleared() {
        want(null)
        streamJob?.cancel()
        input?.close()
        val session = _session.value
        _session.value = null
        session?.close()
    }

    private companion object {
        const val TAG = "TerminalViewModel"

        /**
         * The grid a terminal last sent to Rex, so the next emulator can start at that size, also on the first pane
         * a screen opens.
         */
        @Volatile var grid: Grid? = null

        /** A little longer than Rex keeps a dropped client. */
        const val RESUME_WINDOW_MS = 90_000L

        /** How long to wait before an ownership change whose answer was lost goes out again. */
        const val RETRY_MS = 1_000L

        /** How often Rax reads a pane's screen again when the reads fail in a way that can pass. */
        const val REDRAW_TRIES = 3

        /** How long a pane may load or reconnect with nothing arriving before Rax calls it failed. */
        const val STALL_MS = 20_000L

        /** How long the stream of a screen that is out of sight stays open, like the control connection's grace period. */
        const val HIDDEN_STREAM_MS = 30_000L
    }
}

/**
 * Names [chips] so the pane switcher tells them apart: a pane that runs the same program as another gets its
 * directory from [dirs] when that is its own, or else its number among them.
 */
internal fun distinctChips(chips: List<PaneChip>, dirs: List<String?>): List<PaneChip> = chips.mapIndexed { i, chip ->
    val same = chips.indices.filter { chips[it].title == chip.title }
    val dir = dirs[i]
    val shown = when {
        same.size == 1 -> chip.title
        dir != null && same.count { dirs[it] == dir } == 1 -> "${chip.title} · $dir"
        else -> "${chip.title} ${same.indexOf(i) + 1}"
    }
    chip.copy(shown = shown)
}
