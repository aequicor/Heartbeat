package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.harness.api.ItemName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** A registered definition; evaluation registers it, execution belongs to the workflow driver. */
public typealias WorkflowDefinition = suspend WorkflowScope.(JsonObject) -> JsonElement

/** Only registers a definition. A workflow script must not run it while being evaluated. */
public fun interface WorkflowRegistration {
    /** Exactly one definition is accepted per evaluated workflow. */
    public fun register(definition: WorkflowDefinition)
}

/**
 * Optional helper model override; trust is always bounded by the host. [target] pins the model; otherwise
 * [adaptive] asks the host to choose one for this step (see [AdaptiveAgent]); without both the helper inherits.
 */
public data class AgentOptions(
    val title: String,
    val target: EngineTarget? = null,
    val adaptive: AdaptiveAgent? = null,
) {
    override fun toString(): String = "AgentOptions(***)"
}

/**
 * Replayable workflow operations. Structural branch/sequence keys are assigned by the driver, never by
 * completion order. Completed values and failures are memoized; capacity waits are not failures.
 * All item lookups use the run's pinned revision, even after the library changes.
 */
public interface WorkflowScope {
    /** Validated immutable invocation input. */
    public val input: JsonObject

    /** Runs one supervised helper and returns the exact request's bounded answer. */
    public suspend fun agent(prompt: String, options: AgentOptions): String

    /**
     * Runs one organism as the owned executor of a large stage and returns its [OrganismOutcome]. The outcome is
     * memoized under the next structural key together with the goal and options, so replay and recovery return it
     * and never conceive a second organism; changing them for a recorded step fails the run as Diverged. Waiting
     * for the organism quota is not a failure. Cancelling the run or the surrounding branch stops the organism, and
     * its quota is returned only after its work has actually ended. Only this exact step of this run can stop it:
     * the organism's identity is never exposed to workflow code, tools or other sessions. [goal] is bounded by
     * [io.aequicor.heartbeat.feature.harness.api.HarnessLimits.ORGANISM_GOAL_CHARS].
     */
    public suspend fun organism(goal: String, options: OrganismOptions): OrganismOutcome

    /** Runs branches concurrently with stable branch indices and preserves their declaration order. */
    public suspend fun parallel(vararg branches: suspend WorkflowScope.() -> JsonElement): List<JsonElement>

    /** Memoizes a JSON value or typed failure under the next structural key and the supplied name. */
    public suspend fun step(name: String, body: suspend () -> JsonElement): JsonElement

    /** A memoized clock read; replay returns the original instant. */
    public suspend fun now(): Instant

    /** Renders a pinned template with explicit arguments. */
    public fun prompt(name: ItemName, args: Map<String, String> = emptyMap()): String

    /** Reads a pinned skill body. */
    public fun skill(name: ItemName): String
}
