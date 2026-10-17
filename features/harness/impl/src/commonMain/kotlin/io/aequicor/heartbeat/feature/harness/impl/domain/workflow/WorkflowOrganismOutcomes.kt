package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismRefusal
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The journal value of an outcome, or null when it exceeds the bound of every step result: its owner then fails
 * the step as an error instead of cutting the answer.
 */
internal fun OrganismOutcome.toJournal(): JsonElement? = Json.encodeToJsonElement(OrganismOutcome.serializer(), this)
    .takeIf { it.toString().length <= HarnessLimits.RESULT_CHARS }

/** Reads a journaled outcome; null when the stored value is not an outcome. The value is never logged. */
internal fun JsonElement.toOrganismOutcome(): OrganismOutcome? = try {
    Json.decodeFromJsonElement(OrganismOutcome.serializer(), this)
} catch (error: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException; its message can quote the stored value.
    organismLog.w(harnessScriptFailure(error)) { "Workflow organism result cannot be read" }
    null
}

/**
 * Owner of organism steps while no organic AI bridge is connected: it conceives nothing and journals a typed
 * [OrganismRefusal.Unavailable], so the workflow handles the refusal as data and replay returns the same value.
 */
internal class UnavailableWorkflowOrganisms(private val journal: WorkflowStepJournal) : WorkflowOrganismSteps {
    override suspend fun execute(
        key: StepKey,
        digest: String,
        goal: String,
        options: OrganismOptions,
    ): OrganismOutcome {
        val outcome = OrganismOutcome.Refused(OrganismRefusal.Unavailable)
        journal.prepare(key, digest)
        journal.complete(key, checkNotNull(outcome.toJournal()))
        return outcome
    }
}

private val organismLog = Log.tag("HarnessWorkflow")
