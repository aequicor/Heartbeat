package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** Storage helpers run only under StudioHelperAttempts' shared send/stop mutex. */
internal suspend fun KeyValueStore.readLegacyObservations(helper: HelperId): Map<String, StudioLegacyObservation> {
    val raw = get(legacyKey(helper)) ?: return emptyMap()
    return try {
        Json.decodeFromString<Map<String, StudioLegacyObservation>>(raw).also { records ->
            check(records.all { (key, value) -> key == value.request.value }) { "Legacy observation key changed" }
        }
    } catch (error: IllegalArgumentException) {
        throw LegacyJournalException(error::class.simpleName.orEmpty())
    }
}

/** Caller holds route → journal locks and has excluded an existing send receipt for this legacy request. */
internal suspend fun KeyValueStore.rememberLegacyObservation(
    helper: HelperId,
    record: StudioChatRecord,
    observation: StudioLegacyObservation,
) {
    check(observation.phase in setOf(StudioLegacyPhase.Idle, StudioLegacyPhase.Terminal)) {
        "Unresolved legacy activity cannot authorize a new writer"
    }
    check(record.lastRunRequest == observation.request && record.ref == observation.session) {
        "Legacy native identity changed during inspection"
    }
    check(record.target == observation.target && record.resolvedExecutionWorkspace() == observation.workspace) {
        "Legacy native route changed during inspection"
    }
    val records = readLegacyObservations(helper)
    val retained = records[observation.request.value]?.updated(observation) ?: observation
    set(legacyKey(helper), Json.encodeToString(records + (observation.request.value to retained)))
}

/** Stop changes intent only, never the native phase or its outcome. Caller holds the journal mutex. */
internal suspend fun KeyValueStore.stopLegacyObservation(
    helper: HelperId,
    request: RequestId,
): StudioLegacyObservation? {
    val records = readLegacyObservations(helper)
    val previous = records[request.value] ?: return null
    val stopped = previous.copy(isStopRequested = true)
    set(legacyKey(helper), Json.encodeToString(records + (request.value to stopped)))
    return stopped
}

private fun legacyKey(helper: HelperId) = stringKey("legacy_${helper.value.encodeUtf8().sha256().hex()}")

/** Decoder errors can contain private stored values; retain only their type. */
private class LegacyJournalException(type: String) : IllegalStateException("Unreadable legacy observation ($type)")
