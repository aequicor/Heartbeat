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

    /**
     * Saves terminal evidence of a journaled helper request. History is read only for an unresolved receipt; a
     * failure leaves the receipt for later reconciliation and never fails the observed turn.
     */
    suspend fun remember(
        record: StudioChatRecord,
        request: RequestId,
        session: SessionRef,
        turn: TurnId,
        outcome: TurnOutcome,
        items: suspend () -> List<SessionItem>,
    ) {
        evidence {
            val helper = HelperId(record.id)
            val receipt = attempts.receipt(helper, request)
            if (receipt == null) log.w { "Helper turn has no journaled request; terminal evidence is not saved" }
            if (receipt == null || receipt.phase !in NATIVE_PHASES) return@evidence null
            check(record.ref == session) { "Helper native identity changed" }
            val settled = confirmed(record, Turn(turn, request, checkNotNull(record.target), outcome))
                ?: return@evidence null
            attempts.terminal(helper, request, terminal(session, turn, settled, items()))
            log.v { "Saved exact helper terminal evidence" }
        }
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
        val receipt = attempts.receipt(HelperId(record.id), request)
        check(receipt?.session == null || receipt.session == active.ref) { "Helper receipt session changed" }
        val localTurn = receipt?.turn ?: turn.id
        val settled = confirmed(record, turn) ?: return@evidence null
        // Cleanup publishes turn_finished. An unconfirmed poll must not wake its own result observer.
        tools.finishTurn(active.ref, localTurn, request)
        terminal(active.ref, localTurn, settled, read(active.features.requireFeature(SessionHistory)))
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
                    OwnedTurnAccess(target, record.resolvedExecutionWorkspace()),
                )
                (result as? OwnedTurnStop.Confirmed)?.turn ?: return@evidence null
            } else if (capability == FeatureAccess.Unsupported) {
                stopLive(active?.takeIf { it.ref == ref }, request, receipt.turn) ?: return@evidence null
            } else {
                return@evidence null
            }
            check(turn.request == request) {
                "Helper stop returned another request"
            }
            val localTurn = receipt.turn ?: turn.id
            tools.finishTurn(ref, localTurn, request)
            val outcome = checkNotNull(turn.outcome)
            val items = stoppedHistory(ref, active, outcome) ?: return@evidence null
            terminal(ref, localTurn, turn, items)
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

    /**
     * Native terminal can precede an adapter-owned tool's cleanup; the engine owns that extra barrier. Helper
     * receipts contain facade turn ids, so the adapter resolves the immutable request under its exact route.
     * Never pass a facade id as expectedTurn: the adapter journals a different id, also after a restart.
     */
    private suspend fun confirmed(record: StudioChatRecord, turn: Turn): Turn? = when (
        val capability = facade.engines.features(
            checkNotNull(record.ref).engine,
        ).resolve(StopsOwnedTurns)
    ) {
        is FeatureAccess.Available -> {
            val result = capability.feature.stop(
                record.ref,
                checkNotNull(turn.request),
                OwnedTurnAccess(checkNotNull(record.target), record.resolvedExecutionWorkspace()),
            ) as? OwnedTurnStop.Confirmed
            result?.turn?.takeIf { it.request == turn.request }
        }

        is FeatureAccess.Unavailable -> null

        FeatureAccess.Unsupported -> turn
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

    /** Receipt identity stays in facade ids; history is marked with the adapter's confirmed turn id. */
    private fun terminal(ref: SessionRef, turn: TurnId, native: Turn, items: List<SessionItem>) = StudioHelperTerminal(
        ref,
        turn,
        when (checkNotNull(native.outcome)) {
            TurnOutcome.Completed -> StudioHelperTerminalOutcome.Completed
            is TurnOutcome.Failed -> StudioHelperTerminalOutcome.Failed
            TurnOutcome.Cancelled -> StudioHelperTerminalOutcome.Cancelled
            TurnOutcome.Unknown -> StudioHelperTerminalOutcome.Unknown
        },
        // Engines mark history with their own turn ids, which differ from the facade turn (Koog), so only a turn
        // that completed may fall back to the messages after its prompt; any other outcome needs exact marks.
        answerOf(
            items,
            native.id,
            SchedulerLimits.MAX_PAYLOAD,
            isMarkedOnly = native.outcome != TurnOutcome.Completed,
        ).orEmpty(),
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

        /** Unresolved native phases; terminal, revoked or missing receipts never read history again. */
        val NATIVE_PHASES = setOf(StudioHelperPhase.Submitting, StudioHelperPhase.Accepted)
    }
}
