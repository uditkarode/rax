package de.uditkaro.rax.rex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/** What one pane reports about itself beyond the layout. */
data class BlockFacts(
    val sessionId: String,
    val title: String? = null,
    val pwd: String? = null,
    val process: ProcessReport? = null,
    /** Program status records by ID; the root record has the empty ID. */
    val status: Map<String, ProgramRecord> = emptyMap(),
) {
    /** The record that waits for the user, if any; the root first, then children in ID order. */
    val waiting: ProgramRecord? get() = status.toSortedMap().values.firstOrNull { it.blocked }
    val working: ProgramRecord? get() = status.toSortedMap().values.firstOrNull { it.working }
    val programName: String? get() = process?.foreground?.label ?: process?.child?.label
    val cwd: String? get() = process?.foreground?.cwd ?: pwd?.let(::pathOf)
}

data class SessionState(val info: SessionInfo, val view: SessionView? = null)

data class HostState(
    val link: RexClient.State = RexClient.State.Stopped,
    val welcome: Welcome? = null,
    /** False until the session list of the current connection has arrived. */
    val ready: Boolean = false,
    val sessions: List<SessionState> = emptyList(),
    val blocks: Map<String, BlockFacts> = emptyMap(),
    val server: ServerStatus? = null,
    val version: ServerVersion? = null,
) {
    val clientId: String? get() = welcome?.clientId
    fun session(id: String) = sessions.firstOrNull { it.info.id == id }
}

/** Something a program asked the user for, for a notification. */
sealed interface Alert {
    val sessionId: String
    val blockId: String

    data class Waiting(override val sessionId: String, override val blockId: String, val record: ProgramRecord) : Alert
    data class Notification(override val sessionId: String, override val blockId: String, val title: String, val body: String) : Alert
}

/**
 * Everything Rax knows about one Rex server, kept current from its events. All changes happen on
 * one coroutine, in the order the server sent them, so the state is always one the server had.
 */
class RexHost(
    val endpoint: RexEndpoint,
    val httpClient: OkHttpClient,
    identity: ClientIdentity,
    private val scope: CoroutineScope,
) {
    val client = RexClient(endpoint, httpClient, identity, scope)

    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _alerts = MutableSharedFlow<Alert>(extraBufferCapacity = 64)
    val alerts: SharedFlow<Alert> = _alerts.asSharedFlow()

    private sealed interface Msg {
        data class Signal(val signal: ClientSignal) : Msg
        data class Bootstrapped(val gen: Int, val sessions: List<SessionInfo>, val server: ServerStatus?, val seq: Long) : Msg
        data class BootstrapFailed(val gen: Int) : Msg
        data class ViewRead(val gen: Int, val sessionId: String, val view: SessionView) : Msg
        data class FactsRead(
            val gen: Int,
            val blockId: String,
            val sessionId: String,
            val field: Fact,
            val stamp: Int,
            val apply: (BlockFacts) -> BlockFacts,
        ) : Msg
        data class Version(val gen: Int, val version: ServerVersion) : Msg
    }

    private val inbox = Channel<Msg>(Channel.UNLIMITED)

    private var gen = 0
    private var bootstrapped = false
    private val held = ArrayList<RexEvent>()
    private var bootstrapSeq = 0L
    private val viewReading = HashSet<String>()
    private val viewDirty = HashSet<String>()
    /** The facts Rax reads about each pane, each kept current by its own events. */
    private enum class Fact { Title, Pwd, Process, Status }

    /** Bumped by each event that changes one fact of a pane, so an older read does not overwrite it. */
    private val factStamps = HashMap<Pair<String, Fact>, Int>()

    init {
        scope.launch { client.signals.collect { inbox.send(Msg.Signal(it)) } }
        scope.launch { client.state.collect { link -> _state.update { it.copy(link = link) } } }
        scope.launch { for (m in inbox) handle(m) }
    }

    fun start() = client.start()
    fun stop() = client.stop()
    fun retryNow() = client.retryNow()

    private fun handle(m: Msg) {
        when (m) {
            is Msg.Signal -> when (val s = m.signal) {
                is ClientSignal.Connected -> onConnected(s.welcome)
                is ClientSignal.Disconnected -> {
                    bootstrapped = false
                    _state.update { it.copy(ready = false) }
                }
                is ClientSignal.Event -> if (bootstrapped) onEvent(s.event) else held += s.event
            }
            is Msg.Bootstrapped -> if (m.gen == gen) {
                bootstrapped = true
                bootstrapSeq = m.seq
                val keep = _state.value.sessions.associateBy { it.info.id }
                _state.update { st ->
                    st.copy(
                        ready = true,
                        server = m.server ?: st.server,
                        sessions = m.sessions.map { info -> keep[info.id]?.copy(info = info) ?: SessionState(info) },
                        blocks = st.blocks.filterValues { f -> m.sessions.any { it.id == f.sessionId } },
                    )
                }
                m.sessions.forEach { attach(it.id) }
                val pending = held.toList()
                held.clear()
                pending.filter { it.seq > bootstrapSeq }.forEach(::onEvent)
            }
            is Msg.BootstrapFailed -> if (m.gen == gen) client.dropAndRetry()
            is Msg.ViewRead -> if (m.gen == gen) {
                viewReading -= m.sessionId
                applyView(m.sessionId, m.view)
                if (viewDirty.remove(m.sessionId)) readView(m.sessionId)
            }
            is Msg.FactsRead -> if (m.gen == gen && (factStamps[m.blockId to m.field] ?: 0) == m.stamp) {
                updateFacts(m.blockId, m.sessionId, null, m.apply)
            }
            is Msg.Version -> if (m.gen == gen) _state.update { it.copy(version = m.version) }
        }
    }

    private fun onConnected(welcome: Welcome) {
        gen++
        bootstrapped = false
        held.clear()
        viewReading.clear()
        viewDirty.clear()
        // Events from the time without a connection are gone, so every pane's facts are read again.
        factsLoaded.clear()
        _state.update { it.copy(welcome = welcome, ready = false) }
        val g = gen
        scope.launch {
            try {
                val reply = client.callWithSeq("session.bootstrap", buildJsonObject {
                    put("event_scope", "all")
                    putJsonArray("attach_session_ids") {}
                })
                val list = (reply.payload["attach_all"] as? JsonObject)?.get("sessions") as? JsonArray
                val sessions = list?.mapNotNull { runCatching { RexJson.decodeFromJsonElement<SessionInfo>(it) }.getOrNull() }.orEmpty()
                val server = reply.payload["server_status"]?.let { runCatching { RexJson.decodeFromJsonElement<ServerStatus>(it) }.getOrNull() }
                inbox.send(Msg.Bootstrapped(g, sessions, server, reply.seq))
            } catch (e: Exception) {
                inbox.send(Msg.BootstrapFailed(g))
            }
            runCatching { RexJson.decodeFromJsonElement<ServerVersion>(client.call("server.version")) }
                .onSuccess { inbox.send(Msg.Version(g, it)) }
        }
    }

    private fun attach(sessionId: String) {
        val g = gen
        viewReading += sessionId
        scope.launch {
            val view = runCatching {
                val reply = client.call("session.attach", buildJsonObject { put("session_id", sessionId) })
                RexJson.decodeFromJsonElement<SessionView>(reply["view"]!!)
            }.getOrNull()
            if (view != null) inbox.send(Msg.ViewRead(g, sessionId, view)) else inbox.send(Msg.ViewRead(g, sessionId, SessionView(revision = -1)))
        }
    }

    private fun readView(sessionId: String) {
        if (!viewReading.add(sessionId)) {
            viewDirty += sessionId
            return
        }
        val g = gen
        scope.launch {
            val view = runCatching {
                RexJson.decodeFromJsonElement<SessionView>(client.call("session.view", buildJsonObject { put("session_id", sessionId) }))
            }.getOrNull() ?: SessionView(revision = -1)
            inbox.send(Msg.ViewRead(g, sessionId, view))
        }
    }

    private fun applyView(sessionId: String, view: SessionView) {
        if (view.revision < 0) return
        val known = _state.value.session(sessionId) ?: return
        if ((known.view?.revision ?: -1) >= view.revision) return
        val blockIds = view.tabs.flatMap { w -> w.blocks.filter { it.isTerminal }.mapNotNull { it.id } }.toSet()
        _state.update { st ->
            st.copy(
                sessions = st.sessions.map { if (it.info.id == sessionId) it.copy(view = view, info = it.info.copy(label = view.label ?: it.info.label)) else it },
                blocks = st.blocks.filterValues { it.sessionId != sessionId } +
                    blockIds.associateWith { id -> st.blocks[id] ?: BlockFacts(sessionId) },
            )
        }
        val fresh = blockIds - factsLoaded
        fresh.forEach { readFacts(sessionId, it) }
        factsLoaded.retainAll(_state.value.blocks.keys)
        factsLoaded += fresh
    }

    private val factsLoaded = HashSet<String>()

    private fun readFacts(sessionId: String, blockId: String) {
        val g = gen
        fun read(field: Fact, method: String, apply: (JsonObject, BlockFacts) -> BlockFacts) {
            val stamp = factStamps[blockId to field] ?: 0
            scope.launch {
                runCatching { client.callBlock(sessionId, blockId, method) }.onSuccess { reply ->
                    inbox.send(Msg.FactsRead(g, blockId, sessionId, field, stamp) { apply(reply, it) })
                }
            }
        }
        read(Fact.Title, "title") { r, f -> f.copy(title = r.str("title")) }
        read(Fact.Pwd, "pwd") { r, f -> f.copy(pwd = r.str("pwd")) }
        read(Fact.Process, "process") { r, f ->
            f.copy(process = runCatching { RexJson.decodeFromJsonElement<ProcessReport>(r) }.getOrNull())
        }
        read(Fact.Status, "program_status") { r, f ->
            val records = (r["records"] as? JsonArray).orEmpty().mapNotNull {
                runCatching { RexJson.decodeFromJsonElement<ProgramRecord>(it) }.getOrNull()
            }
            f.copy(status = records.associateBy { it.id.orEmpty() })
        }
    }

    /**
     * Applies a change to one pane's facts. [event] names the fact an event changed, so reads that
     * started before it are dropped. A pane that starts to wait raises an alert.
     */
    private fun updateFacts(blockId: String, sessionId: String, event: Fact?, change: (BlockFacts) -> BlockFacts) {
        if (event != null) factStamps[blockId to event] = (factStamps[blockId to event] ?: 0) + 1
        val old = _state.value.blocks[blockId] ?: BlockFacts(sessionId)
        val new = change(old)
        _state.update { st -> st.copy(blocks = st.blocks + (blockId to new)) }
        val waiting = new.waiting
        if (waiting != null && old.waiting == null) _alerts.tryEmit(Alert.Waiting(sessionId, blockId, waiting))
    }

    private fun onEvent(e: RexEvent) {
        val p = e.payload
        when (e.type) {
            "session_created" -> {
                val info = (p["session"] as? JsonObject)?.let { runCatching { RexJson.decodeFromJsonElement<SessionInfo>(it) }.getOrNull() } ?: return
                if (_state.value.session(info.id) == null) {
                    _state.update { it.copy(sessions = it.sessions + SessionState(info)) }
                    attach(info.id)
                }
            }
            "session_destroyed" -> {
                val id = p.str("session_id") ?: return
                _state.update { st ->
                    st.copy(sessions = st.sessions.filterNot { it.info.id == id }, blocks = st.blocks.filterValues { it.sessionId != id })
                }
            }
            "session_moved" -> {
                val id = p.str("session_id") ?: return
                val before = p.str("before_session_id")
                _state.update { st ->
                    val moving = st.session(id) ?: return@update st
                    val rest = st.sessions.filterNot { it.info.id == id }
                    val at = before?.let { b -> rest.indexOfFirst { it.info.id == b } }?.takeIf { it >= 0 } ?: rest.size
                    st.copy(sessions = rest.toMutableList().apply { add(at, moving) })
                }
            }
            "tailscale_status_changed" -> {
                val status = runCatching { RexJson.decodeFromJsonElement<TailscaleStatus>(p) }.getOrNull()
                _state.update { it.copy(server = (it.server ?: ServerStatus()).copy(tailscale = status)) }
            }
            "session_event" -> {
                val sessionId = p.str("session_id") ?: return
                val event = p["event"] as? JsonObject ?: return
                onSessionEvent(sessionId, event.str("type") ?: return, event["payload"] as? JsonObject ?: JsonObject(emptyMap()))
            }
        }
    }

    private fun onSessionEvent(sessionId: String, type: String, p: JsonObject) {
        when (type) {
            "session_view_changed" -> {
                val revision = (p["revision"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return
                val known = _state.value.session(sessionId)?.view?.revision ?: -1
                if (revision > known) readView(sessionId)
            }
            "session_label_changed" -> {
                val label = p.str("label") ?: return
                _state.update { st ->
                    st.copy(sessions = st.sessions.map { if (it.info.id == sessionId) it.copy(info = it.info.copy(label = label)) else it })
                }
            }
            "block_closed" -> {
                val id = p.str("block_id") ?: return
                _state.update { it.copy(blocks = it.blocks - id) }
                factsLoaded -= id
            }
            "block_event" -> onBlockEvent(sessionId, p)
        }
    }

    private fun onBlockEvent(sessionId: String, p: JsonObject) {
        val blockId = p.str("block_id") ?: return
        if ((p.str("creator_name") ?: TERMINAL) != TERMINAL) return
        val body = p["payload"] as? JsonObject ?: JsonObject(emptyMap())
        when (p.str("name")) {
            "title_changed" -> updateFacts(blockId, sessionId, Fact.Title) { it.copy(title = body.str("title")) }
            "pwd_changed" -> updateFacts(blockId, sessionId, Fact.Pwd) { it.copy(pwd = body.str("pwd")) }
            "process_changed" -> {
                val fg = body["foreground"]?.let { runCatching { RexJson.decodeFromJsonElement<ProcessInfo>(it) }.getOrNull() }
                updateFacts(blockId, sessionId, Fact.Process) { f ->
                    f.copy(process = (f.process ?: ProcessReport()).copy(foreground = fg))
                }
            }
            "program_status_changed" -> {
                val id = body.str("id").orEmpty()
                val record = body["record"]?.let { runCatching { RexJson.decodeFromJsonElement<ProgramRecord>(it) }.getOrNull() } ?: return
                updateFacts(blockId, sessionId, Fact.Status) { it.copy(status = it.status + (id to record)) }
            }
            "program_status_removed" -> {
                val ids = (body["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: return
                updateFacts(blockId, sessionId, Fact.Status) { it.copy(status = it.status - ids) }
            }
            "desktop_notification" -> {
                _alerts.tryEmit(Alert.Notification(sessionId, blockId, body.str("title").orEmpty(), body.str("body").orEmpty()))
            }
        }
    }

    // Session operations. Each returns once Rex has done it; the state follows from the events.

    /** Creates a session with one shell tab; returns the session, its tab and the tab's pane. */
    suspend fun createSession(label: String? = null): Triple<String, String?, String?> {
        val reply = client.call("session.create", buildJsonObject {
            if (!label.isNullOrBlank()) put("label", label)
            putJsonArray("initial_windows") {
                add(buildJsonObject { put("layout", shellLayout()) })
            }
        })
        val window = (reply["initial_windows"] as? JsonArray)?.firstOrNull() as? JsonObject
        return Triple(
            reply.str("session_id") ?: error("no session_id"),
            window?.str("window_id"),
            (window?.get("block_ids") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun renameSession(sessionId: String, label: String) {
        client.call("session.set_label", buildJsonObject { put("session_id", sessionId); put("label", label) })
    }

    suspend fun endSession(sessionId: String) {
        client.call("session.destroy", buildJsonObject { put("session_id", sessionId) })
    }

    /** Opens a tab without moving the desktop to it; returns its window and first block. */
    suspend fun newTab(sessionId: String): Pair<String?, String?> {
        val reply = client.call("session.new_window", buildJsonObject {
            put("session_id", sessionId)
            put("layout", shellLayout())
            put("focus", false)
        })
        return reply.str("window_id") to (reply["block_ids"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
    }

    suspend fun renameTab(sessionId: String, windowId: String, label: String) {
        client.call("session.set_window_label", buildJsonObject {
            put("session_id", sessionId); put("window_id", windowId); put("label", label)
        })
    }

    suspend fun closeTab(sessionId: String, windowId: String) {
        client.call("session.close_window", buildJsonObject { put("session_id", sessionId); put("window_id", windowId) })
    }

    /** Splits [blockId]: [vertical] puts the new pane below, otherwise to the right. Returns the new block. */
    suspend fun split(sessionId: String, blockId: String, vertical: Boolean): String? {
        val reply = client.call("session.new_split", buildJsonObject {
            put("session_id", sessionId)
            put("anchor_id", blockId)
            put("direction", if (vertical) "vertical" else "horizontal")
            put("side", "after")
            put("layout", shellLayout())
            put("focus", false)
        })
        return (reply["block_ids"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
    }

    suspend fun renamePane(sessionId: String, blockId: String, label: String) {
        client.call("session.set_block_label", buildJsonObject {
            put("session_id", sessionId); put("block_id", blockId); put("label", label)
        })
    }

    suspend fun closePane(sessionId: String, blockId: String) {
        client.call("block.close", buildJsonObject { put("session_id", sessionId); put("block_id", blockId) })
    }

    suspend fun take(sessionId: String, blockId: String) {
        client.call("session.own_block", buildJsonObject {
            put("session_id", sessionId)
            putJsonArray("block_ids") { add(JsonPrimitive(blockId)) }
            put("mode", "take")
        })
    }

    suspend fun release(sessionId: String, blockId: String) {
        client.call("session.disown_block", buildJsonObject {
            put("session_id", sessionId)
            putJsonArray("block_ids") { add(JsonPrimitive(blockId)) }
        })
    }

    private fun shellLayout() = buildJsonObject {
        put("block", buildJsonObject { put("flavor", "$TERMINAL.shell") })
    }
}

/** A `file://host/path` URL from OSC 7 as a path; a plain path stays as it is. */
fun pathOf(pwd: String): String =
    if (pwd.startsWith("file://")) {
        java.net.URLDecoder.decode(pwd.removePrefix("file://").let { rest -> "/" + rest.substringAfter('/', "") }, "UTF-8")
    } else {
        pwd
    }
