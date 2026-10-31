package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** A native DeepSeek model: an opaque selector value (the config-option wire form) and its display title. */
internal data class DeepSeekNativeModel(val value: String, val title: String)

/**
 * Models from the `model` config option of a `session/new` response. DSH nests them as `options` → groups → options,
 * and the selector value is the opaque `["provider","model"]` form the adapter passes back to `set_config_option`.
 */
internal fun deepSeekNativeModels(metadata: JsonObject): List<DeepSeekNativeModel> {
    val options = metadata["configOptions"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
    val model = options.firstOrNull { it.string("category") == "model" } ?: return emptyList()
    return model["options"]?.jsonArray.orEmpty()
        .flatMap { group ->
            (group as? JsonObject)?.get("options")?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        }
        .mapNotNull { entry ->
            val value = entry.string("value") ?: return@mapNotNull null
            DeepSeekNativeModel(value, entry.string("name") ?: value)
        }
}

internal fun List<DeepSeekNativeModel>.modelInfo(engine: EngineId, binding: EngineBindingId): List<ModelInfo> =
    map { model -> ModelInfo(EngineTarget(engine, binding, ModelId(model.value)), model.title) }

/** Whether the session advertises a config option the adapter may select. */
internal fun JsonObject.hasConfigOption(id: String): Boolean = this["configOptions"]?.jsonArray.orEmpty().any { entry ->
    (entry as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == id
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
