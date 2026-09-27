package io.aequicor.heartbeat.feature.aiengine.authenticator.api

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Last established verdict. In-progress work belongs to an authentication machine, not this enum. */
@Serializable
public enum class AuthVerdict { Unknown, NeedsLogin, Authenticated, Rejected, SourceUnavailable }

/** Evidence supporting the verdict; local inspection is not proof of service acceptance. */
@Serializable
public enum class AuthCheckBasis { Local, CliStatus, Service }

/**
 * Context-specific observation. A network error marks the last verdict stale rather than logging the user out.
 * Stale Authenticated may permit opening with a UI indication; source identity must still be checked.
 * A CLI account fingerprint detects account changes, not every token rotation.
 */
@Serializable
public data class AuthCheck(
    val source: AuthSourceId,
    val context: AuthContextKey,
    val verdict: AuthVerdict,
    val basis: AuthCheckBasis,
    val checkedAt: Instant,
    val revision: AuthRevision,
    val isStale: Boolean = false,
)

/** Closed reason taxonomy; never infer expiry from an ambiguous unauthorized response. */
@Serializable
public enum class AuthFailureReason {
    NotAuthenticated,
    CredentialsExpired,
    CredentialsRejected,
    SourceUnavailable,
    AuthMismatch,
    SourceChanged,
}

/** Safe authentication failure, without raw diagnostics or credential/account data. */
@Serializable
public data class AuthFailure(val reason: AuthFailureReason, val source: AuthSourceId? = null)
