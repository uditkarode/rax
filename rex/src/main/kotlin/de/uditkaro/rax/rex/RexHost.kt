package de.uditkaro.rax.rex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    /** True once Rax has read [status] from Rex, so a missing [waiting] means the pane does not wait. */
    val statusRead: Boolean = false,
) {
    /** The record that waits for the user, if any; the root first, then children in ID order. */
    val waiting: ProgramRecord? get() = status.toSortedMap().values.firstOrNull { it.blocked }
    val working: ProgramRecord? get() = status.toSortedMap().values.firstOrNull { it.working }
    val programName: String? get() = process?.foreground?.label ?: process?.child?.label
    val cwd: String? get() = process?.foreground?.cwd ?: pwd?.let(::pathOf)
}

/** How far the current connection got with attaching a session. Rex refuses calls that name a session before it is attached. */
enum class Attach { Pending, Done, Failed }

data class SessionState(val info: SessionInfo, val view: SessionView? = null, val attach: Attach = Attach.Pending)

data class HostState(
    val link: RexClient.State = RexClient.State.Stopped,
    val welcome: Welcome? = null,
    /** False until the session list of the current connection has arrived. */
    val ready: Boolean = false,
    /**
     * True once the sessions' views and their panes' facts arrived for the first time, or Rax stopped
     * waiting for them. It stays true across reconnects, and turns false when the server restarts.
     */
    val loaded: Boolean = false,
    val sessions: List<SessionState> = emptyList(),
    /** The facts of every terminal pane in the sessions' views, minimized ones included. */
    val blocks: Map<String, BlockFacts> = emptyMap(),
    val server: ServerStatus? = null,
    val version: ServerVersion? = null,
) {
    val clientId: String? get() = welcome?.clientId
    fun session(id: String) = sessions.firstOrNull { it.info.id == id }

    /** Whether Rex takes calls that name the session now. */
    fun canCall(sessionId: String): Boolean =
        ready && link is RexClient.State.Connected && session(sessionId)?.attach == Attach.Done
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

    /** The facts Rax reads about each pane, each kept current by its own events. */
    private enum class Fact(val method: String) { Title("title"), Pwd("pwd"), Process("process"), Status("program_status") }

    private sealed interface Msg {
        data class Signal(val signal: ClientSignal) : Msg
        data class Bootstrapped(val gen: Int, val sessions: List<SessionInfo>, val server: ServerStatus?, val seq: Long) : Msg
        data class BootstrapFailed(val gen: Int, val welcome: Welcome) : Msg
        data class ViewRead(val gen: Int, val sessionId: String, val view: SessionView, val attach: Attach? = null) : Msg
        /** Rex's answer to a read of [fact]; [stamp] is the fact's stamp when the read started. */
        class FactRead(val gen: Int, val sessionId: String, val blockId: String, val fact: Fact, val stamp: Int, val reply: JsonObject) : Msg
        /** Rex refused a read of [fact] in a way that trying again does not change. */
        data class FactFailed(val gen: Int, val blockId: String, val fact: Fact) : Msg
        /** The time to wait for the first load on connection [gen] is over. */
        data class LoadWaited(val gen: Int) : Msg
        data class Version(val gen: Int, val version: ServerVersion) : Msg
    }

    private val inbox = Channel<Msg>(Channel.UNLIMITED)

    /** Goes up when a connection starts and when it ends; reads carry it, and an older result is dropped. */
    @Volatile private var gen = 0
    private var bootstrapped = false
    private val held = ArrayList<RexEvent>()
    private var bootstrapSeq = 0L
    private val viewReading = HashSet<String>()
    private val viewDirty = HashSet<String>()
    /** Panes whose facts were read on this connection. */
    private val factsLoaded = HashSet<String>()
    /** Whether the first load is under way, and the reads it still waits for. */
    private var loading = false
    private val firstReads = HashSet<Pair<String, Fact>>()

    /** Counts the events that changed each fact of one pane, so a read that started earlier does not overwrite them. */
    private class Stamps {
        val facts = IntArray(Fact.entries.size)
        /** For each program status record, the [Fact.Status] stamp of the last event that changed it. */
        val records = HashMap<String, Int>()
    }

    private val stamps = HashMap<String, Stamps>()

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
                    gen++
                    bootstrapped = false
                    loading = false
                    firstReads.clear()
                    _state.update { it.copy(ready = false) }
                }
                is ClientSignal.Event -> {
                    // A desktop notification changes no state, so it does not wait for the session list.
                    // The bootstrap reply that replaces the held events does not carry it.
                    val notification = notificationOf(s.event)
                    when {
                        notification != null -> _alerts.tryEmit(notification)
                        bootstrapped -> onEvent(s.event)
                        else -> held += s.event
                    }
                }
            }
            is Msg.Bootstrapped -> if (m.gen == gen) {
                bootstrapped = true
                bootstrapSeq = m.seq
                // A restarted server can reuse session and pane IDs and count their revisions from the
                // start again, so the views and pane facts Rax kept from before do not apply to it.
                val before = _state.value.server?.instanceId
                val restarted = before != null && m.server?.instanceId != null && m.server.instanceId != before
                val keep = if (restarted) emptyMap() else _state.value.sessions.associateBy { it.info.id }
                _state.update { st ->
                    st.copy(
                        ready = true,
                        loaded = st.loaded && !restarted,
                        server = m.server ?: st.server,
                        sessions = m.sessions.map { info -> keep[info.id]?.copy(info = info, attach = Attach.Pending) ?: SessionState(info) },
                        blocks = if (restarted) emptyMap() else st.blocks.filterValues { f -> m.sessions.any { it.id == f.sessionId } },
                    )
                }
                forgetGonePanes()
                if (!_state.value.loaded) {
                    loading = true
                    val g = gen
                    scope.launch {
                        delay(LOAD_WAIT_MS)
                        inbox.send(Msg.LoadWaited(g))
                    }
                }
                m.sessions.forEach { attach(it.id) }
                val pending = held.toList()
                held.clear()
                pending.filter { it.seq > bootstrapSeq }.forEach(::onEvent)
                checkLoaded()
            }
            is Msg.BootstrapFailed -> if (m.gen == gen) client.dropAndRetry(m.welcome)
            is Msg.ViewRead -> if (m.gen == gen) {
                viewReading -= m.sessionId
                if (m.attach != null) {
                    _state.update { st -> st.copy(sessions = st.sessions.map { if (it.info.id == m.sessionId) it.copy(attach = m.attach) else it }) }
                }
                applyView(m.sessionId, m.view)
                if (viewDirty.remove(m.sessionId)) readView(m.sessionId)
                checkLoaded()
            }
            is Msg.FactRead -> if (m.gen == gen) {
                if (m.blockId in _state.value.blocks) applyRead(m)
                firstReads -= m.blockId to m.fact
                checkLoaded()
            }
            is Msg.FactFailed -> if (m.gen == gen) {
                firstReads -= m.blockId to m.fact
                checkLoaded()
            }
            is Msg.LoadWaited -> if (m.gen == gen && loading) finishLoad()
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
                inbox.send(Msg.BootstrapFailed(g, welcome))
            }
            runCatching { RexJson.decodeFromJsonElement<ServerVersion>(client.call("server.version")) }
                .onSuccess { inbox.send(Msg.Version(g, it)) }
        }
    }

    /** Attaches the session, trying again while Rex fails in a way that can pass. The view follows as a [Msg.ViewRead]. */
    private fun attach(sessionId: String) {
        val g = gen
        viewReading += sessionId
        scope.launch {
            var failures = 0
            while (true) {
                val result = runCatching {
                    val reply = client.call("session.attach", buildJsonObject { put("session_id", sessionId) })
                    RexJson.decodeFromJsonElement<SessionView>(reply["view"]!!)
                }
                val view = result.getOrNull()
                if (view != null || !worthRetrying(result.exceptionOrNull(), g)) {
                    inbox.send(Msg.ViewRead(g, sessionId, view ?: SessionView(revision = -1), attach = if (view != null) Attach.Done else Attach.Failed))
                    return@launch
                }
                delay(retryDelay(failures++))
                // A reply on a newer connection would be dropped.
                if (g != gen) return@launch
            }
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
        if ((known.view?.revision ?: -1) < view.revision) {
            val blockIds = view.terminalIds
            _state.update { st ->
                st.copy(
                    sessions = st.sessions.map { if (it.info.id == sessionId) it.copy(view = view, info = it.info.copy(label = view.label ?: it.info.label)) else it },
                    blocks = st.blocks.filterValues { it.sessionId != sessionId } +
                        blockIds.associateWith { id -> st.blocks[id] ?: BlockFacts(sessionId) },
                )
            }
            forgetGonePanes()
        }
        // After a reconnect the view is often the one Rax already has. Its panes' facts are read
        // again all the same.
        val st = _state.value
        val fresh = st.session(sessionId)?.view?.terminalIds.orEmpty().filter { it in st.blocks && it !in factsLoaded }
        fresh.forEach { readFacts(sessionId, it) }
        factsLoaded += fresh
    }

    /** Ends the first load once every session is attached and the first reads of their panes are back. */
    private fun checkLoaded() {
        val st = _state.value
        if (loading && st.ready && st.sessions.none { it.attach == Attach.Pending } && firstReads.isEmpty()) finishLoad()
    }

    private fun finishLoad() {
        loading = false
        firstReads.clear()
        _state.update { it.copy(loaded = true) }
    }

    /** Forgets the reads and stamps of panes that are no longer in [HostState.blocks]. */
    private fun forgetGonePanes() {
        val ids = _state.value.blocks.keys
        factsLoaded.retainAll(ids)
        stamps.keys.retainAll(ids)
    }

    private fun stamp(blockId: String, fact: Fact): Int = stamps[blockId]?.facts?.get(fact.ordinal) ?: 0

    private fun readFacts(sessionId: String, blockId: String) {
        Fact.entries.forEach { readFact(sessionId, blockId, it) }
    }

    /** Reads one fact of a pane, trying again while Rex fails in a way that can pass. */
    private fun readFact(sessionId: String, blockId: String, fact: Fact) {
        val g = gen
        val stamp = stamp(blockId, fact)
        if (loading) firstReads += blockId to fact
        scope.launch {
            var failures = 0
            while (true) {
                val result = runCatching { client.callBlock(sessionId, blockId, fact.method) }
                result.onSuccess { reply ->
                    inbox.send(Msg.FactRead(g, sessionId, blockId, fact, stamp, reply))
                    return@launch
                }
                if (!worthRetrying(result.exceptionOrNull(), g)) {
                    inbox.send(Msg.FactFailed(g, blockId, fact))
                    return@launch
                }
                delay(retryDelay(failures++))
                // A reply on a newer connection would be dropped.
                if (g != gen) return@launch
            }
        }
    }

    /** Whether a call made on connection [g] that failed with [e] can succeed when it goes out again. */
    private fun worthRetrying(e: Throwable?, g: Int): Boolean =
        g == gen && e is RexException && (e.code == "timeout" || e.code == "conflict" || e.status >= 500)

    private fun retryDelay(failures: Int): Long = minOf(30_000L, 1_000L shl minOf(failures, 5))

    /**
     * Applies a read of one fact. An event that came after the read started is newer than the read
     * for what it carries. Title and directory events carry the whole fact, so the read is dropped.
     * Process and status events carry a part, so the read fills in the rest.
     */
    private fun applyRead(m: Msg.FactRead) {
        val r = m.reply
        val changed = stamp(m.blockId, m.fact) != m.stamp
        when (m.fact) {
            Fact.Title -> if (!changed) updateFacts(m.blockId, m.sessionId) { it.copy(title = r.str("title")) }
            Fact.Pwd -> if (!changed) updateFacts(m.blockId, m.sessionId) { it.copy(pwd = r.str("pwd")) }
            Fact.Process -> {
                val read = runCatching { RexJson.decodeFromJsonElement<ProcessReport>(r) }.getOrNull()
                updateFacts(m.blockId, m.sessionId) { f ->
                    f.copy(process = if (changed) read?.copy(foreground = f.process?.foreground) ?: f.process else read)
                }
            }
            Fact.Status -> {
                val read = (r["records"] as? JsonArray).orEmpty().mapNotNull {
                    runCatching { RexJson.decodeFromJsonElement<ProgramRecord>(it) }.getOrNull()
                }.associateBy { it.id.orEmpty() }
                val records = stamps[m.blockId]?.records
                val touched = records?.filterValues { it > m.stamp }?.keys.orEmpty()
                records?.values?.removeAll { it <= m.stamp }
                updateFacts(m.blockId, m.sessionId) { f ->
                    f.copy(status = f.status.filterKeys { it in touched } + read.filterKeys { it !in touched }, statusRead = true)
                }
            }
        }
    }

    /** Applies an event that changed [fact] of a pane, and the program status [records] it names. */
    private fun onFactEvent(blockId: String, sessionId: String, fact: Fact, records: Collection<String> = emptyList(), change: (BlockFacts) -> BlockFacts) {
        val s = stamps.getOrPut(blockId, ::Stamps)
        val stamp = ++s.facts[fact.ordinal]
        records.forEach { s.records[it] = stamp }
        updateFacts(blockId, sessionId, change)
    }

    /** Applies a change to one pane's facts. A pane that starts to wait raises an alert. */
    private fun updateFacts(blockId: String, sessionId: String, change: (BlockFacts) -> BlockFacts) {
        val old = _state.value.blocks[blockId] ?: BlockFacts(sessionId)
        val new = change(old)
        _state.update { st -> st.copy(blocks = st.blocks + (blockId to new)) }
        val waiting = new.waiting
        if (waiting != null && old.waiting == null) _alerts.tryEmit(Alert.Waiting(sessionId, blockId, waiting))
    }

    /** The desktop notification a program sent, if [e] is one. */
    private fun notificationOf(e: RexEvent): Alert.Notification? {
        if (e.type != "session_event") return null
        val sessionId = e.payload.str("session_id") ?: return null
        val event = e.payload["event"] as? JsonObject ?: return null
        if (event.str("type") != "block_event") return null
        val p = event["payload"] as? JsonObject ?: return null
        if (p.str("name") != "desktop_notification" || (p.str("creator_name") ?: TERMINAL) != TERMINAL) return null
        val blockId = p.str("block_id") ?: return null
        val body = p["payload"] as? JsonObject ?: JsonObject(emptyMap())
        return Alert.Notification(sessionId, blockId, body.str("title").orEmpty(), body.str("body").orEmpty())
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
                forgetGonePanes()
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
                forgetGonePanes()
            }
            "block_event" -> onBlockEvent(sessionId, p)
        }
    }

    private fun onBlockEvent(sessionId: String, p: JsonObject) {
        val blockId = p.str("block_id") ?: return
        if ((p.str("creator_name") ?: TERMINAL) != TERMINAL) return
        val body = p["payload"] as? JsonObject ?: JsonObject(emptyMap())
        when (p.str("name")) {
            "title_changed" -> onFactEvent(blockId, sessionId, Fact.Title) { it.copy(title = body.str("title")) }
            "pwd_changed" -> onFactEvent(blockId, sessionId, Fact.Pwd) { it.copy(pwd = body.str("pwd")) }
            "process_changed" -> {
                val fg = body["foreground"]?.let { runCatching { RexJson.decodeFromJsonElement<ProcessInfo>(it) }.getOrNull() }
                onFactEvent(blockId, sessionId, Fact.Process) { f ->
                    f.copy(process = (f.process ?: ProcessReport()).copy(foreground = fg))
                }
            }
            "program_status_changed" -> {
                val id = body.str("id").orEmpty()
                val record = body["record"]?.let { runCatching { RexJson.decodeFromJsonElement<ProgramRecord>(it) }.getOrNull() } ?: return
                onFactEvent(blockId, sessionId, Fact.Status, listOf(id)) { it.copy(status = it.status + (id to record)) }
            }
            "program_status_removed" -> {
                val ids = (body["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: return
                onFactEvent(blockId, sessionId, Fact.Status, ids) { it.copy(status = it.status - ids) }
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

/** The longest a host's first load waits for slow or failing reads before the host shows what it has. */
private const val LOAD_WAIT_MS = 2_000L

/** A `file://host/path` URL from OSC 7 as a path; a plain path stays as it is. */
fun pathOf(pwd: String): String =
    if (pwd.startsWith("file://")) {
        java.net.URLDecoder.decode(pwd.removePrefix("file://").let { rest -> "/" + rest.substringAfter('/', "") }, "UTF-8")
    } else {
        pwd
    }
