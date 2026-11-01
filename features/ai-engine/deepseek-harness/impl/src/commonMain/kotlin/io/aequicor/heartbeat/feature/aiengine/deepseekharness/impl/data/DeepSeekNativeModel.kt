package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A native DeepSeek model: an opaque selector value (the config-option wire form) and its display title. */
internal data class DeepSeekNativeModel(val value: String, val title: String)

/**
 * Native models plus the prompt support negotiated by the ACP connection. DSH advertises image prompts for the
 * connection's configured route and re-validates each session's exact model before accepting an image; the adapter
 * mirrors that with [imageModelIds], so models outside the set keep an empty support set.
 */
internal data class DeepSeekModelCatalog(
    val models: List<DeepSeekNativeModel>,
    val inputSupport: PromptInputSupport,
    val imageModelIds: Set<String>,
)

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

/** Models and the capability the ACP bridge advertised during `initialize` for [imageModelIds]. */
internal fun deepSeekNativeCatalog(
    metadata: JsonObject,
    capabilities: JsonObject,
    imageModelIds: Set<String>,
): DeepSeekModelCatalog = DeepSeekModelCatalog(
    models = deepSeekNativeModels(metadata),
    inputSupport = deepSeekPromptInputSupport(capabilities),
    imageModelIds = imageModelIds,
)

/**
 * Reads `agentCapabilities.promptCapabilities.image` from the ACP `initialize` result. The flag is connection-wide:
 * DSH derives it from the provider/model configured for the `acp` profile and re-validates the exact session route
 * before accepting an image, while ACP exposes no per-model capability. The installed bridge never advertises
 * `embeddedContext`, so document blocks stay unsupported.
 */
internal fun deepSeekPromptInputSupport(capabilities: JsonObject): PromptInputSupport {
    val prompt = capabilities["promptCapabilities"] as? JsonObject
    val isImageSupported = (prompt?.get("image") as? JsonPrimitive)?.booleanOrNull == true
    return PromptInputSupport(
        imageMediaTypes = if (isImageSupported) DeepSeekImageMediaTypes else emptySet(),
    )
}

/** Raster formats the DSH attachment store and ACP image blocks both accept. */
internal val DeepSeekImageMediaTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

/**
 * Model ids (resolved by [dshSelectedModel]) whose native catalog entries declare image input. ACP exposes no
 * per-model capability, and DSH re-validates every inline image against the exact session route: the account
 * route `deepseek-v4-pro` rejects an image with a JSON-RPC `-32602`, so it must not be advertised as multimodal.
 * A model outside this set stays text-only until DSH reports per-model capability.
 */
internal val DeepSeekImageModelIds: Set<String> = setOf("deepseek-flash")

internal fun DeepSeekModelCatalog.modelInfo(engine: EngineId, binding: EngineBindingId): List<ModelInfo> =
    models.map { model ->
        val isImageModel = dshSelectedModel(model.value) in imageModelIds
        ModelInfo(
            EngineTarget(engine, binding, ModelId(model.value)),
            model.title,
            inputSupport = if (isImageModel) inputSupport else PromptInputSupport(),
        )
    }

/** Model id of an opaque `["provider","model"]` selector; a bare legacy id is already a model id. */
internal fun dshSelectedModel(value: String): String {
    if (!value.startsWith("[")) return value
    val selector = Json.parseToJsonElement(value) as? JsonArray ?: return value
    return (selector.lastOrNull() as? JsonPrimitive)?.contentOrNull ?: value
}

/** Whether the session advertises a config option the adapter may select. */
internal fun JsonObject.hasConfigOption(id: String): Boolean = array("configOptions").any { entry ->
    entry.string("id") == id
}

private fun JsonObject.array(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
