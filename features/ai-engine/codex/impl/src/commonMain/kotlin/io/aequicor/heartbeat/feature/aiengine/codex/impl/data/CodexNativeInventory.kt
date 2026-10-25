package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Inert stand-in declared by the offline probe only when the working thread declares no hosted tools, because an
 * empty catalog cannot show where the CLI placed its declarations. It is never executed.
 */
internal const val CODEX_INVENTORY_TOOL = "heartbeat_inventory_probe"

/**
 * The model-facing surface of one protected working thread that the offline probe reproduces: the selected
 * [model], the working process's [effective] `config/read` result, the thread-level [thread] `config` sent with
 * thread/start or thread/resume, and the hosted dynamic [tools] declarations of that thread.
 */
internal data class CodexInventoryTarget(
    val model: String,
    val effective: JsonObject,
    val thread: JsonObject,
    val tools: List<JsonObject>,
)

/**
 * Top-level keys of the working process's effective configuration that the probe either reproduces or that
 * cannot add a native tool: feature flags and isolation keys the host overrides, keys whose tools are gated by
 * reproduced features (plugins, apps, hooks, skills), and inference, prompt, authentication, storage and
 * presentation settings. Everything else (for example `tools`, legacy `experimental_use_*` and `include_*`
 * switches) can change the catalog independently of features and fails closed. `profile` is refused separately:
 * an active profile's features apply after session flags and are absent from the effective `features`.
 */
internal val CodexInventoryConfigKeys = setOf(
    "features", "web_search", "agents", "mcp_servers", "model_catalog_json", "approval_policy", "sandbox_mode",
    "sandbox_workspace_write", "model", "review_model", "model_provider", "model_providers", "model_context_window",
    "model_auto_compact_token_limit", "model_reasoning_effort", "model_reasoning_summary", "model_verbosity",
    "model_supports_reasoning_summaries", "service_tier", "instructions", "developer_instructions",
    "model_instructions_file", "compact_prompt", "project_doc_max_bytes", "project_doc_fallback_filenames",
    "plugins", "marketplaces", "apps", "hooks", "skills", "profiles", "projects", "forced_login_method",
    "forced_chatgpt_workspace_id", "cli_auth_credentials_store", "mcp_oauth_credentials_store", "chatgpt_base_url",
    "shell_environment_policy", "notify", "history", "file_opener", "tui", "desktop", "hide_agent_reasoning",
    "show_raw_agent_reasoning", "notice", "check_for_update_on_startup", "disable_paste_burst",
    "windows_wsl_setup_acknowledged", "otel", "analytics", "feedback",
)

/**
 * Refuses a working configuration the probe cannot faithfully reproduce. Unset values (null, empty or all-null
 * tables, as `config/read` serializes absent optional fields) are ignored. No key name or value is logged.
 */
internal fun requireCodexInventoryConfig(effective: JsonObject) {
    effective.forEach { (key, value) ->
        if (!isUnsetConfig(value) && (key == "profile" || key !in CodexInventoryConfigKeys)) inventoryFailure()
    }
}

private fun isUnsetConfig(value: JsonElement): Boolean = when (value) {
    JsonNull -> true
    is JsonObject -> value.values.all(::isUnsetConfig)
    is JsonArray -> value.isEmpty()
    else -> false
}

/**
 * Checks actual Responses declarations, not version numbers or echoed configuration keys. Each [declared] host
 * function must appear exactly once; besides them only native questions are allowed, which the host handles and
 * refuses for tool-free turns. An absent or malformed catalog is not evidence of isolation. Unknown namespaces and
 * tool kinds fail closed even when they reuse an allowed function name.
 */
internal fun validateCodexInventory(request: JsonObject, declared: Set<String> = setOf(CODEX_INVENTORY_TOOL)) {
    if (declared.isEmpty()) inventoryFailure()
    val names = requestedTools(request).map { tool ->
        if (tool.text("type") != "function") inventoryFailure()
        val name = tool.text("name") ?: inventoryFailure()
        if (name !in declared && name != "request_user_input") inventoryFailure()
        name
    }
    if (!names.containsAll(declared) || names.distinct().size != names.size) inventoryFailure()
}

/** Top-level tools plus deferred `additional_tools` input items; a missing input list is malformed. */
private fun requestedTools(request: JsonObject): List<JsonObject> {
    val tools = mutableListOf<JsonObject>()
    request["tools"]?.let { tools += inventoryTools(it as? JsonArray ?: inventoryFailure()) }
    val input = request["input"] as? JsonArray ?: inventoryFailure()
    input.forEach { element ->
        val item = element as? JsonObject ?: inventoryFailure()
        if (item.text("type") == "additional_tools") {
            tools += inventoryTools(item["tools"] as? JsonArray ?: inventoryFailure())
        }
    }
    return tools
}

internal fun inventoryFailure(): Nothing = fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))

/** Responses Lite groups tools into namespaces; only the host function namespace is permitted. */
private fun inventoryTools(items: JsonArray): List<JsonObject> = items.flatMap { element ->
    val tool = element as? JsonObject ?: inventoryFailure()
    if (tool.text("type") == "namespace") {
        if (tool.text("name") != "functions") inventoryFailure()
        (tool["tools"] as? JsonArray ?: inventoryFailure()).map { it as? JsonObject ?: inventoryFailure() }
    } else {
        listOf(tool)
    }
}
