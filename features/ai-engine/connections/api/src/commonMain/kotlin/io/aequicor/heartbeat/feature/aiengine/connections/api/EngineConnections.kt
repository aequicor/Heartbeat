package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.ResultContract
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Profile settings space "engine × connection × model": every engine, its connections (bindings) and the models
 * enabled through each connection. Registered in the profile tree because the engine facade is profile-owned.
 */
@Serializable
@SerialName("engine_connections")
public data object EngineConnectionsRoute : Route

/**
 * Wizard adding a connection: engine → authentication method → models. [engine] skips the first step when that
 * engine can be connected. Opened with [ConnectEngineResult] it answers with the new binding.
 */
@Serializable
@SerialName("connect_engine")
public data class ConnectEngineRoute(val engine: EngineId? = null) : Route

/** Binding created by a completed [ConnectEngineRoute]; closing the wizard otherwise gives no result. */
public object ConnectEngineResult : ResultContract<EngineBindingId>("ai_engine.connect", EngineBindingId.serializer())

/** Connection setup screens; disabled until engine runtimes are bundled. */
public val EngineConnectionsEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.engine_connections",
    "Настройка подключений ИИ-движков",
    default = false,
)

/** Whether users can add a connection to this engine: it runs on this platform and declares a method. */
public val EngineInfo.isConnectable: Boolean
    get() = availability != EngineAvailability.UnsupportedPlatform && descriptor.connectionMethods.isNotEmpty()

/** Domain failure of an effect: the facade's classification, otherwise an unclassified failure. */
internal fun Throwable.toEngineFailure(): EngineFailure = (this as? EngineException)?.failure ?: EngineFailure.Unknown()
