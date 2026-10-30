package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal data class KimiModelCatalog(
    val models: List<KimiNativeModel>,
    val efforts: List<String>,
    val defaultEffort: String?,
    val inputSupport: PromptInputSupport,
)

internal data class KimiNativeModel(val id: String, val title: String)

internal fun kimiNativeCatalog(metadata: JsonObject, initialization: JsonObject): KimiModelCatalog {
    val options = metadata["configOptions"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
    val models = options.firstOrNull { it.string("category") == "model" }.options().mapNotNull { option ->
        val id = option.string("value") ?: return@mapNotNull null
        KimiNativeModel(id, option.string("name") ?: id)
    }.ifEmpty { listOf(KimiNativeModel("kimi-for-coding", "Kimi for Coding")) }
    val thinking = options.firstOrNull { it.string("category") == "thought_level" }
    val promptCapabilities = initialization["promptCapabilities"] as? JsonObject
    val hasImageSupport = (promptCapabilities?.get("image") as? JsonPrimitive)?.booleanOrNull == true
    val hasEmbeddedContext = (promptCapabilities?.get("embeddedContext") as? JsonPrimitive)?.booleanOrNull == true
    return KimiModelCatalog(
        models,
        thinking.options().mapNotNull { it.string("value") },
        thinking?.string("currentValue"),
        PromptInputSupport(
            imageMediaTypes = if (hasImageSupport) KimiImageMediaTypes else emptySet(),
            resourceMediaTypes = if (hasEmbeddedContext) {
                PromptInputSupport.TextDocuments.resourceMediaTypes
            } else {
                emptySet()
            },
        ),
    )
}

internal fun KimiModelCatalog.modelInfo(engine: EngineId, binding: EngineBindingId): List<ModelInfo> =
    models.map { model ->
        ModelInfo(
            EngineTarget(engine, binding, ModelId(model.id)),
            model.title,
            reasoningEfforts = efforts,
            defaultReasoningEffort = defaultEffort,
            inputSupport = inputSupport,
        )
    }

internal fun nativeKimiModel(model: ModelId): String = when (model.value) {
    "kimi-for-coding" -> "kimi-code/kimi-for-coding"
    else -> model.value
}

private fun JsonObject?.options(): List<JsonObject> =
    this?.get("options")?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal val KimiImageMediaTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
