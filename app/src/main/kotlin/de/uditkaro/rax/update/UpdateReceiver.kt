package de.uditkaro.rax.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.uditkaro.rax.graph

/** Hears from Android how the install of an update goes. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.graph.updater.onStatus(intent)
    }
}
