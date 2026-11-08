package de.uditkaro.rax.connection

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.uditkaro.rax.BuildConfig
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.data.HostStore
import de.uditkaro.rax.rex.Alert
import de.uditkaro.rax.rex.ClientIdentity
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.rex.RexClient
import de.uditkaro.rax.rex.RexEndpoint
import de.uditkaro.rax.rex.RexHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** One saved host and the live connection behind it. */
class LiveHost(
    val host: Host,
    val endpoint: RexEndpoint,
    val rex: RexHost,
    internal val client: OkHttpClient,
    internal val scope: CoroutineScope,
)

/**
 * Keeps one [RexHost] per saved host: connected while the app is in use, or while background
 * alerts are on, and dropped otherwise so the phone does not hold sockets for nothing.
 */
class Connections(
    private val app: Application,
    private val hostStore: HostStore,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    /** Reaches the tailnet through the app's own node. */
    private val tailnetHttp: OkHttpClient,
    /** Whether the app's Tailscale node is running, which hosts on the tailnet need before they can connect. */
    private val tailnetUp: StateFlow<Boolean>,
    /** Whether tailnet hosts go through the Tailscale app on the phone, null until the settings are read. */
    private val tailscaleApp: StateFlow<Boolean?>,
    private val keepInBackground: StateFlow<Boolean>,
) {

    private val identity = ClientIdentity(
        name = "Rax",
        version = BuildConfig.VERSION_NAME,
        label = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL,
    )

    private val _live = MutableStateFlow<Map<String, LiveHost>>(emptyMap())
    val live: StateFlow<Map<String, LiveHost>> = _live.asStateFlow()

    private val _hosts = MutableStateFlow<List<Host>?>(null)

    /** The saved hosts, null until they are read. Each host here has its [LiveHost] in [live]. */
    val hosts: StateFlow<List<Host>?> = _hosts.asStateFlow()

    /** Every host's state, by host ID. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val states: StateFlow<Map<String, HostState>> = _live.flatMapLatest { map ->
        if (map.isEmpty()) flowOf(emptyMap())
        else combine(map.values.map { live -> live.rex.state.map { live.host.id to it } }) { it.toMap() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _alerts = MutableSharedFlow<Pair<Host, Alert>>(extraBufferCapacity = 64)
    val alerts: SharedFlow<Pair<Host, Alert>> = _alerts.asSharedFlow()

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(connectivity.activeNetwork != null)

    /** Whether the phone has a network at all. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val inForeground = MutableStateFlow(false)
    private var backgroundJob: Job? = null

    /**
     * Whether the app is in use or background alerts are on. Hosts stay connected while it is true,
     * and for a short grace period after.
     */
    val inUse: StateFlow<Boolean> = combine(inForeground, keepInBackground) { foreground, keep -> foreground || keep }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            combine(hostStore.hosts, tailscaleApp.filterNotNull()) { hosts, viaApp -> hosts to viaApp }
                .collect { (hosts, viaApp) -> sync(hosts, viaApp) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                inForeground.value = true
                backgroundJob?.cancel()
                startAll()
            }

            override fun onStop(owner: LifecycleOwner) {
                inForeground.value = false
                backgroundJob = scope.launch {
                    // A short grace period, so switching to another app for a moment keeps the session warm.
                    delay(BACKGROUND_GRACE_MS)
                    if (!keepInBackground.value) _live.value.values.forEach { it.rex.client.suspendConnection() }
                }
            }
        })
        scope.launch {
            keepInBackground.collect { keep ->
                if (keep) startAll()
                else if (!inForeground.value) _live.value.values.forEach { it.rex.client.suspendConnection() }
            }
        }
        // While the node is down, every attempt to reach a tailnet host fails at once. Those attempts
        // would count as failures and show the host as unreachable after the node comes up, so a
        // host that is not connected waits for the node and then starts again from its first attempt.
        scope.launch {
            tailnetUp.collect { up ->
                _live.value.values.filter { usesNode(it.host) }.forEach {
                    if (it.rex.client.state.value is RexClient.State.Connected) return@forEach
                    it.rex.client.suspendConnection()
                    if (up && active) it.rex.start()
                }
            }
        }
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
                _live.value.values.forEach { it.rex.retryNow() }
            }

            // The default network's callback hears of a loss only when no network takes its place.
            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    fun get(hostId: String): LiveHost? = _live.value[hostId]

    /** Whether [host] reaches the tailnet through the app's own node. */
    fun usesNode(host: Host): Boolean = host.tailnet && tailscaleApp.value != true

    fun retry(hostId: String) {
        val live = _live.value[hostId] ?: return
        if (usesNode(live.host) && !tailnetUp.value) return
        live.rex.start()
        live.rex.retryNow()
    }

    /** [RexHost.refresh] on every host that may connect now, and returns when all are done. */
    suspend fun refresh() = coroutineScope {
        _live.value.values.filter { canRun(it.host) }.forEach { launch { it.rex.refresh() } }
    }

    /** Reconnects now every host that does not go through Tailscale. */
    fun retryLocal() {
        _live.value.values.filter { !it.host.tailnet }.forEach { it.rex.retryNow() }
    }

    private val active: Boolean get() = inForeground.value || keepInBackground.value

    private fun canRun(host: Host): Boolean = active && (!usesNode(host) || tailnetUp.value)

    private fun startAll() {
        _live.value.values.forEach { if (canRun(it.host)) it.rex.start() }
    }

    private fun sync(hosts: List<Host>, viaApp: Boolean) {
        val next = HashMap<String, LiveHost>()
        val old = _live.value
        for (host in hosts) {
            val endpoint = (RexEndpoint.parse(host.address, host.tailnet) as? RexEndpoint.Companion.Parsed.Ok)?.endpoint ?: continue
            val client = if (host.tailnet && !viaApp) tailnetHttp else http
            val existing = old[host.id]
            if (existing != null && existing.endpoint == endpoint && existing.client === client) {
                next[host.id] = LiveHost(host, endpoint, existing.rex, client, existing.scope)
                continue
            }
            existing?.let { it.rex.stop(); it.scope.cancel() }
            val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val rex = RexHost(endpoint, client, identity, hostScope)
            // The host can be renamed later and keep this connection.
            hostScope.launch { rex.alerts.collect { _alerts.emit((_live.value[host.id]?.host ?: host) to it) } }
            hostScope.launch {
                rex.client.state.collect { st ->
                    if (st is RexClient.State.Down) Log.i(TAG, "${endpoint.display}: ${st.problem}")
                }
            }
            next[host.id] = LiveHost(host, endpoint, rex, client, hostScope)
            if (canRun(host)) rex.start()
        }
        (old.keys - next.keys).forEach { id -> old[id]?.let { it.rex.stop(); it.scope.cancel() } }
        _live.value = next
        _hosts.value = hosts
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 30_000L
        const val TAG = "RaxConnections"
    }
}
