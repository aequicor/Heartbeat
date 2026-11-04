package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.ktor.http.Headers
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.hostIsIp
import kotlinx.coroutines.CancellationException

/** Snapshot first, then validate bounded synthetic fixture values without exposing them in diagnostics. */
internal fun offlineScript(script: List<OfflineHttpExchange>): List<OfflineHttpExchange> {
    require(script.size in 1..MAX_OFFLINE_EXCHANGES) { "Invalid offline HTTP script" }
    val snapshot = script.map { exchange ->
        require(exchange.headers.size <= MAX_OFFLINE_HEADERS) { "Invalid offline HTTP script" }
        exchange.copy(
            headers = exchange.headers.toMap(),
            response = when (val response = exchange.response) {
                is OfflineHttpResponse.Reply -> {
                    require(response.headers.size <= MAX_OFFLINE_HEADERS) { "Invalid offline HTTP script" }
                    response.copy(headers = response.headers.toMap())
                }

                OfflineHttpResponse.AwaitCancellation -> response
            },
        )
    }
    var bytes = 0
    for (exchange in snapshot) {
        require(exchange.method.length in 1..MAX_OFFLINE_METHOD_BYTES && exchange.method.all { it in 'A'..'Z' }) {
            "Invalid offline HTTP script"
        }
        bytes += boundedBytes(exchange.method, MAX_OFFLINE_METHOD_BYTES)
        bytes += boundedBytes(exchange.url, MAX_OFFLINE_METADATA_BYTES)
        require(validatedOfflineValue { validOfflineUrl(exchange.url) }) { "Invalid offline HTTP script" }
        bytes += boundedBytes(exchange.body, MAX_OFFLINE_BODY_BYTES)
        bytes += headerBytes(exchange.headers)
        val response = exchange.response
        if (response is OfflineHttpResponse.Reply) {
            require(response.status in MIN_HTTP_STATUS..MAX_HTTP_STATUS) { "Invalid offline HTTP script" }
            bytes += boundedBytes(response.body, MAX_OFFLINE_BODY_BYTES)
            bytes += headerBytes(response.headers)
        }
    }
    require(bytes <= MAX_OFFLINE_SCRIPT_BYTES) { "Invalid offline HTTP script" }
    return snapshot
}

private fun validOfflineUrl(value: String): Boolean {
    if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
        return false
    }
    val url = Url(value)
    val host = url.host
    val isLiteral = host.any { it == '[' || it == ']' || it == ':' }
    val isValidLiteral = !isLiteral || hostIsIp(host) || (':' in host && hostIsIp("[$host]"))
    return isValidLiteral && url.protocol in setOf(URLProtocol.HTTP, URLProtocol.HTTPS) && host.isNotBlank() &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty()
}

private fun headerBytes(headers: Map<String, String>): Int {
    require(headers.keys.all { name -> name.isNotEmpty() && name.all { it in '!'..'~' && it !in HEADER_SEPARATORS } }) {
        "Invalid offline HTTP script"
    }
    require(
        headers.size <= MAX_OFFLINE_HEADERS && headers.keys.map { it.lowercase() }.distinct().size == headers.size,
    ) {
        "Invalid offline HTTP script"
    }
    val size = headers.entries.sumOf {
        boundedBytes(
            it.key,
            MAX_OFFLINE_METADATA_BYTES,
        ) + boundedBytes(it.value, MAX_OFFLINE_METADATA_BYTES)
    }
    validatedOfflineValue { Headers.build { headers.forEach { (key, value) -> append(key, value) } } }
    return size
}

private fun boundedBytes(value: String, limit: Int): Int {
    require(value.length <= limit) { "Invalid offline HTTP script" }
    val size = value.encodeToByteArray().size
    require(size <= limit) { "Invalid offline HTTP script" }
    return size
}

private inline fun <T> validatedOfflineValue(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    val safe = IllegalArgumentException("Invalid offline HTTP script")
    Log.tag("OfflineHttpClients").w(safe) { "Invalid offline fixture: ${e::class.simpleName.orEmpty()}" }
    throw safe
}

private const val MAX_OFFLINE_EXCHANGES = 16
private const val MAX_OFFLINE_METHOD_BYTES = 16
private const val MAX_OFFLINE_HEADERS = 32
private const val MAX_OFFLINE_METADATA_BYTES = 4096
internal const val MAX_OFFLINE_BODY_BYTES = 65_536
private const val MAX_OFFLINE_SCRIPT_BYTES = 1_048_576
private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599
private const val HEADER_SEPARATORS = "\"(),/:;<=>?@[\\]{}"
