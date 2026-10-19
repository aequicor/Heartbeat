package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismCommit
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismExecution
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganismContext
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismConception
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismHost
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismRelease
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismView
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismIsolation
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.api.isStopped
import io.aequicor.heartbeat.feature.organicai.api.zygote

/**
 * The organic AI machine as the workflow owner uses it, through public intents only. The machine exists only while
 * organic AI is on in the profile, so a missing or sleeping machine is [OrganismView.Unavailable], never absence:
 * a sleeping machine still owns its developing organisms and revives them on awakening. An aborted organism is
 * ended only once the machine confirms the release of its sessions. Conception fixes AutomaticSafety for every
 * working cell turn and caps trust at Ask; a process decision never expands native tool permissions. Texts are
 * never logged.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineOrganismHost(
    private val machines: MachineRegistry,
    private val isolated: WorkflowOrganismWorkspaces,
) : OrganismHost {
    override suspend fun view(organism: WorkflowOrganism): OrganismView {
        val state = machines.find(OrganicAiMachineKey)?.state?.value
        val living = state as? OrganicAiState.Living ?: return OrganismView.Unavailable
        val found = living.organisms[OrganismId(organism.id)] ?: return OrganismView.Absent
        val view = found.toView()
        val isEnded = view == OrganismView.Aborted ||
            (view is OrganismView.Answered && view.release == OrganismRelease.Confirmed)
        if (found.baseCommit != null && isEnded && isolated.isReleased(found) != true) {
            return if (view is OrganismView.Answered) {
                view.copy(release = OrganismRelease.Unconfirmed)
            } else {
                OrganismView.AbortUnconfirmed
            }
        }
        return view
    }

    override suspend fun conceive(
        organism: WorkflowOrganism,
        goal: String,
        options: OrganismOptions,
        workspace: WorkspaceRef?,
    ): OrganismConception {
        if (view(organism) == OrganismView.Unavailable) return OrganismConception.Unavailable
        val conception = Conception(
            OrganismId(organism.id),
            goal,
            target = options.target,
            immunityTarget = options.immunityTarget,
            workspace = workspace,
            trust = TrustLevel.Ask,
            decisionMode = AgentToolDecisionMode.AutomaticSafety,
        )
        return submit(organism, conception)
    }

    override suspend fun conceiveIsolated(
        organism: WorkflowOrganism,
        goal: String,
        options: OrganismOptions,
        workspace: WorkspaceRef?,
        context: WorkflowOrganismContext,
    ): OrganismConception {
        if (view(organism) == OrganismView.Unavailable) return OrganismConception.Unavailable
        val project = workspace ?: return OrganismConception.Rejected
        val execution = options.execution ?: OrganismExecution()
        val base = isolated.prepare(organism, project, execution.baseCommit) ?: return OrganismConception.Rejected
        val conception = Conception(
            OrganismId(organism.id), goal, options.target, options.immunityTarget, project,
            trust = TrustLevel.Ask,
            limits = GrowthLimits(
                execution.maxCells,
                execution.maxDepth,
                execution.maxCases,
                execution.maxOpenCasesPerCell,
            ),
            isolation = OrganismIsolation.Sandbox,
            harnesses = context.harnesses.map { it.value }.toSet(),
            baseCommit = base,
            decisionMode = AgentToolDecisionMode.AutomaticSafety,
        )
        return submit(organism, conception)
    }

    override suspend fun output(organism: WorkflowOrganism): OrganismCommit? {
        val living = machines.find(OrganicAiMachineKey)?.state?.value as? OrganicAiState.Living ?: return null
        val found = living.organisms[OrganismId(organism.id)] ?: return null
        if (found.status !is OrganismStatus.Completed || found.sessions != SessionRelease.Released) return null
        if (isolated.isReleased(found) != true) return null
        return isolated.output(found)
    }

    private suspend fun submit(organism: WorkflowOrganism, conception: Conception): OrganismConception {
        // The registry logs every send; a refusal reaches the workflow as a typed outcome.
        return when (machines.send(OrganicAiMachineKey, OrganicAiIntent.Public.Conceive(conception))) {
            SendResult.Accepted -> OrganismConception.Accepted

            SendResult.NotRunning -> OrganismConception.Unavailable

            // Conceiving an existing id is ignored: the organism is there if the machine shows it.
            SendResult.Ignored -> when (view(organism)) {
                OrganismView.Unavailable -> OrganismConception.Unavailable

                OrganismView.Absent -> OrganismConception.Rejected

                is OrganismView.Answered, is OrganismView.Stalled, OrganismView.Developing,
                OrganismView.Aborting, OrganismView.AbortUnconfirmed, OrganismView.Aborted,
                -> OrganismConception.Accepted
            }
        }
    }

    override suspend fun abort(organism: WorkflowOrganism) {
        machines.send(OrganicAiMachineKey, OrganicAiIntent.Public.Abort(OrganismId(organism.id)))
    }
}

private fun Organism.toView(): OrganismView = when (val ended = status) {
    is OrganismStatus.Completed -> OrganismView.Answered(
        ended.result,
        when (sessions) {
            SessionRelease.Releasing -> OrganismRelease.Pending
            SessionRelease.Unconfirmed -> OrganismRelease.Unconfirmed
            SessionRelease.Released -> OrganismRelease.Confirmed
        },
    )

    OrganismStatus.Aborted -> when {
        isStopped -> OrganismView.Aborted
        sessions == SessionRelease.Unconfirmed -> OrganismView.AbortUnconfirmed
        else -> OrganismView.Aborting
    }

    OrganismStatus.Developing -> developingView()
}

/** A workflow has no permission dialog; any live cell requesting input must go through ordinary abort cleanup. */
private fun Organism.developingView(): OrganismView {
    val phase = zygote.phase
    return when {
        cells.any { (it.phase as? CellPhase.Working)?.awaiting?.isNotEmpty() == true } ->
            OrganismView.Stalled(OrganismEnd.InteractionRequired)

        phase is CellPhase.Stalled -> OrganismView.Stalled(phase.breakdown.toEnd())

        else -> OrganismView.Developing
    }
}

private fun Breakdown.toEnd(): OrganismEnd = if (this == Breakdown.NoModel) OrganismEnd.NoModel else OrganismEnd.Failed
