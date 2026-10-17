package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism

/** What organic AI shows of one organism a workflow owns. The text of an answer is never rendered by [toString]. */
internal sealed interface OrganismView {
    /** Organic AI is off, asleep or not awake: nothing can be asked of it and nothing is known about the organism. */
    data object Unavailable : OrganismView

    /** Organic AI is awake and has no such organism. */
    data object Absent : OrganismView

    /** The organism develops. */
    data object Developing : OrganismView

    /** The zygote broke and waits for a resume or an abort; the organism still holds its sessions. */
    data class Stalled(val end: OrganismEnd) : OrganismView

    /** The zygote answered after every descendant ended. */
    data class Answered(val text: String) : OrganismView {
        override fun toString(): String = "Answered(${text.length} chars)"
    }

    /** The organism was aborted and every cell it had alive ended. */
    data object Aborted : OrganismView
}

/**
 * Whether no cell of the organism can take another turn: it never existed, answered, or was aborted. This is the
 * proof the owner needs before it returns the organism's quota; nothing else settles it.
 */
internal val OrganismView.isEnded: Boolean
    get() = this == OrganismView.Absent || this is OrganismView.Answered || this == OrganismView.Aborted

/** How organic AI took a conception. */
internal enum class OrganismConception {
    /** The organism exists now, newly conceived or already there. */
    Accepted,

    /** Organic AI is off, asleep or not awake yet; nothing was conceived. */
    Unavailable,

    /** Organic AI did not accept the conception. */
    Rejected,
}

/**
 * The profile's organic AI as the workflow owner uses it. Only the owner of a step knows the organism of that step,
 * so nothing else can address or stop it. Goal texts and answers are never logged.
 */
internal interface OrganismHost {
    /** What organic AI shows of [organism] now. */
    suspend fun view(organism: WorkflowOrganism): OrganismView

    /**
     * Conceives [organism] from [goal] with trust capped at Ask; repeating it for an organism that exists is
     * harmless, so a lost acknowledgement never starts a second one.
     */
    suspend fun conceive(
        organism: WorkflowOrganism,
        goal: String,
        options: OrganismOptions,
        workspace: WorkspaceRef?,
    ): OrganismConception

    /** Asks organic AI to abort [organism]. Whether its work ended is read from [view], never from this call. */
    suspend fun abort(organism: WorkflowOrganism)
}
