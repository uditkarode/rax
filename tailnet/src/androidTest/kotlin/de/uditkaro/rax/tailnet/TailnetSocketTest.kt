package de.uditkaro.rax.tailnet

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileDescriptor
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the socket over a local socketpair, without the Go node. */
@RunWith(AndroidJUnit4::class)
class TailnetSocketTest {
    private val peer = FileDescriptor()
    private var dialed: Triple<String, Int, Int>? = null
    private val node = SocketpairNode()

    private inner class SocketpairNode : UnusedNode() {
        var dial: (String, Int, Int) -> Int = { host, port, timeout ->
            dialed = Triple(host, port, timeout)
            val app = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, app, peer)
            ParcelFileDescriptor.dup(app).detachFd().also { Os.close(app) }
        }
        var cancels = 0

        override fun newDial() = object : PendingDial {
            override fun dial(host: String, port: Int, timeoutMillis: Int) = this@SocketpairNode.dial(host, port, timeoutMillis)
            override fun cancel() {
                cancels++
            }
        }
    }

    @After
    fun closePeer() {
        if (peer.valid()) Os.close(peer)
    }

    private fun connect(): Socket = TailnetSocket(node).apply {
        connect(InetSocketAddress(InetAddress.getByName("100.64.0.2"), 8080), 3000)
    }

    @Test
    fun bothDirections() {
        val socket = connect()
        assertEquals(Triple("100.64.0.2", 8080, 3000), dialed)
        assertTrue(socket.isConnected)
        assertEquals(InetAddress.getByName("100.64.0.2"), socket.inetAddress)
        assertEquals(8080, socket.port)

        socket.getOutputStream().write("ping".toByteArray())
        val buf = ByteArray(4)
        assertEquals(4, Os.read(peer, buf, 0, 4))
        assertEquals("ping", String(buf))

        Os.write(peer, "pong".toByteArray(), 0, 4)
        assertEquals(4, socket.getInputStream().read(buf))
        assertEquals("pong", String(buf))
        socket.close()
    }

    @Test
    fun readTimeout() {
        val socket = connect()
        socket.soTimeout = 200
        val start = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) { socket.getInputStream().read() }
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(190))
        // The socket stays usable after a timeout.
        Os.write(peer, byteArrayOf(7), 0, 1)
        assertEquals(7, socket.getInputStream().read())
        socket.close()
    }

    @Test
    fun closeWakesBlockedReadAndTellsPeer() {
        val socket = connect()
        val done = CountDownLatch(1)
        var result: Any? = null
        thread {
            result = try {
                socket.getInputStream().read()
            } catch (e: java.io.IOException) {
                e
            }
            done.countDown()
        }
        Thread.sleep(200)
        socket.close()
        assertTrue("read did not return after close", done.await(2, TimeUnit.SECONDS))
        assertTrue("read returned $result", result is SocketException)
        assertTrue(socket.isClosed)
        assertEquals(0, Os.read(peer, ByteArray(1), 0, 1))
    }

    @Test
    fun peerCloseIsEndOfStream() {
        val socket = connect()
        Os.close(peer)
        assertEquals(-1, socket.getInputStream().read())
        socket.close()
    }

    @Test
    fun shutdownOutputHalfCloses() {
        val socket = connect()
        socket.shutdownOutput()
        assertEquals(0, Os.read(peer, ByteArray(1), 0, 1))
        Os.write(peer, byteArrayOf(1), 0, 1)
        assertEquals(1, socket.getInputStream().read())
        socket.close()
    }

    @Test
    fun dialErrors() {
        node.dial = { _, _, _ -> throw Exception("timeout: connecting to 100.64.0.2:8080: context deadline exceeded") }
        assertThrows(SocketTimeoutException::class.java) { connect() }
        node.dial = { _, _, _ -> throw Exception("connecting to 100.64.0.2:8080: connection refused") }
        assertThrows(ConnectException::class.java) { connect() }
    }

    @Test
    fun closeCancelsDial() {
        val dialing = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        node.dial = { _, _, _ ->
            dialing.countDown()
            canceled.await(5, TimeUnit.SECONDS)
            throw Exception("context canceled")
        }
        val socket = TailnetSocket(node)
        thread {
            dialing.await()
            socket.close()
            canceled.countDown()
        }
        val e = assertThrows(SocketException::class.java) {
            socket.connect(InetSocketAddress(InetAddress.getByName("100.64.0.2"), 80))
        }
        assertEquals("Socket closed", e.message)
        assertEquals(1, node.cancels)
    }

    @Test
    fun tlsLayersOnTop() {
        val raw = connect()
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, "desktop.tail1234.ts.net", 443, true) as SSLSocket
        val handshake = thread {
            assertThrows(SSLException::class.java) { tls.startHandshake() }
        }
        // A TLS handshake record arrives, then a handshake_failure alert goes back.
        val header = ByteArray(5)
        assertEquals(5, Os.read(peer, header, 0, 5))
        assertEquals(0x16, header[0].toInt())
        Os.write(peer, byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28), 0, 7)
        handshake.join(5000)
        assertTrue(!handshake.isAlive)
        tls.close()
        assertTrue(raw.isClosed)
    }

    @Test
    fun okHttpOverTcpDescriptor() {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("hello from the tailnet").build())
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // The node's descriptor is a socketpair in production; any stream socket works here.
        node.dial = { host, port, timeout ->
            dialed = Triple(host, port, timeout)
            val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
            Os.connect(fd, InetAddress.getByName(host), port)
            ParcelFileDescriptor.dup(fd).detachFd().also { Os.close(fd) }
        }
        node.lookups = { "127.0.0.1" }
        val tailnet = Tailnet(
            stateDir = java.io.File("/unused"),
            defaultHostname = "rax-test",
            deviceModel = "test",
            osVersion = "test",
            node = node,
            network = object : NetworkWatcher {
                override fun start(onChange: (String, String) -> Unit) {}
                override fun stop() {}
            },
        )
        val client = tailnet.clientBuilder(OkHttpClient()).build()
        val url = server.url("/").newBuilder().host("desktop").build()
        client.newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals("hello from the tailnet", it.body.string())
        }
        assertEquals("127.0.0.1", dialed?.first)
        server.close()
    }
}

internal open class UnusedNode : Node {
    var lookups: (String) -> String = { throw UnsupportedOperationException() }
    override fun init(host: NodeHost) {}
    override fun start(dir: String, hostname: String, deviceModel: String, osVersion: String) = throw UnsupportedOperationException()
    override fun stop() = throw UnsupportedOperationException()
    override fun login() = throw UnsupportedOperationException()
    override fun logout() = throw UnsupportedOperationException()
    override fun lookup(name: String): String = lookups(name)
    override fun newDial(): PendingDial = throw UnsupportedOperationException()
    override fun networkChanged(interfaceName: String, gateway: String) = throw UnsupportedOperationException()
}
