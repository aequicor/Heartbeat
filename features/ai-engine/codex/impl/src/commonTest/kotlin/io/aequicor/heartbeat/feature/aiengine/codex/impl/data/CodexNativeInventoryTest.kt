package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    @Test
    fun `every declared host function must appear exactly once beside native questions`() {
        val declared = setOf("read_file", "web_search")
        validateCodexInventory(request(listOf(tool("read_file"), tool("web_search"))), declared)
        validateCodexInventory(
            request(listOf(tool("read_file"), tool("web_search"), tool("request_user_input"))),
            declared,
        )
        listOf(
            request(listOf(tool("read_file"))),
            request(listOf(tool("read_file"), tool("web_search"), tool("web_search"))),
            request(listOf(tool("read_file"), tool("web_search"), marker)),
            request(listOf(tool("read_file"), tool("web_search"), tool("exec"))),
        ).forEach { candidate -> assertFailsWith<EngineException> { validateCodexInventory(candidate, declared) } }
        assertFailsWith<EngineException> { validateCodexInventory(request(listOf(marker)), emptySet()) }
    }

    @Test
    fun `effective config keys outside the reproduced surface fail closed`() {
        requireCodexInventoryConfig(
            json(
                "features" to json("shell_tool" to JsonPrimitive(false)),
                "model" to "model".json(),
                "plugins" to json("browser" to json("enabled" to JsonPrimitive(true))),
                "tools" to json("web_search" to JsonNull, "view_image" to JsonNull),
                "profile" to JsonNull,
                "experimental_use_unified_exec_tool" to JsonNull,
                "include_plan_tool" to JsonArray(emptyList()),
            ),
        )
        listOf(
            json("tools" to json("view_image" to JsonPrimitive(true))),
            json("profile" to "work".json()),
            json("experimental_use_unified_exec_tool" to JsonPrimitive(true)),
            json("include_apply_patch_tool" to JsonPrimitive(false)),
            json("unknown_future_key" to json("nested" to "value".json())),
        ).forEach { config -> assertFailsWith<EngineException> { requireCodexInventoryConfig(config) } }
    }

    private fun tool(name: String) = json("type" to "function".json(), "name" to name.json())

    private fun request(tools: List<JsonObject>, input: List<JsonObject> = emptyList()) =
        json("tools" to JsonArray(tools), "input" to JsonArray(input))
}
