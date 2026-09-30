package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Main-confined native configuration; only acknowledged snapshots replace the last confirmed values. */
internal class PiSessionConfiguration(
    initialTarget: EngineTarget,
    private val nativeId: () -> String?,
    private val rpc: () -> PiConnection,
    private val trust: () -> TrustLevel,
    private val modelChanged: (JsonObject?) -> Unit,
) {
    private val log = Log.tag("PiSessionConfiguration")
    private val provider = initialTarget.model.value.substringBefore("/")
    private var nativeThinking: String? = null
    private var appliedThinking: String? = null
    var target = initialTarget
        private set
    private val mutableConfiguration = MutableStateFlow(SessionConfiguration(initialTarget.model, trust = trust()))
    val configuration = mutableConfiguration.asStateFlow()

    fun start(snapshot: JsonObject) {
        confirm(snapshot)
        nativeThinking = appliedThinking
    }

    fun publishTrust() = publish(configuration.value.copy(trust = trust()))

    suspend fun apply(change: SessionConfigurationChange, applyTrust: (TrustLevel) -> Unit): SessionConfiguration {
        when (change) {
            is SessionConfigurationChange.Trust -> {
                applyTrust(change.trust)
                publishTrust()
            }

            is SessionConfigurationChange.Model -> {
                val selected = rpc().command("set_model", modelFields(change.model))
                modelChanged((selected["model"] as? JsonObject) ?: selected)
                confirm(rpc().command("get_state"))
                nativeThinking = appliedThinking
            }

            is SessionConfigurationChange.Effort -> {
                val level = change.effort
                if (level != null && level !in PiAcceptedThinkingLevels) {
                    piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
                }
                prepareEffort(level)
            }
        }
        return configuration.value
    }

    /** Pi clamps thinking to the model; prompt overrides and resets are confirmed before delivery. */
    suspend fun prepareEffort(requested: String?) {
        val level = requested?.let(::piThinkingLevel) ?: nativeThinking ?: return
        if (level != appliedThinking) {
            rpc().command("set_thinking_level", JsonObject(mapOf("level" to JsonPrimitive(level))))
        }
        confirm(rpc().command("get_state"))
    }

    /**
     * Parses the entire native snapshot before publishing it. Toggle-only models always expose `off` or `on`,
     * including snapshots obtained after resetting effort, switching models or reconciling the session.
     */
    fun confirm(snapshot: JsonObject) {
        if (snapshot.string("sessionId") != nativeId()) {
            piFailure(EngineFailure.Session(SessionFailureReason.Changed))
        }
        val model = snapshot["model"] as? JsonObject
            ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        val confirmedProvider = model.string("provider")
        val id = model.string("id")
        if (confirmedProvider != provider || id.isNullOrBlank()) {
            piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
        val confirmedModel = ModelId("$provider/$id")
        val confirmedThinking = snapshot.string("thinkingLevel")
            ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        val effort = if (PI_THINKING_ON in model.piThinkingLevels() && confirmedThinking != "off") {
            PI_THINKING_ON
        } else {
            confirmedThinking
        }
        if (confirmedModel != target.model) modelChanged(model)
        target = target.copy(model = confirmedModel)
        appliedThinking = confirmedThinking
        publish(SessionConfiguration(target.model, effort, trust()))
    }

    fun modelFields(model: ModelId): JsonObject {
        if (model.value.substringBefore("/") != provider || "/" !in model.value) {
            piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
        return JsonObject(
            mapOf("provider" to JsonPrimitive(provider), "modelId" to JsonPrimitive(model.value.substringAfter("/"))),
        )
    }

    private fun publish(value: SessionConfiguration) {
        log.i { "Pi session configuration acknowledged" }
        mutableConfiguration.value = value
    }
}
