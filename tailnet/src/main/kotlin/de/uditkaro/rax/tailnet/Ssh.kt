package de.uditkaro.rax.tailnet

import de.uditkaro.rax.rex.SshFailure
import de.uditkaro.rax.tailnet.libtailnet.DialHandle
import de.uditkaro.rax.tailnet.libtailnet.Libtailnet
import de.uditkaro.rax.tailnet.libtailnet.SSHConn
import de.uditkaro.rax.tailnet.libtailnet.SSHLogin
import de.uditkaro.rax.tailnet.libtailnet.SSHSigner
import java.net.ConnectException
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import okhttp3.Dns
import okhttp3.OkHttpClient

/** How Rax signs in to an SSH server. */
sealed interface SshAuth {
    /**
     * The phone's own key: [publicKey] in PKIX DER form, and [sign], which returns an ASN.1 DER
     * ECDSA signature of its input hashed with SHA-256.
     */
    class PhoneKey(val publicKey: ByteArray, val sign: (ByteArray) -> ByteArray) : SshAuth

    /** A private key in PEM or OpenSSH form, and its passphrase, empty if it has none. */
    class PrivateKey(val text: String, val passphrase: String) : SshAuth

    class Password(val password: String) : SshAuth
}

/**
 * Who to sign in as and what Rax knows about the server: [hostKey] in authorized_keys form from
 * an earlier connection, null for a server Rax has not seen, and [socket], the path of Rex's
 * socket there, null to ask Rex on the server.
 */
class SshLogin(val user: String, val auth: SshAuth, val hostKey: String?, val socket: String?)

/** A signed-in SSH connection to a computer that runs Rex. */
class SshConnection internal constructor(internal val go: SSHConn) {
    /** The key the server showed, in authorized_keys form. */
    val hostKey: String get() = go.hostKey()

    /** Whether the connection ended: the server went away, it was idle, or it was closed. */
    val closed: Boolean get() = go.closed()

    fun close() = go.close()
}

/**
 * One attempt to sign in or to open a stream to Rex. Every call blocks; [cancel] aborts it from
 * another thread. A failure is an [SshFailure], or a [SocketTimeoutException] or [ConnectException]
 * when the computer could not be reached.
 */
class SshAttempt {
    private val handle: DialHandle = Libtailnet.newDialHandle()

    /**
     * Signs in on [port] of [addresses], which are IP addresses separated by spaces, tried in
     * order. With [viaTailnet], [addresses] is one name that the app's Tailscale node dials, and
     * the node must be running.
     */
    fun connect(addresses: String, port: Int, viaTailnet: Boolean, login: SshLogin, timeoutMillis: Int): SshConnection {
        val go = SSHLogin().apply {
            user = login.user
            hostKey = login.hostKey.orEmpty()
            socket = login.socket.orEmpty()
        }
        var signer: SSHSigner? = null
        when (val auth = login.auth) {
            is SshAuth.Password -> go.password = auth.password
            is SshAuth.PrivateKey -> {
                go.privateKey = auth.text
                go.passphrase = auth.passphrase
            }
            is SshAuth.PhoneKey -> {
                go.phoneKey = auth.publicKey
                signer = SSHSigner { data -> auth.sign(data) }
            }
        }
        return translate { SshConnection(handle.connectSSH(addresses, port, viaTailnet, timeoutMillis, go, signer)) }
    }

    /** Opens a stream to Rex on [connection] and returns a file descriptor that the caller owns. */
    fun open(connection: SshConnection, timeoutMillis: Int): Int = translate { connection.go.dial(handle, timeoutMillis) }

    fun cancel() = handle.cancel()

    private inline fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw goFailure(e)
        }
}

/**
 * [base] for a computer that Rax reaches through SSH, where each connection is a stream to Rex.
 * [open] gets a fresh [SshAttempt] for each and returns the stream's file descriptor; it signs in
 * first when there is no connection. The address OkHttp connects to and its connect timeout are
 * not used, because [open] sets its own.
 */
fun sshClientBuilder(base: OkHttpClient, open: (SshAttempt) -> Int): OkHttpClient.Builder {
    // Each connection is a stream to Rex's socket, so the name in the URL is never looked up.
    val loopback = Dns { listOf(InetAddress.getLoopbackAddress()) }
    val sockets = FdSocketFactory({
        val attempt = SshAttempt()
        object : PendingDial {
            override fun dial(host: String, port: Int, timeoutMillis: Int): Int = open(attempt)
            override fun cancel() = attempt.cancel()
        }
    }, loopback)
    return base.newBuilder()
        .socketFactory(sockets)
        .dns(loopback)
        .proxy(Proxy.NO_PROXY)
        // A failed sign-in must not be tried again at once, which can lock the account.
        .retryOnConnectionFailure(false)
}

/** Helpers for the keys Rax signs in with. */
object SshKeys {
    /** The line for ~/.ssh/authorized_keys that lets in [publicKey], given in PKIX DER form. */
    fun authorizedKey(publicKey: ByteArray, comment: String): String = Libtailnet.phoneKeyLine(publicKey, comment)

    enum class Check { Ok, NeedsPassphrase, WrongPassphrase, Invalid }

    /** The SHA-256 fingerprint of a key in authorized_keys form, as `ssh-keygen -l` shows it, or null if it is not one. */
    fun fingerprint(key: String): String? {
        val blob = key.trim().split(Regex("\\s+")).getOrNull(1) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return null
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    /** Whether [text] is a private key that [passphrase] opens. */
    fun check(text: String, passphrase: String): Check = when (Libtailnet.checkPrivateKey(text, passphrase)) {
        "" -> Check.Ok
        "passphrase" -> Check.NeedsPassphrase
        "wrong_passphrase" -> Check.WrongPassphrase
        else -> Check.Invalid
    }
}
