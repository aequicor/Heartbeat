package io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthenticatorId

/**
 * Verifies sources of particular kinds. Contributed to the profile graph by engine adapters and the shared
 * authenticator module; used only through AuthChecks. Implementations never log or return credential material.
 */
public interface Authenticator {
    /** Stable identity referenced by engine registrations. */
    public val id: AuthenticatorId

    /** Pure capability test without IO; CLI logins must be supported only by their owning CLI. */
    public fun supports(source: AuthSource): Boolean

    /**
     * Verifies [source] in [context]. The result names the source revision it observed.
     * Throws only when no verdict could be established (e.g. transport failure); the caller keeps a stale verdict.
     */
    public suspend fun check(source: AuthSource, context: AuthContextKey): AuthCheck
}

/**
 * Credential material for trusted engine adapters only. Values are resolved on each use, never cached,
 * logged or passed to UI; the caller closes every returned [Secret].
 */
public interface AuthCredentials {
    /** Current value of a Heartbeat-owned key; throws AuthException(NotAuthenticated) when the vault has none. */
    public suspend fun managedKey(source: AuthSource.ManagedKey): Secret
}
