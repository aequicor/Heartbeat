package io.aequicor.heartbeat.feature.harness.impl.domain.decision

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandSnapshot
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcessCommandResult
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess

/** Immutable activation invocation, copied before any suspension. Never exposes a decision-writing capability. */
internal data class ProcessCommandInvocation(
    val activation: HarnessActivationRequest,
    val access: HarnessInstanceAccess,
    val process: ProcessWorkflowKey,
    val workspace: WorkspaceRef,
    val authority: ProcessAuthority,
    val command: ProcessCommandSnapshot,
) {
    override fun toString(): String = "ProcessCommandInvocation(***)"
}

/** Executor owns request construction, live revalidation and atomic claim, not a boolean returned to author code. */
internal interface HarnessProcessCommandHost {
    suspend fun execute(invocation: ProcessCommandInvocation): ScriptProcessCommandResult
}
