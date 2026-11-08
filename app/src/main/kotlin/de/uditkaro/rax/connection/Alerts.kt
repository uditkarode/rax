package de.uditkaro.rax.connection

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.uditkaro.rax.MainActivity
import de.uditkaro.rax.R
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.rex.Alert
import de.uditkaro.rax.rex.Attach
import de.uditkaro.rax.rex.HostState
import de.uditkaro.rax.ui.home.appName
import de.uditkaro.rax.ui.home.tabRow
import de.uditkaro.rax.ui.home.waitingText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns what Rex reports into notifications while Rax is out of sight: a program that waits for
 * the user, or a desktop notification a program sent. A waiting notification goes away by itself
 * once the program stops waiting.
 */
class Alerts(
    private val app: Application,
    private val connections: Connections,
    /** Whether background alerts are on; null until the settings are read. */
    private val enabled: StateFlow<Boolean?>,
    scope: CoroutineScope,
) {
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
        // Waiting notifications posted before Android restarted the app are still in the shade.
        for (n in manager.activeNotifications) {
            if (n.id != ID_WAITING) continue
            val (hostId, blockId) = paneOf(n.tag) ?: continue
            shown.getOrPut(hostId) { mutableSetOf() } += blockId
        }
        scope.launch {
            connections.alerts.collect { (host, alert) -> withContext(Dispatchers.Main) { post(host, alert) } }
        }
        scope.launch {
            connections.states.collect { states -> withContext(Dispatchers.Main) { withdraw(states) } }
        }
        scope.launch {
            connections.hosts.filterNotNull().collect { hosts -> withContext(Dispatchers.Main) { forgetRemoved(hosts) } }
        }
        // A program that began to wait while Rax was open got no notification, so leaving Rax posts it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = postWaiting()
        })
        // Without background alerts the connections stop, and nothing would take a waiting notification away.
        scope.launch {
            enabled.filterNotNull().collect { on -> if (!on) withContext(Dispatchers.Main) { withdrawAll() } }
        }
    }

    private fun postWaiting() {
        for (live in connections.live.value.values) {
            for ((blockId, facts) in live.rex.state.value.blocks) {
                val record = facts.waiting ?: continue
                if (blockId in shown[live.host.id].orEmpty()) continue
                post(live.host, Alert.Waiting(facts.sessionId, blockId, record), silent = true)
            }
        }
    }

    private fun post(host: Host, alert: Alert, silent: Boolean = false) {
        if (enabled.value != true) return
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (!manager.areNotificationsEnabled()) return
        val state = connections.get(host.id)?.rex?.state?.value
        val facts = state?.blocks?.get(alert.blockId)
        // The program can stop waiting before its alert gets here, and then nothing would withdraw it.
        if (alert is Alert.Waiting && facts?.waiting == null) return
        val program = facts?.status?.values?.firstNotNullOfOrNull { it.effectiveApp ?: it.app }?.let(::appName) ?: facts?.programName
        val session = state?.session(alert.sessionId)
        val sessionName = session?.info?.label.orEmpty()
        // The tab's name as Home shows it, which tells apart several programs that wait.
        val tabTitle = session?.view?.tabs?.withIndex()
            ?.firstOrNull { (_, w) -> w.allBlocks.any { it.id == alert.blockId } }
            ?.let { (i, w) -> tabRow(app.resources, host.id, alert.sessionId, sessionName, w, i, state?.blocks.orEmpty(), alert.blockId)?.title }
        val name = tabTitle ?: program ?: host.name
        val (title, text) = when (alert) {
            is Alert.Waiting -> name to waitingText(app.resources, alert.record)
            is Alert.Notification -> alert.title.ifBlank { name } to alert.body
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(listOf(host.name, sessionName).filter { it.isNotBlank() }.joinToString(" · "))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // A pane that still waits after a restart or a reconnect is posted again; it sounds only the first time.
            .setOnlyAlertOnce(alert is Alert.Waiting)
            .setSilent(silent)
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
            val done = blocks.filter { blockId ->
                val facts = state.blocks[blockId]
                // Until a pane's status is read, nothing says whether it still waits. A pane missing
                // from every view is gone, and so is one in a session Rex does not let Rax attach.
                if (facts != null) {
                    facts.statusRead && facts.waiting == null
                } else {
                    state.sessions.all { it.view != null || it.attach == Attach.Failed }
                }
            }
            done.forEach { manager.cancel(tag(hostId, it), ID_WAITING) }
            blocks -= done.toSet()
        }
    }

    private fun withdrawAll() {
        for ((hostId, blocks) in shown) blocks.forEach { manager.cancel(tag(hostId, it), ID_WAITING) }
        shown.clear()
    }

    /** Takes away the notifications of hosts the user removed. */
    private fun forgetRemoved(hosts: List<Host>) {
        val ids = hosts.map { it.id }.toSet()
        for (n in manager.activeNotifications) {
            if (n.id != ID_WAITING && n.id != ID_NOTIFICATION) continue
            val (hostId, _) = paneOf(n.tag) ?: continue
            if (hostId !in ids) manager.cancel(n.tag, n.id)
        }
        shown.keys.retainAll(ids)
    }

    private fun openPane(host: Host, alert: Alert, state: HostState?): PendingIntent {
        val windowId = state?.session(alert.sessionId)?.view?.tabs
            ?.firstOrNull { w -> w.allBlocks.any { it.id == alert.blockId } }?.id
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

    /** The host and pane IDs in a notification [tag]. */
    private fun paneOf(tag: String?): Pair<String, String>? {
        if (tag == null || '/' !in tag) return null
        return tag.substringBeforeLast('/') to tag.substringAfterLast('/')
    }

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
