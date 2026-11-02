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
    val snapshot = gate.continuationReceipt()?.executionSnapshot
    val target = snapshot?.target ?: graphWork?.target?.takeIf { origin is StudioTurnOrigin.GraphWork } ?: return null
    val record = records.record(id)
    if (snapshot != null) {
        snapshot.requireContinuationConnection(record)
    } else {
        check(record.pendingEngineSwitch == null) { "Graph connection replacement is unresolved" }
    }
    check(selections.observe().first().isEnabled(target)) { "The execution model is no longer enabled" }
    configurations.freeze(id, live(HelperId(id)))
    return open(target)
}

/** Independent turns use current preferences; continuation admission subsequently restores its source snapshot. */
internal fun StudioTurnRequest.withCurrentSettings(record: StudioChatRecord): StudioTurnRequest =
    if (origin is StudioTurnOrigin.GraphWork || (record.target == null && record.configuration == null)) {
        this
    } else {
        copy(settings = hostRunSettings(record, record.configuration, settings))
    }
