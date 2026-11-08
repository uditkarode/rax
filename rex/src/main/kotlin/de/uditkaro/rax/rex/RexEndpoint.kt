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
 *   for `--extra-listen`. A bare IP through Tailscale also uses plain HTTP on 7297.
 *
 * A path after the host, such as `/api/control`, is dropped.
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

        enum class Reason {
            Empty,
            BadAddress,
            BadScheme,

            /**
             * A name without a dot, through Tailscale and without a port. Rex's built-in Tailscale has a
             * certificate only for the full `.ts.net` name, so the connection could not be secure.
             */
            ShortTailnetName,
        }

        fun parse(input: String, tailnet: Boolean): Parsed {
            val text = input.trim().trimEnd('/')
            if (text.isEmpty()) return Parsed.Invalid(Reason.Empty)
            val scheme = text.substringBefore("://", "").lowercase()
            val url = when (scheme) {
                "" -> {
                    val (host, port) = authority(text) ?: return Parsed.Invalid(Reason.BadAddress)
                    val h = if (':' in host) "[$host]" else host
                    when {
                        port != null -> "${if (port == 443) "https" else "http"}://$h:$port"
                        host.lowercase().endsWith(".ts.net") -> "https://$h"
                        // No certificate names an IP, so a tailnet IP means the computer's own
                        // Tailscale, where Rex listens on its usual port.
                        tailnet && isIpLiteral(host) -> "http://$h:$DEFAULT_PORT"
                        tailnet && '.' !in host -> return Parsed.Invalid(Reason.ShortTailnetName)
                        tailnet -> "https://$h"
                        else -> "http://$h:$DEFAULT_PORT"
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

        /** The host in what the user typed, as typed and without brackets, or null if there is none. */
        fun hostOf(input: String): String? = authority(input.trim().substringAfter("://"))?.first

        /**
         * Host and port of `host[:port][/path]`, where an IPv6 host is either in brackets or bare. A
         * bare IPv6 address has no port, since its last group could not be told apart from one.
         */
        private fun authority(text: String): Pair<String, Int?>? {
            val a = text.substringBefore('/').substringBefore('?').substringBefore('#')
            val (host, port) = when {
                a.startsWith("[") -> {
                    val end = a.indexOf(']')
                    if (end < 0) return null
                    val rest = a.substring(end + 1)
                    a.substring(1, end) to when {
                        rest.isEmpty() -> null
                        rest.startsWith(":") -> rest.drop(1).toIntOrNull() ?: return null
                        else -> return null
                    }
                }
                a.count { it == ':' } >= 2 -> a to null
                ':' in a -> a.substringBefore(':') to (a.substringAfter(':').toIntOrNull() ?: return null)
                else -> a to null
            }
            return if (host.isEmpty()) null else host to port
        }

        /** Whether [host] is an IP address rather than a name. */
        fun isIpLiteral(host: String) = ':' in host || host.all { it.isDigit() || it == '.' }
    }
}
