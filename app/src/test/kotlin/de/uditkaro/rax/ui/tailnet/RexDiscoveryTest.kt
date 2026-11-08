package de.uditkaro.rax.ui.tailnet

import de.uditkaro.rax.tailnet.TailnetPeer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RexDiscoveryTest {
    private val peer = TailnetPeer("studio.t.ts.net.", "studio-rex", "macOS", true, emptyList())
    private val https = "https://studio.t.ts.net/api/control"
    private val lan = "http://studio.t.ts.net:7297/api/control"

    /** Answers each URL with a status and body; a URL that is not listed fails to connect. */
    private fun client(answers: Map<String, Pair<Int, String>>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val (code, body) = answers[chain.request().url.toString()] ?: throw IOException("unreachable")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun find(answers: Map<String, Pair<Int, String>>) = runBlocking { findRexServer(client(answers), peer) }

    @Test
    fun httpsComesFirst() {
        assertEquals(RexServer("studio", "studio.t.ts.net"), find(mapOf(https to (426 to ""), lan to (426 to ""))))
    }

    @Test
    fun port7297WhenHttpsIsNotRex() {
        assertEquals(RexServer("studio", "studio.t.ts.net:7297"), find(mapOf(https to (404 to ""), lan to (426 to ""))))
    }

    @Test
    fun refusalIsListed() {
        val refused = 403 to """{"error":"tailscale user bob@example.com is not allowed"}"""
        assertEquals(RexServer("studio", "studio.t.ts.net", refused = true), find(mapOf(https to refused)))
        assertEquals(RexServer("studio", "studio.t.ts.net:7297", refused = true), find(mapOf(lan to refused)))
    }

    @Test
    fun aUsableListenerWinsOverARefusal() {
        val refused = 403 to """{"error":"not allowed"}"""
        assertEquals(RexServer("studio", "studio.t.ts.net:7297"), find(mapOf(https to refused, lan to (426 to ""))))
    }

    @Test
    fun otherAnswersAreNotRex() {
        assertNull(find(emptyMap()))
        assertNull(find(mapOf(https to (403 to "<html>Forbidden</html>"), lan to (404 to ""))))
        assertNull(find(mapOf(https to (403 to """{"message":"no"}"""))))
        assertNull(find(mapOf(https to (200 to "{}"))))
    }

    @Test
    fun cancellingStopsTheRequests() {
        val waiting = CountDownLatch(2)
        val cancelled = CountDownLatch(2)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                waiting.countDown()
                while (!chain.call().isCanceled()) Thread.sleep(10)
                cancelled.countDown()
                throw IOException("Canceled")
            }
            .build()
        runBlocking {
            val job = launch(Dispatchers.Default) { findRexServer(client, peer) }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            job.cancel()
        }
        assertTrue("the requests kept running", cancelled.await(5, TimeUnit.SECONDS))
    }
}
