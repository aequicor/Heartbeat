package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.answerOf
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Converts authoritative native evidence into exact helper receipts; missing handles/history never imply exit. */
@Inject
internal class StudioHelperNative(
    private val facade: EngineFacade,
    private val tools: ProfileAgentTools,
    private val attempts: StudioHelperAttempts,
) {
    private val log = Log.tag("StudioHelperNative")

    suspend fun remember(
        record: StudioChatRecord,
        request: RequestId,
        session: SessionRef,
        turn: TurnId,
        outcome: TurnOutcome,
        items: List<SessionItem>,
    ) {
        val helper = HelperId(record.id)
        if (attempts.receipt(helper, request)?.terminal != null) return
        check(record.ref == session) { "Helper native identity changed" }
        val settled = confirmed(record, request, turn, outcome) ?: return
        attempts.terminal(helper, request, terminal(session, turn, settled, items))
        log.v { "Saved exact helper terminal evidence" }
    }

    suspend fun result(
        record: StudioChatRecord,
        request: RequestId,
        open: suspend () -> ActiveSession,
    ): StudioHelperTerminal? = evidence {
        val active = open()
        check(active.ref == record.ref) { "Helper native identity changed" }
        val turn = active.state.value.lastCompletedTurn()?.takeIf {
            it.request == request && active.state.value.isTerminalFor(it.id) && it.outcome != null
        } ?: return@evidence null
        tools.finishTurn(active.ref, turn.id)
        val settled = confirmed(record, request, turn.id, checkNotNull(turn.outcome)) ?: return@evidence null
        terminal(active.ref, turn.id, settled, read(active.features.requireFeature(SessionHistory)))
    }

    suspend fun stop(record: StudioChatRecord, request: RequestId, active: ActiveSession?): StudioHelperTerminal? =
        evidence {
            val ref = record.ref ?: return@evidence null
            val target = record.target ?: return@evidence null
            val receipt = attempts.receipt(HelperId(record.id), request) ?: return@evidence null
            val capability = facade.engines.features(ref.engine).resolve(StopsOwnedTurns)
            val turn = if (capability is FeatureAccess.Available) {
                val result = capability.feature.stop(
                    ref,
                    request,
                    OwnedTurnAccess(target, record.resolvedExecutionWorkspace(), receipt.turn),
                )
                (result as? OwnedTurnStop.Confirmed)?.turn ?: return@evidence null
            } else if (capability == FeatureAccess.Unsupported) {
                stopLive(active?.takeIf { it.ref == ref }, request, receipt.turn) ?: return@evidence null
            } else {
                return@evidence null
            }
            check(turn.request == request && (receipt.turn == null || receipt.turn == turn.id)) {
                "Helper stop returned another request"
            }
            tools.finishTurn(ref, turn.id)
            val outcome = checkNotNull(turn.outcome)
            val items = stoppedHistory(ref, active, outcome) ?: return@evidence null
            terminal(ref, turn.id, outcome, items)
        }

    private suspend fun stoppedHistory(
        ref: SessionRef,
        active: ActiveSession?,
        outcome: TurnOutcome,
    ): List<SessionItem>? {
        if (outcome == TurnOutcome.Unknown) return emptyList()
        val history = active?.takeIf { it.ref == ref }?.features?.resolve(SessionHistory) as? FeatureAccess.Available
        val stored = history?.feature
            ?: (facade.sessions.get(ref).features.resolve(SessionHistory) as? FeatureAccess.Available)?.feature
        return stored?.let { read(it) }
    }

    /** Native terminal can precede an adapter-owned tool's cleanup; the engine owns that extra barrier. */
    private suspend fun confirmed(
        record: StudioChatRecord,
        request: RequestId,
        turn: TurnId,
        outcome: TurnOutcome,
    ): TurnOutcome? = when (
        val capability = facade.engines.features(
            checkNotNull(record.ref).engine,
        ).resolve(StopsOwnedTurns)
    ) {
        is FeatureAccess.Available -> {
            val result = capability.feature.stop(
                record.ref,
                request,
                OwnedTurnAccess(checkNotNull(record.target), record.resolvedExecutionWorkspace(), turn),
            ) as? OwnedTurnStop.Confirmed
            result?.turn?.takeIf { it.id == turn && it.request == request }?.outcome
        }

        is FeatureAccess.Unavailable -> null

        FeatureAccess.Unsupported -> outcome
    }

    private suspend fun stopLive(active: ActiveSession?, request: RequestId, expected: TurnId?): Turn? {
        if (active == null) return null
        val state = active.state.value
        val terminal = state.lastCompletedTurn()?.takeIf {
            it.request == request && (expected == null || expected == it.id) && state.isTerminalFor(it.id)
        }
        val turn = state.activeTurn()?.takeIf { it.request == request && (expected == null || expected == it.id) }
        val capability = active.features.resolve(CancelsTurns) as? FeatureAccess.Available
        return when {
            terminal != null -> terminal

            turn == null || capability == null -> null

            else -> {
                capability.feature.cancel(turn.id)
                withTimeoutOrNull(STOP_WAIT_MILLIS) {
                    active.state.first { it.isTerminalFor(turn.id) }.lastCompletedTurn()
                }
            }
        }
    }

    internal suspend fun read(history: SessionHistory): List<SessionItem> {
        val items = mutableListOf<SessionItem>()
        val seen = mutableSetOf<HistoryCursor>()
        var cursor: HistoryCursor? = null
        do {
            val page = history.page(HistoryPageRequest(cursor))
            items.addAll(0, page.items)
            cursor = page.older
            check(cursor == null || seen.add(cursor)) { "Repeated helper history cursor" }
        } while (cursor != null)
        return items
    }

    private fun terminal(ref: SessionRef, turn: TurnId, outcome: TurnOutcome, items: List<SessionItem>) =
        StudioHelperTerminal(
            ref,
            turn,
            when (outcome) {
                TurnOutcome.Completed -> StudioHelperTerminalOutcome.Completed
                is TurnOutcome.Failed -> StudioHelperTerminalOutcome.Failed
                TurnOutcome.Cancelled -> StudioHelperTerminalOutcome.Cancelled
                TurnOutcome.Unknown -> StudioHelperTerminalOutcome.Unknown
            },
            answerOf(items, turn, SchedulerLimits.MAX_PAYLOAD, isMarkedOnly = true).orEmpty(),
        )

    private suspend fun <T> evidence(block: suspend () -> T?): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Helper operation failed (${error::class.simpleName.orEmpty()})")) {
            "Helper native evidence unavailable"
        }
        null
    }

    private companion object {
        const val STOP_WAIT_MILLIS = 30_000L
    }
}
