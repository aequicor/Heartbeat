package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The only native execution overrides supported by this adapter. An absent restriction preserves the captured
 * launch configuration; it never writes an On value. Both process flags and cold-resume config carry restrictions.
 */
internal data class CodexNativeOff(
    val isShellDisabled: Boolean = false,
    val isSearchDisabled: Boolean = false,
    val isVirtualWorkspace: Boolean = false,
    /** Native reads and provider tools have no shared per-call gate and must also be unavailable. */
    val isActionAnalysisRequired: Boolean = false,
    /** Text-only service sessions close the same native catalog independently of the profile safety toggle. */
    val isToolFree: Boolean = false,
) {
    /** Both enhanced authorization and text-only isolation need an audited native inventory. */
    val isVerifiedInventoryRequired get() = isActionAnalysisRequired || isToolFree
    private val isExecutionOff get() = isShellDisabled || isVerifiedInventoryRequired
    private val isSearchOff get() = isSearchDisabled || isVerifiedInventoryRequired
    private val isLocalOff get() = isVirtualWorkspace || isVerifiedInventoryRequired
    val isRestricted: Boolean get() = isExecutionOff || isSearchOff || isLocalOff

    /** Appended after every user override and isolation flag without accepting arbitrary configuration keys. */
    fun arguments(): List<String> = buildList {
        if (isExecutionOff) executionFeatures.forEach { addAll(listOf("-c", "features.$it=false")) }
        if (isSearchOff) addAll(listOf("-c", "web_search=\"disabled\""))
        if (isLocalOff) virtualFeatures.forEach { addAll(listOf("-c", "features.$it=false")) }
        if (isVerifiedInventoryRequired) enhancedFeatures.forEach { addAll(listOf("-c", "features.$it=false")) }
        if (isVerifiedInventoryRequired) addAll(listOf("-c", "agents.enabled=false"))
        if (isToolFree) addAll(listOf("-c", "features.default_mode_request_user_input=false"))
    }

    /** Applies Off after adapter defaults, including the optional `web_search=live` default. */
    fun applyTo(config: JsonObject): JsonObject = buildJsonObject {
        config.forEach { (key, value) -> put(key, value) }
        if (isExecutionOff || isLocalOff) {
            put("features", disabledFeatures(config))
        }
        if (isSearchOff) put("web_search", "disabled")
        if (isVerifiedInventoryRequired) {
            put("agents", JsonObject((config["agents"] as? JsonObject).orEmpty() + ("enabled" to JsonPrimitive(false))))
        }
    }

    private fun disabledFeatures(config: JsonObject): JsonObject = buildJsonObject {
        (config["features"] as? JsonObject).orEmpty().forEach { (key, value) -> put(key, value) }
        if (isExecutionOff) executionFeatures.forEach { put(it, false) }
        if (isLocalOff) virtualFeatures.forEach { put(it, false) }
        if (isVerifiedInventoryRequired) enhancedFeatures.forEach { put(it, false) }
        if (isToolFree) put("default_mode_request_user_input", false)
    }

    /**
     * Audits the candidate execution process's `config/read(includeLayers=true)` response before thread creation
     * or resume. Managed settings must not override Off, and active sessionFlags must retain the requested keys.
     * Nothing from the configuration or its layers is logged: they may contain credentials.
     */
    fun validateConfig(response: JsonObject) {
        if (!isRestricted) return
        val effective = response["config"] as? JsonObject ?: unsupportedPolicy()
        val layers = response["layers"] as? JsonArray ?: unsupportedPolicy()
        val hasFlags = layers.any { value ->
            val layer = value as? JsonObject
            val name = layer?.get("name") as? JsonObject
            val config = layer?.get("config") as? JsonObject
            // 0.160.0 omits this nullable field for active layers despite its generated TS declaration.
            val isActive = layer?.get("disabledReason").let { it == null || it == JsonNull }
            name?.text("type") == "sessionFlags" && isActive &&
                config != null && matches(config)
        }
        if (!matches(effective) || !hasFlags) unsupportedPolicy()
    }

    private fun matches(config: JsonObject): Boolean {
        val features = config["features"] as? JsonObject
        val required = buildList {
            if (isExecutionOff) addAll(executionFeatures)
            if (isLocalOff) addAll(virtualFeatures)
            if (isVerifiedInventoryRequired) addAll(enhancedFeatures)
            if (isToolFree) add("default_mode_request_user_input")
        }
        return required.all { features?.get(it) == JsonPrimitive(false) } &&
            (!isSearchOff || config["web_search"] == JsonPrimitive("disabled")) &&
            (!isVerifiedInventoryRequired || (config["agents"] as? JsonObject)?.get("enabled") == JsonPrimitive(false))
    }

    private fun unsupportedPolicy(): Nothing = fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))

    private companion object {
        val executionFeatures = listOf("shell_tool", "apply_patch_freeform", "multi_agent")
        val virtualFeatures = listOf("view_image_tool", "apply_patch_freeform", "multi_agent")

        /**
         * Close alternate native entry points as well as their legacy flags. Effective configuration is
         * checked on every execution connection; the desktop transport additionally captures the actual
         * model-facing catalog on a local credential-free provider before protected thread creation.
         * CLI versions never authorize execution. Unknown native tools fail the inventory check.
         */
        val enhancedFeatures = listOf(
            "view_image", "unified_exec", "unified_exec_tty", "shell_snapshot", "shell_snapshot_v2",
            "code_mode", "code_mode_host", "code_mode_host_grpc", "code_mode_only",
            "code_mode_only_strict_3p_tools", "code_mode_tool_search", "code_mode_prewarm",
            "multi_agent_v2", "multi_agent_v2_dynamic_tools", "agent_message_board", "goals",
            "memories", "external_agent_memory_import", "chronicle", "artifact", "workspace_dependencies",
            "skill_search", "standalone_web_search", "web_search_cached", "web_search_request",
            "deferred_executor", "deferred_tool_world_state", "executor_capability_discovery",
            "stable_environment_tools", "request_permissions_tool", "enable_mcp_apps",
        )
    }
}
