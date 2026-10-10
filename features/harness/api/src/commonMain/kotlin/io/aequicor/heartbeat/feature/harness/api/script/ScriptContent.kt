package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import kotlinx.serialization.json.JsonObject

/** Template rendering within the script's owning harness; arguments and returned text remain private. */
public interface ScriptPrompts {
    /** Renders an enabled named template; unknown or missing required arguments are rejected. */
    public suspend fun render(name: ItemName, args: Map<String, String> = emptyMap()): String
}

/** Workflow starts inherit the approved script's ownership and host context, never identity from JSON input. */
public interface ScriptWorkflows {
    /**
     * Starts an enabled workflow after input validation and returns its run identity. Script approval covers
     * this start; helper trust remains capped at Ask and common quotas apply. Forbidden from hook callbacks.
     */
    public suspend fun start(name: ItemName, input: JsonObject = JsonObject(emptyMap())): RunId
}
