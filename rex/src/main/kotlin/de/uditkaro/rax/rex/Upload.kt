package de.uditkaro.rax.rex

import java.io.InputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** A file to send to the computer. [size] is its exact length, and [open] reads it from the start. */
class UploadFile(val name: String, val size: Long, val open: () -> InputStream)

/**
 * Sends [files] to the computer that runs the pane. Once all have arrived, the pane gets their paths
 * as a paste, in order. [progress] gets the bytes sent so far, on another thread. Throws
 * [RexException] when Rex refuses or the transfer breaks, and then Rex types nothing and keeps
 * nothing. Cancelling ends the transfer the same way.
 */
suspend fun RexHost.upload(sessionId: String, blockId: String, files: List<UploadFile>, progress: (Long) -> Unit) {
    val batch = if (files.size > 1) UUID.randomUUID().toString() else null
    val streams = ArrayList<ByteStream>(files.size)
    try {
        var sent = 0L
        for ((i, file) in files.withIndex()) {
            streams.forEach { it.checkNotFailed() }
            val reply = client.callBlock(sessionId, blockId, "upload_begin", buildJsonObject {
                put("ref", "upload:${UUID.randomUUID()}")
                put("name", file.name)
                put("size", file.size)
                if (batch != null) {
                    put("batch", batch)
                    put("index", i)
                    put("count", files.size)
                }
            })
            val ticket = reply.str("ticket")?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
                ?: throw RexException("bad_reply", 0, "upload_begin returned no ticket")
            val stream = ByteStream(this, ticket)
            streams += stream
            stream.handshake()
            val before = sent
            stream.send(file) { progress(before + it) }
            sent += file.size
        }
        // Rex ends each stream with OK once its file has arrived, and the last one once it has typed the paths.
        streams.forEach { it.finished() }
    } finally {
        // Closing a stream that Rex has not ended yet stops the whole batch.
        streams.forEach { it.close() }
    }
}

/** One file's `rex.data.bytes.v1` data connection. */
private class ByteStream(host: RexHost, ticket: ByteArray) {
    private val first = CompletableDeferred<ByteArray?>()
    private val ended = CompletableDeferred<Int>()

    private val ws: WebSocket = host.httpClient.newWebSocket(
        Request.Builder().url(host.endpoint.dataUrl).header("Sec-WebSocket-Protocol", SUBPROTOCOL).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send((byteArrayOf(0x01) + ticket).toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                first.complete(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                end(code)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(code)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(ABNORMAL)
        },
    )

    private fun end(code: Int) {
        ended.complete(code)
        first.complete(null)
    }

    suspend fun handshake() {
        val reply = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { first.await() }
        if (reply == null || reply.isEmpty() || reply[0] != WELCOME) {
            throw RexException("upload_rejected", 0, "data connection refused: ${reply?.firstOrNull()}")
        }
    }

    /** Sends [file] and the empty message that ends it. [progress] gets the bytes that have left the phone. */
    suspend fun send(file: UploadFile, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        var read = 0L
        file.open().use { input ->
            val buffer = ByteArray(MAX_MESSAGE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                read += n
                if (read > file.size) throw RexException("size_changed", 0, "${file.name} is larger than ${file.size} bytes")
                // OkHttp holds unsent messages in memory, and closes the socket when they pass 16 MiB.
                while (ws.queueSize() > MAX_QUEUED) {
                    checkOpen()
                    progress(read - n - ws.queueSize())
                    delay(QUEUE_POLL_MS)
                }
                if (!ws.send(buffer.toByteString(0, n))) throw failure(null)
            }
        }
        if (read != file.size) throw RexException("size_changed", 0, "${file.name} has $read bytes, not ${file.size}")
        if (!ws.send(ByteString.EMPTY)) throw failure(null)
        while (ws.queueSize() > 0) {
            checkOpen()
            progress(read - ws.queueSize())
            delay(QUEUE_POLL_MS)
        }
        progress(read)
    }

    /** Throws if Rex ended the stream before the whole file went out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun checkOpen() {
        if (ended.isCompleted) throw failure(ended.getCompleted())
    }

    /** Throws if Rex ended the stream with anything but OK. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun checkNotFailed() {
        if (ended.isCompleted && ended.getCompleted() != NORMAL) throw failure(ended.getCompleted())
    }

    /** Waits for Rex to end the stream, and throws unless it ended it with OK. */
    suspend fun finished() {
        val code = withTimeoutOrNull(END_TIMEOUT_MS) { ended.await() }
        if (code != NORMAL) throw failure(code)
    }

    fun close() {
        if (!ws.close(NORMAL, null)) ws.cancel()
    }

    private fun failure(code: Int?) = RexException("upload_failed", code ?: 0, "upload ended with close code $code")

    companion object {
        const val SUBPROTOCOL = "rex.data.bytes.v1"
        const val WELCOME = 0x81.toByte()
        const val NORMAL = 1000
        const val ABNORMAL = 1006

        /** Rex closes a stream whose message is larger. */
        const val MAX_MESSAGE = 64 * 1024
        const val MAX_QUEUED = 1024 * 1024L
        const val QUEUE_POLL_MS = 10L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        /** How long Rex may take to type the paths after the last file. */
        const val END_TIMEOUT_MS = 30_000L
    }
}
