package io.aequicor.heartbeat.core.network

import io.ktor.util.AttributeKey

/**
 * Mark a request with `attributes.put(SensitiveNetworkRequest, Unit)` before sending when any part of its URL or
 * headers can contain user data. The application transport hides the entire URL and all headers in its logs,
 * including retries and timeouts, while retaining method, status, duration and failure type. It does not change
 * the request, redact exceptions returned to the caller, or configure third-party logging plugins.
 */
public val SensitiveNetworkRequest: AttributeKey<Unit> = AttributeKey("HeartbeatSensitiveNetworkRequest")
