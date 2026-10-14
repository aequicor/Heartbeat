package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aistudio.impl.domain.RunFailureKind

/** Classes a failure for the transcript notice; unclassified or absent ones stay [RunFailureKind.Unknown]. */
internal fun EngineFailure?.toRunFailureKind(): RunFailureKind = when (this) {
    is EngineFailure.RateLimited -> RunFailureKind.Limit

    is EngineFailure.QuotaExceeded -> RunFailureKind.Quota

    is EngineFailure.ContextLimitExceeded -> RunFailureKind.Context

    is EngineFailure.Authentication -> reason.reason.toRunFailureKind()

    is EngineFailure.Transport -> RunFailureKind.Network

    is EngineFailure.Access -> RunFailureKind.Access

    is EngineFailure.Engine -> reason.toRunFailureKind()

    is EngineFailure.Request -> reason.toRunFailureKind()

    is EngineFailure.Session,
    is EngineFailure.History,
    is EngineFailure.Lifecycle,
    is EngineFailure.Unknown,
    null,
    -> RunFailureKind.Unknown
}

private fun AuthFailureReason.toRunFailureKind(): RunFailureKind = when (this) {
    AuthFailureReason.NotAuthenticated, AuthFailureReason.SourceUnavailable -> RunFailureKind.CredentialsMissing
    AuthFailureReason.AuthMismatch, AuthFailureReason.SourceChanged -> RunFailureKind.ConnectionChanged
    AuthFailureReason.CredentialsExpired, AuthFailureReason.CredentialsRejected -> RunFailureKind.Authentication
}

private fun EngineFailureReason.toRunFailureKind(): RunFailureKind = when (this) {
    EngineFailureReason.Unavailable, EngineFailureReason.RequirementsNotMet, EngineFailureReason.Crashed ->
        RunFailureKind.EngineUnavailable

    EngineFailureReason.UnsupportedCapability -> RunFailureKind.Unknown
}

private fun RequestFailureReason.toRunFailureKind(): RunFailureKind = when (this) {
    RequestFailureReason.Invalid -> RunFailureKind.RequestRejected
    RequestFailureReason.UnsupportedContent, RequestFailureReason.OutcomeUnknown -> RunFailureKind.Unknown
}
