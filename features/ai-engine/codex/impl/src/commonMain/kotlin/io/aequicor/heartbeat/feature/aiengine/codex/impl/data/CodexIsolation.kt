package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native integrations can act outside the command sandbox, so hosted sessions disable them. */
internal val CodexDisabledCapabilities: List<String> = listOf(
    "apps", "plugins", "remote_plugin", "hooks", "browser_use", "browser_use_external",
    "browser_use_full_cdp_access", "computer_use", "image_generation", "worktrees", "skill_mcp_dependency_install",
)

/**
 * Distinguishes native isolation from the host's authorized execution path. Without this scope, Codex can
 * interpret its read-only permission context as a ban on all edits and refuse before calling a hosted tool.
 * Supplied on start and resume only when hosted declarations exist; the dispatcher remains the authority for
 * the current turn's trust level, so these thread-wide instructions never cache an approval decision.
 */
internal fun codexHostedInstructions(workflow: String): String = """
    Heartbeat hosted tools and permissions:
    The read-only sandbox applies only to Codex's built-in tools. Native network restrictions and
    approvalPolicy=never also apply only to native execution. Heartbeat's declared hosted tools are a separate,
    authorized execution path: hosted tools can edit workspace files and run commands within their declared scope.
    Use the available hosted tools for project changes, Git operations, builds, and other actions they support.
    Heartbeat applies the user's current approval mode to each hosted call: Ask requests confirmation for
    mutations; AutoEdits automatically approves file edits; Full automatically approves edits and commands.
    Call the appropriate hosted tool directly for the user's task; Heartbeat asks for confirmation when required.
    Do not treat the native read-only sandbox as evidence that hosted edits are forbidden or request a new
    writable session solely because of it. Report an access limitation if the hosted tool actually returns one.
    Respect hosted tool refusals and scope limits; do not bypass them through native tools or sandbox changes.
    """.trimIndent().let { permissions ->
    listOf(permissions, workflow).filter(String::isNotBlank).joinToString("\n\n")
}

/**
 * Empty MCP tables merge with disk configuration. Each configured server must be explicitly disabled instead.
 * Only names and effective feature booleans are consumed; credentials are never exposed or logged.
 */
internal suspend fun codexIsolationConfig(
    rpc: CodexRpc,
    cwd: String?,
    search: Boolean,
    questions: Boolean,
    subagents: Boolean = false,
): JsonObject {
    val effective = rpc.request(
        "config/read",
        buildJsonObject {
            put("includeLayers", false)
            put("cwd", cwd?.json() ?: JsonNull)
        },
    ).obj("config")
    val features = effective["features"] as? JsonObject
    // Config requirements can override CLI flags. A conflicting managed configuration fails closed.
    if (CodexDisabledCapabilities.any { features?.get(it) != JsonPrimitive(false) }) {
        fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
    }
    val servers = effective["mcp_servers"] as? JsonObject
    return buildJsonObject {
        put(
            "mcp_servers",
            buildJsonObject {
                servers?.keys?.forEach { name -> put(name, buildJsonObject { put("enabled", false) }) }
            },
        )
        put(
            "features",
            buildJsonObject {
                CodexDisabledCapabilities.forEach { put(it, false) }
                put("multi_agent", subagents)
                // Default-mode questions need an explicit opt-in and a host capable of collecting answers.
                put("default_mode_request_user_input", questions)
            },
        )
        if (search) put("web_search", "live")
    }
}
