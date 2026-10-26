package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexNativeInventoryTest {
    private val marker = responseTool(CodexInventoryMarker)

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
        val declared = listOf(spec("read_file"), spec("web_search"))
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
        assertFailsWith<EngineException> { validateCodexInventory(request(listOf(marker)), emptyList()) }
        assertFailsWith<EngineException> {
            validateCodexInventory(request(listOf(tool("read_file"))), listOf(spec("read_file"), spec("read_file")))
        }
    }

    @Test
    fun `same named native replacement with another description or schema is refused`() {
        val declared = spec(
            "read_file",
            json(
                "type" to "object".json(),
                "properties" to json(
                    "path" to json("type" to "string".json(), "minLength" to JsonPrimitive(1)),
                    "range" to json(
                        "type" to "array".json(),
                        "items" to json("type" to "integer".json()),
                    ),
                ),
                "required" to JsonArray(listOf("path".json())),
                "additionalProperties" to JsonPrimitive(false),
            ),
        )
        val faithful = responseTool(declared)
        // Normalization by the CLI (dropped keywords, widened numeric types) keeps the declared structure.
        val widened = json(
            "type" to "object".json(),
            "properties" to json(
                "path" to json("type" to "string".json()),
                "range" to json("type" to "array".json(), "items" to json("type" to "number".json())),
            ),
            "required" to JsonArray(listOf("path".json())),
        )
        val normalized = JsonObject(faithful + ("parameters" to widened))
        validateCodexInventory(request(listOf(faithful)), listOf(declared))
        validateCodexInventory(request(listOf(normalized)), listOf(declared))
        val parameters = faithful.obj("parameters")
        val optional = JsonObject(parameters + ("required" to JsonArray(emptyList())))
        val extended = JsonObject(
            parameters + ("properties" to JsonObject(parameters.obj("properties") + ("cmd" to json()))),
        )
        listOf(
            JsonObject(faithful + ("description" to "Native file reader".json())),
            JsonObject(faithful - "description"),
            JsonObject(faithful - "parameters"),
            JsonObject(faithful + ("parameters" to optional)),
            JsonObject(faithful + ("parameters" to extended)),
        ).forEach { replacement ->
            assertFailsWith<EngineException> { validateCodexInventory(request(listOf(replacement)), listOf(declared)) }
        }
    }

    @Test
    fun `an items the CLI adds to an array declared without one is accepted while property changes still fail`() {
        val declared = spec(
            "tag_files",
            json(
                "type" to "object".json(),
                "properties" to json("tags" to json("type" to "array".json())),
                "required" to JsonArray(listOf("tags".json())),
            ),
        )
        val faithful = responseTool(declared)
        val parameters = faithful.obj("parameters")
        val withProperties = { properties: JsonObject ->
            JsonObject(faithful + ("parameters" to JsonObject(parameters + ("properties" to properties))))
        }
        val sanitized = withProperties(json("tags" to json("type" to "array".json(), "items" to json())))
        validateCodexInventory(request(listOf(sanitized)), listOf(declared))
        listOf(
            json("other" to json("type" to "array".json())),
            json(),
            json("tags" to json("type" to "array".json()), "extra" to json()),
        ).forEach { properties ->
            val changed = withProperties(properties)
            assertFailsWith<EngineException> { validateCodexInventory(request(listOf(changed)), listOf(declared)) }
        }
        // Declared items stay compared: dropping them or renaming their properties is a different declaration.
        val typed = spec(
            "tag_files",
            json(
                "type" to "object".json(),
                "properties" to json(
                    "tags" to json(
                        "type" to "array".json(),
                        "items" to json("type" to "object".json(), "properties" to json("id" to json())),
                    ),
                ),
            ),
        )
        listOf(
            json("tags" to json("type" to "array".json())),
            json("tags" to json("type" to "array".json(), "items" to json("properties" to json("name" to json())))),
        ).forEach { properties ->
            val changed = JsonObject(
                responseTool(typed) + ("parameters" to json("type" to "object".json(), "properties" to properties)),
            )
            assertFailsWith<EngineException> { validateCodexInventory(request(listOf(changed)), listOf(typed)) }
        }
    }

    @Test
    fun `a persistent refusal warns once and again only when the refused key set changes`() {
        val refusals = CodexConfigRefusals()
        val tools = json("tools" to json("view_image" to JsonPrimitive(true)))
        repeat(2) { assertFailsWith<EngineException> { requireCodexInventoryConfig(tools, json(), refusals) } }
        assertFalse(refusals.isNew(setOf("tools")))
        assertTrue(refusals.isNew(setOf("profile", "tools")))
        assertFalse(refusals.isNew(setOf("profile", "tools")))
        assertTrue(refusals.isNew(setOf("tools")))
    }

    @Test
    fun `presentation auth logging and trust keys written by the Codex TUI are accepted`() {
        requireCodexInventoryConfig(
            json(
                "personality" to "pragmatic".json(),
                "suppress_unstable_features_warning" to JsonPrimitive(true),
                "project_root_markers" to JsonArray(listOf(".git".json())),
                "tool_output_token_limit" to JsonPrimitive(10_000),
                "preferred_auth_method" to "chatgpt".json(),
                "approvals_reviewer" to "user".json(),
                "zsh_path" to "/bin/zsh".json(),
                "log_dir" to "/tmp/codex-logs".json(),
                "model_reasoning_effort" to "high".json(),
                "model_reasoning_summary" to "auto".json(),
                "model_verbosity" to "low".json(),
                "hide_agent_reasoning" to JsonPrimitive(true),
                "show_raw_agent_reasoning" to JsonPrimitive(false),
                "file_opener" to "vscode".json(),
                "notify" to JsonArray(listOf("notify-send".json())),
                "windows_wsl_setup_acknowledged" to JsonPrimitive(true),
                "projects" to json("/work" to json("trust_level" to "trusted".json())),
                "notice" to json("hide_full_access_warning" to JsonPrimitive(true)),
                "tui" to json("notifications" to JsonPrimitive(true)),
                "history" to json("persistence" to "save-all".json()),
                "analytics" to json("enabled" to JsonPrimitive(false)),
                "feedback" to json("enabled" to JsonPrimitive(false)),
                "otel" to json("exporter" to "none".json()),
            ),
            baseline = json(),
        )
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
                // Allowlisted keys stay lenient, including empty declarations.
                "project_doc_fallback_filenames" to JsonArray(emptyList()),
                "sandbox_workspace_write" to json(),
            ),
            baseline = json(),
        )
        listOf(
            json("tools" to json("view_image" to JsonPrimitive(true))),
            // An explicitly declared empty table or list on an unknown key is a setting, not an absent field.
            json("tools" to json()),
            json("include_plan_tool" to JsonArray(emptyList())),
            json("unknown_future_key" to json("nested" to json())),
            json("profile" to "work".json()),
            json("experimental_use_unified_exec_tool" to JsonPrimitive(true)),
            json("include_apply_patch_tool" to JsonPrimitive(false)),
            json("unknown_future_key" to json("nested" to "value".json())),
        ).forEach { config -> assertFailsWith<EngineException> { requireCodexInventoryConfig(config, json()) } }
    }

    @Test
    fun `defaults of the same executable are accepted while changed or new values fail closed`() {
        // Clean-home config/read of a 0.163 build: keys added after the allowlist was written.
        val baseline = json(
            "allow_login_shell" to JsonPrimitive(true),
            "background_terminal_max_timeout" to JsonPrimitive(300_000),
            "include_apps_instructions" to JsonPrimitive(true),
            "include_environment_context" to JsonPrimitive(true),
            "tools" to json(),
            "shell_policy" to json("mode" to "default".json(), "extra" to JsonArray(listOf("a".json()))),
            "profile" to "baseline".json(),
        )
        requireCodexInventoryConfig(
            json(
                "allow_login_shell" to JsonPrimitive(true),
                "background_terminal_max_timeout" to JsonPrimitive(300_000),
                "include_apps_instructions" to JsonPrimitive(true),
                "include_environment_context" to JsonPrimitive(true),
                // An empty table is strict only when it is not the default itself.
                "tools" to json(),
                "shell_policy" to json("extra" to JsonArray(listOf("a".json())), "mode" to "default".json()),
                "model" to "model".json(),
            ),
            baseline,
        )
        listOf(
            json("allow_login_shell" to JsonPrimitive(false)),
            json("background_terminal_max_timeout" to JsonPrimitive(1)),
            json("include_environment_context" to JsonPrimitive(false)),
            json("tools" to json("view_image" to JsonPrimitive(true))),
            json("shell_policy" to json("mode" to "default".json(), "extra" to JsonArray(emptyList()))),
            json("shell_policy" to json("mode" to "default".json())),
            // Unknown non-default keys, including an explicit empty table, still fail closed.
            json("unknown_future_key" to JsonPrimitive(true)),
            json("unknown_future_key" to json()),
            // An active profile is refused even if the baseline reports the same one.
            json("profile" to "baseline".json()),
        ).forEach { config -> assertFailsWith<EngineException> { requireCodexInventoryConfig(config, baseline) } }
    }

    private fun spec(name: String, schema: JsonObject = emptySchema) = json(
        "type" to "function".json(),
        "name" to name.json(),
        "description" to "Host $name".json(),
        "inputSchema" to schema,
    )

    private val emptySchema = json("type" to "object".json(), "properties" to json())

    /** The Responses form the CLI sends for a dynamic declaration. */
    private fun responseTool(spec: JsonObject) = json(
        "type" to "function".json(),
        "name" to spec.getValue("name"),
        "description" to spec.getValue("description"),
        "strict" to JsonPrimitive(false),
        "parameters" to spec.getValue("inputSchema"),
    )

    private fun tool(name: String) = responseTool(spec(name))

    private fun request(tools: List<JsonObject>, input: List<JsonObject> = emptyList()) =
        json("tools" to JsonArray(tools), "input" to JsonArray(input))
}
