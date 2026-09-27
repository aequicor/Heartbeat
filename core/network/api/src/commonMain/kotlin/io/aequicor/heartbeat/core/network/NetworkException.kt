package io.aequicor.heartbeat.core.network

import io.ktor.http.HttpStatusCode

/**
 * Expected failure of a network call, produced by [networkResult]. Repositories log it and turn it into a domain
 * error (an `Internal` intent of the feature machine). Anything else thrown by the call is a bug and is not wrapped.
 *
 * Safe to log with its throwable: there is no cause and the message holds only the kind of failure and, for
 * transport failures, the class of the original exception. Ktor exceptions are not attached because their messages
 * carry the full URL (query values included), response bodies and JSON fragments.
 */
public sealed class NetworkException(message: String) : Exception(message) {

    /** The server answered with a non-2xx [status]. */
    public class Http(public val status: HttpStatusCode) : NetworkException("HTTP ${status.value}")

    /** Request, connect or socket timeout expired. [origin] — class of the original exception. */
    public class Timeout(public val origin: String) : NetworkException("timeout ($origin)")

    /**
     * No connection: DNS, refused or reset connection, TLS, interrupted body.
     * [origin] — class of the original exception.
     */
    public class Connectivity(public val origin: String) : NetworkException("connectivity ($origin)")

    /** The response body cannot be converted to the expected type. [origin] — class of the original exception. */
    public class InvalidResponse(public val origin: String) : NetworkException("invalid response ($origin)")
}
