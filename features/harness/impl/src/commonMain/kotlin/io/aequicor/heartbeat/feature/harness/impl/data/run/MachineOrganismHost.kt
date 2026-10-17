package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismConception
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismHost
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismView
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.zygote

/**
 * The organic AI machine as the workflow owner uses it, through public intents only. The machine exists only while
 * organic AI is on in the profile, so a missing or sleeping machine is [OrganismView.Unavailable], never absence:
 * a sleeping machine still owns its developing organisms and revives them on awakening. Texts are never logged.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineOrganismHost(private val machines: MachineRegistry) : OrganismHost {
    override suspend fun view(organism: WorkflowOrganism): OrganismView {
        val state = machines.find(OrganicAiMachineKey)?.state?.value
        val living = state as? OrganicAiState.Living ?: return OrganismView.Unavailable
        return living.organisms[OrganismId(organism.id)]?.toView() ?: OrganismView.Absent
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
        )
        // The registry logs every send; a refusal reaches the workflow as a typed outcome.
        return when (machines.send(OrganicAiMachineKey, OrganicAiIntent.Public.Conceive(conception))) {
            SendResult.Accepted -> OrganismConception.Accepted

            SendResult.NotRunning -> OrganismConception.Unavailable

            // Conceiving an existing id is ignored: the organism is there if the machine shows it.
            SendResult.Ignored -> when (view(organism)) {
                OrganismView.Unavailable -> OrganismConception.Unavailable
                OrganismView.Absent -> OrganismConception.Rejected
                else -> OrganismConception.Accepted
            }
        }
    }

    override suspend fun abort(organism: WorkflowOrganism) {
        machines.send(OrganicAiMachineKey, OrganicAiIntent.Public.Abort(OrganismId(organism.id)))
    }
}

private fun Organism.toView(): OrganismView = when (val ended = status) {
    is OrganismStatus.Completed -> OrganismView.Answered(ended.result)

    OrganismStatus.Aborted -> OrganismView.Aborted

    OrganismStatus.Developing -> when (val phase = zygote.phase) {
        is CellPhase.Stalled -> OrganismView.Stalled(phase.breakdown.toEnd())
        else -> OrganismView.Developing
    }
}

private fun Breakdown.toEnd(): OrganismEnd = if (this == Breakdown.NoModel) OrganismEnd.NoModel else OrganismEnd.Failed
