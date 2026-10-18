package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import kotlinx.serialization.json.JsonObject

/** Template rendering within the script's owning harness; arguments and returned text remain private. */
public interface ScriptPrompts {
    /** Renders an enabled named template; unknown or missing required arguments are rejected. */
    public suspend fun render(name: ItemName, args: Map<String, String> = emptyMap()): String
}

/** Workflow starts inherit the approved script's ownership and host context, never identity from JSON input. */
public interface ScriptWorkflows {
    /**
     * Reserves and dispatches one process attempt. Persist [key] before retrying; the same key and admission
     * snapshot always identify the same run. Changed input, revision, script or project conflicts. A retired
     * attempt is never recreated even if its result was removed. Only the owning script may observe or cancel it.
     * Unknown storage or executor state is an error, never proof that another start is safe.
     */
    public suspend fun start(
        key: ProcessWorkflowKey,
        name: ItemName,
        input: JsonObject = JsonObject(
            emptyMap(),
        ),
    ): RunId = error("Process workflows are unavailable")

    /** Reads a durable safe projection. Missing differs from an unavailable journal and never authorizes a retry. */
    public suspend fun get(key: ProcessWorkflowKey): ScriptWorkflowState = ScriptWorkflowState.Unavailable

    /** Requests cancellation. Requested is not completion: cleanup must confirm every owned resource stopped. */
    public suspend fun cancel(key: ProcessWorkflowKey): ScriptWorkflowCancellation =
        ScriptWorkflowCancellation.Unavailable

    /**
     * Starts an enabled workflow after input validation and returns its run identity. Script approval covers
     * this start; helper trust remains capped at Ask and common quotas apply. Forbidden from hook callbacks.
     */
    public suspend fun start(name: ItemName, input: JsonObject = JsonObject(emptyMap())): RunId
}

/** A script never receives pinned code, native handles or another script's execution context. */
public sealed interface ScriptWorkflowState {
    /** No owned reservation exists for this exact lifetime and attempt. */
    public data object Missing : ScriptWorkflowState

    /** Storage or execution cannot currently be established; do not start a replacement. */
    public data object Unavailable : ScriptWorkflowState

    /** Durable status; cancellation remains running until native cleanup is confirmed. */
    public data class Found(val run: RunId, val status: WorkflowStatus, val isCancellationRequested: Boolean) :
        ScriptWorkflowState
}

/** Safe cancellation outcomes, separate from the workflow's durable terminal status. */
public enum class ScriptWorkflowCancellation { Missing, Unavailable, Requested, Finished }
