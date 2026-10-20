package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperProgress
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.flow.first

/** Uses the repository's existing handle locks and fixed route; does not own another session registry. */
internal interface StudioHelperAccess {
    suspend fun helperRecord(helper: HelperId): StudioChatRecord
    suspend fun openHelper(record: StudioChatRecord): ActiveSession
    suspend fun liveHelper(helper: HelperId): ActiveSession?
}

@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioHelperRuns(
    private val access: Lazy<StudioHelperAccess>,
    private val runtime: Lazy<StudioRuntime>,
    private val host: Lazy<StudioRunHost>,
    private val coordinator: StudioRunCoordinator,
    private val native: StudioHelperNative,
    private val recovery: StudioHelperRecovery,
    private val worktrees: StudioWorktrees? = null,
) : StudioHelperRuns {
    private val log = Log.tag("StudioHelperRuns")

    override suspend fun prepareHelperRoute(helper: HelperId) {
        if (access.value.helperRecord(helper).pendingEngineSwitch == null) return
        log.i { "Recover helper replacement before preparing a new receipt" }
        coordinator.mutate(helper.value) { host.value.prepareRun(helper.value) }
    }

    override suspend fun runHelper(
        helper: HelperId,
        prompt: HelperPrompt,
        submission: StudioHelperSubmission,
        onAccepted: suspend (SessionRef, TurnId) -> Unit,
    ) {
        var accepted: Pair<SessionRef, TurnId>? = null
        val record = access.value.helperRecord(helper)
        val settings = hostRunSettings(
            record,
            runtime.value.state.value.configurations[record.id]?.applied,
            runtime.value.defaults(),
        )
        log.i { "Run a journaled helper request" }
        coordinator.run(
            host.value,
            StudioTurnRequest(
                record.id,
                prompt.text,
                settings,
                WorktreeRunKind.Coding,
                prompt.request,
                onAccepted = {
                    val (session, turn) = checkNotNull(accepted) { "Helper acceptance has no native identity" }
                    onAccepted(session, turn)
                },
                directives = prompt.directives,
                submission = submission,
                onTurnAccepted = { active, turn -> accepted = active.ref to turn },
            ),
            waitForIdle = true,
        )
    }

    override suspend fun helperTerminal(helper: HelperId, request: RequestId): StudioHelperTerminal? {
        val record = access.value.helperRecord(helper)
        log.v { "Read exact helper native result" }
        return if (record.ref == null || record.target == null) {
            null
        } else {
            native.result(record, request) {
                access.value.liveHelper(helper) ?: recovery.open(record) { access.value.openHelper(record) }
            }
        }
    }

    override suspend fun virtualResultRequest(helper: HelperId, request: RequestId): RequestId? {
        val worktrees = worktrees ?: return request
        val task = worktrees.tasks().first()[helper.value] ?: return request
        if (request !in task.virtualInterruptedRequests) return request
        val continuation = task.virtualContinuations[request.value] ?: return null
        val isSandboxReady = task.virtualSandbox?.let {
            it.isReady || it.phase == VirtualSandboxPhase.HandedOff
        } == true
        val isAvailable = isSandboxReady && !task.pendingVirtualContinuation &&
            task.actionRequest == null && task.run?.outcome != null
        return RequestId(continuation).takeIf { isAvailable }
    }

    override suspend fun virtualResultNote(helper: HelperId, request: RequestId): String {
        val worktrees = worktrees ?: return ""
        val record = access.value.helperRecord(helper)
        if (record.worktreeTaskId == null) return ""
        val task = worktrees.tasks().first()[helper.value] ?: return ""
        if (task.virtualSandbox == null) return ""
        val actual = task.virtualContinuations[request.value] ?: request.value
        val snapshot = task.virtualResultSnapshots[actual] ?: return ""
        return "\nHost note: integrate this helper's virtual result with sandbox_integrate: " +
            "workspace=${task.executionWorkspace?.value ?: "null"}, snapshot=$snapshot."
    }

    override suspend fun stopHelper(helper: HelperId, request: RequestId): StudioHelperTerminal? {
        val record = access.value.helperRecord(helper)
        log.v { "Stop exact helper request" }
        return native.stop(record, request, access.value.liveHelper(helper))
    }

    override suspend fun helperProgress(helper: HelperId, request: RequestId): HelperProgress? {
        val active = access.value.liveHelper(helper) ?: return null
        val state = active.state.value
        val turn = when (state) {
            is ActiveSessionState.Running -> state.turn
            is ActiveSessionState.AwaitingUserAction -> state.turn
            is ActiveSessionState.Interrupting -> state.turn
            is ActiveSessionState.Unavailable -> state.activeTurn
            is ActiveSessionState.Ready -> state.lastTurn
            is ActiveSessionState.Submitting, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
        }?.takeIf { it.request == request } ?: return null
        val permissions = (state as? ActiveSessionState.AwaitingUserAction)?.requests.orEmpty()
        return HelperProgress(request, active.ref, turn.id, permissions)
    }
}
