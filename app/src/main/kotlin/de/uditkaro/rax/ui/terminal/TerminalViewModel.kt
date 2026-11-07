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
import de.uditkaro.rax.rex.RexClient
import de.uditkaro.rax.rex.TerminalStream
import de.uditkaro.rax.rex.WindowView
import de.uditkaro.rax.ui.home.Access
import de.uditkaro.rax.ui.home.Link
import de.uditkaro.rax.ui.home.Need
import de.uditkaro.rax.ui.home.hostLink
import io.github.sagernet.libghostty.GhosttyTerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the stream of the shown pane is doing. */
enum class PaneStatus { Opening, Live, Reconnecting, ElsewhereOwned, Closed, Unavailable }

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

    val host: StateFlow<HostState?> = (live?.rex?.state ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.Eagerly, live?.rex?.state?.value)

    private val link: StateFlow<Link?> = combine(host, graph.tailnet.status, graph.localNetwork.granted) { st, tailnet, local ->
        live?.let { hostLink(app.resources, it.host, st, Access(tailnet, local)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val loaded = MutableStateFlow(0L)
    private val stalled = MutableStateFlow(false)

    /** When the stream last moved forward, on the [SystemClock.elapsedRealtime] clock. */
    @Volatile private var lastProgress = SystemClock.elapsedRealtime()

    val view: StateFlow<PaneView> = combine(_status, _session, link, loaded, stalled) { status, session, link, bytes, stalled ->
        paneView(status, session != null, link, bytes, stalled)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PaneView.Loading(connecting = false, bytes = 0))

    /** The stream is read, and emulators are made and fed, on this one thread, in stream order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val terminalThread = Dispatchers.Default.limitedParallelism(1)

    private var streamJob: Job? = null
    private var input: PaneInput? = null
    private var visible = true
    private val feeding = ThreadLocal<Boolean>()

    // Ownership, changed on the main thread only: the pane this phone should size now, the pane Rex
    // last confirmed as this phone's, and the view revision at that moment.
    private var wanted: String? = null
    private var owned: String? = null
    private var ownedSince = -1L
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
            // Another device took the pane this phone sized: stop sizing it and say so.
            host.collect { st ->
                val pane = owned ?: return@collect
                val view = st?.session(sessionId)?.view ?: return@collect
                if (view.revision <= ownedSince) return@collect
                val owner = view.tabs.flatMap { it.blocks }.firstOrNull { it.id == pane }?.owner
                if (owner != null && owner != st.clientId) {
                    owned = null
                    wanted = null
                    if (_target.value?.blockId == pane) _status.value = PaneStatus.ElsewhereOwned
                }
            }
        }
        viewModelScope.launch {
            // Time spent waiting for the computer does not count against the stream.
            link.distinctUntilChanged { a, b -> (a is Link.Online) == (b is Link.Online) }.collect {
                if (it is Link.Online) lastProgress = SystemClock.elapsedRealtime()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val waiting = _status.value == PaneStatus.Opening || _status.value == PaneStatus.Reconnecting
                stalled.value = waiting && link.value is Link.Online &&
                    SystemClock.elapsedRealtime() - lastProgress > STALL_MS
            }
        }
    }

    private fun paneView(status: PaneStatus, hasScreen: Boolean, link: Link?, bytes: Long, stalled: Boolean): PaneView {
        val r = getApplication<Application>().resources
        return when (status) {
            PaneStatus.Unavailable -> PaneView.Unavailable
            PaneStatus.Closed -> PaneView.Closed
            PaneStatus.ElsewhereOwned -> PaneView.ElsewhereOwned
            PaneStatus.Opening, PaneStatus.Live, PaneStatus.Reconnecting -> when (link) {
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

    fun window(st: HostState?, windowId: String? = _target.value?.windowId): WindowView? =
        st?.session(sessionId)?.view?.tabs?.firstOrNull { it.id == windowId }

    fun show(target: PaneTarget, restart: Boolean = false) {
        val rex = live?.rex ?: return
        if (!restart && _target.value == target && streamJob?.isActive == true) return
        want(null)
        streamJob?.cancel()
        input?.close()
        val old = _session.value
        if (old != null && _target.value?.blockId != target.blockId) {
            // Another pane's screen must not show while this one loads.
            _session.value = null
            old.close()
        }
        _target.value = target
        _status.value = PaneStatus.Opening
        loaded.value = 0
        stalled.value = false
        lastProgress = SystemClock.elapsedRealtime()
        val paneInput = PaneInput(rex, sessionId, target.blockId, viewModelScope)
        input = paneInput
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
            is TerminalStream.Event.Started -> {
                val ctx = getApplication<Application>()
                val snapshot = e.snapshot
                val restored = snapshot?.let { GhosttyTerminalSession.restore(ctx, it, options()) }
                if (snapshot != null && restored == null) {
                    Log.w(TAG, "snapshot did not restore, ${snapshot.size} bytes; redrawing from the screen")
                }
                val session = restored ?: GhosttyTerminalSession(ctx, options())
                session.transport = transport(target, paneInput)
                try {
                    withContext(Dispatchers.Main) {
                        val old = _session.value
                        _session.value = session
                        old?.close()
                    }
                } catch (c: CancellationException) {
                    // Another pane was picked before this one could show.
                    session.close()
                    throw c
                }
                if (restored == null) redraw(session, target)
                loaded.value = 0
                _status.value = PaneStatus.Live
            }
            is TerminalStream.Event.Output -> feed(e.bytes)
            is TerminalStream.Event.Resize, is TerminalStream.Event.Exited -> Unit
            TerminalStream.Event.Interrupted -> _status.value = PaneStatus.Reconnecting
            TerminalStream.Event.Resumed -> if (_status.value == PaneStatus.Reconnecting) _status.value = PaneStatus.Live
            TerminalStream.Event.Closed -> _status.value = PaneStatus.Closed
        }
    }

    private fun feed(bytes: ByteArray) {
        val session = _session.value ?: return
        feeding.set(true)
        try {
            session.feedOutput(bytes)
        } finally {
            feeding.set(false)
        }
    }

    /** The screen as escape sequences, for a snapshot this build cannot read. */
    private suspend fun redraw(session: GhosttyTerminalSession, target: PaneTarget) {
        val rex = live?.rex ?: return
        val reply = runCatching {
            rex.client.callBlock(sessionId, target.blockId, "format", buildJsonObject {
                put("format", "vt")
                put("extra_cursor", true)
                put("extra_modes", true)
                put("extra_palette", true)
                put("extra_style", true)
                put("extra_scrolling_region", true)
                put("extra_tabstops", true)
                put("extra_charsets", true)
                put("extra_keyboard", true)
                put("extra_kitty_keyboard", true)
                put("extra_pwd", true)
                put("max_bytes", 960 * 1024)
            })
        }.getOrNull() ?: return
        val content = reply["content"]?.jsonPrimitive?.contentOrNull ?: return
        if (_session.value === session) feed(content.toByteArray())
    }

    private fun options() = GhosttyTerminalSession.Options(reportedVersion = "Rax", terminfoName = "xterm-256color")

    private fun transport(target: PaneTarget, paneInput: PaneInput) = object : GhosttyTerminalSession.Transport {
        override fun sendInput(data: ByteArray) {
            // The emulator answers terminal queries while it reads output; Rex's own terminal already
            // answers them, so only what the user typed goes back.
            if (feeding.get() == true) return
            paneInput.send(data)
        }

        override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
            paneInput.resize(columns, rows, widthPixels / columns.coerceAtLeast(1), heightPixels / rows.coerceAtLeast(1))
            viewModelScope.launch(Dispatchers.Main) {
                if (visible && _target.value == target && _status.value != PaneStatus.ElsewhereOwned) want(target.blockId)
            }
        }

        override fun close() = Unit
    }

    /** Reconnects to the computer if needed and opens the pane's stream again. */
    fun retry() {
        val l = live ?: return
        if (l.host.tailnet) graph.scope.launch { graph.tailnet.start() }
        graph.connections.retry(hostId)
        val target = _target.value ?: return
        if (_status.value != PaneStatus.Live) show(target, restart = true)
    }

    /** Asks for the pane back from the device that took it. */
    fun useHere() {
        val target = _target.value ?: return
        _status.value = PaneStatus.Live
        want(target.blockId)
    }

    fun onVisible() {
        visible = true
        val target = _target.value ?: return
        if (_status.value == PaneStatus.Live) want(target.blockId)
    }

    fun onHidden() {
        visible = false
        want(null)
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
        while (true) {
            val want = wanted
            val have = owned
            if (want == have) return
            // While the connection is down, wait for it: when it resumes, Rex gives this phone back
            // the panes it owned, so a release that could not go out must still be sent.
            withTimeoutOrNull(RESUME_WINDOW_MS) { rex.client.state.first { it is RexClient.State.Connected } } ?: return
            if (have != null) {
                val released = runCatching { rex.release(sessionId, have) }
                if (released.isSuccess || rex.client.state.value is RexClient.State.Connected) owned = null
            } else if (want != null) {
                val revision = rex.state.value.session(sessionId)?.view?.revision ?: -1
                if (runCatching { rex.take(sessionId, want) }.isFailure) return
                owned = want
                ownedSince = revision
            }
        }
    }

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

        /** How long a pane may load or reconnect with nothing arriving before Rax calls it failed. */
        const val STALL_MS = 20_000L
    }
}
