package io.aequicor.heartbeat.feature.harness.impl.data.decision

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandSnapshot
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsIntent
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsMachineSpec
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsOutput
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsState
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandToggles
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestAccess
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestClock
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProtectedHarnessData
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessCommandInvocation
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionEffects
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionHost
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope

/** Executes the actual decision spec and effects synchronously, persisting through the real encrypted adapter. */
internal class DecisionTestMachine(storage: ProcessDecisionStorage) :
    ProcessDecisionsMachine,
    EffectScope<ProcessDecisionsIntent> {
    override val name = "process-decisions"
    override val state = MutableStateFlow<ProcessDecisionsState>(ProcessDecisionsState.Idle)
    override val outputs = MutableSharedFlow<ProcessDecisionsOutput>()
    private val effects = ProcessDecisionEffects(storage)

    override suspend fun send(intent: ProcessDecisionsIntent): SendResult {
        val resolution = ProcessDecisionsMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        resolution.outputs.forEach { outputs.emit(it) }
        resolution.effects.forEach { effects.handle(it, this) }
        return SendResult.Accepted
    }
}

internal class ProcessCommandTestEnv(test: TestScope) {
    val secrets = HarnessDataTestSecrets()
    val clock = ProcessesTestClock(1_000)
    val access = ProcessesTestAccess(test)
    val data = ProtectedHarnessData(secrets)
    val scriptData = HarnessScriptData(harness.id, access, data)
    val processes = HarnessScriptProcesses(scriptData, clock, data.processMutex)
    val local = ProcessTestCommands()
    val toggles = CommandToggles()
    private val storage = ProtectedProcessDecisions(secrets)
    var host = ProcessDecisionHost(DecisionTestMachine(storage), clock)
        private set
    val process = ProcessId("process")

    suspend fun initialize() {
        processes.open(process, 1)
        processes.apply(process, ProcessCommand.ObserveSubject(SubjectSha(local.head)))
    }

    suspend fun invocation(): ProcessCommandInvocation = ProcessCommandInvocation(
        HarnessActivationRequest(harness, code, 1),
        access,
        checkNotNull(processes.get(process)).workflowKey(StageName("merge")),
        WorkspaceRef("project"),
        ProcessAuthority.Merge,
        ProcessCommandSnapshot("gh", listOf("pr", "merge", "1"), 60_000),
    )

    fun executor() = ProcessCommandExecutor(host, data, local, toggles, clock)

    fun restart() {
        host = ProcessDecisionHost(DecisionTestMachine(ProtectedProcessDecisions(secrets)), clock)
    }
}

internal class ProcessTestCommands : LocalCommands {
    override val isAvailable = true
    var head = "a".repeat(40)
    var repository = "https://github.com/owner/repo.git"
    var outcome: LocalCommandOutcome = LocalCommandOutcome.Exited(0, "done")
    val executions = mutableListOf<LocalCommandRequest>()
    var afterRemoteRead: suspend () -> Unit = {}
    var beforeExecute: suspend () -> Unit = {}

    override suspend fun run(request: LocalCommandRequest): LocalCommandOutcome = when {
        request.program == "git" && request.arguments.first() == "rev-parse" -> LocalCommandOutcome.Exited(0, head)

        request.program == "git" && request.arguments.first() == "config" -> {
            val value = repository
            afterRemoteRead()
            LocalCommandOutcome.Exited(0, value)
        }

        else -> {
            executions += request
            beforeExecute()
            outcome
        }
    }
}
