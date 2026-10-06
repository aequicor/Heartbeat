package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Restores host references using native user timestamps, stable across process launches and branch replay. */
internal class PiPromptResources(private val environment: PiSessionEnvironment) {
    private val originals = mutableMapOf<String, List<ContentPart>>()
    private var pending: List<ContentPart>? = null
    private var prepared: PiPromptInputs? = null
    var support = PromptInputSupport.TextDocuments
        private set

    fun model(model: JsonObject?) {
        support = model?.let(::piInputSupport) ?: PromptInputSupport.TextDocuments
    }

    fun originals(message: JsonObject): List<ContentPart>? = piResourceKey(message)?.let { originals[it] }

    suspend fun restore(ref: SessionRef, branch: PiStoredBranch) {
        branch.messages.forEach { message ->
            val key = piResourceKey(message)
            if (key != null) {
                val parts = environment.resourceHistory.parts(ref, key)
                if (parts != null) originals[key] = parts
            }
        }
    }

    suspend fun prepare(ref: SessionRef, request: PromptRequest) {
        prepared = piPromptInputs(request, support, environment.resources)
        pending = request.parts.takeIf { parts -> parts.any { it is ContentPart.Image || it is ContentPart.Resource } }
        environment.resourceHistory.remember(ref, "request:" + request.id.value, request.parts)
    }

    suspend fun submit(
        rpc: PiConnection,
        configuration: PiSessionConfiguration,
        request: PromptRequest,
        confirm: suspend () -> Unit,
    ) {
        val input = prepared ?: piFailure(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        prepared = null
        configuration.prepareEffort(request.reasoningEffort)
        try {
            confirm()
        } catch (error: EngineException) {
            throw PromptNotSentException(error.failure, error)
        }
        rpc.command(
            "prompt",
            JsonObject(
                buildMap {
                    put("message", JsonPrimitive(input.text))
                    if (input.images.isNotEmpty()) put("images", kotlinx.serialization.json.JsonArray(input.images))
                },
            ),
        )
    }

    suspend fun event(ref: SessionRef, record: JsonObject) {
        val parts = pending ?: return
        val message = record["message"] as? JsonObject ?: return
        if (message.string("role") == "user" && record.string("type") in setOf("message_start", "message_end")) {
            val key = piResourceKey(message)
                ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
            environment.resourceHistory.remember(ref, key, parts)
            originals[key] = parts
            if (record.string("type") == "message_end") pending = null
        }
    }
}

/** The prompt never reached Pi, so the failure is definite rather than an unknown delivery. */
internal class PromptNotSentException(val failure: EngineFailure, cause: EngineException) :
    Exception(failure.code, cause)

/** Native user timestamps survive branch reloads; runtime-generated normalized item IDs do not. */
internal fun piResourceKey(message: JsonObject): String? = if (message.string("role") == "user") {
    (message["timestamp"] as? JsonPrimitive)?.content?.let { "user:$it" }
} else {
    null
}
