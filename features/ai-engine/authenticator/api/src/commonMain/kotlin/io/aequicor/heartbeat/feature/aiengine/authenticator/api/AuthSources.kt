package io.aequicor.heartbeat.feature.aiengine.authenticator.api

import io.aequicor.heartbeat.core.secrets.Secret
import kotlinx.coroutines.flow.StateFlow

/**
 * Description of a source whose credentials live outside Heartbeat. Registration stores only this reference:
 * it never reads the referenced values, runs a helper or contacts a service.
 */
public sealed interface AuthSourceDraft {
    /** User-visible label; may contain PII and is never logged. */
    public val label: String

    /** Provider and origin the source may be used with. */
    public val scope: AuthScope

    /** Read-only external key reference. */
    public data class ExternalKey(
        override val label: String,
        override val scope: AuthScope,
        val location: AuthLocationId,
        val revision: AuthRevision = AuthRevision.Unknown,
    ) : AuthSourceDraft

    /** Login owned by one CLI; [revision] is the owner-provided account fingerprint, if observable. */
    public data class CliLogin(
        override val label: String,
        override val scope: AuthScope,
        val owner: AuthOwnerId,
        val location: AuthLocationId,
        val revision: AuthRevision = AuthRevision.Unknown,
    ) : AuthSourceDraft

    /** Helper command reference; running it still requires explicit user approval. */
    public data class CredentialHelper(
        override val label: String,
        override val scope: AuthScope,
        val location: AuthLocationId,
    ) : AuthSourceDraft

    /** Endpoint without credentials. */
    public data class NoAuth(override val label: String, override val scope: AuthScope) : AuthSourceDraft
}

/**
 * Profile-owned registry of authentication sources. Persists only non-secret metadata; reads never execute
 * helpers, inspect external credentials or contact services. Suspend functions are main-safe, throw
 * [AuthException] for domain failures and propagate CancellationException unchanged.
 * Forgetting a source does not remove engine bindings that refer to it: they fail with SourceUnavailable.
 */
public interface AuthSources {
    /** Current sources in creation order. */
    public val state: StateFlow<List<AuthSource>>

    /** One source, or null when it is unknown in this profile. */
    public suspend fun get(id: AuthSourceId): AuthSource?

    /** Stores a new Heartbeat-owned key in the profile vault. [key] stays owned by the caller. */
    public suspend fun addManagedKey(label: String, scope: AuthScope, key: Secret): AuthSource.ManagedKey

    /** Replaces the value of a managed key; the source keeps its id and receives a new revision. */
    public suspend fun replaceManagedKey(id: AuthSourceId, key: Secret): AuthSource.ManagedKey

    /** Registers an independent reference to credentials owned elsewhere. */
    public suspend fun register(draft: AuthSourceDraft): AuthSource

    /** Records a new owner-provided revision of an external source; managed keys change only by replacement. */
    public suspend fun updateRevision(id: AuthSourceId, revision: AuthRevision): AuthSource

    /** Forgets the source. A managed value is removed from the vault; external credentials remain intact. */
    public suspend fun forget(id: AuthSourceId)
}

/**
 * Authentication verification entry point. A check runs the first authenticator that supports the source,
 * preferring [check]'s requested ids over the shared built-in ones. A failed check never logs the user out:
 * the previous verdict is returned marked stale.
 */
public interface AuthChecks {
    /** Last observation for this source and context; reading never starts a check. */
    public fun last(source: AuthSourceId, context: AuthContextKey): AuthCheck?

    /** Explicit verification in [context] with the engine's [authenticators]; main-safe. */
    public suspend fun check(
        source: AuthSourceId,
        context: AuthContextKey,
        authenticators: Set<AuthenticatorId> = emptySet(),
    ): AuthCheck
}

/** Expected authentication failure; messages carry only the stable reason, never credential or account data. */
public class AuthException(public val failure: AuthFailure) : Exception("auth.${failure.reason.name}")
