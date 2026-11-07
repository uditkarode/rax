package de.uditkaro.rax.tailnet

import java.io.IOException
import java.net.InetAddress

sealed interface TailnetStatus {
    data object Stopped : TailnetStatus

    data object Starting : TailnetStatus

    /** The user must open [authUrl] in a browser and log in. */
    data class NeedsLogin(val authUrl: String) : TailnetStatus

    /** Logged in, but a tailnet admin must approve this device. */
    data object NeedsApproval : TailnetStatus

    data class Running(
        val tailnetName: String,
        /** This device's MagicDNS name, for example `rax-pixel-9.tail1234.ts.net`. */
        val dnsName: String,
        val addresses: List<InetAddress>,
    ) : TailnetStatus

    data class Error(val message: String) : TailnetStatus
}

data class TailnetPeer(
    /** MagicDNS name, for example `desktop.tail1234.ts.net`. */
    val dnsName: String,
    val hostName: String,
    /** For example `macOS`, `linux`, `windows`; null if the peer did not report it. */
    val os: String?,
    val online: Boolean,
    val addresses: List<InetAddress>,
)

class TailnetException(message: String, cause: Throwable? = null) : IOException(message, cause)
