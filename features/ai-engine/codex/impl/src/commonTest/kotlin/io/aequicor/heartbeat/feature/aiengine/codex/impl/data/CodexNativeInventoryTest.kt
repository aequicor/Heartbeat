package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CodexNativeInventoryTest {
    private val marker = tool(CODEX_INVENTORY_TOOL)

    @Test
    fun `actual safe inventory is accepted without any version information`() {
        validateCodexInventory(request(listOf(marker)))
        validateCodexInventory(request(listOf(marker, tool("request_user_input"))))
    }

    @Test
    fun `responses lite namespace declarations are validated including hidden native tools`() {
        val safe = json(
            "type" to "namespace".json(),
            "name" to "functions".json(),
            "tools" to JsonArray(listOf(marker)),
        )
        validateCodexInventory(
            request(emptyList(), listOf(json("type" to "additional_tools".json(), "tools" to JsonArray(listOf(safe))))),
        )
        val unsafe = JsonObject(safe + ("tools" to JsonArray(listOf(marker, tool("exec")))))
        assertFailsWith<EngineException> {
            validateCodexInventory(
                request(
                    emptyList(),
                    listOf(json("type" to "additional_tools".json(), "tools" to JsonArray(listOf(unsafe)))),
                ),
            )
        }
        assertFailsWith<EngineException> {
            validateCodexInventory(request(listOf(JsonObject(safe + ("name" to "collaboration".json())))))
        }
    }

    @Test
    fun `unknown native tools fail even if they reuse an allowed name or are nested`() {
        listOf(
            tool("new_native_tool"),
            json("type" to "custom".json(), "name" to CODEX_INVENTORY_TOOL.json()),
            json("type" to "web_search".json()),
            json("type" to "namespace".json(), "name" to "functions".json(), "tools" to JsonArray(listOf(marker))),
            json(),
        ).forEach { extra ->
            assertFailsWith<EngineException> { validateCodexInventory(request(listOf(marker, extra))) }
        }
    }

    @Test
    fun `missing malformed duplicate and deferred catalogs cannot prove isolation`() {
        listOf(
            json(),
            json("tools" to JsonNull, "input" to JsonArray(emptyList())),
            request(emptyList()),
            request(listOf(marker, marker)),
            json("tools" to JsonArray(listOf(marker))),
            request(listOf(marker), listOf(json("type" to "additional_tools".json()))),
        ).forEach { candidate -> assertFailsWith<EngineException> { validateCodexInventory(candidate) } }
    }

    private fun tool(name: String) = json("type" to "function".json(), "name" to name.json())

    private fun request(tools: List<JsonObject>, input: List<JsonObject> = emptyList()) =
        json("tools" to JsonArray(tools), "input" to JsonArray(input))
}
