package de.uditkaro.rax.ui.terminal

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uditkaro.rax.connection.LiveHost
import de.uditkaro.rax.graph
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.rex.PaneInput
import de.uditkaro.rax.rex.RexClient
import de.uditkaro.rax.rex.TerminalStream
import de.uditkaro.rax.rex.WindowView
import io.github.sagernet.libghostty.GhosttyTerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    }

    fun window(st: HostState?, windowId: String? = _target.value?.windowId): WindowView? =
        st?.session(sessionId)?.view?.tabs?.firstOrNull { it.id == windowId }

    fun show(target: PaneTarget) {
        val rex = live?.rex ?: return
        if (_target.value == target && streamJob?.isActive == true) return
        want(null)
        streamJob?.cancel()
        input?.close()
        _target.value = target
        _status.value = PaneStatus.Opening
        val paneInput = PaneInput(rex, sessionId, target.blockId, viewModelScope)
        input = paneInput
        streamJob = viewModelScope.launch(terminalThread) {
            TerminalStream(rex, sessionId, target.blockId).events().collect { e -> onStreamEvent(e, target, paneInput) }
        }
    }

    private suspend fun onStreamEvent(e: TerminalStream.Event, target: PaneTarget, paneInput: PaneInput) {
        when (e) {
            is TerminalStream.Event.Started -> {
                val ctx = getApplication<Application>()
                val snapshot = e.snapshot
                val restored = snapshot?.let { GhosttyTerminalSession.restore(ctx, it, options()) }
                if (snapshot != null && restored == null) {
                    Log.w(TAG, "snapshot did not restore, ${snapshot.size} bytes; redrawing from the screen")
                }
                val session = restored ?: GhosttyTerminalSession(ctx, options())
                session.transport = transport(target, paneInput)
                withContext(Dispatchers.Main) {
                    val old = _session.value
                    _session.value = session
                    old?.close()
                }
                if (restored == null) redraw(session, target)
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
    }
}
