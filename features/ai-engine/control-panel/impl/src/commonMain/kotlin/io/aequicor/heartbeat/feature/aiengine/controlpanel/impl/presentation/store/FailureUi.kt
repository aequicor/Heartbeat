package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason

/** Localizable failure category; native diagnostics are never shown. */
enum class FailureUi {
    Authentication,
    CredentialsMissing,
    ConnectionChanged,
    Network,
    Limit,
    Quota,
    EngineUnavailable,
    Access,
    Rejected,
    Unknown,
}

internal fun EngineFailure.toUi(): FailureUi = when (this) {
    is EngineFailure.Authentication -> when (reason.reason) {
        AuthFailureReason.NotAuthenticated, AuthFailureReason.SourceUnavailable -> FailureUi.CredentialsMissing
        AuthFailureReason.AuthMismatch, AuthFailureReason.SourceChanged -> FailureUi.ConnectionChanged
        AuthFailureReason.CredentialsExpired, AuthFailureReason.CredentialsRejected -> FailureUi.Authentication
    }

    is EngineFailure.Transport -> FailureUi.Network

    is EngineFailure.RateLimited -> FailureUi.Limit

    is EngineFailure.QuotaExceeded -> FailureUi.Quota

    is EngineFailure.Access -> FailureUi.Access

    is EngineFailure.Request -> FailureUi.Rejected

    is EngineFailure.Engine -> when (reason) {
        EngineFailureReason.UnsupportedCapability -> FailureUi.Rejected

        EngineFailureReason.Unavailable, EngineFailureReason.RequirementsNotMet, EngineFailureReason.Crashed ->
            FailureUi.EngineUnavailable
    }

    is EngineFailure.ContextLimitExceeded, is EngineFailure.Session, is EngineFailure.History,
    is EngineFailure.Lifecycle, is EngineFailure.Unknown,
    -> FailureUi.Unknown
}
