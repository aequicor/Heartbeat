package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.connections.api.isConnectable
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo

/** Installation state shown next to an engine. */
enum class AvailabilityUi { Unknown, Available, Unsupported, Unavailable }

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

/** How a method authenticates. */
enum class MethodKindUi { ApiKey, CliLogin, NoAuth }

/** Engine row of the wizard and of the settings space; [isEnabled] is false for an engine switched off. */
@Immutable
data class EngineRowUi(
    val id: String,
    val title: String,
    val availability: AvailabilityUi,
    val connections: Int,
    val isConnectable: Boolean,
    val isEnabled: Boolean = true,
    val preparation: EnginePreparationUi? = null,
)

/** Provider/method row of the wizard's second step. */
@Immutable
data class MethodRowUi(
    val id: String,
    val provider: String,
    val kind: MethodKindUi,
    val origin: String,
    val isOriginEditable: Boolean,
    val isPathEditable: Boolean,
    val credentialsPage: String?,
    /** Host application whose runtime the connection uses, for a host application sign-in. */
    val hostApp: String? = null,
)

/** Model row; [isEnabled] means offered to model pickers; [description] is null while descriptions are off. */
@Immutable
data class ModelRowUi(
    val id: String,
    val title: String,
    val contextLimit: Long?,
    val isEnabled: Boolean,
    val isDefault: Boolean = false,
    val description: ModelDescriptionUi? = null,
)

/** Whether a model has a description for adaptive subagents. */
enum class ModelDescriptionUi { Described, Missing }

internal fun EngineInfo.toRow(): EngineRowUi = EngineRowUi(
    id = descriptor.id.value,
    title = descriptor.title,
    availability = availability.toUi(),
    connections = bindings.size,
    isConnectable = isConnectable,
    preparation = preparation.toUi(),
)

internal fun ConnectionMethod.toRow(): MethodRowUi = MethodRowUi(
    id = id.value,
    provider = provider.title,
    kind = when (this) {
        is ConnectionMethod.ApiKey -> MethodKindUi.ApiKey
        is ConnectionMethod.CliLogin -> MethodKindUi.CliLogin
        is ConnectionMethod.NoAuth -> MethodKindUi.NoAuth
    },
    origin = origin.value,
    isOriginEditable = isOriginEditable,
    isPathEditable = isPathEditable,
    credentialsPage = provider.credentialsPage,
    hostApp = (this as? ConnectionMethod.CliLogin)?.hostApp?.title,
)

internal fun ModelInfo.toRow(isEnabled: Boolean, isDefault: Boolean = false): ModelRowUi =
    ModelRowUi(target.model.value, title, contextLimitTokens, isEnabled, isDefault)

internal fun EngineAvailability.toUi(): AvailabilityUi = when (this) {
    EngineAvailability.Unknown -> AvailabilityUi.Unknown
    EngineAvailability.Available -> AvailabilityUi.Available
    EngineAvailability.UnsupportedPlatform -> AvailabilityUi.Unsupported
    is EngineAvailability.Unavailable -> AvailabilityUi.Unavailable
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

/** Case-insensitive match of any of [fields] against a trimmed query. */
internal fun matches(query: String, vararg fields: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() || fields.any { it.contains(needle, ignoreCase = true) }
}
