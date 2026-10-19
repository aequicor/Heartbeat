package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismCommit
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganismContext

/** What organic AI shows of one organism a workflow owns. The text of an answer is never rendered by [toString]. */
internal sealed interface OrganismView {
    /** Organic AI is off, asleep or not awake: nothing can be asked of it and nothing is known about the organism. */
    data object Unavailable : OrganismView

    /** Organic AI is awake and has no such organism. */
    data object Absent : OrganismView

    /** The organism develops. */
    data object Developing : OrganismView

    /** The zygote broke or a working cell needs interactive input; the organism still holds its sessions. */
    data class Stalled(val end: OrganismEnd) : OrganismView

    /** The zygote answered after every descendant ended. */
    data class Answered(val text: String, val release: OrganismRelease = OrganismRelease.Confirmed) : OrganismView {
        override fun toString(): String = "Answered(${text.length} chars)"
    }

    /**
     * The organism is marked aborted and organic AI is letting its sessions go: its native turns may still run, so
     * this is no proof of an end. Another abort changes nothing until the release reports.
     */
    data object Aborting : OrganismView

    /** The abort could not be confirmed: organic AI did not see every native turn stop. Another abort retries. */
    data object AbortUnconfirmed : OrganismView

    /** The organism was aborted and organic AI confirmed that the native turns of its cells stopped. */
    data object Aborted : OrganismView
}

/**
 * Whether no cell of the organism can take another turn and none of its native work is known to run: it never
 * existed, answered, or was aborted and organic AI confirmed the release of its sessions. This is the proof the
 * owner needs before it returns the organism's quota; nothing else settles it, and an organism that is merely marked
 * aborted does not.
 */
internal val OrganismView.isEnded: Boolean
    get() = this == OrganismView.Absent ||
        (this is OrganismView.Answered && release == OrganismRelease.Confirmed) || this == OrganismView.Aborted

/** Whether another abort moves the organism forward: it develops, is stalled or its release was not confirmed. */
internal val OrganismView.isAbortable: Boolean
    get() = this == OrganismView.Developing || this is OrganismView.Stalled || isReleaseUnconfirmed

/** Whether an ended organism needs another attempt to release its cells. */
internal val OrganismView.isReleaseUnconfirmed: Boolean
    get() = this == OrganismView.AbortUnconfirmed ||
        (this is OrganismView.Answered && release == OrganismRelease.Unconfirmed)

/** Native work confirmation accompanying an answer; the answer alone does not prove release. */
internal enum class OrganismRelease { Pending, Unconfirmed, Confirmed }

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

    /** A captured environment requires sandbox execution, with no Shared fallback. Legacy hosts refuse it. */
    suspend fun conceiveIsolated(
        organism: WorkflowOrganism,
        goal: String,
        options: OrganismOptions,
        workspace: WorkspaceRef?,
        context: WorkflowOrganismContext,
    ): OrganismConception = OrganismConception.Rejected

    /** Git proof captured after every native cell stopped; null refuses a successful sandbox result. */
    suspend fun output(organism: WorkflowOrganism): OrganismCommit? = null

    /**
     * Asks organic AI to abort [organism]; for one already aborted whose release was not confirmed it asks again.
     * Whether its work ended is read from [view], never from this call.
     */
    suspend fun abort(organism: WorkflowOrganism)
}
