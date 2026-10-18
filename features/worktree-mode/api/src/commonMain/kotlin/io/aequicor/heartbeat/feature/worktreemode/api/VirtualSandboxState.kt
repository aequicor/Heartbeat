package io.aequicor.heartbeat.feature.worktreemode.api

import kotlinx.serialization.Serializable

/** Durable virtual-workspace lifecycle; legacy sandbox tasks have no [VirtualSandboxState]. */
@Serializable
public enum class VirtualSandboxPhase { Ready, Pausing, Waiting, Manual, Rebasing, Conflicted, HandedOff }

/** Content revision and source barrier projected without exposing host filesystem paths. */
@Serializable
public data class VirtualSandboxState(
    val phase: VirtualSandboxPhase = VirtualSandboxPhase.Ready,
    val revision: Long = 0,
    val baseCommit: String,
    val snapshot: String,
    val deadlineMillis: Long? = null,
    val conflicts: List<String> = emptyList(),
    val resultCommit: String? = null,
    val isResolving: Boolean = false,
) {
    /** Ordinary coding is permitted only while the source and virtual base agree. */
    public val isReady: Boolean get() = phase == VirtualSandboxPhase.Ready
}

/**
 * Pure projection transition used by the journal and tested separately from IO.
 *
 * | Current | Observation | Result |
 * |---|---|---|
 * | Any | Older content revision | Unchanged |
 * | Any | Pausing/Waiting/Manual/Conflicted | Retain run identity, block new coding |
 * | Any | Ready | Publish new base and snapshot |
 * | Any | HandedOff | Retained; the branch is owned by the user |
 */
public fun WorktreeTask.virtualChanged(state: VirtualSandboxState): WorktreeTask {
    if (state.revision < (virtualSandbox?.revision ?: -1)) return this
    val isInterrupted = !state.isReady && !state.isResolving &&
        (virtualSandbox?.isReady == true || virtualSandbox?.isResolving == true) && run != null && run.outcome == null
    return copy(
        virtualSandbox = state,
        baseCommit = state.baseCommit,
        phase = if (state.phase == VirtualSandboxPhase.HandedOff) WorktreePhase.Retained else phase,
        pendingVirtualContinuation = pendingVirtualContinuation || isInterrupted,
        virtualInterruptedRequests = if (isInterrupted) {
            (virtualInterruptedRequests + run.request).distinct()
        } else {
            virtualInterruptedRequests
        },
    )
}

/** Schedules a fresh continuation once; accepted interrupted requests are never replayed. */
public fun WorktreeTask.continueVirtual(operation: String, prompt: String): WorktreeTask {
    val isContinuationNeeded = pendingVirtualContinuation || virtualSandbox?.isResolving == true
    if (run?.outcome == null || actionRequest != null || !isContinuationNeeded) return this
    return copy(
        phase = WorktreePhase.ActionWorking,
        failure = null,
        expectedAction = WorktreeExpectedAction(operation, WorktreeRunKind.Coding),
        actionRequest = WorktreeActionRequest(operation, WorktreeRunKind.Coding, prompt),
        pendingVirtualContinuation = false,
    )
}
