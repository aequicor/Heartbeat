package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Inert stand-in declared by the offline probe only when the working thread declares no hosted tools, because an
 * empty catalog cannot show where the CLI placed its declarations. It is never executed.
 */
internal const val CODEX_INVENTORY_TOOL = "heartbeat_inventory_probe"

/** The inert [CODEX_INVENTORY_TOOL] declaration, in the dynamic tool format of thread/start. */
internal val CodexInventoryMarker: JsonObject = json(
    "type" to "function".json(),
    "name" to CODEX_INVENTORY_TOOL.json(),
    "description" to "Offline inventory marker".json(),
    "inputSchema" to json("type" to "object".json(), "properties" to json()),
)

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
 * reproduced features (plugins, apps, hooks, skills), and inference, reasoning, prompt, authentication, storage,
 * logging, trust bookkeeping, notice and presentation settings that the Codex TUI commonly writes.
 * Any other key (for example `tools`, legacy `experimental_use_*` and `include_*` switches) can change the
 * catalog independently of features and passes only with the same binary's default value (see
 * [requireCodexInventoryConfig]). `windows.sandbox` is accepted separately as an execution isolation setting;
 * other Windows keys still require the same binary's default. `profile` is refused separately: its features apply after
 * session flags and are absent from the effective `features`.
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
    "windows_wsl_setup_acknowledged", "otel", "analytics", "feedback", "personality",
    "suppress_unstable_features_warning", "project_root_markers", "tool_output_token_limit",
    "preferred_auth_method", "approvals_reviewer", "zsh_path", "log_dir", "sqlite_home", "disable_response_storage",
)

/**
 * Refuses a working configuration the probe cannot faithfully reproduce. Allowlisted keys and `windows.sandbox`
 * pass with any value. Other Windows settings are checked individually against the baseline or for being unset.
 * Any other key passes when it is unset (null or a non-empty table of unset values, as `config/read` serializes
 * absent optional fields; an explicitly declared `{}` or `[]` counts as set) or when its value deeply equals the
 * same key in [baseline]: the `config/read` result of the same executable in the probe's empty `CODEX_HOME`, so
 * defaults a new CLI build adds are not mistaken for user settings, while a user or project value that differs
 * from the probe's view, or a key the probe does not report, still fails closed. Layers outside `CODEX_HOME`
 * (system and managed configuration) apply to both processes, so an equal value there is reproduced by the
 * probe rather than refused. The probe's own `-c` overrides touch only allowlisted keys, so the baseline never
 * holds a value the probe forced. `profile` passes only when unset, whatever the baseline. The names of
 * refused top-level keys (never their values) are logged for support diagnostics: at WARN when the refused set
 * differs from the last one [refusals] reported, otherwise at VERBOSE, so a persistent configuration does not
 * repeat the warning on every protected preparation.
 */
internal fun requireCodexInventoryConfig(
    effective: JsonObject,
    baseline: JsonObject,
    refusals: CodexConfigRefusals = CodexRefusalLog,
) {
    val refused = effective.filter { (key, value) ->
        val isDefault = key != "profile" && baseline[key] == value
        val isKnown = key in CodexInventoryConfigKeys ||
            (key == "windows" && isKnownWindowsConfig(value, baseline[key]))
        (key == "profile" || !isKnown) && !isUnsetConfig(value) && !isDefault
    }.keys
    if (refused.isEmpty()) return
    val names = refused.map { if (ConfigKeyName.matches(it)) it else "<unnamed>" }.sorted().toSet()
    val log = Log.tag("CodexNativeInventory")
    if (refusals.isNew(names)) {
        log.w { "Codex config keys refused by native inventory: ${names.joinToString()}" }
    } else {
        log.v { "Codex config keys refused by native inventory again: ${names.joinToString()}" }
    }
    inventoryFailure()
}

/** Remembers the last refused key set reported at WARN; safe for concurrent session preparations. */
@OptIn(ExperimentalAtomicApi::class)
internal class CodexConfigRefusals {
    private val last = AtomicReference<Set<String>?>(null)

    /** True when [names] differs from the previously reported set, which [names] then replaces. */
    fun isNew(names: Set<String>): Boolean = last.exchange(names) != names
}

/** Process-wide dedupe of refusal warnings only; it never affects the refusal itself. */
private val CodexRefusalLog = CodexConfigRefusals()

private val ConfigKeyName = Regex("[A-Za-z0-9_-]{1,64}")

private fun isUnsetConfig(value: JsonElement): Boolean = when (value) {
    JsonNull -> true
    is JsonObject -> value.isNotEmpty() && value.values.all(::isUnsetConfig)
    is JsonPrimitive, is JsonArray -> false
}

/**
 * `windows.sandbox` chooses native process isolation, not model-facing tools. The CLI validates its value;
 * protected launches still close native execution and verify the actual inventory. Do not allow the entire
 * table: future siblings may add tools and must remain unset or equal to the clean-home default.
 */
private fun isKnownWindowsConfig(value: JsonElement, baseline: JsonElement?): Boolean {
    if (value !is JsonObject) return false
    val defaults = baseline as? JsonObject
    return value.all { (key, setting) ->
        key == "sandbox" || isUnsetConfig(setting) || defaults?.get(key) == setting
    }
}

/**
 * Checks actual Responses declarations, not version numbers or echoed configuration keys. Each [declared] host
 * function (dynamic tool format: `name`, `description`, `inputSchema`) must appear exactly once with the same
 * description and parameter structure, so a same-named native replacement is refused; besides them only native
 * questions are allowed, which the host handles and refuses for tool-free turns. An absent or malformed catalog
 * is not evidence of isolation. Unknown namespaces and tool kinds fail closed even when they reuse an allowed
 * function name.
 */
internal fun validateCodexInventory(request: JsonObject, declared: List<JsonObject> = listOf(CodexInventoryMarker)) {
    val specs = declared.associateBy { it.text("name") ?: inventoryFailure() }
    if (specs.isEmpty() || specs.size != declared.size) inventoryFailure()
    val names = requestedTools(request).map { tool ->
        if (tool.text("type") != "function") inventoryFailure()
        val name = tool.text("name") ?: inventoryFailure()
        val spec = specs[name]
        if (spec == null) {
            if (name != "request_user_input") inventoryFailure()
        } else if (!matchesDeclaration(tool, spec)) {
            inventoryFailure()
        }
        name
    }
    if (!names.containsAll(specs.keys) || names.distinct().size != names.size) inventoryFailure()
}

/**
 * The CLI may normalize JSON schemas (dropped keywords, widened numeric types, a default `items` for an array
 * declared without one), so parameters are compared by structure: property names at every level, required sets,
 * and array items where the declaration has them. Descriptions must match exactly.
 */
private fun matchesDeclaration(tool: JsonObject, spec: JsonObject): Boolean =
    tool.text("description").orEmpty() == spec.text("description").orEmpty() &&
        matchesShape(tool["parameters"], spec["inputSchema"])

/** Missing, added or renamed properties and changed required sets fail; an `items` the CLI added is ignored. */
private fun matchesShape(returned: JsonElement?, declared: JsonElement?): Boolean {
    if (declared !is JsonObject || returned !is JsonObject) return declared !is JsonObject && returned !is JsonObject
    val declaredProperties = (declared["properties"] as? JsonObject).orEmpty()
    val returnedProperties = (returned["properties"] as? JsonObject).orEmpty()
    if (declaredProperties.keys != returnedProperties.keys) return false
    if (declaredProperties.any { (name, schema) -> !matchesShape(returnedProperties[name], schema) }) return false
    if (requiredNames(declared) != requiredNames(returned)) return false
    val declaredItems = declared["items"] ?: return true
    return matchesShape(returned["items"], declaredItems)
}

private fun requiredNames(schema: JsonObject): Set<String> =
    (schema["required"] as? JsonArray).orEmpty().map { it.toString() }.toSet()

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
