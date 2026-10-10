package io.aequicor.heartbeat.feature.harness.api.workflow

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/**
 * A completed memo can contain JSON null, which is distinct from the Kotlin null of an unfinished step.
 * The non-null envelope preserves that distinction with explicitNulls and encodeDefaults in either mode.
 */
internal object WorkflowStepResultSerializer : KSerializer<JsonElement?> {
    private val delegate = StoredStepResult.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: JsonElement?) {
        encoder.encodeSerializableValue(delegate, value?.let(::StoredStepResult))
    }

    override fun deserialize(decoder: Decoder): JsonElement? = decoder.decodeSerializableValue(delegate)?.value
}

@Serializable
private data class StoredStepResult(val value: JsonElement) {
    override fun toString(): String = "StoredStepResult(***)"
}
