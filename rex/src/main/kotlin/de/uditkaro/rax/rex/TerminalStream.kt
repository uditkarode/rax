package de.uditkaro.rax.rex

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * To connect, it gets a ticket on the control connection, opens `/api/data` and does the
 * HELLO/WELCOME handshake. If the connection drops and Rex still has the stream, the stream
 * continues where it stopped. If Rex no longer has it, Rax opens a new stream, and that stream
 * starts with a new snapshot.
 */
class TerminalStream(
    private val host: RexHost,
    val sessionId: String,
    val blockId: String,
) {
    sealed interface Event {
        /** The snapshot is still arriving; [bytes] have come so far. */
        data class Loading(val bytes: Long) : Event
        /** A new stream began. Rebuild the emulator from [snapshot] (null: start blank). */
        class Started(val snapshot: ByteArray?) : Event
        class Output(val bytes: ByteArray) : Event
        data class Resize(val columns: Int, val rows: Int, val owner: String?) : Event
        data class Exited(val code: Int) : Event
        /** The connection is down and being retried; what is on screen stays. */
        data object Interrupted : Event
        /** The stream continues after [Interrupted], with no output missed. */
        data object Resumed : Event
        /** The pane closed. Nothing more will come. */
        data object Closed : Event
    }

    private class Handshake(val token: ByteArray, val nonce: Long)

    fun events(): Flow<Event> = channelFlow {
        var resume: Handshake? = null
        var received = 0L
        var parser: StreamParser? = null
        var started = false
        var failures = 0
        var rejects = 0

        while (isActive) {
            host.client.state.first { it is RexClient.State.Connected }
            val hello: ByteArray = if (resume != null) {
                ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
                    .put(0x02).put(resume.token).putLong(received).array()
            } else {
                val ticket = runCatching {
                    host.client.call("data.connect", buildJsonObject {
                        put("session_id", sessionId)
                        put("block_id", blockId)
                        put("data_ref_id", "terminal")
                        put("subprotocol", SUBPROTOCOL)
                    }).str("ticket")
                }
                val error = ticket.exceptionOrNull()
                if (error is RexException && (error.code == "not_found" || error.code == "session_not_found")) {
                    send(Event.Closed)
                    return@channelFlow
                }
                val value = ticket.getOrNull()
                if (value == null) {
                    // Usually the session is not attached yet on a fresh connection.
                    delay(backoff(failures++))
                    continue
                }
                ByteArray(17).also { b ->
                    b[0] = 0x01
                    Base64.getUrlDecoder().decode(value).copyInto(b, 1)
                }
            }

            val inbox = Channel<Incoming>(Channel.UNLIMITED)
            val ws = host.httpClient.newWebSocket(
                Request.Builder().url(host.endpoint.dataUrl).header("Sec-WebSocket-Protocol", SUBPROTOCOL).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(hello.toByteString())
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        inbox.trySend(Incoming.Bytes(bytes.toByteArray()))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        inbox.trySend(Incoming.Closed(code))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        inbox.trySend(Incoming.Closed(code))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        inbox.trySend(Incoming.Closed(1006))
                    }
                },
            )
            try {
                val first = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { inbox.receive() }
                val welcome = (first as? Incoming.Bytes)?.bytes
                when {
                    welcome != null && welcome.size >= 32 && welcome[0] == 0x81.toByte() -> {
                        val b = ByteBuffer.wrap(welcome).order(ByteOrder.LITTLE_ENDIAN)
                        val token = welcome.copyOfRange(1, 17)
                        val nonce = b.getLong(24)
                        val resumed = resume != null && parser != null
                        if (!resumed) {
                            parser = StreamParser(nonce)
                            received = 0
                            started = false
                        }
                        resume = Handshake(token, nonce)
                        failures = 0
                        rejects = 0
                        if (resumed) send(Event.Resumed)
                    }
                    welcome != null && welcome.size >= 2 && welcome[0] == 0x82.toByte() -> {
                        // If Rex rejects a resume, drop the token and open a new stream. If Rex rejects
                        // a new HELLO, try again. Rex keeps rejecting it until it closes this client's
                        // old stream. When the pane was just left and opened again, that takes Rex a
                        // moment, so those tries come quickly at first.
                        if (resume == null) {
                            if (++rejects == REJECTS_BEFORE_INTERRUPTED) send(Event.Interrupted)
                            val stillOpen = welcome[1] == REJECT_ALREADY_CONNECTED
                            delay(if (stillOpen && rejects <= QUICK_RETRIES) QUICK_RETRY_MS else backoff(failures++))
                        }
                        resume = null
                        parser = null
                        continue
                    }
                    else -> {
                        if (first is Incoming.Closed && first.code == BLOCK_CLOSED) {
                            send(Event.Closed)
                            return@channelFlow
                        }
                        send(Event.Interrupted)
                        delay(backoff(failures++))
                        continue
                    }
                }

                val p = parser!!
                var ended = false
                for (m in inbox) {
                    when (m) {
                        is Incoming.Bytes -> {
                            received += m.bytes.size
                            val parts = p.feed(m.bytes)
                            if (!started && parts.isEmpty()) send(Event.Loading(received))
                            for (part in parts) {
                                if (part is StreamPart.Snapshot) started = true
                                send(
                                    when (part) {
                                        is StreamPart.Snapshot -> Event.Started(part.bytes)
                                        is StreamPart.Output -> Event.Output(part.bytes)
                                        is StreamPart.Resize -> Event.Resize(part.columns, part.rows, part.owner)
                                        is StreamPart.Exited -> Event.Exited(part.code)
                                    },
                                )
                            }
                        }
                        is Incoming.Closed -> {
                            ended = m.code == BLOCK_CLOSED || m.code == 1000
                            break
                        }
                    }
                }
                if (ended) {
                    send(Event.Closed)
                    return@channelFlow
                }
                send(Event.Interrupted)
                delay(backoff(failures++))
            } finally {
                // A close frame ends the stream on Rex, so the next HELLO for this pane is not refused
                // as a second connection. A socket that already failed has nothing to close.
                if (!ws.close(1000, null)) ws.cancel()
            }
        }
        awaitClose()
    }

    private sealed interface Incoming {
        class Bytes(val bytes: ByteArray) : Incoming
        data class Closed(val code: Int) : Incoming
    }

    private fun backoff(failures: Int): Long = minOf(10_000L, 300L shl minOf(failures, 5))

    companion object {
        const val SUBPROTOCOL = "rex.data.terminal.v1"
        private const val BLOCK_CLOSED = 4000
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val REJECTS_BEFORE_INTERRUPTED = 3
        private const val REJECT_ALREADY_CONNECTED: Byte = 0x02
        private const val QUICK_RETRIES = 20
        private const val QUICK_RETRY_MS = 250L
    }
}

/**
 * Typing and sizing for one pane. Writes go in order, one in flight at a time, with whatever was
 * typed meanwhile joined into the next one. Sizes go one at a time too, and only the latest waiting
 * size is sent, so a burst of sizes while the keyboard slides in ends on the last one.
 */
class PaneInput(private val host: RexHost, private val sessionId: String, private val blockId: String, scope: CoroutineScope) {
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private val sizes = Channel<Size>(Channel.CONFLATED)

    private data class Size(val columns: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int)

    init {
        scope.launch {
            val batch = ByteArrayOutputStream()
            for (first in queue) {
                batch.reset()
                batch.write(first)
                while (true) batch.write(queue.tryReceive().getOrNull() ?: break)
                val text = batch.toByteArray().decodeToString()
                // Keys typed while the connection is down wait a little for it to come back.
                withTimeoutOrNull(15_000) { host.client.state.first { it is RexClient.State.Connected } } ?: continue
                runCatching {
                    host.client.callBlock(sessionId, blockId, "write", buildJsonObject { put("data", text) })
                }
            }
        }
        scope.launch {
            for (size in sizes) {
                host.client.state.first { it is RexClient.State.Connected }
                runCatching {
                    host.client.callBlock(sessionId, blockId, "resize", buildJsonObject {
                        put("columns", size.columns)
                        put("rows", size.rows)
                        put("cell_width_px", size.cellWidth)
                        put("cell_height_px", size.cellHeight)
                    })
                }
            }
        }
    }

    fun send(bytes: ByteArray) {
        if (bytes.isNotEmpty()) queue.trySend(bytes)
    }

    /** Sizes the pane to the phone's grid; it applies when this client owns the pane. */
    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        sizes.trySend(Size(columns, rows, cellWidth, cellHeight))
    }

    fun close() {
        queue.close()
        sizes.close()
    }
}
