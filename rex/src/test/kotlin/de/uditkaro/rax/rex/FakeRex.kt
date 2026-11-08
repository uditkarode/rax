package de.uditkaro.rax.rex

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * A Rex server for tests. By default it answers from [sessions], [views] and [facts]. A test can
 * answer a request itself with [onRequest], and drive each connection through [controls].
 */
class FakeRex : Closeable {
    private val server = MockWebServer()

    /** Session IDs in order, each with its label. */
    val sessions = CopyOnWriteArrayList<Pair<String, String>>()
    /** Each session's view as `session.attach` and `session.view` return it. */
    val views = ConcurrentHashMap<String, JsonObject>()
    /** Replies to pane methods, by pane ID and method name, such as `program_status`. */
    val facts = ConcurrentHashMap<Pair<String, String>, JsonObject>()
    @Volatile var instanceId = "instance-1"
    @Volatile var maxMessageBytes = 1_048_576
    @Volatile var resumed = false

    /** Answers a request before the defaults do. Returns true if it answered, or will later. */
    @Volatile var onRequest: (Control, Request) -> Boolean = { _, _ -> false }

    /** What the data endpoint does with each new connection. */
    @Volatile var onData: (Data) -> Unit = {}

    val controls = LinkedBlockingQueue<Control>()
    val datas = LinkedBlockingQueue<Data>()
    val openControls = AtomicInteger()
    val requests = CopyOnWriteArrayList<String>()
    /** The size of each request message, in bytes. */
    val requestBytes = CopyOnWriteArrayList<Int>()

    val http: OkHttpClient = OkHttpClient.Builder().build()
    val endpoint: RexEndpoint

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/control" -> MockResponse.Builder().webSocketUpgrade(Control()).build()
                "/api/data" -> MockResponse.Builder().webSocketUpgrade(Data()).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        endpoint = (RexEndpoint.parse("http://127.0.0.1:${server.port}", false) as RexEndpoint.Companion.Parsed.Ok).endpoint
    }

    fun nextControl(): Control = controls.poll(10, TimeUnit.SECONDS) ?: error("no control connection")
    fun nextData(): Data = datas.poll(10, TimeUnit.SECONDS) ?: error("no data connection")

    class Request(val id: String, val method: String, val payload: JsonObject)

    inner class Control : WebSocketListener() {
        lateinit var ws: WebSocket
        val hello = LinkedBlockingQueue<JsonObject>()
        val token = "token-" + System.nanoTime()
        private var seq = 0L
        private var open = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            synchronized(this) {
                open = true
                openControls.incrementAndGet()
            }
            controls += this
        }

        private fun ended() = synchronized(this) {
            if (open) openControls.decrementAndGet()
            open = false
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = RexJson.parseToJsonElement(text).jsonObject
            when (m.str("type")) {
                "hello" -> {
                    hello += m
                    send(buildJsonObject {
                        put("type", "welcome")
                        put("protocol", RexClient.CONTROL_SUBPROTOCOL)
                        put("limits", buildJsonObject { put("max_message_bytes", maxMessageBytes) })
                        put("client", buildJsonObject {
                            put("id", "client:test")
                            put("reconnect_token", token)
                            if (resumed) put("resumed", true)
                        })
                        put("api_revision", RexClient.API_REVISION)
                        put("api_server_revision", RexClient.API_REVISION)
                    })
                }
                "request" -> {
                    requestBytes += text.toByteArray().size
                    val request = Request(m.str("id")!!, m.str("method")!!, m["payload"] as? JsonObject ?: JsonObject(emptyMap()))
                    requests += request.method
                    if (!onRequest(this, request)) answer(request)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ended()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ended()
        }

        @Synchronized fun send(m: JsonObject) {
            ws.send(m.toString())
        }

        /** Ends the connection the way Rex does when the phone stops answering pings. */
        fun drop() {
            ws.close(1001, "keepalive timeout")
        }

        @Synchronized fun event(type: String, payload: JsonObject) {
            seq++
            ws.send(buildJsonObject {
                put("type", "event")
                put("seq", seq)
                put("payload", buildJsonObject { put("type", type); put("payload", payload) })
            }.toString())
        }

        /** A `block_event` in [sessionId], as Rex wraps it. */
        fun blockEvent(sessionId: String, blockId: String, name: String, payload: JsonObject) =
            sessionEvent(sessionId, "block_event", buildJsonObject {
                put("block_id", blockId)
                put("creator_name", TERMINAL)
                put("name", name)
                put("payload", payload)
            })

        fun sessionEvent(sessionId: String, type: String, payload: JsonObject) = event("session_event", buildJsonObject {
            put("session_id", sessionId)
            put("event", buildJsonObject { put("type", type); put("payload", payload) })
        })

        @Synchronized fun reply(request: Request, payload: JsonElement) {
            ws.send(buildJsonObject {
                put("type", "response")
                put("id", request.id)
                put("seq", seq)
                put("payload", payload)
            }.toString())
        }

        @Synchronized fun fail(request: Request, code: String, status: Int) {
            ws.send(buildJsonObject {
                put("type", "response")
                put("id", request.id)
                put("seq", seq)
                put("error", buildJsonObject { put("code", code); put("status", status); put("message", code) })
            }.toString())
        }

        fun answer(r: Request) {
            val sessionId = r.payload.str("session_id")
            val blockId = r.payload.str("block_id")
            when {
                r.method == "session.bootstrap" -> reply(r, buildJsonObject {
                    put("attach_all", buildJsonObject {
                        put("sessions", buildJsonArray {
                            sessions.forEach { (id, label) -> add(buildJsonObject { put("session_id", id); put("label", label) }) }
                        })
                    })
                    put("server_status", buildJsonObject { put("instance_id", instanceId) })
                })
                r.method == "session.attach" -> reply(r, buildJsonObject { put("view", views[sessionId] ?: JsonObject(emptyMap())) })
                r.method == "session.view" -> reply(r, views[sessionId] ?: JsonObject(emptyMap()))
                r.method == "server.version" -> reply(r, buildJsonObject { put("rex_version", "test") })
                r.method == "data.connect" -> reply(r, buildJsonObject { put("ticket", ticket(16)) })
                r.method.startsWith("$TERMINAL.") ->
                    reply(r, facts[blockId.orEmpty() to r.method.removePrefix("$TERMINAL.")] ?: JsonObject(emptyMap()))
                else -> reply(r, JsonObject(emptyMap()))
            }
        }
    }

    inner class Data : WebSocketListener() {
        lateinit var ws: WebSocket
        /** The client's first message: HELLO or RESUME. */
        val first = LinkedBlockingQueue<ByteArray>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            datas += this
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (first.isEmpty()) {
                first += bytes.toByteArray()
                onData(this)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        fun welcome(nonce: Long) {
            ws.send(
                ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                    .put(0x81.toByte()).put(ByteArray(16) { 7 }).put(ByteArray(3)).putInt(60_000).putLong(nonce)
                    .array().toByteString(),
            )
        }

        fun send(bytes: ByteArray) {
            ws.send(bytes.toByteString())
        }

        fun reject(reason: Int) {
            ws.send(byteArrayOf(0x82.toByte(), reason.toByte()).toByteString())
        }
    }

    override fun close() {
        server.close()
    }

    companion object {
        fun ticket(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { it.toByte() })

        /** A view with one tab whose layers hold [layers] panes; a layer named "minimized" is minimized. */
        fun view(revision: Long, vararg layers: Pair<String?, List<String>>): JsonObject = buildJsonObject {
            put("revision", revision)
            put("windows", buildJsonArray {
                add(buildJsonObject {
                    put("window_id", "window:1")
                    put("layers", buildJsonArray {
                        layers.forEachIndexed { i, (state, ids) ->
                            add(buildJsonObject {
                                put("layer_id", "layer:$i")
                                put("kind", if (i == 0) "tiled" else "floating")
                                if (state != null) put("state", state)
                                put("blocks", buildJsonArray {
                                    ids.forEach { id -> add(buildJsonObject { put("block_id", id); put("creator_name", TERMINAL) }) }
                                })
                            })
                        }
                    })
                })
            })
        }

        fun status(vararg records: Pair<String, String>): JsonObject = buildJsonObject {
            put("records", buildJsonArray {
                records.forEach { (id, state) -> add(buildJsonObject { put("id", id); put("state", state) }) }
            })
        }
    }
}
