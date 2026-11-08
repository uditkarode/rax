package de.uditkaro.rax.rex

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real Rex server when `RAX_REX_URL` is set, such as the isolated one from
 * `tools/rex-probe/isolated-server.sh start` at `http://127.0.0.1:7297`. Skipped otherwise.
 */
class LiveServerTest {
    private val address = System.getenv("RAX_REX_URL")

    @Test
    fun createsASessionStreamsItAndTypesIntoIt() = runBlocking {
        assumeTrue("RAX_REX_URL not set", address != null)
        val endpoint = (RexEndpoint.parse(address!!, false) as RexEndpoint.Companion.Parsed.Ok).endpoint
        val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = RexHost(endpoint, http, ClientIdentity("Rax test", "0", "jvm"), scope)
        host.start()
        try {
            withTimeout(15_000) { host.state.first { it.ready } }
            val welcome = host.state.value.welcome!!
            assertEquals(RexClient.API_REVISION, welcome.apiRevision)

            val sessionId = host.createSession("rax-live-test").first
            val session = withTimeout(15_000) {
                host.state.first { s -> s.session(sessionId)?.view?.tabs?.firstOrNull()?.primaryBlockId != null }.session(sessionId)!!
            }
            val blockId = session.view!!.tabs.first().primaryBlockId!!
            val facts = withTimeout(15_000) { host.state.first { it.blocks[blockId]?.programName != null }.blocks[blockId]!! }
            assertNotNull(facts.programName)

            val stream = TerminalStream(host, sessionId, blockId)
            val input = PaneInput(host, sessionId, blockId, scope)
            val seen = StringBuilder()
            var started = false
            val reader = scope.launch {
                stream.events().takeWhile { it !is TerminalStream.Event.Closed }.collect { e ->
                    when (e) {
                        is TerminalStream.Event.Started -> started = true
                        is TerminalStream.Event.Output -> synchronized(seen) { seen.append(e.bytes.decodeToString()) }
                        else -> {}
                    }
                }
            }
            withTimeout(15_000) { while (!started) kotlinx.coroutines.delay(50) }
            input.send("echo rax-says-$((6*7))\n".toByteArray())
            withTimeout(15_000) { while (!synchronized(seen) { seen.contains("rax-says-42") }) kotlinx.coroutines.delay(50) }

            // Sent files arrive whole: the paths Rex types finish a wc command that counts their bytes.
            val big = UploadFile("big file.bin", 3L * 1024 * 1024) { java.io.ByteArrayInputStream(ByteArray(3 * 1024 * 1024) { it.toByte() }) }
            val small = UploadFile("small.txt", 6) { "hello\n".byteInputStream() }
            input.send("wc -c ".toByteArray())
            var sent = 0L
            host.upload(sessionId, blockId, listOf(big, small)) { sent = it }
            assertEquals(big.size + small.size, sent)
            input.send("\n".toByteArray())
            withTimeout(15_000) { while (!synchronized(seen) { seen.contains("3145728") && seen.contains("small.txt") }) kotlinx.coroutines.delay(50) }
            host.upload(sessionId, blockId, listOf(UploadFile("one.txt", 3) { "abc".byteInputStream() })) {}
            withTimeout(15_000) { while (!synchronized(seen) { seen.contains("one.txt") }) kotlinx.coroutines.delay(50) }

            host.endSession(sessionId)
            withTimeout(15_000) { host.state.first { it.session(sessionId) == null } }
            withTimeout(15_000) { reader.join() }
            assertTrue(started)
        } finally {
            host.stop()
            scope.cancel()
        }
    }
}
