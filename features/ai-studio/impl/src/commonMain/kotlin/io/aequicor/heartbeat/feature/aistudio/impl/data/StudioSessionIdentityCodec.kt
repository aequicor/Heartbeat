package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Durable migration provenance; unresolved links grant no new creator or owner authority. */
internal enum class StudioIdentityOrigin {
    UserConversation,
    AgentCreated,
    LegacyInteractiveHelper,
    InternalExecution,
    Unresolved,
}

/** Opaque persisted envelope: unknown versions, enums and invalid relationships round-trip without losing data. */
@Serializable(with = StudioSessionIdentityCodec::class)
internal data class StudioSessionIdentity(val raw: JsonElement) {
    constructor(
        origin: StudioIdentityOrigin,
        context: LogicalSessionContext? = null,
        version: Int = 1,
    ) : this(identityPayload(origin, context, version))

    val version: Int
        get() {
            val envelope = raw as? JsonObject ?: return -1
            val stored = envelope["version"] ?: return 1
            return (stored as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: -1
        }
    val origin: StudioIdentityOrigin? get() = StudioIdentityOrigin.entries.firstOrNull {
        it.name == (raw as? JsonObject)?.get("origin").identityString()
    }
    val context: LogicalSessionContext?
        get() = if (version == 1 && origin != null && origin != StudioIdentityOrigin.Unresolved) {
            ((raw as? JsonObject)?.get("context") as? JsonObject)?.logicalContext()
        } else {
            null
        }
}

private fun identityPayload(origin: StudioIdentityOrigin, context: LogicalSessionContext?, version: Int): JsonObject =
    buildJsonObject {
        put("version", version)
        put("origin", origin.name)
        if (context != null) put("context", Json.encodeToJsonElement(LogicalSessionContext.serializer(), context))
    }

/** Defers interpretation until after decoding the entire healthy chat list. */
internal object StudioSessionIdentityCodec : KSerializer<StudioSessionIdentity> {
    override val descriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder) = StudioSessionIdentity(
        decoder.decodeSerializableValue(JsonElement.serializer()),
    )
    override fun serialize(encoder: Encoder, value: StudioSessionIdentity) =
        encoder.encodeSerializableValue(JsonElement.serializer(), value.raw)
}

private fun JsonElement?.identityString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.logicalId(name: String): LogicalSessionId? =
    ((get(name) as? JsonObject)?.get("value").identityString())?.takeIf { it.isNotBlank() }?.let(::LogicalSessionId)

private fun JsonObject.logicalContext(): LogicalSessionContext? {
    val id = logicalId("id") ?: return null
    val kind = SessionKind.entries.firstOrNull { it.name == get("kind").identityString() } ?: return null
    val root = if (containsKey("root")) logicalId("root") ?: return null else id
    val creator = logicalId("creator")
    val owner = logicalId("owner")
    if (get("creator") != null && get("creator") != JsonNull && creator == null) return null
    if (get("owner") != null && get("owner") != JsonNull && owner == null) return null
    return LogicalSessionContext.createOrNull(id, kind, root, creator, owner)
}
