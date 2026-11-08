package de.uditkaro.rax.ui.tailnet

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import de.uditkaro.rax.AppGraph
import de.uditkaro.rax.R
import de.uditkaro.rax.tailnet.TailnetStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the node if needed and opens Tailscale's sign-in page once the node has a login URL.
 * The page opens in a browser tab; when the user finishes there, the node signs in by itself.
 */
@Composable
fun rememberTailnetSignIn(graph: AppGraph): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(graph, context, scope) {
        {
            scope.launch {
                graph.tailnet.start()
                val status = withTimeoutOrNull(SIGN_IN_WAIT_MS) {
                    graph.tailnet.status.first { it !is TailnetStatus.Starting && it !is TailnetStatus.Stopped }
                }
                if (status is TailnetStatus.NeedsLogin) {
                    openPage(context, status.authUrl)
                    // The browser sends Rax to the background, and the node keeps the key that the
                    // user signs in only in memory until the login finishes, so it must keep running.
                    graph.scope.launch {
                        graph.tailnetKeeper.holding {
                            withTimeoutOrNull(SIGN_IN_HOLD_MS) { graph.tailnet.status.first { it !is TailnetStatus.NeedsLogin } }
                        }
                    }
                }
            }
        }
    }
}

/** Opens [url] in a browser tab. With no app to open it, copies it, so the user can open it elsewhere. */
fun openPage(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
    } catch (_: ActivityNotFoundException) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, url))
        Toast.makeText(context, R.string.tailscale_no_browser, Toast.LENGTH_LONG).show()
    }
}

private const val SIGN_IN_WAIT_MS = 30_000L
private const val SIGN_IN_HOLD_MS = 10 * 60_000L
