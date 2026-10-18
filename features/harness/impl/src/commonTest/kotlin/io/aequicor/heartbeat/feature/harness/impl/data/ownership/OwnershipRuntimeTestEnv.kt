package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ScriptOwnershipResult
import io.aequicor.heartbeat.feature.harness.impl.data.decision.DecisionTestMachine
import io.aequicor.heartbeat.feature.harness.impl.data.decision.ProcessTestCommands
import io.aequicor.heartbeat.feature.harness.impl.data.decision.ProcessTestToggles
import io.aequicor.heartbeat.feature.harness.impl.data.decision.ProtectedProcessDecisions
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.PortableGitHubAdapter
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.ProtectedImportedGitHub
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandOrigins
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestAccess
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestClock
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProtectedHarnessData
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProtectedProcessArchives
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionHost
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistry
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowFixture
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Only external GitHub/git IO is fake; profile stores, consent, fencing and transfer adapters are production code. */
internal class OwnershipRuntimeTestEnv(
    scope: TestScope,
    server: GitHubOwnershipBackendTest.Server,
    val secrets: HarnessDataTestSecrets = HarnessDataTestSecrets(),
    val request: HarnessActivationRequest = HarnessActivationRequest(harness, code, 1),
) {
    val clock = ProcessesTestClock(1_000)
    val access = ProcessesTestAccess(scope)
    val toggles = ProcessTestToggles()
    val runtime = ProfileOwnershipRuntime(ProtectedOwnershipJournal(secrets), server, clock)
    val data = ProtectedHarnessData(secrets, ProtectedProcessArchives(secrets, clock, toggles), runtime)
    val decisions = ProcessDecisionHost(DecisionTestMachine(ProtectedProcessDecisions(secrets)), clock)
    val workspace = WorkspaceRef("project")
    val processes = HarnessScriptProcesses(
        HarnessScriptData(request.harness.id, access, data),
        clock,
        data.processMutex,
        owner = request.harness.id,
        definition = ProcessDefinition(request.item.id, request.harness.revision),
    )
    private val imported = ProtectedImportedGitHub(secrets)
    private val verifier = OwnershipWorkspaceVerifier(ProcessTestCommands())
    private val github = PortableGitHubAdapter(
        runtime.api,
        GitHubRegistry(HarnessScriptData(request.harness.id, access, data).processData()),
        imported,
    )
    private val target = HarnessInstanceTarget(request, access)
    private val host = OwnershipRuntimeHost(data, runtime, verifier, decisions, clock)
    val local = RuntimeOwnershipTransferLocal(
        target,
        host,
        WorkflowFixture().storage,
        { error("Idle transfer must not fabricate a native cancellation receipt") },
        github,
        imported,
    )
    val ownership = HarnessScriptOwnership(target, host, local, toggles, CommandOrigins())

    suspend fun approve(result: ScriptOwnershipResult) {
        val id = assertIs<ScriptOwnershipResult.Decision>(result).id
        val decision = checkNotNull(decisions.ready()?.find(id)).decision
        assertTrue(
            decisions.edit(ProcessDecisionEdit.Change(id, ProcessDecisionCommand.Answer(decision.revision, true))),
        )
    }

    suspend fun record(process: ProcessId) = checkNotNull(processes.get(process))
}
