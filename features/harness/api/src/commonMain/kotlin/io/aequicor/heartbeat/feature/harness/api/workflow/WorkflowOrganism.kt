package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Options of an organism step. [title] names it for the user and diagnostics. [target] pins the model of every cell
 * (null — the profile's default model) and [immunityTarget] the judges of the immune system (null — [target]).
 * Trust is always bounded by the host and never wider than Ask; growth ceilings are the host's finite defaults.
 */
public data class OrganismOptions(
    val title: String,
    val target: EngineTarget? = null,
    val immunityTarget: EngineTarget? = null,
) {
    override fun toString(): String = "OrganismOptions(***)"
}

/**
 * What an organism step gives back. Every variant is plain data the workflow must handle: a refusal or an ending
 * without an answer is not an exception, and [Answered] says only that the zygote stopped answering, never that the
 * stage achieved its business goal. The workflow checks that goal itself, for example in a following step.
 */
@Serializable
public sealed interface OrganismOutcome {
    /**
     * The zygote gave its final answer after every descendant cell ended. The text is bounded by
     * [io.aequicor.heartbeat.feature.harness.api.HarnessLimits.RESULT_CHARS]; a longer answer fails the step as
     * [WorkflowFailure.Error] instead of being cut.
     */
    @Serializable
    @SerialName("answered")
    public data class Answered(val text: String) : OrganismOutcome {
        override fun toString(): String = "Answered(${text.length} chars)"
    }

    /** No organism was conceived, so nothing runs and nothing was spent. */
    @Serializable
    @SerialName("refused")
    public data class Refused(val reason: OrganismRefusal) : OrganismOutcome

    /** An organism existed but never answered; every cell of it has stopped. */
    @Serializable
    @SerialName("ended")
    public data class Ended(val reason: OrganismEnd) : OrganismOutcome
}

/** Why no organism was conceived. */
@Serializable
public enum class OrganismRefusal {
    /** Organic AI is off in the profile, asleep or unreachable now. A later run may succeed. */
    Unavailable,

    /** The host did not accept the conception. */
    Rejected,
}

/** Why an organism ended without an answer. */
@Serializable
public enum class OrganismEnd {
    /** There was no model to run it on: none pinned and the profile has no default. */
    NoModel,

    /** The turn of the zygote broke or its end could not be confirmed. */
    Failed,

    /** It was aborted from outside before the zygote answered. */
    Aborted,
}

/**
 * Opaque identity of the organism one organism step owns. The host derives it from the run and the structural key,
 * so replay finds the same organism and a repeated step never conceives another; workflow code never sees it. It
 * stays within the alphabet and length of the organic AI organism ids.
 */
@Serializable
public data class WorkflowOrganism(val id: String) {
    init {
        require(id.matches(ORGANISM_ID)) { "Invalid workflow organism" }
    }

    override fun toString(): String = "WorkflowOrganism(***)"
}

private val ORGANISM_ID = Regex("[A-Za-z0-9_-]{1,64}")
