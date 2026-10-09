package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.capturePlatformHarnessFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

/**
 * Executes only the pinned source on the runtime's shared bounded lane. Evaluation merely registers exactly
 * one workflow; its definition starts afterwards. The artifact lease remains alive until all author code has
 * actually drained, including non-cooperative code after a deadline. No failed compiler diagnostic is logged.
 */
internal class WorkflowCodeExecutor(
    private val host: HarnessScriptHost,
    private val lane: HarnessExecutionLane,
    private val origins: HarnessCallOrigins,
    private val clock: Clock,
    private val digest: (String) -> String,
) {
    private val log = Log.tag("HarnessWorkflow")
    val isAvailable: Boolean get() = host.isAvailable

    suspend fun execute(
        run: WorkflowRun,
        progress: WorkflowStepJournal,
        agents: WorkflowAgentSteps,
        origin: HarnessCallOrigin,
    ): JsonElement {
        check(!origin.isHookRestricted) { "Hooks cannot execute workflows" }
        if (digest(run.pinned.source) != run.pinned.sourceSha) throw WorkflowStepFailed(WorkflowFailure.Diverged)
        val remaining = run.deadline - clock.now()
        if (!remaining.isPositive()) throw WorkflowStepFailed(WorkflowFailure.Timeout)
        return withTimeoutOrNull(remaining) {
            val compilation = host.compile(
                HarnessCompilationRequest(run.harness, run.workflow, HarnessCodeKind.Workflow, run.pinned.source),
            )
            when (compilation) {
                is HarnessCompilationResult.Success -> try {
                    withContext(lane.instanceDispatcher() + origins.context(origin)) {
                        val capture = WorkflowDefinitionCapture()
                        val context = HarnessEvaluationContext.Workflow(capture)
                        val evaluated = capturePlatformHarnessFailure { host.evaluate(compilation.code, context) }
                            .getOrElse { error ->
                                log.w(harnessScriptFailure(error)) { "Pinned workflow evaluation failed" }
                                throw WorkflowStepFailed(WorkflowFailure.Error)
                            }
                        val definition = capture.seal()
                        if (evaluated != HarnessEvaluationResult.Success || definition == null) {
                            throw WorkflowStepFailed(WorkflowFailure.Error)
                        }
                        WorkflowEngine(run, progress, agents, clock, digest).execute(checkNotNull(definition))
                    }
                } finally {
                    compilation.code.close()
                }
                HarnessCompilationResult.TimedOut -> throw WorkflowStepFailed(WorkflowFailure.Timeout)
                is HarnessCompilationResult.Failure, HarnessCompilationResult.Unsupported ->
                    throw WorkflowStepFailed(WorkflowFailure.Error)
            }
        } ?: throw WorkflowStepFailed(WorkflowFailure.Timeout)
    }
}
