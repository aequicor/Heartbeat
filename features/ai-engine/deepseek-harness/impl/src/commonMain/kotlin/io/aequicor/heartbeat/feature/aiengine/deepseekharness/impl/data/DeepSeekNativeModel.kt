package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A native DeepSeek model: an opaque selector value (the config-option wire form) and its display title. */
internal data class DeepSeekNativeModel(val value: String, val title: String)

/**
 * Models from the `model` config option of a `session/new` response. DSH nests them as `options` → groups → options,
 * and the selector value is the opaque `["provider","model"]` form the adapter passes back to `set_config_option`.
 * Unexpected metadata shapes yield no models rather than a parse failure.
 */
internal fun deepSeekNativeModels(metadata: JsonObject): List<DeepSeekNativeModel> {
    val model = metadata.array("configOptions").firstOrNull { it.string("category") == "model" } ?: return emptyList()
    return model.array("options")
        .asSequence()
        .flatMap { group -> group.array("options").asSequence() }
        .mapNotNull { entry ->
            val value = entry.string("value") ?: return@mapNotNull null
            DeepSeekNativeModel(value, entry.string("name") ?: value)
        }
        .toList()
}

internal fun List<DeepSeekNativeModel>.modelInfo(engine: EngineId, binding: EngineBindingId): List<ModelInfo> =
    map { model -> ModelInfo(EngineTarget(engine, binding, ModelId(model.value)), model.title) }

/** Whether the session advertises a config option the adapter may select. */
internal fun JsonObject.hasConfigOption(id: String): Boolean = array("configOptions").any { entry ->
    entry.string("id") == id
}

private fun JsonObject.array(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
