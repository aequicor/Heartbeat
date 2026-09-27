package io.aequicor.heartbeat.feature.aiengine.authenticator.api

import io.aequicor.heartbeat.core.secrets.Secret
import kotlinx.coroutines.flow.StateFlow

/**
 * Profile registry of credential sources and the only writer of their metadata and managed vault entries.
 * Suspend operations are main-safe, propagate CancellationException and never log labels or credential values.
 * Reads never discover external credentials or execute helpers.
 */
public interface AuthSources {
    /** Saved sources of the active profile, without credential values. */
    public val state: StateFlow<List<AuthSource>>

    /**
     * Creates an independent source with a fresh id; an equal existing source is never reused implicitly.
     * A managed key is copied into the profile vault; the caller still owns and closes the supplied Secret.
     */
    public suspend fun create(request: NewAuthSource): AuthSource

    /**
     * Forgets the source. A managed key is removed from the vault, external credentials and CLI logins remain intact.
     * Callers disconnect engine bindings first; a still referenced source is rejected with IllegalStateException.
     */
    public suspend fun forget(source: AuthSourceId)
}

/** Request of [AuthSources.create]; [label] is user-visible text that may contain PII and is never logged. */
public sealed interface NewAuthSource {
    /** User-visible name of the source. */
    public val label: String

    /** Provider and exact origin the source may be used against. */
    public val scope: AuthScope

    /** A key typed by the user; Heartbeat stores it in the profile vault and owns it from then on. */
    public data class ManagedKey(
        override val label: String,
        override val scope: AuthScope,
        /** Caller-owned value; the registry copies it and never retains this instance. */
        public val key: Secret,
    ) : NewAuthSource {
        override fun toString(): String = "NewAuthSource.ManagedKey(scope=$scope)"
    }

    /** An existing login of the CLI [owner] at [location]; tokens stay owned by that CLI. */
    public data class CliLogin(
        override val label: String,
        override val scope: AuthScope,
        /** The only engine namespace allowed to consume this source. */
        public val owner: AuthOwnerId,
        /** Opaque reference to the CLI profile or configuration directory. */
        public val location: AuthLocationId,
    ) : NewAuthSource {
        override fun toString(): String = "NewAuthSource.CliLogin(scope=$scope, owner=$owner)"
    }

    /** An endpoint without credentials, such as a local model server. */
    public data class NoAuth(override val label: String, override val scope: AuthScope) : NewAuthSource {
        override fun toString(): String = "NewAuthSource.NoAuth(scope=$scope)"
    }
}

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
