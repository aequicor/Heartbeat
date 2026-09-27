package io.aequicor.heartbeat.core.secrets.impl.data

import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretUsage
import io.aequicor.heartbeat.core.secrets.impl.domain.SecretSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class VaultCodec {
    fun decode(bytes: ByteArray?): SecretSnapshot {
        if (bytes == null) return SecretSnapshot()
        val dto = Json.decodeFromString<VaultState>(bytes.decodeToString())
        var isTransferred = false
        try {
            check(dto.version == 1) { "Unsupported vault version" }
            val references = dto.references.associate { it.usage() to SecretKey(it.key) }
            check(references.size == dto.references.size) { "Duplicate vault references" }
            val values = dto.values.mapKeys { SecretKey(it.key) }
            check(references.values.all { it in values }) { "Invalid vault references" }
            // Ownership of decoded buffers transfers to the transaction snapshot.
            val snapshot = SecretSnapshot(values.toMutableMap(), references.toMutableMap())
            isTransferred = true
            return snapshot
        } finally {
            if (!isTransferred) dto.values.values.forEach { it.fill('\u0000') }
        }
    }

    fun encode(snapshot: SecretSnapshot): ByteArray {
        val dto = VaultState(
            values = snapshot.values.mapKeys { it.key.value },
            references = snapshot.references.map { (usage, key) ->
                VaultReference(usage.type, usage.id, usage.slot, key.value)
            },
        )
        return Json.encodeToString(dto).encodeToByteArray()
    }
}

@Serializable
@Suppress("UseDataClass") // Generated copies and toString must not expose sensitive values.
private class VaultState(
    val version: Int = 1,
    val values: Map<String, CharArray> = emptyMap(),
    val references: List<VaultReference> = emptyList(),
)

@Serializable
private data class VaultReference(val type: String, val id: String, val slot: String, val key: String) {
    fun usage(): SecretUsage = SecretUsage(type, id, slot)
}
