package io.aequicor.heartbeat.feature.aiengine.authenticator.api

/**
 * Canonicalizes a user-entered HTTP(S) address into an [EndpointOrigin]: lower-cases scheme and host, drops a trailing
 * slash and a default port. Returns null for anything else — userinfo, paths, queries, fragments or other schemes —
 * rather than silently discarding parts the user typed.
 */
public fun canonicalOrigin(input: String): EndpointOrigin? {
    val match = ORIGIN_INPUT.matchEntire(input.trim()) ?: return null
    val scheme = match.groupValues[1].lowercase()
    val host = match.groupValues[2].lowercase()
    val portText = match.groupValues[3]
    val port = portText.toIntOrNull()
    val isPortValid = portText.isEmpty() || (!portText.startsWith("0") && port != null && port <= MAX_PORT_NUMBER)
    if (!isPortValid) return null
    val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
    val authority = if (port == null || port == defaultPort) host else "$host:$port"
    return EndpointOrigin("$scheme://$authority")
}

private val ORIGIN_INPUT = Regex("(?i)(https?)://([a-z0-9.-]+|\\[[a-f0-9:]+])(?::([0-9]{1,5}))?/?")
private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80
private const val MAX_PORT_NUMBER = 65535
