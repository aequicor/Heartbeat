package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable

/** Fresh native reconciliation, rather than absence of a receipt or an empty cached Ready state. */
@Serializable
internal enum class StudioLegacyPhase { Idle, Terminal, Active, Unknown }

/** Legacy request correlation survives the first universal writer without inventing a native send receipt. */
@Serializable
internal data class StudioLegacyObservation(
    val request: RequestId,
    val session: SessionRef,
    val target: EngineTarget,
    val workspace: WorkspaceRef?,
    val phase: StudioLegacyPhase,
    val turn: TurnId? = null,
    val outcome: StudioHelperTerminalOutcome? = null,
    val isStopRequested: Boolean = false,
) {
    init {
        require(session.engine == target.engine)
        require(phase != StudioLegacyPhase.Idle || (turn == null && outcome == null))
        require(
            phase != StudioLegacyPhase.Terminal || outcome in setOf(
                StudioHelperTerminalOutcome.Completed,
                StudioHelperTerminalOutcome.Failed,
                StudioHelperTerminalOutcome.Cancelled,
            ),
        )
    }

    fun updated(incoming: StudioLegacyObservation): StudioLegacyObservation {
        check(
            request == incoming.request && session == incoming.session && target == incoming.target &&
                workspace == incoming.workspace,
        ) { "Legacy observation identity changed" }
        return if (phase == StudioLegacyPhase.Terminal) {
            check(turn == null || incoming.turn == null || turn == incoming.turn) { "Legacy native turn changed" }
            check(incoming.outcome == null || incoming.outcome == outcome) { "Legacy native outcome changed" }
            copy(turn = turn ?: incoming.turn, isStopRequested = isStopRequested || incoming.isStopRequested)
        } else {
            incoming.copy(isStopRequested = isStopRequested || incoming.isStopRequested)
        }
    }

    override fun toString(): String = "StudioLegacyObservation(phase=$phase)"
}

/** Only an authoritative Idle decision can admit a new independent request without an old terminal receipt. */
@Inject
internal class StudioLegacyRecovery(private val native: Lazy<StudioHelperNative>) {
    private val log = Log.tag("StudioLegacyRecovery")

    suspend fun inspect(record: StudioChatRecord, request: RequestId, active: ActiveSession): StudioLegacyObservation {
        val initial = StudioLegacyObservation(
            request,
            checkNotNull(record.ref),
            checkNotNull(record.target),
            record.resolvedExecutionWorkspace(),
            StudioLegacyPhase.Unknown,
        )
        check(active.ref == initial.session) { "Legacy recovery opened another native session" }
        check(active.route.binding == initial.target.binding && active.route.workspace == initial.workspace) {
            "Legacy recovery opened another native route"
        }
        log.v { "Reconcile legacy request before enabling universal admission" }
        return when (val feature = active.features.resolve(RestoresSessionTurns)) {
            is FeatureAccess.Available -> inspected(initial, active, feature.feature.inspect(null))

            is FeatureAccess.Unavailable -> initial

            FeatureAccess.Unsupported -> {
                val terminal = native.value.result(record, request) { active }
                if (terminal != null && terminal.outcome != StudioHelperTerminalOutcome.Unknown) {
                    initial.copy(phase = StudioLegacyPhase.Terminal, turn = terminal.turn, outcome = terminal.outcome)
                } else {
                    initial.copy(
                        phase = if (active.state.value.activeTurn() != null) {
                            StudioLegacyPhase.Active
                        } else {
                            StudioLegacyPhase.Unknown
                        },
                    )
                }
            }
        }
    }

    private fun inspected(
        initial: StudioLegacyObservation,
        active: ActiveSession,
        inspection: TurnInspection,
    ): StudioLegacyObservation = when (inspection) {
        TurnInspection.Idle -> initial.copy(phase = StudioLegacyPhase.Idle)

        TurnInspection.Unknown -> initial

        is TurnInspection.Observed -> {
            val outcome = inspection.outcome
            when {
                outcome == null -> initial.copy(phase = StudioLegacyPhase.Active)

                inspection.request != initial.request || outcome == TurnOutcome.Unknown -> initial

                else -> initial.copy(
                    phase = StudioLegacyPhase.Terminal,
                    turn = active.state.value.lastCompletedTurn()?.takeIf { it.request == initial.request }?.id,
                    outcome = when (outcome) {
                        TurnOutcome.Completed -> StudioHelperTerminalOutcome.Completed
                        TurnOutcome.Cancelled -> StudioHelperTerminalOutcome.Cancelled
                        is TurnOutcome.Failed -> StudioHelperTerminalOutcome.Failed
                        TurnOutcome.Unknown -> StudioHelperTerminalOutcome.Unknown
                    },
                )
            }
        }
    }
}

/** Legacy terminal-only evidence has no fabricated answer or turn; fresh Idle is not an outcome. */
internal fun StudioLegacyObservation.terminalResult(): HostConversationResult? {
    if (phase != StudioLegacyPhase.Terminal) return null
    val nativeOutcome = when (checkNotNull(outcome)) {
        StudioHelperTerminalOutcome.Completed -> TurnOutcome.Completed
        StudioHelperTerminalOutcome.Cancelled -> TurnOutcome.Cancelled
        StudioHelperTerminalOutcome.Failed -> TurnOutcome.Failed(EngineFailure.Unknown())
        StudioHelperTerminalOutcome.Unknown -> error("Unknown legacy evidence is not terminal")
    }
    return HostConversationResult(request, nativeOutcome, "", isAnswerComplete = false)
}
