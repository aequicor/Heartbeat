package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.flow.first

/** Native callbacks also cover manual turns in helper chats; normal chats never create helper receipts. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class StudioHelperTurnObserver(
    private val access: Lazy<StudioHelperAccess>,
    private val attempts: StudioHelperAttempts,
    private val native: StudioHelperNative,
    private val worktrees: StudioWorktrees? = null,
) : StudioTurnObserver {
    override suspend fun acceptedTurn(request: StudioTurnRequest, active: ActiveSession, turn: TurnId) {
        val record = access.value.helperRecord(HelperId(request.id))
        if (record.helper != null || attempts.receipt(HelperId(record.id), request.request) != null) {
            val helper = HelperId(record.id)
            val task = worktrees?.tasks()?.first()?.get(record.id)
            if (request.request.value in task?.virtualContinuations?.values.orEmpty()) {
                attempts.accepted(helper, request.request, active.ref, turn, isVirtualContinuation = true)
            } else {
                attempts.accepted(helper, request.request, active.ref, turn)
            }
            if (attempts.receipt(helper, request.request)?.isStopRequested == true &&
                active.state.value.activeTurn()?.id == turn
            ) {
                (active.features.resolve(CancelsTurns) as? FeatureAccess.Available)?.feature?.cancel(turn)
            }
        }
    }

    override suspend fun terminalTurn(
        request: StudioTurnRequest,
        active: ActiveSession,
        turn: TurnId,
        outcome: TurnOutcome,
        isHistoryCurrent: Boolean,
    ) {
        val record = access.value.helperRecord(HelperId(request.id))
        if (record.helper != null || attempts.receipt(HelperId(record.id), request.request) != null) {
            native.remember(record, request.request, active.ref, turn, outcome) {
                native.read(active.features.requireFeature(SessionHistory))
            }
        }
    }

    /** Recovery may enrich an existing R receipt, never create one from a graph's recovery list. */
    override suspend fun adoptedTurn(request: StudioTurnRequest, active: ActiveSession, native: StudioNativeTurn) {
        val helper = HelperId(request.id)
        val receipt = attempts.receipt(helper, native.request) ?: return
        if (receipt.session == active.ref && receipt.turn == native.turn) {
            attempts.accepted(helper, native.request, active.ref, native.turn)
        }
    }

    override suspend fun terminalAdoptedTurn(
        request: StudioTurnRequest,
        active: ActiveSession,
        native: StudioNativeTurn,
        outcome: TurnOutcome,
        isHistoryCurrent: Boolean,
    ) {
        val helper = HelperId(request.id)
        val receipt = attempts.receipt(helper, native.request) ?: return
        if (receipt.session != active.ref || receipt.turn != native.turn) return
        nativeRemember(request.id, active, native, outcome)
    }

    private suspend fun nativeRemember(
        id: String,
        active: ActiveSession,
        observed: StudioNativeTurn,
        outcome: TurnOutcome,
    ) {
        val record = access.value.helperRecord(HelperId(id))
        native.remember(record, observed.request, active.ref, observed.turn, outcome) {
            native.read(active.features.requireFeature(SessionHistory))
        }
    }
}
