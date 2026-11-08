package de.uditkaro.rax.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.uditkaro.rax.graph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Starts background alerts again after the phone restarts or Rax updates, which both end the service. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = context.graph
        val result = goAsync()
        graph.scope.launch {
            try {
                // Android allows a foreground service to start while this broadcast is handled.
                withTimeoutOrNull(START_WAIT_MS) { graph.syncAlertServiceWhenRead() }
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val START_WAIT_MS = 8_000L
    }
}
