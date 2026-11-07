package de.uditkaro.rax.ui.tailnet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import de.uditkaro.rax.AppGraph
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
                if (status is TailnetStatus.NeedsLogin) openPage(context, status.authUrl)
            }
        }
    }
}

fun openPage(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val SIGN_IN_WAIT_MS = 30_000L
