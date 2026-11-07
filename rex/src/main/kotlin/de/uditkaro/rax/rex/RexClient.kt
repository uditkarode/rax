package de.uditkaro.rax.rex

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** How Rax introduces itself in the hello. Rex shows these to people, for example in a client list. */
data class ClientIdentity(val name: String, val version: String, val label: String)

data class Welcome(
    val clientId: String,
    /** The revision this connection speaks. */
    val apiRevision: Int,
    /** The newest revision the server speaks; higher than [RexClient.API_REVISION] means Rex is newer than Rax. */
    val serverRevision: Int,
    val maxMessageBytes: Int,
    /** True when this connection continued the previous client, keeping its attachments and owned panes. */
    val resumed: Boolean,
)

data class RexEvent(val seq: Long, val type: String, val payload: JsonObject)

/** Why the control connection is down. */
sealed interface Problem {
    /** The server could not be reached: no route, refused, timed out, or the name did not resolve. */
    data class Unreachable(val detail: String) : Problem
    data class Tls(val detail: String) : Problem
    /** The listener turned the caller away, such as a Tailscale user the server does not admit. */
    data class Refused(val httpStatus: Int, val detail: String) : Problem
    /** Something answered that is not a Rex control endpoint. */
    data class NotRex(val detail: String) : Problem
    /** The server and Rax share no API revision; [reason] is Rex's own sentence, such as "...; update the server". */
    data class Incompatible(val reason: String) : Problem
    data class Closed(val code: Int, val reason: String) : Problem
}

/** What the control connection tells its owner, in order. */
sealed interface ClientSignal {
    data class Connected(val welcome: Welcome) : ClientSignal
    data class Event(val event: RexEvent) : ClientSignal
    data class Disconnected(val problem: Problem) : ClientSignal
}

class RexException(val code: String, val status: Int, message: String) : Exception(message) {
    companion object {
        fun notConnected() = RexException("not_connected", 0, "not connected")
    }
}

/**
 * The control connection to one Rex server: the hello, requests matched to responses, events, and
 * reconnects that resume the same client when the server still holds it.
 */
class RexClient(
    private val endpoint: RexEndpoint,
    private val http: OkHttpClient,
    private val identity: ClientIdentity,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Stopped : State
        /** [attempt] counts from 0 after each connection. [lastProblem] is why the attempt before failed. */
        data class Connecting(val attempt: Int, val lastProblem: Problem? = null) : State
        data class Connected(val welcome: Welcome) : State
        /**
         * Attempt number [attempt] failed. [retryInMs] is null when retrying cannot help until
         * something changes, such as an update.
         */
        data class Down(val problem: Problem, val retryInMs: Long?, val attempt: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val signalChannel = Channel<ClientSignal>(Channel.UNLIMITED)

    /** Connection changes and events in the order they happened. Collect it from one place only. */
    val signals: Flow<ClientSignal> = signalChannel.receiveAsFlow()

    @Volatile private var current: Connection? = null
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var reconnectToken: String? = null
    private val nextId = AtomicLong()

    fun start() {
        if (loop?.isActive == true) {
            wake.trySend(Unit)
            return
        }
        loop = scope.launch { run() }
    }

    fun retryNow() {
        wake.trySend(Unit)
    }

    /** Closes the connection with a close frame, which ends this client on the server at once. */
    fun stop() {
        closeFrameOnEnd = true
        loop?.cancel()
        loop = null
        current?.let { conn ->
            conn.ws?.close(1000, null)
            conn.fail(RexException.notConnected())
        }
        current = null
        reconnectToken = null
        _state.value = State.Stopped
    }

    /**
     * Drops the connection without a close frame, the way a lost network does, so the server keeps
     * this client for a minute and the next connection resumes it.
     */
    fun suspendConnection() {
        closeFrameOnEnd = false
        loop?.cancel()
        loop = null
        current?.let { conn ->
            conn.ws?.cancel()
            conn.fail(RexException.notConnected())
        }
        current = null
        _state.value = State.Stopped
    }

    /** Ends this connection and lets the loop open a fresh one, after a setup call failed half way. */
    fun dropAndRetry() {
        reconnectToken = null
        current?.ws?.close(1000, "retry")
    }

    val connected: Boolean get() = current?.welcome?.isCompleted == true && _state.value is State.Connected

    suspend fun call(method: String, payload: JsonObject? = null, timeoutMs: Long = 20_000): JsonObject =
        callWithSeq(method, payload, timeoutMs).payload

    /**
     * Like [call], with the response's `seq`: the last event the server had queued when it started
     * the request, so events up to it are already part of the result.
     */
    suspend fun callWithSeq(method: String, payload: JsonObject? = null, timeoutMs: Long = 20_000): Reply {
        val conn = current?.takeIf { _state.value is State.Connected } ?: throw RexException.notConnected()
        val id = "r" + nextId.incrementAndGet()
        val reply = CompletableDeferred<Reply>()
        conn.pending[id] = reply
        val message = buildJsonObject {
            put("type", "request")
            put("id", id)
            put("method", method)
            if (payload != null) put("payload", payload)
        }
        if (conn.ws?.send(message.toString()) != true) {
            conn.pending.remove(id)
            throw RexException.notConnected()
        }
        return try {
            withTimeout(timeoutMs) { reply.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw RexException("timeout", 504, "$method timed out")
        } finally {
            conn.pending.remove(id)
        }
    }

    /** A method of a block's creator, such as `com.superlogical.terminal.write`. */
    suspend fun callBlock(
        sessionId: String,
        blockId: String,
        method: String,
        args: JsonObject? = null,
        creator: String = TERMINAL,
    ): JsonObject = call("$creator.$method", buildJsonObject {
        put("session_id", sessionId)
        put("block_id", blockId)
        if (args != null) put("args", args)
    })

    /** How [stop] and [suspendConnection] end the loop, for a connection it opened after they looked. */
    @Volatile private var closeFrameOnEnd = true

    private suspend fun run() {
        var attempt = 0
        while (scope.isActive) {
            _state.value = State.Connecting(attempt, (_state.value as? State.Down)?.problem)
            val conn = Connection()
            current = conn
            try {
                attempt = connectOnce(conn, attempt)
            } catch (e: CancellationException) {
                if (closeFrameOnEnd) conn.ws?.close(1000, null) else conn.ws?.cancel()
                conn.fail(RexException.notConnected())
                if (current === conn) current = null
                signalChannel.trySend(ClientSignal.Disconnected(Problem.Closed(1000, "stopped")))
                throw e
            }
        }
    }

    /**
     * Runs one connection until it closes, then waits before the next attempt. Returns the number of
     * the next attempt.
     */
    private suspend fun connectOnce(conn: Connection, attemptIn: Int): Int {
        var attempt = attemptIn
        conn.ws = http.newWebSocket(
            Request.Builder()
                .url(endpoint.controlUrl)
                .header("Sec-WebSocket-Protocol", CONTROL_SUBPROTOCOL)
                .build(),
            conn.listener,
        )
        val problem: Problem = try {
            val welcome = withTimeout(HANDSHAKE_TIMEOUT_MS) { conn.welcome.await() }
            reconnectToken = conn.token
            attempt = 0
            _state.value = State.Connected(welcome)
            signalChannel.send(ClientSignal.Connected(welcome))
            conn.closed.await()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            conn.ws?.cancel()
            Problem.Unreachable("no answer")
        } catch (e: HandshakeFailure) {
            e.problem
        }
        conn.fail(RexException.notConnected())
        if (current === conn) current = null
        signalChannel.send(ClientSignal.Disconnected(problem))

        val failed = attempt++
        val retry = retryDelay(problem, failed)
        _state.value = State.Down(problem, retry, failed)
        if (retry == null) {
            wake.receive()
            attempt = 0
        } else {
            select {
                wake.onReceive {}
                onTimeout(retry) {}
            }
        }
        return attempt
    }

    private fun retryDelay(problem: Problem, attempt: Int): Long? {
        if (problem is Problem.Incompatible) return null
        if (problem is Problem.Refused && problem.httpStatus == 403) return null
        val base = minOf(30_000L, 500L shl minOf(attempt, 6))
        return base + Random.nextLong(base / 5 + 1)
    }

    private class HandshakeFailure(val problem: Problem) : Exception()

    private inner class Connection {
        var ws: WebSocket? = null
        val welcome = CompletableDeferred<Welcome>()
        val closed = CompletableDeferred<Problem>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<Reply>>()
        var token: String? = null

        fun fail(e: Exception) {
            welcome.completeExceptionally(e)
            closed.complete(Problem.Closed(1006, "gone"))
            pending.values.forEach { it.completeExceptionally(e) }
            pending.clear()
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = buildJsonObject {
                    put("type", "hello")
                    put("api_revision", API_REVISION)
                    put("api_min_revision", API_MIN_REVISION)
                    reconnectToken?.let { put("reconnect_token", it) }
                    putJsonObject("client") {
                        put("kind", "app")
                        put("platform", "other")
                        put("name", identity.name)
                        put("version", identity.version)
                        put("label", identity.label)
                    }
                }
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { RexJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (message.str("type")) {
                    "welcome" -> onWelcome(message)
                    "response" -> onResponse(message)
                    "event" -> onEvent(message)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                val problem = closeProblem(code, reason)
                welcome.completeExceptionally(HandshakeFailure(problem))
                closed.complete(problem)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val problem = closeProblem(code, reason)
                welcome.completeExceptionally(HandshakeFailure(problem))
                closed.complete(problem)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val problem = failureProblem(t, response)
                welcome.completeExceptionally(HandshakeFailure(problem))
                closed.complete(problem)
            }
        }

        private fun onWelcome(m: JsonObject) {
            if (m.str("protocol") != CONTROL_SUBPROTOCOL) {
                ws?.cancel()
                welcome.completeExceptionally(HandshakeFailure(Problem.NotRex("protocol ${m.str("protocol")}")))
                return
            }
            val client = m["client"]?.jsonObject
            token = client?.str("reconnect_token")
            welcome.complete(
                Welcome(
                    clientId = client?.str("id").orEmpty(),
                    apiRevision = m.int("api_revision") ?: 0,
                    serverRevision = m.int("api_server_revision") ?: m.int("api_revision") ?: 0,
                    maxMessageBytes = m["limits"]?.jsonObject?.int("max_message_bytes") ?: 65_536,
                    resumed = client?.get("resumed")?.jsonPrimitive?.contentOrNull == "true",
                ),
            )
        }

        private fun onResponse(m: JsonObject) {
            val id = m.str("id") ?: return
            val reply = pending.remove(id) ?: return
            val error = m["error"]?.let { it as? JsonObject }
            if (error != null) {
                reply.completeExceptionally(
                    RexException(error.str("code") ?: "error", error.int("status") ?: 0, error.str("message").orEmpty()),
                )
            } else {
                val payload = (m["payload"] as? JsonObject) ?: JsonObject(emptyMap())
                reply.complete(Reply(payload, m["seq"]?.jsonPrimitive?.longOrNull ?: 0))
            }
        }

        private fun onEvent(m: JsonObject) {
            val seq = m["seq"]?.jsonPrimitive?.longOrNull ?: 0
            val body = m["payload"] as? JsonObject ?: return
            val type = body.str("type") ?: return
            val payload = body["payload"] as? JsonObject ?: JsonObject(emptyMap())
            signalChannel.trySend(ClientSignal.Event(RexEvent(seq, type, payload)))
        }
    }

    private fun closeProblem(code: Int, reason: String): Problem = when {
        code == 1008 && reason.startsWith("no common API revision") -> Problem.Incompatible(reason)
        code == 1002 -> Problem.NotRex(reason.ifEmpty { "protocol error" })
        else -> Problem.Closed(code, reason)
    }

    private fun failureProblem(t: Throwable, response: Response?): Problem {
        if (response != null) {
            val status = response.code
            val body = runCatching { response.body.string() }.getOrNull().orEmpty()
            val detail = runCatching { RexJson.parseToJsonElement(body).jsonObject.str("error") }.getOrNull() ?: body.take(200)
            return when (status) {
                401, 403 -> Problem.Refused(status, detail)
                else -> Problem.NotRex("HTTP $status")
            }
        }
        return when (t) {
            is SSLException -> Problem.Tls(t.message ?: "TLS failed")
            is UnknownHostException -> Problem.Unreachable("unknown host")
            is ConnectException, is SocketTimeoutException -> Problem.Unreachable(t.message ?: "no route")
            is IOException -> Problem.Unreachable(t.message ?: "connection lost")
            else -> Problem.Unreachable(t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        /** The revision Rax is written against. */
        const val API_REVISION = 11
        /** The oldest revision whose messages Rax understands. */
        const val API_MIN_REVISION = 11
        const val CONTROL_SUBPROTOCOL = "rex.control.v1"
        private const val HANDSHAKE_TIMEOUT_MS = 12_000L
    }
}

data class Reply(val payload: JsonObject, val seq: Long)

internal fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
