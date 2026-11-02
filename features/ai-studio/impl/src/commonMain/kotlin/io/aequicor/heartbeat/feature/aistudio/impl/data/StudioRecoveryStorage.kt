package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** Storage operations are serialized by StudioHelperAttempts' shared journal mutex. */
internal suspend fun KeyValueStore.readRecoveryWitnesses(helper: HelperId): Map<String, StudioRecoveryWitness> {
    val raw = get(recoveryKey(helper)) ?: return emptyMap()
    return try {
        Json.decodeFromString<Map<String, StudioRecoveryWitness>>(raw).also(::validateRecoveryLineage)
    } catch (error: IllegalArgumentException) {
        throw RecoveryStorageException(error::class.simpleName.orEmpty())
    }
}

/** Caller holds the same mutex as native entry, cancellation and receipt writes. */
internal suspend fun KeyValueStore.writeRecoveryWitnesses(
    helper: HelperId,
    records: Map<String, StudioRecoveryWitness>,
) {
    validateRecoveryLineage(records)
    set(recoveryKey(helper), Json.encodeToString(records))
}

private fun recoveryKey(helper: HelperId) = stringKey("recovery_${helper.value.encodeUtf8().sha256().hex()}")

/** Decoder diagnostics can contain private stored work. */
private class RecoveryStorageException(type: String) : IllegalStateException("Unreadable recovery witnesses ($type)")
