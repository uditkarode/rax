package de.uditkaro.rax

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uditkaro.rax.connection.Alerts
import de.uditkaro.rax.connection.LocalNetwork
import de.uditkaro.rax.data.Appearance
import de.uditkaro.rax.data.Settings
import de.uditkaro.rax.theme.RaxTheme
import de.uditkaro.rax.theme.RexTheme
import de.uditkaro.rax.theme.ThemeCatalog
import de.uditkaro.rax.ui.RaxNavigation
import de.uditkaro.rax.ui.Screen
import de.uditkaro.rax.ui.SystemActions

class MainActivity : ComponentActivity() {
    private val graph by lazy { application.graph }

    private var onPermission: ((Boolean) -> Unit)? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        graph.localNetwork.refresh()
        onPermission?.invoke(granted)
        onPermission = null
    }

    private val system = SystemActions(
        askLocalNetwork = {
            ask(LocalNetwork.PERMISSION, appSettings()) {}
        },
        askNotifications = { onResult ->
            val settings = Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(SystemSettings.EXTRA_APP_PACKAGE, packageName)
            ask(Manifest.permission.POST_NOTIFICATIONS, settings, onResult)
        },
    )

    /** A pane a notification asked to open. */
    private var pendingOpen by mutableStateOf<Screen.Terminal?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { graph.settings.value == null || graph.themes.value == null }
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingOpen = paneOf(intent)

        setContent {
            val settings by graph.settings.collectAsStateWithLifecycle()
            val themes by graph.themes.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            val catalog = themes ?: return@setContent
            val theme = activeTheme(s, catalog)
            SystemBars(theme.dark)
            RaxTheme(theme) {
                RaxNavigation(graph, system, pendingOpen = pendingOpen, onPendingOpened = { pendingOpen = null })
            }
        }
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
        graph.localNetwork.refresh()
    }

    private fun paneOf(intent: Intent?): Screen.Terminal? {
        if (intent?.action != Alerts.ACTION_OPEN_PANE) return null
        val host = intent.getStringExtra(Alerts.EXTRA_HOST) ?: return null
        val session = intent.getStringExtra(Alerts.EXTRA_SESSION) ?: return null
        val window = intent.getStringExtra(Alerts.EXTRA_WINDOW) ?: return null
        return Screen.Terminal(host, session, window, intent.getStringExtra(Alerts.EXTRA_BLOCK))
    }

    /**
     * Asks for [permission]. Once Android stops showing the prompt for it, opens [settings], where
     * the user can still turn it on.
     */
    private fun ask(permission: String, settings: Intent, onResult: (Boolean) -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        val asked = permission in graph.settings.value?.askedPermissions.orEmpty()
        if (asked && !shouldShowRequestPermissionRationale(permission)) {
            startActivity(settings)
            onResult(false)
        } else {
            graph.updateSettings { setAsked(permission) }
            onPermission = onResult
            permissionRequest.launch(permission)
        }
    }

    private fun appSettings() =
        Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

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

@Composable
fun activeTheme(settings: Settings, catalog: ThemeCatalog): RexTheme {
    val dark = when (settings.appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    return if (dark) catalog.dark(settings.darkThemeId) else catalog.light(settings.lightThemeId)
}
