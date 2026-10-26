package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSeal
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.api.failed
import io.aequicor.heartbeat.feature.worktreemode.api.reconciled
import io.aequicor.heartbeat.feature.worktreemode.api.runPrepared
import io.aequicor.heartbeat.feature.worktreemode.api.sealed
import io.aequicor.heartbeat.feature.worktreemode.api.transition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A worktree machine whose journal applies the real pure transitions of the task. With [isCutting], Prepare cuts a
 * sandbox the way worktree mode does; the sandbox of a key already cut is found again.
 */
internal class WorktreeFake(private val isCutting: Boolean = false) :
    MachineRef<WorktreeState, WorktreeIntent.Public, WorktreeOutput> {
    override val name = "worktree-fake"
    override val state = MutableStateFlow<WorktreeState>(WorktreeState.Ready())
    override val outputs = emptyFlow<WorktreeOutput>()
    val sent = mutableListOf<WorktreeIntent.Public>()
    var result = SendResult.Accepted
    var onPrepare: () -> Unit = {}
    var onSeal: suspend () -> Unit = {}
    var failAfterRunStarted = false
    var failRuns = false
    var failSeals = false
    var seals = 0
    val prepares = mutableListOf<String>()

    fun tasks(): Map<String, WorktreeTask> = (state.value as WorktreeState.Ready).tasks

    fun publish(task: WorktreeTask) {
        val tasks = (state.value as WorktreeState.Ready).tasks
        state.value = WorktreeState.Ready(tasks + (task.chatId to task))
    }

    fun task(key: String): WorktreeTask = tasks().getValue(key)

    /** What the journal does when the profile reopens: native runs nobody observes any more need recovery. */
    fun restart() = tasks().values.forEach { task ->
        change(task.chatId) { it.reconciled(isPresent = true, it.executionWorkspace, restart = true) }
    }

    override suspend fun send(intent: WorktreeIntent.Public): SendResult {
        sent += intent
        if (result != SendResult.Accepted) return result
        when (intent) {
            is WorktreeIntent.Public.Prepare -> {
                prepares += intent.chatId
                if (isCutting && intent.chatId !in tasks()) {
                    publish(
                        WorktreeTask(
                            intent.chatId,
                            intent.project,
                            executionWorkspace = WorkspaceRef("sandbox:${intent.chatId}"),
                            branch = "heartbeat/${intent.chatId}",
                            baseCommit = "base0",
                            phase = WorktreePhase.Idle,
                            revision = 1,
                            isSandbox = true,
                        ),
                    )
                }
                onPrepare()
            }

            is WorktreeIntent.Public.RunStarted -> {
                if (failAfterRunStarted) {
                    change(intent.chatId) { it.transition(intent) }
                    error("PreparationAcknowledgementLost")
                }
                change(intent.chatId) { task ->
                    // The journal prepares the run in the same step; this is what the real one commits.
                    if (failRuns) {
                        task.failed("WorktreeUnavailable")
                    } else {
                        task.transition(intent).runPrepared(intent.request)
                    }
                }
            }

            is WorktreeIntent.Public.Seal -> {
                onSeal()
                change(intent.chatId) {
                    it.sealed(
                        if (failSeals) {
                            WorktreeSeal(intent.operation, failure = "TurnActive")
                        } else {
                            WorktreeSeal(intent.operation, "commit-${++seals}")
                        },
                    )
                }
            }

            is WorktreeIntent.Public.Recheck -> change(intent.chatId) { it.rechecked(intent) }

            else -> change(intent.runKey()) { it.transition(intent) }
        }
        return result
    }

    /** A recheck continues a paused sandbox; a handed-off one belongs to the user unless explicitly reopened. */
    private fun WorktreeTask.rechecked(intent: WorktreeIntent.Public.Recheck): WorktreeTask {
        val sandbox = virtualSandbox ?: return this
        if (!intent.isReopen && sandbox.phase == VirtualSandboxPhase.HandedOff) return this
        return copy(virtualSandbox = sandbox.copy(phase = VirtualSandboxPhase.Ready, resultCommit = null))
    }

    private fun change(key: String, transform: (WorktreeTask) -> WorktreeTask) {
        val before = task(key)
        val after = transform(before)
        if (after != before) publish(after.copy(revision = before.revision + 1))
    }

    private fun WorktreeIntent.Public.runKey(): String = when (this) {
        is WorktreeIntent.Public.RunAccepted -> chatId
        is WorktreeIntent.Public.RunSettled -> chatId
        is WorktreeIntent.Public.RunRejected -> chatId
        is WorktreeIntent.Public.RunAdopted -> chatId
        is WorktreeIntent.Public.RunObservationLost -> chatId
        is WorktreeIntent.Public.RunSubmissionObservationLost -> chatId
        is WorktreeIntent.Public.RunSubmissionReconciled -> chatId
        else -> error("unexpected $this")
    }
}

internal fun registry(machine: WorktreeFake?) = object : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // Only the checked worktree key is served by this fixture.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? {
        check(key == WorktreeMachineKey)
        return machine as MachineRef<S, P, O>?
    }

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key)?.send(intent) ?: SendResult.NotRunning
}
