package de.uditkaro.rax.tailnet

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketImpl
import java.net.SocketOptions
import java.net.SocketTimeoutException
import java.util.Objects
import javax.net.SocketFactory
import okhttp3.Dns

internal class TailnetSocketFactory(private val node: Node, private val dns: Dns) : SocketFactory() {
    override fun createSocket(): Socket = TailnetSocket(node)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket(dns.lookup(host).first(), port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        requireNoLocalAddress(localHost, localPort)
        return createSocket(host, port)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        requireNoLocalAddress(localAddress, localPort)
        return createSocket(address, port)
    }

    private fun requireNoLocalAddress(address: InetAddress?, port: Int) {
        if ((address != null && !address.isAnyLocalAddress) || port != 0) {
            throw SocketException("tailnet sockets cannot bind to a local address")
        }
    }
}

/**
 * A TCP connection through the node. The node dials the target and hands back
 * one end of a Unix socketpair whose other end it splices to the connection, so
 * this socket reads and writes a real file descriptor. TLS layered on top (by
 * OkHttp or Conscrypt) works as on any socket.
 */
internal class TailnetSocket private constructor(private val impl: TailnetSocketImpl) : Socket(impl) {
    constructor(node: Node) : this(TailnetSocketImpl(node))

    init {
        impl.owner = this
    }
}

internal class TailnetSocketImpl(private val node: Node) : SocketImpl() {
    var owner: Socket? = null

    private val lock = Any()
    private var dial: PendingDial? = null
    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var closed = false
    @Volatile private var inputShutdown = false
    @Volatile private var readTimeoutMillis = 0
    private val options = HashMap<Int, Any?>()

    private val input = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = readFd(b, off, len)
        override fun available(): Int = this@TailnetSocketImpl.available()
        override fun close() = closeOwner()
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = writeFd(b, off, len)
        override fun close() = closeOwner()
    }

    override fun create(stream: Boolean) {
        if (!stream) throw SocketException("tailnet sockets are stream sockets only")
    }

    override fun connect(host: String, port: Int) = connect(InetSocketAddress(host, port), 0)

    override fun connect(address: InetAddress, port: Int) = connect(InetSocketAddress(address, port), 0)

    override fun connect(address: SocketAddress, timeout: Int) {
        val target = address as? InetSocketAddress
            ?: throw IllegalArgumentException("unsupported address type ${address.javaClass.name}")
        val host = target.address?.hostAddress ?: target.hostString
        val pending = synchronized(lock) {
            if (closed) throw SocketException("Socket closed")
            check(pfd == null && dial == null) { "already connected" }
            node.newDial().also { dial = it }
        }
        val fd = try {
            pending.dial(host, target.port, timeout)
        } catch (e: Exception) {
            throw when {
                closed -> SocketException("Socket closed")
                e.message.orEmpty().startsWith("timeout:") -> SocketTimeoutException(e.message)
                else -> ConnectException(e.message)
            }.apply { initCause(e) }
        } finally {
            synchronized(lock) { dial = null }
        }
        synchronized(lock) {
            val adopted = ParcelFileDescriptor.adoptFd(fd)
            if (closed) {
                adopted.close()
                throw SocketException("Socket closed")
            }
            pfd = adopted
            this.fd = adopted.fileDescriptor
            this.address = target.address
            this.port = target.port
        }
    }

    override fun close() {
        val (pending, descriptor) = synchronized(lock) {
            if (closed) return
            closed = true
            dial to pfd
        }
        pending?.cancel()
        if (descriptor != null) {
            // Shutdown wakes threads blocked in read or poll and tells the node at once.
            try {
                Os.shutdown(descriptor.fileDescriptor, OsConstants.SHUT_RDWR)
            } catch (_: ErrnoException) {
            }
            descriptor.close()
        }
    }

    private fun closeOwner() {
        owner?.close() ?: close()
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output

    override fun available(): Int {
        if (inputShutdown) return 0
        return if (poll(openFd(), 0)) 1 else 0
    }

    override fun shutdownInput() {
        inputShutdown = true
    }

    override fun shutdownOutput() = io { Os.shutdown(openFd(), OsConstants.SHUT_WR) }

    private fun readFd(b: ByteArray, off: Int, len: Int): Int {
        Objects.checkFromIndexSize(off, len, b.size)
        if (len == 0) return 0
        if (inputShutdown) return -1
        val fd = openFd()
        val timeout = readTimeoutMillis
        if (timeout > 0) awaitReadable(fd, timeout)
        val n = io { Os.read(fd, b, off, len) }
        if (n == 0) {
            if (closed) throw SocketException("Socket closed")
            return -1
        }
        return n
    }

    private fun writeFd(b: ByteArray, off: Int, len: Int) {
        Objects.checkFromIndexSize(off, len, b.size)
        var offset = off
        var remaining = len
        while (remaining > 0) {
            val n = io { Os.write(openFd(), b, offset, remaining) }
            offset += n
            remaining -= n
        }
    }

    private fun awaitReadable(fd: FileDescriptor, timeoutMillis: Int) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remaining <= 0) throw SocketTimeoutException("Read timed out")
            if (poll(fd, remaining)) return
        }
    }

    /** Returns whether [fd] is readable (or at EOF or in error) within [timeoutMillis]. */
    private fun poll(fd: FileDescriptor, timeoutMillis: Int): Boolean {
        val pollFd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        while (true) {
            try {
                return io { Os.poll(arrayOf(pollFd), timeoutMillis) } > 0
            } catch (e: SocketException) {
                if ((e.cause as? ErrnoException)?.errno != OsConstants.EINTR || closed) throw e
            }
        }
    }

    private fun openFd(): FileDescriptor {
        if (closed) throw SocketException("Socket closed")
        return synchronized(lock) { pfd }?.fileDescriptor ?: throw SocketException("Socket is not connected")
    }

    /**
     * Runs an [Os] call and turns its failures into [SocketException]. A close on
     * another thread interrupts a blocked call with an [InterruptedIOException].
     */
    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (e: ErrnoException) {
            throw (if (closed) SocketException("Socket closed") else SocketException(e.message)).apply { initCause(e) }
        } catch (e: InterruptedIOException) {
            if (!closed) throw e
            throw SocketException("Socket closed").apply { initCause(e) }
        }

    override fun setOption(optID: Int, value: Any?) {
        when (optID) {
            SocketOptions.SO_TIMEOUT -> readTimeoutMillis = value as Int
            // Other options have no effect on a socketpair; remember them for getOption.
            else -> synchronized(options) { options[optID] = value }
        }
    }

    override fun getOption(optID: Int): Any? = when (optID) {
        SocketOptions.SO_TIMEOUT -> readTimeoutMillis
        SocketOptions.SO_BINDADDR -> InetAddress.getByAddress(ByteArray(4))
        SocketOptions.TCP_NODELAY, SocketOptions.SO_KEEPALIVE, SocketOptions.SO_REUSEADDR,
        SocketOptions.SO_OOBINLINE -> synchronized(options) { options[optID] } ?: false
        SocketOptions.SO_LINGER -> synchronized(options) { options[optID] } ?: false
        SocketOptions.SO_SNDBUF, SocketOptions.SO_RCVBUF -> synchronized(options) { options[optID] } ?: 65536
        SocketOptions.IP_TOS -> synchronized(options) { options[optID] } ?: 0
        else -> throw SocketException("unsupported socket option $optID")
    }

    override fun sendUrgentData(data: Int) = throw SocketException("urgent data is not supported")

    override fun bind(host: InetAddress, port: Int) = throw SocketException("tailnet sockets cannot bind")

    override fun listen(backlog: Int) = throw SocketException("tailnet sockets cannot listen")

    override fun accept(s: SocketImpl) = throw SocketException("tailnet sockets cannot accept")
}
