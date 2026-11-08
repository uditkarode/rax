package de.uditkaro.rax.rex

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStreamTest {
    private val rex = FakeRex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = RexHost(rex.endpoint, rex.http, ClientIdentity("Rax test", "0", "jvm"), scope)

    init {
        rex.sessions += "session:1" to "work"
        rex.views["session:1"] = FakeRex.view(1, null to listOf("block:a"))
    }

    @After
    fun tearDown() {
        host.stop()
        scope.cancel()
        rex.close()
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!check()) {
            if (System.nanoTime() > end) throw AssertionError(what)
            Thread.sleep(20)
        }
    }

    private fun collect(): CopyOnWriteArrayList<TerminalStream.Event> {
        val events = CopyOnWriteArrayList<TerminalStream.Event>()
        scope.launch {
            TerminalStream(host, "session:1", "block:a").events()
                .takeWhile { it !is TerminalStream.Event.Closed }
                .collect { events += it }
        }
        return events
    }

    @Test
    fun reconnectRequiredStartsANewStream() {
        val streams = AtomicInteger()
        rex.onData = { data ->
            data.welcome(nonce = 5)
            data.send("screen".toByteArray())
            if (streams.incrementAndGet() == 1) data.ws.close(1013, "data reconnect required")
        }
        host.start()
        val events = collect()
        val first = rex.nextData()
        val second = rex.nextData()
        assertEquals(0x01.toByte(), first.first.poll(10, TimeUnit.SECONDS)!![0])
        assertEquals("a new stream starts with HELLO", 0x01.toByte(), second.first.poll(10, TimeUnit.SECONDS)!![0])
        waitUntil("two streams") { events.count { it is TerminalStream.Event.Started } == 2 }
        assertEquals(2, rex.requests.count { it == "data.connect" })
    }

    @Test
    fun ticketOfAnotherLengthIsTriedAgain() {
        var tickets = 0
        rex.onRequest = { control, r ->
            if (r.method == "data.connect" && tickets++ == 0) {
                control.reply(r, buildJsonObject { put("ticket", FakeRex.ticket(32)) })
                true
            } else {
                false
            }
        }
        rex.onData = { data ->
            data.welcome(nonce = 5)
            data.send("screen".toByteArray())
        }
        host.start()
        val events = collect()
        val hello = rex.nextData().first.poll(10, TimeUnit.SECONDS)!!
        assertEquals(17, hello.size)
        waitUntil("stream started") { events.any { it is TerminalStream.Event.Started } }
        assertEquals(2, rex.requests.count { it == "data.connect" })
    }

    @Test
    fun largePasteIsSplitToTheMessageLimit() {
        rex.maxMessageBytes = 65_536
        val writes = CopyOnWriteArrayList<String>()
        rex.onRequest = { control, r ->
            if (r.method == "$TERMINAL.write") {
                writes += (r.payload["args"]!!.jsonObject["data"] as JsonPrimitive).content
                control.reply(r, buildJsonObject { put("bytes_written", 1) })
                true
            } else {
                false
            }
        }
        host.start()
        runBlocking { withTimeout(10_000) { host.state.first { it.ready } } }
        val paste = buildString { repeat(30_000) { append("ab\t\"é\u0001🙂") } }
        PaneInput(host, "session:1", "block:a", scope).send(paste.toByteArray())
        waitUntil("whole paste") { writes.sumOf { it.length } == paste.length }
        assertTrue(writes.size > 1)
        assertEquals(paste, writes.joinToString(""))
        assertTrue(rex.requestBytes.max() <= rex.maxMessageBytes)
    }

    @Test
    fun writeWaitsForTheSessionList() {
        val order = CopyOnWriteArrayList<String>()
        lateinit var bootstrap: FakeRex.Request
        rex.onRequest = { _, r ->
            when (r.method) {
                "session.bootstrap" -> {
                    bootstrap = r
                    order += "bootstrap"
                    true
                }
                "$TERMINAL.write" -> {
                    order += "write"
                    false
                }
                else -> false
            }
        }
        host.start()
        val control = rex.nextControl()
        waitUntil("bootstrap asked") { "bootstrap" in order }
        PaneInput(host, "session:1", "block:a", scope).send("y\r".toByteArray())
        Thread.sleep(300)
        order += "answered"
        control.answer(bootstrap)
        waitUntil("write") { "write" in order }
        assertEquals(listOf("bootstrap", "answered", "write"), order)
    }
}
