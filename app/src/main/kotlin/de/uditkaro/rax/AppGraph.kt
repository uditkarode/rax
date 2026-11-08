package de.uditkaro.rax

import android.app.Application
import de.uditkaro.rax.connection.AlertService
import de.uditkaro.rax.connection.Alerts
import de.uditkaro.rax.connection.Connections
import de.uditkaro.rax.connection.LocalNetwork
import de.uditkaro.rax.connection.NotificationAccess
import de.uditkaro.rax.connection.TailnetKeeper
import de.uditkaro.rax.data.HostStore
import de.uditkaro.rax.data.PermissionStore
import de.uditkaro.rax.data.Settings
import de.uditkaro.rax.data.SettingsStore
import de.uditkaro.rax.terminal.TerminalFonts
import de.uditkaro.rax.tailnet.Tailnet
import de.uditkaro.rax.tailnet.TailnetStatus
import de.uditkaro.rax.theme.ThemeCatalog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** The app's long-lived objects, made once in [RaxApplication] and shared by every screen. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Rax's built-in Tailscale node, run by [tailnetKeeper]. It is made before anything that starts
     * a thread, because making it sets environment variables.
     */
    val tailnet: Tailnet = Tailnet.get(app)

    private val settingsStore = SettingsStore(app)

    /** Null until the first read from disk, which the splash screen waits for. */
    val settings: StateFlow<Settings?> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, null)

    private val _themes = MutableStateFlow<ThemeCatalog?>(null)
    val themes: StateFlow<ThemeCatalog?> = _themes.asStateFlow()

    val fonts: TerminalFonts by lazy { TerminalFonts(app) }

    val hostStore = HostStore(app)

    val localNetwork = LocalNetwork(app)

    val notifications = NotificationAccess(app)

    val permissions = PermissionStore(app, scope)

    /**
     * Background alerts are on and Rax may post them, so turning notifications off in system settings
     * also stops them. Null until the settings are read from disk.
     */
    val backgroundAlerts: StateFlow<Boolean?> = combine(settings, notifications.allowed) { s, allowed ->
        s?.let { it.backgroundAlerts && allowed }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val http = OkHttpClient.Builder()
        .dns(localNetwork.dns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    /** Reaches computers on the tailnet through [tailnet]. */
    val tailnetHttp: OkHttpClient = tailnet.clientBuilder(http).build()

    val connections = Connections(
        app = app,
        hostStore = hostStore,
        scope = scope,
        http = http,
        tailnetHttp = tailnetHttp,
        tailnetUp = tailnet.status.map { it is TailnetStatus.Running }.stateIn(scope, SharingStarted.Eagerly, false),
        keepInBackground = backgroundAlerts.map { it == true }.stateIn(scope, SharingStarted.Eagerly, false),
    )

    /** Runs [tailnet] while a saved host uses it and [connections] is in use, or while something holds it. */
    val tailnetKeeper = TailnetKeeper(
        scope = scope,
        hostsNeed = combine(connections.hosts, connections.inUse) { hosts, inUse -> hosts?.let { inUse && it.any { host -> host.tailnet } } },
        start = { tailnet.start() },
        stop = { tailnet.stop() },
    )

    /** Background alerts are on and there is a host to watch; null until both are read from disk. */
    private val watching = combine(backgroundAlerts, connections.hosts) { on, hosts ->
        if (on == null || hosts == null) null else on && hosts.isNotEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(Dispatchers.IO) { _themes.value = ThemeCatalog.load(app.assets) }
        scope.launch(Dispatchers.IO) {
            // The saved font can be one that an update no longer bundles.
            val fontId = settings.filterNotNull().first().fontId
            if (!fonts.exists(fontId)) settingsStore.setFont(Settings.DEFAULT_FONT)
        }
        Alerts(app, connections, scope)
        scope.launch { watching.filterNotNull().collect { syncAlertService() } }
        // LAN hosts that waited for the permission connect as soon as it is granted, without waiting
        // out their retry delay.
        scope.launch {
            localNetwork.granted.collect { granted -> if (granted) connections.retryLocal() }
        }
    }

    /**
     * Starts or stops the service behind background alerts. Android lets it start only while Rax is
     * on screen, so the activity calls this too each time it opens.
     */
    fun syncAlertService() {
        when (watching.value) {
            true -> AlertService.start(app)
            false -> AlertService.stop(app)
            null -> Unit
        }
    }

    private val _signingOut = MutableStateFlow(false)

    /** A sign-out of Tailscale is under way. */
    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()

    /**
     * Signs out of Tailscale, once at a time, without tying it to the screen that asked, because it
     * can take a while. [onFailed] runs on the main thread when Tailscale could not be told.
     */
    fun signOut(onFailed: () -> Unit) {
        if (!_signingOut.compareAndSet(expect = false, update = true)) return
        scope.launch(Dispatchers.Main) {
            try {
                tailnet.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailed()
            } finally {
                _signingOut.value = false
            }
        }
    }

    /** Writes a setting without tying it to the screen that asked, so leaving the screen cannot cancel it. */
    fun updateSettings(block: suspend SettingsStore.() -> Unit) {
        scope.launch { settingsStore.block() }
    }
}
