package de.uditkaro.rax.rex

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RexClientTest {
    private val rex = FakeRex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = RexClient(rex.endpoint, rex.http, ClientIdentity("Rax test", "0", "jvm"), scope)

    @After
    fun tearDown() {
        client.stop()
        scope.cancel()
        rex.close()
    }

    @Test
    fun startAndSuspendFromManyThreadsLeaveOneConnection() {
        val go = CountDownLatch(1)
        val workers = (0 until 4).map { n ->
            thread {
                go.await()
                repeat(100) { if ((it + n) % 2 == 0) client.start() else client.suspendConnection() }
            }
        }
        go.countDown()
        workers.forEach { it.join() }
        client.start()
        runBlocking { withTimeout(10_000) { client.state.first { it is RexClient.State.Connected } } }

        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (rex.openControls.get() != 1 && System.nanoTime() < end) Thread.sleep(50)
        Thread.sleep(500)
        assertEquals(1, rex.openControls.get())
        assertTrue(client.state.value is RexClient.State.Connected)
    }

    @Test
    fun suspendedClientStaysStopped() {
        client.start()
        runBlocking { withTimeout(10_000) { client.state.first { it is RexClient.State.Connected } } }
        client.suspendConnection()
        Thread.sleep(1_000)
        assertEquals(RexClient.State.Stopped, client.state.value)
        val result = runCatching { runBlocking { client.call("server.version") } }
        assertEquals("not_connected", (result.exceptionOrNull() as RexException).code)
    }

    @Test
    fun resumeSendsTheLastToken() {
        client.start()
        val first = rex.nextControl()
        runBlocking { withTimeout(10_000) { client.state.first { it is RexClient.State.Connected } } }
        client.suspendConnection()
        client.start()
        val hello = rex.nextControl().hello.poll(10, TimeUnit.SECONDS)!!
        assertEquals(first.token, (hello["reconnect_token"] as JsonPrimitive).content)
    }

    @Test
    fun retryNowCountsFailedAttemptsFromTheStart() {
        val port = ServerSocket(0).use { it.localPort }
        val nobody = (RexEndpoint.parse("127.0.0.1:$port", false) as RexEndpoint.Companion.Parsed.Ok).endpoint
        val offline = RexClient(nobody, rex.http, ClientIdentity("Rax test", "0", "jvm"), scope)
        try {
            offline.start()
            runBlocking { withTimeout(10_000) { offline.state.first { it is RexClient.State.Down && it.attempt >= 2 } } }
            offline.retryNow()
            runBlocking { withTimeout(5_000) { offline.state.first { it is RexClient.State.Down && it.attempt == 0 } } }
        } finally {
            offline.stop()
        }
    }
}
