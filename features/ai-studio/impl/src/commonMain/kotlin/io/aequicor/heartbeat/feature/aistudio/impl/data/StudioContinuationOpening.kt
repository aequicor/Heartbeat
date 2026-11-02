package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.flow.first

/** Resolves a continuation from its receipt, independently of the next human turn's model preference. */
internal suspend fun StudioTurnRequest.openContinuation(
    records: StudioChatRecords,
    selections: ModelSelections,
    configurations: StudioConfigurationController,
    live: suspend (HelperId) -> ActiveSession?,
    open: suspend (EngineTarget) -> ActiveSession,
): ActiveSession? {
    val gate = checkNotNull(submission as? StudioHelperSubmission) { "Native opening requires journal admission" }
    gate.requireOpening()
    val snapshot = gate.continuationReceipt()?.executionSnapshot ?: return null
    snapshot.requireContinuationConnection(records.record(id))
    check(selections.observe().first().isEnabled(snapshot.target)) { "The continuation model is no longer enabled" }
    configurations.freeze(id, live(HelperId(id)))
    return open(snapshot.target)
}

/** Independent turns use current preferences; continuation admission subsequently restores its source snapshot. */
internal fun StudioTurnRequest.withCurrentSettings(record: StudioChatRecord): StudioTurnRequest =
    if (record.target == null && record.configuration == null) {
        this
    } else {
        copy(settings = hostRunSettings(record, record.configuration, settings))
    }
