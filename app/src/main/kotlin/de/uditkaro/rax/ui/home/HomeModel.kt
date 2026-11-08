package de.uditkaro.rax.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import de.uditkaro.rax.data.Host
import de.uditkaro.rax.tailnet.TailnetStatus
import de.uditkaro.rax.ui.components.ProgramKind

/** What Home draws, built from the hosts the user added and what each connected Rex reports. */
@Immutable
data class HomeState(
    val hosts: List<HostCard>,
    /** Programs that wait for the user, across every host, newest first. */
    val waiting: List<TabRow>,
)

@Immutable
data class HostCard(
    val host: Host,
    val link: Link,
    val sessions: List<SessionGroup>,
)

sealed interface Link {
    data object Connecting : Link
    data class Online(val viaTailnet: Boolean) : Link
    /** [message] says why in plain words; [retryable] is false when the user must change something first. */
    data class Offline(val message: String, val retryable: Boolean = true) : Link
    /** Rax cannot reach the host until the user allows it. */
    data class Blocked(val need: Need) : Link
}

enum class Need {
    /** The app's tailnet node is signed out. */
    TailscaleSignIn,

    /** Android 17 asks the user before an app reaches the local network. */
    LocalNetwork,
}

/**
 * What the phone itself allows, which decides whether a host can be reached at all. [localNames]
 * says for each host name that was looked up whether it points into the local network.
 */
data class Access(val tailnet: TailnetStatus, val localNetwork: Boolean, val localNames: Map<String, Boolean> = emptyMap())

@Immutable
data class SessionGroup(val id: String, val name: String, val tabs: List<TabRow>)

@Immutable
data class TabRow(
    val hostId: String,
    val sessionId: String,
    val sessionName: String,
    val tabId: String,
    val paneId: String?,
    val title: String,
    /** The second line: what waits, or the program and where it runs. */
    val detail: String?,
    val program: ProgramKind,
    val programName: String,
    @DrawableRes val icon: Int?,
    val waiting: Boolean,
    /** When the program started to wait, in milliseconds since the epoch, if it says. */
    val waitingSince: Long? = null,
)
