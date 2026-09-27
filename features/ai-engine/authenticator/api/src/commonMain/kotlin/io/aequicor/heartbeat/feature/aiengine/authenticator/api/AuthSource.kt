package io.aequicor.heartbeat.feature.aiengine.authenticator.api

import kotlinx.serialization.Serializable

/** Non-secret source metadata. Labels may contain PII and must not be logged. */
@Serializable
public data class AuthSourceInfo(val id: AuthSourceId, val label: String, val revision: AuthRevision) {
    override fun toString(): String = "AuthSourceInfo(id=$id)"
}

/** Provider and exact endpoint origin against which this source may be used. */
@Serializable
public data class AuthScope(val provider: ProviderId, val origin: EndpointOrigin)

/**
 * Credential provenance. The source alone owns its vault usage; bindings refer only to its id.
 * Forgetting an external source preserves external credentials. Importing a key creates an independent source.
 * No variant contains a key, OAuth token, helper command or account name. OAuth material is never imported.
 */
@Serializable
public sealed interface AuthSource {
    /** Persistent, non-secret metadata. */
    public val info: AuthSourceInfo

    /** Provider and origin restrictions, enforced again before use. */
    public val scope: AuthScope

    /** Heartbeat owns the key and resolves its current value from the profile vault on each use. */
    @Serializable
    public data class ManagedKey(
        override val info: AuthSourceInfo,
        override val scope: AuthScope,
        val secret: AuthSecretId,
    ) : AuthSource

    /** Read-only external key reference; revision may be unknown when value inspection is unavailable. */
    @Serializable
    public data class ExternalKey(
        override val info: AuthSourceInfo,
        override val scope: AuthScope,
        val location: AuthLocationId,
    ) : AuthSource

    /** Only the owning CLI authenticates and refreshes tokens; no other engine may consume this source. */
    @Serializable
    public data class CliLogin(
        override val info: AuthSourceInfo,
        override val scope: AuthScope,
        val owner: AuthOwnerId,
        val location: AuthLocationId,
    ) : AuthSource

    /** The location references a command; execution requires separate explicit user approval. */
    @Serializable
    public data class CredentialHelper(
        override val info: AuthSourceInfo,
        override val scope: AuthScope,
        val location: AuthLocationId,
    ) : AuthSource

    /** Endpoint access requires no credentials. */
    @Serializable
    public data class NoAuth(override val info: AuthSourceInfo, override val scope: AuthScope) : AuthSource
}

/** Upper bound on ownership compatibility; a factory may narrow it, never widen it. */
public fun AuthSource.isVisibleTo(owner: AuthOwnerId): Boolean = when (this) {
    is AuthSource.CliLogin -> this.owner == owner
    is AuthSource.ManagedKey, is AuthSource.ExternalKey, is AuthSource.CredentialHelper, is AuthSource.NoAuth -> true
}
