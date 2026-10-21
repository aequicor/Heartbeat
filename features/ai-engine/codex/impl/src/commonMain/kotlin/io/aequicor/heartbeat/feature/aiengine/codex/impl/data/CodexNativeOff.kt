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
) {
    private val isExecutionOff get() = isShellDisabled || isActionAnalysisRequired
    private val isSearchOff get() = isSearchDisabled || isActionAnalysisRequired
    private val isLocalOff get() = isVirtualWorkspace || isActionAnalysisRequired
    val isRestricted: Boolean get() = isExecutionOff || isSearchOff || isLocalOff

    /** Appended after every user override and isolation flag without accepting arbitrary configuration keys. */
    fun arguments(): List<String> = buildList {
        if (isExecutionOff) executionFeatures.forEach { addAll(listOf("-c", "features.$it=false")) }
        if (isSearchOff) addAll(listOf("-c", "web_search=\"disabled\""))
        if (isLocalOff) virtualFeatures.forEach { addAll(listOf("-c", "features.$it=false")) }
        if (isActionAnalysisRequired) enhancedFeatures.forEach { addAll(listOf("-c", "features.$it=false")) }
    }

    /**
     * Enhanced execution accepts only a build whose actual model-facing tool inventory was checked. Its known
     * flags are insufficient proof for any earlier, later or differently suffixed build. Legacy restrictions
     * retain their existing 0.160.0 protocol baseline and effective-config checks.
     */
    fun requireVersion(version: String?) {
        if (!isRestricted) return
        if (isActionAnalysisRequired) {
            if (version !in verifiedEnhancedVersions) unsupportedPolicy()
        } else {
            val match = version?.let { Version.matchEntire(it) } ?: unsupportedPolicy()
            val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: unsupportedPolicy() }
            if (numbers[0] == 0 && numbers[1] < MIN_MINOR) unsupportedPolicy()
            val isBaselinePrerelease = numbers == listOf(0, MIN_MINOR, 0) &&
                match.groupValues[PRERELEASE_GROUP].isNotEmpty()
            if (isBaselinePrerelease) unsupportedPolicy()
        }
    }

    /** Applies Off after adapter defaults, including the optional `web_search=live` default. */
    fun applyTo(config: JsonObject): JsonObject = buildJsonObject {
        config.forEach { (key, value) -> put(key, value) }
        if (isExecutionOff || isLocalOff) {
            put("features", disabledFeatures(config))
        }
        if (isSearchOff) put("web_search", "disabled")
    }

    private fun disabledFeatures(config: JsonObject): JsonObject = buildJsonObject {
        (config["features"] as? JsonObject).orEmpty().forEach { (key, value) -> put(key, value) }
        if (isExecutionOff) executionFeatures.forEach { put(it, false) }
        if (isLocalOff) virtualFeatures.forEach { put(it, false) }
        if (isActionAnalysisRequired) enhancedFeatures.forEach { put(it, false) }
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
            if (isActionAnalysisRequired) addAll(enhancedFeatures)
        }
        return required.all { features?.get(it) == JsonPrimitive(false) } &&
            (!isSearchOff || config["web_search"] == JsonPrimitive("disabled"))
    }

    private fun unsupportedPolicy(): Nothing = fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))

    private companion object {
        val Version = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)(-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
        const val MIN_MINOR = 160
        const val PRERELEASE_GROUP = 4
        val executionFeatures = listOf("shell_tool", "apply_patch_freeform", "multi_agent")
        val virtualFeatures = listOf("view_image_tool", "apply_patch_freeform", "multi_agent")

        /**
         * Windows CLI 0.163.0-alpha.10.2: `features list`, `config/read(includeLayers=true)` and an offline
         * Responses fixture verified this registry and the actual declarations. `view_image_tool` alone is
         * ignored; `view_image` removes local image reads. `code_mode_only` can expose exec/wait despite
         * code_mode=false, and multi_agent_v2 can expose collaboration despite multi_agent=false. Goals also
         * have native state mutations. The remaining feature variants are closed even when a user override
         * enables them. Auth, storage, sandbox, proxy and presentation flags keep their captured values.
         *
         * After these restrictions the fixture declares only the host-provided dynamic tool and native
         * request_user_input. The latter is a questionnaire routed to CodexSession.userInput, where the live
         * turn and host availability are checked; it is not an autonomous filesystem or network operation.
         * `native-inventory.test.mjs` repeats the capture without credentials or an external model service.
         */
        val verifiedEnhancedVersions = setOf("0.163.0-alpha.10.2")
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

/** Retains the whole reported build identifier; the legacy parser can truncate an unverified suffix. */
internal fun codexActionAnalysisVersion(output: String): String? = output.lineSequence()
    .map(String::trim)
    .singleOrNull { it.startsWith("codex-cli ") }
    ?.removePrefix("codex-cli ")
