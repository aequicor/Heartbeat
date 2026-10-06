package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Native callbacks also cover manual turns in helper chats; normal chats never create helper receipts. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class StudioHelperTurnObserver(
    private val access: Lazy<StudioHelperAccess>,
    private val attempts: StudioHelperAttempts,
    private val native: StudioHelperNative,
) : StudioTurnObserver {
    override suspend fun acceptedTurn(request: StudioTurnRequest, active: ActiveSession, turn: TurnId) {
        val record = access.value.helperRecord(HelperId(request.id))
        if (record.helper != null) attempts.accepted(HelperId(record.id), request.request, active.ref, turn)
    }

    override suspend fun terminalTurn(
        request: StudioTurnRequest,
        active: ActiveSession,
        turn: TurnId,
        outcome: TurnOutcome,
        isHistoryCurrent: Boolean,
    ) {
        val record = access.value.helperRecord(HelperId(request.id))
        if (record.helper != null) {
            native.remember(
                record,
                request.request,
                active.ref,
                turn,
                outcome,
                native.read(active.features.requireFeature(SessionHistory)),
            )
        }
    }
}
