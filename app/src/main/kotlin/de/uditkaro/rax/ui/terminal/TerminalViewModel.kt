package de.uditkaro.rax.ui.terminal

import android.app.Application
import android.os.SystemClock
import android.util.Log
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
import de.uditkaro.rax.ui.home.Access
import de.uditkaro.rax.ui.home.Link
import de.uditkaro.rax.ui.home.Need
import de.uditkaro.rax.ui.home.TabRow
import de.uditkaro.rax.ui.home.appName
import de.uditkaro.rax.ui.home.hostLink
import de.uditkaro.rax.ui.home.shortPath
import de.uditkaro.rax.ui.home.tabRow
import io.github.xchacha20_poly1305.libghostty.GhosttyTerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

/** What the stream of the shown pane is doing. */
enum class PaneStatus { Opening, Live, Reconnecting, Closed, Unavailable }

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
    data class Failed(val message: String, val canRetry: Boolean) : PaneView
    data object ElsewhereOwned : PaneView
    data object Closed : PaneView
    data object Unavailable : PaneView
}

data class PaneTarget(val windowId: String, val blockId: String)

/** What the screen shows around the terminal: the shown tab, its panes and the session. */
data class PaneHeader(
    val sessionName: String,
    /** Null until the tab's layout arrives. */
    val tab: TabRow?,
    val subtitle: String,
    val panes: List<PaneChip>,
)

/** A pane of the shown tab. [label] is the name the user gave it, if any. */
data class PaneChip(val id: String, val name: String, val label: String?, val waiting: Boolean)

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

    /** Only what the screen shows of the host, so events elsewhere on the host do not redraw it. */
    val header: StateFlow<PaneHeader> = combine(host, _target) { st, target -> headerOf(st, target) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, headerOf(host.value, _target.value))

    private val link: StateFlow<Link?> = combine(host, graph.tailnet.status, graph.localNetwork.granted, graph.localNetwork.names) { st, tailnet, local, names ->
        live?.let { hostLink(app.resources, it.host, st, Access(tailnet, local, names)) }
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

    /** The grid the terminal last sent to Rex, so the next emulator can start at that size. */
    @Volatile private var grid: Grid? = null

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
                val window = view.tabs.firstOrNull { it.id == current.windowId } ?: view.tabs.firstOrNull() ?: return@collect
                window.primaryBlockId?.takeIf { it != current.blockId }?.let { show(PaneTarget(window.id!!, it)) }
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
                is Link.Offline -> PaneView.Failed(link.message, link.retryable)
                is Link.Blocked -> PaneView.Failed(
                    r.getString(if (link.need == Need.TailscaleSignIn) R.string.terminal_needs_tailnet else R.string.terminal_needs_local_network),
                    canRetry = false,
                )
                null, Link.Connecting -> if (hasScreen) PaneView.Reconnecting else PaneView.Loading(connecting = true, bytes = 0)
                is Link.Online -> when {
                    status == PaneStatus.Live && hasScreen -> PaneView.Live
                    stalled -> PaneView.Failed(r.getString(if (hasScreen) R.string.terminal_lost else R.string.terminal_failed), canRetry = true)
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
        val blocks = st?.blocks.orEmpty()
        val facts = target?.blockId?.let { blocks[it] }
        return PaneHeader(
            sessionName = sessionName,
            tab = window?.let { tabRow(getApplication<Application>().resources, hostId, sessionId, sessionName, it, 0, blocks, target?.blockId) },
            subtitle = listOfNotNull(session?.info?.label, shortPath(facts?.cwd, facts?.process?.home)).joinToString("  ·  "),
            panes = window?.blocks.orEmpty().filter { it.isTerminal && it.id != null }.map { block ->
                val f = blocks[block.id]
                PaneChip(
                    id = block.id!!,
                    name = f?.status?.values?.firstNotNullOfOrNull { it.effectiveApp ?: it.app }?.let(::appName) ?: f?.programName ?: "…",
                    label = block.label,
                    waiting = f?.waiting != null,
                )
            },
        )
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
        val paneInput = PaneInput(rex, sessionId, target.blockId, viewModelScope, before = oldInput.takeIf { samePane })
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
        val session = restored ?: redrawn(target)
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
     * the pane's size on Rex, so rows and the cursor land where Rex has them. Blank if Rex cannot send the screen.
     */
    private suspend fun redrawn(target: PaneTarget): GhosttyTerminalSession {
        val client = live?.rex?.client ?: return GhosttyTerminalSession(options())
        val screen = readScreenDump(
            call = { method, args -> client.callBlock(sessionId, target.blockId, method, args) },
            onProgress = { lastProgress = SystemClock.elapsedRealtime() },
        ) ?: return GhosttyTerminalSession(options())
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

        /** A little longer than Rex keeps a dropped client. */
        const val RESUME_WINDOW_MS = 90_000L

        /** How long to wait before an ownership change whose answer was lost goes out again. */
        const val RETRY_MS = 1_000L

        /** How long a pane may load or reconnect with nothing arriving before Rax calls it failed. */
        const val STALL_MS = 20_000L

        /** How long the stream of a screen that is out of sight stays open, like the control connection's grace period. */
        const val HIDDEN_STREAM_MS = 30_000L
    }
}
