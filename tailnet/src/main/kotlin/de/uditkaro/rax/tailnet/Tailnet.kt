package de.uditkaro.rax.tailnet

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.Proxy
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * A Tailscale node that runs inside Rax. Rax can reach the tailnet without the
 * Tailscale app and without a VPN. There is one node per process; get it with
 * [Tailnet.get].
 *
 * The node keeps its keys in the app's no-backup directory, so the user logs in
 * once and a restored backup on another phone does not clone this device.
 */
class Tailnet internal constructor(
    private val stateDir: File,
    /** Hostname used when [start] is called without one. */
    val defaultHostname: String,
    private val deviceModel: String,
    private val osVersion: String,
    private val node: Node,
    private val network: NetworkWatcher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _status = MutableStateFlow<TailnetStatus>(TailnetStatus.Stopped)
    val status: StateFlow<TailnetStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<TailnetPeer>>(emptyList())

    /** The other devices of the tailnet, sorted by [TailnetPeer.dnsName]. Empty unless running. */
    val peers: StateFlow<List<TailnetPeer>> = _peers.asStateFlow()

    /** Resolves MagicDNS names and tailnet addresses through the node. */
    val dns: Dns = TailnetDns(node)

    /** Creates sockets whose connections go through the node. */
    val socketFactory: SocketFactory = TailnetSocketFactory(node, dns)

    private val lifecycle = Mutex()
    @Volatile private var started = false

    // The first Go call loads the native library and starts the Go runtime, so
    // it happens in start(), off the main thread.
    private val nodeInit = lazy {
        node.init(object : NodeHost {
            override fun interfaces(): String = interfacesJson()

            override fun onStatus(json: String) {
                if (!started) return
                val state = try {
                    parseStatus(json)
                } catch (e: Exception) {
                    Log.e(TAG, "bad status from the node: $json", e)
                    return
                }
                _status.value = state.status
                _peers.value = state.peers
            }
        })
    }

    /**
     * Starts the node and returns once it is initialized. Login and connection
     * progress, and any failure, show up in [status]. Does nothing if started.
     */
    suspend fun start(hostname: String = defaultHostname) = withContext(dispatcher) {
        lifecycle.withLock {
            if (started) return@withLock
            nodeInit.value
            started = true
            _status.value = TailnetStatus.Starting
            network.start(node::networkChanged)
            try {
                node.start(stateDir.path, hostname, deviceModel, osVersion)
            } catch (e: Exception) {
                Log.e(TAG, "starting the node failed", e)
                network.stop()
                started = false
                _status.value = TailnetStatus.Error(e.message ?: e.toString())
            }
        }
    }

    /**
     * Requests a fresh login URL, delivered as [TailnetStatus.NeedsLogin]. The
     * node requests one by itself whenever it needs login, so this is only for
     * a URL that expired or was dismissed.
     */
    suspend fun login() = call("login") { node.login() }

    /** Logs out and removes this device's keys. [status] then shows a new login URL. */
    suspend fun logout() = call("logout") { node.logout() }

    /** Stops the node. Its keys stay, so the next [start] needs no login. */
    suspend fun stop() = withContext(dispatcher) {
        lifecycle.withLock {
            if (!started) return@withLock
            node.stop()
            network.stop()
            started = false
            _status.value = TailnetStatus.Stopped
            _peers.value = emptyList()
        }
    }

    /**
     * Returns a builder based on [base] whose connections go through the
     * tailnet. Protocols, TLS and certificate checks stay as in [base]; hosts
     * that serve HTTPS with their Tailscale certificate verify as usual.
     */
    fun clientBuilder(base: OkHttpClient): OkHttpClient.Builder =
        base.newBuilder()
            .dns(dns)
            .socketFactory(socketFactory)
            // A system HTTP proxy is not on the tailnet and cannot reach its hosts.
            .proxy(Proxy.NO_PROXY)

    private suspend fun call(what: String, block: () -> Unit) = withContext(dispatcher) {
        lifecycle.withLock {
            if (!started) throw IllegalStateException("$what: the tailnet node is not started")
            try {
                block()
            } catch (e: Exception) {
                throw TailnetException("$what failed: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "RaxTailnet"

        @Volatile private var instance: Tailnet? = null

        fun get(context: Context): Tailnet =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    Tailnet(
                        stateDir = File(app.noBackupFilesDir, "tailnet"),
                        defaultHostname = hostnameFor(Build.MODEL),
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                        osVersion = Build.VERSION.RELEASE,
                        node = GoNode,
                        network = AndroidNetworkWatcher(app),
                    ).also { instance = it }
                }
            }
    }
}

/** Returns `rax-<model>` as a valid DNS label, for example `rax-pixel-9`. */
internal fun hostnameFor(model: String): String {
    val label = model.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return "rax-$label".take(63).trimEnd('-')
}
