package de.uditkaro.rax.connection

import de.uditkaro.rax.rex.RexEndpoint
import de.uditkaro.rax.tailnet.SshAttempt
import de.uditkaro.rax.tailnet.SshConnection
import de.uditkaro.rax.tailnet.SshLogin
import de.uditkaro.rax.tailnet.sshClientBuilder
import java.io.IOException
import java.net.InetAddress
import okhttp3.OkHttpClient

/**
 * The SSH connection of one host, which carries every connection to its Rex: the control
 * connection and each pane's stream. The first stream signs in, and later ones reuse the
 * connection while it lasts. The Go side closes it when no stream has been open for a few seconds,
 * or when the computer stops answering, and the next stream signs in again.
 *
 * [login] reads what to sign in with when it is needed, so a changed password applies at the next
 * sign-in. A computer Rax has not seen gets its host key saved through [onHostKey].
 */
class SshLink(
    private val target: RexEndpoint.SshTarget,
    /** Dial through the app's Tailscale node. */
    val viaTailnet: Boolean,
    private val resolve: (String) -> List<InetAddress>,
    private val login: () -> SshLogin,
    private val onHostKey: (String) -> Unit,
) {
    private val lock = Any()

    @Volatile private var connection: SshConnection? = null

    @Volatile private var closed = false

    /** [base], with each of its connections going through this link. */
    fun client(base: OkHttpClient): OkHttpClient = sshClientBuilder(base, ::open).build()

    private fun open(attempt: SshAttempt): Int = attempt.open(connection(attempt), OPEN_TIMEOUT_MS)

    private fun connection(attempt: SshAttempt): SshConnection = synchronized(lock) {
        if (closed) throw IOException("closed")
        connection?.takeIf { !it.closed }?.let { return it }
        val addresses = if (viaTailnet) target.host else resolve(target.host).joinToString(" ") { it.hostAddress.orEmpty() }
        val l = login()
        val c = attempt.connect(addresses, target.port, viaTailnet, l, CONNECT_TIMEOUT_MS)
        if (l.hostKey == null) onHostKey(c.hostKey)
        if (closed) {
            c.close()
            throw IOException("closed")
        }
        connection = c
        c
    }

    /** Ends the SSH connection and every stream on it, for a host that was removed or changed. */
    fun close() {
        closed = true
        connection?.close()
    }

    private companion object {
        /** Signing in and starting Rex on the computer, which can take a few seconds when it was not running. */
        const val CONNECT_TIMEOUT_MS = 25_000
        const val OPEN_TIMEOUT_MS = 10_000
    }
}
