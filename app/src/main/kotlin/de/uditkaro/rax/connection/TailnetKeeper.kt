package de.uditkaro.rax.connection

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the app's Tailscale node while something needs it, and stops it [graceMs] after nothing
 * does. Saved hosts need it as [hostsNeed] says, and a [hold] needs it until it is released, such as
 * the Tailscale screen while it is on screen. The node keeps its keys when it stops, so the next
 * start needs no login.
 */
class TailnetKeeper(
    scope: CoroutineScope,
    /** Whether saved hosts need the node now, or null while the hosts are not read yet. */
    hostsNeed: Flow<Boolean?>,
    private val start: suspend () -> Unit,
    private val stop: suspend () -> Unit,
    private val graceMs: Long = STOP_GRACE_MS,
) {
    private val holds = MutableStateFlow(0)

    init {
        scope.launch {
            combine(hostsNeed, holds) { hosts, holds -> if (holds > 0) true else hosts }
                .distinctUntilChanged()
                .collectLatest { needed ->
                    when (needed) {
                        true -> start()
                        false -> {
                            delay(graceMs)
                            stop()
                        }
                        null -> Unit
                    }
                }
        }
    }

    /** Keeps the node running until the returned function is called. */
    fun hold(): () -> Unit {
        holds.update { it + 1 }
        val released = AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) holds.update { it - 1 } }
    }

    /** Keeps the node running while [block] runs. */
    suspend fun <T> holding(block: suspend () -> T): T {
        val release = hold()
        try {
            return block()
        } finally {
            release()
        }
    }

    private companion object {
        // Longer than the grace period in Connections, so that a host that is still connected
        // keeps the tailnet until Connections lets it go.
        const val STOP_GRACE_MS = 60_000L
    }
}
