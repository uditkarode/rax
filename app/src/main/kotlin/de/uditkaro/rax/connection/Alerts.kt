package de.uditkaro.rax.connection

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import de.uditkaro.rax.MainActivity
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.rex.Alert
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.ui.home.appName
import de.uditkaro.rax.ui.home.waitingText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns what Rex reports into notifications while Rax is out of sight: a program that waits for
 * the user, or a desktop notification a program sent. A waiting notification goes away by itself
 * once the program stops waiting.
 */
class Alerts(private val app: Application, private val connections: Connections, scope: CoroutineScope) {
    private val manager = app.getSystemService(NotificationManager::class.java)

    /** Notifications shown for waiting programs, by host ID, so they can be withdrawn. */
    private val shown = HashMap<String, MutableSet<String>>()

    init {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ALERTS, app.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_SERVICE, app.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
            ),
        )
        scope.launch {
            connections.alerts.collect { (host, alert) -> withContext(Dispatchers.Main) { post(host, alert) } }
        }
        scope.launch {
            connections.states.collect { states -> withContext(Dispatchers.Main) { withdraw(states) } }
        }
    }

    private fun post(host: Host, alert: Alert) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val state = connections.states.value[host.id]
        val facts = state?.blocks?.get(alert.blockId)
        val program = facts?.status?.values?.firstNotNullOfOrNull { it.effectiveApp ?: it.app }?.let(::appName) ?: facts?.programName
        val (title, text) = when (alert) {
            is Alert.Waiting -> (program ?: host.name) to waitingText(app.resources, alert.record)
            is Alert.Notification -> alert.title.ifBlank { program ?: host.name } to alert.body
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(host.name)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openPane(host, alert, state))
            .build()
        val tag = tag(host.id, alert.blockId)
        manager.notify(tag, if (alert is Alert.Waiting) ID_WAITING else ID_NOTIFICATION, notification)
        if (alert is Alert.Waiting) shown.getOrPut(host.id) { mutableSetOf() } += alert.blockId
    }

    private fun withdraw(states: Map<String, HostState>) {
        for ((hostId, blocks) in shown) {
            val state = states[hostId] ?: continue
            if (!state.ready) continue
            val done = blocks.filter { state.blocks[it]?.waiting == null }
            done.forEach { manager.cancel(tag(hostId, it), ID_WAITING) }
            blocks -= done.toSet()
        }
    }

    private fun openPane(host: Host, alert: Alert, state: HostState?): PendingIntent {
        val windowId = state?.session(alert.sessionId)?.view?.tabs
            ?.firstOrNull { w -> w.blocks.any { it.id == alert.blockId } }?.id
        val intent = Intent(app, MainActivity::class.java)
            .setAction(ACTION_OPEN_PANE)
            .putExtra(EXTRA_HOST, host.id)
            .putExtra(EXTRA_SESSION, alert.sessionId)
            .putExtra(EXTRA_WINDOW, windowId)
            .putExtra(EXTRA_BLOCK, alert.blockId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            app,
            tag(host.id, alert.blockId).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun tag(hostId: String, blockId: String) = "$hostId/$blockId"

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_SERVICE = "connection"
        const val ACTION_OPEN_PANE = "de.uditkaro.rax.OPEN_PANE"
        const val EXTRA_HOST = "host"
        const val EXTRA_SESSION = "session"
        const val EXTRA_WINDOW = "window"
        const val EXTRA_BLOCK = "block"

        // One of each per pane, so a program's own notification does not replace its waiting one.
        private const val ID_WAITING = 1
        private const val ID_NOTIFICATION = 2
    }
}
