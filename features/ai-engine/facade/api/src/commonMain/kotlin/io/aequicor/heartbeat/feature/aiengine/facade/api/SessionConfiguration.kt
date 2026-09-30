package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

/** Confirmed parameters used by subsequent operations; an in-flight model/tool call keeps its snapshot. */
@Serializable
public data class SessionConfiguration(
    val model: ModelId,
    val reasoningEffort: String? = null,
    val trust: TrustLevel? = null,
)

/** One session-local configuration change, without changing the engine or credential binding. */
@Serializable
public sealed interface SessionConfigurationChange {
    /** Selects a model for the next model request. */
    @Serializable
    public data class Model(val model: ModelId) : SessionConfigurationChange

    /** Selects effort for the next model request; null restores the native default. */
    @Serializable
    public data class Effort(val effort: String?) : SessionConfigurationChange {
        init {
            require(effort == null || effort.isNotBlank())
        }
    }

    /** Selects the approval policy for subsequent tool calls; outstanding permissions are unchanged. */
    @Serializable
    public data class Trust(val trust: TrustLevel) : SessionConfigurationChange
}

/** A later runtime correction, such as a provider rejecting an installed reasoning parameter. */
public data class SessionConfigurationUpdate(
    val operationId: String,
    val configuration: SessionConfiguration,
    val failure: EngineFailure,
)

/**
 * Optional live configuration capability. Applying a change never interrupts or resubmits accepted work.
 * A successful acknowledgement guarantees that subsequent tool/model requests read the returned parameters;
 * it does not change an already running request. Unsupported changes throw [EngineException].
 */
public interface ChangesSessionConfiguration : EngineFeature {
    /** Authoritative configuration for subsequent operations, including native corrections. */
    public val configuration: StateFlow<SessionConfiguration>

    /** Later rejected parameters, correlated with the original change. Ordinary apply results are not replayed. */
    public val updates: Flow<SessionConfigurationUpdate> get() = emptyFlow()

    /** Installs [change] for subsequent operations, returning the actual configuration after acknowledgement. */
    public suspend fun apply(operationId: String, change: SessionConfigurationChange): SessionConfiguration

    /** Typed live-configuration key. */
    public companion object : EngineFeatureKey<ChangesSessionConfiguration>(
        EngineFeatureId("session.configuration"),
        ChangesSessionConfiguration::class,
    )
}
