package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import okio.ByteString.Companion.encodeUtf8

/** Organic AI as a script: awake or asleep, with the views and conceptions the test sets. */
internal class FakeOrganismHost : OrganismHost {
    val views = mutableMapOf<WorkflowOrganism, OrganismView>()
    var isAwake = true
    var conception = OrganismConception.Accepted

    /** Whether an abort ends the organism; false models a stop that organic AI does not confirm. */
    var isAbortEffective = true

    /** What an effective abort leaves: Aborted, or Aborting while organic AI still releases the sessions. */
    var abortLeaves: OrganismView = OrganismView.Aborted
    val conceived = mutableListOf<Pair<WorkflowOrganism, String>>()
    val aborted = mutableListOf<WorkflowOrganism>()

    override suspend fun view(organism: WorkflowOrganism): OrganismView =
        if (isAwake) views[organism] ?: OrganismView.Absent else OrganismView.Unavailable

    override suspend fun conceive(
        organism: WorkflowOrganism,
        goal: String,
        options: OrganismOptions,
        workspace: WorkspaceRef?,
    ): OrganismConception {
        if (!isAwake) return OrganismConception.Unavailable
        conceived += organism to goal
        if (conception == OrganismConception.Accepted && organism !in views) views[organism] = OrganismView.Developing
        return conception
    }

    override suspend fun abort(organism: WorkflowOrganism) {
        aborted += organism
        val view = views[organism]
        val isAbortable = view?.isEnded != true && view != OrganismView.Aborting
        if (isAbortEffective && isAwake && isAbortable) views[organism] = abortLeaves
    }
}

/** The quota rule of the real journal over a list, without storage. */
internal class InMemoryOrganismJournal : WorkflowOrganismJournal {
    private val records = mutableListOf<WorkflowOrganismGrant>()

    override suspend fun acquire(grant: WorkflowOrganismGrant): OrganismAdmission {
        val old = records.singleOrNull { it.run == grant.run && it.key == grant.key }
        if (old == grant) return OrganismAdmission.Granted
        if (old != null || records.any { it.organism == grant.organism }) throw HarnessStorageConflict()
        return admissionFor(records, grant).also { if (it == OrganismAdmission.Granted) records += grant }
    }

    override suspend fun pending(): List<WorkflowOrganismGrant> = records.toList()

    override suspend fun settled(grant: WorkflowOrganismGrant) {
        records.removeAll { it.run == grant.run && it.key == grant.key }
    }
}

internal val TestDigest: (String) -> String = { it.encodeUtf8().sha256().hex() }

/** The organism id the owner derives for [key] of the fixture run. */
internal fun organismOf(key: String, run: String = "wf_test"): WorkflowOrganism =
    workflowOrganism(RunId(run), StepKey(key), TestDigest)

/** Organism ports over fakes, shared by the owner and run execution tests. */
internal class OrganismTestPorts {
    val host = FakeOrganismHost()
    val grants = InMemoryOrganismJournal()
    val ports = WorkflowOrganismPorts(WorkflowOrganismResources(grants, host), host, TestDigest)
}
