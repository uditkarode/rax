package de.uditkaro.rax

import android.app.Application
import de.uditkaro.rax.connection.AlertService
import de.uditkaro.rax.connection.Alerts
import de.uditkaro.rax.connection.Connections
import de.uditkaro.rax.connection.LocalNetwork
import de.uditkaro.rax.data.HostStore
import de.uditkaro.rax.data.Settings
import de.uditkaro.rax.data.SettingsStore
import de.uditkaro.rax.terminal.TerminalFonts
import de.uditkaro.rax.tailnet.Tailnet
import de.uditkaro.rax.tailnet.TailnetStatus
import de.uditkaro.rax.theme.ThemeCatalog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** The app's long-lived objects, made once in [RaxApplication] and shared by every screen. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsStore = SettingsStore(app)

    /** Null until the first read from disk, which the splash screen waits for. */
    val settings: StateFlow<Settings?> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, null)

    private val _themes = MutableStateFlow<ThemeCatalog?>(null)
    val themes: StateFlow<ThemeCatalog?> = _themes.asStateFlow()

    val fonts: TerminalFonts by lazy { TerminalFonts(app) }

    val hostStore = HostStore(app)

    /** Rax's built-in Tailscale node. It starts when a host uses Tailscale or when the user signs in. */
    val tailnet: Tailnet = Tailnet.get(app)

    val localNetwork = LocalNetwork(app)

    private val http = OkHttpClient.Builder()
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
        keepInBackground = settings.map { it?.backgroundAlerts == true }.stateIn(scope, SharingStarted.Eagerly, false),
    )

    /** Background alerts are on and there is a host to watch; null until both are read from disk. */
    private val watching = combine(settings, connections.hosts) { s, hosts ->
        if (s == null || hosts == null) null else s.backgroundAlerts && hosts.isNotEmpty()
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
        scope.launch {
            connections.hosts.collect { hosts -> if (hosts.orEmpty().any { it.tailnet }) tailnet.start() }
        }
        // Hosts that waited for the node or the permission connect as soon as it is there,
        // without waiting out their retry delay.
        scope.launch {
            tailnet.status.map { it is TailnetStatus.Running }.distinctUntilChanged().collect { up ->
                if (up) connections.retryAll(tailnet = true)
            }
        }
        scope.launch {
            localNetwork.granted.collect { granted -> if (granted) connections.retryAll(tailnet = false) }
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

    /** Writes a setting without tying it to the screen that asked, so leaving the screen cannot cancel it. */
    fun updateSettings(block: suspend SettingsStore.() -> Unit) {
        scope.launch { settingsStore.block() }
    }
}
