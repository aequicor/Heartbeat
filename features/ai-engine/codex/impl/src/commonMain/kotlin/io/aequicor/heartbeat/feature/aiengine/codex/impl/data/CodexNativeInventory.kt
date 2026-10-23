package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The only dynamic declaration supplied to an offline inventory probe. It is never executed. */
internal const val CODEX_INVENTORY_TOOL = "heartbeat_inventory_probe"

/**
 * Checks actual Responses declarations, not version numbers or echoed configuration keys. An absent or malformed
 * catalog is not evidence of isolation. Native questions are handled by the host and refused for tool-free turns.
 * Unknown namespaces and tool kinds fail closed even when they reuse an allowed function name.
 */
internal fun validateCodexInventory(request: JsonObject) {
    val tools = mutableListOf<JsonObject>()
    request["tools"]?.let { tools += inventoryTools(it as? JsonArray ?: inventoryFailure()) }
    val input = request["input"] as? JsonArray ?: inventoryFailure()
    input.forEach { element ->
        val item = element as? JsonObject ?: inventoryFailure()
        if (item.text("type") == "additional_tools") {
            tools += inventoryTools(item["tools"] as? JsonArray ?: inventoryFailure())
        }
    }
    val names = tools.map { tool ->
        if (tool.text("type") != "function") inventoryFailure()
        val name = tool.text("name") ?: inventoryFailure()
        if (name != CODEX_INVENTORY_TOOL && name != "request_user_input") inventoryFailure()
        name
    }
    if (names.count { it == CODEX_INVENTORY_TOOL } != 1 || names.distinct().size != names.size) inventoryFailure()
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
