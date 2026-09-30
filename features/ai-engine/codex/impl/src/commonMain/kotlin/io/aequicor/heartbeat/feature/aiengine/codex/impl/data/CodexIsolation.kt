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
    "apps", "plugins", "remote_plugin", "hooks", "multi_agent", "browser_use", "browser_use_external",
    "browser_use_full_cdp_access", "computer_use", "image_generation", "worktrees", "skill_mcp_dependency_install",
)

/**
 * Empty MCP tables merge with disk configuration. Each configured server must be explicitly disabled instead.
 * Only names and effective feature booleans are consumed; credentials are never exposed or logged.
 */
internal suspend fun codexIsolationConfig(rpc: CodexRpc, cwd: String?, search: Boolean): JsonObject {
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
        put("features", buildJsonObject { CodexDisabledCapabilities.forEach { put(it, false) } })
        if (search) put("web_search", "live")
    }
}
