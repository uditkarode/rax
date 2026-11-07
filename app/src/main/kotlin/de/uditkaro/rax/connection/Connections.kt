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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** One saved host and the live connection behind it. */
class LiveHost(val host: Host, val endpoint: RexEndpoint, val rex: RexHost, internal val scope: CoroutineScope)

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
    private val keepInBackground: StateFlow<Boolean>,
) {

    private val identity = ClientIdentity(
        name = "Rax",
        version = BuildConfig.VERSION_NAME,
        label = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL,
    )

    private val _live = MutableStateFlow<Map<String, LiveHost>>(emptyMap())
    val live: StateFlow<Map<String, LiveHost>> = _live.asStateFlow()

    val hosts: StateFlow<List<Host>?> = hostStore.hosts.stateIn(scope, SharingStarted.Eagerly, null)

    /** Every host's state, by host ID. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val states: StateFlow<Map<String, HostState>> = _live.flatMapLatest { map ->
        if (map.isEmpty()) flowOf(emptyMap())
        else combine(map.values.map { live -> live.rex.state.map { live.host.id to it } }) { it.toMap() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _alerts = MutableSharedFlow<Pair<Host, Alert>>(extraBufferCapacity = 64)
    val alerts: SharedFlow<Pair<Host, Alert>> = _alerts.asSharedFlow()

    private var inForeground = false
    private var backgroundJob: Job? = null

    init {
        scope.launch { hostStore.hosts.collect { sync(it) } }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                inForeground = true
                backgroundJob?.cancel()
                startAll()
            }

            override fun onStop(owner: LifecycleOwner) {
                inForeground = false
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
                else if (!inForeground) _live.value.values.forEach { it.rex.client.suspendConnection() }
            }
        }
        // While the node is down, every attempt to reach a tailnet host fails at once. Those attempts
        // would count as failures and show the host as unreachable after the node comes up, so a
        // host that is not connected waits for the node and then starts again from its first attempt.
        scope.launch {
            tailnetUp.collect { up ->
                _live.value.values.filter { it.host.tailnet }.forEach {
                    if (it.rex.client.state.value is RexClient.State.Connected) return@forEach
                    it.rex.client.suspendConnection()
                    if (up && active) it.rex.start()
                }
            }
        }
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _live.value.values.forEach { it.rex.retryNow() }
            }
        })
    }

    fun get(hostId: String): LiveHost? = _live.value[hostId]

    fun retry(hostId: String) {
        val live = _live.value[hostId] ?: return
        if (live.host.tailnet && !tailnetUp.value) return
        live.rex.start()
        live.rex.retryNow()
    }

    /** Reconnects now every host that does not go through Tailscale. */
    fun retryLocal() {
        _live.value.values.filter { !it.host.tailnet }.forEach { it.rex.retryNow() }
    }

    private val active: Boolean get() = inForeground || keepInBackground.value

    private fun canRun(host: Host): Boolean = active && (!host.tailnet || tailnetUp.value)

    private fun startAll() {
        _live.value.values.forEach { if (canRun(it.host)) it.rex.start() }
    }

    private fun sync(hosts: List<Host>) {
        val next = HashMap<String, LiveHost>()
        val old = _live.value
        for (host in hosts) {
            val endpoint = (RexEndpoint.parse(host.address, host.tailnet) as? RexEndpoint.Companion.Parsed.Ok)?.endpoint ?: continue
            val client = if (host.tailnet) tailnetHttp else http
            val existing = old[host.id]
            if (existing != null && existing.endpoint == endpoint && existing.host.tailnet == host.tailnet) {
                next[host.id] = LiveHost(host, endpoint, existing.rex, existing.scope)
                continue
            }
            existing?.let { it.rex.stop(); it.scope.cancel() }
            val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val rex = RexHost(endpoint, client, identity, hostScope)
            hostScope.launch { rex.alerts.collect { _alerts.emit(host to it) } }
            hostScope.launch {
                rex.client.state.collect { st ->
                    if (st is RexClient.State.Down) Log.i(TAG, "${endpoint.display}: ${st.problem}")
                }
            }
            next[host.id] = LiveHost(host, endpoint, rex, hostScope)
            if (canRun(host)) rex.start()
        }
        (old.keys - next.keys).forEach { id -> old[id]?.let { it.rex.stop(); it.scope.cancel() } }
        _live.value = next
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 30_000L
        const val TAG = "RaxConnections"
    }
}
