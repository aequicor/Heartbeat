package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexModelCatalogTest {
    @Test
    fun `normalization disables model native surfaces while preserving inference metadata`() {
        val original = json(
            "slug" to "new-model".json(),
            "tool_mode" to "code_mode_only".json(),
            "multi_agent_version" to "v2".json(),
            "shell_type" to "unified_exec".json(),
            "apply_patch_tool_type" to "freeform".json(),
            "supports_search_tool" to JsonPrimitive(true),
            "base_instructions" to "model instructions".json(),
            "context_window" to JsonPrimitive(123456),
            "unknown_metadata" to json("preserve" to JsonPrimitive(true)),
        )
        val normalized = normalizeCodexModels(json("models" to JsonArray(listOf(original))))
        val model = (normalized["models"] as JsonArray).single() as JsonObject
        for (key in listOf("slug", "base_instructions", "context_window", "unknown_metadata")) {
            assertEquals(original[key], model[key])
        }
        assertEquals("direct", model.text("tool_mode"))
        assertEquals("disabled", model.text("shell_type"))
        assertEquals(JsonNull, model["multi_agent_version"])
        assertEquals(JsonNull, model["apply_patch_tool_type"])
        assertEquals(JsonPrimitive(false), model["supports_search_tool"])
        assertEquals(JsonPrimitive(true), model["node_repl_disabled"])
        assertEquals(JsonArray(emptyList()), model["experimental_supported_tools"])
    }

    @Test
    fun `unknown model catalog schemas are rejected`() {
        for (source in listOf(
            json(),
            json("models" to JsonArray(emptyList())),
            json("models" to JsonArray(listOf(json()))),
        )) {
            assertFailsWith<EngineException> { normalizeCodexModels(source) }
        }
    }
}
