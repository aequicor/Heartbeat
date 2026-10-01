package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Classifies an app-server v2 `TurnError` by its `codexErrorInfo` only. Native `message` text is never
 * inspected or propagated: it may contain account details, and users see localized text from the type.
 * `codexErrorInfo` is a unit variant (`"usageLimitExceeded"`) or a single-key object (`{"httpConnectionFailed": {…}}`).
 */
internal fun codexTurnFailure(error: JsonObject?, binding: EngineBindingId): EngineFailure =
    when (error?.let(::errorKind)) {
        "usageLimitExceeded" -> EngineFailure.QuotaExceeded(LimitScope.Binding(binding))

        "contextWindowExceeded" -> EngineFailure.ContextLimitExceeded()

        "unauthorized" -> EngineFailure.Authentication(AuthFailure(AuthFailureReason.CredentialsRejected))

        "httpConnectionFailed", "responseStreamConnectionFailed", "responseStreamDisconnected" ->
            EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)

        "serverOverloaded", "internalServerError", "responseTooManyFailedAttempts" ->
            EngineFailure.Transport(TransportFailureReason.ServiceUnavailable)

        else -> EngineFailure.Unknown()
    }

private fun errorKind(error: JsonObject): String? = when (val info = error["codexErrorInfo"]) {
    is JsonPrimitive -> info.contentOrNull
    is JsonObject -> info.keys.singleOrNull()
    else -> null
}
