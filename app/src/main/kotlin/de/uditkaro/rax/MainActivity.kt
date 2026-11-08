package de.uditkaro.rax

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.connection.Alerts
import de.uditkaro.rax.connection.LocalNetwork
import de.uditkaro.rax.data.Appearance
import de.uditkaro.rax.data.Settings
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxTheme
import de.uditkaro.rax.theme.RexTheme
import de.uditkaro.rax.theme.ThemeCatalog
import de.uditkaro.rax.ui.RaxNavigation
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.SystemActions

class MainActivity : ComponentActivity() {
    private val graph by lazy { application.graph }

    // The permission Android is asking the user for, and whether it showed a rationale before.
    // Both are saved with the activity, since the answer can come to a new one.
    private var asking: String? = null
    private var rationaleBefore = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions()
        val permission = asking ?: return@registerForActivityResult
        asking = null
        if (granted) return@registerForActivityResult
        when (afterDenial(rationaleBefore, shouldShowRequestPermissionRationale(permission), permission in graph.permissions.refused.value)) {
            Denial.AsksAgain -> Unit
            Denial.MayNotAsk -> graph.permissions.setRefused(permission)
            Denial.WillNotAsk -> startActivity(settingsFor(permission))
        }
    }

    private val system = SystemActions(
        askLocalNetwork = { ask(LocalNetwork.PERMISSION) },
        askNotifications = {
            when {
                graph.notifications.allowed.value -> Unit
                Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                    ask(Manifest.permission.POST_NOTIFICATIONS)
                // With no permission to ask for, only system settings turn notifications on.
                else -> startActivity(notificationSettings())
            }
        },
    )

    /** A pane a notification asked to open. */
    private var pendingOpen by mutableStateOf<Screen.Terminal?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // A screen that Android restores, or a notification opens, needs the hosts from its first frame.
        splash.setKeepOnScreenCondition {
            graph.settings.value == null || graph.themes.value == null || graph.connections.hosts.value == null
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingOpen = paneOf(intent)
        asking = savedInstanceState?.getString(STATE_ASKING)
        rationaleBefore = savedInstanceState?.getBoolean(STATE_RATIONALE) ?: false

        setContent {
            val settings by graph.settings.collectAsStateWithLifecycle()
            val themes by graph.themes.collectAsStateWithLifecycle()
            val hosts by graph.connections.hosts.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            val catalog = themes ?: return@setContent
            if (hosts == null) return@setContent
            val theme = activeTheme(s, catalog)
            SystemBars(theme.dark)
            // The splash screen and the window take their colours from the app's night mode.
            LaunchedEffect(s.appearance) {
                if (Build.VERSION.SDK_INT < 31) return@LaunchedEffect
                getSystemService(UiModeManager::class.java).setApplicationNightMode(
                    when (s.appearance) {
                        Appearance.System -> UiModeManager.MODE_NIGHT_AUTO
                        Appearance.Light -> UiModeManager.MODE_NIGHT_NO
                        Appearance.Dark -> UiModeManager.MODE_NIGHT_YES
                    },
                )
            }
            RaxTheme(theme) {
                // Screens fade over this while they slide in and out.
                Box(Modifier.fillMaxSize().background(Rax.colors.page)) {
                    RaxNavigation(graph, system, pendingOpen = pendingOpen, onPendingOpened = { pendingOpen = null })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ASKING, asking)
        outState.putBoolean(STATE_RATIONALE, rationaleBefore)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        paneOf(intent)?.let { pendingOpen = it }
    }

    override fun onStart() {
        super.onStart()
        graph.syncAlertService()
    }

    override fun onResume() {
        super.onResume()
        // The user can change permissions in system settings while Rax is in the background.
        refreshPermissions()
    }

    private fun refreshPermissions() {
        graph.localNetwork.refresh()
        graph.notifications.refresh()
        graph.permissions.refused.value
            .filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .forEach(graph.permissions::clearRefused)
    }

    private fun paneOf(intent: Intent?): Screen.Terminal? {
        if (intent?.action != Alerts.ACTION_OPEN_PANE) return null
        // Opening Rax from recents repeats the intent that started its task, which can be an old notification's.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val host = intent.getStringExtra(Alerts.EXTRA_HOST) ?: return null
        val session = intent.getStringExtra(Alerts.EXTRA_SESSION) ?: return null
        val block = intent.getStringExtra(Alerts.EXTRA_BLOCK)
        // A notification can come before Rax knew the pane's tab.
        val window = intent.getStringExtra(Alerts.EXTRA_WINDOW)
            ?: block?.let { b ->
                graph.connections.get(host)?.rex?.state?.value?.session(session)?.view?.tabs
                    ?.firstOrNull { w -> w.allBlocks.any { it.id == b } }?.id
            }
            ?: return null
        return Screen.Terminal(host, session, window, block)
    }

    /**
     * Asks for [permission]. Once Android stops showing the prompt for it, opens system settings,
     * where the user can still turn it on.
     */
    private fun ask(permission: String) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED || asking != null) return
        asking = permission
        rationaleBefore = shouldShowRequestPermissionRationale(permission)
        graph.permissions.setAsked(permission)
        permissionRequest.launch(permission)
    }

    private fun settingsFor(permission: String): Intent =
        if (permission == Manifest.permission.POST_NOTIFICATIONS) {
            notificationSettings()
        } else {
            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }

    private fun notificationSettings(): Intent =
        Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, packageName)

    @Composable
    private fun SystemBars(dark: Boolean) {
        DisposableEffect(dark) {
            val style = if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose {}
        }
    }
}

private const val STATE_ASKING = "asking"
private const val STATE_RATIONALE = "rationale_before"

/** What a denied permission request says about Android's next prompt for it. */
enum class Denial {
    /** Android shows its prompt again next time. */
    AsksAgain,

    /** Android may not show its prompt again, so the next ask that Android answers by itself opens system settings. */
    MayNotAsk,

    /** Android answered without a prompt, so only system settings can turn the permission on. */
    WillNotAsk,
}

/**
 * Reads a denial from the rationale Android showed for the permission before and after the request.
 * Without a rationale, Android either prompts for the first time or, after the user refused twice,
 * answers by itself. A prompt closed without an answer also leaves none. So a denial without a
 * rationale only marks the permission [refused], and the next one opens system settings.
 */
fun afterDenial(rationaleBefore: Boolean, rationaleNow: Boolean, refused: Boolean): Denial = when {
    rationaleNow -> Denial.AsksAgain
    !rationaleBefore && refused -> Denial.WillNotAsk
    else -> Denial.MayNotAsk
}

@Composable
fun activeTheme(settings: Settings, catalog: ThemeCatalog): RexTheme {
    val dark = when (settings.appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    return if (dark) catalog.dark(settings.darkThemeId) else catalog.light(settings.lightThemeId)
}
