package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Production reservation runs outside coordinator locks and freezes the canonical route before publication. */
internal interface StudioJournaledRecoveryHost :
    StudioRecoveryRunHost,
    StudioTurnHost,
    StudioHelperAccess {
    override val recoveryJournal: StudioRecoveryJournal

    override suspend fun reserveRecovery(request: StudioTurnRequest) {
        prepareRun(request.id)
        recoveryJournal.reserve(request, helperRecord(HelperId(request.id)))
    }

    override suspend fun recordRecoveryStop(request: StudioTurnRequest) {
        recoveryJournal.stop(checkNotNull(request.submission?.recoveryDelivery))
    }

    override suspend fun requireNativeRecovery(request: StudioTurnRequest) {
        recoveryJournal.requireReserved(request, helperRecord(HelperId(request.id)))
    }
}

/** Frozen route is rechecked against canonical persistence, never inferred from a currently visible turn. */
internal fun StudioRecoveryWitness.requireRoute(record: StudioChatRecord) {
    val frozen = checkNotNull(route) { "Recovery route evidence is unavailable" }
    check(delivery?.chat == record.id) { "Recovery belongs to another conversation" }
    check(record.ref == session && record.target?.engine == session.engine) { "Recovery session changed" }
    check(record.target == frozen.target && record.resolvedExecutionWorkspace() == frozen.workspace) {
        "Recovery native target or workspace changed"
    }
    check(record.pendingEngineSwitch == null) { "Recovery must resolve a pending engine switch first" }
}
