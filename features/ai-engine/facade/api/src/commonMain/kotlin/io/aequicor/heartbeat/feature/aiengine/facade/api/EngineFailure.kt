package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Access failures; authentication and model availability are independent. */
@Serializable
public enum class AccessFailureReason { ModelAccessDenied, OperationNotAllowed }

/** Runtime and installation failures. */
@Serializable
public enum class EngineFailureReason { Unavailable, RequirementsNotMet, Crashed, UnsupportedCapability }

/** Stored-session failures. A busy session must not silently queue another prompt. */
@Serializable
public enum class SessionFailureReason { NotFound, Busy, NotResumable, Changed }

/** History cursor failures are distinct from reaching the end of history. */
@Serializable
public enum class HistoryFailureReason { Unavailable, CursorExpired, RevisionConflict }

/** Rejected requests and ambiguous acceptance require different recovery. */
@Serializable
public enum class RequestFailureReason { Invalid, UnsupportedContent, OutcomeUnknown }

/** Transport failures never imply that a user logged out. */
@Serializable
public enum class TransportFailureReason { NetworkUnavailable, Timeout, ServiceUnavailable, ProtocolViolation }

/** Lifetime boundaries invalidate previously obtained handles. */
@Serializable
public enum class LifecycleFailureReason { ProfileClosed, SessionClosed }

/** Scope in which a rate or usage quota applies. */
@Serializable
public sealed interface LimitScope {
    /** The service did not identify the affected scope. */
    @Serializable
    public data object Unknown : LimitScope

    /** An engine-wide limit. */
    @Serializable
    public data class Engine(val engine: EngineId) : LimitScope

    /** A limit for one configured credential route. */
    @Serializable
    public data class Binding(val binding: EngineBindingId) : LimitScope

    /** A limit for a model through a particular route. */
    @Serializable
    public data class Model(val target: EngineTarget) : LimitScope
}

/**
 * Serializable domain failures. Messages for users are localized from the type and facts, never native text.
 * Adapters classify only established causes. An ambiguous unauthorized response is not proof of token expiry.
 */
@Serializable
public sealed interface EngineFailure {
    /** A stable safe classification suitable for exception messages. */
    public val code: String

    /** Delegates authentication classification to the authenticator contract. */
    @Serializable
    public data class Authentication(val reason: AuthFailure) : EngineFailure {
        override val code: String get() = "auth.${reason.reason.name}"
    }

    /** Request frequency limit; an unknown retry time remains null. */
    @Serializable
    public data class RateLimited(val scope: LimitScope, val retryAt: Instant? = null) : EngineFailure {
        override val code: String get() = "limit.rate"
    }

    /** Usage allowance exhausted; never trigger automatic fallback to another binding. */
    @Serializable
    public data class QuotaExceeded(val scope: LimitScope, val resetsAt: Instant? = null) : EngineFailure {
        override val code: String get() = "limit.quota"
    }

    /** Retrying the same context cannot help. */
    @Serializable
    public data class ContextLimitExceeded(val maximumTokens: Long? = null) : EngineFailure {
        init {
            require(maximumTokens == null || maximumTokens > 0)
        }
        override val code: String get() = "limit.context"
    }

    /** Authorization policy or model access denied the operation. */
    @Serializable
    public data class Access(val reason: AccessFailureReason) : EngineFailure {
        override val code: String get() = "access.${reason.name}"
    }

    /** Installation or runtime failure. */
    @Serializable
    public data class Engine(val reason: EngineFailureReason) : EngineFailure {
        override val code: String get() = "engine.${reason.name}"
    }

    /** Session lookup or concurrency failure. */
    @Serializable
    public data class Session(val reason: SessionFailureReason) : EngineFailure {
        override val code: String get() = "session.${reason.name}"
    }

    /** History retrieval or consistency failure. */
    @Serializable
    public data class History(val reason: HistoryFailureReason) : EngineFailure {
        override val code: String get() = "history.${reason.name}"
    }

    /** Invalid input or unknown acceptance, correlated by the original request id. */
    @Serializable
    public data class Request(val reason: RequestFailureReason, val request: RequestId? = null) : EngineFailure {
        override val code: String get() = "request.${reason.name}"
    }

    /** A transport failure with no inferred authentication verdict. */
    @Serializable
    public data class Transport(val reason: TransportFailureReason) : EngineFailure {
        override val code: String get() = "transport.${reason.name}"
    }

    /** The owning scope or handle has closed. */
    @Serializable
    public data class Lifecycle(val reason: LifecycleFailureReason) : EngineFailure {
        override val code: String get() = "lifecycle.${reason.name}"
    }

    /** Unclassified failure; the identifier locates sanitized internal diagnostics. */
    @Serializable
    public data class Unknown(val diagnostic: DiagnosticId? = null) : EngineFailure {
        override val code: String get() = "unknown"
    }
}

/**
 * Expected operation failure. After turn acceptance, failure is instead recorded as TurnOutcome.Failed.
 * CancellationException is always propagated unchanged. Native causes stay in sanitized adapter diagnostics;
 * this exception intentionally exposes no native throwable that could leak credentials to consumers or logs.
 */
public class EngineException(public val failure: EngineFailure) : Exception(failure.code)

/** Retry guidance; eligibility never proves safe request replay or enables credential fallback. */
@Serializable
public sealed interface RetryAdvice {
    /** Repeating unchanged input cannot help. */
    @Serializable
    public data object Never : RetryAdvice

    /** A user must repair configuration, authenticate or change the request first. */
    @Serializable
    public data object AfterUserAction : RetryAdvice

    /** Wait until the indicated instant; null means no reliable service hint. */
    @Serializable
    public data class AfterDelay(val until: Instant? = null) : RetryAdvice

    /** No safe generic retry recommendation can be made. */
    @Serializable
    public data object Unknown : RetryAdvice
}

/** Computes conservative recovery guidance without authorizing replay of an ambiguous request. */
public fun EngineFailure.retryAdvice(): RetryAdvice = when (this) {
    is EngineFailure.RateLimited -> RetryAdvice.AfterDelay(retryAt)

    is EngineFailure.QuotaExceeded -> resetsAt?.let { RetryAdvice.AfterDelay(it) } ?: RetryAdvice.AfterUserAction

    is EngineFailure.Authentication, is EngineFailure.Access, is EngineFailure.ContextLimitExceeded,
    is EngineFailure.Engine, is EngineFailure.Session, is EngineFailure.History,
    -> RetryAdvice.AfterUserAction

    is EngineFailure.Lifecycle -> RetryAdvice.Never

    is EngineFailure.Request, is EngineFailure.Unknown -> RetryAdvice.Unknown

    is EngineFailure.Transport -> when (reason) {
        TransportFailureReason.NetworkUnavailable, TransportFailureReason.ServiceUnavailable -> RetryAdvice.AfterDelay()
        TransportFailureReason.Timeout, TransportFailureReason.ProtocolViolation -> RetryAdvice.Unknown
    }
}
