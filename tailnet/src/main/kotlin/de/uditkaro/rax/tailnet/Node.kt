package de.uditkaro.rax.tailnet

import de.uditkaro.rax.tailnet.libtailnet.DialHandle
import de.uditkaro.rax.tailnet.libtailnet.Host
import de.uditkaro.rax.tailnet.libtailnet.Libtailnet

/** The Go node. Every call blocks; none may run on the main thread. */
internal interface Node {
    fun init(host: NodeHost)
    fun start(dir: String, hostname: String, deviceModel: String, osVersion: String)
    fun stop()
    fun login()
    fun logout()

    /** Returns the addresses of [name], separated by spaces. */
    fun lookup(name: String): String
    fun newDial(): PendingDial
    fun networkChanged(interfaceName: String, gateway: String)
}

internal interface NodeHost {
    fun interfaces(): String

    /** Called from a Go thread. Must not call back into [Node]. */
    fun onStatus(json: String)
}

internal interface PendingDial {
    /** Returns a file descriptor that the caller owns. */
    fun dial(host: String, port: Int, timeoutMillis: Int): Int

    /** Aborts [dial] from another thread. */
    fun cancel()
}

internal object GoNode : Node {
    override fun init(host: NodeHost) {
        Libtailnet.init(object : Host {
            override fun interfaces(): String = host.interfaces()
            override fun onStatus(status: String) = host.onStatus(status)
        })
    }

    override fun start(dir: String, hostname: String, deviceModel: String, osVersion: String) =
        Libtailnet.start(dir, hostname, deviceModel, osVersion)

    override fun stop() = Libtailnet.stop()
    override fun login() = Libtailnet.login()
    override fun logout() = Libtailnet.logout()
    override fun lookup(name: String): String = Libtailnet.lookup(name)

    override fun newDial(): PendingDial {
        val handle: DialHandle = Libtailnet.newDialHandle()
        return object : PendingDial {
            override fun dial(host: String, port: Int, timeoutMillis: Int): Int =
                handle.dial(host, port, timeoutMillis)

            override fun cancel() = handle.cancel()
        }
    }

    override fun networkChanged(interfaceName: String, gateway: String) =
        Libtailnet.networkChanged(interfaceName, gateway)
}
