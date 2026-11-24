package com.castpro.core.common.model

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Everything a phone needs to reach a Cast Pro TV receiver.
 *
 * The QR code on the TV uses the `castpro://connect` URI form so it can never
 * be confused with the browser receiver URL. Plain `host`, `host:port` and
 * `http://host:port` values are accepted too, because people often copy the
 * address shown on the TV by hand.
 */
data class PairingInfo(
    val host: String,
    val port: Int = 8998,
    val name: String? = null,
    val webPort: Int = 8080
) {
    /** URI encoded into the TV QR code. */
    fun toUri(): String {
        val encodedHost = URLEncoder.encode(host, StandardCharsets.UTF_8.name())
        val encodedName = name
            ?.takeIf { it.isNotBlank() }
            ?.let { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }
        return buildString {
            append("castpro://connect?host=").append(encodedHost)
            append("&port=").append(port)
            if (!encodedName.isNullOrBlank()) append("&name=").append(encodedName)
            append("&web=").append(webPort)
        }
    }

    companion object {
        private const val DEFAULT_STREAM_PORT = 8998
        private const val DEFAULT_WEB_PORT = 8080

        fun parse(raw: String): PairingInfo? {
            val value = raw.trim()
            if (value.isEmpty()) return null

            if (value.startsWith("castpro://", ignoreCase = true)) {
                return parseCastProUri(value)
            }
            if (value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
            ) {
                return parseHttpUri(value)
            }
            if (value.contains("://")) return null
            return parseHostPort(value)
        }

        private fun parseCastProUri(value: String): PairingInfo? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val params = parseQuery(uri.rawQuery)
            val hostFromAuthority = if (uri.rawQuery == null) {
                uri.host?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val host = params["host"]?.takeIf { it.isNotBlank() }
                ?: hostFromAuthority
                ?: return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it.isValidPort() }
                ?: uri.port.takeIf { it.isValidPort() }
                ?: DEFAULT_STREAM_PORT
            val webPort = params["web"]?.toIntOrNull()?.takeIf { it.isValidPort() }
                ?: DEFAULT_WEB_PORT
            return PairingInfo(
                host = host.trim(),
                port = port,
                name = params["name"]?.trim()?.takeIf { it.isNotBlank() },
                webPort = webPort
            ).takeIf { it.isValid() }
        }

        private fun parseHttpUri(value: String): PairingInfo? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val webPort = uri.port.takeIf { it.isValidPort() } ?: DEFAULT_WEB_PORT
            // A browser URL identifies the same box; the native receiver keeps
            // its own stream port, so prefer the scheme default over the HTTP port.
            return PairingInfo(host = host, port = DEFAULT_STREAM_PORT, webPort = webPort)
                .takeIf { it.isValid() }
        }

        private fun parseHostPort(value: String): PairingInfo? {
            val cleaned = value.split('/', '?', '#').first().trim()
            if (cleaned.isEmpty()) return null

            var host = cleaned
            var port = DEFAULT_STREAM_PORT

            if (cleaned.startsWith("[")) {
                val end = cleaned.indexOf(']')
                if (end <= 1) return null
                host = cleaned.substring(1, end)
                if (end + 1 < cleaned.length) {
                    if (cleaned[end + 1] != ':') return null
                    port = cleaned.substring(end + 2).toIntOrNull() ?: return null
                }
            } else {
                val colon = cleaned.lastIndexOf(':')
                if (colon > 0 && cleaned.indexOf(':') == colon) {
                    host = cleaned.substring(0, colon)
                    port = cleaned.substring(colon + 1).toIntOrNull() ?: return null
                }
            }

            return PairingInfo(host = host.trim(), port = port)
                .takeIf { it.isValid() }
        }

        private fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            return rawQuery.split('&').mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = decode(part.substring(0, separator))
                val value = decode(part.substring(separator + 1))
                key to value
            }.toMap()
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
                .getOrDefault(value)

        private fun Int.isValidPort(): Boolean = this in 1..65535

        private fun PairingInfo.isValid(): Boolean =
            host.isNotBlank() &&
                host.length <= 253 &&
                host.none { it.isWhitespace() } &&
                port.isValidPort()
    }
}
