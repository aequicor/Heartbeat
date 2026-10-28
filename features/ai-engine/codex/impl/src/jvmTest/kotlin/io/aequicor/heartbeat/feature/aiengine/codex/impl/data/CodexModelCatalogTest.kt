package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigOverride
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexModelCatalogTest {
    @Test
    fun `account export retains the captured home environment and overrides and rejects absent models`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("heartbeat-codex-account-catalog-")
        try {
            val record = directory.resolve("export.json")
            val executable = FakeCodexInventoryCli.install(
                directory.toFile(),
                FakeInventoryScenario.AccountCatalog,
                record.toFile(),
            )
            val home = Files.createDirectory(directory.resolve("account-home"))
            val launch = CodexLaunch(
                executable.absolutePath,
                InstallSource.Custom,
                true,
                home.toString(),
                listOf(ConfigOverride("model_catalog_json", "\"/account/models.json\"")),
            )
            val environment = mapOf("CODEX_HOME" to home.toString(), "HEARTBEAT_TEST_ACCOUNT" to "selected")
            val dispatchers = object : DispatcherProvider {
                override val main = Dispatchers.IO
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
            }
            runBlocking {
                CodexModelCatalog.create(launch, environment, dispatchers).use { catalog ->
                    catalog.requireModel("${FakeCodexInventoryCli.MODEL}-account")
                    assertFailsWith<EngineException> { catalog.requireModel("unknown-model") }
                    val exported = Json.parseToJsonElement(Files.readString(record)) as JsonObject
                    assertEquals(home.toString(), exported.text("home"))
                    assertEquals("selected", exported.text("account"))
                    assertEquals(
                        JsonArray(codexModelCatalogCommand(launch).drop(1).map { it.json() }),
                        exported["args"],
                    )
                    val source = Json.parseToJsonElement(Files.readString(catalog.path)) as JsonObject
                    assertEquals("direct", ((source["models"] as JsonArray).single() as JsonObject).text("tool_mode"))
                }
            }
        } finally {
            deleteTree(directory)
        }
    }

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

    @Test
    fun `closing the catalog twice removes it once without failing`() {
        withCatalog { catalog ->
            val root = catalog.path.parent
            catalog.requireModel(FakeCodexInventoryCli.MODEL)
            catalog.close()
            assertFalse(Files.exists(root))
            catalog.close()
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `unchecked walk failure during cleanup is logged instead of thrown and is not retried`() {
        withCatalog { catalog ->
            val root = catalog.path.parent
            val locked = Files.createDirectory(root.resolve("locked"))
            Files.createFile(locked.resolve("entry"))
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
            try {
                // A privileged user can still read the directory, so the walk would not fail.
                assumeFalse(Files.isReadable(locked))
                catalog.close()
                assertTrue(Files.exists(locked))
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
            }
            // Idempotent close does not repeat the cleanup, even after the failure.
            catalog.close()
            assertTrue(Files.exists(catalog.path))
            deleteTree(root)
        }
    }

    private fun withCatalog(block: (CodexModelCatalog) -> Unit) {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("heartbeat-codex-fake-catalog-")
        try {
            val executable = FakeCodexInventoryCli.install(
                directory.toFile(),
                FakeInventoryScenario.Faithful,
                directory.resolve("unused").toFile(),
            )
            val dispatchers = object : DispatcherProvider {
                override val main = Dispatchers.IO
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
            }
            val catalog = runBlocking { CodexModelCatalog.create(executable.absolutePath, dispatchers) }
            block(catalog)
        } finally {
            deleteTree(directory)
        }
    }
}
