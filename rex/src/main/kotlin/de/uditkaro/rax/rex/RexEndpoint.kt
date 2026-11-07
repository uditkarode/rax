package de.uditkaro.rax.rex

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a Rex server listens. Built from what the user typed:
 *
 * - a full URL (`http`, `https`, `ws`, `wss`) is used as given;
 * - `host:port` uses HTTPS on 443 and plain HTTP on any other port;
 * - a bare name uses HTTPS on 443 when it is reached through Tailscale or is a MagicDNS name, the
 *   way Rex's built-in Tailscale serves, and plain HTTP on 7297 otherwise, the port Rex suggests
 *   for `--extra-listen`.
 */
class RexEndpoint private constructor(val base: HttpUrl) {

    val controlUrl: String get() = wsUrl("api/control")
    val dataUrl: String get() = wsUrl("api/data")
    val secure: Boolean get() = base.isHttps
    val host: String get() = base.host

    /** The address as a person reads it, such as `devbox.tail1234.ts.net` or `ws://192.168.1.20:7297`. */
    val display: String
        get() = if (secure && base.port == 443) base.host else "${if (secure) "wss" else "ws"}://${base.host}:${base.port}"

    private fun wsUrl(path: String): String {
        val url = base.newBuilder().addPathSegments(path).build().toString()
        return if (url.startsWith("https://")) "wss://" + url.removePrefix("https://") else "ws://" + url.removePrefix("http://")
    }

    override fun equals(other: Any?) = other is RexEndpoint && other.base == base
    override fun hashCode() = base.hashCode()
    override fun toString() = base.toString()

    companion object {
        const val DEFAULT_PORT = 7297

        sealed interface Parsed {
            data class Ok(val endpoint: RexEndpoint) : Parsed
            data class Invalid(val reason: Reason) : Parsed
        }

        enum class Reason { Empty, BadAddress, BadScheme }

        fun parse(input: String, tailnet: Boolean): Parsed {
            val text = input.trim().trimEnd('/')
            if (text.isEmpty()) return Parsed.Invalid(Reason.Empty)
            val scheme = text.substringBefore("://", "").lowercase()
            val url = when (scheme) {
                "" -> {
                    val bareV6 = text.count { it == ':' } >= 2 && !text.startsWith("[")
                    val hasPort = !bareV6 && Regex(":\\d+$").containsMatchIn(text) && !text.startsWith("[")
                    val bracketPort = Regex("^\\[[^]]+]:\\d+$").matches(text)
                    when {
                        hasPort || bracketPort -> {
                            val port = text.substringAfterLast(':').toIntOrNull()
                            if (port == 443) "https://$text" else "http://$text"
                        }
                        tailnet || text.lowercase().endsWith(".ts.net") -> "https://$text"
                        else -> "http://${bracketIfV6(text)}:$DEFAULT_PORT"
                    }
                }
                "http", "https" -> text
                "ws" -> "http://" + text.substringAfter("://")
                "wss" -> "https://" + text.substringAfter("://")
                else -> return Parsed.Invalid(Reason.BadScheme)
            }
            val parsed = url.toHttpUrlOrNull() ?: return Parsed.Invalid(Reason.BadAddress)
            if (parsed.host.isBlank()) return Parsed.Invalid(Reason.BadAddress)
            // Rex serves its endpoints at the root; a path the user pasted, such as /api/control, is dropped.
            return Parsed.Ok(RexEndpoint(parsed.newBuilder().encodedPath("/").query(null).fragment(null).build()))
        }

        private fun bracketIfV6(host: String) = if (host.count { it == ':' } >= 2) "[$host]" else host
    }
}
